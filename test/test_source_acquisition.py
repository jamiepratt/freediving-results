import sys
import subprocess
import tempfile
import threading
import time
import unittest
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime, parsedate_to_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from source_acquisition import AcquisitionClient, AcquisitionError, CMAS_POLICY, Policy


class LocalPublisher:
    def __init__(self, respond):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                status, headers, body = respond(self.path)
                self.send_response(status)
                for name, value in headers.items():
                    self.send_header(name, value)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}/result"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class AcquisitionTest(unittest.TestCase):
    def test_separate_processes_share_spacing_and_concurrency(self):
        arrivals = []
        lock = threading.Lock()

        def respond(path):
            with lock:
                arrivals.append(time.monotonic())
            time.sleep(0.18)
            return 200, {}, b"ok"

        publisher = LocalPublisher(respond)
        self.addCleanup(publisher.close)
        with tempfile.TemporaryDirectory() as directory:
            state = str(Path(directory) / "leases.sqlite3")
            script = (
                "import sys; sys.path.insert(0, sys.argv[1]); "
                "from source_acquisition import AcquisitionClient, Policy; "
                "AcquisitionClient(default_policy=Policy(concurrency=1, min_interval=0.05), "
                "lease_path=sys.argv[2]).fetch(sys.argv[3])"
            )
            scripts = str(Path(__file__).resolve().parents[1] / "scripts")
            workers = [subprocess.Popen([sys.executable, "-c", script, scripts, state, publisher.url])
                       for _ in range(3)]
            for worker in workers:
                self.assertEqual(0, worker.wait(timeout=5))
        self.assertEqual(3, len(arrivals))
        self.assertTrue(all(b - a >= 0.16 for a, b in zip(arrivals, arrivals[1:])))

    def test_new_client_and_public_lease_preserve_host_spacing(self):
        with tempfile.TemporaryDirectory() as directory:
            state = str(Path(directory) / "leases.sqlite3")
            policy = Policy(concurrency=1, min_interval=0.18)
            url = "https://publisher.example/private?token=secret"
            with AcquisitionClient(default_policy=policy, state_path=state).lease(url) as first_wait:
                self.assertGreaterEqual(first_wait, 0)
            with AcquisitionClient(default_policy=policy, state_path=state).lease(url) as second_wait:
                self.assertGreaterEqual(second_wait, 0.14)
            self.assertNotIn("secret", Path(state).read_bytes().decode("utf-8", errors="ignore"))

    def test_redirect_target_shares_budget_with_new_client(self):
        arrived = []
        target = LocalPublisher(lambda path: (arrived.append(time.monotonic()) or 200, {}, b"target"))
        self.addCleanup(target.close)
        source = LocalPublisher(lambda path: (302, {"Location": target.url}, b""))
        self.addCleanup(source.close)
        with tempfile.TemporaryDirectory() as directory:
            state = str(Path(directory) / "leases.sqlite3")
            policy = Policy(concurrency=1, min_interval=0.18)
            AcquisitionClient(default_policy=policy, state_path=state).fetch(target.url)
            AcquisitionClient(default_policy=policy, state_path=state).fetch(source.url)
        self.assertGreaterEqual(arrived[1] - arrived[0], 0.14)

    def test_crashed_worker_does_not_hold_concurrency_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            state = str(Path(directory) / "leases.sqlite3")
            scripts = str(Path(__file__).resolve().parents[1] / "scripts")
            script = (
                "import sys, time; sys.path.insert(0, sys.argv[1]); "
                "from source_acquisition import AcquisitionClient, Policy; "
                "client = AcquisitionClient(default_policy=Policy(concurrency=1, min_interval=0), state_path=sys.argv[2]); "
                "lease = client.lease('https://publisher.example/a'); lease.__enter__(); "
                "print('ready', flush=True); time.sleep(60)"
            )
            worker = subprocess.Popen([sys.executable, "-u", "-c", script, scripts, state],
                                      stdout=subprocess.PIPE, text=True)
            try:
                self.assertEqual("ready\n", worker.stdout.readline())
            finally:
                worker.kill()
                worker.wait(timeout=5)
                worker.stdout.close()
            client = AcquisitionClient(default_policy=Policy(concurrency=1, min_interval=0), state_path=state)
            with client.lease("https://publisher.example/b") as waited:
                self.assertLess(waited, 0.5)

    def test_retries_429_after_server_delay(self):
        calls = []

        def respond(path):
            calls.append(path)
            if len(calls) == 1:
                return 429, {"Retry-After": "0"}, b"later"
            return 200, {"Content-Length": "2"}, b"ok"

        publisher = LocalPublisher(respond)
        self.addCleanup(publisher.close)
        result = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2)).fetch(publisher.url)
        self.assertEqual(b"ok", result.body)
        self.assertEqual(2, result.attempts)
        self.assertEqual(["/result", "/result"], calls)

    def test_http_date_retry_after_does_not_retry_early(self):
        times = []
        retry_at = datetime.now(timezone.utc) + timedelta(seconds=2)
        header = format_datetime(retry_at, usegmt=True)

        def respond(path):
            times.append(datetime.now(timezone.utc))
            return (429, {"Retry-After": header}, b"") if len(times) == 1 else (200, {}, b"ok")

        publisher = LocalPublisher(respond)
        self.addCleanup(publisher.close)
        result = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2, max_delay=3)).fetch(publisher.url)
        self.assertEqual(b"ok", result.body)
        self.assertGreaterEqual(times[1], parsedate_to_datetime(header))

    def test_permanent_403_stops_without_retry_or_body(self):
        calls = []
        publisher = LocalPublisher(lambda path: (calls.append(path) or 403, {}, b"challenge"))
        self.addCleanup(publisher.close)
        with self.assertRaises(AcquisitionError) as caught:
            AcquisitionClient(default_policy=Policy(min_interval=0)).fetch(publisher.url)
        self.assertEqual(403, caught.exception.status)
        self.assertEqual(1, len(calls))
        self.assertNotIn("challenge", str(caught.exception))

    def test_concurrent_workers_share_per_host_spacing(self):
        started = []
        lock = threading.Lock()

        def respond(path):
            with lock:
                started.append(time.monotonic())
            time.sleep(0.08)
            return 200, {}, b"ok"

        publisher = LocalPublisher(respond)
        self.addCleanup(publisher.close)
        client = AcquisitionClient(default_policy=Policy(concurrency=1, min_interval=0.12))
        workers = [threading.Thread(target=lambda: client.fetch(publisher.url)) for _ in range(3)]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()
        self.assertEqual(3, len(started))
        self.assertTrue(all(b - a >= 0.10 for a, b in zip(started, started[1:])))

    def test_redirect_obeys_target_host_gate(self):
        started = []

        def target(path):
            started.append(time.monotonic())
            return 200, {}, b"target"

        destination = LocalPublisher(target)
        self.addCleanup(destination.close)
        source = LocalPublisher(lambda path: (302, {"Location": destination.url}, b""))
        self.addCleanup(source.close)
        client = AcquisitionClient(default_policy=Policy(concurrency=1, min_interval=0.12))
        client.fetch(destination.url)
        result = client.fetch(source.url)
        self.assertEqual(b"target", result.body)
        self.assertEqual(destination.url, result.final_url)
        self.assertGreaterEqual(started[1] - started[0], 0.10)

    def test_incomplete_body_is_retried_then_exhausted(self):
        calls = []

        def respond(path):
            calls.append(path)
            return 200, {"Content-Length": "10"}, b"short"

        publisher = LocalPublisher(respond)
        self.addCleanup(publisher.close)
        with self.assertRaises(AcquisitionError) as caught:
            AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2, max_delay=0)).fetch(publisher.url)
        self.assertEqual("attempts_exhausted", caught.exception.reason)
        self.assertEqual(2, len(calls))

    def test_5xx_retries_and_attempt_limit(self):
        calls = []

        def respond(path):
            calls.append(path)
            return 503, {}, b"temporary"

        publisher = LocalPublisher(respond)
        self.addCleanup(publisher.close)
        with self.assertRaises(AcquisitionError) as caught:
            AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2, max_delay=0)).fetch(publisher.url)
        self.assertEqual(503, caught.exception.status)
        self.assertEqual(2, len(calls))
        self.assertTrue(any(event.reason == "backoff" for event in caught.exception.events))

    def test_non_retryable_501_and_invalid_retry_after_are_bounded(self):
        permanent = LocalPublisher(lambda path: (501, {}, b"unsupported"))
        self.addCleanup(permanent.close)
        with self.assertRaises(AcquisitionError) as caught:
            AcquisitionClient(default_policy=Policy(min_interval=0, max_delay=0)).fetch(permanent.url)
        self.assertEqual("permanent_status", caught.exception.reason)

        calls = []

        def invalid_header(path):
            calls.append(path)
            return (429, {"Retry-After": "NaN"}, b"") if len(calls) == 1 else (200, {}, b"ok")

        publisher = LocalPublisher(invalid_header)
        self.addCleanup(publisher.close)
        result = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2, max_delay=0)).fetch(publisher.url)
        self.assertEqual(b"ok", result.body)
        self.assertTrue(any(event.reason == "backoff" for event in result.events))

    def test_network_error_is_bounded_and_safe(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), BaseHTTPRequestHandler)
        port = server.server_port
        server.server_close()
        with self.assertRaises(AcquisitionError) as caught:
            AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2, max_delay=0, timeout=0.2)).fetch(
                f"http://127.0.0.1:{port}/secret?token=private")
        self.assertEqual(2, caught.exception.attempts)
        self.assertNotIn("private", str(caught.exception))
        self.assertTrue(all(event.host == "127.0.0.1" for event in caught.exception.events))

    def test_cmas_defaults_are_stricter_and_configurable(self):
        client = AcquisitionClient()
        self.assertEqual(CMAS_POLICY, client.policy_for("www.cmas.org"))
        self.assertLess(CMAS_POLICY.concurrency, client.default_policy.concurrency)
        self.assertGreater(CMAS_POLICY.min_interval, client.default_policy.min_interval)
        custom = Policy(concurrency=1, min_interval=5)
        self.assertEqual(custom, AcquisitionClient(host_policies={"WWW.CMAS.ORG": custom}).policy_for("www.cmas.org"))


if __name__ == "__main__":
    unittest.main()
