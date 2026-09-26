import sys
import tempfile
import time
import unittest
import json
import os
from contextlib import redirect_stdout, redirect_stderr
from io import StringIO
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from browser_acquisition import BrowserAcquisitionError, capture_page
from acquire_source import acquire
from source_acquisition import AcquisitionClient, Policy
from capture_browser import main as capture_main


class Response:
    def __init__(self, status=200, content_type="text/html", body=b"<html>results</html>", headers=None):
        self.status = status
        self.headers = {"content-type": content_type, **(headers or {})}
        self._body = body
        self.reads = 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, size):
        self.reads += 1
        return self._body[:size]


class Request:
    def __init__(self, url, resource_type="document", headers=None):
        self.url = url
        self.resource_type = resource_type
        self._headers = headers or {}
        self.method = "GET"

    def all_headers(self):
        return self._headers


class Route:
    def __init__(self, url, response, requests, headers=None):
        self.request = Request(url, "stylesheet" if url.endswith(".css") else "document", headers)
        self.response = response
        self.requests = requests

    def fetch(self, **kwargs):
        raise AssertionError("Playwright must not buffer the publisher response")

    def fulfill(self, **kwargs):
        self.fulfilled = kwargs

    def abort(self):
        self.aborted = True


class Page:
    def __init__(self, responses, request_headers=None):
        self.responses = responses
        self.request_headers = request_headers
        self.requests = []
        self.routes = []

    def route(self, pattern, callback):
        self.callback = callback

    def unroute(self, pattern, callback):
        self.callback = None

    def goto(self, url, **kwargs):
        for request_url, response in self.responses:
            route = Route(request_url, response, self.requests, self.request_headers)
            self.routes.append(route)
            self.current_route = route
            self.callback(route)
        return self.responses[0][1]

    def open(self, request, **kwargs):
        self.requests.append((request.full_url, time.monotonic(), kwargs))
        response = self.current_route.response
        response = response.pop(0) if isinstance(response, list) else response
        if isinstance(response, Exception):
            raise response
        return response

    def content(self):
        return "<html>rendered results</html>"

    @property
    def url(self):
        return self.responses[-1][0]


