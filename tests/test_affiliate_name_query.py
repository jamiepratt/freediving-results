import hashlib
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest

from test_unified_evidence_query import snapshot

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_evidence_web import make_server


def digest(data):
    return hashlib.sha256(data).hexdigest()


class AffiliateNameViewTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        (root / 'snapshot').mkdir()
        self.snapshot_dir = snapshot(root / 'snapshot')
        self.input_dir = root / 'affiliate'
        self.input_dir.mkdir()
        original = b'<html><body><p>\xe5\xb1\xb1\xe7\x94\xb0 Maya</p></body></html>'
        source = self.input_dir / 'original.html'
        source.write_bytes(original)
        source_sha = digest(original)
        receipt = {'sha256': source_sha, 'requested_url': 'https://example.org/list',
                   'final_url': 'https://example.org/list', 'retrieved_at': '2026-10-02T00:00:00Z'}
        receipt_path = self.input_dir / 'receipt.json'
        receipt_path.write_text(json.dumps(receipt))
        manifest = (self.snapshot_dir / 'manifest.json').read_bytes()
        sqlite = (self.snapshot_dir / 'snapshot.sqlite').read_bytes()
        self.input = {
            'schema': 'affiliate-name-input/v1',
            'snapshot': {'manifest_sha256': digest(manifest), 'sqlite_sha256': digest(sqlite),
                         'cutoff': json.loads(manifest)['cutoff']},
            'sources': [{'source_sha256': source_sha, 'path': str(source),
                         'receipt_path': str(receipt_path), 'publisher': 'Example federation',
                         'discovery_url': receipt['requested_url'], 'requested_url': receipt['requested_url'],
                         'final_url': receipt['final_url'],
                         'retrieved_at': receipt['retrieved_at'], 'source_date': '2026-09-01'}],
            'assertions': [{'evidence_key': 'name:1', 'source_sha256': source_sha,
                            'parser_version': 'affiliate-html/v1',
                            'source_position': {'format': 'html', 'selector': 'p', 'ordinal': 1},
                            'original_name': '\u5c71\u7530', 'publisher_romanization': 'Maya',
                            'person_id': 'publisher:1', 'candidate_observation_refs': ['candidate:1'],
                            'uncertainty': ['identity unreviewed']}],
            'gaps': [], 'roster': {'checked': 1}}
        self.input_path = self.input_dir / 'input.json'
        self.save_input()

    def tearDown(self):
        self.tmp.cleanup()

    def save_input(self):
        self.input_path.write_text(json.dumps(self.input, ensure_ascii=False))
        self.input_sha = digest(self.input_path.read_bytes())

    def request(self, server, method, path, cookie=None):
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
        headers = {'Host': f'127.0.0.1:{server.server_port}'}
        if cookie:
            headers['Cookie'] = cookie
        if method == 'POST':
            headers['Origin'] = f'http://127.0.0.1:{server.server_port}'
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        connection.request(method, path, b'password=secret' if method == 'POST' else None, headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_checked_names_and_original_are_authenticated_read_only(self):
        with make_server(self.snapshot_dir, 'secret', affiliate_name_path=self.input_path,
                         affiliate_name_sha256=self.input_sha) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                self.assertEqual(self.request(server, 'GET', '/api/affiliate-names')[0], 401)
                cookie = self.request(server, 'POST', '/login')[1]['Set-Cookie'].split(';', 1)[0]
                status, _, body = self.request(server, 'GET', '/api/affiliate-names', cookie)
                self.assertEqual(status, 200)
                listing = json.loads(body)
                self.assertEqual(listing['assertions'][0]['original_name'], '\u5c71\u7530')
                self.assertEqual(listing['assertions'][0]['source_position']['ordinal'], 1)
                sha = self.input['sources'][0]['source_sha256']
                status, headers, body = self.request(server, 'GET', '/api/affiliate-names/source/' + sha, cookie)
                self.assertEqual(status, 200)
                self.assertTrue(headers['Content-Type'].startswith('text/plain'))
                self.assertIn(b'\xe5\xb1\xb1\xe7\x94\xb0', body)
                self.assertEqual(self.request(server, 'PUT', '/api/affiliate-names', cookie)[0], 405)
            finally:
                server.shutdown(); worker.join()

    def test_mismatched_snapshot_source_and_receipt_fail_closed(self):
        from affiliate_name_query import AffiliateNameQuery
        from unified_evidence_query import SnapshotQuery
        with SnapshotQuery(self.snapshot_dir) as snap:
            self.input['snapshot']['manifest_sha256'] = '0' * 64
            self.save_input()
            with self.assertRaises(ValueError):
                AffiliateNameQuery(self.input_path, self.input_sha, self.snapshot_dir, snap)
            self.input['snapshot']['manifest_sha256'] = digest((self.snapshot_dir / 'manifest.json').read_bytes())
            self.input['sources'][0]['source_sha256'] = '0' * 64
            self.save_input()
            with self.assertRaises(ValueError):
                AffiliateNameQuery(self.input_path, self.input_sha, self.snapshot_dir, snap)
            self.input['sources'][0]['source_sha256'] = digest((self.input_dir / 'original.html').read_bytes())
            self.input['sources'][0]['final_url'] = 'https://wrong.example/'
            self.save_input()
            with self.assertRaises(ValueError):
                AffiliateNameQuery(self.input_path, self.input_sha, self.snapshot_dir, snap)

    def test_modified_original_or_receipt_is_rejected_after_open(self):
        from affiliate_name_query import AffiliateNameQuery
        from unified_evidence_query import SnapshotQuery
        with SnapshotQuery(self.snapshot_dir) as snap:
            query = AffiliateNameQuery(self.input_path, self.input_sha, self.snapshot_dir, snap)
            source_sha = self.input['sources'][0]['source_sha256']
            self.assertIn('Maya', query.original(source_sha))
            source = self.input_dir / 'original.html'
            original = source.read_bytes()
            source.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                query.original(source_sha)
            source.write_bytes(original)
            (self.input_dir / 'receipt.json').write_text('{}')
            with self.assertRaises(ValueError):
                query.listing()
