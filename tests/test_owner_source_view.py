import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_evidence_origin import make_server
from owner_evidence_web import make_server as make_local_server
from owner_source_view import OriginalSourceView, SourceViewError
from unified_evidence_query import SnapshotQuery
from private_source_bundle import build
from vestico_safe_derivative import canonical_bytes, derive

HOST = 'owner-private.alphacompose.com'
SECRET = 'a-private-gateway-secret-for-tests'
EMAIL = 'owner@example.com'
ROATAN_SNAPSHOT = Path('/Users/jamiep/.codex/private-corpora/roatan-issue55-snapshot-20260928/snapshot')
ROATAN_BUNDLE = Path('/Users/jamiep/Documents/ChatGPT/freediving-results/data/owner-evidence-source-bundle-20260928-roatan-v5/bundle-validated')
V7_SNAPSHOT = Path('/Users/jamiep/.codex/private-corpora/issue55-aida-eindhoven-snapshot-20260928-v7/snapshot')
V7_BUNDLE = Path('/Users/jamiep/.codex/private-corpora/issue55-aida-eindhoven-bundle-20260928/bundle-validated')
V8_SNAPSHOT = Path('/Users/jamiep/.codex/private-corpora/issue55-unified-snapshot-20260928-v8/snapshot')
V8_BUNDLE = Path('/Users/jamiep/.codex/private-corpora/issue55-v8-bundle-20260928/bundle-validated')
V8_BUNDLE_SHA = 'ec7ce579e525a54f4920f5e4c615848c4562099266f15191f1b5c5f58df07431'


