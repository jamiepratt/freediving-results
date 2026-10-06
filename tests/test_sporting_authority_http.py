"""Real private origin HTTP and signature tests with isolated synthetic context."""
import base64
import hashlib
import hmac
import json
import subprocess
import unittest

from sporting_authority_fixture import Fixture, HOST, OWNER, GATE
from sporting_authority import canonical
from test_sporting_authority import synthetic_context, synthetic_proposal
from sporting_authority_http import live_context


class SportingAuthorityHTTPTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.addCleanup(self.fixture.close)

    def challenge(self, *, signature=None, extra=None):
        f = self.fixture
        body = {'schema': 'sporting-authority-challenge/v1', 'nonce': '9' * 64}
        headers = {'Host': '127.0.0.1:' + str(f.server.server_port), 'Content-Type': 'application/json',
                   'X-Freediving-Sporting-HMAC': signature or hmac.new(f.secret.encode(), canonical(body), hashlib.sha256).hexdigest()}
        headers.update(extra or {})
        return f.request('/owner-evidence/api/sporting-authority/current', body, headers=headers)

    def test_actual_owner_domain_reviews_export_signed_safe_projection(self):
        f = self.fixture
        publication = synthetic_proposal(synthetic_context())['publication']
        self.assertEqual(f.stage(publication)[0], 200)
        for action in ('source-approve', 'select-cohort'):
            self.assertEqual(f.action(action)[0], 200)
            self.assertIsNone(self.challenge()[1]['payload']['publication'])
        self.assertEqual(f.action('publish')[0], 200)
        status, envelope = self.challenge()
        self.assertEqual(status, 200)
        self.assertEqual(envelope['payload']['publication'], publication)
        self.assertNotIn(OWNER, canonical(envelope).decode())
        signature = f.root / 'signature.bin'; signature.write_bytes(base64.b64decode(envelope['signature_base64']))
        public = f.root / 'public.der'; public.write_bytes(base64.b64decode(f.config()['public_key_der']))
        message = f.root / 'safe-message.json'; message.write_bytes(canonical(envelope['payload']))
        verified = subprocess.run(['openssl', 'pkeyutl', '-verify', '-rawin', '-pubin', '-keyform', 'DER',
                                   '-inkey', str(public), '-sigfile', str(signature), '-in', str(message)],
                                  capture_output=True)
        self.assertEqual(verified.returncode, 0)
        self.assertEqual(f.action('reverse')[0], 200)
        self.assertIsNone(self.challenge()[1]['payload']['publication'])

    def test_machine_challenge_cannot_use_owner_or_browser_credentials(self):
        self.assertEqual(self.challenge(signature='0' * 64)[0], 403)
        self.assertEqual(self.challenge(extra={'X-Freediving-Owner-Email': OWNER})[0], 403)
        self.assertEqual(self.challenge(extra={'Origin': 'https://poc.alphacompose.com'})[0], 403)
        self.assertEqual(self.challenge(extra={'Authorization': 'Bearer synthetic'})[0], 403)
        self.assertEqual(self.fixture.request('/owner-evidence/api/sporting-authority/review',
                         headers={'Host': HOST, 'X-Freediving-Owner-Gateway': GATE})[0], 403)

    def test_owner_actions_require_csrf_and_cannot_choose_actor(self):
        f = self.fixture
        proposal = synthetic_proposal(synthetic_context())
        current = f.review()
        body = {'proposal': proposal, 'expected_revision': 0, 'idempotency_key': 'stage', 'csrf_token': 'bad'}
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/stage', body)[0], 403)
        self.assertEqual(f.stage(proposal['publication'])[0], 200)
        current = f.review()
        body = {'id': f.proposal_id, 'action': 'source-approve', 'reason': 'Synthetic owner action',
                'expected_revision': 1, 'idempotency_key': 'act', 'csrf_token': current['csrf_token'],
                'actor': 'forged@example.test'}
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/actions', body)[0], 400)
        self.assertEqual(f.review()['revision'], 1)

    def test_owner_has_readable_separate_sporting_review_page(self):
        # Read directly since fixture.request expects JSON, not HTML.
        import http.client
        f = self.fixture
        connection = http.client.HTTPConnection('127.0.0.1', f.server.server_port)
        connection.request('GET', '/owner-evidence/sporting', headers={
            'Host': HOST, 'X-Freediving-Owner-Gateway': GATE, 'X-Freediving-Owner-Email': OWNER})
        response = connection.getresponse(); body = response.read(); connection.close()
        self.assertEqual(response.status, 200)
        self.assertIn(b'Sporting source reviews', body)
        self.assertIn(b'/owner-evidence/assets/sporting.js', body)
        self.assertEqual(response.getheader('Cache-Control'), 'no-store')

    def test_live_context_accepts_actual_reference_shape_and_explicit_pinned_parser(self):
        from owner_evidence_origin import make_server
        from pathlib import Path
        from test_unified_evidence_query import snapshot
        f = self.fixture
        root = f.root / 'real-shape'; root.mkdir()
        directory = snapshot(root)
        runtime = root / 'runtime'
        relative = 'src/freediving/attempt_view_adapter.clj'
        adapter = Path(__file__).resolve().parents[1] / relative
        (runtime / relative).parent.mkdir(parents=True)
        (runtime / relative).write_bytes(adapter.read_bytes())
        manifest = runtime / 'manifest.json'
        manifest.write_bytes(canonical({'files': {relative: hashlib.sha256(adapter.read_bytes()).hexdigest()}}))
        packet = root / 'packet.edn'; packet.write_text('isolated private packet pin'); packet.chmod(0o600)
        comparison = root / 'comparison.json'
        comparison.write_bytes(canonical({'runtime_path': str(runtime),
                   'runtime_manifest_sha256': hashlib.sha256(manifest.read_bytes()).hexdigest(),
                   'packet': {'path': str(packet), 'sha256': hashlib.sha256(packet.read_bytes()).hexdigest()}}))
        comparison.chmod(0o600)
        config = root / 'sporting.json'; config.write_bytes(canonical({'rule_objects': {}})); config.chmod(0o600)
        env = {'OWNER_EVIDENCE_GATEWAY_SECRET': GATE, 'OWNER_EVIDENCE_EMAILS': OWNER,
               'OWNER_EVIDENCE_ORIGIN_HOST': HOST,
               'OWNER_EVIDENCE_SNAPSHOT_SHA256': json.loads((directory / 'manifest.json').read_bytes())['snapshot_sha256'],
               'OWNER_EVIDENCE_DECISION_DB': str(root / 'decisions' / 'owner.sqlite'),
               'OWNER_EVIDENCE_IMPORT_TOKEN': 'isolated-synthetic-import-token-32characters'}
        origin = make_server(directory, env)
        self.addCleanup(origin.server_close)
        ref = {'job-id': 'b035efaef9e24ba8cee6e2b5cbcfa85416702068713442098dd7bfe1259fba65',
               'ordinal': 56, 'candidate-id': 'synthetic-private-row', 'source-sha256': '2' * 64,
               'artifact-sha256': 'c4d68ff2c14eb8a797658e247e10373114b7370c763b60afa9e7068835b3e6cf'}
        def comparator(filters, authority):
            self.assertEqual(filters['discipline'], 'DNF')
            return {'rows': [{'reference': ref, 'row_coordinate': {'kind': 'pdf', 'page': 5, 'row': 1},
                             'year': '2026', 'environment': 'pool', 'discipline': 'DNF', 'gender': 'women'}]}
        origin.comparison_reader = comparator
        context = live_context(origin, directory, {**env, 'OWNER_EVIDENCE_COMPARISON_CONFIG': str(comparison)}, config, review=True)
        self.assertEqual(context['rows'][0]['reference']['parser-version'], 'cmas-2026-indoor-time/2')
        self.assertEqual(context['rows'][0]['discipline'], 'dnf')
        self.assertEqual(context['rows'][0]['upstream'], {})
        reads = []
        def proof_reader(rows, *, deadline=None):
            reads.append(rows)
            return {'binding_sha256': 'e' * 64, 'config_sha256': 'f' * 64,
                    'scope_bindings': {'source': '1' * 64, 'relationships': '2' * 64},
                    'rows': [{**r, 'upstream': {'review': {'value': 'verified', 'event_sha256': '3' * 64}},
                              'diagnostics': {'mapping': {'state': 'current'}}} for r in rows]}
        origin.sporting_proof_reader = proof_reader
        minimal = live_context(origin, directory, {**env, 'OWNER_EVIDENCE_COMPARISON_CONFIG': str(comparison)}, config)
        self.assertEqual(minimal['pins']['canonical_upstream_sha256'], 'e' * 64)
        self.assertEqual(minimal['rows'], [])
        exact = live_context(origin, directory, {**env, 'OWNER_EVIDENCE_COMPARISON_CONFIG': str(comparison)}, config, review=True)
        self.assertEqual(exact['rows'][0]['upstream']['review']['value'], 'verified')
        self.assertEqual(reads[-1], [])
        def drift(rows, *, deadline=None):
            value = proof_reader(rows, deadline=deadline)
            if not rows:
                value['binding_sha256'] = '9' * 64
            return value
        origin.sporting_proof_reader = drift
        with self.assertRaisesRegex(ValueError, 'upstream authority changed'):
            live_context(origin, directory, {**env, 'OWNER_EVIDENCE_COMPARISON_CONFIG': str(comparison)}, config, review=True)
        origin.sporting_proof_reader = proof_reader
        (runtime / relative).write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'metadata changed'):
            live_context(origin, directory, {**env, 'OWNER_EVIDENCE_COMPARISON_CONFIG': str(comparison)}, config, review=True)



