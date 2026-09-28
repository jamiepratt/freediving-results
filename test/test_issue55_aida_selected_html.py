"""Selected-date AIDA evidence packet from retained HTML response bytes."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/issue55_aida_selected_html.py'
HEADERS = ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline', 'OT',
           'AP', 'RP', 'Card', 'Points', 'Remarks')
LEGACY_HEADERS = ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline', 'Line',
                  'Official Top', 'AP', 'RP', 'Card', 'Points', 'Remarks')


def sample_html(rows=None, active='2025-08-30', selector='day_1', headers=HEADERS):
    rows = rows if rows is not None else [
        '<tr><td>1</td><td><a href="/Athletes/Profile-example">Ada &amp; Eve</a></td>'
        '<td>GER</td><td>F</td><td>CWTB</td><td>09:40</td><td>25 m</td>'
        '<td>24 m</td><td>YELLOW</td><td>19</td><td>Depth penalty</td></tr>']
    return (f'<html><li class="active"><a class="days" id="{selector}">{active}</a></li>'
            '<li><a class="days" id="day_2">2025-08-31</a></li>'
            '<table id="table_ajax"><thead><tr>'
            + ''.join(f'<th>{x}</th>' for x in headers)
            + '</tr></thead><tbody id="body_ajax">' + ''.join(rows)
            + '</tbody></table></html>')


class SelectedHtmlPacketTests(unittest.TestCase):
    def run_packet(self, html=None, receipt_change=None):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            raw = root / 'raw'
            raw.mkdir()
            source = raw / 'eventpage-4408-2025-08-30.html'
            body = (html if html is not None else sample_html()).encode('utf-8')
            source.write_bytes(body)
            receipt = {
                'schema': 'aida-selected-html-browser-receipt/v1',
                'requested_url': 'https://www.aidainternational.org/EventPage/4408',
                'final_url': 'https://www.aidainternational.org/EventPage/4408',
                'redirects': [], 'http_status': 200,
                'content_type': 'text/html; charset=UTF-8',
                'response_time': '2026-09-28T18:43:07Z',
                'selected_view': {'date': '2025-08-30', 'selector': 'day_1'},
                'body': {'path': 'raw/eventpage-4408-2025-08-30.html',
                         'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()},
                'source_citation': {'url': 'https://www.aidainternational.org/EventPage/4408',
                                    'selected_date': '2025-08-30',
                                    'table': 'table_ajax', 'tbody': 'body_ajax'},
            }
            if receipt_change:
                receipt_change(receipt)
            receipt_path = root / 'receipt.json'
            receipt_path.write_text(json.dumps(receipt))
            output = root / 'packet.json'
            result = subprocess.run([sys.executable, str(SCRIPT), str(source),
                                     str(receipt_path), str(output)], capture_output=True, text=True)
            packet = json.loads(output.read_text()) if output.exists() else None
            return result, packet

    def test_cites_every_selected_date_source_row_and_preserves_cell_evidence(self):
        result, packet = self.run_packet()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(packet['schema'], 'aida-selected-html-packet/v1')
        self.assertEqual(packet['summary']['source_positions'], 1)
        self.assertEqual(packet['summary']['parsed'], 1)
        self.assertEqual(packet['summary']['parse_unresolved'], 0)
        self.assertEqual(packet['summary']['review_unresolved'], 1)
        self.assertEqual(packet['summary']['observation_versions'], 0)
        self.assertIsNone(packet['summary']['confirmed_distinct_attempts'])
        row = packet['positions'][0]
        self.assertEqual(row['position'], {'table': 'table_ajax', 'table_number': 1,
                                            'tbody': 'body_ajax', 'row': 2, 'tbody_row': 1,
                                            'selector': 'day_1', 'date': '2025-08-30'})
        self.assertEqual(row['disposition'], 'parsed')
        self.assertEqual(row['review_status'], 'unreviewed')
        self.assertIn('Ada &amp; Eve', row['source_html'])
        self.assertEqual(row['cells']['Diver']['value'], 'Ada & Eve')
        self.assertIn('Ada &amp; Eve', row['cells']['Diver']['source_html'])
        self.assertEqual(row['cells']['RP']['value'], '24 m')
        self.assertEqual(row['cells']['Card']['value'], 'YELLOW')
        self.assertEqual(row['cells']['Remarks']['value'], 'Depth penalty')
        self.assertIsNone(row['penalty'])
        self.assertIsNone(row['category'])

    def test_cites_legacy_event_results_selected_date_and_twelve_cells(self):
        rows = ['<tr><td>1</td><td>Eva &amp; Max</td><td>GER</td><td>F</td>'
                '<td>CWT</td><td>Blue</td><td>30 m</td><td>29 m</td>'
                '<td>28 m</td><td>WHITE</td><td>25</td><td>Clean</td></tr>']
        url = 'https://www.aidainternational.org/Events/EventResults-4464'

        def legacy_receipt(receipt):
            receipt['requested_url'] = url
            receipt['final_url'] = url
            receipt['source_citation']['url'] = url

        result, packet = self.run_packet(
            html=sample_html(rows=rows, headers=LEGACY_HEADERS),
            receipt_change=legacy_receipt)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(packet['source']['url'], url)
        self.assertEqual(packet['summary']['source_positions'], 1)
        self.assertEqual(packet['summary']['parsed'], 1)
        self.assertEqual(packet['summary']['observation_versions'], 0)
        self.assertIsNone(packet['summary']['confirmed_distinct_attempts'])
        position = packet['positions'][0]
        self.assertEqual(position['position']['date'], '2025-08-30')
        self.assertEqual(position['cells']['Line']['value'], 'Blue')
        self.assertEqual(position['cells']['Official Top']['value'], '30 m')
        self.assertEqual(position['cells']['Remarks']['value'], 'Clean')
        self.assertIn('Eva &amp; Max', position['cells']['Diver']['source_html'])

    def test_rejects_receipt_hash_mismatch(self):
        result, packet = self.run_packet(receipt_change=lambda x: x['body'].update(sha256='0' * 64))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(packet)
        self.assertIn('sha256', result.stderr)

    def test_rejects_receipt_status_and_citation_url_mismatch(self):
        for change in (lambda x: x.update(http_status=403),
                       lambda x: x['source_citation'].update(url='https://example.org/EventPage/4408')):
            with self.subTest(change=change):
                result, packet = self.run_packet(receipt_change=change)
                self.assertNotEqual(result.returncode, 0)
                self.assertIsNone(packet)

    def test_rejects_wrong_active_date(self):
        result, packet = self.run_packet(html=sample_html(active='2025-08-31'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(packet)
        self.assertIn('active', result.stderr)

    def test_rejects_wrong_active_selector(self):
        result, packet = self.run_packet(html=sample_html(selector='day_2'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(packet)

    def test_rejects_truncated_table(self):
        result, packet = self.run_packet(html=sample_html()[:-16])
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(packet)

    def test_marks_wrong_width_row_unresolved_without_dropping_it(self):
        rows = ['<tr>' + ''.join(f'<td>{n}</td>' for n in range(11)) + '</tr>',
                '<tr><td>2</td><td>incomplete</td></tr>']
        result, packet = self.run_packet(html=sample_html(rows=rows))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(packet['summary']['source_positions'], 2)
        self.assertEqual(packet['summary']['parsed'], 1)
        self.assertEqual(packet['summary']['parse_unresolved'], 1)
        self.assertEqual(packet['summary']['review_unresolved'], 2)
        self.assertEqual(packet['positions'][1]['disposition'], 'unresolved')
        self.assertEqual(packet['positions'][1]['position']['row'], 3)
        self.assertEqual(packet['positions'][1]['position']['tbody_row'], 2)


if __name__ == '__main__':
    unittest.main()
