import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from browser_acquisition import BrowserAcquisitionError, capture_page
from source_acquisition import AcquisitionClient, Policy


class Response:
    def __init__(self, status=200, content_type="text/html", body=b"<html>results</html>", headers=None):
        self.status = status
        self.headers = {"content-type": content_type, **(headers or {})}
        self._body = body

    def body(self):
        return self._body


class Request:
    def __init__(self, url, resource_type="document"):
        self.url = url
        self.resource_type = resource_type


class Route:
    def __init__(self, url, response, requests):
        self.request = Request(url, "stylesheet" if url.endswith(".css") else "document")
        self.response = response
        self.requests = requests

    def fetch(self, **kwargs):
        self.requests.append((self.request.url, time.monotonic(), kwargs))
        response = self.response.pop(0) if isinstance(self.response, list) else self.response
        if isinstance(response, Exception):
            raise response
        return response

    def fulfill(self, **kwargs):
        self.fulfilled = kwargs

    def abort(self):
        self.aborted = True


class Page:
    def __init__(self, responses):
        self.responses = responses
        self.requests = []
        self.routes = []

    def route(self, pattern, callback):
        self.callback = callback

    def unroute(self, pattern, callback):
        self.callback = None

    def goto(self, url, **kwargs):
        for request_url, response in self.responses:
            route = Route(request_url, response, self.requests)
            self.routes.append(route)
            self.callback(route)
        return self.responses[0][1]

    def content(self):
        return "<html>rendered results</html>"


class BrowserCaptureTest(unittest.TestCase):
    def test_navigation_api_and_download_share_host_budget(self):
        host = "https://publisher.example"
        page = Page([(host + "/event", Response()),
                     (host + "/api", Response(content_type="application/json", body=b'{"rows":[]}')),
                     (host + "/result.pdf", Response(content_type="application/pdf", body=b"%PDF-1.7\n"))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0.08), lease_path=Path(directory) / "lease.sqlite3")
            result = capture_page(page, host + "/event", client)
        self.assertEqual("<html>rendered results</html>", result.dom)
        self.assertEqual(host + "/event", result.responses[0].url)
        self.assertEqual(3, len(page.requests))
        self.assertTrue(all(b[1] - a[1] >= 0.065 for a, b in zip(page.requests, page.requests[1:])))
        self.assertTrue(all(route.fulfilled for route in page.routes))
        self.assertTrue(all(call[2]["max_redirects"] == 0 for call in page.requests))

    def test_challenge_page_stops_without_second_request(self):
        page = Page([("https://publisher.example/event", Response(body=b"<html>Cloudflare challenge: verify you are human</html>"))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0), lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError) as caught:
                capture_page(page, "https://publisher.example/event", client)
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
                capture_page(page, host + "/api", client)
        self.assertEqual(2, len(page.requests))
        self.assertGreaterEqual(page.requests[1][1] - page.requests[0][1], 0.065)

    def test_sensitive_redirect_stops_before_browser_follows(self):
        page = Page([("https://publisher.example/start",
                      Response(status=302, headers={"location": "/next?token=private"}))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0), lease_path=Path(directory) / "lease.sqlite3")
            with self.assertRaises(BrowserAcquisitionError) as caught:
                capture_page(page, "https://publisher.example/start", client)
        self.assertEqual("unsafe_redirect", caught.exception.reason)
        self.assertEqual(1, len(page.requests))

    def test_browser_redirect_records_observed_chain(self):
        source = "https://publisher.example/start"
        target = "https://other.example/results"
        page = Page([(source, Response(status=302, headers={"location": target})),
                     (target, Response())])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0), lease_path=Path(directory) / "lease.sqlite3")
            capture = capture_page(page, source, client)
        self.assertEqual(((source, target, 302),), capture.redirects)
        self.assertEqual(target, capture.responses[0].url)

    def test_browser_network_retry_enters_budget_again(self):
        host = "https://publisher.example"
        page = Page([(host + "/event", [OSError("temporary"), Response()])])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0.08, max_attempts=2, max_delay=0),
                                       lease_path=Path(directory) / "lease.sqlite3")
            result = capture_page(page, host + "/event", client)
        self.assertEqual(1, len(result.responses))
        self.assertEqual(2, len(page.requests))
        self.assertGreaterEqual(page.requests[1][1] - page.requests[0][1], 0.065)

    def test_page_assets_are_paced_without_result_signature_requirement(self):
        host = "https://publisher.example"
        page = Page([(host + "/event", Response()),
                     (host + "/style.css", Response(content_type="text/css", body=b"body { color: black }"))])
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0.08), lease_path=Path(directory) / "lease.sqlite3")
            capture_page(page, host + "/event", client)
        self.assertEqual(2, len(page.requests))
        self.assertGreaterEqual(page.requests[1][1] - page.requests[0][1], 0.065)


if __name__ == "__main__":
    unittest.main()