@unittest.skipUnless(V8_SNAPSHOT.exists() and V8_BUNDLE.exists(), 'private v8 evidence unavailable')
class V8SourceViewTest(unittest.TestCase):
    def test_loopback_jpeg_route_requires_login_and_exact_record(self):
        with make_local_server(V8_SNAPSHOT, 'local secret', source_bundle_dir=V8_BUNDLE,
                               source_bundle_sha256=V8_BUNDLE_SHA) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            host = f'127.0.0.1:{server.server_port}'
            def request(path, method='GET', headers=None, body=None):
                conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
                conn.request(method, path, body=body, headers={'Host': host, **(headers or {})})
                response = conn.getresponse()
                result = response.status, dict(response.getheaders()), response.read()
                conn.close()
                return result
            try:
                with SnapshotQuery(V8_SNAPSHOT) as query:
                    record_id = query.browse(source_name='san-mauro-jpg', collection='positions', limit=1)['records'][0]['record_id']
                    source_sha = query.detail(record_id)['source_object_id'].removeprefix('sha256:')
                url = '/api/source-view/' + record_id + '/image'
                self.assertEqual(request(url)[0], 401)
                status, headers, _ = request('/login', 'POST',
                    {'Origin': 'http://' + host, 'Content-Type': 'application/x-www-form-urlencoded'},
                    b'password=local+secret')
                self.assertEqual(status, 303)
                cookie = {'Cookie': headers['Set-Cookie'].split(';', 1)[0]}
                status, headers, data = request(url, headers=cookie)
                self.assertEqual((status, headers['Content-Type']), (200, 'image/jpeg'))
                self.assertEqual(sha(data), source_sha)
                self.assertEqual(request('/api/source-view/' + source_sha + '/image', headers=cookie)[0], 404)
                self.assertEqual(request(url, method='PUT', headers=cookie)[0], 405)
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_citation_tampering_rejects_ffessm_and_jpeg(self):
        with SnapshotQuery(V8_SNAPSHOT) as query:
            viewer = OriginalSourceView(V8_BUNDLE, V8_BUNDLE_SHA, query.manifest['snapshot_sha256'])
            pdf = query.detail(query.browse(source_name='ffessm-rankings', collection='positions', limit=1)['records'][0]['record_id'])
            jpg = query.detail(query.browse(source_name='san-mauro-jpg', collection='positions', limit=1)['records'][0]['record_id'])
            observation = query.detail(query.browse(source_name='san-mauro-jpg', collection='observation_versions', limit=1)['records'][0]['record_id'])
            for changed in (dict(pdf, citation='page 2 line 9 column start 1 column end 119'),
                            dict(jpg, citation=dict(jpg['citation'], printed_row=2))):
                with self.assertRaises(SourceViewError) as raised:
                    viewer.inspect(changed)
                self.assertEqual(raised.exception.status, 422)
            with self.assertRaises(SourceViewError) as raised:
                viewer.inspect(observation)
            self.assertEqual(raised.exception.status, 422)

    def test_private_origin_replays_cited_ffessm_pdf_and_san_mauro_jpg(self):
        env = {'OWNER_EVIDENCE_GATEWAY_SECRET': SECRET, 'OWNER_EVIDENCE_ORIGIN_HOST': HOST,
               'OWNER_EVIDENCE_EMAILS': EMAIL,
               'OWNER_EVIDENCE_SNAPSHOT_SHA256': '40997d52fc409647f4ab3cbacb8e926abcd5920401aa224f5c73195a1ff89fb3',
               'OWNER_EVIDENCE_SOURCE_BUNDLE_DIR': str(V8_BUNDLE),
               'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256': V8_BUNDLE_SHA}
        with make_server(V8_SNAPSHOT, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            def request(path, method='GET', authorized=True):
                conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=10)
                headers = {'Host': HOST}
                if authorized:
                    headers.update({'X-Freediving-Owner-Gateway': SECRET, 'X-Freediving-Owner-Email': EMAIL})
                conn.request(method, path, headers=headers)
                response = conn.getresponse()
                result = response.status, dict(response.getheaders()), response.read()
                conn.close()
                return result
            try:
                with SnapshotQuery(V8_SNAPSHOT) as query:
                    pdf_id = query.browse(source_name='ffessm-rankings', collection='positions', limit=1)['records'][0]['record_id']
                    daily_id = query.browse(source_name='ffessm-daily', collection='observations', limit=1)['records'][0]['record_id']
                    jpg_id = query.browse(source_name='san-mauro-jpg', collection='positions', limit=1)['records'][0]['record_id']
                    aggregate_id = query.browse(source_name='san-mauro-jpg', collection='positions', offset=150, limit=1)['records'][0]['record_id']
                    aida_id = query.browse(source_name='mabini-4545-2025-05-01', limit=1)['records'][0]['record_id']
                pdf = '/owner-evidence/api/source-view/' + pdf_id
                jpg = '/owner-evidence/api/source-view/' + jpg_id
                self.assertEqual(json.loads(request(pdf)[2])['format'], 'pdf')
                self.assertEqual(request(pdf + '/page/1')[1]['Content-Type'], 'image/png')
                daily = '/owner-evidence/api/source-view/' + daily_id
                self.assertEqual(json.loads(request(daily)[2])['format'], 'pdf')
                self.assertEqual(request(daily + '/page/1')[1]['Content-Type'], 'image/png')
                self.assertEqual(json.loads(request(jpg)[2])['format'], 'jpeg')
                self.assertEqual(json.loads(request('/owner-evidence/api/source-view/' + aggregate_id)[2])['format'], 'jpeg')
                status, headers, body = request(jpg + '/image')
                self.assertEqual((status, headers['Content-Type']), (200, 'image/jpeg'))
                self.assertTrue(body.startswith(b'\xff\xd8\xff'))
                self.assertEqual(request(jpg + '/image', authorized=False)[0], 403)
                self.assertEqual(request(jpg + '/image', method='POST')[0], 405)
                self.assertEqual(request('/owner-evidence/api/source-view/' + aida_id + '/image')[0], 404)
                self.assertEqual(json.loads(request('/owner-evidence/api/source-view/' + aida_id)[2])['original_replay'],
                                 'restricted_original_required')
            finally:
                server.shutdown()
                thread.join(timeout=2)


