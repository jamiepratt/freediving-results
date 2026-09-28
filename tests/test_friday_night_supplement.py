"""The Friday Night Dive packet preserves every cited source appearance without importing attempts."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "friday_night_supplement.py"


class FridayNightSupplementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.pdf = root / "source.pdf"
        self.pdf.write_bytes(b"fixture Friday Night Dive image PDF")
        self.digest = hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.gap = root / "gap.json"
        source = {"id": f"sha256:{self.digest}", "original_sha256": self.digest,
                  "final_url": "https://example.org/Classifica_2_Friday_Night_Dive.pdf"}
        self.gap.write_text(json.dumps({"source_gaps": [{"verified_sha256": self.digest,
            "bytes": self.pdf.stat().st_size, "source": source,
            "assessment": {"disposition": "unsupported_image_results"}}]}))
        self.card = root / "card.json"
        self.card.write_text(json.dumps({"cards": [{"event_id": 11005,
            "title": "Qualificazioni Apnea: Friday Night Dive", "date": "2026-05-29",
            "result_urls": ["https://example.org/Classifica_2_Friday_Night_Dive.pdf"]}]}))
        self.parts = [root / f"part-{i}.json" for i in range(3)]
        pages = []
        for n in range(1, 22):
            aggregate = n in (1, 2)
            row = self.row(1)
            pages.append({"page": n,
                "heading_raw": "Classifica Società" if aggregate else "Classifica DYN",
                "event_date_raw": "30 maggio 2026" if aggregate else None,
                "category_raw": None if aggregate else "Seniores",
                "discipline_raw": None if aggregate else "DYN",
                "disposition": "club_aggregate" if aggregate else "result_table",
                "columns_raw": ["Posizione", "Cognome", "Nome"],
                "rows": [] if aggregate else [row],
                "aggregate_rows": [row] if aggregate else [],
                "visual_row_count": {"athlete": 0 if aggregate else 1,
                                     "aggregate": 1 if aggregate else 0},
                "render": {"dpi": 220, "pixel_width": 2572, "pixel_height": 1819,
                           "rotation_clockwise_degrees": 270}})
        for part, subset in zip(self.parts, [pages[:7], pages[7:14], pages[14:]]):
            part.write_text(json.dumps({"source_sha256": self.digest,
                "render": {"dpi": 220, "rotation_clockwise_degrees": 270}, "pages": subset}))
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
        self.assertEqual(doc["schema"], "friday-night-visual-evidence/v1")
        self.assertEqual(doc["event_id_calendar"], 11005)
        self.assertEqual(doc["event_date_calendar"], "2026-05-29")
        self.assertEqual(doc["event_date_printed_evidence"], [
            {"page": 1, "raw": "30 maggio 2026"},
            {"page": 2, "raw": "30 maggio 2026"}])
        self.assertEqual(doc["pages"][0]["event_date_printed"], "30 maggio 2026")
        self.assertIsNone(doc["pages"][2]["event_date_printed"])
        self.assertEqual(doc["counts"]["source_positions"], 19)
        self.assertEqual(doc["counts"]["athlete_row_appearances"], 19)
        self.assertEqual(doc["counts"]["nonduplicate_candidate_positions"], 19)
        self.assertEqual(doc["counts"]["aggregate_row_appearances"], 2)
        self.assertEqual(doc["counts"]["duplicate_aggregate_rows"], 0)
        self.assertEqual(doc["counts"]["nonduplicate_aggregate_rows"], 2)
        self.assertEqual(doc["pages"][2]["rows"][0]["citation"]["page"], 3)
        self.assertEqual(doc["counts"]["imported_observation_versions"], 0)
        self.assertIsNone(doc["counts"]["confirmed_distinct_attempts"])
        self.assertEqual(doc["owner_review_status"], "unreviewed")
        self.assertEqual(self.run_cli().returncode, 0)
        self.assertEqual(self.output.read_bytes(), first)

    def test_preserves_page_specific_columns_and_handwritten_correction(self):
        part = json.loads(self.parts[2].read_text())
        page = part["pages"][5]
        page["columns_raw"] = ["Posizione", "Atleta", "Distanza realizzata"]
        page["rows"][0]["fields"] = {
            "Posizione": "1", "Atleta": "Ciriani Riccardo",
            "Distanza realizzata": "187,50 [barrato]; 178,50 [manoscritto]"}
        page["rows"][0]["annotations_raw"] = [
            "187,50 struck through; handwritten 178,50"]
        page["rows"][0]["uncertainties"] = [
            "Whether correction changes realized distance unresolved"]
        self.parts[2].write_text(json.dumps(part))
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        doc = json.loads(self.output.read_text())
        row = doc["pages"][19]["rows"][0]
        self.assertEqual(row["annotations_raw"], [
            "187,50 struck through; handwritten 178,50"])
        self.assertEqual(doc["counts"]["unresolved_positions"], 1)
        self.assertEqual(doc["counts"]["confirmed_distinct_attempts"], None)

    def test_rejects_changed_source_or_gap_identity(self):
        self.pdf.write_bytes(b"changed")
        self.assertIn("source SHA256", self.run_cli().stderr)
        self.assertFalse(self.output.exists())
        self.pdf.write_bytes(b"fixture Friday Night Dive image PDF")
        gap = json.loads(self.gap.read_text())
        gap["source_gaps"][0]["source"]["id"] = "sha256:wrong"
        self.gap.write_text(json.dumps(gap))
        self.assertIn("identity mismatch", self.run_cli().stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_unlinked_calendar_card(self):
        card = json.loads(self.card.read_text())
        card["cards"][0]["result_urls"] = ["https://example.org/other.pdf"]
        self.card.write_text(json.dumps(card))
        self.assertIn("link", self.run_cli().stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_missing_pages_or_invalid_render(self):
        part = json.loads(self.parts[2].read_text())
        last = part["pages"].pop()
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("pages 1 through 21", self.run_cli().stderr)
        part["pages"].append(last)
        part["pages"][0]["render"]["rotation_clockwise_degrees"] = 180
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("render", self.run_cli().stderr)
        part["pages"][0]["render"]["rotation_clockwise_degrees"] = 270
        part["pages"][0]["render"]["pixel_width"] = 2573
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("render dimensions", self.run_cli().stderr)

    def test_rejects_row_count_citation_and_misplaced_aggregate(self):
        part = json.loads(self.parts[0].read_text())
        athlete = part["pages"][2]
        athlete["visual_row_count"]["athlete"] = 2
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("visual row count", self.run_cli().stderr)
        athlete["visual_row_count"]["athlete"] = 1
        athlete["rows"][0]["region"]["bbox"][2] = 2573
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("render dimensions", self.run_cli().stderr)
        athlete["rows"][0]["region"]["bbox"][2] = 200
        athlete["aggregate_rows"] = [self.row(1)]
        athlete["visual_row_count"]["aggregate"] = 1
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("aggregate", self.run_cli().stderr)

    def test_rejects_missing_printed_column_value(self):
        part = json.loads(self.parts[0].read_text())
        del part["pages"][2]["rows"][0]["fields"]["Nome"]
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("printed columns", self.run_cli().stderr)

    def test_rejects_wrong_aggregate_pages(self):
        part = json.loads(self.parts[0].read_text())
        part["pages"][1]["disposition"] = "result_table"
        part["pages"][2]["disposition"] = "club_aggregate"
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("club aggregate pages", self.run_cli().stderr)

    def test_rejects_false_aggregate_duplicate_and_bad_athlete_duplicate(self):
        part = json.loads(self.parts[0].read_text())
        aggregate = part["pages"][1]["aggregate_rows"][0]
        aggregate["duplicate_of"] = {"page": 1, "row": 1}
        aggregate["duplicate_evidence"] = "claimed repeat"
        aggregate["fields"]["Nome"] = "Different"
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("duplicate_of", self.run_cli().stderr)
        del aggregate["duplicate_of"]
        del aggregate["duplicate_evidence"]
        aggregate["fields"]["Nome"] = "Name"
        athlete = part["pages"][3]["rows"][0]
        athlete["duplicate_of"] = {"page": 4, "row": 1}
        athlete["duplicate_evidence"] = "claimed repeat"
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("duplicate_of", self.run_cli().stderr)

    def test_rejects_duplicate_without_evidence(self):
        part = json.loads(self.parts[0].read_text())
        part["pages"][1]["aggregate_rows"][0]["duplicate_of"] = {
            "page": 1, "row": 1}
        self.parts[0].write_text(json.dumps(part))
        self.assertIn("duplicate_evidence", self.run_cli().stderr)


if __name__ == "__main__":
    unittest.main()
