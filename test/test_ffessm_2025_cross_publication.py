import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/ffessm_2025_cross_publication.py"
DAY1, DAY2, CATEGORY = (character * 64 for character in "abc")


def row(source, ordinal, day, points=70, depth=70, card="Blanc", name="A Diver"):
    return {"source-sha256": source, "ref": {"job-id": source, "ordinal": ordinal},
            "day": day, "discipline": "FIM", "position": [{"page": 1, "line": 8 + ordinal}],
            "source-text": f"{name} {depth} {points} {card}",
            "parsed": {"source-name": name, "gender": "F", "discipline": "FIM",
                       "federation": "FFESSM", "nationality": "Française",
                       "realized-depth": depth, "final-points": points, "card": card,
                       "depth-penalty": depth - points, "plate-penalty": None if day else 0,
                       "announced-depth": depth, "unit": "m"}}


def fixture():
    return {"sources": [
        {"sha256": DAY1, "role": "daily", "event": "france-outdoor-2025-villefranche",
         "day": "2025-06-27", "title": "Résultats Eau Libre J1 - Championnat de France 2025 - Villefranche sur mer"},
        {"sha256": DAY2, "role": "daily", "event": "france-outdoor-2025-villefranche",
         "day": "2025-06-28", "title": "Résultats Eau Libre J2 - Championnat de France 2025 - Villefranche-sur-mer"},
        {"sha256": CATEGORY, "role": "category", "event": "france-outdoor-2025-villefranche",
         "day": None, "title": "Championnat de France Eau Libre 2025 - Villefranche-sur-Mer - Immersion Libre Femmes"}],
        "observations": [row(DAY1, 0, "2025-06-27"), row(CATEGORY, 0, None)]}


class CrossPublicationTest(unittest.TestCase):
    def run_cli(self, data):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.json"
            output = Path(directory) / "ledger.json"
            source.write_text(json.dumps(data))
            result = subprocess.run([sys.executable, str(SCRIPT), str(source), str(output)],
                                    capture_output=True, text=True)
            if result.returncode:
                return result, None
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return result, json.loads(output.read_text())

    def test_exact_cited_result_is_linked_and_replay_is_stable(self):
        data = fixture()
        result, ledger = self.run_cli(data)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, ledger["counts"]["same-result"])
        self.assertEqual(0, ledger["counts"]["distinct-session"])
        self.assertEqual(0, ledger["counts"]["unknown"])
        self.assertEqual(DAY1, ledger["edges"][0]["daily"]["source-sha256"])
        self.assertEqual(CATEGORY, ledger["edges"][0]["category"]["source-sha256"])
        self.assertEqual(ledger["ledger_sha256"], self.run_cli(data)[1]["ledger_sha256"])

    def test_same_athlete_different_day_and_performance_is_distinct(self):
        data = fixture()
        data["observations"].append(row(DAY2, 0, "2025-06-28", points=75, depth=75))
        result, ledger = self.run_cli(data)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, ledger["counts"]["same-result"])
        self.assertEqual(1, ledger["counts"]["distinct-session"])

    def test_same_name_and_value_on_both_days_is_unknown(self):
        data = fixture()
        data["observations"].append(row(DAY2, 0, "2025-06-28"))
        result, ledger = self.run_cli(data)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, ledger["counts"]["same-result"])
        self.assertEqual(2, ledger["counts"]["unknown"])

    def test_missing_event_context_does_not_make_a_same_result(self):
        data = fixture()
        data["sources"][2].pop("event")
        result, ledger = self.run_cli(data)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, ledger["counts"]["same-result"])
        self.assertEqual(1, ledger["counts"]["unknown"])

    def test_junior_category_without_daily_age_stays_unknown(self):
        data = fixture()
        data["sources"][2]["title"] += " Juniors"
        data["observations"][1]["parsed"]["category"] = "Femmes Juniors"
        result, ledger = self.run_cli(data)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, ledger["counts"]["same-result"])
        self.assertEqual("junior-category-unverified-in-daily-row", ledger["edges"][0]["reason"])

    def test_missing_daily_session_title_stays_unknown(self):
        data = fixture()
        data["sources"][0]["title"] = "Championnat de France 2025 - Villefranche sur mer"
        result, ledger = self.run_cli(data)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, ledger["counts"]["same-result"])
        self.assertEqual(1, ledger["counts"]["unknown"])

    def test_category_title_discipline_conflict_stays_unknown(self):
        data = fixture()
        data["sources"][2]["title"] = data["sources"][2]["title"].replace(
            "Immersion Libre", "Sans Palmes")
        result, ledger = self.run_cli(data)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, ledger["counts"]["same-result"])
        self.assertEqual(1, ledger["counts"]["unknown"])

    def test_same_name_and_performance_with_conflicting_affiliation_stays_unknown(self):
        for field, value in (("federation", "Other federation"),
                             ("nationality", "Italienne")):
            with self.subTest(field=field):
                data = fixture()
                data["observations"][0]["parsed"][field] = value
                result, ledger = self.run_cli(data)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(0, ledger["counts"]["same-result"])
                self.assertEqual(1, ledger["counts"]["unknown"])
                self.assertEqual("federation-or-nationality-conflict",
                                 ledger["edges"][0]["reason"])

    def test_missing_citation_is_rejected(self):
        data = fixture()
        data["observations"][1]["position"] = []
        result, _ = self.run_cli(data)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
