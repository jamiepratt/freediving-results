import http.client
import json
import threading
import unittest
from pathlib import Path

from test_unified_evidence_query import snapshot
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_evidence_web import make_server


class WorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.server = make_server(snapshot(Path(self.tmp.name)), password='local secret')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_port
        self.host = f'127.0.0.1:{self.port}'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.port)
        connection.request(method, path, body=body, headers={'Host': self.host, **(headers or {})})
        response = connection.getresponse()
        data = response.read()
        result = response.status, dict(response.getheaders()), data
        connection.close()
        return result

    def login(self):
        status, headers, _ = self.request('POST', '/login', b'password=local+secret',
                                          {'Origin': f'http://{self.host}', 'Content-Type': 'application/x-www-form-urlencoded'})
        self.assertEqual(status, 303)
        return headers['Set-Cookie'].split(';', 1)[0]

    def test_private_api_and_assets_require_login(self):
        for path in ('/', '/assets/app.js', '/api/overview', '/api/browse', '/api/sources'):
            with self.subTest(path=path):
                status, headers, data = self.request('GET', path)
                self.assertEqual(status, 401)
                self.assertEqual(headers['Cache-Control'], 'no-store')
                self.assertNotIn(b'Sample meet', data)
        self.assertEqual(self.request('GET', '/login')[0], 200)

    def test_login_requires_exact_origin_and_host(self):
        body = b'password=local+secret'
        for headers in ({}, {'Origin': 'http://evil.example'}, {'Origin': f'http://{self.host}', 'Host': 'localhost:8000'}):
            with self.subTest(headers=headers):
                self.assertNotEqual(self.request('POST', '/login', body, headers)[0], 303)

    def test_authenticated_browse_detail_and_read_only_surface(self):
        cookie = self.login()
        headers = {'Cookie': cookie}
        status, security, body = self.request('GET', '/api/browse?kind=candidate_position&limit=1', headers=headers)
        self.assertEqual(status, 200)
        self.assertEqual(security['Cache-Control'], 'no-store')
        self.assertIn('Content-Security-Policy', security)
        item = json.loads(body)['records'][0]
        record_id = item['record_id']
        detail = json.loads(self.request('GET', f'/api/detail/{record_id}', headers=headers)[2])
        self.assertEqual(detail['raw_fields'], {'Name': 'Ada'})
        self.assertEqual(detail['citation'], {'page': 1, 'line': 3})
        self.assertEqual(json.loads(self.request('GET', '/api/gaps', headers=headers)[2])['total'], 1)
        self.assertEqual(json.loads(self.request('GET', '/api/relationships', headers=headers)[2])['total'], 1)
        self.assertEqual(json.loads(self.request('GET', '/api/sources', headers=headers)[2])[0]['status'], 'excluded')
        for method, path in (('POST', '/api/browse'), ('PUT', '/api/detail/' + record_id), ('DELETE', '/api/detail/' + record_id)):
            self.assertIn(self.request(method, path, headers=headers)[0], (403, 405))

    def test_query_bounds_and_asset_isolation(self):
        cookie = self.login()
        headers = {'Cookie': cookie}
        for path in ('/api/browse?limit=101', '/api/browse?offset=-1', '/api/browse?foo=x',
                     '/api/browse?source_name=' + 'a' * 201, '/assets/../scripts/unified_evidence_query.py',
                     '/api/detail/' + 'x' * 64):
            self.assertIn(self.request('GET', path, headers=headers)[0], (400, 404))
        status, _, body = self.request('GET', '/assets/app.js', headers=headers)
        self.assertEqual(status, 200)
        self.assertIn(b'textContent', body)
        self.assertNotIn(b'innerHTML', body)


REAL_SNAPSHOT = Path('/Users/jamiep/.codex/worktrees/1ad1/freediving-results/data/issue55-unified-snapshot-20260928')


@unittest.skipUnless(REAL_SNAPSHOT.exists(), 'private snapshot unavailable')
class RealSnapshotSmokeTest(unittest.TestCase):
    def test_real_snapshot_examples_stay_candidates_and_quarantined(self):
        from unified_evidence_query import SnapshotQuery
        with SnapshotQuery(REAL_SNAPSHOT) as query:
            self.assertEqual(query.overview()['snapshot_sha256'],
                             'c681566922dc93adfb5d54db6d50c0fe8267961a0de32188cec0fc2d9990943e')
            self.assertEqual(query.browse(source_name='komaros-visual', kind='candidate_position')['total'], 47)
            self.assertEqual(query.browse(source_name='gia', kind='candidate_position')['total'], 355)
            self.assertEqual(query.browse(source_name='firenze', kind='relationship')['total'], 1)
            gaps = query.browse(source_name='gap', collection='unparsed_rows', kind='gap')
            self.assertEqual(gaps['total'], 4)
            for row in gaps['records']:
                self.assertEqual(query.detail(row['record_id'])['raw']['assessment']['disposition'],
                                 'explicit_quarantine')

    def test_real_snapshot_served_only_after_login(self):
        server = make_server(REAL_SNAPSHOT, password='temporary test secret')
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        host = f'127.0.0.1:{server.server_port}'

        def request(method, path, body=None, headers=None):
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
            connection.request(method, path, body=body, headers={'Host': host, **(headers or {})})
            response = connection.getresponse()
            result = response.status, dict(response.getheaders()), response.read()
            connection.close()
            return result

        try:
            self.assertEqual(request('GET', '/api/browse?source_name=komaros-visual')[0], 401)
            status, headers, _ = request('POST', '/login', b'password=temporary+test+secret',
                                         {'Origin': f'http://{host}', 'Content-Type': 'application/x-www-form-urlencoded'})
            self.assertEqual(status, 303)
            cookie = headers['Set-Cookie'].split(';', 1)[0]
            status, _, body = request('GET', '/api/browse?source_name=komaros-visual&kind=candidate_position',
                                      headers={'Cookie': cookie})
            self.assertEqual(status, 200)
            self.assertEqual(json.loads(body)['total'], 47)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
