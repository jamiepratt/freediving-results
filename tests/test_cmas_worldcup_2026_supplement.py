"""The CMAS World Cup scan packet reconciles private page transcriptions."""

import csv
import hashlib
import json
import subprocess
import struct
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "cmas_worldcup_2026_supplement.py"
COUNTS = (33, 32, 31, 30)
SIZES = ((2435, 2735), (2590, 2545), (2415, 2625), (2735, 2810))
HEADER = ("page", "row", "last_name", "first_name", "country", "discipline",
          "ap_m", "top_time", "dive_time", "rp_m", "card", "record", "points", "note", "uncertain")


class CmasWorldCupSupplementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pdf = self.root / "source.pdf"
        self.pdf.write_bytes(b"fixture image-only PDF")
        self.digest = hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.gap = self.root / "gap.json"
        source = {"id": f"sha256:{self.digest}", "original_sha256": self.digest,
                  "publisher": "CMAS", "final_url": "https://example.org/world-cup.pdf"}
        self.gap.write_text(json.dumps({"source_gaps": [{"verified_sha256": self.digest,
            "bytes": self.pdf.stat().st_size, "source": source,
            "assessment": {"disposition": "unsupported_image_results"}}]}))
        self.parts = [self.root / "pages12.tsv", self.root / "pages34.tsv"]
        self.renders = []
        self.render_hashes = []
        for page, (width, height) in enumerate(SIZES, 1):
            render = self.root / f"page{page}.png"
            render.write_bytes(b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" +
                               struct.pack(">II", width, height))
            self.renders.append(render)
            self.render_hashes.append(hashlib.sha256(render.read_bytes()).hexdigest())
        all_rows = []
        for page, count in enumerate(COUNTS, 1):
            for row in range(1, count + 1):
                all_rows.append({"page": str(page), "row": str(row),
                    "last_name": f"Surname{page}-{row}", "first_name": "Athlete",
                    "country": "Country", "discipline": "CNF", "ap_m": "63",
                    "top_time": "8:30", "dive_time": "2:40", "rp_m": "63",
                    "card": "", "record": "", "points": "63", "note": "", "uncertain": ""})
        all_rows[0]["card"] = "DSQ SP assist"
        all_rows[0]["points"] = "0"
        all_rows[1]["rp_m"] = "DNS"
        all_rows[1]["note"] = "RP printed DNS"
        all_rows[2]["uncertain"] = "surname clipped"
        self.write_part(self.parts[0], all_rows[:65])
        self.write_part(self.parts[1], all_rows[65:])
        self.output = self.root / "packet.json"

    def write_part(self, path, rows):
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=HEADER, delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)

    def run_cli(self):
        args = [sys.executable, str(SCRIPT), "--pdf", str(self.pdf),
            "--gap", str(self.gap), "--part", str(self.parts[0]),
            "--part", str(self.parts[1]), "--output", str(self.output),
            "--expected-sha256", self.digest]
        for render, digest in zip(self.renders, self.render_hashes):
            args += ["--render", str(render), "--expected-render-sha256",
                     digest]
        return subprocess.run(args, text=True, capture_output=True)

    def test_reconciles_all_four_cited_tables_without_import_or_attempt_inference(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        first_bytes = self.output.read_bytes()
        packet = json.loads(first_bytes)
        self.assertEqual(packet["counts"], {"source_objects": 1,
            "source_positions": 126, "visual_evidence_rows": 126,
            "imported_observation_versions": 0, "unresolved_positions": 1,
            "confirmed_distinct_attempts": None})
        self.assertEqual([len(page["rows"]) for page in packet["pages"]], list(COUNTS))
        self.assertEqual(packet["pages"][0]["event_date_printed"], "26/05/2026")
        self.assertEqual(packet["pages"][0]["event_date"], "2026-05-26")
        first = packet["pages"][0]["rows"][0]
        self.assertEqual(first["fields"]["Card"], "DSQ SP assist")
        self.assertEqual(first["fields"]["Points"], "0")
        self.assertIsNone(first["fields"]["Record"])
        self.assertEqual(first["citation"]["page"], 1)
        self.assertEqual(first["citation"]["region"]["units"], "rendered_px_180dpi")
        self.assertEqual(packet["pages"][0]["render"]["sha256"],
                         hashlib.sha256(self.renders[0].read_bytes()).hexdigest())
        self.assertEqual(packet["pages"][0]["rows"][1]["transcription_notes"], ["RP printed DNS"])
        self.assertEqual(packet["pages"][0]["rows"][1]["uncertainties"], [])
        self.assertEqual(packet["pages"][0]["rows"][2]["uncertainties"], ["surname clipped"])
        self.assertEqual(packet["source_relationship_status"], "unresolved")
        self.assertEqual(packet["gap_reconciliation"]["owner_review_status"], "unreviewed")
        self.assertEqual(self.run_cli().returncode, 0)
        self.assertEqual(self.output.read_bytes(), first_bytes)

    def test_rejects_changed_pdf_or_missing_and_duplicate_rows(self):
        self.pdf.write_bytes(b"changed")
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("source SHA256", result.stderr)
        self.pdf.write_bytes(b"fixture image-only PDF")
        with self.parts[1].open(newline="", encoding="utf-8") as stream:
            rows = list(csv.DictReader(stream, delimiter="\t"))
        self.write_part(self.parts[1], rows[:-1])
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("expected 126", result.stderr)
        rows[-1]["row"] = rows[-2]["row"]
        self.write_part(self.parts[1], rows)
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("row ordinals", result.stderr)

    def test_rejects_incomplete_columns(self):
        with self.parts[0].open(newline="", encoding="utf-8") as stream:
            rows = list(csv.DictReader(stream, delimiter="\t"))
        for row in rows:
            del row["rp_m"]
        with self.parts[0].open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=[name for name in HEADER if name != "rp_m"], delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("TSV columns", result.stderr)

    def test_rejects_tampered_render(self):
        self.renders[2].write_bytes(self.renders[2].read_bytes() + b"tampered")
        result = self.run_cli()
        self.assertEqual(result.returncode, 2)
        self.assertIn("render SHA256", result.stderr)


if __name__ == "__main__":
    unittest.main()
