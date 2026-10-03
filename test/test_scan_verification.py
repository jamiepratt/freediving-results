import unittest
import json
from unittest.mock import patch
from copy import deepcopy

from scripts.scan_verification import verify_transcriptions


SOURCE = "a" * 64
REGIONS = {
    "p1-r1": {"x1": 1, "y1": 2, "x2": 10, "y2": 12},
    "p1-r2": {"x1": 1, "y1": 13, "x2": 10, "y2": 22},
    "p1-r3": {"x1": 1, "y1": 23, "x2": 10, "y2": 32},
}


def entry(position, reading):
    return {"position": position, "page": 1, "region_px": REGIONS[position],
            "citation": f"page 1 region {position}", "reading": reading}


def transcription(pass_id, actor, readings):
    return {"schema": "scan-transcription/v1", "pass_id": pass_id,
            "source_sha256": SOURCE, "parser_version": "scan-transcription/1",
            "provenance": {"actor": actor, "source_only": True,
                           "first_pass_visible": False,
                           "attestation": "I transcribed directly from the source without seeing another pass."},
            "entries": [entry(position, reading) for position, reading in readings.items()]}


def inspection(position, decision, reading=None):
    return {"position": position, "source_sha256": SOURCE, "page": 1,
            "region_px": REGIONS[position], "citation": f"page 1 region {position}",
            "inspected_by": "reviewer", "decision": decision, "reading": reading,
            "note": "checked against retained source"}


