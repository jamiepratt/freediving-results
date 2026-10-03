import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from scripts.aida_snapshot_observations import load_source_observations


class AidaSnapshotObservationsTest(unittest.TestCase):
    def test_exact_original_packet_and_snapshot_bind_without_pg_or_attempt_claims(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            source = root / 'raw' / 'source.html'
            source.parent.mkdir()
            source.write_text('<html><li class="active"><a class="days" id="day_1">2025-08-30</a></li>'
                              '<table id="table_ajax"><thead><tr>'
                              + ''.join(f'<th>{h}</th>' for h in
                                        ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline',
                                         'OT', 'AP', 'RP', 'Card', 'Points', 'Remarks'))
                              + '</tr></thead><tbody id="body_ajax"><tr>'
                              + ''.join(f'<td>{v}</td>' for v in
                                        ('1', 'Synthetic Athlete', 'GER', 'F', 'CWTB', '09:40',
                                         '25 m', '24 m', 'YELLOW', '19', 'Note'))
                              + '</tr></tbody></table></html>')
            body = source.read_bytes()
            url = 'https://www.aidainternational.org/EventPage/4408'
            receipt = {'schema': 'aida-selected-html-browser-receipt/v1',
                       'requested_url': url, 'final_url': url, 'http_status': 200,
                       'content_type': 'text/html', 'response_time': '2026-09-28T18:43:07Z',
                       'selected_view': {'date': '2025-08-30', 'selector': 'day_1'},
                       'body': {'path': 'raw/source.html', 'bytes': len(body),
                                'sha256': hashlib.sha256(body).hexdigest()},
                       'source_citation': {'url': url, 'selected_date': '2025-08-30',
                                           'table': 'table_ajax', 'tbody': 'body_ajax'}}
            receipt_path = root / 'receipt.json'
            receipt_path.write_text(json.dumps(receipt))
            from scripts.issue55_aida_selected_html import build
            packet = build(source, receipt_path)
            packet_path = root / 'packet.json'
            packet_path.write_text(json.dumps(packet))
            name = 'aida-4408-2025-08-30'
            record_id = hashlib.sha256(f'{name}:positions[0]'.encode()).hexdigest()
            db = sqlite3.connect(root / 'snapshot.sqlite')
            db.execute('CREATE TABLE records (record_id TEXT, source_name TEXT, collection TEXT, '
                       'record_path TEXT, kind TEXT, raw_json TEXT, citation_json TEXT, '
                       'source_object_id TEXT, event_date TEXT, parser_version TEXT, '
                       'observation_version TEXT, event_name TEXT, session TEXT, category TEXT)')
            row = packet['positions'][0]
            db.execute('INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                       (record_id, name, 'positions', 'positions[0]', 'candidate_position',
                        json.dumps(row), json.dumps(row['position']),
                        'sha256:' + packet['source']['sha256'], '2025-08-30',
                        None, None, None, None, None))
            db.commit()
            db.close()
            digest = hashlib.sha256((root / 'snapshot.sqlite').read_bytes()).hexdigest()
            (root / 'manifest.json').write_text(json.dumps({
                'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': digest,
                'inputs': {name: {'source_schema': 'aida-selected-html-packet/v1',
                                  'path': str(packet_path),
                                  'sha256': hashlib.sha256(packet_path.read_bytes()).hexdigest(),
                                  'collections': {'positions': 1}}}}))
            result = load_source_observations(root, [name])
            self.assertEqual(1, result['summary']['supported'])
            self.assertEqual([], result['gaps'])
            observed = result['observations'][0]
            self.assertEqual(record_id, observed['snapshot_record_id'])
            self.assertEqual('Synthetic Athlete', observed['source_fields']['name'])
            self.assertEqual('GER', observed['source_fields']['representation_raw'])
            self.assertEqual('2025-08-30', observed['event_date'])
            self.assertIsNone(observed['event_name'])
            self.assertIsNone(observed['session'])
            self.assertIsNone(observed['category'])
            self.assertIsNone(observed['pg_observation_ref'])
            self.assertIsNone(observed['confirmed_attempt_id'])
            self.assertIsNone(observed['approved_athlete_id'])
            self.assertEqual(observed['observation_version'],
                             load_source_observations(root, [name])['observations'][0]['observation_version'])
            original_packet = packet_path.read_bytes()
            packet['positions'][0]['cells']['Diver']['value'] = 'Altered'
            packet_path.write_text(json.dumps(packet))
            with self.assertRaisesRegex(ValueError, 'packet hash mismatch'):
                load_source_observations(root, [name])
            packet_path.write_bytes(original_packet)
            source.write_text(source.read_text().replace('Synthetic Athlete', 'Changed Athlete'))
            with self.assertRaisesRegex(ValueError, 'source (bytes|sha256) differ'):
                load_source_observations(root, [name])
            source.write_bytes(body)
            packet_path.unlink()
            result = load_source_observations(root, [name])
            self.assertEqual(0, result['summary']['supported'])
            self.assertEqual([{'snapshot_record_id': record_id, 'source_name': name,
                               'citation': row['position'], 'event_date': '2025-08-30',
                               'source_object_id': 'sha256:' + packet['source']['sha256'],
                               'reason': 'retained-packet-missing'}], result['gaps'])


if __name__ == '__main__':
    unittest.main()
