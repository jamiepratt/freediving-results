"""The Asti Blu packet preserves every cited source appearance without importing attempts."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "asti_blu_supplement.py"


class AstiBluSupplementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.pdf = root / "source.pdf"
        self.pdf.write_bytes(b"fixture Asti Blu image PDF")
        self.digest = hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.gap = root / "gap.json"
        source = {"id": f"sha256:{self.digest}", "original_sha256": self.digest,
                  "final_url": "https://example.org/9%C2%B0_Trofeo_Asti_Blu.pdf"}
        self.gap.write_text(json.dumps({"source_gaps": [{"verified_sha256": self.digest,
            "bytes": self.pdf.stat().st_size, "source": source,
            "assessment": {"disposition": "unsupported_image_results"}}]}))
        self.card = root / "card.json"
        self.card.write_text(json.dumps({"cards": [{"event_id": 9491,
            "title": "9° Trofeo Asti Blu", "date": "2026-04-19",
            "result_urls": ["https://example.org/9°_Trofeo_Asti_Blu.pdf"]}]}))
        self.parts = [root / f"part-{i}.json" for i in range(3)]
        pages = []
        for n in range(1, 20):
            aggregate = n in (1, 19)
            row = self.row(1)
            if n == 19:
                row["duplicate_of"] = {"page": 1, "row": 1}
                row["duplicate_evidence"] = "Repeated club ranking page"
            pages.append({"page": n,
                "heading_raw": "Classifica società" if aggregate else "Classifica 1CM - DYN",
                "event_date_raw": "Asti - 19 aprile 2026" if aggregate else None,
                "category_raw": None if aggregate else "1CM",
                "discipline_raw": None if aggregate else "DYN",
                "disposition": "club_aggregate" if aggregate else "result_table",
                "columns_raw": ["Posizione", "Cognome", "Nome"],
                "rows": [] if aggregate else [row],
                "aggregate_rows": [row] if aggregate else [],
                "visual_row_count": {"athlete": 0 if aggregate else 1,
                                     "aggregate": 1 if aggregate else 0},
                "render": {"dpi": 220, "pixel_width": 2573, "pixel_height": 1820,
                           "rotation_clockwise_degrees": 0}})
        for part, subset in zip(self.parts, [pages[:6], pages[6:12], pages[12:]]):
            part.write_text(json.dumps({"source_sha256": self.digest,
                "render": {"dpi": 220, "rotation_clockwise_degrees": 0}, "pages": subset}))
        self.output = root / "packet.json"

    @staticmethod
    def row(n):
        return {"row": n, "region": {"units": "upright_png_pixels_220dpi",
                "bbox": [10, 20, 200, 70]},
                "fields": {"Posizione": str(n), "Cognome": "Raw", "Nome": "Name"},
                "status_raw": None, "penalty_raw": None, "notes_raw": None,
                "uncertainties": []}

    def run_cli(self):
        command = [sys.executable, str(SCRIPT), "--pdf", str(self.pdf),
            "--gap", str(self.gap)]
        for part in self.parts:
            command += ["--part", str(part)]
        command += ["--card-ledger", str(self.card), "--output", str(self.output),
                    "--expected-sha256", self.digest]
        return subprocess.run(command, capture_output=True, text=True)

    def test_replays_positions_and_duplicate_club_ranking_deterministically(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        first = self.output.read_bytes()
        doc = json.loads(first)
        self.assertEqual(doc["schema"], "asti-blu-visual-evidence/v1")
        self.assertEqual(doc["event_id_calendar"], 9491)
        self.assertEqual(doc["event_date_calendar"], "2026-04-19")
        self.assertEqual(doc["event_date_printed_evidence"], [
            {"page": 1, "raw": "Asti - 19 aprile 2026"},
            {"page": 19, "raw": "Asti - 19 aprile 2026"}])
        self.assertEqual(doc["pages"][0]["event_date_printed"], "Asti - 19 aprile 2026")
        self.assertIsNone(doc["pages"][1]["event_date_printed"])
        self.assertEqual(doc["counts"]["source_positions"], 17)
        self.assertEqual(doc["counts"]["athlete_row_appearances"], 17)
        self.assertEqual(doc["counts"]["nonduplicate_candidate_positions"], 17)
        self.assertEqual(doc["counts"]["aggregate_row_appearances"], 2)
        self.assertEqual(doc["counts"]["duplicate_aggregate_rows"], 1)
        self.assertEqual(doc["counts"]["nonduplicate_aggregate_rows"], 1)
        self.assertEqual(doc["pages"][18]["aggregate_rows"][0]["duplicate_of"],
                         {"page": 1, "row": 1})
        self.assertEqual(doc["pages"][1]["rows"][0]["citation"]["page"], 2)
        self.assertEqual(doc["counts"]["imported_observation_versions"], 0)
        self.assertIsNone(doc["counts"]["confirmed_distinct_attempts"])
        self.assertEqual(doc["owner_review_status"], "unreviewed")
        self.assertEqual(self.run_cli().returncode, 0)
        self.assertEqual(self.output.read_bytes(), first)

    def test_rejects_changed_source_or_unlinked_card(self):
        self.pdf.write_bytes(b"changed")
        self.assertIn("source SHA256", self.run_cli().stderr)
        self.assertFalse(self.output.exists())
        self.pdf.write_bytes(b"fixture Asti Blu image PDF")
        card = json.loads(self.card.read_text())
        card["cards"][0]["result_urls"] = ["https://example.org/other.pdf"]
        self.card.write_text(json.dumps(card))
        self.assertIn("link", self.run_cli().stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_missing_pages_or_invalid_render(self):
        part = json.loads(self.parts[2].read_text())
        last = part["pages"].pop()
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("pages 1 through 19", self.run_cli().stderr)
        part["pages"].append(last)
        part["pages"][0]["render"]["rotation_clockwise_degrees"] = 270
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("render", self.run_cli().stderr)
        part["pages"][0]["render"]["rotation_clockwise_degrees"] = 0
        part["pages"][0]["render"]["pixel_width"] = 2574
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("render dimensions", self.run_cli().stderr)

    def test_rejects_row_count_citation_and_misplaced_aggregate(self):
        part = json.loads(self.parts[0].read_text())
        part["pages"][1]["visual_row_count"]["athlete"] = 2
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("visual row count", self.run_cli().stderr)
        part["pages"][1]["visual_row_count"]["athlete"] = 1
        part["pages"][1]["rows"][0]["region"]["bbox"][2] = 2574
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("render dimensions", self.run_cli().stderr)
        part["pages"][1]["rows"][0]["region"]["bbox"][2] = 200
        part["pages"][1]["aggregate_rows"] = [self.row(1)]
        part["pages"][1]["visual_row_count"]["aggregate"] = 1
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("aggregate", self.run_cli().stderr)

    def test_rejects_missing_printed_column_value(self):
        part = json.loads(self.parts[0].read_text())
        del part["pages"][1]["rows"][0]["fields"]["Nome"]
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("printed columns", self.run_cli().stderr)

    def test_rejects_false_aggregate_duplicate_and_bad_athlete_duplicate(self):
        part = json.loads(self.parts[2].read_text())
        part["pages"][-1]["aggregate_rows"][0]["fields"]["Nome"] = "Different"
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("duplicate_of", self.run_cli().stderr)
        part["pages"][-1]["aggregate_rows"][0]["fields"]["Nome"] = "Name"
        self.parts[2].write_text(json.dumps(part))
        first = json.loads(self.parts[0].read_text())
        first["pages"][2]["rows"][0]["duplicate_of"] = {"page": 3, "row": 1}
        self.parts[0].write_text(json.dumps(first))
        self.assertIn("duplicate_of", self.run_cli().stderr)

    def test_requires_explicit_page_19_repeat_reference(self):
        part = json.loads(self.parts[2].read_text())
        del part["pages"][-1]["aggregate_rows"][0]["duplicate_of"]
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("page 19 aggregate duplicate_of", self.run_cli().stderr)


if __name__ == "__main__":
    unittest.main()
