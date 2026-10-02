"""Synthetic browser-to-signed-feed trace through the real Worker and origin."""

import hashlib
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'tests'))
from owner_evidence_origin import make_server
from test_owner_decision_store import proposal
from test_unified_evidence_query import snapshot
from owner_decision_export_adapter import register_verified_export
from unified_evidence_query import SnapshotQuery


class OwnerDecisionSyntheticPathTest(unittest.TestCase):
    def test_browser_action_reaches_signed_feed_and_preserves_correction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot_dir = snapshot(root)
            digest = json.loads((snapshot_dir / 'manifest.json').read_text())['snapshot_sha256']
            env = {
                'OWNER_EVIDENCE_GATEWAY_SECRET': 'synthetic-private-gateway-secret',
                'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
                'OWNER_EVIDENCE_SNAPSHOT_SHA256': digest,
                'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
                'OWNER_EVIDENCE_DECISION_DB': str(root / 'decisions.sqlite'),
                'OWNER_EVIDENCE_IMPORT_TOKEN': 'synthetic-separate-machine-import-token',
                'OWNER_EVIDENCE_IMPORT_CLIENT_ID': 'synthetic1.access',
            }
            server = make_server(snapshot_dir, env)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                evidence = server.query.browse(kind='candidate_position', limit=1)['records'][0]['record_id']
                for ident, dependencies in [('root', ()), ('child', ('root',)), ('corrected', ())]:
                    server.decisions.register(
                        digest,
                        proposal(ident, evidence=evidence, depends_on=dependencies,
                                 status='automatic_approved'),
                        idempotency_key='synthetic-register-' + ident,
                    )
                command = ['node', str(ROOT / 'tests' / 'owner_decision_synthetic_browser.mjs'),
                           str(server.server_port)]
                result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True,
                                        timeout=45)
                self.assertEqual(result.returncode, 0, result.stderr)
                trace = json.loads(result.stdout)
                self.assertEqual(trace['rejected'], {
                    'expired_access': 403,
                    'foreign_origin': 403,
                    'stale_revision': 409,
                    'browser_event_feed': 403,
                })
                self.assertEqual(trace['actions'], {'reverse': 200, 'retry': 200,
                                                    'correct': 200})
                self.assertEqual(trace['inspection']['root'], 'reversed')
                self.assertEqual(trace['inspection']['child'], 'invalidated')
                self.assertEqual(trace['inspection']['corrected'], 'human_corrected')
                self.assertEqual(trace['inspection']['correction']['action'], 'two')
                events = json.loads(trace['feed']['payload_json'])['events']
                self.assertEqual(len(events), 2)
                self.assertEqual([item['action'] for item in events], ['reverse', 'correct'])
                self.assertEqual(len({item['id'] for item in events}), 2)
                self.assertEqual(server.decisions.revision, trace['revision'])
                self.assertEqual(server.decisions.inspect('corrected')['status'], 'human_corrected')
                self.assertEqual(server.decisions.projection()['overlay_decision_counts_by_group'].get('event:one'), 1)
                self.assertEqual(trace['feed']['signature'], hmac.new(
                    env['OWNER_EVIDENCE_IMPORT_TOKEN'].encode(),
                    trace['feed']['payload_json'].encode(), hashlib.sha256).hexdigest())
            finally:
                server.shutdown()
                thread.join(timeout=2)
                server.server_close()

    def test_canonical_projection_stays_unavailable_without_live_reader(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot_dir = snapshot(root)
            digest = json.loads((snapshot_dir / 'manifest.json').read_text())['snapshot_sha256']
            env = {'OWNER_EVIDENCE_GATEWAY_SECRET': 'synthetic-private-gateway-secret',
                   'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
                   'OWNER_EVIDENCE_SNAPSHOT_SHA256': digest,
                   'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com'}
            server = make_server(snapshot_dir, env)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
                connection.request('GET', '/owner-evidence/api/canonical-projection', headers={
                    'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
                    'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                    'X-Freediving-Owner-Email': 'owner@example.com'})
                response = connection.getresponse()
                self.assertEqual(response.status, 503)
                response.read()
                connection.close()
            finally:
                server.shutdown()
                thread.join(timeout=2)
                server.server_close()


def serve_integrated(metadata_path):
    """Keep one verified synthetic source, owner store and canonical read alive."""
    metadata = json.loads(Path(metadata_path).read_text())
    root = Path(metadata_path).parent
    revisions = metadata['observation_revisions']
    source_sha = revisions[0]['source_sha256']
    assert all(revision['source_sha256'] == source_sha for revision in revisions)
    packet = root / 'synthetic-source.json'
    packet.write_text(json.dumps({
        'schema': 'synthetic/v1', 'source': {'sha256': source_sha},
        'event_title_calendar': 'Synthetic identity review',
        'positions': [{
            'id': f'synthetic-source-row-{index}',
            'source_sha256': source_sha,
            'artifact_sha256': revision['artifact_sha256'],
            'parser_version': revision['parser_version'],
            'observation_refs': [revision],
            'citation': {'page': 1, 'line': index + 1},
            'raw_fields': {'Name': 'Éxample'},
        } for index, revision in enumerate(revisions)],
    }))
    snapshot_dir = root / 'snapshot'
    subprocess.run([sys.executable, str(ROOT / 'scripts' / 'unified_evidence_snapshot.py'),
                    'build', '--cutoff', '2026-10-02T00:00:00Z',
                    '--input', f'synthetic={packet}', '--output-dir', str(snapshot_dir)],
                   check=True, capture_output=True, text=True)
    with SnapshotQuery(snapshot_dir) as query:
        records = query.browse(kind='candidate_position')['records']
        ids = {query.detail(record['record_id'])['raw']['id']: record['record_id']
               for record in records}
        digest = query.manifest['snapshot_sha256']
    binding = {
        'decision_id': metadata['decision_id'], 'reconciliation_run_revision': 1,
        'reconciliation_event_id': 'original-flow',
        'observation_revisions': revisions,
        'evidence_bindings': [
            {'evidence_id': f'synthetic-source-row-{index}',
             'snapshot_record_id': ids[f'synthetic-source-row-{index}'],
             'observation_revision': revision}
            for index, revision in enumerate(revisions)],
    }
    evidence = [
        {'id': item['snapshot_record_id'],
         'citation': {'evidence_id': item['evidence_id'],
                      'source_citation': {'source-sha256': source_sha,
                                          'page': 1, 'line': index + 1},
                      'observation_revision': item['observation_revision']},
         'version': item['observation_revision']}
        for index, item in enumerate(binding['evidence_bindings'])]
    proposal = {
        'id': metadata['decision_id'], 'type': 'identity',
        'subject_id': metadata['decision_id'], 'source_name': 'Synthetic source',
        'original': {'athlete_names': ['Éxample', 'Éxample']},
        'proposed': {'action': 'same_person'}, 'selected_option': 'same_person',
        'competing_options': ['different_person', 'unknown'],
        'evidence': evidence, 'supporting_evidence': ['exact synthetic source'],
        'conflicting_evidence': [], 'depends_on': [], 'groups': ['synthetic-event'],
        'provider_confidence': None, 'score': None,
        'rule_version': 'synthetic/1', 'model_version': None,
        'policy_version': 'synthetic/1', 'status': 'pending',
        'canonical_binding': binding,
    }
    env = {
        'OWNER_EVIDENCE_GATEWAY_SECRET': 'synthetic-private-gateway-secret',
        'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
        'OWNER_EVIDENCE_SNAPSHOT_SHA256': digest,
        'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
        'OWNER_EVIDENCE_DECISION_DB': str(root / 'decisions.sqlite'),
        'OWNER_EVIDENCE_IMPORT_TOKEN': 'synthetic-separate-machine-import-token',
        'OWNER_EVIDENCE_IMPORT_CLIENT_ID': 'synthetic1.access',
    }

    def canonical_reader():
        command = ['clojure', '-Sdeps', '{:paths ["src" "resources" "test"]}',
                   '-M', '-m', 'freediving.owner-decision-cli-path-test', 'projection']
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True,
                                env={**__import__('os').environ,
                                     'FREEDIVING_TEST_URL': metadata['app_url']}, timeout=30)
        if result.returncode:
            (root / 'canonical-error.txt').write_text(result.stderr)
            raise RuntimeError(result.stderr)
        return json.loads(result.stdout)

    origin = make_server(snapshot_dir, env, canonical_reader=canonical_reader)
    register_verified_export(origin.decisions, snapshot_dir, {
        'snapshot_sha256': digest,
        'binding_revision': origin.decisions._binding()['revision'],
        'reconciliation_run_revision': 1, 'proposals': [proposal],
    })
    origin_thread = threading.Thread(target=origin.serve_forever, daemon=True)
    origin_thread.start()

    class ImportProxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if not self.path.startswith('/owner-evidence/api/decision-events?after_revision='):
                self.send_error(404)
                return
            if (self.headers.get('CF-Access-Client-Id') != 'synthetic.access' or
                    self.headers.get('CF-Access-Client-Secret') != 'synthetic-service-secret'):
                self.send_error(403)
                return
            connection = http.client.HTTPConnection('127.0.0.1', origin.server_port, timeout=10)
            connection.request('GET', self.path, headers={
                'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
                'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                'X-Freediving-Owner-Machine': env['OWNER_EVIDENCE_IMPORT_CLIENT_ID'],
                'X-Freediving-Import-Token': self.headers.get('X-Freediving-Import-Token', ''),
            })
            response = connection.getresponse()
            payload = response.read()
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type'))
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            connection.close()

    proxy = ThreadingHTTPServer(('127.0.0.1', 0), ImportProxy)
    proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    proxy_thread.start()
    print(json.dumps({'origin_port': origin.server_port, 'import_port': proxy.server_port,
                      'snapshot_sha256': digest,
                      'binding_revision': origin.decisions._binding()['revision'],
                      'canonical_binding': binding,
                      'decision_id': metadata['decision_id']}), flush=True)
    try:
        for line in sys.stdin:
            if line.strip() == 'register-dependent':
                child = 'synthetic-dependent'
                child_proposal = proposal | {
                    'id': child, 'subject_id': child, 'depends_on': [metadata['decision_id']],
                    'status': 'automatic_approved',
                    'canonical_binding': binding | {'decision_id': child},
                }
                origin.decisions.register(digest, child_proposal,
                                          idempotency_key='synthetic-register-dependent')
                print(json.dumps({'registered': child}), flush=True)
            elif line.strip() == 'stop':
                break
    finally:
        proxy.shutdown()
        proxy_thread.join(timeout=2)
        proxy.server_close()
        origin.shutdown()
        origin_thread.join(timeout=2)
        origin.server_close()


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--serve-integrated':
        serve_integrated(sys.argv[2])
    else:
        unittest.main()
