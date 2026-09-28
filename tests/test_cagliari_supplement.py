"""Cagliari packet CLI keeps printed evidence private and unimported."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "cagliari_supplement.py"


class CagliariSupplementTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.pdf = root / "source.pdf"
        self.pdf.write_bytes(b"Cagliari fixture")
        self.digest = hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.url = "https://example.org/Classifica_2%C2%B0_Trofeo.pdf"
        self.gap = root / "gap.json"
        self.gap.write_text(json.dumps({"source_gaps": [{"verified_sha256": self.digest,
            "bytes": self.pdf.stat().st_size, "source": {"id": "sha256:" + self.digest,
            "original_sha256": self.digest, "final_url": self.url},
            "assessment": {"disposition": "unsupported_image_results"}}]}))
        self.card = root / "cards.json"
        self.card.write_text(json.dumps({"cards": [{"event_id": event_id,
            "title": "Cagliari", "date": "2026-01-18",
            "result_urls": ["https://example.org/Classifica_2°_Trofeo.pdf"]}
            for event_id in (9372, 9506)]}))
        self.parts = [root / f"part-{n}.json" for n in range(3)]
        pages = []
        for n in range(1, 30):
            kind = "club" if n >= 28 else "other" if n == 19 else "result"
            columns = ["Nome", "Punteggio"] if kind == "club" else ["Posizione", "Cognome", "Nome"]
            row = {"row_ordinal": 1, "kind": "aggregate" if kind == "club" else "athlete",
                "bbox": [10, 20, 200, 70], "fields_raw": {key: "raw" for key in columns},
                "status_raw": None, "penalty_raw": None, "notes_raw": None, "uncertainties": []}
            pages.append({"page": n, "heading_raw": None if kind == "other" else "Classifica" if n < 21 else ["Classifica"],
                "event_date_raw": "Serrenti - 18 gennaio 2026" if n >= 28 else None,
                "category_raw": None if kind == "club" else "EM", "discipline_raw": None if kind == "club" else "DYN",
                "columns_raw": columns, "page_kind": kind, "row_count": 0 if kind == "other" else 1,
                "rows": [] if kind == "other" else [row]})
        for index, subset in enumerate((pages[:10], pages[10:20], pages[20:])):
            self.parts[index].write_text(json.dumps({
                "source_pdf_sha256" if index != 1 else "source_sha256": self.digest,
                "render_dpi": 220,
                "render_size_px" if index != 1 else "render_dimensions_px": [2572, 1819],
                "pages": subset}))
        self.output = root / "packet.json"

    def run_cli(self):
        cmd = [sys.executable, str(SCRIPT), "--pdf", str(self.pdf), "--gap", str(self.gap)]
        for part in self.parts:
            cmd += ["--part", str(part)]
        cmd += ["--card-ledger", str(self.card), "--output", str(self.output),
                "--expected-sha256", self.digest]
        return subprocess.run(cmd, capture_output=True, text=True)

    def test_replays_two_calendar_candidates_and_cited_rows_without_import(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        first = self.output.read_bytes()
        packet = json.loads(first)
        self.assertEqual([x["event_id"] for x in packet["source_relationship_candidates"]], [9372, 9506])
        self.assertEqual(packet["counts"]["athlete_row_appearances"], 26)
        self.assertEqual(packet["counts"]["aggregate_rows_excluded"], 2)
        self.assertEqual(packet["counts"]["nonduplicate_candidate_positions"], 26)
        self.assertEqual(packet["counts"]["imported_observation_versions"], 0)
        self.assertIsNone(packet["counts"]["confirmed_distinct_attempts"])
        self.assertEqual(packet["pages"][0]["rows"][0]["citation"],
                         {"page": 1, "region": {"units": "upright_png_pixels_220dpi", "bbox": [10, 20, 200, 70]}})
        self.assertEqual(packet["event_date_printed_evidence"],
                         [{"page": 28, "raw": "Serrenti - 18 gennaio 2026"},
                          {"page": 29, "raw": "Serrenti - 18 gennaio 2026"}])
        self.assertEqual(self.run_cli().returncode, 0)
        self.assertEqual(self.output.read_bytes(), first)

    def test_rejects_unlinked_calendar_candidate_and_changed_pdf(self):
        card = json.loads(self.card.read_text())
        card["cards"][1]["result_urls"] = ["https://example.org/other.pdf"]
        self.card.write_text(json.dumps(card))
        self.assertIn("link", self.run_cli().stderr)
        self.assertFalse(self.output.exists())
        card["cards"][1]["result_urls"] = ["https://example.org/Classifica_2°_Trofeo.pdf"]
        self.card.write_text(json.dumps(card))
        self.pdf.write_bytes(b"altered")
        self.assertIn("source SHA256", self.run_cli().stderr)

    def test_rejects_missing_page_and_uncited_or_mismatched_row(self):
        part = json.loads(self.parts[2].read_text())
        last = part["pages"].pop()
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("pages 1 through 29", self.run_cli().stderr)
        part["pages"].append(last)
        part["pages"][0]["rows"][0]["bbox"] = [10, 20, 3000, 70]
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("bbox", self.run_cli().stderr)
        part["pages"][0]["rows"][0]["bbox"] = [10, 20, 200, 70]
        del part["pages"][0]["rows"][0]["fields_raw"]["Nome"]
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("printed columns", self.run_cli().stderr)

    def test_rejects_unsupported_duplicate_reference(self):
        part = json.loads(self.parts[2].read_text())
        part["pages"][1]["rows"][0]["duplicate_of"] = {"page": 1, "row": 1}
        part["pages"][1]["rows"][0]["fields_raw"]["Nome"] = "different"
        self.parts[2].write_text(json.dumps(part))
        self.assertIn("duplicate_of", self.run_cli().stderr)


if __name__ == "__main__":
    unittest.main()