class ExactProofDiagnosticHTTPTest(unittest.TestCase):
    def test_authenticated_diagnostics_exist_without_sporting_proposals(self):
        f = Fixture()
        self.addCleanup(f.close)
        f.server.sporting_proof_context = lambda: {
            'pins': {'canonical_upstream_sha256': 'a' * 64}, 'rules': {},
            'rows': [{'reference': {'source-sha256': 'b' * 64, 'artifact-sha256': 'c' * 64,
                       'candidate-id': 'isolated-row', 'ordinal': 1, 'job-id': 'd' * 64,
                       'parser-version': 'isolated/1'}, 'coordinates': {'table': 1, 'row': 1},
                      'upstream': {}, 'diagnostics': {'mapping': {'state': 'missing',
                       'reason': 'Exact AIDA import mapping absent'}}, 'year': '2026',
                      'environment': 'pool', 'discipline': 'dnf', 'gender': 'women'}]}
        status, value = f.request('/owner-evidence/api/sporting-authority/proofs?offset=0&limit=20')
        self.assertEqual(status, 200)
        self.assertEqual(value['pagination']['total'], 1)
        self.assertEqual(value['rows'][0]['diagnostics']['mapping']['state'], 'missing')
        self.assertEqual(value['relationship_revision'], 0)
        self.assertNotIn('csrf_token', canonical(value).decode())
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/proofs?offset=-1')[0], 400)

