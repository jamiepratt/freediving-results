"""Public CLI checks with synthetic source positions only."""
import hashlib
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/fipsas_image_verified_import.py"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    raw = (json.dumps(value, sort_keys=True) + "\n").encode()
    path.write_bytes(raw)
    return digest(raw)


class FipsasImportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.pdf = self.root / "source.pdf"
        self.pdf.write_bytes(b"%PDF-synthetic-private-test")
        self.source = digest(self.pdf.read_bytes())
        self.render = self.root / "page.png"
        self.render.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" +
                                struct.pack(">II", 100, 100) + b"\x08\x02\0\0\0")
        def row(n, aggregate=False):
            ident = digest(f"{self.source}:page:1:{'society aggregate' if aggregate else 'athlete'}:row:{n}".encode())
            return {"id": ("aggregate-position:" if aggregate else "source-position:") + ident,
                    "row": n, "citation": {"page": 1, "region": {"units": "upright_png_pixels_220dpi",
                    "bbox": [1, n, 80, n + 10]}}, "fields": {"name": "Test", "points": "10"},
                    "status_raw": None, "notes_raw": None, "uncertainties": []}
        self.packet = {"schema": "barracuda-visual-evidence/v1", "source_sha256": self.source,
                       "source_bytes": self.pdf.stat().st_size,
                       "source": {"id": f"sha256:{self.source}", "original_sha256": self.source},
                       "pages": [{"page": 1, "disposition": "result_table", "rows": [row(1)],
                                  "aggregate_rows": [], "render": {"dpi": 220,
                                  "pixel_width": 100, "pixel_height": 100}}],
                       "counts": {"source_positions": 1, "aggregate_rows_excluded": 0}}
        self.second = {"schema": "fipsas-image-second-pass/v1", "source_sha256": self.source,
                       "blind_to_first_pass": True, "transcriber": "second",
                       "rows": [{"page": 1, "row": 1, "fields_raw": {"name": "Test", "points": "10"},
                                 "uncertain_fields": []}]}
        self.receipt = {"source_sha256": self.source, "bytes": self.pdf.stat().st_size}
        self.inspections = {"schema": "fipsas-image-inspections/v1", "source_sha256": self.source,
                            "records": [{"page": 1, "row": 1, "reason": "agreement_sample",
                                         "render_sha256": digest(self.render.read_bytes()),
                                         "bbox": [1, 1, 80, 11], "inspector": "third",
                                         "fields_raw": {"name": "Test", "points": "10"},
                                         "uncertain_fields": []}]}
        self.packet_path = self.root / "packet.json"
        self.second_path = self.root / "second.json"
        self.receipt_path = self.root / "receipt.json"
        self.inspections_path = self.root / "inspection.json"

    def run_cli(self, *extra):
        packet_hash = write_json(self.packet_path, self.packet)
        second_hash = write_json(self.second_path, self.second)
        receipt_hash = write_json(self.receipt_path, self.receipt)
        inspection_hash = write_json(self.inspections_path, self.inspections)
        args = [sys.executable, str(SCRIPT), "--pdf", str(self.pdf),
                "--expected-source-sha256", self.source, "--packet", str(self.packet_path),
                "--packet-sha256", packet_hash, "--second-pass", str(self.second_path),
                "--second-pass-sha256", second_hash, "--receipt", str(self.receipt_path),
                "--receipt-sha256", receipt_hash, "--render", str(self.render),
                "--parser-version", "synthetic-v1", "--output", str(self.root / "stage.json")]
        return subprocess.run(args + list(extra), capture_output=True, text=True)

    def test_compare_requires_image_inspection_and_stage_is_replayable(self):
        comparison = self.run_cli("--compare-only")
        self.assertEqual(comparison.returncode, 0, comparison.stderr)
        data = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(data["sampled_agreements"], [[1, 1]])
        (self.root / "stage.json").unlink()
        staged = self.run_cli("--inspections", str(self.inspections_path),
                              "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(staged.returncode, 0, staged.stderr)
        stage = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(stage["counts"]["verified_staged_versions"], 1)
        self.assertEqual(self.run_cli("--inspections", str(self.inspections_path),
                                     "--inspections-sha256", digest(self.inspections_path.read_bytes())).returncode, 0)

    def test_damaged_citation_is_rejected(self):
        self.packet["pages"][0]["rows"][0]["citation"]["region"]["bbox"] = [1, 1, 200, 11]
        self.assertEqual(self.run_cli("--compare-only").returncode, 2)

    def test_mismatched_source_and_receipt_are_rejected(self):
        self.packet["source_sha256"] = "0" * 64
        self.assertEqual(self.run_cli("--compare-only").returncode, 2)
        self.packet["source_sha256"] = self.source
        self.receipt["bytes"] = 0
        self.assertEqual(self.run_cli("--compare-only").returncode, 2)

    def test_aggregate_is_accounted_but_not_imported(self):
        aggregate = {"id": "aggregate-position:" + digest(
            f"{self.source}:page:2:society aggregate:row:1".encode()),
            "row": 1, "citation": {"page": 2, "region": {"units": "upright_png_pixels_220dpi",
            "bbox": [1, 20, 80, 30]}}, "fields": {"club": "Example", "points": "10"},
            "status_raw": None, "notes_raw": None, "uncertainties": []}
        self.packet["pages"].append({"page": 2, "disposition": "society_aggregate", "rows": [],
                                     "aggregate_rows": [aggregate], "render": {"dpi": 220,
                                     "pixel_width": 100, "pixel_height": 100}})
        self.packet["counts"]["aggregate_rows_excluded"] = 1
        self.second["aggregate"] = {"page": 2, "row": 1,
                                    "fields_raw": aggregate["fields"], "uncertain_fields": []}
        result = self.run_cli("--render", str(self.render), "--compare-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        comparison = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(comparison["counts"]["cited_positions"], 2)
        self.assertEqual(comparison["counts"]["aggregate_rows_excluded"], 1)
        (self.root / "stage.json").unlink()
        staged = self.run_cli("--render", str(self.render), "--inspections", str(self.inspections_path),
                              "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(staged.returncode, 0, staged.stderr)
        stage = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(len(stage["observation_versions"]), 1)
        self.assertEqual(len(stage["non_primary_positions"]), 1)

    def test_isolated_import_is_idempotent_and_rejects_other_store(self):
        write_json(self.inspections_path, self.inspections)
        staged = self.run_cli("--inspections", str(self.inspections_path),
                              "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(staged.returncode, 0, staged.stderr)
        stage_path = self.root / "stage.json"
        store = self.root / "isolated.sqlite"
        command = [sys.executable, str(SCRIPT), "--import-stage", str(stage_path),
                   "--stage-sha256", digest(stage_path.read_bytes()), "--sqlite-store", str(store)]
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute("select count(*) from observation_versions").fetchone()[0], 1)
        unrelated = self.root / "unrelated.sqlite"
        with sqlite3.connect(unrelated) as db:
            db.execute("create table unrelated (value text)")
        self.assertEqual(subprocess.run(command[:-1] + [str(unrelated)], capture_output=True).returncode, 2)

    def test_parser_version_creates_distinct_immutable_version(self):
        write_json(self.inspections_path, self.inspections)
        inspection_args = ["--inspections", str(self.inspections_path),
                           "--inspections-sha256", digest(self.inspections_path.read_bytes())]
        self.assertEqual(self.run_cli(*inspection_args).returncode, 0)
        original = json.loads((self.root / "stage.json").read_text())
        other_path = self.root / "stage-v2.json"
        self.assertEqual(self.run_cli(*inspection_args, "--parser-version", "synthetic-v2",
                                      "--output", str(other_path)).returncode, 0)
        revised = json.loads(other_path.read_text())
        self.assertNotEqual(original["observation_versions"][0]["id"],
                            revised["observation_versions"][0]["id"])
        store = self.root / "isolated.sqlite"
        for path in (self.root / "stage.json", other_path):
            command = [sys.executable, str(SCRIPT), "--import-stage", str(path),
                       "--stage-sha256", digest(path.read_bytes()), "--sqlite-store", str(store)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute("select count(*) from observation_versions").fetchone()[0], 2)

    def test_conflict_rolls_back_earlier_insert_in_same_stage(self):
        write_json(self.inspections_path, self.inspections)
        self.assertEqual(self.run_cli("--inspections", str(self.inspections_path),
                                      "--inspections-sha256", digest(self.inspections_path.read_bytes())).returncode, 0)
        path = self.root / "stage.json"
        store = self.root / "isolated.sqlite"
        def import_path(target):
            return subprocess.run([sys.executable, str(SCRIPT), "--import-stage", str(target),
                                   "--stage-sha256", digest(target.read_bytes()),
                                   "--sqlite-store", str(store)], capture_output=True)
        self.assertEqual(import_path(path).returncode, 0)
        conflict = json.loads(path.read_text())
        first = conflict["observation_versions"][0]
        added = json.loads(json.dumps(first))
        added["id"] = "observation-version:" + "1" * 64
        added["source_position"]["id"] = "source-position:" + "2" * 64
        first["raw_fields"]["points"] = "changed"
        conflict["observation_versions"] = [added, first]
        conflict["counts"]["cited_positions"] = 2
        conflict["counts"]["candidate_result_positions"] = 2
        conflict["counts"]["verified_staged_versions"] = 2
        failing = self.root / "conflict.json"
        write_json(failing, conflict)
        self.assertEqual(import_path(failing).returncode, 2)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute("select count(*) from observation_versions").fetchone()[0], 1)

    def test_komaros_status_note_and_blank_equivalence_then_real_disagreement(self):
        self.packet["schema"] = "komaros-visual-evidence/v1"
        row = self.packet["pages"][0]["rows"][0]
        row["id"] = "source-position:" + digest(f"{self.source}:page:1:row:1".encode())
        row["citation"]["region"]["units"] = "rendered_px_220dpi"
        row["fields"] = {"position": None, "name": "Test", "penalty": "DQ/BO\nsource note",
                         "homologated_time": None, "points": "10"}
        self.second["rows"][0]["fields_raw"] = {"position": "", "given_name": "Test",
            "penalty_or_status": "DQ/BO", "note": "source note", "approved_time": "", "points": "10"}
        result = self.run_cli("--source-kind", "komaros", "--compare-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        comparison = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(comparison["counts"]["disagreements"], 0)
        (self.root / "stage.json").unlink()
        self.second["rows"][0]["fields_raw"]["points"] = "11"
        result = self.run_cli("--source-kind", "komaros", "--compare-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        comparison = json.loads((self.root / "stage.json").read_text())
        self.assertEqual(comparison["disagreements"], [{"page": 1, "row": 1, "fields": ["points"]}])
        (self.root / "stage.json").unlink()
        result = self.run_cli("--source-kind", "komaros", "--inspections", str(self.inspections_path),
                              "--inspections-sha256", digest(self.inspections_path.read_bytes()))
        self.assertEqual(result.returncode, 2)

    def test_forged_aggregate_observation_is_rejected_before_store_creation(self):
        write_json(self.inspections_path, self.inspections)
        self.assertEqual(self.run_cli("--inspections", str(self.inspections_path),
                                      "--inspections-sha256", digest(self.inspections_path.read_bytes())).returncode, 0)
        stage = json.loads((self.root / "stage.json").read_text())
        stage["observation_versions"][0]["source_role"] = "society_aggregate"
        forged = self.root / "forged.json"
        write_json(forged, stage)
        store = self.root / "forged.sqlite"
        result = subprocess.run([sys.executable, str(SCRIPT), "--import-stage", str(forged),
                                 "--stage-sha256", digest(forged.read_bytes()),
                                 "--sqlite-store", str(store)], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(store.exists())


if __name__ == "__main__":
    unittest.main()