class ScanVerificationTest(unittest.TestCase):
    def setUp(self):
        self.first = transcription("pass-1", "worker-1", {"p1-r1": "A", "p1-r2": "B", "p1-r3": None})
        self.second = transcription("pass-2", "worker-2", {"p1-r1": "A", "p1-r2": "8", "p1-r3": "C"})

    def checks(self):
        return [inspection("p1-r1", "confirmed", "A"),
                inspection("p1-r2", "resolved", "B"),
                inspection("p1-r3", "unresolved")]

    def test_compares_retained_passes_with_disagreement_inspection_and_agreement_sample(self):
        result = verify_transcriptions(self.first, self.second, self.checks(), sample_size=1)
        self.assertEqual("scan-verification/v1", result["schema"])
        self.assertEqual(["p1-r1"], result["sampled_agreements"])
        self.assertEqual(["p1-r2", "p1-r3"], result["disagreements"])
        self.assertEqual("B", result["positions"][1]["accepted_reading"])
        self.assertIsNone(result["positions"][2]["accepted_reading"])
        self.assertEqual("unresolved", result["positions"][2]["status"])
        self.assertEqual(self.first["entries"][1], result["positions"][1]["first_pass"])
        self.assertEqual(self.second["entries"][1], result["positions"][1]["second_pass"])

    def test_accepts_overlapping_independent_citations_and_inspection(self):
        second = deepcopy(self.second)
        second["entries"][1]["region_px"] = {"x1": 2, "y1": 14, "x2": 11, "y2": 23}
        second["entries"][1]["citation"] = "second pass box for row 2"
        checks = self.checks()
        checks[1]["region_px"] = {"x1": 2, "y1": 13, "x2": 11, "y2": 22}
        checks[1]["citation"] = "reviewer box for row 2"

        result = verify_transcriptions(self.first, second, checks, sample_size=1)

        row = result["positions"][1]
        self.assertEqual("B", row["accepted_reading"])
        self.assertEqual(self.first["entries"][1], row["first_pass"])
        self.assertEqual(second["entries"][1], row["second_pass"])
        self.assertEqual(checks[1], row["inspection"])

    def test_rejects_pass_citations_that_do_not_identify_same_region(self):
        for change in ({"region_px": {"x1": 8, "y1": 20, "x2": 17, "y2": 29}},
                       {"page": 2}, {"region_px": {"x1": 11, "y1": 13, "x2": 20, "y2": 22}}):
            second = deepcopy(self.second)
            second["entries"][1].update(change)
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "citation mismatch"):
                verify_transcriptions(self.first, second, self.checks(), sample_size=1)

    def test_rejects_inspection_not_overlapping_both_passes(self):
        second = deepcopy(self.second)
        second["entries"][1]["region_px"] = {"x1": 4, "y1": 13, "x2": 13, "y2": 22}
        checks = self.checks()
        checks[1]["region_px"] = {"x1": 1, "y1": 13, "x2": 7, "y2": 22}
        with self.assertRaisesRegex(ValueError, "inspection citation mismatch"):
            verify_transcriptions(self.first, second, checks, sample_size=1)

    def test_rejects_invalid_or_source_mismatched_citations(self):
        for change, message in (({"region_px": None}, "invalid region"),
                                ({"citation": ""}, "missing citation"),
                                ({"source_sha256": "b" * 64}, "citation source mismatch")):
            second = deepcopy(self.second)
            second["entries"][1].update(change)
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, message):
                verify_transcriptions(self.first, second, self.checks(), sample_size=1)
        checks = self.checks()
        checks[1]["source_sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "inspection source mismatch"):
            verify_transcriptions(self.first, self.second, checks, sample_size=1)

    def test_replay_is_deterministic_and_retains_versions_without_changing_inputs(self):
        first, second, checks = deepcopy((self.first, self.second, self.checks()))
        result = verify_transcriptions(first, second, checks, sample_size=1)
        self.assertEqual(result, verify_transcriptions(first, second, checks, sample_size=1))
        self.assertEqual((self.first, self.second, self.checks()), (first, second, checks))
        self.assertEqual(["scan-transcription/1", "scan-transcription/1"],
                         [item["parser_version"] for item in result["passes"]])
        self.assertEqual(SOURCE, result["source_sha256"])

    def test_rejects_unattested_or_visible_first_pass(self):
        for change in ({"first_pass_visible": True}, {"source_only": False}, {"attestation": ""}):
            second = deepcopy(self.second)
            second["provenance"].update(change)
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "provenance"):
                verify_transcriptions(self.first, second, self.checks(), sample_size=1)
        second = deepcopy(self.second)
        second["provenance"]["actor"] = "worker-1"
        with self.assertRaisesRegex(ValueError, "distinct"):
            verify_transcriptions(self.first, second, self.checks(), sample_size=1)

    def test_requires_inspection_of_every_disagreement_and_sampled_agreement(self):
        for missing in ("p1-r1", "p1-r2", "p1-r3"):
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "missing cited"):
                verify_transcriptions(self.first, self.second,
                                      [check for check in self.checks() if check["position"] != missing],
                                      sample_size=1)

    def test_requires_nonempty_agreement_sample_when_agreements_exist(self):
        with self.assertRaisesRegex(ValueError, "agreement sample"):
            verify_transcriptions(self.first, self.second,
                                  [inspection("p1-r2", "resolved", "B"),
                                   inspection("p1-r3", "unresolved")], sample_size=0)

    def test_uninspected_agreements_are_marked_explicitly(self):
        first = transcription("pass-1", "worker-1", {"p1-r1": "A", "p1-r2": "B"})
        second = transcription("pass-2", "worker-2", {"p1-r1": "A", "p1-r2": "B"})
        sampled = verify_transcriptions(first, second, [inspection("p1-r1", "confirmed", "A")],
                                         sample_size=1)
        statuses = {row["position"]: row["status"] for row in sampled["positions"]}
        self.assertEqual({"source_inspected", "agreed_uninspected"}, set(statuses.values()))

    def test_zero_sample_is_valid_when_no_agreements_exist(self):
        first = transcription("pass-1", "worker-1", {"p1-r1": "A"})
        second = transcription("pass-2", "worker-2", {"p1-r1": "8"})
        result = verify_transcriptions(first, second,
                                       [inspection("p1-r1", "unresolved")], sample_size=0)
        self.assertEqual([], result["sampled_agreements"])
        self.assertEqual("unresolved", result["positions"][0]["status"])

    def test_rejects_uncited_or_unresolved_acceptance(self):
        bad = self.checks()
        bad[1]["region_px"] = {"x1": 11, "y1": 13, "x2": 20, "y2": 22}
        with self.assertRaisesRegex(ValueError, "citation mismatch"):
            verify_transcriptions(self.first, self.second, bad, sample_size=1)
        bad = self.checks()
        bad[2]["reading"] = "guessed"
        with self.assertRaisesRegex(ValueError, "unresolved"):
            verify_transcriptions(self.first, self.second, bad, sample_size=1)

    def test_rejects_different_source_or_position_coverage(self):
        second = deepcopy(self.second)
        second["source_sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            verify_transcriptions(self.first, second, self.checks(), sample_size=1)
        second = deepcopy(self.second)
        second["entries"].pop()
        with self.assertRaisesRegex(ValueError, "coverage differs"):
            verify_transcriptions(self.first, second, self.checks(), sample_size=1)


class RetainedReplayTest(unittest.TestCase):
    def test_replay_requires_bound_summary_and_recomputed_review(self):
        from scripts.scan_verification import replay_scan_source
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / "blind-a").mkdir()
            (root / "blind-b").mkdir()
            (root / "review").mkdir()
            source = root / "napoli_statica_maschile_2025.jpg"
            source.write_bytes(b"synthetic jpg")
            sha = __import__("hashlib").sha256(source.read_bytes()).hexdigest()
            first = transcription("pass-1", "worker-1", {"p1-r1": "A", "p1-r2": "B"})
            second = transcription("pass-2", "worker-2", {"p1-r1": "A", "p1-r2": None})
            for item in (first, second):
                item["source_sha256"] = sha
            checks = [inspection("p1-r1", "confirmed", "A"), inspection("p1-r2", "unresolved")]
            for check in checks:
                check["source_sha256"] = sha
            review = verify_transcriptions(first, second, checks, sample_size=1)
            def save(path, value):
                path.write_text(json.dumps(value), encoding="utf-8")
                return __import__("hashlib").sha256(path.read_bytes()).hexdigest()
            stem = source.stem
            a = save(root / "blind-a" / (stem + ".json"), first)
            b = save(root / "blind-b" / (stem + ".json"), second)
            v = save(root / "review" / (stem + ".verification.json"), review)
            coverage = {"schema": "scan-transcription-coverage/v1", "source_sha256": sha,
                        "sections": [{"label": "STATICA maschile", "rows_attempted": 2,
                                      "rows_unexamined": 0, "rows_ambiguous": 1}]}
            save(root / "blind-a" / (stem + ".coverage.json"), dict(coverage, pass_id="pass-1"))
            save(root / "blind-b" / (stem + ".coverage.json"), dict(coverage, pass_id="pass-2"))
            summary = {"schema": "clear62-blind-scan-review/v1", "source_receipt_sha256": "a" * 64,
                       "sources": [{"source": source.name, "source_sha256": sha,
                                    "first_pass_sha256": a, "second_pass_sha256": b,
                                    "verification_sha256": v, "positions": 2, "sections": 1}],
                       "counts": {"positions": 2, "sections": 1, "supported_positions": 1,
                                  "ambiguous_positions": 1}}
            summary_sha = save(root / "review" / "summary.json", summary)
            receipt = {"schema": "issue55-san-mauro-jpg-receipts/v1",
                       "sources": [{"path": str(source), "sha256": sha, "bytes": len(source.read_bytes()),
                                    "http_status": 200, "content_type": "image/jpeg",
                                    "retrieved_at": "2026-01-01T00:00:00+00:00"}]}
            receipt_path = root / "receipt.json"
            receipt_sha = save(receipt_path, receipt)
            summary["source_receipt_sha256"] = receipt_sha
            summary_sha = save(root / "review" / "summary.json", summary)
            manifest = {"bundle_root": str(root), "receipt_path": str(receipt_path),
                        "source": source.name, "summary_sha256": summary_sha}
            with patch("scripts.scan_verification.TRUSTED_REVIEW_SHA256", summary_sha):
                result = replay_scan_source(manifest)
            self.assertEqual(2, len(result["document"]["positions"]))
            self.assertEqual(1, len(result["candidates"]))
            self.assertEqual("individual-result", result["candidates"][0]["evidence_role"])
            review["positions"][0]["accepted_reading"] = "forged"
            save(root / "review" / (stem + ".verification.json"), review)
            with patch("scripts.scan_verification.TRUSTED_REVIEW_SHA256", summary_sha):
                with self.assertRaisesRegex(ValueError, "review digest"):
                    replay_scan_source(manifest)
            summary["sources"][0]["verification_sha256"] = save(
                root / "review" / (stem + ".verification.json"), review)
            changed_summary_sha = save(root / "review" / "summary.json", summary)
            manifest["summary_sha256"] = changed_summary_sha
            with self.assertRaisesRegex(ValueError, "untrusted review digest"):
                replay_scan_source(manifest)
            with patch("scripts.scan_verification.TRUSTED_REVIEW_SHA256", changed_summary_sha):
                with self.assertRaisesRegex(ValueError, "stale or forged review"):
                    replay_scan_source(manifest)


if __name__ == "__main__":
    unittest.main()
