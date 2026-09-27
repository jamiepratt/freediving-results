import json
import sys
import tempfile
import threading
import unittest
from datetime import datetime
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from fipsas_calendar import CalendarError, capture_months
from source_acquisition import AcquisitionClient, Policy


class FipsasCalendarTest(unittest.TestCase):
    def test_selected_month_is_verified_and_retained_without_nonce(self):
        requests = []

        class Publisher(BaseHTTPRequestHandler):
            def do_POST(self):
                form = parse_qs(self.rfile.read(int(self.headers["Content-Length"])).decode())
                requests.append((self.path, form))
                body = json.dumps({"status": "GOOD", "json": [],
                    "html": "<div class='eventon_list_event no_events'>Nessun Evento</div>",
                    "cal_month_title": "<span class='evo_header_mo'>gennaio</span>, <span class='evo_header_yr'>2025</span>",
                    "SC": {"fixed_year": "2025", "fixed_month": "1"}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Publisher)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), worker.join()))
        source = b'''<div class="evo_cal_data" data-sc="{&quot;event_type&quot;:&quot;499,&quot;,&quot;fixed_year&quot;:&quot;2026&quot;,&quot;fixed_month&quot;:&quot;9&quot;}"></div>
        <script>var evo_general_params = {"n":"public-nonce-value"};</script>'''
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=1),
                lease_path=Path(directory) / "lease.sqlite3")
            out = Path(directory) / "capture"
            receipts = capture_months(source, f"http://127.0.0.1:{server.server_port}/?evo-ajax=eventon_get_events",
                2025, [1], out, client)
            receipt = json.loads((out / "2025-01.receipt.json").read_text())
            raw = (out / "2025-01.json").read_bytes()
        self.assertEqual(1, len(receipts))
        self.assertEqual("GOOD", json.loads(raw)["status"])
        self.assertEqual(2025, receipt["selected_year"])
        self.assertEqual(1, receipt["selected_month"])
        self.assertEqual(len(raw), receipt["length"])
        self.assertEqual(sha256(raw).hexdigest(), receipt["sha256"])
        self.assertEqual([], receipt["redirects"])
        self.assertNotIn("public-nonce-value", json.dumps(receipt))
        path, form = requests[0]
        self.assertEqual("/?evo-ajax=eventon_get_events", path)
        self.assertEqual(["public-nonce-value"], form["nonce"])
        self.assertEqual(["499,"], form["shortcode[event_type]"])
        self.assertEqual(["2025"], form["shortcode[fixed_year]"])
        self.assertEqual(["1"], form["shortcode[fixed_month]"])
        self.assertEqual(["jumper"], form["ajaxtype"])
        self.assertEqual(["none"], form["direction"])
        start = str(int(datetime(2025, 1, 1, tzinfo=ZoneInfo("Europe/Rome")).timestamp()))
        self.assertEqual([start], form["shortcode[focus_start_date_range]"])
        self.assertEqual({"direction", "ajaxtype", "nonce", "shortcode[event_type]",
                          "shortcode[fixed_year]", "shortcode[fixed_month]",
                          "shortcode[fixed_day]", "shortcode[focus_start_date_range]",
                          "shortcode[focus_end_date_range]"}, set(form))

    def test_wrong_month_response_is_rejected_before_evidence_write(self):
        class Publisher(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                body = json.dumps({"status": "GOOD", "json": [], "html": "Nessun Evento",
                    "cal_month_title": "<span>febbraio</span> <span>2025</span>",
                    "SC": {"fixed_year": "2025", "fixed_month": "2"}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Publisher)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), worker.join()))
        source = b'''<div class="evo_cal_data" data-sc="{&quot;event_type&quot;:&quot;499,&quot;}"></div>
        <script>var evo_general_params = {"n":"public-nonce-value"};</script>'''
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=1),
                lease_path=Path(directory) / "lease.sqlite3")
            out = Path(directory) / "capture"
            with self.assertRaisesRegex(CalendarError, "month title differs"):
                capture_months(source, f"http://127.0.0.1:{server.server_port}/?evo-ajax=eventon_get_events",
                    2025, [1], out, client)
            self.assertEqual([], list(out.iterdir()))

    def test_html_error_response_is_rejected_even_if_it_contains_json(self):
        class Publisher(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                body = json.dumps({"status": "GOOD", "json": [], "html": "Nessun Evento",
                    "cal_month_title": "gennaio 2025",
                    "SC": {"fixed_year": "2025", "fixed_month": "1"}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Publisher)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), worker.join()))
        source = b'''<div class="evo_cal_data" data-sc="{&quot;event_type&quot;:&quot;499,&quot;}"></div>
        <script>var evo_general_params = {"n":"public-nonce-value"};</script>'''
        with tempfile.TemporaryDirectory() as directory:
            client = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=1),
                lease_path=Path(directory) / "lease.sqlite3")
            out = Path(directory) / "capture"
            with self.assertRaisesRegex(CalendarError, "content type"):
                capture_months(source, f"http://127.0.0.1:{server.server_port}/?evo-ajax=eventon_get_events",
                    2025, [1], out, client)
            self.assertEqual([], list(out.iterdir()))


if __name__ == "__main__":
    unittest.main()
