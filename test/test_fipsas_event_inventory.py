import json
import sys
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
