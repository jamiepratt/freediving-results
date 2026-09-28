"""The private Komaros supplement can be replayed from cited source pages."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "komaros_supplement.py"
COUNTS = (1, 2, 4, 1, 1, 3, 3, 6, 5, 10, 1, 4, 6)
FIELDS = (
    "position", "surname", "name", "club", "birth_year", "declared_time",
    "declared_distance", "realized_time", "realized_distance", "penalty",
    "homologated_time", "homologated_distance", "time_difference", "points",
)


class KomarosSupplementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pdf = self.root / "source.pdf"
        self.pdf.write_bytes(b"fixture Komaros image PDF")
        self.digest = hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.gap = self.root / "gap.json"
        source = {
            "id": f"sha256:{self.digest}", "original_sha256": self.digest,
            "publisher": "FIPSAS", "final_url": "https://example.org/komaros.pdf",
            "discovery_url": "https://example.org/event", "retrieved_at": "2026-09-27T12:00:00Z",
            "source_status": "no_imported_extraction", "selected_view": None,
        }
        self.gap.write_text(json.dumps({"source_gaps": [{
            "verified_sha256": self.digest, "source": source,
            "assessment": {"disposition": "unsupported_image_results"},
        }]}))
        self.parts = [self.root / "pages-01-07.json", self.root / "pages-08-13.json"]
        self.card_ledger = self.root / "card-ledger.json"
        self.card_ledger.write_text(json.dumps({"cards": [{"event_id": 9678, "date": "2026-04-12",
            "result_urls": [source["final_url"]]}]}))
        pages = []
        for page, count in enumerate(COUNTS, 1):
            rows = []
            for row in range(1, count + 1):
                fields = {key: None for key in FIELDS}
                fields.update(position=str(row), surname=f"Surname{page}-{row}", name="Athlete")
                rows.append({"row": row, "region": {"units": "rendered_px_220dpi",
                    "bbox": [1, 2, 3, 4]},
                             "fields": fields, "uncertainties": []})
            pages.append({"page": page, "heading_raw": "Classifica 1CM - DYN",
                          "event_date_raw": None, "rows": rows})
        pages[0]["rows"][0]["uncertainties"] = ["points illegible"]
        pages[0]["legend_raw"] = "DQ = printed legend"
        pages[3]["heading_raw"] = "Classifica EF (3) - DNF"
        self.parts[0].write_text(json.dumps({"pages": pages[:7]}))
        self.parts[1].write_text(json.dumps({"pages": pages[7:]}))
        self.output = self.root / "supplement.json"

    def run_cli(self):
        return subprocess.run([
            sys.executable, str(SCRIPT), "--pdf", str(self.pdf), "--gap", str(self.gap),
            "--part", str(self.parts[0]), "--part", str(self.parts[1]),
            "--output", str(self.output), "--expected-sha256", self.digest,
            "--card-ledger", str(self.card_ledger),
        ], text=True, capture_output=True)

    def test_replays_cited_rows_without_inventing_attempts(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        first_bytes = self.output.read_bytes()
        doc = json.loads(first_bytes)
        self.assertEqual(doc["source"]["original_sha256"], self.digest)
        self.assertEqual(doc["counts"], {"source_objects": 1, "source_positions": 47,
                                          "visual_evidence_rows": 47,
                                          "imported_observation_versions": 0,
                                          "unresolved_positions": 1, "unresolved_fields": 1,
                                          "confirmed_distinct_attempts": None})
        self.assertEqual(len(doc["pages"]), 13)
        self.assertEqual(doc["event_date_calendar"], "2026-04-12")
        self.assertEqual(doc["event_date_calendar_provenance"]["event_id"], 9678)
        self.assertIsNone(doc["pages"][0]["event_date_printed"])
        self.assertEqual(doc["pages"][0]["category_raw"], "1CM")
        self.assertEqual(doc["pages"][0]["discipline_raw"], "DYN")
        self.assertEqual(doc["pages"][0]["disposition"], "result_table")
        self.assertEqual(doc["pages"][0]["legend_raw"], "DQ = printed legend")
        self.assertEqual(doc["pages"][3]["category_raw"], "EF (3)")
        self.assertEqual(doc["pages"][0]["rows"][0]["citation"],
                         {"page": 1, "region": {"units": "rendered_px_220dpi",
                                               "bbox": [1, 2, 3, 4]}})
        self.assertEqual(doc["pages"][0]["rows"][0]["fields"]["surname"], "Surname1-1")
        self.assertEqual(doc["pages"][0]["rows"][0]["review_status"], "unreviewed")
        self.assertEqual(self.run_cli().returncode, 0)
        self.assertEqual(self.output.read_bytes(), first_bytes)

    def test_rejects_changed_source_and_incomplete_pages(self):
        self.pdf.write_bytes(b"different source")
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("source SHA256", result.stderr)
        self.assertFalse(self.output.exists())
        self.pdf.write_bytes(b"fixture Komaros image PDF")
        self.parts[1].write_text(json.dumps({"pages": []}))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("pages 1 through 13", result.stderr)

    def test_rejects_duplicate_or_missing_position_citation(self):
        part = json.loads(self.parts[0].read_text())
        part["pages"][1]["rows"][1]["row"] = 1
        self.parts[0].write_text(json.dumps(part))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("row ordinals", result.stderr)
        part["pages"][1]["rows"][1]["row"] = 2
        part["pages"][1]["rows"][1]["region"] = {}
        self.parts[0].write_text(json.dumps(part))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("region", result.stderr)


if __name__ == "__main__":
    unittest.main()
