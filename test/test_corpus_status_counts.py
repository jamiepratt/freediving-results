import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "scripts" / "corpus_status_counts.py"


def run_report(document):
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / "inventory.json"
        source.write_text(json.dumps(document))
        return subprocess.run([sys.executable, str(CLI), str(source)],
                              capture_output=True, text=True)


class CorpusStatusCountsTest(unittest.TestCase):
    def test_separates_links_events_sources_and_row_evidence(self):
        digest = "a" * 64
        document = {
            "schema": "corpus-status-counts/v1",
            "scope": "bounded 2025 CMAS index",
            "groups": [
                {"group_id": "index", "cutoff": "2026-09-26T12:00:00Z", "links": [
                    {"link_id": "url-1", "status": "imported", "reason": "cited PDF",
                     "event_ids": ["event-a"], "source_sha256": digest,
                     "row_count": 12, "row_count_kind": "imported_positions",
                     "row_basis_id": "pdf-a"},
                    {"link_id": "url-2", "status": "already_present", "reason": "same bytes",
                     "event_ids": ["event-a"], "source_sha256": digest},
                    {"link_id": "url-3", "status": "unsupported", "reason": "image only"},
                ]},
                {"group_id": "review", "cutoff": "2026-09-27T12:00:00Z", "links": [
                    {"link_id": "url-4", "status": "unresolved", "reason": "row clipped",
                     "event_ids": ["event-b"], "source_sha256": "b" * 64,
                     "row_count": 1, "row_count_kind": "unresolved_positions",
                     "row_basis_id": "pdf-b"},
                ]},
            ],
            "corpus_snapshot": {"cutoff": "2026-09-27T12:00:00Z", "jobs": 91,
                                "source_hashes": 89, "observation_versions": 11150},
        }
        completed = run_report(document)
        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual({"imported": 1, "already_present": 1, "unsupported": 1,
                          "unresolved": 1}, report["links_by_status"])
        self.assertEqual(2, report["known_event_count"])
        self.assertEqual(1, report["unknown_event_link_count"])
        self.assertEqual(2, report["distinct_source_hash_count"])
        self.assertEqual({"imported_positions": 12, "unresolved_positions": 1},
                         report["rows_by_kind"])
        self.assertEqual(3, report["groups"][0]["link_count"])
        self.assertEqual(1, report["groups"][0]["known_event_count"])
        self.assertEqual(91, report["corpus_snapshot"]["jobs"])
        self.assertNotIn("unique_attempts", report)

    def test_shared_pdf_link_and_event_cards_have_separate_counts(self):
        document = {"schema": "corpus-status-counts/v1", "scope": "calendar",
                    "groups": [{
                        "group_id": "calendar", "cutoff": "2026-09-27T00:00:00Z",
                        "event_cards": [
                            {"event_id": "one", "status": "linked", "reason": "PDF route"},
                            {"event_id": "two", "status": "linked", "reason": "same PDF route"},
                            {"event_id": "three", "status": "unlinked", "reason": "no result URL"}],
                        "links": [{"link_id": "pdf", "status": "imported", "reason": "results",
                                   "event_ids": ["one", "two"], "source_sha256": "a" * 64}]}]}
        completed = run_report(document)
        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(1, report["link_count"])
        self.assertEqual(3, report["known_event_count"])
        self.assertEqual({"imported": 2}, report["events_by_link_status"])
        self.assertEqual({"linked": 2, "unlinked": 1}, report["event_cards_by_status"])
        self.assertEqual(3, report["distinct_event_card_count"])
        self.assertEqual(0, report["unknown_event_link_count"])

    def test_repeated_row_basis_deduplicates_across_groups(self):
        row = {"status": "imported", "reason": "same source",
               "row_count": 4, "row_count_kind": "printed_positions",
               "row_basis_id": "source-a"}
        document = {"schema": "corpus-status-counts/v1", "scope": "replay",
                    "groups": [
                        {"group_id": "one", "cutoff": "2026-09-26T00:00:00Z",
                         "links": [dict(row, link_id="a")]},
                        {"group_id": "two", "cutoff": "2026-09-27T00:00:00Z",
                         "links": [dict(row, link_id="b")]},
                    ]}
        completed = run_report(document)
        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(4, report["rows_by_kind"]["printed_positions"])
        self.assertEqual(2, report["link_count"])

    def test_rejects_conflicting_basis_and_partial_row_counts(self):
        row = {"link_id": "a", "status": "imported", "reason": "cited",
               "row_count": 4, "row_count_kind": "printed_positions",
               "row_basis_id": "source-a"}
        document = {"schema": "corpus-status-counts/v1", "scope": "bad",
                    "groups": [{"group_id": "g", "cutoff": "2026-09-26T00:00:00Z",
                                "links": [row, dict(row, link_id="b", row_count=5)]}]}
        completed = run_report(document)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("conflicting row basis", completed.stderr)
        document["groups"][0]["links"] = [dict(row, link_id="c", row_basis_id=None)]
        completed = run_report(document)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("row_count", completed.stderr)


if __name__ == "__main__":
    unittest.main()
