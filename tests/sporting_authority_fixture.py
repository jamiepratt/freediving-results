"""Owned private HTTP workflow for isolated synthetic public integration tests.

The only stand-in is independent current evidence/canonical context. All staging,
owner domain reviews, CSRF/auth, immutable ledger and signature routes are real.
stdin/stdout JSON commands; never launch against production or retained sources.
"""
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import signal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_evidence_origin import make_server
from sporting_authority import canonical, digest
from test_unified_evidence_query import snapshot

HOST = 'synthetic-owner.alphacompose.com'
OWNER = 'synthetic-owner@example.test'
GATE = 'isolated-synthetic-gateway-secret'


class Fixture:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.key = self.root / 'signing.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'Ed25519', '-out', str(self.key)],
                       check=True, capture_output=True)
        self.key.chmod(0o600)
        request_key = self.root / 'request.key'
        request_key.write_text('isolated-synthetic-request-secret-32-characters')
        request_key.chmod(0o600)
        self.secret = request_key.read_text()
        config = self.root / 'sporting.json'
        config.write_bytes(canonical({'schema': 'sporting-authority-service/v1',
                          'ledger_path': str(self.root / 'ledger' / 'authority.sqlite'),
                          'signing_key_path': str(self.key), 'request_key_path': str(request_key)}))
        config.chmod(0o600)
        directory = snapshot(self.root)
        self.server = make_server(directory, {
            'OWNER_EVIDENCE_GATEWAY_SECRET': GATE, 'OWNER_EVIDENCE_EMAILS': OWNER,
            'OWNER_EVIDENCE_ORIGIN_HOST': HOST,
            'OWNER_EVIDENCE_SNAPSHOT_SHA256': json.loads((directory / 'manifest.json').read_bytes())['snapshot_sha256'],
            'OWNER_EVIDENCE_SPORTING_CONFIG': str(config)})
        self.context = {'pins': {'snapshot_sha256': 'a' * 64, 'owner_revision': 1,
                                  'packet_sha256': 'b' * 64}, 'rows': [], 'rules': {}}
        self.server.sporting.context_reader = lambda: copy.deepcopy(self.context)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.proposal_id = 'synthetic-public-cohort'
        self.browser_proxy = None
        self.genuine_reader = None
        self.genuine_relationships = None

    def config(self):
        public = subprocess.check_output(['openssl', 'pkey', '-in', str(self.key), '-pubout', '-outform', 'DER'])
        import base64
        return {'endpoint': 'http://127.0.0.1:' + str(self.server.server_port) + '/owner-evidence/api/sporting-authority/current',
                'request_secret': self.secret, 'public_key_der': base64.b64encode(public).decode(),
                'key_id': hashlib.sha256(public).hexdigest()}

    def request(self, path, body=None, *, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        raw = canonical(body) if body is not None else None
        base = {'Host': HOST, 'X-Freediving-Owner-Gateway': GATE, 'X-Freediving-Owner-Email': OWNER}
        if body is not None:
            base.update({'Origin': 'https://poc.alphacompose.com', 'Content-Type': 'application/json',
                         'X-Freediving-CSRF': body.get('csrf_token', '')})
        connection.request('POST' if body is not None else 'GET', path, body=raw,
                           headers=base if headers is None else headers)
        response = connection.getresponse()
        data, status = response.read(), response.status
        connection.close()
        return status, json.loads(data) if data else None

    def review(self):
        status, body = self.request('/owner-evidence/api/sporting-authority/review')
        if status != 200:
            raise ValueError('synthetic owner queue failed')
        return body

    def canonical_context(self, config, rows):
        """Opt-in real isolated PostgreSQL authority transport; no supplied upstream facts."""
        import importlib.util
        import private_sporting_proofs
        from private_sporting_relationships import RelationshipReviews
        if set(config) != {'jdbc_url', 'database', 'canonical_jdbc_url', 'canonical_database'}:
            raise ValueError('isolated canonical context configuration required')
        if self.genuine_reader is not None:
            raise ValueError('canonical fixture already configured')
        spec = importlib.util.spec_from_file_location('synthetic_proof_package', ROOT / 'deploy/canonical_status_runtime.py')
        package = importlib.util.module_from_spec(spec); spec.loader.exec_module(package)
        runtime = self.root / 'proof-runtime'
        source_root = Path(os.environ.get('FREEDIVING_PRIVATE_TEST_SOURCE_ROOT', str(ROOT))).resolve()
        manifest = package.build_runtime(source_root, runtime, 'isolated-synthetic-only', private_sporting_proofs)
        path = self.root / 'proof-config.json'
        path.write_bytes(canonical({**config, 'runtime_path': str(runtime), 'runtime_manifest_sha256': manifest}))
        path.chmod(0o600)
        self.genuine_reader = private_sporting_proofs.create_reader({'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG': str(path)})
        if not isinstance(rows, list) or not 1 <= len(rows) <= 400:
            raise ValueError('isolated exact retained source rows required')
        for row in rows:
            if set(row) != {'reference', 'coordinates', 'year', 'environment', 'discipline', 'gender'}:
                raise ValueError('canonical fixture input cannot supply upstream assertions')
        self.context['rows'] = copy.deepcopy(rows)
        def base():
            proof = self.genuine_reader([{'reference': r['reference'], 'coordinates': r['coordinates']} for r in rows])
            return {'pins': {**self.context['pins'], 'canonical_upstream_sha256': proof['binding_sha256'],
                             'canonical_scope_bindings': proof['scope_bindings']},
                    'rows': [{**r, **verified} for r, verified in zip(rows, proof['rows'])],
                    'rules': copy.deepcopy(self.context['rules'])}
        self.genuine_relationships = RelationshipReviews(self.root / 'relationships.sqlite', base)
        self.server.relationship_reviews = self.genuine_relationships
        def current():
            return self.genuine_relationships.proofs(base())
        self.server.sporting.context_reader = current
        self.server.sporting_proof_context = current
        return current()

    def canonical_relationships(self):
        """Synthetic explicit human intent through the actual authenticated owner route."""
        from private_sporting_relationships import inventory
        current = self.server.sporting.context_reader()
        for row in current['rows']:
            state = self.server.sporting.context_reader()
            csrf = self.review()['csrf_token']
            assertion = {'reference': row['reference'], 'coordinates': row['coordinates'],
                         'inventory_sha256': digest(inventory(state)),
                         'canonical_binding_sha256': state['pins']['canonical_upstream_sha256'],
                         'reviewed_references': [r['reference'] for r in state['rows']],
                         'same-attempt': 'distinct', 'source-conflict': 'resolved',
                         'reason': 'Isolated synthetic owner explicitly reviewed the exhaustive exact source inventory',
                         'citation': {'url': 'https://example.test/isolated-relationship-authority',
                                      'locator': 'Exact retained source and all synthetic possible repeats'}}
            status, result = self.request('/owner-evidence/api/sporting-authority/relationships', {
                'assertion': assertion, 'action': 'review', 'expected_revision': len(self.genuine_relationships.history()),
                'idempotency_key': 'synthetic-relationship-' + str(len(self.genuine_relationships.history())), 'csrf_token': csrf})
            if status != 200:
                return status, result
        return 200, {'relationship_revision': len(self.genuine_relationships.history())}

    def reverse_relationship(self, index=0):
        current = self.server.sporting.context_reader()
        row = current['rows'][index]
        csrf = self.review()['csrf_token']
        return self.request('/owner-evidence/api/sporting-authority/relationships', {
            'assertion': row['relationship_review']['assertion'], 'action': 'reverse',
            'expected_revision': len(self.genuine_relationships.history()),
            'idempotency_key': 'reverse-relationship-' + str(len(self.genuine_relationships.history())),
            'csrf_token': csrf})

    def stage(self, publication):
        self.proposal_id = 'synthetic-public-cohort-' + str(self.review()['revision'])
        rows, evidence = [], []
        for index, item in enumerate(publication['rows']):
            ref = item['reference']
            coords = {k: v for k, v in item['facts']['final']['citation'].items() if k in ('page', 'line', 'table', 'row')}
            retained = {'job-id': digest(['synthetic-job', index]), 'ordinal': ref['ordinal'],
                        'candidate-id': 'synthetic-' + str(index), 'source-sha256': ref['source-sha256'],
                        'artifact-sha256': ref['artifact-sha256'], 'parser-version': 'isolated-synthetic/1'}
            upstream = {k: {'value': item['facts'][k]['value'], 'event_sha256': digest(['synthetic-upstream', k, ref])}
                        for k in ('review', 'same-attempt', 'source-conflict')}
            if 'source-selection' in item['facts']:
                upstream['source-selection'] = {'value': item['facts']['source-selection']['value'],
                                                'event_sha256': digest(['synthetic-selection', ref])}
            upstream['publication'] = {'value': 'approved', 'event_sha256': digest(['synthetic-public-eligibility', ref]), 'reference': ref}
            if self.genuine_reader is not None:
                matches = [r for r in self.server.sporting.context_reader()['rows'] if r.get('public_reference') == ref]
                if len(matches) != 1:
                    raise ValueError('genuine exact public source mapping absent')
                retained, coords = matches[0]['reference'], matches[0]['coordinates']
            rows.append({'reference': retained, 'coordinates': coords, 'year': '2026',
                         'environment': 'pool', 'discipline': 'dnf', 'gender': 'women', 'upstream': upstream,
                         'public_reference': ref})
            evidence.append({'reference': ref, 'retained_reference': retained, 'coordinates': coords})
        rules = set().union(*(set(item['facts']) for item in publication['rows'])) | {'cohort'}
        if any(item.get('hypothetical') is not None for item in publication['rows']):
            rules.add('hypothetical')
        rule_sha = digest('isolated synthetic source rule document')
        if self.genuine_reader is None:
            self.context['rows'] = rows
        self.context['rules'] = {rule_sha: 'https://example.test/synthetic-rules.pdf'}
        now = datetime.now(timezone.utc)
        proposal = {'id': self.proposal_id, 'publication': publication, 'evidence': evidence,
                    'rules': {k: {'url': 'https://example.test/synthetic-rules.pdf', 'source-sha256': rule_sha,
                                  'locator': 'Isolated synthetic rule for ' + k} for k in rules},
                    'valid_until': (now + timedelta(days=1)).isoformat().replace('+00:00', 'Z')}
        current = self.review()
        return self.request('/owner-evidence/api/sporting-authority/stage', {
            'proposal': proposal, 'expected_revision': current['revision'],
            'idempotency_key': 'stage-' + str(current['revision']), 'csrf_token': current['csrf_token']})

    def action(self, action):
        current = self.review()
        return self.request('/owner-evidence/api/sporting-authority/actions', {
            'id': self.proposal_id, 'action': action, 'reason': 'Isolated synthetic owner ' + action,
            'expected_revision': current['revision'], 'idempotency_key': action + '-' + str(current['revision']),
            'csrf_token': current['csrf_token']})

    def browser(self):
        if self.browser_proxy is None:
            fixture = self
            class ReadOnlySyntheticBrowser(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass
                def do_GET(self):
                    if not self.path.startswith('/owner-evidence'):
                        self.send_response(404); self.end_headers(); return
                    connection = http.client.HTTPConnection('127.0.0.1', fixture.server.server_port, timeout=5)
                    connection.request('GET', self.path, headers={'Host': HOST,
                        'X-Freediving-Owner-Gateway': GATE, 'X-Freediving-Owner-Email': OWNER})
                    response = connection.getresponse(); body = response.read()
                    self.send_response(response.status)
                    for name in ('Content-Type', 'Cache-Control', 'Content-Security-Policy'):
                        if response.getheader(name):
                            self.send_header(name, response.getheader(name))
                    self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
                    connection.close()
            self.browser_proxy = ThreadingHTTPServer(('127.0.0.1', 0), ReadOnlySyntheticBrowser)
            self.proxy_thread = threading.Thread(target=self.browser_proxy.serve_forever, daemon=True)
            self.proxy_thread.start()
        return {'browser_url': 'http://127.0.0.1:' + str(self.browser_proxy.server_port) + '/owner-evidence/sporting',
                'synthetic': True, 'read_only': True}

    def close(self):
        if self.browser_proxy is not None:
            self.browser_proxy.shutdown(); self.proxy_thread.join(timeout=3); self.browser_proxy.server_close()
        self.server.shutdown(); self.thread.join(timeout=3)
        self.server.server_close()
        if self.genuine_relationships is not None:
            self.genuine_relationships.close()
        if self.genuine_reader is not None:
            self.genuine_reader.close()
        self.tmp.cleanup()


def main():
    fixture = Fixture()
    def terminate(*_):
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, terminate)
    try:
        print(json.dumps(fixture.config()), flush=True)
        for line in sys.stdin:
            try:
                request = json.loads(line)
                op = request['op']
                if op == 'canonical-context':
                    status, result = 200, fixture.canonical_context(request['config'], request['rows'])
                elif op == 'canonical-relationships':
                    status, result = fixture.canonical_relationships()
                elif op == 'reverse-relationship':
                    status, result = fixture.reverse_relationship(request.get('index', 0))
                elif op == 'canonical-proof':
                    status, result = 200, fixture.server.sporting.context_reader()
                elif op == 'stage':
                    status, result = fixture.stage(request['publication'])
                elif op == 'approve':
                    for action in ('source-approve', 'select-cohort', 'publish'):
                        status, result = fixture.action(action)
                        if status != 200:
                            break
                elif op in ('source-approve', 'select-cohort', 'publish', 'reverse'):
                    status, result = fixture.action(op)
                elif op == 'drift':
                    fixture.context['pins']['owner_revision'] += 1
                    status, result = 200, {'drift': True}
                elif op == 'unavailable':
                    fixture.server.sporting.context_reader = lambda: None
                    status, result = 200, {'unavailable': True}
                elif op in ('tamper', 'missing-predecessor'):
                    original = fixture.server.sporting.current
                    def faulty(nonce, original=original, fault=op):
                        envelope = original(nonce)
                        if fault == 'tamper':
                            import base64
                            signature = bytearray(base64.b64decode(envelope['signature_base64']))
                            signature[0] ^= 1
                            envelope['signature_base64'] = base64.b64encode(signature).decode()
                            return envelope
                        payload = envelope['payload']
                        payload['events'] = payload['events'][1:]
                        return fixture.server.sporting._sign(payload)
                    fixture.server.sporting.current = faulty
                    status, result = 200, {'isolated_synthetic_fault': op}
                elif op == 'expire':
                    fixture.server.sporting.clock = lambda: (datetime.now(timezone.utc) + timedelta(days=2)).isoformat().replace('+00:00', 'Z')
                    status, result = 200, {'expired': True}
                elif op == 'stop':
                    print(json.dumps({'status': 200}), flush=True); break
                elif op == 'browser':
                    status, result = 200, fixture.browser()
                else:
                    raise ValueError('unknown synthetic operation')
                print(json.dumps({'status': status, 'result': result}), flush=True)
            except Exception as exc:
                print(json.dumps({'status': 500, 'error': type(exc).__name__}), flush=True)
    finally:
        fixture.close()


if __name__ == '__main__':
    main()
