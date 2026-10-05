"""Disposable Microplus owner origin for the PostgreSQL delivery integration test."""

import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import copy
from pathlib import Path
import sqlite3
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'scripts'), str(ROOT / 'tests')]
from cmas_microplus_snapshot_observations import load_attempt_evidence
from owner_decision_export_adapter import (build_verified_microplus_attempt_export,
                                           register_verified_export)
from owner_evidence_origin import make_server
from test_cmas_microplus_snapshot_observations import fixture


def prepare(root):
    root.mkdir(parents=True, exist_ok=True)
    name, record_id, row = fixture(root)
    alternate_url = row['citation']['url'].replace('/17/', '/16/')
    alternate_bytes = json.dumps([{'unrelated': True}, row['raw_fields']]).encode()
    alternate_hash = hashlib.sha256(alternate_bytes).hexdigest()
    (root / 'unit-16-results.json').write_bytes(alternate_bytes)
    (root / 'unit-16-results.receipt.json').write_text(json.dumps({
        'status': 200, 'sha256': alternate_hash, 'bytes': len(alternate_bytes),
        'final_url': alternate_url}))
    row['alternate_citations'] = [{
        'url': alternate_url, 'source_sha256': alternate_hash, 'json_pointer': '/1'}]
    packet = json.loads((root / 'packet.json').read_text())
    packet['positions'][0] = row
    packet['sources'].append(dict(packet['sources'][0], id='sha256:' + alternate_hash,
                                  unit_id=16, url=alternate_url, sha256=alternate_hash,
                                  bytes=len(alternate_bytes), row_count=2))
    (root / 'packet.json').write_text(json.dumps(packet))
    with sqlite3.connect(root / 'snapshot.sqlite') as db:
        db.execute('UPDATE records SET raw_json=?', (json.dumps(row),))
    manifest = json.loads((root / 'manifest.json').read_text())
    manifest['snapshot_sha256'] = hashlib.sha256((root / 'snapshot.sqlite').read_bytes()).hexdigest()
    manifest['inputs'][name]['sha256'] = hashlib.sha256((root / 'packet.json').read_bytes()).hexdigest()
    (root / 'manifest.json').write_text(json.dumps(manifest))
    attempt = load_attempt_evidence(root, [name], record_ids=[record_id])
    return {'source_name': name, 'record_id': record_id,
            'snapshot_sha256': attempt['snapshot_sha256'], 'evidence': attempt['evidence']}


def serve(metadata_path):
    metadata = json.loads(Path(metadata_path).read_text())
    root = Path(metadata_path).parent
    snapshot = root / 'snapshot'
    env = {'OWNER_EVIDENCE_GATEWAY_SECRET': 'synthetic-private-gateway-secret',
           'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
           'OWNER_EVIDENCE_SNAPSHOT_SHA256': metadata['snapshot_sha256'],
           'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
           'OWNER_EVIDENCE_DECISION_DB': str(root / 'decisions.sqlite'),
           'OWNER_EVIDENCE_IMPORT_TOKEN': 'synthetic-separate-machine-import-token',
           'OWNER_EVIDENCE_IMPORT_CLIENT_ID': 'synthetic1.access'}
    origin = make_server(snapshot, env)
    store = origin.decisions
    store.bind_verified_snapshot(snapshot, expected_revision=store.revision,
                                 idempotency_key='attempt-view-bind',
                                 attempt_record_ids=[metadata['record_id']])
    envelope = build_verified_microplus_attempt_export(
        store, snapshot, [metadata['source_name']], metadata['record_id'],
        decision_id=metadata['decision_id'],
        reconciliation_run_revision=metadata['reconciliation_run_revision'],
        reconciliation_event_id=metadata['reconciliation_event_id'],
        reconciliation_flow_path=metadata['reconciliation_flow_path'])
    register_verified_export(store, snapshot, envelope,
                             reconciliation_flow_path=metadata['reconciliation_flow_path'])
    origin_thread = threading.Thread(target=origin.serve_forever, daemon=True)
    origin_thread.start()

    class ImportProxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def relay(self):
            if self.headers.get('CF-Access-Client-Id') != 'synthetic.access' or self.headers.get(
                    'CF-Access-Client-Secret') != 'synthetic-service-secret':
                self.send_error(403)
                return
            body = (self.rfile.read(int(self.headers['Content-Length']))
                    if self.command == 'POST' else None)
            connection = http.client.HTTPConnection('127.0.0.1', origin.server_port, timeout=10)
            connection.request(self.command, self.path, body=body, headers={
                'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
                'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                'X-Freediving-Owner-Machine': env['OWNER_EVIDENCE_IMPORT_CLIENT_ID'],
                'X-Freediving-Import-Token': self.headers.get('X-Freediving-Import-Token', ''),
                'Content-Type': 'application/json'})
            response = connection.getresponse()
            payload = response.read()
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            connection.close()

        do_GET = relay
        do_POST = relay

    proxy = ThreadingHTTPServer(('127.0.0.1', 0), ImportProxy)
    proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    proxy_thread.start()
    print(json.dumps({'import_port': proxy.server_port,
                      'binding_revision': store._binding()['revision'],
                      'canonical_binding': envelope['proposals'][0]['canonical_binding']}),
          flush=True)
    try:
        for command in sys.stdin:
            command = command.strip()
            if command in ('approve', 'reverse'):
                result = store.act(metadata['decision_id'], action=command,
                                   expected_revision=store.revision,
                                   idempotency_key='test-' + command)
                print(json.dumps({'result': result, 'event': store.human_events()['events'][-1]}),
                      flush=True)
            elif command == 'checkpoints':
                print(json.dumps({'checkpoints': store.delivery_checkpoints(),
                                  'status': store.inspect(metadata['decision_id'])['effective_status']}),
                      flush=True)
            elif command == 'bad-bindings':
                rejected = {}
                for name, changes in {
                    'stale_event': {'reconciliation_event_id': 'forged-event'},
                    'stale_run': {'reconciliation_run_revision':
                                  metadata['reconciliation_run_revision'] + 1},
                }.items():
                    values = dict(metadata, **changes)
                    try:
                        build_verified_microplus_attempt_export(
                            store, snapshot, [values['source_name']], values['record_id'],
                            decision_id=values['decision_id'],
                            reconciliation_run_revision=values['reconciliation_run_revision'],
                            reconciliation_event_id=values['reconciliation_event_id'],
                            reconciliation_flow_path=values['reconciliation_flow_path'])
                    except ValueError:
                        rejected[name] = True
                    else:
                        rejected[name] = False
                forged = copy.deepcopy(envelope)
                proposal = forged['proposals'][0]
                proposal['id'] = 'forged-decision'
                proposal['canonical_binding']['decision_id'] = 'forged-decision'
                forged['store_revision'] = store.revision
                try:
                    register_verified_export(
                        store, snapshot, forged,
                        reconciliation_flow_path=metadata['reconciliation_flow_path'])
                except ValueError:
                    rejected['forged_export'] = True
                else:
                    rejected['forged_export'] = False
                print(json.dumps(rejected), flush=True)
            elif command == 'stop':
                break
    finally:
        proxy.shutdown()
        proxy_thread.join(timeout=2)
        proxy.server_close()
        origin.shutdown()
        origin_thread.join(timeout=2)
        origin.server_close()


if __name__ == '__main__':
    if sys.argv[1] == 'prepare':
        print(json.dumps(prepare(Path(sys.argv[2]))))
    elif sys.argv[1] == 'serve':
        serve(sys.argv[2])
