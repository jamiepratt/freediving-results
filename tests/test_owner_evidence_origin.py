import http.client
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest

from test_unified_evidence_query import snapshot

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_evidence_origin import make_server
from owner_decision_store import DecisionStore
from test_owner_decision_store import proposal


HOST = 'owner-private.alphacompose.com'
EMAIL = 'owner@example.com'
SECRET = 'a-private-gateway-secret-for-tests'


class PrivateOriginTest(unittest.TestCase):
    def test_snapshot_revisit_after_rollback_binds_new_revision(self):
        decision_path = Path(self.tmp.name) / 'durable-decisions' / 'ledger.sqlite'
        original_env = {**self.env, 'OWNER_EVIDENCE_DECISION_DB': str(decision_path)}
        other_dir = Path(self.tmp.name) / 'other-snapshot'
        shutil.copytree(self.snapshot_dir, other_dir)
        with sqlite3.connect(other_dir / 'snapshot.sqlite') as db:
            db.execute('PRAGMA user_version=1')
        other_digest = sha256((other_dir / 'snapshot.sqlite').read_bytes()).hexdigest()
        manifest = json.loads((other_dir / 'manifest.json').read_text())
        manifest['snapshot_sha256'] = other_digest
        (other_dir / 'manifest.json').write_text(json.dumps(manifest))
        other_env = {**original_env, 'OWNER_EVIDENCE_SNAPSHOT_SHA256': other_digest}

        with make_server(self.snapshot_dir, original_env) as server:
            self.assertEqual(server.decisions.revision, 1)
        with make_server(self.snapshot_dir, original_env) as server:
            self.assertEqual(server.decisions.revision, 1)
        with make_server(other_dir, other_env) as server:
            self.assertEqual(server.decisions.revision, 2)
        with make_server(self.snapshot_dir, original_env) as server:
            self.assertEqual(server.decisions.revision, 3)
        with make_server(other_dir, other_env) as server:
            self.assertEqual(server.decisions.revision, 4)
        with make_server(other_dir, other_env) as server:
            self.assertEqual(server.decisions.revision, 4)

    def test_consolidated_queue_is_private_bounded_and_independent_of_decision_store(self):
        queue = {'schema': 'issue172-owner-queue-v1', 'audit_sha256': 'a' * 64,
                 'entries': [{'id': f'item-{index}', 'kind': 'unresolved_field',
                              'source_key': 'pdf', 'source_sha256': 'b' * 64,
                              'source_position': {'page': 1, 'row': index},
                              'citation': {'page': 1, 'row': index},
                              'evidence_version': {'parser_version': 'v1'},
                              'reason': 'unreadable card', 'related_positions': [],
                              'status': 'pending'} for index in range(1000)]}
        path = Path(self.tmp.name) / 'queue.json'
        path.write_text(json.dumps(queue))
        env = {**self.env, 'OWNER_EVIDENCE_ISSUE172_QUEUE_FILE': str(path),
               'OWNER_EVIDENCE_ISSUE172_QUEUE_SHA256': sha256(path.read_bytes()).hexdigest()}
        server = make_server(self.snapshot_dir, env)
        self.addCleanup(server.server_close)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), thread.join(timeout=2)))
        previous = self.server
        self.server = server
        self.addCleanup(lambda: setattr(self, 'server', previous))
        status, headers, body = self.request('/owner-evidence/api/issue172-queue?limit=2&offset=1')
        self.assertEqual(status, 200)
        result = json.loads(body)
        self.assertEqual(result['total'], 1000)
        self.assertEqual([item['id'] for item in result['items']], ['item-1', 'item-2'])
        self.assertEqual(result['audit_sha256'], 'a' * 64)
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(self.request('/owner-evidence/api/issue172-queue?limit=101')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/issue172-queue', headers=[('Host', HOST)])[0], 403)
        self.assertEqual(self.request('/owner-evidence/api/decisions')[0], 503)
        with self.assertRaises(ValueError):
            make_server(self.snapshot_dir, {**env, 'OWNER_EVIDENCE_ISSUE172_QUEUE_SHA256': '0' * 64})

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

    def test_attempt_inspector_is_private_and_collects_fresh_authority(self):
        # Isolated transport fixture: no real retained rows or authority.
        authority = {'synthetic_revision': 1}
        self.server.status_authority = lambda: dict(authority)
        self.server.comparison_reader = lambda filters, current: {
            'schema': 'private-attempt-inspector/v1', 'filters': filters,
            'authority': current, 'rows': [], 'ranks': []}
        path = '/owner-evidence/api/attempt-inspector?federation=AIDA&limit=25&offset=0'
        status, headers, body = self.request(path)
        self.assertEqual(status, 200)
        result = json.loads(body)
        self.assertEqual(result['filters'], {'federation': 'AIDA', 'limit': 25, 'offset': 0})
        self.assertEqual(result['authority']['synthetic_revision'], 1)
        authority['synthetic_revision'] = 2
        self.assertEqual(json.loads(self.request(path)[2])['authority']['synthetic_revision'], 2)
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(self.request(path, headers=[('Host', HOST)])[0], 403)
        for query in ('federation=AIDA&federation=CMAS', 'unknown=1', 'limit=101', 'offset=-1'):
            self.assertEqual(self.request('/owner-evidence/api/attempt-inspector?' + query)[0], 400)

    def test_exact_private_peer_link_preserves_scope_and_requires_gateway_authentication(self):
        self.server.comparison_reader = lambda filters, current: {
            'schema': 'private-attempt-inspector/v1', 'filters': filters, 'rows': []}
        path = ('/owner-evidence/api/attempt-inspector?peer_anchor=synthetic&geography=national'
                '&peer_token=' + 'a' * 64 + '&sanction_scope=broad&listing_filter=international'
                '&federation=CMAS&environment=pool&discipline=DNF&year=2026&gender=women'
                '&category=seniors&representation=POL&review=verified&publication=approved&limit=25&offset=0')
        status, headers, body = self.request(path)
        self.assertEqual(status, 200)
        filters = json.loads(body)['filters']
        self.assertEqual(filters['peer_anchor'], 'synthetic')
        self.assertEqual(filters['sanction_scope'], 'broad')
        self.assertEqual(filters['federation'], 'CMAS')
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(self.request(path, headers=[('Host', HOST)])[0], 403)
        self.assertEqual(self.request(path + '&federation=AIDA')[0], 400)

    def test_private_inspector_accepts_bounded_full_version_page_without_raising_other_route_caps(self):
        padding = 'synthetic' * (3 * 1024 * 1024 // 9)
        self.server.comparison_reader = lambda filters, current: {
            'schema': 'private-attempt-inspector/v1', 'rows': [], 'synthetic_padding': padding}
        path = '/owner-evidence/api/attempt-inspector?limit=100'
        self.assertEqual(self.request(path)[0], 200)
        self.server.query.overview = lambda: {'synthetic_padding': padding, 'federation_mapping': {'schema': None}}
        self.assertEqual(self.request('/owner-evidence/api/overview')[0], 413)
        padding += 'synthetic' * (2 * 1024 * 1024 // 9)
        self.assertEqual(self.request(path)[0], 413)

    def test_retained_inspector_source_context_rechecks_exact_private_row(self):
        retained = {'reference': {'source-sha256': 'a' * 64, 'artifact-sha256': 'b' * 64, 'ordinal': 0},
                    'candidate': {'coordinates': {'page': 5, 'row': 1},
                                  'raw': {'fields': {'card': 'synthetic WHITE'}},
                                  'parsed': {'discipline': 'DNF'}},
                    'source': {'federation': 'AIDA'}, 'rank': None}
        self.server.comparison_reader = lambda filters, current: {
            'schema': 'private-attempt-inspector/v1', 'pagination': {'offset': 0, 'limit': 25, 'total': 1},
            'rows': [retained]}
        status, _, body = self.request('/owner-evidence/api/attempt-inspector')
        self.assertEqual(status, 200)
        access = json.loads(body)['rows'][0]['source_access']
        self.assertEqual(access['status'], 'retained_derivative')
        path = '/owner-evidence/api/attempt-inspector/source/' + access['retained_row_id']
        status, headers, body = self.request(path)
        self.assertEqual(status, 200)
        source = json.loads(body)
        self.assertEqual(source['format'], 'cited_retained_derivative')
        self.assertEqual(source['source_sha256'], 'a' * 64)
        self.assertEqual(source['derivative_sha256'], 'b' * 64)
        self.assertEqual(source['source_value'], retained['candidate']['raw'])
        self.assertEqual(source['original_replay'], 'restricted_original_required')
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(self.request(path, headers=[('Host', HOST)])[0], 403)
        previous_reader = self.server.comparison_reader
        def unavailable(*args):
            raise RuntimeError('isolated unavailable private evidence')
        self.server.comparison_reader = unavailable
        self.assertEqual(self.request(path)[0], 503)
        self.server.comparison_reader = previous_reader
        retained['candidate']['coordinates'] = {'page': 5, 'row': 2}
        self.assertEqual(self.request(path)[0], 404)

    def test_retained_inspector_pdf_is_private_and_exact_page_bound(self):
        from test_owner_source_view import pdf_bytes
        retained = {'reference': {'source-sha256': 'a' * 64, 'artifact-sha256': 'b' * 64, 'ordinal': 0},
                    'candidate': {'coordinates': {'page': 1, 'row': 1}, 'raw': {}, 'parsed': {}},
                    'source': {'federation': 'CMAS'}}
        def reader(filters, current):
            return {'schema': 'private-attempt-inspector/v1', 'pagination': {'offset': 0, 'total': 1}, 'rows': [retained]}
        reader.source_available = lambda row: row['source']['federation'] == 'CMAS'
        reader.source_bytes = lambda row: pdf_bytes()
        self.server.comparison_reader = reader
        body = json.loads(self.request('/owner-evidence/api/attempt-inspector')[2])
        access = body['rows'][0]['source_access']
        self.assertEqual(access['original_page'], 1)
        path = '/owner-evidence/api/attempt-inspector/source/' + access['retained_row_id'] + '/page/'
        status, headers, body = self.request(path + '1')
        self.assertEqual((status, headers['Content-Type']), (200, 'image/png'))
        self.assertTrue(body.startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertEqual(self.request(path + '2')[0], 404)
        self.assertEqual(self.request(path + '1', headers=[('Host', HOST)])[0], 403)
        retained['source']['federation'] = 'AIDA'
        self.assertEqual(self.request(path + '1')[0], 404)

    def test_retained_source_context_survives_current_authority_loss_without_returning_ranks(self):
        from private_presentation_status import StatusConflict
        retained = {'reference': {'source-sha256': 'a' * 64, 'artifact-sha256': 'b' * 64, 'ordinal': 0},
                    'candidate': {'coordinates': {'page': 5, 'row': 1},
                                  'raw': {'fields': {'card': 'isolated synthetic WHITE'}}, 'parsed': {}},
                    'source': {'federation': 'AIDA'}}
        def reader(filters, current):
            return {'schema': 'private-attempt-inspector/v1', 'pagination': {'offset': 0, 'total': 1},
                    'rows': [{**retained, 'rank': 1 if current is not None else None}]}
        self.server.comparison_reader = reader
        self.server.status_authority = lambda: {'synthetic_revision': 1}
        page = json.loads(self.request('/owner-evidence/api/attempt-inspector')[2])
        row_id = page['rows'][0]['source_access']['retained_row_id']
        def unavailable():
            raise StatusConflict('isolated external authority unavailable')
        self.server.status_authority = unavailable
        status, _, body = self.request('/owner-evidence/api/attempt-inspector/source/' + row_id)
        self.assertEqual(status, 200)
        source = json.loads(body)
        self.assertEqual(source['source_value'], retained['candidate']['raw'])
        self.assertNotIn('rank', source)
        self.assertNotIn('authority', source)
        self.assertEqual(self.request('/owner-evidence/api/attempt-inspector')[0], 409)

    def test_overview_reports_verified_source_bundle_binding(self):
        self.server.source_bundle_sha256 = 'd' * 64
        status, _, body = self.request('/owner-evidence/api/overview')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['bundle_manifest_sha256'], 'd' * 64)

    def test_federation_filter_accepts_unknown_on_unmapped_snapshot(self):
        status, _, body = self.request('/owner-evidence/api/browse?federation=unknown&kind=candidate_position&limit=1&offset=1')
        self.assertEqual(status, 200)
        result = json.loads(body)
        self.assertEqual(result['total'], 2)
        self.assertEqual(result['offset'], 1)
        self.assertEqual(len(result['records']), 1)
        self.assertIsNone(result['records'][0]['federation'])
        self.assertEqual(self.request('/owner-evidence/api/browse?federation=CMAS')[0], 200)
        self.assertEqual(json.loads(self.request('/owner-evidence/api/browse?federation=CMAS')[2])['total'], 0)
        self.assertEqual(self.request('/owner-evidence/api/browse?federation=unknown&federation=CMAS')[0], 400)

    def test_federation_filter_on_mapped_snapshot_keeps_citation_and_paging(self):
        root = Path(self.tmp.name)
        packet = root / 'mapped-packet.json'
        packet.write_text(json.dumps({'schema': 'synthetic/v1', 'positions': [
            {'source_object_id': 'sha256:' + 'a' * 64, 'citation': {'page': 1, 'row': 1}},
            {'source_object_id': 'sha256:' + 'b' * 64, 'citation': {'page': 2, 'row': 1}}]}))
        base, mapped = root / 'base', root / 'mapped'
        script = ROOT / 'scripts' / 'unified_evidence_snapshot.py'
        subprocess.run([sys.executable, str(script), 'build', '--cutoff', '2026-10-01T00:00:00Z',
                        '--input', f'packet={packet}', '--output-dir', str(base)],
                       check=True, capture_output=True)
        mapping = root / 'mapping.json'
        mapping.write_text(json.dumps({'schema': 'evidence-federation-map/v1', 'entries': [{
            'source_name': 'packet', 'source_object_id': 'sha256:' + 'a' * 64,
            'federation': 'CMAS', 'authority': 'CMAS publisher', 'role': 'primary',
            'citation': {'url': 'https://example.test/results.pdf', 'sha256': 'a' * 64,
                         'locator': 'page 1 heading', 'evidence_text': 'CMAS WORLD CUP'}}]}))
        subprocess.run([sys.executable, str(script), 'map-federations', '--base-dir', str(base),
                        '--mapping', str(mapping), '--output-dir', str(mapped)],
                       check=True, capture_output=True)
        env = {**self.env, 'OWNER_EVIDENCE_SNAPSHOT_SHA256':
               json.loads((mapped / 'manifest.json').read_text())['snapshot_sha256']}
        server = make_server(mapped, env)
        self.addCleanup(server.server_close)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), thread.join(timeout=2)))
        previous = self.server
        self.server = server
        self.addCleanup(lambda: setattr(self, 'server', previous))
        status, _, body = self.request('/owner-evidence/api/browse?federation=CMAS&kind=candidate_position&limit=1&offset=0')
        self.assertEqual(status, 200)
        page = json.loads(body)
        self.assertEqual((page['total'], page['limit'], page['offset']), (1, 1, 0))
        row = page['records'][0]
        self.assertEqual((row['federation'], row['authority'], row['role']),
                         ('CMAS', 'CMAS publisher', 'primary'))
        detail = json.loads(self.request('/owner-evidence/api/detail/' + row['record_id'])[2])
        self.assertEqual(detail['federation_citation']['locator'], 'page 1 heading')
        self.assertEqual(detail['citation'], {'page': 1, 'row': 1})
        unknown = json.loads(self.request('/owner-evidence/api/browse?federation=unknown&kind=candidate_position')[2])
        self.assertEqual(unknown['total'], 1)
        overview = json.loads(self.request('/owner-evidence/api/overview')[2])
        self.assertEqual(overview['federation_mapping']['mapped_candidate_positions'], 1)
        self.assertEqual(overview['federation_mapping']['unknown_candidate_positions'], 1)

    def test_idle_origin_connection_does_not_block_authorized_overview(self):
        idle = socket.create_connection(('127.0.0.1', self.server.server_port), timeout=1)
        self.addCleanup(idle.close)
        idle.sendall(b'GET /owner-evidence HTTP/1.1\r\nHost: ' + HOST.encode())
        status, _, body = self.request('/owner-evidence/api/overview')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['snapshot_sha256'], self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'])

    def test_private_comparison_budget_exhaustion_is_retriable(self):
        def reader(filters, authority):
            raise ValueError('owned runtime exhausted its bounded request budget')
        self.server.comparison_reader = reader
        status, headers, body = self.request('/owner-evidence/api/attempt-inspector')
        self.assertEqual(status, 503)
        self.assertEqual(headers['Retry-After'], '1')
        self.assertEqual(body, b'')
        self.assertEqual(self.request('/owner-evidence/api/attempt-inspector?limit=101')[0], 400)

    def test_slow_owned_inspection_does_not_queue_current_status(self):
        # Owned subprocess stand-in isolates transport/queueing from JVM work.
        marker = Path(self.tmp.name) / 'read-entered'
        release = Path(self.tmp.name) / 'read-release'
        script = ("import pathlib,time; pathlib.Path(" + repr(str(marker)) + ").touch(); "
                  "p=pathlib.Path(" + repr(str(release)) + "); "
                  "exec('while not p.exists():\\n time.sleep(0.01)')")
        def reader(filters, current):
            subprocess.run([sys.executable, '-c', script], check=True, timeout=3)
            return {'schema': 'private-attempt-inspector/v1', 'rows': [], 'ranks': []}
        self.server.comparison_reader = reader
        results = []
        thread = threading.Thread(target=lambda: results.append(
            self.request('/owner-evidence/api/attempt-inspector')[0]))
        thread.start()
        try:
            until = time.monotonic() + 1
            while not marker.exists() and time.monotonic() < until:
                time.sleep(0.01)
            self.assertTrue(marker.exists())
            started = time.monotonic()
            # Release eventually even on the old whole-request lock.
            timer = threading.Timer(0.6, release.touch)
            timer.start()
            status, _, body = self.request('/owner-evidence/api/presentation-status')
            elapsed = time.monotonic() - started
            timer.join()
            self.assertEqual(status, 200)
            self.assertEqual(json.loads(body)['status'], 'unavailable')
            self.assertLess(elapsed, 0.3, 'status waited behind expensive inspection')
        finally:
            release.touch()
            thread.join(timeout=4)
        self.assertEqual(results, [200])

    def test_authorized_decision_actions_do_not_overlap(self):
        import hmac
        from hashlib import sha256
        first_entered = threading.Event()
        second_entered = threading.Event()
        release_first = threading.Event()

        class Decisions:
            def __init__(self):
                self.calls = 0

            def act(self, *_args, **_kwargs):
                self.calls += 1
                if self.calls == 1:
                    first_entered.set()
                    release_first.wait(timeout=2)
                else:
                    second_entered.set()
                return {'revision': self.calls}

        self.server.decisions = Decisions()
        csrf = hmac.new(SECRET.encode(), ('decision-csrf-v1:' + EMAIL).encode(), sha256).hexdigest()
        path = '/owner-evidence/api/decisions/decision-1/actions'
        results = []

        def action(number):
            body = json.dumps({'action': 'reverse', 'expected_revision': number,
                               'idempotency_key': f'action-{number}', 'reason': 'owner review',
                               'csrf_token': csrf}).encode()
            headers = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                       ('X-Freediving-Owner-Email', EMAIL), ('Origin', 'https://poc.alphacompose.com'),
                       ('Content-Type', 'application/json'), ('X-Freediving-CSRF', csrf),
                       ('Content-Length', str(len(body)))]
            results.append(self.request(path, method='POST', headers=headers, body=body)[0])

        first = threading.Thread(target=action, args=(1,))
        second = threading.Thread(target=action, args=(2,))
        first.start()
        try:
            self.assertTrue(first_entered.wait(timeout=1))
            second.start()
            self.assertFalse(second_entered.wait(timeout=0.5))
        finally:
            release_first.set()
            first.join(timeout=3)
            if second.ident is not None:
                second.join(timeout=3)
        self.assertEqual(results, [200, 200])

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
            active_snapshot_sha256 = 'a' * 64
            def queue(self, **filters):
                self.filters = filters
                return {'revision': 1, 'items': [{'id': 'decision-1'}], 'total': 1,
                        'scoreless_items': [], 'scoreless_total': 0}
            def inspect(self, decision_id):
                return {'id': decision_id, 'store_revision': 1, 'history': []}
            def preview(self, decision_id, action):
                return {'id': decision_id, 'action': action, 'affected': []}
            def audit_sample(self, *, limit): return {'items': [], 'sample_size': 0, 'blocking': False}
        self.server.decisions = Decisions()
        status, _, body = self.request('/owner-evidence/api/decisions?type=identity&status=pending&source=meet&limit=2')
        self.assertEqual(status, 200)
        listing = json.loads(body)
        self.assertEqual(listing['items'][0]['id'], 'decision-1')
        self.assertIn('csrf_token', listing)
        self.assertEqual(self.server.decisions.filters,
                         {'decision_type': 'identity', 'status': 'pending', 'source_name': 'meet',
                          'limit': 2, 'offset': 0, 'summary': True})
        self.assertEqual(json.loads(self.request('/owner-evidence/api/decisions/decision-1')[2])['id'], 'decision-1')
        self.assertEqual(json.loads(self.request('/owner-evidence/api/decisions/decision-1/preview?action=reverse')[2])['action'], 'reverse')
        self.assertEqual(self.request('/owner-evidence/api/decisions/decision-1/preview?action=correct')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/decisions?limit=101')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/decisions?type=x&type=x')[0], 400)
        self.assertEqual(self.request('/owner-evidence/api/decisions/decision-1/preview?action=invalid')[0], 400)
        self.assertEqual(json.loads(self.request('/owner-evidence/api/decisions/audit-sample')[2])['blocking'], False)

    def test_human_approved_api_returns_five_current_reviews_with_delivery_still_pending(self):
        store = DecisionStore(Path(self.tmp.name) / 'owner-decisions.sqlite')
        self.addCleanup(store.close)
        self.server.decisions = store
        digest = self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256']
        reference = {'kind': 'source-derived', 'source_sha256': 'c' * 64, 'citation': {'row': 1}}
        store.bind_snapshot(digest, ['row-1'], expected_revision=0, idempotency_key='bind-source',
                            _observation_refs={'row-1': {'source_sha256': 'c' * 64, 'refs': [],
                                                         'source_derived_ref': reference}})
        for index in range(5):
            ident = f'approved-{index}'
            p = proposal(ident)
            p['evidence'][0] = {'id': 'row-1', 'version': reference,
                                'citation': {'source_citation': {'source-sha256': 'c' * 64,
                                                                 'locator': reference['citation']},
                                             'observation_revision': reference}}
            p['canonical_binding'] = {'decision_id': ident, 'observation_revisions': [reference],
                                      'evidence_bindings': [{'snapshot_record_id': 'row-1',
                                                             'observation_revision': reference}]}
            store.register(digest, p, idempotency_key='register-' + ident)
            store.act(ident, action='approve', expected_revision=store.revision,
                      idempotency_key='approve-' + ident)
        revision = store.revision
        for filter_status in ('human_approved', 'projection_pending'):
            status, headers, body = self.request(
                '/owner-evidence/api/decisions?status=' + filter_status + '&limit=2&offset=1')
            self.assertEqual(status, 200)
            self.assertEqual(headers['Cache-Control'], 'no-store')
            listing = json.loads(body)
            self.assertEqual(listing['total'], 5)
            self.assertEqual(listing['revision'], revision)
            self.assertEqual([p['id'] for p in listing['items']], ['approved-1', 'approved-2'])
            self.assertTrue(all(p['status'] == 'human_approved' and
                                p['effective_status'] == 'projection_pending' and
                                p['canonical_projection_status'] == 'pending' for p in listing['items']))
        self.assertEqual(store.revision, revision)
        self.assertEqual(len(store.human_events()['events']), 5)
        self.assertEqual(store.projection()['active_decisions'], [])

    def test_queue_and_status_use_current_owner_binding_without_full_projection(self):
        class Decisions:
            revision = 209
            active_snapshot_sha256 = 'a' * 64

            def queue(self, **_):
                return {'revision': self.revision, 'items': [], 'total': 207,
                        'scoreless_items': [], 'scoreless_total': 207}

            def projection(self):
                raise AssertionError('full projection is unnecessary for owner binding')

        class Status:
            def read(self, active, *, owner_revision, owner_snapshot,
                     include_stale_checkpoint=False):
                return {'owner_revision': owner_revision, 'owner_snapshot': owner_snapshot}

            def update(self, body, active, *, owner_revision, owner_snapshot):
                return {'owner_revision': owner_revision, 'owner_snapshot': owner_snapshot}

        self.server.decisions = Decisions()
        self.server.presentation_status = Status()
        self.server.status_token = 'private-status-token-for-tests'
        self.server.status_client_id = 'status-client.access'
        queue_status, _, queue_body = self.request('/owner-evidence/api/decisions')
        self.assertEqual(queue_status, 200)
        self.assertEqual(json.loads(queue_body)['active_snapshot_sha256'], 'a' * 64)
        status_code, _, status_body = self.request('/owner-evidence/api/presentation-status')
        self.assertEqual(status_code, 200)
        self.assertEqual(json.loads(status_body)['owner_revision'], 209)
        self.assertEqual(json.loads(status_body)['owner_snapshot'], 'a' * 64)
        machine_headers = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                           ('X-Freediving-Owner-Machine', self.server.status_client_id),
                           ('X-Freediving-Status-Token', self.server.status_token),
                           ('Content-Type', 'application/json')]
        payload = b'{}'
        post_code, _, post_body = self.request('/owner-evidence/api/presentation-status',
                                              method='POST',
                                              headers=machine_headers + [('Content-Length', str(len(payload)))],
                                              body=payload)
        self.assertEqual(post_code, 200)
        self.assertEqual(json.loads(post_body)['owner_snapshot'], 'a' * 64)

    def test_decision_action_checks_origin_csrf_revision_and_body(self):
        class Decisions:
            active_snapshot_sha256 = 'a' * 64
            def queue(self, **_): return {'revision': 3, 'items': [], 'total': 0,
                                          'scoreless_items': [], 'scoreless_total': 0}
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
            active_snapshot_sha256 = 'a' * 64
            def queue(self, **_): return {'revision': 3, 'items': [], 'total': 0,
                                          'scoreless_items': [], 'scoreless_total': 0}
            def inspect(self, _): return {'selected_option': 'same-person',
                                          'competing_options': ['different-person', 'unknown']}
            def act(self, *args, **kwargs):
                self.call = (args, kwargs)
                return {'status': 'human_corrected'}
        self.server.decisions = Decisions()
        def preview(_id, *, action, option=None):
            return {'action': action, 'before_option': 'same-person', 'after_option': option}
        self.server.decisions.preview = preview
        status, _, body = self.request('/owner-evidence/api/decisions/decision-1/preview?action=correct&option=different-person')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['after_option'], 'different-person')
        for option in ('same-person', 'new-person'):
            self.assertEqual(self.request('/owner-evidence/api/decisions/decision-1/preview?action=correct&option=' + option)[0], 400)
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

    def test_pending_delivery_owner_reversal_and_restoration_preserve_signed_history(self):
        from test_owner_decision_store import DecisionStoreTest
        import hashlib
        import hmac
        fixture = DecisionStoreTest()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        digest, p = fixture.source_bound_proposal('pending-approval')
        fixture.store.register(digest, p, idempotency_key='register-pending')
        self.server.decisions = fixture.store
        self.addCleanup(lambda: setattr(self.server, 'decisions', None))
        self.server.import_token = 'separate-owner-import-token-for-tests'
        self.server.import_client_id = 'abc12345.access'
        path = '/owner-evidence/api/decisions/pending-approval'
        listing = json.loads(self.request('/owner-evidence/api/decisions')[2])
        csrf = listing['csrf_token']

        def write(action, revision, key):
            payload = json.dumps({'action': action, 'expected_revision': revision,
                                  'idempotency_key': key, 'reason': 'synthetic owner review',
                                  'csrf_token': csrf}).encode()
            headers = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                       ('X-Freediving-Owner-Email', EMAIL), ('Origin', 'https://poc.alphacompose.com'),
                       ('Content-Type', 'application/json'), ('X-Freediving-CSRF', csrf),
                       ('Content-Length', str(len(payload)))]
            status, _, body = self.request(path + '/actions', method='POST', headers=headers, body=payload)
            return status, json.loads(body) if body else None

        status, approved = write('approve', listing['revision'], 'approve-pending')
        self.assertEqual(status, 200)
        self.assertEqual(approved['effective_status'], 'projection_pending')
        history = fixture.store.human_events()
        status, _, body = self.request(path + '/preview?action=reverse')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['review_after']['pending-approval'], 'reversed')
        self.assertEqual(json.loads(body)['after']['pending-approval'], 'projection_pending')
        self.assertEqual(fixture.store.human_events(), history)
        revision = approved['store_revision']
        status, reversed_decision = write('reverse', revision, 'reverse-pending')
        self.assertEqual(status, 200)
        self.assertEqual(reversed_decision['status'], 'reversed')
        self.assertEqual(write('reverse', revision, 'reverse-pending'), (status, reversed_decision))
        self.assertEqual(write('approve', revision, 'stale-restoration')[0], 409)
        status, _, body = self.request(path + '/preview?action=approve')
        self.assertEqual(status, 200)
        revision = json.loads(body)['revision']
        status, restored = write('approve', revision, 'restore-pending')
        self.assertEqual(status, 200)
        self.assertEqual(restored['status'], 'human_approved')
        self.assertEqual(restored['effective_status'], 'projection_pending')
        headers = [('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                   ('X-Freediving-Owner-Machine', self.server.import_client_id),
                   ('X-Freediving-Import-Token', self.server.import_token)]
        status, _, body = self.request('/owner-evidence/api/decision-events?after_revision=0', headers=headers)
        self.assertEqual(status, 200)
        envelope = json.loads(body)
        self.assertEqual(envelope['signature'], hmac.new(self.server.import_token.encode(),
                         envelope['payload_json'].encode(), hashlib.sha256).hexdigest())
        self.assertEqual([e['action'] for e in json.loads(envelope['payload_json'])['events']],
                         ['approve', 'reverse', 'approve'])
        self.assertEqual(fixture.store.delivery_checkpoints(), {'flow-ledger': 0, 'postgresql': 0})
        self.assertEqual(fixture.store.projection()['active_decisions'], [])

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

    def test_large_decision_queue_page_fits_response_and_detail_retains_evidence(self):
        from test_owner_decision_store import proposal
        decision_path = Path(self.tmp.name) / 'large-decisions.sqlite'
        server = make_server(self.snapshot_dir, {**self.env,
                             'OWNER_EVIDENCE_DECISION_DB': str(decision_path),
                             'OWNER_EVIDENCE_STATUS_FILE': str(Path(self.tmp.name) / 'large-status.json'),
                             'OWNER_EVIDENCE_STATUS_TOKEN': 'private-status-token-for-tests',
                             'OWNER_EVIDENCE_STATUS_CLIENT_ID': 'status-client.access'})
        self.addCleanup(server.server_close)
        evidence_id = server.query.browse(kind='candidate_position', limit=1)['records'][0]['record_id']
        citation = {'source_position': 'synthetic-row-1'}
        large_support = 'synthetic support ' + 'x' * 25000
        proposals = []
        for index in range(207):
            item = proposal(f'candidate-{index:03}', evidence=evidence_id,
                            score=index / 207)
            item['evidence'][0]['citation'] = citation
            item['supporting_evidence'] = [large_support]
            proposals.append(item)
        server.decisions.register_batch(self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'], proposals,
                                        idempotency_key='register-large-queue')
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), thread.join(timeout=2)))

        def request(path):
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=10)
            connection.request('GET', path, headers={
                'Host': HOST, 'X-Freediving-Owner-Gateway': SECRET,
                'X-Freediving-Owner-Email': EMAIL})
            response = connection.getresponse()
            result = response.status, response.read()
            connection.close()
            return result

        started = time.monotonic()
        status, body = request('/owner-evidence/api/decisions?limit=100')
        queue_elapsed = time.monotonic() - started
        self.assertEqual(status, 200)
        self.assertLess(queue_elapsed, 15, f'207-proposal queue took {queue_elapsed:.2f}s')
        self.assertLess(len(body), 2 * 1024 * 1024)
        queue = json.loads(body)
        self.assertEqual((queue['total'], len(queue['items'])), (207, 100))
        self.assertEqual(queue['items'][0]['id'], 'candidate-000')
        self.assertEqual(queue['items'][0]['provider_confidence'], 0)
        detail_status, detail_body = request('/owner-evidence/api/decisions/candidate-000')
        self.assertEqual(detail_status, 200)
        detail = json.loads(detail_body)
        self.assertEqual(detail['evidence'][0]['citation'], citation)
        self.assertEqual(detail['supporting_evidence'], [large_support])
        machine = {'Host': HOST, 'X-Freediving-Owner-Gateway': SECRET,
                   'X-Freediving-Owner-Machine': 'status-client.access',
                   'X-Freediving-Status-Token': 'private-status-token-for-tests',
                   'Content-Type': 'application/json'}
        receipt = {'schema': 'private-presentation-status/v3', 'run_id': 'large-batch',
                   'revision': 1, 'expected_revision': 0,
                   'local': {'snapshot_sha256': self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'],
                             'cutoff': '2026-10-03T00:00:00Z', 'gap_count': 0},
                   'remote': {'status': 'pending',
                              'pending': self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'],
                              'failed': None, 'active': None},
                   'application': {'snapshot_sha256': self.env['OWNER_EVIDENCE_SNAPSHOT_SHA256'],
                                   'canonical_revision': 1,
                                   'canonical_readback_sha256': 'c' * 64,
                                   'owner_store_revision': server.decisions.revision,
                                   'pending_proposals': 207, 'unresolved_exclusions': 0,
                                   'provider_calls_recorded': 0,
                                   'publication_status': 'private'}}
        payload = json.dumps(receipt).encode()
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=15)
        started = time.monotonic()
        connection.request('POST', '/owner-evidence/api/presentation-status', body=payload,
                           headers={**machine, 'Content-Length': str(len(payload))})
        response = connection.getresponse()
        post_status, post_body = response.status, response.read()
        post_elapsed = time.monotonic() - started
        connection.close()
        self.assertEqual(post_status, 200, post_body)
        self.assertLess(post_elapsed, 15, f'207-proposal status POST took {post_elapsed:.2f}s')
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=15)
        started = time.monotonic()
        connection.request('GET', '/owner-evidence/api/presentation-status', headers=machine)
        response = connection.getresponse()
        get_status, get_body = response.status, response.read()
        get_elapsed = time.monotonic() - started
        connection.close()
        self.assertEqual(get_status, 200, get_body)
        self.assertLess(get_elapsed, 15, f'207-proposal status GET took {get_elapsed:.2f}s')
        self.assertEqual(json.loads(get_body)['application']['pending_proposals'], 207)


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
