import hashlib
import csv
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from reconciliation_corpus_test import build_report, build_pg_binding_report


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
        source_input = self.root / "aida.json"
        source_input.write_bytes(b'{"synthetic":true}')
        manifest = {"schema": "unified-evidence-snapshot/v1", "cutoff": "2026-10-01T12:39:47Z",
                    "snapshot_sha256": self.db_hash, "confirmed_distinct_attempts": None,
                    "inputs": {"aida-html": {"status": "included", "record_count": 3,
                                             "path": str(source_input), "sha256": digest(source_input.read_bytes())},
                               "cmas-pdf": {"status": "included", "record_count": 2,
                                            "path": str(self.root / "missing.json"), "sha256": "0" * 64}}}
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
        self.assertEqual(2, report["source_object_refs"])
        self.assertEqual({"source": 1, "candidate_position": 3, "aggregate": 1},
                         report["record_kinds"])
        self.assertEqual(3, report["observation_version_refs"])
        self.assertIsNone(report["confirmed_distinct_attempts"])
        self.assertEqual(1, report["checked_name_input"]["gap_count"])
        self.assertEqual(3, len(report["by_source_year"]))
        self.assertEqual(1, report["unknown_date_by_source"]["aida-html"])
        self.assertEqual({"declared": 2, "verified": 1, "missing": 1, "unverified": 0},
                         report["manifest_inputs"])
        self.assertEqual(["missing-observation-store-revision", "missing-decision-store-revision",
                          "missing-decision-api-mapping"], report["replay_blockers"])

    def test_present_input_hash_mismatch_fails_closed(self):
        (self.root / "aida.json").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "manifest input hash mismatch"):
            build_report(self.snapshot, self.checked, self.checked_hash)

    def test_changed_checked_input_or_snapshot_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "checked input hash mismatch"):
            build_report(self.snapshot, self.checked, "0" * 64)
        checked = json.loads(self.checked.read_text())
        checked["snapshot"]["manifest_sha256"] = "0" * 64
        self.checked.write_text(json.dumps(checked))
        with self.assertRaisesRegex(ValueError, "manifest binding mismatch"):
            build_report(self.snapshot, self.checked, digest(self.checked.read_bytes()))

    def test_out_of_scope_event_date_is_not_counted_as_2026(self):
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


class PostgreSQLBindingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.snapshot = self.root / "snapshot"
        self.snapshot.mkdir()
        self.revision = {"job_id": "a" * 64, "ordinal": 0, "candidate_id": "candidate-1",
                         "artifact_sha256": "b" * 64, "source_sha256": "c" * 64,
                         "parser_version": "parser-v1"}
        self.db_path = self.snapshot / "snapshot.sqlite"
        self.csv_path = self.root / "pg-binding.csv"
        self._write_snapshot([self.revision])
        self._write_csv([self.revision])

    def _write_snapshot(self, refs, scalar="candidate-1", source_hash=None, parser=None,
                        repeated=False, conflicting=False):
        self.db_path.unlink(missing_ok=True)
        with sqlite3.connect(self.db_path) as db:
            db.execute("CREATE TABLE records (kind TEXT, raw_json TEXT, observation_version TEXT, "
                       "source_object_id TEXT, parser_version TEXT, source_id TEXT, citation_json TEXT)")
            db.execute("INSERT INTO records VALUES (?,?,?,?,?,?,?)", (
                "candidate_position", json.dumps({"observation_refs": refs}), scalar,
                "sha256:" + (source_hash or self.revision["source_sha256"]),
                parser or self.revision["parser_version"], "position-1", '{"page":1}'))
            if repeated:
                db.execute("INSERT INTO records VALUES (?,?,?,?,?,?,?)", (
                    "candidate_position", json.dumps({"observation_refs": refs}), scalar,
                    "sha256:" + self.revision["source_sha256"], self.revision["parser_version"],
                    "position-2", '{"page":2}' if conflicting else '{"page":1}'))
            db.execute("INSERT INTO records VALUES (?,?,?,?,?,?,?)", (
                "candidate_position", "{}", "local-version", None, None, "local", "null"))
            db.execute("INSERT INTO records VALUES (?,?,?,?,?,?,?)", (
                "observation_version", "{}", "supplement-version", None, None,
                "supplement", "null"))
        self.db_hash = digest(self.db_path.read_bytes())
        manifest = {"schema": "unified-evidence-snapshot/v1", "snapshot_sha256": self.db_hash}
        manifest_path = self.snapshot / "manifest.json"
        manifest_path.write_text(json.dumps(manifest))
        self.manifest_hash = digest(manifest_path.read_bytes())

    def _write_csv(self, revisions):
        with self.csv_path.open("w", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=list(self.revision))
            writer.writeheader()
            writer.writerows(revisions)
        self.csv_hash = digest(self.csv_path.read_bytes())

    def report(self):
        return build_pg_binding_report(self.snapshot, self.csv_path, self.manifest_hash,
                                       self.db_hash, self.csv_hash)

    def test_exact_snapshot_reference_binds_to_one_pg_observation(self):
        report = self.report()
        self.assertEqual(1, report["pg_observation_versions"])
        self.assertEqual(1, report["bound_pg_observation_versions"])
        self.assertEqual(0, report["unreferenced_pg_observation_versions"])
        self.assertEqual(3, report["snapshot_observation_version_strings"])
        self.assertEqual(2, report["unmatched_non_pg_strings"])

    def test_snapshot_reference_uses_record_source_hash_when_ref_omits_it(self):
        ref = {key: value for key, value in self.revision.items() if key != "source_sha256"}
        self._write_snapshot([ref])
        self.assertEqual(1, self.report()["bound_pg_observation_versions"])

    def test_position_parser_can_differ_from_exact_pg_reference_parser(self):
        ref = {key: value for key, value in self.revision.items() if key != "source_sha256"}
        self._write_snapshot([ref], parser="snapshot-projection-v1")
        report = self.report()
        self.assertEqual(1, report["bound_pg_observation_versions"])
        self.assertEqual(1, report["snapshot_record_parser_disagreements"])

    def test_missing_ambiguous_and_mismatched_references_fail_closed(self):
        self._write_csv([])
        with self.assertRaisesRegex(ValueError, "missing PostgreSQL observation"):
            self.report()
        self._write_csv([self.revision, self.revision])
        with self.assertRaisesRegex(ValueError, "duplicate PostgreSQL observation"):
            self.report()
        self._write_csv([self.revision])
        self._write_snapshot([dict(self.revision, artifact_sha256="d" * 64)])
        with self.assertRaisesRegex(ValueError, "mismatched PostgreSQL observation"):
            self.report()

    def test_snapshot_source_parser_and_frozen_hashes_must_match(self):
        self._write_snapshot([self.revision], source_hash="d" * 64)
        with self.assertRaisesRegex(ValueError, "snapshot source version differs"):
            self.report()
        self._write_snapshot([self.revision])
        with self.assertRaisesRegex(ValueError, "PostgreSQL binding hash mismatch"):
            build_pg_binding_report(self.snapshot, self.csv_path, self.manifest_hash,
                                    self.db_hash, "0" * 64)

    def test_repeated_same_citation_is_counted_but_conflicting_position_fails(self):
        self._write_snapshot([self.revision], repeated=True)
        report = self.report()
        self.assertEqual(2, report["snapshot_structured_pg_refs"])
        self.assertEqual(1, report["repeated_pg_refs_same_position"])
        self.assertEqual(1, report["bound_pg_observation_versions"])
        self._write_snapshot([self.revision], repeated=True, conflicting=True)
        with self.assertRaisesRegex(ValueError, "ambiguous snapshot observation reference"):
            self.report()


if __name__ == "__main__":
    unittest.main()
