"""Synthetic public CLI checks for the Firenze scan route."""
import hashlib
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/firenze_verified_import.py"


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, sort_keys=True) + "\n")
    return digest(path.read_bytes())


class FirenzeImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pdf = self.root / "source.pdf"
        self.pdf.write_bytes(b"%PDF-synthetic-firenze")
        self.source = digest(self.pdf.read_bytes())
        self.renders = []
        self.pages = []
        for number in range(1, 26):
            path = self.root / f"page-{number:02}.png"
            path.write_bytes(b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR" +
                             struct.pack(">II", 100, 100) + b"\x08\x02\0\0\0")
            self.renders.append(path)
            self.pages.append({"page": number, "render": {"pixel_width": 100,
                "pixel_height": 100}, "rows": [], "aggregate_rows": [],
                "visual_row_count": {"athlete": 0, "aggregate": 0}})
        self.add_row(1, "aggregate", "Club")
        self.add_row(24, "source", "Athlete")
        self.add_row(25, "source", "Athlete", {"page": 24, "row": 1})
        self.first = self.pass_document("first")
        self.second = self.pass_document("second")
        self.receipt = {"source_sha256": self.source, "bytes": self.pdf.stat().st_size}
        self.inspections = {"schema": "firenze-image-inspections/v1",
                            "source_sha256": self.source, "records": []}
        self.first_path = self.root / "first.json"
        self.second_path = self.root / "second.json"
        self.receipt_path = self.root / "receipt.json"
        self.inspections_path = self.root / "inspections.json"
        self.output = self.root / "stage.json"

    def add_row(self, page, kind, name, duplicate=None):
        section = "aggregate_rows" if kind == "aggregate" else "rows"
        rows = self.pages[page - 1][section]
        row = len(rows) + 1
        raw = {"name": name, "score": "10"}
        item = {"id": f"{kind}-position:" + digest(
            f"{self.source}:page:{page}:{kind}:row:{row}".encode()),
            "row": row, "citation": {"page": page, "region": {
                "units": "upright_png_pixels_220dpi", "bbox": [1, row, 90, row + 1]}},
            "fields": raw, "status_raw": None, "penalty_raw": None,
            "notes_raw": None, "uncertainties": []}
        if duplicate:
            item["duplicate_of"] = duplicate
            item["duplicate_evidence"] = "same rendered result row"
        rows.append(item)
        self.pages[page - 1]["visual_row_count"]["aggregate" if kind == "aggregate" else "athlete"] += 1

    def pass_document(self, transcriber):
        return {"schema": "firenze-visual-evidence/v1", "source_sha256": self.source,
                "source_bytes": self.pdf.stat().st_size,
                "attestation": {"transcriber": transcriber, "method": "original_page_images",
                    "blind_to_other_pass": True, "blind_to_derivative_snapshot": True},
                "pages": json.loads(json.dumps(self.pages)), "counts": {
                    "source_positions": 2, "aggregate_rows_excluded": 1,
                    "duplicate_rendered_rows": 1,
                    "nonduplicate_candidate_positions": 1}}

    def call(self, *extra):
        first_hash = save(self.first_path, self.first)
        second_hash = save(self.second_path, self.second)
        receipt_hash = save(self.receipt_path, self.receipt)
        cmd = [sys.executable, str(SCRIPT), "--pdf", str(self.pdf),
            "--expected-source-sha256", self.source, "--packet", str(self.first_path),
            "--packet-sha256", first_hash, "--second-pass", str(self.second_path),
            "--second-pass-sha256", second_hash, "--receipt", str(self.receipt_path),
            "--receipt-sha256", receipt_hash, "--parser-version", "synthetic-v1",
            "--output", str(self.output)]
        for path in self.renders:
            cmd.extend(["--render", str(path)])
        return subprocess.run(cmd + list(extra), text=True, capture_output=True)

    def inspect(self, page, row, role, reason, fields=None):
        source_row = self.first["pages"][page - 1]["aggregate_rows" if role == "aggregate" else "rows"][row - 1]
        self.inspections["records"].append({"page": page, "row": row, "role": role,
            "reason": reason, "render_sha256": digest(self.renders[page - 1].read_bytes()),
            "bbox": source_row["citation"]["region"]["bbox"], "inspector": "third",
            "fields_raw": fields or source_row["fields"], "uncertain_fields": []})

    def test_compare_stage_and_idempotent_import(self):
        result = self.call("--compare-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        comparison = json.loads(self.output.read_text())
        self.assertEqual(comparison["counts"]["cited_positions"], 3)
        self.assertEqual(comparison["counts"]["candidate_result_positions"], 1)
        self.assertEqual(comparison["counts"]["duplicate_rendered_rows"], 1)
        self.assertEqual(comparison["counts"]["aggregate_rows_excluded"], 1)
        self.output.unlink()
        self.inspect(24, 1, "source", "agreement_sample")
        save(self.inspections_path, self.inspections)
        result = self.call("--inspections", str(self.inspections_path),
                           "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads(self.output.read_text())
        self.assertEqual(len(stage["observation_versions"]), 1)
        self.assertEqual(len(stage["non_primary_positions"]), 2)
        store = self.root / "isolated.sqlite"
        command = [sys.executable, str(SCRIPT), "--import-stage", str(self.output),
            "--stage-sha256", digest(self.output.read_bytes()), "--sqlite-store", str(store)]
        for _ in range(2):
            imported = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(imported.returncode, 0, imported.stderr)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute("select count(*) from observation_versions").fetchone()[0], 1)

    def test_source_or_citation_mismatch_rejects_without_stage(self):
        self.receipt["bytes"] = 0
        self.assertEqual(self.call("--compare-only").returncode, 2)
        self.assertFalse(self.output.exists())
        self.receipt["bytes"] = self.pdf.stat().st_size
        self.first["pages"][24]["rows"][0]["duplicate_of"] = {"page": 23, "row": 1}
        self.assertEqual(self.call("--compare-only").returncode, 2)
        self.assertFalse(self.output.exists())

    def test_disagreement_needs_image_inspection_and_uncertainty_remains_unresolved(self):
        self.second["pages"][23]["rows"][0]["fields"]["score"] = "11"
        self.second["pages"][24]["rows"][0]["fields"]["score"] = "11"
        result = self.call("--compare-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_text())["disagreements"],
                         [{"page": 24, "row": 1, "role": "source", "fields": ["score"]},
                          {"page": 25, "row": 1, "role": "source", "fields": ["score"]}])
        self.output.unlink()
        save(self.inspections_path, self.inspections)
        self.assertEqual(self.call("--inspections", str(self.inspections_path),
            "--inspections-sha256", digest(self.inspections_path.read_bytes())).returncode, 2)
        self.inspect(24, 1, "source", "disagreement")
        self.inspect(25, 1, "source", "disagreement")
        self.inspections["records"][0]["uncertain_fields"] = ["score"]
        save(self.inspections_path, self.inspections)
        result = self.call("--inspections", str(self.inspections_path),
                           "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads(self.output.read_text())
        self.assertEqual(len(stage["observation_versions"]), 0)
        self.assertEqual(len(stage["unresolved_positions"]), 1)

    def test_parser_version_retained_and_invalid_store_rejected(self):
        self.inspect(24, 1, "source", "agreement_sample")
        save(self.inspections_path, self.inspections)
        inspect_args = ("--inspections", str(self.inspections_path),
                        "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(self.call(*inspect_args).returncode, 0)
        original = json.loads(self.output.read_text())
        revised_path = self.root / "stage-v2.json"
        result = self.call(*inspect_args, "--parser-version", "synthetic-v2",
                           "--output", str(revised_path))
        self.assertEqual(result.returncode, 0, result.stderr)
        revised = json.loads(revised_path.read_text())
        self.assertNotEqual(original["observation_versions"][0]["id"],
                            revised["observation_versions"][0]["id"])
        store = self.root / "isolated.sqlite"
        for path in (self.output, revised_path):
            command = [sys.executable, str(SCRIPT), "--import-stage", str(path),
                       "--stage-sha256", digest(path.read_bytes()), "--sqlite-store", str(store)]
            result = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute("select count(*) from observation_versions").fetchone()[0], 2)
        unrelated = self.root / "unrelated.sqlite"
        with sqlite3.connect(unrelated) as db:
            db.execute("create table unrelated (value text)")
        command[-1] = str(unrelated)
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)

    def test_forged_repeat_observation_rejected_before_store_creation(self):
        self.inspect(24, 1, "source", "agreement_sample")
        save(self.inspections_path, self.inspections)
        result = self.call("--inspections", str(self.inspections_path),
                           "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads(self.output.read_text())
        stage["observation_versions"][0]["source_role"] = "duplicate_render"
        forged = self.root / "forged.json"
        save(forged, stage)
        store = self.root / "forged.sqlite"
        command = [sys.executable, str(SCRIPT), "--import-stage", str(forged),
                   "--stage-sha256", digest(forged.read_bytes()), "--sqlite-store", str(store)]
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)
        self.assertFalse(store.exists())

    def test_import_conflict_rolls_back_earlier_insert(self):
        self.inspect(24, 1, "source", "agreement_sample")
        save(self.inspections_path, self.inspections)
        result = self.call("--inspections", str(self.inspections_path),
                           "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        store = self.root / "isolated.sqlite"
        def import_path(path):
            return subprocess.run([sys.executable, str(SCRIPT), "--import-stage", str(path),
                "--stage-sha256", digest(path.read_bytes()), "--sqlite-store", str(store)],
                text=True, capture_output=True)
        self.assertEqual(import_path(self.output).returncode, 0)
        forged = json.loads(self.output.read_text())
        conflict = forged["observation_versions"][0]
        new = json.loads(json.dumps(conflict))
        new["id"] = "observation-version:" + "1" * 64
        new["source_position"]["id"] = "source-position:" + "2" * 64
        conflict["raw_fields"]["score"] = "changed"
        forged["observation_versions"] = [new, conflict]
        forged["counts"]["cited_positions"] += 1
        forged["counts"]["candidate_result_positions"] += 1
        forged["counts"]["verified_staged_versions"] += 1
        path = self.root / "conflict.json"
        save(path, forged)
        self.assertEqual(import_path(path).returncode, 2)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute("select count(*) from observation_versions").fetchone()[0], 1)

    def test_comparable_derivatives_bind_sealed_passes(self):
        sealed_first = self.root / "sealed-first.json"
        sealed_second = self.root / "sealed-second.json"
        first_hash = save(sealed_first, {"source_sha256": self.source})
        second_hash = save(sealed_second, {"source_sha256": self.source})
        self.first["schema"] = self.second["schema"] = "firenze-comparable-pass/v1"
        self.first["basis_sha256"] = first_hash
        self.second["basis_sha256"] = second_hash
        del self.first["counts"]
        del self.second["counts"]
        for page in self.second["pages"]:
            page["render_dimensions"] = [page["render"]["pixel_width"],
                                         page["render"]["pixel_height"]]
            del page["render"]
        for document in (self.first, self.second):
            for page in document["pages"]:
                for row in page["rows"] + page["aggregate_rows"]:
                    del row["id"]
        args = ("--first-sealed", str(sealed_first), "--first-sealed-sha256", first_hash,
                "--second-sealed", str(sealed_second), "--second-sealed-sha256", second_hash,
                "--compare-only")
        result = self.call(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_text())["sealed_basis_sha256"],
                         {"first": first_hash, "second": second_hash})
        self.output.unlink()
        self.second["basis_sha256"] = "0" * 64
        self.assertEqual(self.call(*args).returncode, 2)

    def test_source_image_can_correct_shared_reading_without_rewriting_passes(self):
        self.inspect(24, 1, "source", "shared_error", {"name": "Corrected", "score": "10"})
        self.inspections["records"][0]["source_image_correction"] = "both OCR passes clipped name"
        save(self.inspections_path, self.inspections)
        result = self.call("--inspections", str(self.inspections_path),
                           "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        version = json.loads(self.output.read_text())["observation_versions"][0]
        self.assertEqual(version["raw_fields"]["name"], "Corrected")
        self.assertEqual(version["first_pass_raw_fields"]["name"], "Athlete")
        self.assertEqual(version["second_pass_raw_fields"]["name"], "Athlete")

    def test_inspection_corrects_selected_full_raw_field_and_keeps_historical_original(self):
        for document in (self.first, self.second):
            for number in (24, 25):
                document["pages"][number - 1]["rows"][0]["fields"] = {
                    "position_raw": "1", "cognome_raw": "Corrected", "nome_raw": "Test",
                    "anno_di_nascita_raw": "1990", "societa_raw": "Club",
                    "result_columns_raw": "10"}
                document["pages"][number - 1]["rows"][0]["penalty_raw"] = "BO"
        historical = self.pass_document("historical")
        for number in (24, 25):
            historical["pages"][number - 1]["rows"][0]["fields"] = {
                "Posizione": "1", "Cognome": "Wrong", "Nome": "Test",
                "Anno di nascita": "1990", "Società": "Club", "Punteggio": "10",
                "Penalità": "DQ"}
            historical["pages"][number - 1]["rows"][0]["penalty_raw"] = "DQ"
        historical_path = self.root / "historical.json"
        historical_hash = save(historical_path, historical)
        self.inspect(24, 1, "source", "historical_conflict")
        self.inspections["records"][0]["fields_raw"] = self.first["pages"][23]["rows"][0]["fields"]
        self.inspections["records"][0]["source_image_correction"] = "printed surname inspected"
        self.inspections["records"][0]["penalty_raw"] = "BO"
        self.inspect(25, 1, "source", "historical_conflict")
        self.inspections["records"][1]["fields_raw"] = self.first["pages"][24]["rows"][0]["fields"]
        self.inspections["records"][1]["source_image_correction"] = "repeated printed surname inspected"
        self.inspections["records"][1]["penalty_raw"] = "BO"
        save(self.inspections_path, self.inspections)
        result = self.call("--historical-packet", str(historical_path),
            "--historical-packet-sha256", historical_hash,
            "--inspections", str(self.inspections_path),
            "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        version = json.loads(self.output.read_text())["observation_versions"][0]
        self.assertEqual(version["raw_fields"]["Cognome"], "Corrected")
        self.assertEqual(version["historical_fields_raw"]["Cognome"], "Wrong")
        self.assertEqual(version["raw_fields"]["Penalità"], "BO")
        self.assertEqual(version["historical_fields_raw"]["Penalità"], "DQ")


if __name__ == "__main__":
    unittest.main()
