"""Public checkpoint contract for bounded result discovery."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from discovery_frontier import DiscoveryFrontier


SCOPE = {
    "federation": "example",
    "years": [2025, 2026],
    "source_families": ["results", "rankings"],
    "routes": ["calendar", "annual-index"],
    "cutoff": "2026-10-02",
}


class FrontierCheckpointTest(unittest.TestCase):
    def test_unchecked_route_and_lead_prevent_completion_after_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "frontier.json"
            frontier = DiscoveryFrontier.load(path, SCOPE, request_budget=1)
            key = frontier.add_lead(
                "https://example.test/2026/results.pdf", route="calendar",
                year=2026, source_family="results",
                evidence={"citation": "calendar row 4"})
            frontier.mark_route("calendar", evidence={"checked_at": "2026-10-02T12:00:00Z"})
            self.assertEqual(frontier.summary()["state"], "checkpoint")
            frontier.save()

            resumed = DiscoveryFrontier.load(path, SCOPE, request_budget=1)
            self.assertEqual(resumed.summary()["unchecked_leads"], [key])
            resumed.record_disposition(key, "acquired", evidence={"receipt": "sha256:abc"})
            self.assertEqual(resumed.summary()["state"], "checkpoint")
            resumed.mark_route("annual-index", evidence={"checked_at": "2026-10-02T12:01:00Z"})
            self.assertEqual(resumed.summary()["state"], "complete")

    def test_overlapping_edges_share_one_lead_and_keep_name_ranking_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            frontier = DiscoveryFrontier.load(Path(directory) / "frontier.json", SCOPE)
            name = {"native_script": "Κατερίνα", "romanized": "Katerina",
                    "publisher_correspondence": "publisher row 12"}
            ranking = {"period": "2026 season", "category": "women",
                       "rules_reference": "publisher rules page 2"}
            key = frontier.add_lead(
                "https://example.test/2026/ranking.pdf", route="calendar", year=2026,
                source_family="rankings", evidence={"citation": "calendar row 8"},
                metadata={"name_evidence": [name], "ranking_evidence": [ranking]})
            again = frontier.add_lead(
                "https://example.test/2026/ranking.pdf", route="annual-index", year=2026,
                source_family="rankings", evidence={"citation": "annual index row 3"})
            self.assertEqual(key, again)
            self.assertEqual(frontier.summary()["leads"], 1)
            self.assertEqual(len(frontier.leads[key]["edges"]), 2)
            self.assertEqual(frontier.leads[key]["metadata"]["name_evidence"], [name])
            self.assertEqual(frontier.leads[key]["metadata"]["ranking_evidence"], [ranking])

    def test_same_url_with_distinct_selected_day_remains_two_views(self):
        with tempfile.TemporaryDirectory() as directory:
            frontier = DiscoveryFrontier.load(Path(directory) / "frontier.json", SCOPE)
            first = frontier.add_lead(
                "https://example.test/results", route="calendar", year=2026,
                source_family="results", evidence={"citation": "day 1"},
                metadata={"context": {"selected_date": "2026-06-01"}})
            second = frontier.add_lead(
                "https://example.test/results", route="calendar", year=2026,
                source_family="results", evidence={"citation": "day 2"},
                metadata={"context": {"selected_date": "2026-06-02"}})
            self.assertNotEqual(first, second)
            self.assertEqual(frontier.summary()["leads"], 2)

    def test_budget_exhaustion_resumes_with_explicit_unsupported_scope_gap(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "frontier.json"
            frontier = DiscoveryFrontier.load(path, SCOPE, request_budget=1)
            self.assertTrue(frontier.spend_request())
            self.assertFalse(frontier.spend_request())
            frontier.mark_route("calendar", status="gap", evidence={
                "reason": "unsupported_year", "year": 2024})
            key = frontier.add_lead(
                "https://example.test/2024/results.pdf", route="calendar", year=2024,
                source_family="results", evidence={"citation": "calendar 2024 link"})
            self.assertEqual(frontier.leads[key]["disposition"]["status"], "out_of_scope")
            self.assertEqual(frontier.summary()["state"], "checkpoint")
            resumed = DiscoveryFrontier.load(path, SCOPE, request_budget=1)
            self.assertEqual(resumed.summary()["requests"], 1)
            self.assertTrue(resumed.spend_request())
            resumed.mark_route("annual-index", status="gap", evidence={
                "reason": "unsupported_source_family", "family": "workbook"})
            summary = resumed.summary()
            self.assertEqual(summary["state"], "complete")
            self.assertEqual(len(summary["gaps"]), 2)
            self.assertEqual(summary["requests"], 2)


if __name__ == "__main__":
    unittest.main()