@unittest.skipUnless(V7_SNAPSHOT.exists() and V7_BUNDLE.exists(), 'private v7 evidence unavailable')
class V7SourceViewTest(unittest.TestCase):
    def test_local_bundle_route_requires_login_and_replays_packet(self):
        with make_local_server(V7_SNAPSHOT, 'local secret', source_bundle_dir=V7_BUNDLE,
                               source_bundle_sha256='faf181e28d6ed3c39072ba96bbe20df295f4b801321490e9818ba0ca77a533eb') as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            host = f'127.0.0.1:{server.server_port}'
            with SnapshotQuery(V7_SNAPSHOT) as query:
                record_id = query.browse(source_name='aida-4408-2025-08-30', limit=1)['records'][0]['record_id']
            def request(path, method='GET', headers=None, body=None):
                conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
                conn.request(method, path, body=body, headers={'Host': host, **(headers or {})})
                response = conn.getresponse()
                value = response.status, dict(response.getheaders()), response.read()
                conn.close()
                return value
            try:
                url = '/api/source-view/' + record_id
                self.assertEqual(request(url)[0], 401)
                status, headers, _ = request('/login', 'POST',
                    {'Origin': 'http://' + host, 'Content-Type': 'application/x-www-form-urlencoded'},
                    b'password=local+secret')
                self.assertEqual(status, 303)
                cookie = {'Cookie': headers['Set-Cookie'].split(';', 1)[0]}
                status, _, body = request(url, headers=cookie)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)['format'], 'cited_html_packet')
                self.assertEqual(request(url, method='PUT', headers=cookie)[0], 405)
            finally:
                server.shutdown()
                worker.join(timeout=2)

    def test_selected_html_packet_and_eindhoven_json_replay_exact_citations(self):
        with SnapshotQuery(V7_SNAPSHOT) as query:
            viewer = OriginalSourceView(V7_BUNDLE,
                'faf181e28d6ed3c39072ba96bbe20df295f4b801321490e9818ba0ca77a533eb',
                query.manifest['snapshot_sha256'])
            aida = query.detail(query.browse(source_name='aida-4408-2025-08-30', limit=1)['records'][0]['record_id'])
            shown = viewer.inspect(aida)
            self.assertEqual(shown['format'], 'cited_html_packet')
            self.assertEqual(shown['original_replay'], 'restricted_original_required')
            self.assertEqual(shown['source_value']['Diver']['value'], aida['raw_fields']['Diver']['value'])
            with self.assertRaises(SourceViewError) as raised:
                viewer.inspect(dict(aida, citation=dict(aida['citation'], tbody_row=2)))
            self.assertEqual(raised.exception.status, 422)
            result = query.detail(query.browse(source_name='eindhoven-noxy5', collection='result_rows', limit=1)['records'][0]['record_id'])
            exact = viewer.inspect(result)
            self.assertEqual(exact['format'], 'json')
            self.assertEqual(exact['source_value'], result['raw_fields'])
            self.assertEqual(exact['locator'], result['citation']['json_pointer'])
            with self.assertRaises(SourceViewError) as raised:
                viewer.inspect(dict(result, citation=dict(result['citation'], json_pointer='/rows/0')))
            self.assertEqual(raised.exception.status, 422)


