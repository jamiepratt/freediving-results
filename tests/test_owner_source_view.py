import hashlib
import http.client
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_evidence_origin import make_server
from private_source_bundle import build

HOST = 'owner-private.alphacompose.com'
SECRET = 'a-private-gateway-secret-for-tests'
EMAIL = 'owner@example.com'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fixture(root):
    original = root / 'original.json'
    original.write_text(json.dumps({'data': [{'Name': 'Ada'}, {'Name': 'Bea'}]}))
    digest = sha(original.read_bytes())
    packet = root / 'packet.json'
    packet.write_text(json.dumps({'schema': 'visual/v1', 'source': {'id': 'sha256:' + digest},
        'positions': [{'fields': {'Name': 'Ada'}, 'locator': 'row index zero based 0',
                       'parsed_fields': {'score': '3:00'}},
                      {'fields': {'Name': 'Wrong'}, 'locator': 'row index zero based 1'}]}))
    snapshot = root / 'snapshot'
    subprocess.run([sys.executable, str(ROOT / 'scripts/unified_evidence_snapshot.py'), 'build',
                    '--cutoff', '2026-09-28T12:00:00Z', '--input', 'visual=' + str(packet),
                    '--output-dir', str(snapshot)], check=True, capture_output=True)
    snapshot_file = snapshot / 'snapshot.sqlite'
    snapshot_digest = sha(snapshot_file.read_bytes())
    inventory = root / 'inventory.json'
    inventory.write_text(json.dumps({'sources': [{'id': 'sha256:' + digest, 'sha256': digest,
        'bytes': original.stat().st_size, 'content_type': 'application/json', 'source_path': str(original),
        'receipt': {}, 'classification': 'eligible', 'metadata': {}},
        {'id': 'sha256:' + snapshot_digest, 'sha256': snapshot_digest,
         'bytes': snapshot_file.stat().st_size, 'content_type': 'application/vnd.sqlite3',
         'source_path': str(snapshot_file), 'receipt': {}, 'classification': 'eligible', 'metadata': {}}]}))
    bundle = root / 'bundle'
    build(inventory, bundle)
    manifest_sha = sha((bundle / 'manifest.json').read_bytes())
    snapshot_sha = json.loads((snapshot / 'manifest.json').read_text())['snapshot_sha256']
    env = {'OWNER_EVIDENCE_GATEWAY_SECRET': SECRET, 'OWNER_EVIDENCE_EMAILS': EMAIL,
           'OWNER_EVIDENCE_ORIGIN_HOST': HOST, 'OWNER_EVIDENCE_SNAPSHOT_SHA256': snapshot_sha,
           'OWNER_EVIDENCE_SOURCE_BUNDLE_DIR': str(bundle),
           'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256': manifest_sha}
    return snapshot, bundle, env


class SourceViewTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.snapshot, self.bundle, self.env = fixture(Path(self.tmp.name))
        self.server = make_server(self.snapshot, self.env)
        self.addCleanup(self.server.server_close)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)
        self.rows = self.server.query.browse(kind='candidate_position')['records']

    def stop(self):
        self.server.shutdown()
        self.thread.join(timeout=2)

    def request(self, path, authorized=True):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=4)
        headers = {'Host': HOST}
        if authorized:
            headers.update({'X-Freediving-Owner-Gateway': SECRET, 'X-Freediving-Owner-Email': EMAIL})
        connection.request('GET', path, headers=headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_exact_json_row_requires_authenticated_record_and_matching_raw_fields(self):
        first, second = [r['record_id'] for r in self.rows]
        url = '/owner-evidence/api/source-view/' + first
        self.assertEqual(self.request(url, authorized=False)[0], 403)
        status, headers, body = self.request(url)
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data['source_sha256'], sha((self.bundle / 'objects' / data['source_sha256']).read_bytes()))
        self.assertEqual(data['locator'], 'data[0]')
        self.assertEqual(data['source_value'], {'Name': 'Ada'})
        self.assertEqual(data['parsed_fields'], {'score': '3:00'})
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(self.request('/owner-evidence/api/source-view/' + second)[0], 422)
        self.assertEqual(self.request('/owner-evidence/api/source-view/' + '0' * 64)[0], 404)
        self.assertEqual(self.request(url + '?path=anything')[0], 404)
        self.assertEqual(self.request(url)[2], body)

    def test_bundle_pin_and_record_key_reject_unrelated_objects(self):
        first = self.rows[0]['record_id']
        digest = self.server.query.detail(first)['source_object_id'].split(':', 1)[1]
        self.assertEqual(self.request('/owner-evidence/api/source-view/' + digest)[0], 404)
        with self.assertRaises(ValueError):
            make_server(self.snapshot, {**self.env, 'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256': '0' * 64})
        with self.assertRaises(ValueError):
            make_server(self.snapshot, {k: v for k, v in self.env.items()
                                        if k != 'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256'})

    def test_changed_object_fails_closed_after_startup(self):
        first = self.rows[0]['record_id']
        digest = next(x['sha256'] for x in json.loads((self.bundle / 'manifest.json').read_text())['sources']
                      if x['content_type'] == 'application/json')
        (self.bundle / 'objects' / digest).write_text('{"data":[]}')
        self.assertEqual(self.request('/owner-evidence/api/source-view/' + first)[0], 503)


