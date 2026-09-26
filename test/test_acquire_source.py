import json
import hashlib
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from acquire_source import SourceRejected, acquire
from source_acquisition import AcquisitionClient, Policy


class Publisher:
    def __init__(self, respond):
        self.requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(handler):
                self.requests.append(handler.path)
                status, headers, body = respond(handler.path)
                handler.send_response(status)
                for key, value in headers.items():
                    handler.send_header(key, value)
                handler.end_headers()
                handler.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}/source"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class AcquireSourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name) / "private"
        self.client = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=1))

    def publisher(self, respond):
        publisher = Publisher(respond)
        self.addCleanup(publisher.close)
        return publisher

    def test_pdf_preserves_exact_bytes_and_provenance(self):
        body = b"%PDF-1.7\n\x00source bytes\n"
        publisher = self.publisher(lambda path: (200, {"Content-Type": "application/pdf"}, body))
        receipt = acquire(publisher.url, "pdf", self.output, client=self.client)
        self.assertEqual(body, Path(receipt["source_path"]).read_bytes())
        provenance = json.loads(Path(receipt["provenance_path"]).read_text())
        self.assertEqual(publisher.url, provenance["final_url"])
        self.assertEqual([publisher.url], provenance["redirect_chain"])
        self.assertEqual("application/pdf", provenance["content_type"])
        self.assertEqual(200, provenance["status"])
        self.assertEqual(len(body), provenance["byte_length"])
        self.assertEqual(hashlib.sha256(body).hexdigest(), provenance["sha256"])
        self.assertIn("started_at", provenance)
        self.assertIn("retrieved_at", provenance)
        self.assertGreaterEqual(provenance["elapsed_seconds"], 0)
        self.assertEqual(1, len(publisher.requests))
        self.assertEqual(0o700, self.output.stat().st_mode & 0o777)
        self.assertEqual(0o600, Path(receipt["source_path"]).stat().st_mode & 0o777)

    def test_json_and_html_retain_distinct_exact_representations(self):
        cases = [
            ("json", "application/json; charset=utf-8", b'{"results":[1,2]}'),
            ("html", "text/html; charset=utf-8", b"<!doctype html><html><body>Results</body></html>"),
        ]
        for representation, mime, body in cases:
            with self.subTest(representation=representation):
                publisher = self.publisher(lambda path: (200, {"Content-Type": mime}, body))
                receipt = acquire(publisher.url, representation, self.output, client=self.client)
                self.assertEqual(body, Path(receipt["source_path"]).read_bytes())
                self.assertEqual(mime, json.loads(Path(receipt["provenance_path"]).read_text())["content_type"])

    def test_redirect_and_retry_have_one_successful_artifact(self):
        target = self.publisher(lambda path: (200, {"Content-Type": "application/pdf"}, b"%PDF-1.4\nresult"))
        calls = []

        def redirect(path):
            calls.append(path)
            if len(calls) == 1:
                return 429, {"Retry-After": "0"}, b"wait"
            return 302, {"Location": target.url}, b""

        source = self.publisher(redirect)
        client = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2, max_delay=0))
        receipt = acquire(source.url, "pdf", self.output, client=client)
        provenance = json.loads(Path(receipt["provenance_path"]).read_text())
        self.assertEqual([source.url, target.url], provenance["redirect_chain"])
        self.assertEqual(2, provenance["attempts"])
        self.assertEqual(2, len(calls))
        self.assertEqual(1, len(target.requests))
        self.assertEqual(1, len(list(self.output.glob("*.pdf"))))

    def test_challenge_and_wrong_response_never_become_sources(self):
        cases = [
            ("pdf", "text/html", b"<html>Cloudflare security challenge</html>", "access_challenge"),
            ("pdf", "application/pdf", b"not a pdf", "invalid_pdf_signature"),
            ("json", "application/json", b"{invalid", "invalid_json"),
            ("html", "text/plain", b"<html>wrong type</html>", "wrong_content_type"),
        ]
        for representation, mime, body, reason in cases:
            with self.subTest(reason=reason):
                publisher = self.publisher(lambda path: (200, {"Content-Type": mime}, body))
                with self.assertRaises(SourceRejected) as caught:
                    acquire(publisher.url, representation, self.output, client=self.client)
                self.assertEqual(reason, caught.exception.reason)
                gap = json.loads(Path(caught.exception.gap_path).read_text())
                self.assertEqual(reason, gap["reason"])
                self.assertNotIn("body", gap)
                self.assertNotIn("url", gap)
                self.assertEqual(1, len(publisher.requests))
        self.assertEqual([], list(self.output.glob("*.pdf")))

    def test_403_records_safe_terminal_gap_without_retry(self):
        publisher = self.publisher(lambda path: (403, {"Content-Type": "text/html"}, b"secret blocked body"))
        with self.assertRaises(SourceRejected) as caught:
            acquire(publisher.url, "html", self.output, client=self.client)
        gap_text = Path(caught.exception.gap_path).read_text()
        self.assertEqual(1, len(publisher.requests))
        self.assertEqual(403, json.loads(gap_text)["status"])
        self.assertEqual(64, len(json.loads(gap_text)["source_identity"]))
        self.assertNotIn("secret", gap_text)
        self.assertNotIn(publisher.url, gap_text)

    def test_sensitive_redirect_is_rejected_before_target_request(self):
        target = self.publisher(lambda path: (200, {"Content-Type": "application/pdf"}, b"%PDF-1.4\n"))
        source = self.publisher(lambda path: (302, {"Location": target.url + "?token=private"}, b""))
        with self.assertRaises(SourceRejected) as caught:
            acquire(source.url, "pdf", self.output, client=self.client)
        self.assertEqual("unsafe_redirect", caught.exception.reason)
        self.assertEqual([], target.requests)
        self.assertNotIn("private", Path(caught.exception.gap_path).read_text())

    def test_dry_run_shows_effective_policy_without_network_or_files(self):
        receipt = acquire("https://www.cmas.org/results", "pdf", self.output, dry_run=True)
        self.assertTrue(receipt["dry_run"])
        self.assertEqual(1, receipt["policy"]["concurrency"])
        self.assertEqual(3.0, receipt["policy"]["min_interval"])
        self.assertFalse(self.output.exists())

    def test_cli_requires_representation_and_omits_sensitive_url(self):
        command = [sys.executable, str(ROOT / "scripts" / "acquire_source.py"),
                   "https://www.cmas.org/results?token=private", "pdf", str(self.output), "--dry-run"]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("private", result.stdout + result.stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
