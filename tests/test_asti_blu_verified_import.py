"""Synthetic public-CLI checks for the Asti Blu image import."""
import hashlib
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "asti_blu_verified_import.py"


def sha(value):
    return hashlib.sha256(value).hexdigest()


def put(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")
    return sha(path.read_bytes())


class AstiBluVerifiedImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pdf = self.root / "original.pdf"
        self.pdf.write_bytes(b"synthetic Asti Blu PDF")
        self.source = sha(self.pdf.read_bytes())
        png = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 100, 100) + b"\x08\x02\x00\x00\x00"
        self.renders = []
        for number in range(1, 20):
            render = self.root / f"page-{number:02}.png"
            render.write_bytes(png)
            self.renders.append(render)

        def row(page, kind, number, fields, duplicate=None):
            result = {"id": f"{kind}-position:" + sha(f"{self.source}:page:{page}:{kind}:row:{number}".encode()),
                      "row": number, "citation": {"page": page, "region": {
                          "units": "upright_png_pixels_220dpi", "bbox": [1, 1, 80, 20]}},
                      "fields": fields, "status_raw": None, "penalty_raw": None,
                      "notes_raw": None, "uncertainties": []}
            if duplicate:
                result.update({"duplicate_of": duplicate, "duplicate_evidence": "same printed club row"})
            return result

        self.first = {"schema": "asti-blu-visual-evidence/v1", "source_sha256": self.source,
                      "source_bytes": self.pdf.stat().st_size,
                      "attestation": {"method": "original_page_images", "transcriber": "first",
                                      "blind_to_other_pass": True,
                                      "blind_to_derivative_snapshot": True},
                      "pages": [], "counts": {"source_positions": 1,
                                              "aggregate_rows_excluded": 2,
                                              "duplicate_rendered_rows": 0,
                                              "nonduplicate_candidate_positions": 1}}
        for number in range(1, 20):
            club = [row(number, "aggregate", 1, {"Società": "Club", "Punti": "10"},
                        {"page": 1, "row": 1} if number == 19 else None)] if number in (1, 19) else []
            athlete = [row(2, "source", 1, {"Posizione": "1", "Cognome": "Test",
                                               "AP": "50", "RP": "49", "Tempo": "1:00",
                                               "Tessera": "123", "Penalità": "0"})] if number == 2 else []
            self.first["pages"].append({"page": number, "render": {"pixel_width": 100,
                "pixel_height": 100}, "visual_row_count": {"athlete": len(athlete),
                "aggregate": len(club)}, "rows": athlete, "aggregate_rows": club})
        self.second = json.loads(json.dumps(self.first))
        self.second["attestation"]["transcriber"] = "second"
        self.receipt = {"source_sha256": self.source, "bytes": self.pdf.stat().st_size}
        self.inspections = {"schema": "asti-blu-image-inspections/v1",
                            "source_sha256": self.source, "records": [{"page": 2,
                            "role": "source", "row": 1, "reason": "agreement_sample",
                            "render_sha256": sha(png), "bbox": [1, 1, 80, 20],
                            "inspector": "third", "fields_raw": athlete[0]["fields"] if athlete else
                            self.first["pages"][1]["rows"][0]["fields"],
                            "uncertain_fields": []}]}

    def run_cli(self, *extra):
        first = self.root / "first.json"; second = self.root / "second.json"
        receipt = self.root / "receipt.json"; inspected = self.root / "inspections.json"
        a, b, c, d = (put(first, self.first), put(second, self.second),
                      put(receipt, self.receipt), put(inspected, self.inspections))
        command = [sys.executable, str(SCRIPT), "--pdf", str(self.pdf),
                   "--expected-source-sha256", self.source, "--packet", str(first),
                   "--packet-sha256", a, "--second-pass", str(second),
                   "--second-pass-sha256", b, "--receipt", str(receipt),
                   "--receipt-sha256", c, "--inspections", str(inspected),
                   "--inspections-sha256", d, "--parser-version", "synthetic-v1",
                   "--output", str(self.root / "stage.json")]
        for render in self.renders:
            command.extend(("--render", str(render)))
        return subprocess.run(command + list(extra), capture_output=True, text=True)

    def test_two_passes_stage_only_athlete_and_preserve_club_repeat(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(stage["counts"]["cited_positions"], 3)
        self.assertEqual(stage["counts"]["verified_staged_versions"], 1)
        self.assertEqual(stage["counts"]["aggregate_rows_excluded"], 2)
        self.assertEqual(stage["non_primary_positions"][1]["duplicate_of"],
                         {"page": 1, "row": 1})
        self.assertEqual(stage["observation_versions"][0]["raw_fields"]["AP"], "50")
        store = self.root / "isolated.sqlite"
        cmd = [sys.executable, str(SCRIPT), "--import-stage", str(self.root / "stage.json"),
               "--stage-sha256", sha((self.root / "stage.json").read_bytes()),
               "--sqlite-store", str(store)]
        self.assertEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
        self.assertEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute("select count(*) from observation_versions").fetchone()[0], 1)

    def test_undefined_penalty_remains_unresolved(self):
        for document in (self.first, self.second):
            row = document["pages"][1]["rows"][0]
            row["fields"]["Penalità"] = "BO"
            row["penalty_raw"] = "BO"
            row["uncertainties"] = ["BO meaning undefined in printed legend"]
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(stage["counts"]["verified_staged_versions"], 0)
        self.assertEqual(stage["counts"]["unresolved_positions"], 1)
        self.assertEqual(stage["unresolved_positions"][0]["raw_fields"]["Penalità"], "BO")
        self.assertEqual(stage["counts"]["confirmed_distinct_attempts"], None)

    def test_changed_club_repeat_and_missing_citation_are_rejected(self):
        self.first["pages"][18]["aggregate_rows"][0]["fields"]["Punti"] = "11"
        self.assertEqual(self.run_cli().returncode, 2)
        self.first["pages"][18]["aggregate_rows"][0]["fields"]["Punti"] = "10"
        self.first["pages"][1]["rows"][0]["citation"]["region"]["bbox"] = [1, 1, 120, 20]
        self.assertEqual(self.run_cli().returncode, 2)

    def test_historical_packet_is_pinned_and_retained(self):
        historical = json.loads(json.dumps(self.first))
        historical.pop("attestation")
        historical["pages"][1]["rows"][0]["fields"]["Tessera"] = "123"
        path = self.root / "historical.json"
        digest = put(path, historical)
        result = self.run_cli("--historical-packet", str(path),
                              "--historical-packet-sha256", digest)
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(stage["historical_packet_sha256"], digest)
        self.assertEqual(stage["observation_versions"][0]["historical_fields_raw"]["Tessera"], "123")

    def test_exact_historical_packet_can_be_first_pass(self):
        self.first.pop("attestation")
        self.first["pages"][1]["rows"][0]["uncertainties"] = [
            "printed code semantics undefined"]
        first_path = self.root / "first.json"
        digest = put(first_path, self.first)
        result = self.run_cli("--historical-packet", str(first_path),
                              "--historical-packet-sha256", digest)
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(stage["first_pass_attestation"]["method"], "retained_visual_packet")
        self.assertEqual(stage["historical_packet_sha256"], digest)
        self.assertEqual(stage["counts"]["verified_staged_versions"], 0)
        self.assertEqual(stage["counts"]["unresolved_positions"], 1)

    def test_second_source_image_addendum_binds_sealed_pass(self):
        sealed = self.root / "second-sealed.json"
        sealed_sha = put(sealed, self.second)
        self.second["schema"] = "asti-blu-comparable-pass/v1"
        self.second["basis_sha256"] = sealed_sha
        addendum = self.root / "second-addendum.json"
        record = {"schema": "source-image-audit-addendum-v1",
                  "basis_sha256": sealed_sha, "original_source_sha256": self.source,
                  "declared_distance": {"count": 72}, "score_dashes": {"count": 23},
                  "club_repeat": {"positions": 14}, "sealed_pass_unchanged": True}
        addendum_sha = put(addendum, record)
        options = ("--second-sealed", str(sealed), "--second-sealed-sha256", sealed_sha,
                   "--second-addendum", str(addendum),
                   "--second-addendum-sha256", addendum_sha)
        result = self.run_cli(*options)
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(stage["input_sha256"]["second_addendum"], addendum_sha)
        compare_path = self.root / "compare.json"
        result = self.run_cli(*options, "--compare-only", "--output", str(compare_path))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("second_addendum_sha256", json.loads(compare_path.read_text()))
        record["basis_sha256"] = "0" * 64
        put(addendum, record)
        self.assertEqual(self.run_cli(*options[:-1], sha(addendum.read_bytes()),
                                      "--output", str(self.root / "rejected.json")).returncode, 2)
        self.assertEqual(self.run_cli(*options[:-1], "0" * 64,
                                      "--output", str(self.root / "rejected.json")).returncode, 2)


if __name__ == "__main__":
    unittest.main()
