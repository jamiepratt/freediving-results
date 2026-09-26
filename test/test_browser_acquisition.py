import sys
import tempfile
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from browser_acquisition import BrowserAcquisitionError, capture_page
from source_acquisition import AcquisitionClient, Policy


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


class BrowserCaptureTest(unittest.TestCase):
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
