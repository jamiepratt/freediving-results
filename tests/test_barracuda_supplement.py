"""The private Barracuda packet replays cited positions without importing results."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "barracuda_supplement.py"
COUNTS = (3, 1, 1, 2)


class BarracudaSupplementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.pdf = root / "source.pdf"
        self.pdf.write_bytes(b"fixture Barracuda image PDF")
        self.digest = hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.output = root / "supplement.json"
        self.pages = root / "pages.json"
        self.gap = root / "gap.json"
        self.card = root / "card.json"
        source = {"id": f"sha256:{self.digest}", "original_sha256": self.digest,
                  "final_url": "https://example.org/Barracuda.pdf", "publisher": "FIPSAS"}
        self.gap.write_text(json.dumps({"source_gaps": [{"verified_sha256": self.digest,
            "bytes": len(self.pdf.read_bytes()), "source": source,
            "assessment": {"disposition": "unsupported_image_results"}}]}))
        self.card.write_text(json.dumps({"cards": [{"event_id": 10947,
            "title": "Gli Amici del Barracuda", "date": "2026-05-08",
            "result_urls": [source["final_url"]]}]}))
        pages = []
        for number, count in enumerate(COUNTS, 1):
            rows = [self.row(row) for row in range(1, count + 1)]
            pages.append({"page": number, "heading_raw": "Classifica STA",
                          "event_date_raw": None, "disposition": "result_table", "rows": rows})
        pages.append({"page": 5, "heading_raw": "Classifica societa",
                      "event_date_raw": "Pistoia - 8 maggio 2026",
                      "disposition": "society_aggregate", "rows": [],
                      "aggregate_rows": [self.row(1)]})
        self.pages.write_text(json.dumps({"source_sha256": self.digest,
            "render": {"dpi": 220, "pixel_width": 2552, "pixel_height": 1815,
                       "rotation_clockwise_degrees": 270}, "pages": pages}))

    def row(self, ordinal):
        return {"row": ordinal, "region": {"units": "upright_png_pixels_220dpi",
            "bbox": [10, 20, 200, 70]},
            "fields": {"POS": str(ordinal), "ATLETA": "Athlete Raw", "RISULTATO": None},
            "status_raw": None, "notes_raw": None, "uncertainties": []}

    def run_cli(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--pdf", str(self.pdf),
            "--gap", str(self.gap), "--pages", str(self.pages), "--card-ledger",
            str(self.card), "--output", str(self.output), "--expected-sha256",
            self.digest], capture_output=True, text=True)

    def test_replays_seven_athlete_positions_and_excludes_society_aggregate(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        first_bytes = self.output.read_bytes()
        doc = json.loads(first_bytes)
        self.assertEqual(doc["schema"], "barracuda-visual-evidence/v1")
        self.assertEqual(doc["counts"]["source_positions"], 7)
        self.assertEqual(doc["counts"]["visual_evidence_rows"], 7)
        self.assertEqual(doc["counts"]["aggregate_rows_excluded"], 1)
        self.assertEqual(doc["counts"]["imported_observation_versions"], 0)
        self.assertIsNone(doc["counts"]["confirmed_distinct_attempts"])
        self.assertEqual(doc["event_date_calendar"], "2026-05-08")
        self.assertIsNone(doc["pages"][0]["event_date_printed"])
        self.assertEqual(doc["pages"][4]["event_date_printed"], "Pistoia - 8 maggio 2026")
        self.assertEqual(doc["pages"][4]["rows"], [])
        self.assertEqual(len(doc["pages"][4]["aggregate_rows"]), 1)
        self.assertEqual(doc["pages"][0]["rows"][0]["fields"]["ATLETA"], "Athlete Raw")
        self.assertEqual(doc["pages"][0]["rows"][0]["review_status"], "unreviewed")
        self.assertEqual(doc["owner_review_status"], "unreviewed")
        self.assertEqual(self.run_cli().returncode, 0)
        self.assertEqual(self.output.read_bytes(), first_bytes)

    def test_rejects_changed_source_and_bad_page_accounting(self):
        self.pdf.write_bytes(b"different")
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("source SHA256", result.stderr)
        self.assertFalse(self.output.exists())
        self.pdf.write_bytes(b"fixture Barracuda image PDF")
        packet = json.loads(self.pages.read_text())
        packet["pages"][0]["rows"].pop()
        self.pages.write_text(json.dumps(packet))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("expected 3 athlete", result.stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_wrong_card_link_or_date(self):
        card = json.loads(self.card.read_text())
        card["cards"][0]["result_urls"] = ["https://example.org/other.pdf"]
        self.card.write_text(json.dumps(card))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("link", result.stderr)
        card["cards"][0]["result_urls"] = ["https://example.org/Barracuda.pdf"]
        card["cards"][0]["date"] = "2026-05-09"
        self.card.write_text(json.dumps(card))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("2026-05-08", result.stderr)

    def test_rejects_bad_citation_and_aggregate_misclassification(self):
        packet = json.loads(self.pages.read_text())
        packet["pages"][0]["rows"][0]["region"]["bbox"][2] = 2553
        self.pages.write_text(json.dumps(packet))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("render dimensions", result.stderr)
        packet["pages"][0]["rows"][0]["region"]["bbox"][2] = 200
        packet["pages"][4]["rows"] = [self.row(1)]
        self.pages.write_text(json.dumps(packet))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("society aggregate", result.stderr)

    def test_page_render_override_controls_citation_bounds(self):
        packet = json.loads(self.pages.read_text())
        packet["pages"][1]["render"] = {"dpi": 220, "pixel_width": 100,
            "pixel_height": 1815, "rotation_clockwise_degrees": 270}
        self.pages.write_text(json.dumps(packet))
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("page 2 row 1: region exceeds render dimensions", result.stderr)
        packet["pages"][1]["render"]["pixel_width"] = 2527
        self.pages.write_text(json.dumps(packet))
        self.assertEqual(self.run_cli().returncode, 0)
        doc = json.loads(self.output.read_text())
        self.assertEqual(doc["pages"][1]["render"]["pixel_width"], 2527)


if __name__ == "__main__":
    unittest.main()
