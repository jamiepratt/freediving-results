"""Authenticated owned HTTP concurrency, using synthetic evidence only."""
import concurrent.futures
import copy
from hashlib import sha256
import hmac
import http.client
import json
import os
from pathlib import Path
import subprocess
import socket
import sys
import threading
import time
import unittest
from unittest import mock

import test_owner_evidence_origin as origin_tests
HOST, EMAIL, SECRET = origin_tests.HOST, origin_tests.EMAIL, origin_tests.SECRET
from test_owner_source_view import pdf_bytes


class OriginConcurrencyTest(unittest.TestCase):
    setUp = origin_tests.PrivateOriginTest.setUp
    stop_server = origin_tests.PrivateOriginTest.stop_server
    request = origin_tests.PrivateOriginTest.request

    def test_idle_socket_overload_is_bounded_and_recovers_after_disconnect(self):
        sockets = []
        try:
            for _ in range(16):
                connection = socket.create_connection(('127.0.0.1', self.server.server_port), timeout=1)
                sockets.append(connection)
                connection.sendall(b'GET /owner-evidence HTTP/1.1\r\n')
            status, headers, body = self.request('/owner-evidence/api/presentation-status')
            self.assertEqual((status, body), (503, b''))
            self.assertEqual(headers['Retry-After'], '1')
        finally:
            for connection in sockets:
                connection.close()
        until = time.monotonic() + 1
        while True:
            response = self.request('/owner-evidence/api/presentation-status')
            if response[0] == 200 or time.monotonic() >= until:
                break
            time.sleep(0.01)
        self.assertEqual(response[0], 200)

    def test_four_parallel_inspections_and_sources_admit_without_starving_status(self):
        retained = {'reference': {'source-sha256': 'a' * 64, 'artifact-sha256': 'b' * 64, 'ordinal': 0},
                    'candidate': {'coordinates': {'page': 1, 'row': 1}, 'raw': {}, 'parsed': {}},
                    'source': {'federation': 'CMAS'}}
        entered, release = threading.Semaphore(0), threading.Event()
        blocking = False
        def reader(filters, authority):
            if blocking:
                entered.release()
                if not release.wait(timeout=2):
                    raise TimeoutError('owned fixture deadline')
            return {'schema': 'private-attempt-inspector/v1', 'pagination': {'offset': 0},
                    'rows': [copy.deepcopy(retained)]}
        reader.source_bytes = lambda row: pdf_bytes()
        self.server.comparison_reader = reader
        initial = json.loads(self.request('/owner-evidence/api/attempt-inspector')[2])
        source = '/owner-evidence/api/attempt-inspector/source/' + initial['rows'][0]['source_access']['retained_row_id']
        blocking = True
        paths = ['/owner-evidence/api/attempt-inspector?federation=CMAS',
                 '/owner-evidence/api/attempt-inspector?federation=AIDA', source, source + '/page/1']
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
            results = [workers.submit(self.request, path) for path in paths]
            try:
                for _ in paths:
                    self.assertTrue(entered.acquire(timeout=1), 'normal parallel read was queued')
                started = time.monotonic()
                status, headers, body = self.request('/owner-evidence/api/attempt-inspector')
                self.assertEqual(status, 503)
                self.assertEqual(headers['Retry-After'], '1')
                self.assertEqual(body, b'')
                self.assertLess(time.monotonic() - started, 0.3)
                started = time.monotonic()
                self.assertEqual(self.request('/owner-evidence/api/presentation-status')[0], 200)
                self.assertLess(time.monotonic() - started, 0.3)
            finally:
                release.set()
            responses = [result.result(timeout=3) for result in results]
        self.assertEqual([response[0] for response in responses], [200] * 4)
        self.assertTrue(responses[-1][2].startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertEqual(self.request('/owner-evidence/api/attempt-inspector')[0], 200)

    def test_owner_revision_change_while_canonical_read_waits_discards_inspection(self):
        from test_owner_decision_store import DecisionStoreTest
        fixture = DecisionStoreTest()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        digest, proposed = fixture.source_bound_proposal('inflight-owner')
        fixture.store.register(digest, proposed, idempotency_key='register-inflight-owner')
        self.server.decisions = fixture.store
        self.addCleanup(lambda: setattr(self.server, 'decisions', None))
        self.server.import_token = 'separate-owner-import-token-for-tests'
        entered, release = threading.Event(), threading.Event()
        def canonical_reader():
            entered.set()
            if not release.wait(timeout=2):
                raise TimeoutError('owned canonical fixture deadline')
            return None
        self.server.canonical_reader = canonical_reader
        self.server.comparison_reader = lambda filters, authority: {
            'schema': 'private-attempt-inspector/v1', 'rows': [], 'ranks': [1]}
        listing = json.loads(self.request('/owner-evidence/api/decisions')[2])
        csrf = listing['csrf_token']
        raw = json.dumps({'action': 'approve', 'expected_revision': listing['revision'],
                          'idempotency_key': 'approve-inflight-owner', 'reason': 'Synthetic revision',
                          'csrf_token': csrf}).encode()
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as workers:
            pending = workers.submit(self.request, '/owner-evidence/api/attempt-inspector')
            try:
                self.assertTrue(entered.wait(timeout=1))
                started = time.monotonic()
                status = self.request('/owner-evidence/api/decisions/inflight-owner/actions', method='POST', body=raw,
                    headers=[('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                             ('X-Freediving-Owner-Email', EMAIL), ('Origin', 'https://poc.alphacompose.com'),
                             ('X-Freediving-CSRF', csrf), ('Content-Type', 'application/json'),
                             ('Content-Length', str(len(raw)))])[0]
                self.assertEqual(status, 200)
                self.assertLess(time.monotonic() - started, 0.3, 'canonical subprocess held mutation lock')
            finally:
                release.set()
            status, _, body = pending.result(timeout=3)
        self.assertEqual((status, body), (409, b''))

    def test_withdrawal_during_inspection_discards_old_ranks_and_machine_challenge_stays_responsive(self):
        from sporting_authority import canonical
        from test_sporting_authority import SportingAuthorityTest
        fixture = SportingAuthorityTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.publish()
        self.server.sporting = fixture.authority
        self.server.sporting_request_key = 'isolated-synthetic-request-secret-32-characters'
        entered, release = threading.Event(), threading.Event()
        def reader(filters, authority):
            ranked = fixture.authority.current('9' * 64)['payload']['publication'] is not None
            entered.set()
            if not release.wait(timeout=2):
                raise TimeoutError('owned fixture deadline')
            return {'schema': 'private-attempt-inspector/v1', 'rows': [], 'ranks': [1] if ranked else []}
        self.server.comparison_reader = reader
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as workers:
            pending = workers.submit(self.request, '/owner-evidence/api/attempt-inspector')
            try:
                self.assertTrue(entered.wait(timeout=1))
                challenge = {'schema': 'sporting-authority-challenge/v1', 'nonce': '9' * 64}
                raw = canonical(challenge)
                signature = hmac.new(self.server.sporting_request_key.encode(), raw, sha256).hexdigest()
                started = time.monotonic()
                status, _, body = self.request('/owner-evidence/api/sporting-authority/current', method='POST',
                    headers=[('Host', '127.0.0.1:' + str(self.server.server_port)),
                             ('Content-Type', 'application/json'), ('Content-Length', str(len(raw))),
                             ('X-Freediving-Sporting-HMAC', signature)], body=raw)
                self.assertEqual(status, 200)
                self.assertIsNotNone(json.loads(body)['payload']['publication'])
                self.assertLess(time.monotonic() - started, 0.5)
                csrf = hmac.new(SECRET.encode(), ('decision-csrf-v1:' + EMAIL).encode(), sha256).hexdigest()
                action = json.dumps({'id': 'synthetic-cohort', 'action': 'reverse',
                                     'expected_revision': 4, 'idempotency_key': 'withdraw-inflight',
                                     'reason': 'Synthetic in-flight withdrawal', 'csrf_token': csrf}).encode()
                status = self.request('/owner-evidence/api/sporting-authority/actions', method='POST', body=action,
                    headers=[('Host', HOST), ('X-Freediving-Owner-Gateway', SECRET),
                             ('X-Freediving-Owner-Email', EMAIL), ('Origin', 'https://poc.alphacompose.com'),
                             ('X-Freediving-CSRF', csrf), ('Content-Type', 'application/json'),
                             ('Content-Length', str(len(action)))])[0]
                self.assertEqual(status, 200)
            finally:
                release.set()
            status, _, body = pending.result(timeout=3)
        self.assertEqual((status, body), (409, b''))
        status, _, body = self.request('/owner-evidence/api/attempt-inspector')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['ranks'], [])

    def test_parallel_pdf_renderer_overload_is_prompt_and_does_not_block_status(self):
        root = Path(self.tmp.name)
        entered, release = root / 'renderer-entered', root / 'renderer-release'
        executable = root / 'pdftoppm'
        executable.write_text('#!' + sys.executable + '\nimport pathlib,time,sys\n'
            'pathlib.Path(' + repr(str(entered)) + ').touch()\n'
            'p=pathlib.Path(' + repr(str(release)) + ')\n'
            'while not p.exists(): time.sleep(0.01)\n'
            'sys.stdout.buffer.write(b"\\x89PNG\\r\\n\\x1a\\n")\n')
        executable.chmod(0o700)
        retained = {'reference': {'source-sha256': 'a' * 64, 'artifact-sha256': 'b' * 64},
                    'candidate': {'coordinates': {'page': 1, 'row': 1}, 'raw': {}},
                    'source': {'federation': 'CMAS'}}
        def reader(filters, authority):
            return {'schema': 'private-attempt-inspector/v1', 'rows': [copy.deepcopy(retained)]}
        reader.source_bytes = lambda row: pdf_bytes()
        self.server.comparison_reader = reader
        result = json.loads(self.request('/owner-evidence/api/attempt-inspector')[2])
        path = '/owner-evidence/api/attempt-inspector/source/' + result['rows'][0]['source_access']['retained_row_id'] + '/page/1'
        with mock.patch.dict(os.environ, {'PATH': str(root) + os.pathsep + os.environ['PATH']}):
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as workers:
                pending = workers.submit(self.request, path)
                timer = None
                try:
                    until = time.monotonic() + 1
                    while not entered.exists() and time.monotonic() < until:
                        time.sleep(0.01)
                    self.assertTrue(entered.exists())
                    timer = threading.Timer(1, release.touch)
                    timer.start()
                    started = time.monotonic()
                    status, headers, _ = self.request(path)
                    self.assertEqual(status, 503)
                    self.assertEqual(headers['Retry-After'], '1')
                    self.assertLess(time.monotonic() - started, 0.3)
                    self.assertEqual(self.request('/owner-evidence/api/presentation-status')[0], 200)
                finally:
                    if timer is not None:
                        timer.cancel()
                        timer.join()
                    release.touch()
                self.assertEqual(pending.result(timeout=3)[0], 200)

    def test_expiry_or_changed_binding_at_publication_discards_old_ranks(self):
        token = ['current']
        def reader(filters, authority, *, deadline):
            token[0] = 'expired'
            return {'schema': 'private-attempt-inspector/v1', 'rows': [], 'ranks': [1]}
        reader.verify_current = lambda authority: token[0]
        self.server.comparison_reader = reader
        status, _, body = self.request('/owner-evidence/api/attempt-inspector')
        self.assertEqual((status, body), (409, b''))

    def test_tampered_current_binding_is_service_failure_not_invalid_filter(self):
        def reader(filters, authority, *, deadline):
            return {'schema': 'private-attempt-inspector/v1', 'rows': []}
        def verify(authority):
            raise ValueError('owned pinned bytes changed')
        reader.verify_current = verify
        self.server.comparison_reader = reader
        status, headers, body = self.request('/owner-evidence/api/attempt-inspector')
        self.assertEqual((status, body), (503, b''))
        self.assertEqual(headers['Retry-After'], '1')


class PackagedOriginConcurrencyTest(unittest.TestCase):
    setUp = origin_tests.PrivateOriginTest.setUp
    stop_server = origin_tests.PrivateOriginTest.stop_server
    request = origin_tests.PrivateOriginTest.request

    @classmethod
    def setUpClass(cls):
        import tempfile
        from test_private_attempt_inspector import package
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name).resolve()
        cls.runtime = package.build_runtime(origin_tests.ROOT, cls.root / 'package', 'isolated-http-synthetic-only')
        expression = ('(require (quote freediving.private-attempt-inspector-test)) '
                      '(prn freediving.private-attempt-inspector-test/packet)')
        cls.packet = subprocess.check_output(['clojure', '-Sdeps', '{:paths ["src" "resources" "test"]}',
            '-M', '-e', expression], cwd=origin_tests.ROOT, timeout=30)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_authenticated_parallel_http_uses_real_packaged_jvm_and_pdf(self):
        from private_attempt_inspector import create_reader
        root = Path(self.tmp.name).resolve()
        pdf = pdf_bytes()
        digest = sha256(pdf).hexdigest()
        packet = root / 'packet.edn'
        packet.write_bytes(self.packet.replace(b'a' * 64, digest.encode()))
        packet.chmod(0o600)
        source = root / 'source.pdf'
        source.write_bytes(pdf)
        source.chmod(0o600)
        config = root / 'comparison.json'
        config.write_text(json.dumps({**self.runtime, 'authority_evidence': None,
            'packet': {'path': str(packet), 'sha256': sha256(packet.read_bytes()).hexdigest()},
            'source_objects': {digest: {'path': str(source), 'sha256': digest, 'mime_type': 'application/pdf'}}}))
        config.chmod(0o600)
        self.server.comparison_reader = create_reader({'OWNER_EVIDENCE_COMPARISON_CONFIG': str(config)})
        result = json.loads(self.request('/owner-evidence/api/attempt-inspector')[2])
        self.assertEqual(result['coverage']['ranked'], 0)
        row = next(row for row in result['rows'] if row['federation'] == 'CMAS')
        source_path = '/owner-evidence/api/attempt-inspector/source/' + row['source_access']['retained_row_id']
        paths = ['/owner-evidence/api/attempt-inspector?federation=CMAS',
                 '/owner-evidence/api/attempt-inspector?federation=AIDA',
                 source_path, source_path + '/page/1', '/owner-evidence/api/presentation-status']
        barrier = threading.Barrier(len(paths))
        def get(path):
            barrier.wait(timeout=2)
            started = time.monotonic()
            response = self.request(path)
            return response, time.monotonic() - started
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(paths)) as workers:
            responses = list(workers.map(get, paths))
        self.assertEqual([response[0][0] for response in responses], [200] * len(paths))
        self.assertLess(max(elapsed for _, elapsed in responses), 12)
        self.assertLess(responses[-1][1], 0.5)
        self.assertTrue(responses[-2][0][2].startswith(b'\x89PNG\r\n\x1a\n'))
        for response, _ in responses[:2]:
            self.assertEqual(json.loads(response[2])['coverage']['ranked'], 0)
        self.assertNotIn('rank', json.loads(responses[2][0][2]))
        self.assertEqual(self.request(source_path, headers=[('Host', HOST)])[0], 403)


if __name__ == '__main__':
    unittest.main()
