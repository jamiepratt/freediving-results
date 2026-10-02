import unittest
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
        bad[1]["region_px"] = {"x1": 2, "y1": 13, "x2": 10, "y2": 22}
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


if __name__ == "__main__":
    unittest.main()
