import http.client
import json
import os
from pathlib import Path
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
