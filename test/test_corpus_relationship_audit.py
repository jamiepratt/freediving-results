import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/corpus_relationship_audit.py"
A = "a" * 64
B = "b" * 64
C = "c" * 64


def observation(job, ordinal, sha=A, day="2026-01-01", performance="70"):
    return {"ref": {"job-id": job, "ordinal": ordinal}, "source-sha256": sha,
            "position": [{"page": 1, "line": 10}], "source-text": "printed result",
            "event-key": sha, "discipline": "FIM", "day": day,
            "parser-version": "p/1", "parsed": {"source-name": "A Diver",
                                                  "final-performance": performance,
                                                  "status": "OK"}}


def evidence():
    left = {"route-id": "vestico-default", "source-sha256": A}
    right = {"route-id": "vestico-filtered", "source-sha256": A}
    return {"baseline": {
        "observations": [observation("j1", 0), observation("j2", 0)],
        "routes": [left, right],
        "source-edges": [{"scope": "source-route", "left": {"route-id": "vestico-default"},
                          "right": {"route-id": "vestico-filtered"},
                          "kind": "source-duplicate", "basis": {"source-sha256": A,
                                                                 "proof": "identical-source-bytes"}}],
        "source-candidates": [],
        "edges": [{"scope": "observation", "left": {"job-id": "j1", "ordinal": 0},
                   "right": {"job-id": "j2", "ordinal": 0},
                   "kind": "source-duplicate", "basis": {"source-sha256": A,
                    "position": [{"page": 1, "line": 10}],
                    "left-text": "printed result", "right-text": "printed result"}}],
        "candidates": [],
        "counts-by-scope": {"observation": {"source-duplicate": 1},
                            "source-route": {"source-duplicate": 1}}},
        "b44": {"sources": {"b29_cmas": A, "b44_open": B, "b44_italian": C},
                "open": {"full_row_match_count": 183,
                         "normalized_page_text_matches_b29": 31,
                         "all_page_text_equal_after_normalization": True},
                "italian": {"national_position_match_count": 2,
                            "matched_row_evidence": [
                                {"page": 2, "line": 3, "heading": "CWT", "canonical_row_sha256": A},
                                {"page": 2, "line": 4, "heading": "CWT", "canonical_row_sha256": B}]}}}


class CorpusRelationshipAuditTest(unittest.TestCase):
    def run_cli(self, data, *args):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.json"
            output = Path(directory) / "output.json"
            source.write_text(json.dumps(data))
            result = subprocess.run([sys.executable, str(SCRIPT), str(source), str(output),
                                     *args], capture_output=True, text=True)
            if result.returncode == 0:
                self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return result, json.loads(output.read_text()) if output.exists() else None

    def test_cli_retains_exact_edges_and_separates_cited_source_position_relationships(self):
        source = evidence()
        result, audit = self.run_cli(source, "--expected-observations", "2",
                                     "--expected-jobs", "2")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, audit["counts"]["observation_versions"])
        self.assertEqual(2, audit["counts"]["extraction_jobs"])
        self.assertEqual(1, audit["counts"]["exact_observation_edges"])
        self.assertEqual(1, audit["counts"]["exact_source_route_duplicates"])
        self.assertEqual([183, 2], [x["matched_positions"] for x in audit["source_position_relationships"]])
        self.assertEqual("cross-publication-same-result", audit["source_position_relationships"][0]["kind"])
        self.assertNotIn("unique_attempts", json.dumps(audit))
        self.assertEqual(audit["ledger_sha256"], self.run_cli(source)[1]["ledger_sha256"])

    def test_changed_bytes_and_unknown_dates_do_not_become_exact_edges(self):
        source = evidence()
        source["baseline"]["observations"][1] = observation("j2", 0, B, day=None)
        source["baseline"]["edges"] = []
        source["baseline"]["counts-by-scope"]["observation"] = {}
        source["baseline"]["routes"] = [{"route-id": "same-route-a", "source-sha256": A},
                                           {"route-id": "same-route-b", "source-sha256": B}]
        source["baseline"]["source-edges"] = []
        source["baseline"]["counts-by-scope"]["source-route"] = {}
        source["baseline"]["source-candidates"] = [{"scope": "source-route",
            "left": {"route-id": "same-route-a"}, "right": {"route-id": "same-route-b"},
            "kind": "unknown", "reason": "changed-bytes", "basis": {"left-source-sha256": A,
                                                               "right-source-sha256": B}}]
        result, audit = self.run_cli(source)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, audit["counts"]["exact_observation_edges"])
        self.assertEqual(1, len(audit["unknown_candidates"]))

    def test_rejects_uncited_or_conflicting_claims_and_mismatched_b44_counts(self):
        source = evidence()
        source["baseline"]["edges"][0]["right"] = {"job-id": "missing", "ordinal": 0}
        result, output = self.run_cli(source)
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(output)
        source = evidence()
        source["b44"]["italian"]["national_position_match_count"] = 3
        result, output = self.run_cli(source)
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(output)

    def test_archive_and_prior_summaries_do_not_create_observation_pairs(self):
        source = evidence()
        source["archive_inventory"] = {"expected_acquisitions": 3,
            "expected_distinct_hashes": 2, "acquisitions": [
                {"acquisition_id": "one", "route_id": "event-a", "source_sha256": A},
                {"acquisition_id": "two", "route_id": "event-b", "source_sha256": A},
                {"acquisition_id": "three", "route_id": "event-a", "source_sha256": B}]}
        source["prior_ledgers"] = [{"id": "tuttin-february", "ledger_sha256": C,
            "kind": "supporting-printed-value-links", "matched_positions": 1888,
            "breakdown": {"STA": 944, "DYN": 944}}]
        result, audit = self.run_cli(source)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, audit["counts"]["archive_byte_duplicate_groups"])
        self.assertEqual(1, audit["counts"]["unknown_candidate_groups"])
        self.assertEqual("changed-bytes-same-route", audit["unknown_candidates"][0]["reason"])
        self.assertEqual(1888, audit["prior_supporting_ledgers"][0]["matched_positions"])
        self.assertEqual(1, audit["counts"]["exact_observation_edges"])


if __name__ == "__main__":
    unittest.main()
