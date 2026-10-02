import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from reconciliation_corpus_test import build_report


def digest(data):
    return hashlib.sha256(data).hexdigest()


class ReconciliationCorpusTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.snapshot = self.root / "snapshot"
        self.snapshot.mkdir()
        db_path = self.snapshot / "snapshot.sqlite"
        with sqlite3.connect(db_path) as db:
            db.execute("CREATE TABLE records (source_name TEXT, kind TEXT, source_object_id TEXT, "
                       "observation_version TEXT, event_date TEXT)")
            db.executemany("INSERT INTO records VALUES (?,?,?,?,?)", [
                ("aida-html", "source", "source-1", None, "2025-03-01"),
                ("aida-html", "candidate_position", "source-1", "obs-1", "2025-03-01"),
                ("aida-html", "candidate_position", "source-1", "obs-2", None),
                ("cmas-pdf", "candidate_position", "source-2", "obs-3", "2026-04-02"),
                ("cmas-pdf", "aggregate", "source-2", None, "2026-04-02"),
            ])
        self.db_hash = digest(db_path.read_bytes())
        manifest = {"schema": "unified-evidence-snapshot/v1", "cutoff": "2026-10-01T12:39:47Z",
                    "snapshot_sha256": self.db_hash, "confirmed_distinct_attempts": None,
                    "inputs": {"aida-html": {"status": "included", "record_count": 3},
                               "cmas-pdf": {"status": "included", "record_count": 2}}}
        manifest_path = self.snapshot / "manifest.json"
        manifest_path.write_text(json.dumps(manifest))
        self.manifest_hash = digest(manifest_path.read_bytes())
        checked = {"schema": "affiliate-name-input/v1", "snapshot": {
            "cutoff": manifest["cutoff"], "path": str(self.snapshot),
            "manifest_sha256": self.manifest_hash, "sqlite_sha256": self.db_hash},
            "assertions": [{"id": "synthetic"}], "gaps": [{"reason": "unavailable"}]}
        self.checked = self.root / "checked.json"
        self.checked.write_text(json.dumps(checked))
        self.checked_hash = digest(self.checked.read_bytes())

    def test_frozen_inputs_report_dated_positions_and_unknown_gaps(self):
        report = build_report(self.snapshot, self.checked, self.checked_hash)
        self.assertEqual({"2025": 1, "2026": 1, "unknown": 1, "outside_scope": 0},
                         report["candidate_positions_by_year"])
        self.assertEqual(3, report["candidate_positions"])
        self.assertEqual(1, report["source_records"])
        self.assertEqual(3, report["observation_version_refs"])
        self.assertIsNone(report["confirmed_distinct_attempts"])
        self.assertEqual(1, report["checked_name_input"]["gap_count"])
        self.assertEqual(3, len(report["by_source_year"]))
        self.assertEqual(1, report["unknown_date_by_source"]["aida-html"])

    def test_changed_checked_input_or_snapshot_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "checked input hash mismatch"):
            build_report(self.snapshot, self.checked, "0" * 64)
        checked = json.loads(self.checked.read_text())
        checked["snapshot"]["manifest_sha256"] = "0" * 64
        self.checked.write_text(json.dumps(checked))
        with self.assertRaisesRegex(ValueError, "manifest binding mismatch"):
            build_report(self.snapshot, self.checked, digest(self.checked.read_bytes()))

    def test_publication_date_cannot_replace_missing_event_date(self):
        with sqlite3.connect(self.snapshot / "snapshot.sqlite") as db:
            db.execute("UPDATE records SET event_date = '2024-12-01' WHERE observation_version = 'obs-3'")
        manifest = json.loads((self.snapshot / "manifest.json").read_text())
        manifest["snapshot_sha256"] = digest((self.snapshot / "snapshot.sqlite").read_bytes())
        (self.snapshot / "manifest.json").write_text(json.dumps(manifest))
        checked = json.loads(self.checked.read_text())
        checked["snapshot"]["manifest_sha256"] = digest((self.snapshot / "manifest.json").read_bytes())
        checked["snapshot"]["sqlite_sha256"] = manifest["snapshot_sha256"]
        self.checked.write_text(json.dumps(checked))
        report = build_report(self.snapshot, self.checked, digest(self.checked.read_bytes()))
        self.assertEqual(1, report["candidate_positions_by_year"]["outside_scope"])
        self.assertEqual(0, report["candidate_positions_by_year"]["2026"])


if __name__ == "__main__":
    unittest.main()
