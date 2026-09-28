import hashlib
import http.client
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from route_roster_query import RouteRosterQuery
from owner_evidence_origin import make_server
from owner_evidence_web import make_server as make_local_server

ROSTER = Path('/Users/jamiep/.codex/private-corpora/issue55-route-roster-20260928')
SNAPSHOT = Path('/Users/jamiep/.codex/private-corpora/cmas-issue55-snapshot-20260928/snapshot')
ROSTER_SHA = '5865e082eb929d8995a2d42a24dab3b3765b4df65fdbbaf341a10f9dc33d24bd'
SNAPSHOT_SHA = '0285cd65ebf9f63a422c3ed7122aa6f59b97f06ac69e42d810e986b02579a9e6'


@unittest.skipUnless(ROSTER.exists() and SNAPSHOT.exists(), 'private route corpus unavailable')
class RouteRosterTest(unittest.TestCase):
    def test_bounded_roster_and_exact_source_links(self):
        from unified_evidence_query import SnapshotQuery
        with SnapshotQuery(SNAPSHOT) as snapshot:
            roster = RouteRosterQuery(ROSTER, ROSTER_SHA, snapshot)
            routes = roster.routes()
            self.assertEqual((routes['total'], routes['summary']['lead_count']), (8, 42))
            self.assertEqual(routes['roster_sha256'], ROSTER_SHA)
            self.assertEqual(routes['scope']['complete'], False)
            leads = roster.leads(route_id='ffessm', status='acquired', year='2026', limit=1, offset=0)
            self.assertEqual((leads['total'], len(leads['items'])), (9, 1))
            item = leads['items'][0]
            self.assertEqual(len(item['exact_source_records']), 1)
            self.assertEqual(item['exact_source_records'][0]['record_id'], item['snapshot_ids'][0])
            self.assertEqual(item['candidate_source_records'], [])
            self.assertEqual(roster.leads(status='missing')['total'], 0)
            self.assertEqual(roster.leads(relationship='mirror')['total'], 0)
            self.assertEqual(roster.leads(relationship='corroboration')['total'], 8)
            self.assertEqual(roster.leads(route_id='ffessm', offset=100)['items'], [])
            for options in ({'limit': 101}, {'offset': 100001}, {'year': '2024'}, {'status': 'bad'}):
                with self.subTest(options=options), self.assertRaises(ValueError):
                    roster.leads(**options)

    def test_digest_and_schema_mismatch_fail_closed(self):
        from unified_evidence_query import SnapshotQuery
        with SnapshotQuery(SNAPSHOT) as snapshot:
            with self.assertRaises(ValueError):
                RouteRosterQuery(ROSTER, '0' * 64, snapshot)
            with self.assertRaises(ValueError):
                RouteRosterQuery(ROSTER, ROSTER_SHA, snapshot_sha256='0' * 64)
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                (path / 'roster.json').write_bytes((ROSTER / 'roster.json').read_bytes())
                manifest = json.loads((ROSTER / 'manifest.json').read_text())
                manifest['schema'] = 'unknown/v1'
                (path / 'manifest.json').write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    RouteRosterQuery(path, ROSTER_SHA, snapshot)

    def test_private_origin_route_api_requires_owner_and_is_read_only(self):
        env = {'OWNER_EVIDENCE_GATEWAY_SECRET': 'a-private-gateway-secret-for-tests',
               'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
               'OWNER_EVIDENCE_SNAPSHOT_SHA256': SNAPSHOT_SHA,
               'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
               'OWNER_EVIDENCE_ROSTER_DIR': str(ROSTER),
               'OWNER_EVIDENCE_ROSTER_SHA256': ROSTER_SHA}
        with make_server(SNAPSHOT, env) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            def request(path, method='GET', authorized=True):
                conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
                headers = {'Host': env['OWNER_EVIDENCE_ORIGIN_HOST']}
                if authorized:
                    headers.update({'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                                    'X-Freediving-Owner-Email': env['OWNER_EVIDENCE_EMAILS']})
                conn.request(method, path, headers=headers)
                response = conn.getresponse()
                value = response.status, response.read()
                conn.close()
                return value
            try:
                self.assertEqual(request('/owner-evidence/api/routes', authorized=False)[0], 403)
                status, body = request('/owner-evidence/api/routes')
                self.assertEqual((status, json.loads(body)['total']), (200, 8))
                status, body = request('/owner-evidence/api/route-leads?route_id=ffessm&year=2026&limit=1')
                self.assertEqual((status, json.loads(body)['total']), (200, 9))
                self.assertEqual(request('/owner-evidence/api/route-leads?limit=101')[0], 400)
                self.assertEqual(request('/owner-evidence/api/route-leads?route_id=aida&route_id=aida')[0], 400)
                self.assertEqual(request('/owner-evidence/api/routes', method='POST')[0], 405)
            finally:
                server.shutdown()
                worker.join(timeout=2)

    def test_local_route_api_requires_login(self):
        with make_local_server(SNAPSHOT, 'local secret', ROSTER, ROSTER_SHA) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            host = f'127.0.0.1:{server.server_port}'
            def request(path, method='GET', body=None, headers=None):
                conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
                conn.request(method, path, body=body, headers={'Host': host, **(headers or {})})
                response = conn.getresponse()
                value = response.status, dict(response.getheaders()), response.read()
                conn.close()
                return value
            try:
                self.assertEqual(request('/api/routes')[0], 401)
                status, headers, _ = request('/login', 'POST', b'password=local+secret',
                                             {'Origin': f'http://{host}',
                                              'Content-Type': 'application/x-www-form-urlencoded'})
                self.assertEqual(status, 303)
                cookie = {'Cookie': headers['Set-Cookie'].split(';', 1)[0]}
                status, _, body = request('/api/route-leads?relationship=corroboration', headers=cookie)
                self.assertEqual((status, json.loads(body)['total']), (200, 8))
                self.assertEqual(request('/api/route-leads?limit=101', headers=cookie)[0], 400)
                self.assertEqual(request('/api/routes', 'PUT', headers=cookie)[0], 405)
            finally:
                server.shutdown()
                worker.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
