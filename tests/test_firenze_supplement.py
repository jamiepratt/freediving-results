"""The private Firenze packet replays every cited page without importing attempts."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "firenze_supplement.py"


class FirenzeSupplementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.pdf = root / "source.pdf"
        self.pdf.write_bytes(b"fixture Firenze image PDF")
        self.digest = hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.gap = root / "gap.json"
        source = {"id": f"sha256:{self.digest}", "original_sha256": self.digest,
                  "final_url": "https://example.org/Classifica_2%C2%B0_Trofeo.pdf"}
        self.gap.write_text(json.dumps({"source_gaps": [{"verified_sha256": self.digest,
            "bytes": self.pdf.stat().st_size, "source": source,
            "assessment": {"disposition": "unsupported_image_results"}}]}))
        self.card = root / "card.json"
        self.card.write_text(json.dumps({"cards": [{"event_id": 9677,
            "title": "Qualificazioni Apnea: 2° Trofeo Apnea Firenze", "date": "2026-03-29",
            "result_urls": ["https://example.org/Classifica_2°_Trofeo.pdf"]}]}))
        self.parts = [root / "pages-01-12.json", root / "pages-13-25.json"]
        pages = []
        for n in range(1, 26):
            aggregate = n == 1
            continuation = n == 18
            row = self.row(1)
            if n == 25:
                row["duplicate_of"] = {"page": 24, "row": 1}
            pages.append({"page": n, "heading_raw": "Classifica società" if aggregate else "Classifica DNF",
                "event_date_raw": "Firenze - 29 marzo 2026" if n == 1 else None,
                "category_raw": None if aggregate else "1CM", "discipline_raw": None if aggregate else "DNF",
                "disposition": "club_aggregate" if aggregate else
                               ("continuation" if continuation else "result_table"),
                "columns_raw": ["POS", "ATLETA", "RISULTATO"],
                "notes": ["Printed page checked visually"],
                "rows": [] if aggregate or continuation else [row],
                "aggregate_rows": [self.row(1)] if aggregate else [],
                "visual_row_count": {"athlete": 0 if aggregate or continuation else 1,
                                     "aggregate": 1 if aggregate else 0},
                "render": {"dpi": 220, "pixel_width": 2000, "pixel_height": 3000,
                           "rotation_clockwise_degrees": 270}})
        for part, subset in zip(self.parts, [pages[:12], pages[12:]]):
            part.write_text(json.dumps({"source_sha256": self.digest,
                "render": {"dpi": 220, "rotation_clockwise_degrees": 270}, "pages": subset}))
        self.output = root / "packet.json"

    @staticmethod
    def row(n):
        return {"row": n, "region": {"units": "upright_png_pixels_220dpi",
                "bbox": [10, 20, 200, 70]},
                "fields": {"POS": str(n), "ATLETA": "Raw Name", "RISULTATO": None},
                "status_raw": None, "penalty_raw": None, "notes_raw": None,
                "uncertainties": []}

    def run_cli(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--pdf", str(self.pdf),
            "--gap", str(self.gap), "--part", str(self.parts[0]), "--part",
            str(self.parts[1]), "--card-ledger", str(self.card), "--output",
            str(self.output), "--expected-sha256", self.digest],
            capture_output=True, text=True)

    def test_replays_positions_and_separates_club_aggregate(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        first = self.output.read_bytes()
        doc = json.loads(first)
        self.assertEqual(doc["schema"], "firenze-visual-evidence/v1")
        self.assertEqual(doc["counts"]["source_positions"], 23)
        self.assertEqual(doc["counts"]["visual_evidence_rows"], 23)
        self.assertEqual(doc["counts"]["duplicate_rendered_rows"], 1)
        self.assertEqual(doc["counts"]["nonduplicate_candidate_positions"], 22)
        self.assertEqual(doc["counts"]["aggregate_rows_excluded"], 1)
        self.assertEqual(doc["counts"]["imported_observation_versions"], 0)
        self.assertIsNone(doc["counts"]["confirmed_distinct_attempts"])
        self.assertEqual(doc["event_id_calendar"], 9677)
        self.assertEqual(doc["pages"][0]["event_date_printed"], "Firenze - 29 marzo 2026")
        self.assertIsNone(doc["pages"][1]["event_date_printed"])
        self.assertEqual(doc["pages"][0]["rows"], [])
        self.assertEqual(doc["pages"][1]["rows"][0]["fields"]["ATLETA"], "Raw Name")
        self.assertEqual(doc["pages"][1]["rows"][0]["citation"]["page"], 2)
        self.assertEqual(doc["pages"][1]["notes"], ["Printed page checked visually"])
        self.assertEqual(doc["pages"][24]["rows"][0]["duplicate_of"],
                         {"page": 24, "row": 1})
        self.assertEqual(doc["owner_review_status"], "unreviewed")
        self.assertEqual(self.run_cli().returncode, 0)
        self.assertEqual(self.output.read_bytes(), first)

    def test_rejects_changed_pdf_and_wrong_card(self):
        self.pdf.write_bytes(b"changed")
        self.assertIn("source SHA256", self.run_cli().stderr)
        self.assertFalse(self.output.exists())
        self.pdf.write_bytes(b"fixture Firenze image PDF")
        card = json.loads(self.card.read_text())
        card["cards"][0]["result_urls"] = ["https://example.org/other.pdf"]
        self.card.write_text(json.dumps(card))
        self.assertIn("link", self.run_cli().stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_missing_or_duplicate_page(self):
        part = json.loads(self.parts[1].read_text())
        part["pages"].pop()
        self.parts[1].write_text(json.dumps(part))
        self.assertIn("pages 1 through 25", self.run_cli().stderr)
        part["pages"].append(part["pages"][0])
        self.parts[1].write_text(json.dumps(part))
        self.assertIn("pages 1 through 25", self.run_cli().stderr)

    def test_rejects_row_count_and_citation_errors(self):
        part = json.loads(self.parts[0].read_text())
        part["pages"][1]["visual_row_count"]["athlete"] = 2
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("visual row count", self.run_cli().stderr)
        part["pages"][1]["visual_row_count"]["athlete"] = 1
        part["pages"][1]["rows"][0]["region"]["bbox"][2] = 2001
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("render dimensions", self.run_cli().stderr)
        part["pages"][1]["rows"][0]["region"]["bbox"][2] = 200
        part["pages"][0]["rows"] = [self.row(1)]
        part["pages"][0]["visual_row_count"]["athlete"] = 1
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("club aggregate", self.run_cli().stderr)

    def test_rejects_invalid_duplicate_reference(self):
        part = json.loads(self.parts[1].read_text())
        part["pages"][-1]["rows"][0]["duplicate_of"] = {"page": 25, "row": 1}
        self.parts[1].write_text(json.dumps(part))
        self.assertIn("duplicate_of", self.run_cli().stderr)


if __name__ == "__main__":
    unittest.main()
