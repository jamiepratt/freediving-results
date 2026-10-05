import json
import sys
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from fipsas_event_inventory import inventory_month
from fipsas_calendar import CalendarError


class FipsasEventInventoryTest(unittest.TestCase):
    def test_distinct_discipline_cards_share_one_result_source(self):
        pdf = "https://fipsas.it/wp-content/uploads/2026/01/Classifica_Cagliari.pdf"
        rules = "https://fipsas.it/wp-content/uploads/2026/01/Regolamento_Cagliari.pdf"
        cards = [
            {"ID": 10, "event_id": 10, "event_title": "Apnea Dinamica con Attrezzi: Cagliari", "event_start_unix": 1768726800},
            {"ID": 11, "event_id": 11, "event_title": "Apnea Dinamica senza Attrezzi: Cagliari", "event_start_unix": 1768726800},
        ]
        html = f'''<div data-event_id="10"><div><a itemprop="url" href="https://fipsas.it/events/cagliari-con/"></a>
            <p class="evo_location_name">Cagliari</p>
            <p><a href="{rules}">Regolamento</a></p><p><a href="{pdf}"><span>Classifica</span></a></p></div></div>
            <div data-event_id="11"><a itemprop="url" href="https://fipsas.it/events/cagliari-senza/"></a>
            <p><a href="{pdf}">Classifica</a></p></div>'''
        response = {"status": "GOOD", "json": cards, "html": html,
                    "cal_month_title": "gennaio 2026", "SC": {"fixed_year": "2026", "fixed_month": "1"}}
        inventory = inventory_month(json.dumps(response).encode(), 2026, 1)
        self.assertEqual(2, len(inventory["cards"]))
        self.assertEqual([10, 11], [card["event_id"] for card in inventory["cards"]])
        self.assertEqual("2026-01-18", inventory["cards"][0]["date"])
        self.assertEqual("Apnea Dinamica con Attrezzi", inventory["cards"][0]["discipline"])
        self.assertEqual("https://fipsas.it/events/cagliari-con/", inventory["cards"][0]["event_url"])
        self.assertEqual("Cagliari", inventory["cards"][0]["location"])
        self.assertIsNone(inventory["cards"][1]["location"])
        self.assertEqual([pdf], inventory["result_urls"])
        self.assertEqual([pdf], inventory["cards"][0]["result_urls"])
        self.assertEqual([rules], inventory["cards"][0]["rules_urls"])
        self.assertEqual([pdf], inventory["cards"][1]["result_urls"])

    def test_result_link_on_other_host_is_rejected(self):
        response = {"status": "GOOD", "json": [{"ID": 10, "event_id": 10,
                    "event_title": "Apnea Statica: Test", "event_start_unix": 1768726800}],
                    "html": '<div data-event_id="10"><a itemprop="url" href="https://fipsas.it/events/test/"></a>'
                            '<a href="https://example.org/Classifica.pdf">Classifica</a></div>',
                    "cal_month_title": "gennaio 2026", "SC": {"fixed_year": "2026", "fixed_month": "1"}}
        with self.assertRaisesRegex(CalendarError, "nonofficial FIPSAS URL"):
            inventory_month(json.dumps(response).encode(), 2026, 1)

    def test_title_without_discipline_separator_keeps_discipline_unknown(self):
        response = {"status": "GOOD", "json": [{"ID": 10, "event_id": 10,
                    "event_title": "4° Trofeo One Wave", "event_start_unix": 1768726800}],
                    "html": '<div data-event_id="10"><a itemprop="url" href="https://fipsas.it/events/one-wave/"></a></div>',
                    "cal_month_title": "gennaio 2026", "SC": {"fixed_year": "2026", "fixed_month": "1"}}
        card = inventory_month(json.dumps(response).encode(), 2026, 1)["cards"][0]
        self.assertIsNone(card["discipline"])

    def test_card_retains_publisher_end_date(self):
        zone = ZoneInfo("Europe/Rome")
        response = {"status": "GOOD", "json": [{"ID": 10, "event_id": 10,
                    "event_title": "Depth event",
                    "event_start_unix": int(datetime(2026, 10, 23, 9, tzinfo=zone).timestamp()),
                    "event_end_unix": int(datetime(2026, 10, 25, 18, tzinfo=zone).timestamp())}],
                    "html": '<div data-event_id="10"><a itemprop="url" href="https://fipsas.it/events/depth/"></a></div>',
                    "cal_month_title": "ottobre 2026", "SC": {"fixed_year": "2026", "fixed_month": "10"}}
        card = inventory_month(json.dumps(response).encode(), 2026, 10)["cards"][0]
        self.assertEqual("2026-10-23", card["date"])
        self.assertEqual("2026-10-25", card["date_to"])

    def test_all_day_card_uses_displayed_last_day(self):
        zone = ZoneInfo("Europe/Rome")
        response = {"status": "GOOD", "json": [{"ID": 10, "event_id": 10,
                    "event_title": "Capri",
                    "event_start_unix": int(datetime(2026, 10, 23, 2, tzinfo=zone).timestamp()),
                    "event_end_unix": int(datetime(2026, 10, 26, 0, 59, tzinfo=zone).timestamp())}],
                    "html": '<div data-event_id="10"><a itemprop="url" href="https://fipsas.it/events/capri/"></a>'
                            '<span class="evo_eventcard_time_t">23/10/2026 - 25/10/2026 (Tutto il giorno)</span></div>',
                    "cal_month_title": "ottobre 2026", "SC": {"fixed_year": "2026", "fixed_month": "10"}}
        card = inventory_month(json.dumps(response).encode(), 2026, 10)["cards"][0]
        self.assertEqual("2026-10-25", card["date_to"])


if __name__ == "__main__":
    unittest.main()
