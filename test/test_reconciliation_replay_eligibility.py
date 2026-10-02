import unittest

from scripts.reconciliation_replay_eligibility import replay_blockers


class ReplayEligibilityTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = {"manifest_sha256": "a" * 64, "sqlite_sha256": "b" * 64}

    def test_unbound_snapshot_cannot_replay(self):
        self.assertEqual(replay_blockers(self.snapshot, None), [
            "missing-observation-store-revision",
            "missing-decision-store-revision",
            "missing-decision-api-mapping",
        ])

    def test_stale_or_incomplete_binding_cannot_replay(self):
        binding = {
            "snapshot": {"manifest_sha256": "c" * 64, "sqlite_sha256": "b" * 64},
            "observation_store": {"revision": "obs-1", "sha256": "d" * 64},
            "decision_store": {"revision": 0, "sha256": "e" * 64},
            "decision_api_mapping": {"identity": {"observation_id": "record_id"}},
        }
        self.assertEqual(replay_blockers(self.snapshot, binding), [
            "snapshot-binding-mismatch", "missing-decision-api-mapping",
        ])

    def test_complete_structural_binding_has_no_preflight_blocker(self):
        binding = {
            "snapshot": self.snapshot,
            "observation_store": {"revision": "obs-1", "sha256": "d" * 64},
            "decision_store": {"revision": 0, "sha256": "e" * 64},
            "decision_api_mapping": {
                "identity": {key: key for key in
                             ("observation-id", "source-name", "parse-status", "citation")},
                "attempt": {key: key for key in
                            ("sources", "positions", "observation-versions")},
                "flow": {key: key for key in ("decisions", "config", "policy")},
            },
        }
        self.assertEqual(replay_blockers(self.snapshot, binding), [])


if __name__ == "__main__":
    unittest.main()