if __name__ == '__main__':
    unittest.main()

REAL_BUNDLE = Path('/Users/jamiep/Documents/ChatGPT/freediving-results/data/owner-evidence-source-bundle-20260928-recovery-v2/bundle-validated')


@unittest.skipUnless(REAL_BUNDLE.is_dir(), 'retained private source bundle unavailable')
class RetainedSourceViewTest(unittest.TestCase):
    def test_cited_json_and_pdf_views_do_not_write_source_bundle(self):
        manifest = json.loads((REAL_BUNDLE / 'manifest.json').read_text())
        sources_before = {(REAL_BUNDLE / 'objects' / x['sha256']).stat().st_mtime_ns
                          for x in manifest['sources'] if x['status'] == 'included'}
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'snapshot.sqlite').write_bytes((REAL_BUNDLE / 'objects' /
                'c681566922dc93adfb5d54db6d50c0fe8267961a0de32188cec0fc2d9990943e').read_bytes())
            (root / 'manifest.json').write_bytes((REAL_BUNDLE / 'objects' /
                '8fa25c505526a17ae0c5071a694b2e40c19cc4d6b97242a856038dfec3c0a85d').read_bytes())
            env = {'OWNER_EVIDENCE_GATEWAY_SECRET': SECRET, 'OWNER_EVIDENCE_EMAILS': EMAIL,
                   'OWNER_EVIDENCE_ORIGIN_HOST': HOST,
                   'OWNER_EVIDENCE_SNAPSHOT_SHA256': 'c681566922dc93adfb5d54db6d50c0fe8267961a0de32188cec0fc2d9990943e',
                   'OWNER_EVIDENCE_SOURCE_BUNDLE_DIR': str(REAL_BUNDLE),
                   'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256': sha((REAL_BUNDLE / 'manifest.json').read_bytes())}
            with make_server(root, env) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    def request(path):
                        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=20)
                        connection.request('GET', path, headers={'Host': HOST,
                            'X-Freediving-Owner-Gateway': SECRET, 'X-Freediving-Owner-Email': EMAIL})
                        response = connection.getresponse()
                        result = response.status, response.read()
                        connection.close()
                        return result
                    json_id = '067f2110603458e3fdbe34b53fa58b1017c10d12181c35bf6a394b183d05f78d'
                    status, body = request('/owner-evidence/api/source-view/' + json_id)
                    self.assertEqual(status, 200)
                    selected = json.loads(body)
                    self.assertEqual(selected['locator'], 'data[24]')
                    self.assertEqual(selected['source_value']['PlaName'], 'Edmund')
                    pdf_id = 'c333dea8c0fa8dca170f29d5395606204d897b5c381b8e206a0028fad6edca07'
                    path = '/owner-evidence/api/source-view/' + pdf_id
                    self.assertEqual(json.loads(request(path)[1])['page'], 15)
                    self.assertEqual(request(path + '/page/14')[0], 404)
                    status, image = request(path + '/page/15')
                    self.assertEqual(status, 200)
                    self.assertTrue(image.startswith(b'\x89PNG\r\n\x1a\n'))
                    self.assertEqual(request(path + '/page/15'), (status, image))
                finally:
                    server.shutdown()
                    thread.join(timeout=2)
        self.assertEqual(sources_before, {(REAL_BUNDLE / 'objects' / x['sha256']).stat().st_mtime_ns
                          for x in manifest['sources'] if x['status'] == 'included'})
