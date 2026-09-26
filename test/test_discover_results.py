"""Public discovery CLI against a synthetic publisher."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_acquire_source import Publisher


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "test" / "fixtures"


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.private = Path(self.temp.name)

        def respond(path):
            if path == "/html":
                return 200, {"Content-Type": "text/html"}, (FIXTURES / "discovery-index.html").read_bytes()
            if path == "/json":
                return 200, {"Content-Type": "application/json"}, (FIXTURES / "discovery-index.json").read_bytes()
            if path.endswith(".pdf"):
                return 200, {"Content-Type": "application/pdf"}, (FIXTURES / "discovery-result.pdf").read_bytes()
            if path.endswith(".json"):
                return 200, {"Content-Type": "application/json"}, b'{"results":[1]}'
            return 404, {}, b"missing"

        self.publisher = Publisher(respond)
        self.addCleanup(self.publisher.close)
        base = self.publisher.url.removesuffix("/source")
        self.config = self.private / "config.json"
        self.config.write_text(json.dumps({
            "schema": "result-discovery-config/v1",
            "cutoff": "2025-01-01",
            "as_of": "2026-09-26",
            "sources": [
                {"publisher": "Synthetic federation", "index_url": base + "/html", "index_representation": "html", "candidate_representation": "pdf", "allowed_host": "127.0.0.1", "context": {"filter": "final"}},
                {"publisher": "Synthetic federation", "index_url": base + "/json", "index_representation": "json", "candidate_representation": "json", "allowed_host": "127.0.0.1", "context": {"filter": "final"}},
            ],
        }))

    def run_discovery(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts" / "discover_results.py"),
                                 str(self.config), str(self.private / "output")],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads((self.private / "output" / "inventory.json").read_text())

    def test_dated_sources_have_exact_receipts_and_resume_without_requests(self):
        inventory = self.run_discovery()
        self.assertEqual("result-discovery-inventory/v1", inventory["schema"])
        self.assertEqual("2025-01-01", inventory["cutoff"])
        candidates = inventory["candidates"]
        self.assertEqual(3, len(candidates))
        self.assertEqual({"2025-06-12", "2026-07-08"},
                         {item["selected_date"] for item in candidates if item["representation"] == "pdf"})
        for item in candidates:
            self.assertEqual("complete", item["status"])
            self.assertEqual(64, len(item["sha256"]))
            provenance = json.loads((self.private / "output" / "archive" / item["provenance"]).read_text())
            self.assertEqual(item["url"], provenance["requested_url"])
            self.assertEqual(item["selected_date"], provenance["context"]["selected_date"])
            self.assertEqual(item["sha256"], provenance["sha256"])
            self.assertEqual([item["url"]], provenance["redirect_chain"])
        requests = list(self.publisher.requests)
        self.assertEqual(5, len(requests))
        self.assertNotIn("/events/2024.pdf", requests)
        self.assertNotIn("/events/2024.json", requests)
        self.assertEqual(inventory, self.run_discovery())
        self.assertEqual(requests, self.publisher.requests)

    def test_failed_index_is_checkpointed_without_a_repeat_attempt(self):
        config = json.loads(self.config.read_text())
        config["sources"] = [{**config["sources"][0],
                              "index_url": self.publisher.url.removesuffix("/source") + "/missing"}]
        self.config.write_text(json.dumps(config))
        first = self.run_discovery()
        self.assertEqual("gap", first["indexes"][0]["status"])
        self.assertEqual("permanent_status", first["indexes"][0]["reason"])
        requests = list(self.publisher.requests)
        self.assertEqual(first, self.run_discovery())
        self.assertEqual(requests, self.publisher.requests)


if __name__ == "__main__":
    unittest.main()
