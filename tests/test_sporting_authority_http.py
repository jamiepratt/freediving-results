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


class SportingProofStartupHTTPTest(unittest.TestCase):
    def comparison_config(self, fixture):
        from pathlib import Path
        root = fixture.root / 'startup-runtime'
        relative = 'src/freediving/attempt_view_adapter.clj'
        source = Path(__file__).resolve().parents[1] / relative
        (root / relative).parent.mkdir(parents=True, exist_ok=True)
        (root / relative).write_bytes(source.read_bytes())
        manifest = root / 'manifest.json'
        manifest.write_bytes(canonical({'files': {relative: hashlib.sha256(source.read_bytes()).hexdigest()}}))
        packet = fixture.root / 'startup-packet.edn'; packet.write_text('isolated startup pin'); packet.chmod(0o600)
        config = fixture.root / 'startup-comparison.json'
        config.write_bytes(canonical({'runtime_path': str(root),
            'runtime_manifest_sha256': hashlib.sha256(manifest.read_bytes()).hexdigest(),
            'packet': {'path': str(packet), 'sha256': hashlib.sha256(packet.read_bytes()).hexdigest()}}))
        config.chmod(0o600)
        return str(config)

    def startup(self, fixture, *, comparison=False):
        from owner_evidence_origin import make_server
        return make_server(fixture.root / 'out', {
            'OWNER_EVIDENCE_GATEWAY_SECRET': GATE, 'OWNER_EVIDENCE_EMAILS': OWNER,
            'OWNER_EVIDENCE_ORIGIN_HOST': HOST,
            'OWNER_EVIDENCE_SNAPSHOT_SHA256': fixture.server.query.manifest['snapshot_sha256'],
            'OWNER_EVIDENCE_SPORTING_CONFIG': str(fixture.root / 'sporting.json'),
            'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG': 'isolated-proof-boundary',
            **({'OWNER_EVIDENCE_COMPARISON_CONFIG': self.comparison_config(fixture)} if comparison else {})})

    def test_owner_server_initializes_current_paired_proofs_before_returning(self):
        import time
        from unittest.mock import patch
        f = Fixture(); self.addCleanup(f.close)
        reads, closed = [], []
        def reader(rows, *, deadline=None):
            self.assertIsNotNone(deadline)
            self.assertGreater(deadline, time.monotonic())
            self.assertLessEqual(deadline - time.monotonic(), 12)
            reads.append(rows)
            return {'schema': 'private-sporting-proofs/v1', 'binding_sha256': 'a' * 64,
                    'scope_bindings': {'source': 'b' * 64, 'relationships': 'c' * 64},
                    'config_sha256': 'd' * 64, 'rows': []}
        reader.close = lambda: closed.append(True)
        with patch('private_sporting_proofs.create_reader', return_value=reader):
            server = self.startup(f)
        self.addCleanup(server.server_close)
        self.assertEqual(reads, [[]])
        self.assertIs(server.sporting_proof_reader, reader)
        self.assertEqual(server.sporting._history(), [])
        self.assertFalse(closed)
        self.assertFalse(hasattr(server, 'cached_sporting_authority'))

    def test_failed_initial_paired_read_closes_runtime_and_refuses_startup(self):
        from unittest.mock import patch
        from private_attempt_inspector import ComparisonTimeout
        f = Fixture(); self.addCleanup(f.close)
        closed = []
        def reader(rows, *, deadline=None):
            raise ComparisonTimeout('isolated cold proof read exceeded deadline')
        reader.close = lambda: closed.append(True)
        with patch('private_sporting_proofs.create_reader', return_value=reader):
            with self.assertRaisesRegex(ComparisonTimeout, 'cold proof read'):
                self.startup(f)
        self.assertEqual(closed, [True])

    def test_owner_startup_warms_comparison_without_authority_and_keeps_fresh_requests(self):
        import time
        from unittest.mock import patch
        f = Fixture(); self.addCleanup(f.close)
        calls, closed = [], []
        def proof(rows, *, deadline=None):
            calls.append(('proof', rows, deadline))
            return {'schema': 'private-sporting-proofs/v1', 'rows': [], 'discarded': 'startup-only'}
        proof.close = lambda: closed.append('proof')
        def comparison(filters, authority, *, deadline=None):
            calls.append(('comparison', filters, deadline))
            self.assertIsNone(authority)
            self.assertGreater(deadline, time.monotonic())
            return {'rows': [], 'discarded': 'startup-only'}
        comparison.verify_current = lambda authority: None
        comparison.close = lambda: closed.append('comparison')
        with patch('private_sporting_proofs.create_reader', return_value=proof), patch('private_attempt_inspector.create_reader', return_value=comparison):
            server = self.startup(f, comparison=True)
        self.addCleanup(server.server_close)
        self.assertEqual([item[0] for item in calls], ['proof', 'comparison', 'comparison', 'proof'])
        self.assertEqual(calls[1][1], {'limit': 1})
        self.assertEqual(calls[2][1]['discipline'], 'DNF')
        self.assertEqual(calls[3][1], [])
        self.assertGreaterEqual(calls[1][2], calls[0][2])
        self.assertFalse(closed)
        # Prewarming must not wrap readers in a retained result cache.
        self.assertIs(server.sporting_proof_reader, proof)
        self.assertIs(server.comparison_reader, comparison)
        server.sporting_proof_reader([], deadline=time.monotonic() + 12)
        self.assertEqual(len(calls), 5)

    def test_comparison_warm_failure_closes_both_runtimes_before_server_start(self):
        from unittest.mock import patch
        from private_attempt_inspector import ComparisonTimeout
        f = Fixture(); self.addCleanup(f.close)
        closed = []
        def proof(rows, *, deadline=None):
            return {'schema': 'private-sporting-proofs/v1', 'rows': []}
        proof.close = lambda: closed.append('proof')
        def comparison(filters, authority, *, deadline=None):
            raise ComparisonTimeout('isolated comparison warm-up failed')
        comparison.verify_current = lambda authority: None
        comparison.close = lambda: closed.append('comparison')
        with patch('private_sporting_proofs.create_reader', return_value=proof), patch('private_attempt_inspector.create_reader', return_value=comparison):
            with self.assertRaisesRegex(ComparisonTimeout, 'comparison warm-up failed'):
                self.startup(f, comparison=True)
        self.assertCountEqual(closed, ['proof', 'comparison'])

    def test_startup_reads_all_exact_retained_versions_before_full_proof_warm(self):
        import time
        from unittest.mock import patch
        f = Fixture(); self.addCleanup(f.close)
        calls, closed = [], []
        newer = {'job-id': 'b035efaef9e24ba8cee6e2b5cbcfa85416702068713442098dd7bfe1259fba65',
                 'artifact-sha256': 'c4d68ff2c14eb8a797658e247e10373114b7370c763b60afa9e7068835b3e6cf'}
        older = {'job-id': 'cff457431610c929a54970f77477aa5e44679a34cab1db74a7af4f3e6e54ee61',
                 'artifact-sha256': '0b47b35617de991d54e20a0c573443e080c6301c5b5fbc32b5d542088c099fe2'}
        raw = []
        for ordinal in range(138):
            coordinate = {'page': 5, 'line': ordinal + 1, 'column-start': 1, 'column-end': 90}
            ref = {**newer, 'source-sha256': '1' * 64, 'ordinal': ordinal, 'candidate-id': hashlib.sha256(str(ordinal).encode()).hexdigest()}
            raw.append({'reference': ref, 'row_coordinate': coordinate,
                        'retained-versions': [{'reference': {**ref, **older}, 'candidate': {'coordinates': coordinate}},
                                              {'reference': ref, 'candidate': {'coordinates': coordinate}}],
                        'year': '2026', 'environment': 'pool', 'discipline': 'DNF', 'gender': 'women'})
        def proof(rows, *, deadline=None):
            self.assertGreater(deadline, time.monotonic())
            self.assertLessEqual(deadline - time.monotonic(), 12)
            calls.append(('proof', rows, deadline))
            return {'schema': 'private-sporting-proofs/v1', 'rows': []}
        proof.close = lambda: closed.append('proof')
        def comparison(filters, authority, *, deadline=None):
            self.assertIsNone(authority)
            calls.append(('comparison', filters, deadline))
            return {'rows': raw[:1] if filters == {'limit': 1} else raw,
                    'pagination': {'total': 138}}
        comparison.verify_current = lambda authority: None
        comparison.close = lambda: closed.append('comparison')
        with patch('private_sporting_proofs.create_reader', return_value=proof), patch('private_attempt_inspector.create_reader', return_value=comparison):
            server = self.startup(f, comparison=True)
        self.addCleanup(server.server_close)
        self.assertEqual([c[0] for c in calls], ['proof', 'comparison', 'comparison', 'proof'])
        exact = calls[-1][1]
        self.assertEqual(len(exact), 276)
        self.assertEqual(exact[0]['coordinates'], {'page': 5, 'line': 1, 'column-start': 1, 'column-end': 90})
        self.assertEqual(exact[1]['reference']['parser-version'], 'cmas-2026-indoor-time/2')
        self.assertTrue(all(set(r) == {'reference', 'coordinates'} for r in exact))
        self.assertEqual(server.inspector_sources, {})
        self.assertEqual(server.sporting._history(), [])
        self.assertFalse(closed)
        self.assertTrue(all(calls[i][2] <= calls[i + 1][2] for i in range(3)))


    def test_full_proof_warm_failure_closes_runtimes_without_starting_owner_http(self):
        from unittest.mock import patch
        from private_attempt_inspector import ComparisonTimeout
        f = Fixture(); self.addCleanup(f.close)
        closed, calls = [], []
        exact = {'reference': {'job-id': 'b035efaef9e24ba8cee6e2b5cbcfa85416702068713442098dd7bfe1259fba65',
            'artifact-sha256': 'c4d68ff2c14eb8a797658e247e10373114b7370c763b60afa9e7068835b3e6cf',
            'source-sha256': '1' * 64, 'candidate-id': '2' * 64, 'ordinal': 1},
            'row_coordinate': {'page': 5, 'line': 1, 'column-start': 1, 'column-end': 90},
            'year': '2026', 'environment': 'pool', 'discipline': 'DNF', 'gender': 'women'}
        def proof(rows, *, deadline=None):
            calls.append(rows)
            if rows:
                raise ComparisonTimeout('isolated full exact proof warm-up failed')
            return {'schema': 'private-sporting-proofs/v1', 'rows': []}
        proof.close = lambda: closed.append('proof')
        def comparison(filters, authority, *, deadline=None):
            return {'rows': [exact], 'pagination': {'total': 1}}
        comparison.verify_current = lambda authority: None
        comparison.close = lambda: closed.append('comparison')
        with patch('private_sporting_proofs.create_reader', return_value=proof), patch('private_attempt_inspector.create_reader', return_value=comparison):
            with self.assertRaisesRegex(ComparisonTimeout, 'full exact proof warm-up failed'):
                self.startup(f, comparison=True)
        self.assertEqual([len(rows) for rows in calls], [0, 1])
        self.assertCountEqual(closed, ['proof', 'comparison'])

    def test_unbound_inventory_parser_refuses_startup_and_closes_runtimes(self):
        from unittest.mock import patch
        f = Fixture(); self.addCleanup(f.close)
        closed, calls = [], []
        def proof(rows, *, deadline=None):
            calls.append(rows)
            return {'schema': 'private-sporting-proofs/v1', 'rows': []}
        proof.close = lambda: closed.append('proof')
        def comparison(filters, authority, *, deadline=None):
            return {'rows': [{'reference': {'job-id': '0' * 64, 'artifact-sha256': '1' * 64},
                    'row_coordinate': {'table': 1, 'row': 2}, 'year': '2026', 'environment': 'pool',
                    'discipline': 'DNF', 'gender': 'women'}], 'pagination': {'total': 1}}
        comparison.verify_current = lambda authority: None
        comparison.close = lambda: closed.append('comparison')
        with patch('private_sporting_proofs.create_reader', return_value=proof), patch('private_attempt_inspector.create_reader', return_value=comparison):
            with self.assertRaisesRegex(ValueError, 'parser version unavailable'):
                self.startup(f, comparison=True)
        self.assertEqual(calls, [[]])
        self.assertCountEqual(closed, ['proof', 'comparison'])

