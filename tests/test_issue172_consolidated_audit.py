import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/issue172_consolidated_audit.py"
SOURCES = ("worldcup", "italian_open", "barracuda", "komaros", "cagliari", "liberamente", "friday", "firenze", "asti")


class ConsolidatedAuditTest(unittest.TestCase):
    def test_nine_pinned_stages_produce_accounted_private_queue(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            command = [sys.executable, str(SCRIPT), "--output-dir", str(root / "out")]
            for name in SOURCES:
                stage = {
                    "schema": "verified-scan-stage-v1", "source_sha256": name,
                    "parser_version": "v1", "input_sha256": {"receipt": "r", "first_packet": "f", "second_pass": "s"},
                    "counts": {"cited_positions": 2, "candidate_result_positions": 2,
                               "verified_staged_versions": 1, "unresolved_positions": 1},
                    "observation_versions": [{"id": name + ":v1", "source_sha256": name,
                        "parser_version": "v1", "source_position": {"id": name + ":1", "citation": {"page": 1}},
                        "raw_fields": {"given_name": "Private", "surname": "Diver", "points": "100"}}],
                    "unresolved_positions": [{"id": name + ":2", "citation": {"page": 1}, "reason": "unreadable"}],
                    "non_primary_positions": []}
                path = root / (name + ".json")
                path.write_text(json.dumps(stage))
                sha = hashlib.sha256(path.read_bytes()).hexdigest()
                command += ["--stage", f"{name}={path}={sha}"]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            audit = json.loads((root / "out/audit-v1.json").read_text())
            queue = json.loads((root / "out/owner-queue-v1.json").read_text())
            self.assertEqual(audit["totals"]["candidate_result_positions"], 18)
            self.assertEqual(audit["totals"]["verified_staged_versions"], 9)
            self.assertIsNone(audit["totals"]["confirmed_distinct_attempts"])
            self.assertEqual(len(queue["entries"]), 9)
            self.assertNotIn("private", (root / "out/owner-queue-v1.json").read_text())
            snapshot = root / "retained.sqlite"
            with sqlite3.connect(snapshot) as db:
                db.execute("create table records(record_id, source_id, source_object_id, event_date, "
                           "discipline, citation_json, raw_fields_json, parsed_fields_json, kind)")
            pinned = hashlib.sha256(snapshot.read_bytes()).hexdigest()
            result = subprocess.run(command + ["--snapshot", f"{snapshot}={pinned}"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            audit = json.loads((root / "out/audit-v1.json").read_text())
            self.assertGreater(audit["totals"]["ambiguous_candidate"], 0)
            self.assertEqual(audit["totals"]["exact_supported"], 0)
            self.assertIsNone(audit["totals"]["confirmed_distinct_attempts"])
            self.assertEqual(len(audit["cross_stage_pair_leads"]), 36)

    def test_missing_stage_or_wrong_hash_fails_closed(self):
        with tempfile.TemporaryDirectory() as root:
            stage = Path(root) / "stage.json"
            stage.write_text("{}")
            result = subprocess.run([sys.executable, str(SCRIPT), "--output-dir", root,
                                     "--stage", f"worldcup={stage}=" + "0" * 64],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SHA-256 mismatch", result.stderr)
            self.assertFalse((Path(root) / "audit-v1.json").exists())


if __name__ == "__main__":
    unittest.main()