class BrowserCaptureTest(unittest.TestCase):
    def test_capture_cli_rejects_sensitive_context_and_url_on_dry_run(self):
        with tempfile.TemporaryDirectory() as directory:
            output = str(Path(directory) / "output")
            for url, context in [
                ("https://publisher.example/results", '{"filters":{"session":"private"}}'),
                ("https://publisher.example/results?token=private", "{}"),
                ("https://publisher.example/results#session=private", "{}"),
            ]:
                with self.subTest(url=url, context=context), redirect_stderr(StringIO()):
                    with self.assertRaises(SystemExit):
                        capture_main([url, output, "--context-json", context, "--dry-run"])

    def test_capture_cli_rejects_sensitive_selection_and_non_iso_date(self):
        with tempfile.TemporaryDirectory() as directory:
            spec = Path(directory) / "selection.json"
            baseline = {"schema": "browser-selection/v1", "selected_date": "2025-06-28",
                        "actions": [{"type": "click", "selector": "button.date"}],
                        "selected_state": {"selector": ".date", "text": "2025-06-28"},
                        "result_selector": "table.results tr", "min_results": 1,
                        "response_contains": "Athlete"}
            for update in ({"selected_date": "20250628"}, {"session": "private"},
                           {"verified_filters": [{"name": "discipline", "selector": ".filter", "text": "token=private"}]}):
                spec.write_text(json.dumps({**baseline, **update}))
                with self.subTest(update=update), redirect_stderr(StringIO()):
                    with self.assertRaises(SystemExit):
                        capture_main(["https://publisher.example/results", str(Path(directory) / "output"),
                                      "--selection-file", str(spec), "--dry-run"])

    def test_capture_cli_validates_selection_file_before_browser_start(self):
        with tempfile.TemporaryDirectory() as directory:
            spec = Path(directory) / "selection.json"
            spec.write_text(json.dumps({"schema": "browser-selection/v1", "selected_date": "2025-06-28",
                "actions": [{"type": "select_option", "selector": "select.day", "value": "2025-06-28"}],
                "selected_state": {"selector": ".date", "text": "2025-06-28"},
                "result_selector": "table.results tr", "min_results": 1,
                "response_contains": "Athlete"}))
            with redirect_stdout(StringIO()) as output:
                result = capture_main(["https://publisher.example/results", str(Path(directory) / "output"),
                                       "--selection-file", str(spec), "--dry-run"])
        self.assertEqual(0, result)
        self.assertEqual("2025-06-28", json.loads(output.getvalue())["selected_date"])

    def test_capture_cli_rejects_broadly_readable_storage_state(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.json"
            state.write_text("{}")
            os.chmod(state, 0o644)
            with redirect_stderr(StringIO()):
                with self.assertRaises(SystemExit):
                    capture_main(["https://publisher.example/results", str(Path(directory) / "output"),
                                  "--storage-state", str(state), "--dry-run"])

    def test_selected_date_capture_verifies_result_view_and_response(self):
        class SelectedPage(Page):
            def __init__(self, responses):
                super().__init__(responses)
                self.selected = None

            def select_option(self, selector, value):
                self.selected = (selector, value)

            def locator(self, selector):
                page = self

                class Locator:
                    def count(self):
                        return 2 if selector == "table.results tbody tr" and page.selected else 0

                    def inner_text(self):
                        if not page.selected:
                            return ""
                        return {".selected-date": "2025-06-28", ".selected-discipline": "DYN"}.get(selector, "")

                return Locator()

            def content(self):
                return "<html><table class='results'><tr><td>Athlete</td></tr></table></html>" if self.selected else "<html></html>"

        url = "https://publisher.example/StartList/4349"
        page = SelectedPage([(url, Response(body=b"<html>2025-06-28 Athlete</html>"))])
        spec = {"schema": "browser-selection/v1", "selected_date": "2025-06-28",
                "actions": [{"type": "select_option", "selector": "select.day", "value": "2025-06-28"}],
                "selected_state": {"selector": ".selected-date", "text": "2025-06-28"},
                "verified_filters": [{"name": "discipline", "selector": ".selected-discipline", "text": "DYN"}],
                "result_selector": "table.results tbody tr", "min_results": 2,
                "response_contains": "Athlete"}
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0),
                                       lease_path=Path(directory) / "lease.sqlite3")
            capture = capture_page(page, url, client, opener=page, selection=spec)
        self.assertEqual(("select.day", "2025-06-28"), page.selected)
        self.assertEqual("2025-06-28", capture.selected_state["selected_date"])
        self.assertEqual(2, capture.selected_state["result_count"])
        self.assertEqual({"discipline": "DYN"}, capture.selected_state["filters"])
        self.assertEqual(url, capture.final_url)

    def test_selected_date_capture_rejects_missing_result_rows(self):
        class EmptyPage(Page):
            def select_option(self, selector, value):
                pass

            def locator(self, selector):
                class Locator:
                    def count(self):
                        return 0

                    def inner_text(self):
                        return "2025-06-28"

                return Locator()

        url = "https://publisher.example/StartList/4349"
        page = EmptyPage([(url, Response(body=b"<html>Athlete</html>"))])
        spec = {"schema": "browser-selection/v1", "selected_date": "2025-06-28",
                "actions": [{"type": "select_option", "selector": "select.day", "value": "2025-06-28"}],
                "selected_state": {"selector": ".selected-date", "text": "2025-06-28"},
                "result_selector": "table.results tbody tr", "min_results": 1,
                "response_contains": "Athlete"}
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0),
                                       lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError) as caught:
                capture_page(page, url, client, opener=page, selection=spec)
        self.assertEqual("incomplete_rendered_results", caught.exception.reason)

    def test_unavailable_archive_stops_before_browser_request(self):
        url = "https://publisher.example/results"
        page = Page([(url, Response())])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0),
                                       lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError) as caught:
                capture_page(page, url, client, opener=page,
                             archive_roots=[Path(directory) / "missing"])
        self.assertEqual("archive_inventory_failed", caught.exception.reason)
        self.assertEqual([], page.requests)

    def test_verified_archive_response_is_reused_without_browser_request(self):
        class Handler(BaseHTTPRequestHandler):
            calls = 0

            def do_GET(self):
                Handler.calls += 1
                body = b"<html>archived results</html>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/results"
            page = Page([(url, Response())])
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                client = AcquisitionClient(default_policy=Policy(min_interval=0),
                                           lease_path=root / "lease.sqlite3")
                acquire(url, "html", root / "archive", client=client)
                capture = capture_page(page, url, client, opener=page,
                                       archive_roots=[root / "archive"])
            self.assertEqual(1, Handler.calls)
            self.assertEqual([], page.requests)
            self.assertEqual(b"<html>archived results</html>", capture.responses[0].body)
            self.assertEqual(1, len(capture.reuses))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_session_bound_browser_request_does_not_reuse_public_receipt(self):
        class Handler(BaseHTTPRequestHandler):
            calls = 0

            def do_GET(self):
                Handler.calls += 1
                body = b"<html>results</html>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/results"
            page = Page([(url, Response())], request_headers={"Cookie": "session=private"})
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                client = AcquisitionClient(default_policy=Policy(min_interval=0),
                                           lease_path=root / "lease.sqlite3")
                acquire(url, "html", root / "archive", client=client)
                capture = capture_page(page, url, client, opener=None,
                                       archive_roots=[root / "archive"])
            self.assertEqual(2, Handler.calls)
            self.assertEqual(0, len(capture.reuses))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_browser_session_header_reaches_bounded_http_route(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get("Cookie") != "session=private":
                    self.send_error(403)
                    return
                body = b"<html>member results</html>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/results"
            page = Page([(url, Response())], request_headers={"Cookie": "session=private"})
            with tempfile.TemporaryDirectory() as directory:
                client = AcquisitionClient(default_policy=Policy(min_interval=0),
                                           lease_path=Path(directory) / "lease.sqlite3")
                capture = capture_page(page, url, client)
            self.assertEqual(b"<html>member results</html>", capture.responses[0].body)
            self.assertNotIn("private", str(capture))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_declared_oversized_response_is_rejected_before_body_read(self):
        url = "https://publisher.example/results.pdf"
        response = Response(content_type="application/pdf", body=b"%PDF-", headers={"content-length": "1000"})
        page = Page([(url, response)])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0, max_bytes=32),
                                       lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError) as caught:
                capture_page(page, url, client, opener=page)
        self.assertEqual("size_limit", caught.exception.reason)
        self.assertEqual(0, response.reads)
        self.assertTrue(page.routes[0].aborted)

    def test_unknown_length_download_stops_after_bounded_read(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "application/pdf")
                self.end_headers()
                self.wfile.write(b"%PDF-" + b"x" * 1000)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/large.pdf"
            page = Page([(url, Response(content_type="application/pdf", body=b"%PDF-"))])
            with tempfile.TemporaryDirectory() as directory:
                client = AcquisitionClient(default_policy=Policy(min_interval=0, max_bytes=32),
                                           lease_path=Path(directory) / "lease.sqlite3")
                with self.assertRaises(BrowserAcquisitionError) as caught:
                    capture_page(page, url, client)
            self.assertEqual("size_limit", caught.exception.reason)
            self.assertTrue(page.routes[0].aborted)
            self.assertEqual([], page.requests)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_navigation_api_and_download_share_host_budget(self):
        host = "https://publisher.example"
        page = Page([(host + "/event", Response()),
                     (host + "/api", Response(content_type="application/json", body=b'{"rows":[]}')),
                     (host + "/result.pdf", Response(content_type="application/pdf", body=b"%PDF-1.7\n"))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0.08), lease_path=Path(directory) / "lease.sqlite3")
            result = capture_page(page, host + "/event", client, opener=page)
        self.assertEqual("<html>rendered results</html>", result.dom)
        self.assertEqual(host + "/event", result.responses[0].url)
        self.assertEqual(3, len(page.requests))
        self.assertTrue(all(b[1] - a[1] >= 0.065 for a, b in zip(page.requests, page.requests[1:])))
        self.assertTrue(all(route.fulfilled for route in page.routes))
        self.assertTrue(all(call[2]["timeout"] > 0 for call in page.requests))

    def test_challenge_page_stops_without_second_request(self):
        page = Page([("https://publisher.example/event", Response(body=b"<html>Cloudflare challenge: verify you are human</html>"))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0), lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError) as caught:
                capture_page(page, "https://publisher.example/event", client, opener=page)
        self.assertEqual("access_challenge", caught.exception.reason)
        self.assertEqual(1, len(page.requests))
        self.assertTrue(page.routes[0].aborted)
        self.assertNotIn("Cloudflare", str(caught.exception))

    def test_retry_is_paced_and_bounded(self):
        host = "https://publisher.example"
        page = Page([(host + "/api", Response(status=429, headers={"retry-after": "0"}))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0.08, max_attempts=2), lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError):
                capture_page(page, host + "/api", client, opener=page)
        self.assertEqual(2, len(page.requests))
        self.assertGreaterEqual(page.requests[1][1] - page.requests[0][1], 0.065)

    def test_retry_decisions_are_retained_without_request_secrets(self):
        url = "https://publisher.example/api?view=private"
        page = Page([(url, [Response(status=429, headers={"retry-after": "0"}),
                            Response(content_type="application/json", body=b'{"rows":[]}')])])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=2),
                                       lease_path=Path(directory) / "lease.sqlite3")
            capture = capture_page(page, url, client, opener=page)
        self.assertTrue(any(event["reason"] == "retry_after" for event in capture.events))
        self.assertEqual(2, len(page.requests))
        self.assertNotIn("private", str(capture.events))

    def test_sensitive_redirect_stops_before_browser_follows(self):
        page = Page([("https://publisher.example/start",
                      Response(status=302, headers={"location": "/next?token=private"}))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0), lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError) as caught:
                capture_page(page, "https://publisher.example/start", client, opener=page)
        self.assertEqual("unsafe_redirect", caught.exception.reason)
        self.assertEqual(1, len(page.requests))

    def test_browser_redirect_records_observed_chain(self):
        source = "https://publisher.example/start"
        target = "https://other.example/results"
        page = Page([(source, Response(status=302, headers={"location": target})),
                     (target, Response())])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0), lease_path=Path(directory) / "lease.sqlite3")
            capture = capture_page(page, source, client, opener=page)
        self.assertEqual(((source, target, 302),), capture.redirects)
        self.assertEqual(target, capture.responses[0].url)

    def test_browser_network_retry_enters_budget_again(self):
        host = "https://publisher.example"
        page = Page([(host + "/event", [OSError("temporary"), Response()])])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0.08, max_attempts=2, max_delay=0),
                                       lease_path=Path(directory) / "lease.sqlite3")
            result = capture_page(page, host + "/event", client, opener=page)
        self.assertEqual(1, len(result.responses))
        self.assertEqual(2, len(page.requests))
        self.assertGreaterEqual(page.requests[1][1] - page.requests[0][1], 0.065)

    def test_page_assets_are_paced_without_result_signature_requirement(self):
        host = "https://publisher.example"
        page = Page([(host + "/event", Response()),
                     (host + "/style.css", Response(content_type="text/css", body=b"body { color: black }"))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0.08), lease_path=Path(directory) / "lease.sqlite3")
            capture_page(page, host + "/event", client, opener=page)
        self.assertEqual(2, len(page.requests))
        self.assertGreaterEqual(page.requests[1][1] - page.requests[0][1], 0.065)


if __name__ == "__main__":
    unittest.main()