class IndependentRelationshipOwnerHTTPTest(unittest.TestCase):
    def test_owner_uses_separate_typed_authority_with_csrf_and_cas(self):
        from private_sporting_relationships import RelationshipReviews
        from test_private_sporting_relationships import context, assertion
        f = Fixture(); self.addCleanup(f.close)
        current = context(); ledger = RelationshipReviews(f.root / 'typed.sqlite', lambda: current)
        self.addCleanup(ledger.close)
        f.server.relationship_reviews = ledger
        f.server.sporting_proof_context = lambda: ledger.proofs(current)
        csrf = f.review()['csrf_token']
        body = {'assertion': assertion(current), 'action': 'review', 'expected_revision': 0,
                'idempotency_key': 'typed-one', 'csrf_token': csrf}
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/relationships',
                                 {**body, 'csrf_token': 'bad'})[0], 403)
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/relationships',
                                 {**body, 'actor': 'forged'})[0], 400)
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/relationships', body)[0], 200)
        self.assertEqual(f.review()['revision'], 0)
        self.assertEqual(ledger.history()[0]['actor'], OWNER)
        proof = f.request('/owner-evidence/api/sporting-authority/proofs')[1]
        self.assertEqual(proof['rows'][0]['upstream']['same-attempt']['value'], 'distinct')
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/relationships',
                                 {**body, 'action': 'reverse', 'idempotency_key': 'stale'})[0], 409)
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/relationships',
                                 {**body, 'action': 'reverse', 'expected_revision': 1, 'idempotency_key': 'reverse'})[0], 200)
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/proofs')[1]['rows'][0]['upstream'], {})


if __name__ == '__main__':
    unittest.main()