class SportingAuthorityConcurrencyHTTPTest(unittest.TestCase):
    def challenge(self, f):
        body = {'schema': 'sporting-authority-challenge/v1', 'nonce': '9' * 64}
        return f.request('/owner-evidence/api/sporting-authority/current', body, headers={
            'Host': '127.0.0.1:' + str(f.server.server_port), 'Content-Type': 'application/json',
            'X-Freediving-Sporting-HMAC': hmac.new(f.secret.encode(), canonical(body), hashlib.sha256).hexdigest()})

    def test_authenticated_read_only_signer_overlaps_owner_proof_collection(self):
        import copy
        import threading
        from concurrent.futures import ThreadPoolExecutor
        f = Fixture(); self.addCleanup(f.close)
        signer_entered, proof_entered, status_entered = threading.Event(), threading.Event(), threading.Event()
        overlaps = []
        def signed_context():
            with f.server.read_lock():
                value = copy.deepcopy(f.context)
            signer_entered.set()
            overlaps.append(proof_entered.wait(0.7) and status_entered.wait(0.7))
            return value
        def proof_context():
            with f.server.read_lock():
                proof_entered.set()
                return copy.deepcopy(f.context)
        def status_authority(*, deadline=None):
            with f.server.read_lock(deadline):
                status_entered.set()
            return None
        f.server.status_authority = status_authority
        f.server.sporting.context_reader = signed_context
        f.server.sporting_proof_context = proof_context
        with ThreadPoolExecutor(max_workers=3) as pool:
            signed = pool.submit(self.challenge, f)
            self.assertTrue(signer_entered.wait(2))
            proof = pool.submit(f.request, '/owner-evidence/api/sporting-authority/proofs')
            status = pool.submit(f.request, '/owner-evidence/api/presentation-status')
            self.assertEqual(status.result(timeout=3)[0], 200)
            self.assertEqual(proof.result(timeout=3)[0], 200)
            self.assertEqual(signed.result(timeout=3)[0], 200)
        self.assertTrue(overlaps)
        self.assertTrue(all(overlaps), 'read-only signer held the owner mutation lock across context collection')
        self.assertEqual(f.server.sporting._history(), [])

    def test_real_owner_mutations_stay_serialized_while_signer_lock_scope_changes(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        f = Fixture(); self.addCleanup(f.close)
        self.assertEqual(f.stage(synthetic_proposal(synthetic_context())['publication'])[0], 200)
        current = f.review()
        first_entered, second_entered, release = threading.Event(), threading.Event(), threading.Event()
        original = f.server.sporting.act
        def act(*args, **kwargs):
            if kwargs['action'] == 'source-approve':
                first_entered.set()
                if not release.wait(2):
                    raise ValueError('isolated serialization barrier timed out')
            else:
                second_entered.set()
            return original(*args, **kwargs)
        f.server.sporting.act = act
        def request(action, revision):
            return f.request('/owner-evidence/api/sporting-authority/actions', {
                'id': f.proposal_id, 'action': action, 'reason': 'Synthetic explicit concurrent owner review',
                'expected_revision': revision, 'idempotency_key': action,
                'csrf_token': current['csrf_token']})
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(request, 'source-approve', 1)
            self.assertTrue(first_entered.wait(2))
            second = pool.submit(request, 'select-cohort', 2)
            try:
                self.assertFalse(second_entered.wait(0.1))
            finally:
                release.set()
            self.assertEqual(first.result(timeout=3)[0], 200)
            self.assertEqual(second.result(timeout=3)[0], 200)
        self.assertTrue(second_entered.is_set())
        self.assertEqual(f.review()['revision'], 3)

    def test_full_proof_admission_refuses_overlap_without_queueing(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        f = Fixture(); self.addCleanup(f.close)
        entered, release = threading.Event(), threading.Event()
        def proof_context():
            entered.set()
            if not release.wait(2):
                raise ValueError('isolated proof barrier timed out')
            return f.context
        f.server.sporting_proof_context = proof_context
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(f.request, '/owner-evidence/api/sporting-authority/proofs')
            self.assertTrue(entered.wait(2))
            try:
                second = pool.submit(f.request, '/owner-evidence/api/sporting-authority/proofs')
                self.assertEqual(second.result(timeout=0.5)[0], 503)
            finally:
                release.set()
            self.assertEqual(first.result(timeout=3)[0], 200)
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/proofs')[0], 200)


    def test_owner_reversal_during_signer_read_withholds_old_publication(self):
        import copy
        import threading
        from concurrent.futures import ThreadPoolExecutor
        f = Fixture(); self.addCleanup(f.close)
        self.assertEqual(f.stage(synthetic_proposal(synthetic_context())['publication'])[0], 200)
        for action in ('source-approve', 'select-cohort', 'publish'):
            self.assertEqual(f.action(action)[0], 200)
        review = f.review()
        entered, release = threading.Event(), threading.Event()
        calls = []
        def context():
            calls.append(True)
            if len(calls) == 1:
                entered.set()
                if not release.wait(2):
                    raise ValueError('isolated signer reversal barrier timed out')
            return copy.deepcopy(f.context)
        f.server.sporting.context_reader = context
        with ThreadPoolExecutor(max_workers=2) as pool:
            signed = pool.submit(self.challenge, f)
            self.assertTrue(entered.wait(2))
            reversal = pool.submit(f.request, '/owner-evidence/api/sporting-authority/actions', {
                'id': f.proposal_id, 'action': 'reverse', 'reason': 'Synthetic explicit racing owner withdrawal',
                'expected_revision': review['revision'], 'idempotency_key': 'race-withdrawal', 'csrf_token': review['csrf_token']})
            try:
                self.assertEqual(reversal.result(timeout=0.7)[0], 200)
            finally:
                release.set()
            status, envelope = signed.result(timeout=3)
        self.assertEqual(status, 200)
        self.assertIsNone(envelope['payload']['publication'])
        self.assertEqual(envelope['payload']['status'], 'unavailable')
        self.assertEqual(envelope['payload']['revision'], 5)
        self.assertEqual(envelope['payload']['events'][-1]['action'], 'reverse')

    def test_proof_read_lock_timeout_releases_admission_for_fresh_retry(self):
        import http.client
        from owner_source_view import SourceViewError
        f = Fixture(); self.addCleanup(f.close)
        def unavailable():
            raise SourceViewError(503)
        f.server.sporting_proof_context = unavailable
        connection = http.client.HTTPConnection('127.0.0.1', f.server.server_port, timeout=3)
        connection.request('GET', '/owner-evidence/api/sporting-authority/proofs', headers={
            'Host': HOST, 'X-Freediving-Owner-Gateway': GATE, 'X-Freediving-Owner-Email': OWNER})
        response = connection.getresponse(); response.read(); connection.close()
        self.assertEqual(response.status, 503)
        self.assertEqual(response.getheader('Retry-After'), '1')
        f.server.sporting_proof_context = lambda: f.context
        self.assertEqual(f.request('/owner-evidence/api/sporting-authority/proofs')[0], 200)


if __name__ == '__main__':
    unittest.main()


class ExactSourceAccuracyOwnerHTTPTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture(); self.addCleanup(self.fixture.close)
        self.calls = []
        def writer(request, **kw):
            self.calls.append((request, kw))
            if kw.get('replay_only'):
                from sporting_authority import ConflictError
                raise ConflictError('No identical prior receipt')
            return {'schema': 'private-sporting-proofs/v1', 'receipt': {'revision': 1}, 'replayed': False}
        self.fixture.server.source_accuracy_review = writer
        csrf = self.fixture.review()['csrf_token']
        self.body = {'id': 'synthetic-review-one', 'action': 'accept',
                'reference': {'job-id': 'a' * 64, 'ordinal': 1, 'candidate-id': 'synthetic',
                              'source-sha256': 'b' * 64, 'artifact-sha256': 'c' * 64, 'parser-version': 'synthetic/1'},
                'coordinates': {'page': 1, 'line': 2, 'column-start': 3, 'column-end': 4},
                'expected_source_binding_sha256': 'd' * 64, 'base_revision': 0, 'event_id': None,
                'reason': 'Compared one exact row against its original PDF page', 'source_visual_accuracy': True,
                'csrf_token': csrf}
        self.context = {'pins': {'packet_sha256': 'e' * 64, 'source_bundle_sha256': 'f' * 64,
                        'canonical_scope_bindings': {'source': 'd' * 64}, 'owner_revision': 1},
                        'rows': [{'reference': self.body['reference'], 'coordinates': self.body['coordinates'],
                                  'source_review': {'enabled': True}}]}
        self.fixture.server.sporting_proof_context = lambda: self.context
        self.body['expected_context_pins_sha256'] = canonical_digest(self.context['pins'])
        self.route = '/owner-evidence/api/sporting-authority/source-accuracy'

    def test_exact_source_accuracy_route_derives_actor_and_requires_csrf(self):
        f, body = self.fixture, self.body
        self.assertEqual(f.request(self.route, {**body, 'csrf_token': 'bad'})[0], 403)
        self.assertEqual(f.request(self.route, {**body, 'actor': 'forged'})[0], 400)
        self.assertEqual(f.request(self.route, body)[0], 200)
        request, options = self.calls[0]
        self.assertEqual(request['actor'], OWNER)
        self.assertNotIn('csrf_token', request)
        self.assertNotIn('expected_context_pins_sha256', request)
        self.assertEqual(request['reference'], body['reference'])
        self.assertTrue(f.server.request_lock.acquire(blocking=False)); f.server.request_lock.release()

    def test_stale_packet_refuses_writer_even_when_database_binding_unchanged(self):
        self.context['pins']['packet_sha256'] = '9' * 64
        self.assertEqual(self.fixture.request(self.route, self.body)[0], 409)
        self.assertTrue(self.calls[0][1]['replay_only'])

    def test_lost_response_retry_allows_only_identical_existing_receipt_after_pin_change(self):
        from sporting_authority import ConflictError
        receipts = {}
        def writer(request, **options):
            if request['id'] in receipts:
                if receipts[request['id']] != request:
                    raise ConflictError('Replay body changed')
                return {'receipt': {'revision': 1}, 'replayed': True}
            if options.get('replay_only'):
                raise ConflictError('No identical prior receipt')
            receipts[request['id']] = request
            return {'receipt': {'revision': 1}, 'replayed': False}
        self.fixture.server.source_accuracy_review = writer
        self.assertEqual(self.fixture.request(self.route, self.body)[0], 200)
        self.context['pins']['packet_sha256'] = '9' * 64
        status, result = self.fixture.request(self.route, self.body)
        self.assertEqual(status, 200); self.assertTrue(result['replayed'])
        self.assertEqual(self.fixture.request(self.route, {**self.body, 'id': 'new-id'})[0], 409)
        self.assertEqual(self.fixture.request(self.route, {**self.body, 'reason': 'Changed reason'})[0], 409)
        self.assertEqual(len(receipts), 1)

    def test_configuration_reuses_paired_reader_writer_callback(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from sporting_authority_http import configure
        calls = []
        class Reader:
            def __call__(self, rows, **kw):
                calls.append(('read', rows))
            def review(self, request, config, **kw):
                calls.append(('review', request, config, kw)); return {'replayed': True}
            def close(self):
                pass
        origin = SimpleNamespace(comparison_reader=None)
        env = {'OWNER_EVIDENCE_SPORTING_CONFIG': str(self.fixture.root / 'sporting.json'),
               'OWNER_EVIDENCE_SOURCE_REVIEW_CONFIG': str(self.fixture.root / 'review.json')}
        with patch('private_sporting_proofs.create_reader', return_value=Reader()):
            configure(origin, self.fixture.root / 'snapshot', env)
            self.addCleanup(origin.sporting.close)
            self.assertTrue(origin.source_accuracy_review({'id': 'retry'}, replay_only=True)['replayed'])
            self.assertEqual(calls[-1][2], env['OWNER_EVIDENCE_SOURCE_REVIEW_CONFIG'])
            self.assertTrue(calls[-1][3]['replay_only'])
        other = SimpleNamespace(comparison_reader=None)
        with patch('private_sporting_proofs.create_reader', return_value=None):
            configure(other, self.fixture.root / 'snapshot', env)
            self.addCleanup(other.sporting.close)
            self.assertIsNone(other.source_accuracy_review)

    def test_writer_unavailable_fails_closed(self):
        self.fixture.server.source_accuracy_review = None
        self.assertEqual(self.fixture.request(self.route, self.body)[0], 503)

    def test_unimported_or_disabled_exact_target_refuses_writer(self):
        for state in ({'enabled': False, 'reason': 'Exact canonical import missing'}, {}):
            self.context['rows'][0]['source_review'] = state
            self.assertEqual(self.fixture.request(self.route, self.body)[0], 409)
        self.assertEqual(self.calls, [])

    def test_exact_parser_and_all_coordinates_must_match_current_retained_version(self):
        changed = {**self.body['reference'], 'parser-version': 'synthetic/2'}
        self.assertEqual(self.fixture.request(self.route, {**self.body, 'reference': changed})[0], 409)
        coords = {**self.body['coordinates'], 'column-end': 5}
        self.assertEqual(self.fixture.request(self.route, {**self.body, 'coordinates': coords})[0], 409)
        self.assertEqual(self.calls, [])


def canonical_digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


class ReviewInventoryReadTest(unittest.TestCase):
    def test_independent_reads_overlap_and_share_the_exact_deadline(self):
        import threading
        import time
        from types import SimpleNamespace
        from unittest.mock import patch
        import sporting_authority_http as http
        started = threading.Event()
        authority_started = threading.Event()
        deadline = time.monotonic() + 1
        rows = [{'reference': {'job-id': 'synthetic'}, 'coordinates': {'table': 0, 'row': 1}}]
        def inventory(origin, comparison, *, deadline):
            self.assertEqual(deadline, expected_deadline)
            started.set()
            self.assertTrue(authority_started.wait(.5))
            return rows
        def authority(*, deadline):
            self.assertEqual(deadline, expected_deadline)
            self.assertTrue(started.wait(.5))
            authority_started.set()
            return {'current': True}
        expected_deadline = deadline
        with patch.object(http, 'retained_rows', inventory):
            value = http._review_inventory(SimpleNamespace(status_authority=authority), {}, deadline)
        self.assertEqual(value, ({'current': True}, rows))
        self.assertIs(value[1], rows)
        self.assertFalse(any(t.name.startswith('sporting-inventory') for t in threading.enumerate()))

    def test_failed_owner_read_joins_the_owned_inventory_worker(self):
        import threading
        import time
        from types import SimpleNamespace
        from unittest.mock import patch
        import sporting_authority_http as http
        started, release, finished = threading.Event(), threading.Event(), threading.Event()
        def inventory(*args, **kwargs):
            started.set()
            self.assertTrue(release.wait(.5))
            finished.set()
            return []
        def authority(**kwargs):
            self.assertTrue(started.wait(.5))
            release.set()
            raise ValueError('synthetic owner refusal')
        with patch.object(http, 'retained_rows', inventory):
            with self.assertRaisesRegex(ValueError, 'owner refusal'):
                http._review_inventory(SimpleNamespace(status_authority=authority), {}, time.monotonic() + 1)
        self.assertTrue(finished.is_set())
        self.assertFalse(any(t.name.startswith('sporting-inventory') for t in threading.enumerate()))

    def test_missing_owner_or_failed_inventory_never_exports_partial_rows(self):
        import time
        from types import SimpleNamespace
        from unittest.mock import patch
        import sporting_authority_http as http
        with patch.object(http, 'retained_rows', return_value=[{'private': 'synthetic'}]):
            with self.assertRaisesRegex(ValueError, 'status unavailable'):
                http._review_inventory(SimpleNamespace(status_authority=lambda **_: None), {}, time.monotonic() + 1)
        with patch.object(http, 'retained_rows', side_effect=ValueError('synthetic exact reference refusal')):
            with self.assertRaisesRegex(ValueError, 'exact reference refusal'):
                http._review_inventory(SimpleNamespace(status_authority=lambda **_: {'current': True}), {}, time.monotonic() + 1)

    def test_expired_deadline_admits_neither_read(self):
        import time
        from types import SimpleNamespace
        from unittest.mock import Mock, patch
        import sporting_authority_http as http
        origin = SimpleNamespace(status_authority=Mock())
        with patch.object(http, 'retained_rows') as inventory:
            with self.assertRaises(ValueError):
                http._review_inventory(origin, {}, time.monotonic() - 1)
            inventory.assert_not_called()
            origin.status_authority.assert_not_called()

    def test_exhausted_inventory_deadline_joins_before_returning_failure(self):
        import threading
        import time
        from types import SimpleNamespace
        from unittest.mock import patch
        from private_attempt_inspector import ComparisonTimeout
        import sporting_authority_http as http
        entered, finished = threading.Event(), threading.Event()
        deadline = time.monotonic() + .04
        def inventory(*args, **kwargs):
            self.assertEqual(kwargs['deadline'], deadline)
            entered.set()
            try:
                threading.Event().wait(max(0, deadline - time.monotonic()))
                raise ComparisonTimeout('synthetic exhausted inventory budget')
            finally:
                finished.set()
        def authority(**kwargs):
            self.assertTrue(entered.wait(.5))
            return {'current': True}
        with patch.object(http, 'retained_rows', inventory):
            with self.assertRaises((TimeoutError, ComparisonTimeout)):
                http._review_inventory(SimpleNamespace(status_authority=authority), {}, deadline)
        self.assertTrue(finished.is_set())
        self.assertFalse(any(t.name.startswith('sporting-inventory') for t in threading.enumerate()))
