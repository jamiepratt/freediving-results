"""Refresh decisions over dated, retained publisher evidence."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from discovery_refresh import plan_refresh, record_publisher_result, retry_delay


class RefreshPolicyTest(unittest.TestCase):
    def test_indexes_are_checked_each_new_discovery_run(self):
        decision = plan_refresh("index", "2026-10-02", last_publisher_check="2026-10-02")
        self.assertTrue(decision["due"])
        self.assertEqual("index_each_run", decision["reason"])

    def test_recent_and_provisional_sources_are_checked_before_old_stable_sources(self):
        old = plan_refresh("result", "2026-10-02", publication_date="2020-01-01",
                           last_publisher_check="2026-09-22")
        recent = plan_refresh("result", "2026-10-02", publication_date="2026-09-01",
                              last_publisher_check="2026-09-22")
        provisional = plan_refresh("result", "2026-10-02", publication_date="2020-01-01",
                                   provisional=True, last_publisher_check="2026-09-30")
        self.assertFalse(old["due"])
        self.assertTrue(recent["due"])
        self.assertTrue(provisional["due"])
        custom = {"schema": "discovery-refresh-policy/v1", "recent_window_days": 365,
                  "recent_interval_days": 14, "provisional_interval_days": 4,
                  "stable_interval_days": 180, "max_attempts": 2,
                  "retry_base_seconds": 3}
        self.assertFalse(plan_refresh("result", "2026-10-02", publication_date="2026-09-01",
                                      last_publisher_check="2026-09-22", policy=custom)["due"])

    def test_hash_integrity_does_not_claim_a_publisher_check(self):
        decision = plan_refresh("result", "2026-10-02", publication_date="2020-01-01",
                                last_publisher_check=None)
        self.assertTrue(decision["due"])
        self.assertEqual("never_checked", decision["reason"])
        self.assertTrue(decision["refresh"])

    def test_same_bytes_and_304_keep_version_but_advance_publisher_check(self):
        first = record_publisher_result(None, checked_at="2026-10-01T12:00:00+00:00",
                                        status=200, sha256="a" * 64)
        same = record_publisher_result(first, checked_at="2026-10-02T12:00:00+00:00",
                                       status=200, sha256="a" * 64)
        self.assertEqual(["a" * 64], same["versions"])
        self.assertEqual("unchanged_bytes", same["outcome"])
        not_modified = record_publisher_result(same, checked_at="2026-10-03T12:00:00+00:00",
                                               status=304)
        self.assertEqual(["a" * 64], not_modified["versions"])
        self.assertEqual("not_modified", not_modified["outcome"])
        self.assertEqual("2026-10-03T12:00:00+00:00", not_modified["last_publisher_check"])
        changed = record_publisher_result(not_modified, checked_at="2026-10-04T12:00:00+00:00",
                                          status=200, sha256="b" * 64)
        self.assertEqual(["a" * 64, "b" * 64], changed["versions"])
        self.assertEqual("changed_bytes", changed["outcome"])

    def test_missing_and_forbidden_remain_explicit_gaps(self):
        first = record_publisher_result(None, checked_at="2026-10-01T12:00:00+00:00",
                                        status=200, sha256="a" * 64)
        for status in (403, 404):
            with self.subTest(status=status):
                gap = record_publisher_result(first, checked_at="2026-10-02T12:00:00+00:00",
                                              status=status, gap_reason="permanent_status")
                self.assertEqual("gap", gap["outcome"])
                self.assertEqual(status, gap["gap"]["status"])
                self.assertEqual(["a" * 64], gap["versions"])

    def test_retry_budget_has_bounded_backoff(self):
        self.assertEqual(0, retry_delay(1))
        self.assertEqual(2, retry_delay(2))
        self.assertEqual(4, retry_delay(3))
        self.assertIsNone(retry_delay(4))


if __name__ == "__main__":
    unittest.main()