@unittest.skipUnless(ROATAN_SNAPSHOT.exists() and ROATAN_BUNDLE.exists(), 'private Roatan evidence unavailable')
class RoatanOriginalViewTest(unittest.TestCase):
    def test_owner_routes_are_authenticated_bounded_and_read_only(self):
        original_snapshot_sha = sha((ROATAN_SNAPSHOT / 'snapshot.sqlite').read_bytes())
        env = {'OWNER_EVIDENCE_GATEWAY_SECRET': SECRET, 'OWNER_EVIDENCE_ORIGIN_HOST': HOST,
               'OWNER_EVIDENCE_EMAILS': EMAIL,
               'OWNER_EVIDENCE_SNAPSHOT_SHA256': '30910ab400071613c902a23e3312072cf48abf6130928efad6ec0a6a1a76fefb',
               'OWNER_EVIDENCE_SOURCE_BUNDLE_DIR': str(ROATAN_BUNDLE),
               'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256': '07be5bbf08b604f7b53e38c3f90525ca5ee94ac5b2047ae54a20328141fd256d'}
        with make_server(ROATAN_SNAPSHOT, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            def request(path, method='GET', authorized=True):
                conn = http.client.HTTPConnection('127.0.0.1', server.server_port)
                headers = {'Host': HOST}
                if authorized:
                    headers.update({'X-Freediving-Owner-Gateway': SECRET, 'X-Freediving-Owner-Email': EMAIL})
                conn.request(method, path, headers=headers)
                response = conn.getresponse()
                result = response.status, response.read()
                conn.close()
                return result
            try:
                status, body = request('/owner-evidence/api/roatan')
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)['total'], 31)
                status, body = request('/owner-evidence/api/roatan/3551/5')
                self.assertEqual(status, 200)
                position = json.loads(body)
                self.assertEqual(position['final_depth'], '34')
                source_path = '/owner-evidence/api/source-view/' + position['position_record_id']
                status, body = request(source_path)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)['source_value']['ParPrintName'], 'LU San-Jen')
                self.assertEqual(request(source_path, authorized=False)[0], 403)
                self.assertEqual(request(source_path, method='POST')[0], 405)
                self.assertEqual(request('/owner-evidence/api/roatan/3551/5', authorized=False)[0], 403)
                self.assertEqual(request('/owner-evidence/api/roatan/3551/999')[0], 404)
                self.assertEqual(request('/owner-evidence/api/roatan/3551/5', method='POST')[0], 405)
            finally:
                server.shutdown()
                thread.join()
        self.assertEqual(sha((ROATAN_SNAPSHOT / 'snapshot.sqlite').read_bytes()), original_snapshot_sha)

    def test_exact_top_level_array_row_and_citation(self):
        with SnapshotQuery(ROATAN_SNAPSHOT) as query:
            row = query.roatan_position(3551, 5)
            detail = query.detail(row['position_record_id'])
            wrong = dict(detail, citation=dict(detail['citation'], **{'row-index-zero-based': 6}))
            viewer = OriginalSourceView(ROATAN_BUNDLE,
                '07be5bbf08b604f7b53e38c3f90525ca5ee94ac5b2047ae54a20328141fd256d',
                query.manifest['snapshot_sha256'])
            shown = viewer.inspect(detail)
            with self.assertRaises(SourceViewError) as raised:
                viewer.inspect(wrong)
        self.assertEqual(shown['locator'], '[5]')
        self.assertEqual(shown['source_value']['ParPrintName'], 'LU San-Jen')
        self.assertEqual(raised.exception.status, 422)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pdf_bytes():
    parts = [b'%PDF-1.4\n']
    offsets = [0]
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
               b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>',
               b'<< /Length 0 >>\nstream\n\nendstream']
    for index, obj in enumerate(objects, 1):
        offsets.append(sum(map(len, parts)))
        parts.append(f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n')
    xref = sum(map(len, parts))
    parts.append(b'xref\n0 5\n0000000000 65535 f \n')
    parts.extend(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
    parts.append(f'trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    return b''.join(parts)


def fixture(root, *, original_bytes=None, content_type='application/json', positions=None,
            classification='eligible', safe_derivative=False, schema='visual/v1', collection='positions'):
    original = root / ('original.pdf' if content_type == 'application/pdf' else 'original.json')
    original.write_bytes(original_bytes if original_bytes is not None else
                         json.dumps({'data': [{'Name': 'Ada'}, {'Name': 'Bea'}]}).encode())
    digest = sha(original.read_bytes())
    if positions is None:
        positions = [{'fields': {'Name': 'Ada'}, 'locator': 'row index zero based 0',
                      'parsed_fields': {'score': '3:00'}},
                     {'fields': {'Name': 'Wrong'}, 'locator': 'row index zero based 1'},
                     {'fields': {'Name': 'Bea'}, 'locator': 'row index zero based 999'},
                     {'fields': {'Name': 'Bea'}, 'locator': 'not an original row'}]
    packet = root / 'packet.json'
    packet.write_text(json.dumps({'schema': schema, 'source': {'id': 'sha256:' + digest},
        collection: positions, 'source_gaps': [{'id': 'gap', 'status': 'unresolved'}]}))
    snapshot = root / 'snapshot'
    subprocess.run([sys.executable, str(ROOT / 'scripts/unified_evidence_snapshot.py'), 'build',
                    '--cutoff', '2026-09-28T12:00:00Z', '--input', 'visual=' + str(packet),
                    '--output-dir', str(snapshot)], check=True, capture_output=True)
    snapshot_file = snapshot / 'snapshot.sqlite'
    snapshot_digest = sha(snapshot_file.read_bytes())
    original_entry = {'id': 'sha256:' + digest, 'sha256': digest,
        'bytes': original.stat().st_size, 'content_type': content_type, 'source_path': str(original),
        'receipt': {'acquisition_id': '1' * 64,
                    'discovery_url': 'https://example.test/results?token=private',
                    'final_url': 'https://example.test/final?secret=private',
                    'retrieved_at': '2026-09-28T12:00:00Z', 'selected_view': 'results'},
        'classification': classification, 'metadata': {}}
    if classification != 'eligible':
        original_entry['reason'] = 'restricted test original'
    derivative_entry = None
    if safe_derivative:
        source_id = original_entry['id']
        derivative = root / 'safe-derivative.json'
        derivative.write_bytes(canonical_bytes(derive(original.read_bytes(), source_id=source_id,
                                                     acquisition_id='1' * 64)))
        derivative_digest = sha(derivative.read_bytes())
        original_entry['derivative'] = {'id': 'safe-derivative:' + digest,
                                        'sha256': derivative_digest,
                                        'schema': 'vestico-safe-result-tables/v1'}
        derivative_entry = {'id': 'safe-derivative:' + digest, 'sha256': derivative_digest,
            'bytes': derivative.stat().st_size, 'content_type': 'application/json',
            'source_path': str(derivative), 'receipt': {}, 'classification': 'eligible',
            'metadata': {}}
    inventory = root / 'inventory.json'
    inventory.write_text(json.dumps({'sources': [original_entry, *([derivative_entry] if derivative_entry else []),
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
    def test_retained_candidate_pdf_page_requires_exact_record_citation(self):
        root = Path(self.tmp.name) / 'retained-candidate-pdf'
        root.mkdir()
        citation = 'page 1 line 2 column start 3 column end 12'
        coords = {'page': 1, 'line': 2, 'column-start': 3, 'column-end': 12}
        good = {'source_id': 'sha256:' + sha(pdf_bytes()), 'citation': citation,
                'candidate': {'coordinates': coords, 'raw': {'fields': {'Name': 'Ada'}}}}
        bad_coords = {**good, 'candidate': {'coordinates': {**coords, 'line': 3}}}
        bad_citation = {**good, 'citation': 'page 1 line 3 column start 3 column end 12'}
        bad_page = {**good, 'citation': 'page 501 line 2 column start 3 column end 12',
                    'candidate': {'coordinates': {**coords, 'page': 501}}}
        bad_columns = {**good, 'citation': 'page 1 line 2 column start 12 column end 3',
                       'candidate': {'coordinates': {**coords, 'column-start': 12, 'column-end': 3}}}
        bad_hash = {**good, 'source_id': 'sha256:' + '0' * 64}
        snapshot, _, env = fixture(root, original_bytes=pdf_bytes(), content_type='application/pdf',
            schema='worker-retained-artifact-reconciliation/v1', collection='candidate_versions',
            positions=[good, bad_coords, bad_citation, bad_page, bad_columns, bad_hash])
        with make_server(snapshot, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                records = server.query.browse(collection='candidate_versions')['records']
                paths = ['/owner-evidence/api/source-view/' + row['record_id'] for row in records]
                self.assertEqual(self.request(paths[0], authorized=False, server=server)[0], 403)
                status, _, body = self.request(paths[0], server=server)
                self.assertEqual(status, 200)
                view = json.loads(body)
                self.assertEqual((view['format'], view['page'], view['region']), ('pdf', 1, citation))
                self.assertEqual(view['source_sha256'], sha(pdf_bytes()))
                self.assertIn('source line and retained artifact not replayed', view['verification_scope'])
                self.assertEqual(self.request(paths[0] + '/page/1', server=server)[0], 200)
                self.assertEqual(self.request(paths[0] + '/page/2', server=server)[0], 404)
                self.assertEqual([self.request(path, server=server)[0] for path in paths[1:]],
                                 [422, 422, 422, 422, 404])
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_portable_roatan_array_requires_exact_citation_and_row(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, bundle, env = fixture(
                root, original_bytes=json.dumps([{'Name': 'Ada'}, {'Name': 'Bea'}]).encode(),
                positions=[{'fields': {'Name': 'Ada'}, 'locator': 'row index zero based 0'}])
            with SnapshotQuery(snapshot) as query:
                record = query.browse(kind='candidate_position')['records'][0]
                detail = query.detail(record['record_id'])
                original_hash = detail['source_object_id'].removeprefix('sha256:')
                detail.update(source_schema='roatan-2026-cwt-men-private-census/v1',
                              raw={'unit': 3551, 'json_index_zero_based': 0,
                                   'source_sha256': original_hash},
                              citation={'unit': 3551, 'row-index-zero-based': 0,
                                        'source-sha256': original_hash})
                viewer = OriginalSourceView(bundle, env['OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256'],
                                            env['OWNER_EVIDENCE_SNAPSHOT_SHA256'])
                shown = viewer.inspect(detail)
                self.assertEqual(shown['source_value'], {'Name': 'Ada'})
                self.assertEqual(shown['locator'], '[0]')
                for changed in (dict(detail, citation=dict(detail['citation'], **{'row-index-zero-based': 1})),
                                dict(detail, citation=dict(detail['citation'], **{'source-sha256': '0' * 64})),
                                dict(detail, raw_fields={'Name': 'Bea'})):
                    with self.subTest(changed=changed):
                        with self.assertRaises(SourceViewError) as raised:
                            viewer.inspect(changed)
                        self.assertEqual(raised.exception.status, 422)

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

    def request(self, path, authorized=True, server=None):
        server = server or self.server
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=4)
        headers = {'Host': HOST}
        if authorized:
            headers.update({'X-Freediving-Owner-Gateway': SECRET, 'X-Freediving-Owner-Email': EMAIL})
        connection.request('GET', path, headers=headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_exact_json_row_requires_authenticated_record_and_matching_raw_fields(self):
        first, second, out_of_range, malformed = [r['record_id'] for r in self.rows]
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
        for wrong in (second, out_of_range, malformed):
            self.assertEqual(self.request('/owner-evidence/api/source-view/' + wrong)[0], 422)
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

    def test_non_result_and_restricted_original_are_denied(self):
        gap = self.server.query.browse(kind='gap')['records'][0]['record_id']
        self.assertEqual(self.request('/owner-evidence/api/source-view/' + gap)[0], 422)
        root = Path(self.tmp.name) / 'restricted'
        root.mkdir()
        snapshot, _, env = fixture(root, classification='restricted')
        with make_server(snapshot, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                record = server.query.browse(kind='candidate_position')['records'][0]['record_id']
                self.assertEqual(self.request('/owner-evidence/api/source-view/' + record,
                                              server=server)[0], 403)
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_restricted_html_is_served_only_as_cited_safe_text(self):
        root = Path(self.tmp.name) / 'html-safe'
        root.mkdir()
        source = (ROOT / 'test/resources/fixtures/vestico-2025/results.html').read_bytes()
        columns = ['Rank', 'OT', 'Lane', 'Competitor', 'M/F', 'Club', 'Result', 'Card', 'IRM']
        row = derive(source, source_id='sha256:' + sha(source),
                     acquisition_id='1' * 64)['tables'][0]['rows'][0]
        fields = dict(zip(columns, row['cells']))
        snapshot, bundle, env = fixture(root, original_bytes=source, content_type='text/html',
            classification='restricted', safe_derivative=True,
            positions=[{'fields': fields, 'locator': 'table 1 row 2'},
                       {'fields': {'Competitor': 'Wrong'}, 'locator': 'table 1 row 2'}])
        with make_server(snapshot, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                records = server.query.browse(kind='candidate_position')['records']
                good = next(r for r in records if server.query.detail(r['record_id'])['raw_fields'] == fields)
                bad = next(r for r in records if r['record_id'] != good['record_id'])
                path = '/owner-evidence/api/source-view/' + good['record_id']
                self.assertEqual(self.request(path, authorized=False, server=server)[0], 403)
                status, headers, body = self.request(path, server=server)
                self.assertEqual(status, 200)
                view = json.loads(body)
                self.assertEqual(view['format'], 'safe_html_derivative')
                self.assertEqual(view['source_value'], fields)
                self.assertEqual(view['citation'], 'table 1 row 2')
                self.assertEqual(view['original_replay'], 'restricted_original_required')
                self.assertNotIn(b'<tr', body)
                self.assertEqual(self.request('/owner-evidence/api/source-view/' + bad['record_id'],
                                              server=server)[0], 422)
                self.assertEqual(self.request(path + '/page/1', server=server)[0], 404)
                self.assertEqual(headers['Cache-Control'], 'no-store')
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_pdf_page_auth_corruption_and_output_bound(self):
        root = Path(self.tmp.name) / 'pdf'
        root.mkdir()
        snapshot, bundle, env = fixture(root, original_bytes=pdf_bytes(),
            content_type='application/pdf', positions=[{'fields': {'Name': 'Ada'},
                'citation': {'page': 1, 'region': {'bbox': [1, 2, 3, 4]}}}])
        with make_server(snapshot, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                record = server.query.browse(kind='candidate_position')['records'][0]['record_id']
                base = '/owner-evidence/api/source-view/' + record
                self.assertEqual(self.request(base + '/page/1', authorized=False, server=server)[0], 403)
                self.assertEqual(self.request(base + '/page/2', server=server)[0], 404)
                self.assertEqual(self.request(base + '/page/1', server=server)[0], 200)
                digest = server.query.detail(record)['source_object_id'].split(':', 1)[1]
                (bundle / 'objects' / digest).write_bytes(b'%PDF-1.4\ncorrupt')
                self.assertEqual(self.request(base + '/page/1', server=server)[0], 503)
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_pdf_render_is_stopped_at_output_limit(self):
        root = Path(self.tmp.name) / 'pdf-limit'
        root.mkdir()
        snapshot, _, env = fixture(root, original_bytes=pdf_bytes(),
            content_type='application/pdf', positions=[{'fields': {'Name': 'Ada'},
                'citation': {'page': 1}}])
        marker_file = root / 'renderer-finished'
        fake = root / 'pdftoppm'
        fake.write_text('#!' + sys.executable + '\nimport sys\nfrom pathlib import Path\n'
                        'sys.stdout.buffer.write(b"\\x89PNG\\r\\n\\x1a\\n" + b"x" * 3000000)\n'
                        + 'Path(' + repr(str(marker_file)) + ').write_text("finished")\n')
        fake.chmod(0o700)
        with make_server(snapshot, env) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                record = server.query.browse(kind='candidate_position')['records'][0]['record_id']
                with mock.patch.dict(os.environ, {'PATH': str(root) + os.pathsep + os.environ['PATH']}):
                    status = self.request('/owner-evidence/api/source-view/' + record + '/page/1',
                                          server=server)[0]
                self.assertEqual(status, 413)
                self.assertFalse(marker_file.exists())
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_receipt_has_screened_urls(self):
        record = self.rows[0]['record_id']
        status, _, body = self.request('/owner-evidence/api/source-view/' + record)
        self.assertEqual(status, 200)
        receipt = json.loads(body)['receipt']
        self.assertEqual(receipt['discovery_url'], 'https://example.test/results')
        self.assertEqual(receipt['final_url'], 'https://example.test/final')
        self.assertEqual(receipt['retrieved_at'], '2026-09-28T12:00:00Z')
        self.assertEqual(receipt['selected_view'], 'results')
        self.assertNotIn('private', str(receipt))

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
