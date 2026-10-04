import http.client
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest

from test_unified_evidence_query import snapshot

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_evidence_origin import make_server


HOST = 'owner-private.alphacompose.com'
EMAIL = 'owner@example.com'
SECRET = 'a-private-gateway-secret-for-tests'


class PrivateOriginTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.snapshot_dir = snapshot(Path(self.tmp.name))
        digest = json.loads((self.snapshot_dir / 'manifest.json').read_text())['snapshot_sha256']
        self.env = {'OWNER_EVIDENCE_GATEWAY_SECRET': SECRET,
                    'OWNER_EVIDENCE_EMAILS': EMAIL,
                    'OWNER_EVIDENCE_SNAPSHOT_SHA256': digest,
                    'OWNER_EVIDENCE_ORIGIN_HOST': HOST}
        self.server = make_server(self.snapshot_dir, self.env)
        self.addCleanup(self.server.server_close)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.thread.join(timeout=2)

    def request(self, path='/owner-evidence', *, method='GET', headers=None, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        connection.putrequest(method, path, skip_host=True)
        for key, value in (headers if headers is not None else [
                ('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                ('X-Freediving-Owner-Email', EMAIL)]):
            connection.putheader(key, value)
        connection.endheaders(body)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_authorized_workspace_and_api_use_private_prefix(self):
        status, headers, html = self.request()
        self.assertEqual(status, 200)
        self.assertIn(b'/owner-evidence/assets/app.js', html)
        self.assertIn(b'/owner-evidence/assets/app.css', html)
        self.assertNotIn(b'/logout', html)
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertIn("default-src 'none'", headers['Content-Security-Policy'])
        status, _, js = self.request('/owner-evidence/assets/app.js')
        self.assertEqual(status, 200)
        self.assertIn(b'/owner-evidence/api/overview', js)
        self.assertNotIn(b"'/api/", js)
        self.assertNotIn(b"'/login'", js)
        status, _, body = self.request('/owner-evidence/api/overview')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['normalized_federation'], 'unavailable in this snapshot')

    def test_overview_reports_verified_source_bundle_binding(self):
        self.server.source_bundle_sha256 = 'd' * 64
        status, _, body = self.request('/owner-evidence/api/overview')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['bundle_manifest_sha256'], 'd' * 64)

    def test_idle_origin_connection_does_not_block_authorized_overview(self):
        idle = socket.create_connection(('127.0.0.1', self.server.server_port), timeout=1)
        self.addCleanup(idle.close)
        idle.sendall(b'GET /owner-evidence HTTP/1.1\r\nHost: ' + HOST.encode())
        status, _, body = self.request('/owner-evidence/api/overview')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['snapshot_sha256'], self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'])

    def test_direct_origin_spoof_and_wrong_owner_get_no_private_bytes(self):
        for headers in ([('Host', HOST)],
                        [('Host', HOST), ('X-Freediving-Owner-Gateway', 'wrong'),
                         ('X-Freediving-Owner-Email', EMAIL)],
                        [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                         ('X-Freediving-Owner-Email', 'other@example.com')],
                        [('Host', 'poc.alphacompose.com'), ('X-Freediving-Owner-Gateway', SECRET),
                         ('X-Freediving-Owner-Email', EMAIL)]):
            with self.subTest(headers=headers):
                status, response_headers, body = self.request('/owner-evidence/api/detail/' + '0' * 64,
                                                              headers=headers)
                self.assertNotEqual(status, 200)
                self.assertNotIn(b'visual', body)
                self.assertEqual(response_headers['Cache-Control'], 'no-store')
                status, _, body = self.request('/owner-evidence/api/queue', headers=headers)
                self.assertEqual(status, 403)
                self.assertNotIn(b'Sample meet', body)

    def test_duplicate_or_conflicting_security_headers_are_rejected(self):
        base = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                ('X-Freediving-Owner-Email', EMAIL)]
        for extra in ([('Host', HOST)], [('X-Freediving-Owner-Gateway', SECRET)],
                      [('X-Freediving-Owner-Email', EMAIL)],
                      [('X-Freediving-Owner-Gateway', SECRET + ', ' + SECRET)],
                      [('Origin', 'https://evil.example')],
                      [('Origin', 'https://poc.alphacompose.com'),
                       ('Origin', 'https://poc.alphacompose.com')],
                      [('Transfer-Encoding', 'chunked')], [('Content-Length', '1')]):
            with self.subTest(extra=extra):
                self.assertNotEqual(self.request(headers=base + extra)[0], 200)

    def test_only_fixed_read_routes_are_available(self):
        for path in ('/', '/assets/app.js', '/login', '/owner-evidence/login',
                     '/owner-evidence/api/corrections', '/owner-evidence/../api/overview',
                     '/owner-evidence/%2e%2e/api/overview', '/owner-evidence/api/unknown'):
            with self.subTest(path=path):
                self.assertEqual(self.request(path)[0], 404)
        self.assertEqual(self.request(method='POST')[0], 405)
        self.assertEqual(self.request(method='HEAD')[0], 200)
        self.assertEqual(self.request(headers=[('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                                               ('X-Freediving-Owner-Email', EMAIL),
                                               ('Origin', 'https://poc.alphacompose.com')])[0], 200)

    def test_read_only_browse_and_detail(self):
        status, _, body = self.request('/owner-evidence/api/roatan')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['total'], 0)
        self.assertEqual(self.request('/owner-evidence/api/roatan/3551/0')[0], 404)
        self.assertEqual(self.request('/owner-evidence/api/roatan?limit=1')[0], 404)
        self.assertEqual(self.request('/owner-evidence/api/roatan', method='POST')[0], 405)
        status, _, body = self.request('/owner-evidence/api/browse?kind=candidate_position&limit=1')
        self.assertEqual(status, 200)
        record = json.loads(body)['records'][0]
        status, _, body = self.request('/owner-evidence/api/detail/' + record['record_id'])
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['raw_fields'], {'Name': 'Ada'})
        status, _, body = self.request('/owner-evidence/api/queue?group=event_publication&limit=1')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['total'], 0)
        self.assertEqual(self.request('/owner-evidence/api/queue?limit=101')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/queue?group=athlete_identity&group=athlete_identity')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/queue', method='POST')[0], 405)
        self.assertEqual(self.request('/owner-evidence/api/browse?limit=101')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/browse?kind=gap&kind=gap')[0], 400)
        status, _, body = self.request('/owner-evidence/api/overview', method='HEAD')
        self.assertEqual(status, 200)
        self.assertEqual(body, b'')

    def test_comparison_api_is_bounded_authorized_and_read_only(self):
        status, _, body = self.request('/owner-evidence/api/comparisons?limit=1')
        self.assertEqual(status, 200)
        listing = json.loads(body)
        self.assertEqual(listing['total'], 1)
        comparison_id = listing['items'][0]['id']
        status, _, body = self.request('/owner-evidence/api/comparison/' + comparison_id)
        self.assertEqual(status, 200)
        self.assertIn('unavailable', json.loads(body))
        self.assertEqual(self.request('/owner-evidence/api/comparisons?limit=101')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/comparisons?offset=-1')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/comparisons?limit=1&limit=1')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/comparison/' + comparison_id,
                                      headers=[('Host', HOST)])[0], 403)
        self.assertEqual(self.request('/owner-evidence/api/comparison/' + comparison_id,
                                      method='POST')[0], 405)

    def test_missing_or_wrong_configuration_fails_before_listening(self):
        for absent in self.env:
            with self.subTest(absent=absent):
                with self.assertRaises(ValueError):
                    make_server(self.snapshot_dir, {k: v for k, v in self.env.items() if k != absent})
        with self.assertRaises(ValueError):
            make_server(self.snapshot_dir, {**self.env, 'OWNER_EVIDENCE_SNAPSHOT_SHA256': '0' * 64})
        with self.assertRaises(ValueError):
            make_server(Path(self.tmp.name) / 'missing', self.env)

    def test_decision_routes_require_store_and_return_private_queue(self):
        self.assertEqual(self.request('/owner-evidence/api/decisions')[0], 503)
        class Decisions:
            def queue(self, **filters):
                self.filters = filters
                return {'revision': 1, 'items': [{'id': 'decision-1'}], 'total': 1,
                        'scoreless_items': [], 'scoreless_total': 0}
            def inspect(self, decision_id):
                return {'id': decision_id, 'store_revision': 1, 'history': []}
            def preview(self, decision_id, action):
                return {'id': decision_id, 'action': action, 'affected': []}
            def projection(self): return {'snapshot_sha256': 'a' * 64}
            def audit_sample(self, *, limit): return {'items': [], 'sample_size': 0, 'blocking': False}
        self.server.decisions = Decisions()
        status, _, body = self.request('/owner-evidence/api/decisions?type=identity&status=pending&source=meet&limit=2')
        self.assertEqual(status, 200)
        listing = json.loads(body)
        self.assertEqual(listing['items'][0]['id'], 'decision-1')
        self.assertIn('csrf_token', listing)
        self.assertEqual(self.server.decisions.filters,
                         {'decision_type': 'identity', 'status': 'pending', 'source_name': 'meet', 'limit': 2, 'offset': 0})
        self.assertEqual(json.loads(self.request('/owner-evidence/api/decisions/decision-1')[2])['id'], 'decision-1')
        self.assertEqual(json.loads(self.request('/owner-evidence/api/decisions/decision-1/preview?action=reverse')[2])['action'], 'reverse')
        self.assertEqual(self.request('/owner-evidence/api/decisions?limit=101')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/decisions?type=x&type=x')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/decisions/decision-1/preview?action=invalid')[0], 400)
        self.assertEqual(json.loads(self.request('/owner-evidence/api/decisions/audit-sample')[2])['blocking'], False)

    def test_decision_action_checks_origin_csrf_revision_and_body(self):
        class Decisions:
            def queue(self, **_): return {'revision': 3, 'items': [], 'total': 0,
                                          'scoreless_items': [], 'scoreless_total': 0}
            def projection(self): return {'snapshot_sha256': 'a' * 64}
            def act(self, *args, **kwargs):
                self.call = (args, kwargs)
                return {'revision': 4, 'status': 'reversed'}
        self.server.decisions = Decisions()
        csrf = json.loads(self.request('/owner-evidence/api/decisions')[2])['csrf_token']
        payload = json.dumps({'action': 'reverse', 'expected_revision': 3,
                              'idempotency_key': 'retry-1', 'reason': 'owner correction',
                              'csrf_token': csrf}).encode()
        base = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                ('X-Freediving-Owner-Email', EMAIL), ('Origin', 'https://poc.alphacompose.com'),
                ('Content-Type', 'application/json'), ('X-Freediving-CSRF', csrf),
                ('Content-Length', str(len(payload)))]
        path = '/owner-evidence/api/decisions/decision-1/actions'
        self.assertEqual(self.request(path, method='POST', headers=base[:-1], body=payload)[0], 400)
        self.assertEqual(self.request(path, method='POST', headers=base[:-2] + base[-1:], body=payload)[0], 403)
        self.assertEqual(self.request(path, method='POST', headers=base, body=payload)[0], 200)
        self.assertEqual(self.server.decisions.call,
                         (('decision-1',), {'action': 'reverse', 'expected_revision': 3,
                                           'idempotency_key': 'retry-1',
                                           'actor': EMAIL, 'reason': 'owner correction'}))
        self.assertEqual(self.request('/owner-evidence/api/queue', method='POST', headers=base, body=payload)[0], 405)

    def test_machine_event_feed_requires_separate_token_and_signs_exact_payload(self):
        import hashlib
        import hmac
        token = 'separate-owner-import-token-for-tests'
        client_id = 'abc12345.access'
        self.server.import_token = token
        self.server.import_client_id = client_id
        class Decisions:
            def human_events(self, **kwargs):
                self.args = kwargs
                return {'store_revision': 3, 'events': [{'store_revision': 3, 'action': 'reverse'}],
                        'next_revision': 3}
        self.server.decisions = Decisions()
        path = '/owner-evidence/api/decision-events?after_revision=1'
        self.assertEqual(self.request(path)[0], 403)
        base = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                ('X-Freediving-Owner-Email', EMAIL)]
        self.assertEqual(self.request(path, headers=base + [('X-Freediving-Import-Token', 'wrong')])[0], 403)
        self.assertEqual(self.request(path, headers=base + [('X-Freediving-Import-Token', token)])[0], 403)
        machine = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                   ('X-Freediving-Owner-Machine', client_id), ('X-Freediving-Import-Token', token)]
        status, _, body = self.request(path, headers=machine)
        self.assertEqual(status, 200)
        envelope = json.loads(body)
        self.assertEqual(self.server.decisions.args, {'after_revision': 1})
        self.assertEqual(envelope['signature'], hmac.new(token.encode(),
                         envelope['payload_json'].encode(), hashlib.sha256).hexdigest())
        self.assertEqual(json.loads(envelope['payload_json'])['events'][0]['action'], 'reverse')

    def test_owner_correction_requires_explicit_option_and_same_csrf(self):
        class Decisions:
            def queue(self, **_): return {'revision': 3, 'items': [], 'total': 0,
                                          'scoreless_items': [], 'scoreless_total': 0}
            def projection(self): return {'snapshot_sha256': 'a' * 64}
            def inspect(self, _): return {'selected_option': 'same-person',
                                          'competing_options': ['different-person', 'unknown']}
            def act(self, *args, **kwargs):
                self.call = (args, kwargs)
                return {'status': 'human_corrected'}
        self.server.decisions = Decisions()
        csrf = json.loads(self.request('/owner-evidence/api/decisions')[2])['csrf_token']
        payload = json.dumps({'action': 'correct', 'expected_revision': 3,
                              'idempotency_key': 'correct-1', 'reason': 'checked source',
                              'csrf_token': csrf,
                              'correction': {'action': 'different-person'}}).encode()
        headers = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                   ('X-Freediving-Owner-Email', EMAIL), ('Origin', 'https://poc.alphacompose.com'),
                   ('Content-Type', 'application/json'), ('X-Freediving-CSRF', csrf),
                   ('Content-Length', str(len(payload)))]
        path = '/owner-evidence/api/decisions/decision-1/actions'
        self.assertEqual(self.request(path, method='POST', headers=headers, body=payload)[0], 200)
        self.assertEqual(self.server.decisions.call[1]['correction'], {'action': 'different-person'})
        bad = payload.replace(b'different-person', b'new-person')
        headers[-1] = ('Content-Length', str(len(bad)))
        self.assertEqual(self.request(path, method='POST', headers=headers, body=bad)[0], 400)

    def test_real_decision_store_is_bound_to_verified_snapshot(self):
        from test_owner_decision_store import proposal
        decision_path = Path(self.tmp.name) / 'durable-decisions' / 'ledger.sqlite'
        import_token = 'separate-owner-import-token-for-tests'
        import_client_id = 'abc12345.access'
        server = make_server(self.snapshot_dir, {**self.env, 'OWNER_EVIDENCE_DECISION_DB': str(decision_path),
                                                      'OWNER_EVIDENCE_IMPORT_TOKEN': import_token,
                                                      'OWNER_EVIDENCE_IMPORT_CLIENT_ID': import_client_id})
        self.addCleanup(server.server_close)
        self.assertEqual(server.decisions.projection()['snapshot_sha256'], self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'])
        self.assertTrue(decision_path.exists())
        self.assertFalse(decision_path.is_relative_to(self.snapshot_dir))
        evidence_id = server.query.browse(kind='candidate_position', limit=1)['records'][0]['record_id']
        rich_proposal = proposal('decision-1', evidence=evidence_id, status='automatic_approved')
        rich_proposal['supporting_evidence'] = ['synthetic citation ' + 'x' * 20000]
        server.decisions.register(self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'],
                                  rich_proposal,
                                  idempotency_key='register-decision-1')
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), thread.join(timeout=2)))
        def request(path, method='GET', payload=None, csrf=None, machine=False):
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
            headers = {'Host': HOST, 'X-Freediving-Owner-Gateway': SECRET,
                       'X-Freediving-Owner-Email': EMAIL}
            if machine:
                del headers['X-Freediving-Owner-Email']
                headers['X-Freediving-Owner-Machine'] = import_client_id
                headers['X-Freediving-Import-Token'] = import_token
            if method == 'POST':
                headers['Content-Type'] = 'application/json'
                if not machine:
                    headers['Origin'] = 'https://poc.alphacompose.com'
                    if csrf is not None:
                        headers['X-Freediving-CSRF'] = csrf
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            result = response.status, response.read()
            connection.close()
            return result
        status, data = request('/owner-evidence/api/decisions?status=automatic_approved')
        self.assertEqual(status, 200)
        listing = json.loads(data)
        self.assertEqual(listing['items'][0]['id'], 'decision-1')
        self.assertEqual(listing['active_snapshot_sha256'], self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'])
        status, data = request('/owner-evidence/api/decisions/decision-1/preview?action=reverse')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(data)['after']['decision-1'], 'reversed')
        payload = json.dumps({'action': 'reverse', 'expected_revision': listing['revision'],
                              'idempotency_key': 'reverse-decision-1', 'reason': 'owner review',
                              'csrf_token': listing['csrf_token']}).encode()
        status, data = request('/owner-evidence/api/decisions/decision-1/actions',
                               method='POST', payload=payload, csrf=listing['csrf_token'])
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(data)['effective_status'], 'reversed')
        self.assertEqual(request('/owner-evidence/api/decision-events?after_revision=0')[0], 403)
        event_status, event_bytes = request('/owner-evidence/api/decision-events?after_revision=0', machine=True)
        self.assertEqual(event_status, 200)
        envelope = json.loads(event_bytes)
        event = json.loads(envelope['payload_json'])['events'][0]
        self.assertEqual(event['action'], 'reverse')
        self.assertEqual(event['proposal']['id'], 'decision-1')
        self.assertEqual(event['snapshot_sha256'], self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'])
        ack_path = '/owner-evidence/api/decision-events/ack'
        ack = json.dumps({'target': 'flow-ledger', 'event': event,
                          'receipt': 'flow:committed'}).encode()
        self.assertGreater(len(ack), 16384)
        self.assertEqual(request(ack_path, method='POST', payload=ack)[0], 403)
        self.assertEqual(request(ack_path, method='POST', payload=ack, machine=True)[0], 200)
        self.assertEqual(request(ack_path, method='POST', payload=ack, machine=True)[0], 200)
        pg = json.dumps({'target': 'postgresql', 'event': event,
                         'receipt': 'pg:committed'}).encode()
        self.assertEqual(request(ack_path, method='POST', payload=pg, machine=True)[0], 200)
        self.assertEqual(server.decisions.delivery_checkpoints()['postgresql'], event['store_revision'])
        self.assertEqual(request('/owner-evidence/api/decisions/decision-1/actions',
                                 method='POST', payload=payload, csrf=listing['csrf_token'])[0], 200)
        stale = json.dumps({'action': 'approve', 'expected_revision': listing['revision'],
                            'idempotency_key': 'stale-approve', 'reason': 'outdated review',
                            'csrf_token': listing['csrf_token']}).encode()
        self.assertEqual(request('/owner-evidence/api/decisions/decision-1/actions',
                                 method='POST', payload=stale, csrf=listing['csrf_token'])[0], 409)
        self.assertEqual(request('/owner-evidence/api/decisions/audit-sample')[0], 200)


class RetainedSnapshotTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('OWNER_EVIDENCE_TEST_SNAPSHOT_DIR'), 'private retained snapshot not supplied')
    def test_corrected_snapshot_is_servable_without_exposing_records_in_output(self):
        directory = Path(os.environ['OWNER_EVIDENCE_TEST_SNAPSHOT_DIR'])
        digest = 'c681566922dc93adfb5d54db6d50c0fe8267961a0de32188cec0fc2d9990943e'
        env = {'OWNER_EVIDENCE_GATEWAY_SECRET': SECRET, 'OWNER_EVIDENCE_EMAILS': EMAIL,
               'OWNER_EVIDENCE_ORIGIN_HOST': HOST, 'OWNER_EVIDENCE_SNAPSHOT_SHA256': digest}
        with make_server(directory, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
                connection.request('GET', '/owner-evidence/api/overview', headers={
                    'Host': HOST, 'X-Freediving-Owner-Gateway': SECRET,
                    'X-Freediving-Owner-Email': EMAIL})
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                overview = json.loads(response.read())
                connection.close()
                self.assertEqual(overview['snapshot_sha256'], digest)
                self.assertIsNone(overview['confirmed_distinct_attempts'])
            finally:
                server.shutdown()
                thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
