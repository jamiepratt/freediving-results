import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from scripts.cmas_microplus_snapshot_observations import load_source_observations


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixture(root, schema='cmas-microplus-private-census/v1', overrides=None):
    source = root / 'unit-17-results.json'
    raw_row = {'DCCmpID': 28, 'ResID': 91, 'UtID': 17, 'ParID': 7,
               'EvID': 8, 'PhID': 9, 'EvStartDate': '2026-08-07T12:00:00',
               'ParPrintName': 'Synthetic Diver', 'EvShortDescr': 'CWT',
               'AGCodeDescr': 'SENM', 'ResResultFinal': '42', 'ResPenality': None}
    raw_row.update(overrides or {})
    raw = json.dumps([raw_row]).encode()
    source.write_bytes(raw)
    url = 'https://cmas-api.microplustimingservices.com/api/units/17/results'
    (root / 'unit-17-results.receipt.json').write_text(json.dumps({
        'status': 200, 'sha256': sha(raw), 'bytes': len(raw), 'final_url': url}))
    citation = {'url': url, 'source_sha256': sha(raw), 'json_pointer': '/0'}
    row = {'id': 'cmas-microplus:28:result:91', 'source_object_id': 'sha256:' + sha(raw),
           'event_name': 'Synthetic Cup', 'event_date': '2026-08-07',
           'discipline': 'CWT', 'category': 'SENM', 'review_status': 'unreviewed',
           'disposition': 'api_transport_result_row', 'citation': citation,
           'alternate_citations': [], 'raw_fields': raw_row}
    packet = {'schema': schema, 'positions': [row], 'sources': [{
        'id': 'sha256:' + sha(raw), 'kind': 'unit_results', 'competition_id': 28,
        'unit_id': 17, 'url': url, 'sha256': sha(raw), 'bytes': len(raw), 'row_count': 1}]}
    packet_path = root / 'packet.json'
    packet_path.write_text(json.dumps(packet))
    name = 'cmas-microplus-2026'
    record_id = sha(f'{name}:positions[0]'.encode())
    db = sqlite3.connect(root / 'snapshot.sqlite')
    db.execute('CREATE TABLE records (record_id TEXT, source_name TEXT, collection TEXT, '
               'record_path TEXT, kind TEXT, source_id TEXT, source_object_id TEXT, '
               'event_name TEXT, event_date TEXT, session TEXT, discipline TEXT, category TEXT, '
               'parser_version TEXT, observation_version TEXT, review_status TEXT, '
               'raw_json TEXT, citation_json TEXT)')
    db.execute('INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
               (record_id, name, 'positions', 'positions[0]', 'candidate_position', row['id'],
                row['source_object_id'], row['event_name'], row['event_date'], None,
                row['discipline'], row['category'], None, None, 'unreviewed',
                json.dumps(row), json.dumps(citation)))
    db.commit()
    db.close()
    (root / 'manifest.json').write_text(json.dumps({
        'schema': 'unified-evidence-snapshot/v1',
        'snapshot_sha256': sha((root / 'snapshot.sqlite').read_bytes()),
        'inputs': {name: {'source_schema': schema, 'path': str(packet_path),
                          'sha256': sha(packet_path.read_bytes()),
                          'collections': {'positions': 1}}}}))
    return name, record_id, row


class MicroplusSnapshotObservationsTest(unittest.TestCase):
    def test_grouped_and_individual_views_keep_their_own_source_hashes(self):
        from scripts.cmas_microplus_snapshot_observations import load_attempt_evidence
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, record_id, row = fixture(root)
            alias_url = row['citation']['url'].replace('/17/', '/16/')
            payload = json.dumps([{'unrelated': 'retained transport row'}, row['raw_fields']]).encode()
            (root / 'unit-16-results.json').write_bytes(payload)
            (root / 'unit-16-results.receipt.json').write_text(json.dumps({
                'status': 200, 'sha256': sha(payload), 'bytes': len(payload), 'final_url': alias_url}))
            alias = {'url': alias_url, 'json_pointer': '/1', 'source_sha256': sha(payload)}
            row['alternate_citations'] = [alias]
            packet = json.loads((root / 'packet.json').read_text())
            packet['positions'][0] = row
            packet['sources'].append(dict(packet['sources'][0], unit_id=16, url=alias_url,
                                           id='sha256:' + sha(payload), sha256=sha(payload),
                                           bytes=len(payload), row_count=2))
            (root / 'packet.json').write_text(json.dumps(packet))
            with sqlite3.connect(root / 'snapshot.sqlite') as db:
                db.execute('UPDATE records SET raw_json=?', (json.dumps(row),))
            manifest = json.loads((root / 'manifest.json').read_text())
            manifest['snapshot_sha256'] = sha((root / 'snapshot.sqlite').read_bytes())
            manifest['inputs'][name]['sha256'] = sha((root / 'packet.json').read_bytes())
            (root / 'manifest.json').write_text(json.dumps(manifest))

            result = load_attempt_evidence(root, [name], record_ids=[record_id], source_dir=root)
            self.assertEqual(2, result['summary']['source_objects'])
            for version in result['evidence']['observation-versions']:
                citation = version['scope-evidence']['citation']
                self.assertEqual(citation['source_sha256'], version['scope-evidence']['source-sha256'])
                self.assertEqual(citation['source_sha256'], version['observation-revision']['source_sha256'])
                self.assertEqual(citation, version['observation-revision']['citation'])

    def test_attempt_evidence_rejects_missing_scope_or_unresolved_result(self):
        from scripts.cmas_microplus_snapshot_observations import load_attempt_evidence
        for changes in ({'UtID': None}, {'PhID': None}, {'ParID': 0},
                        {'EvShortDescr': ''}, {'ResResultFinal': None},
                        {'ResResultFinal': 'NaN'}, {'ResResultFinal': '0'}):
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                name, record_id, _ = fixture(root, overrides=changes)
                with self.assertRaises(ValueError):
                    load_attempt_evidence(root, [name], record_ids=[record_id], source_dir=root)

    def test_attempt_evidence_requires_exact_selected_snapshot_records(self):
        from scripts.cmas_microplus_snapshot_observations import load_attempt_evidence
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, record_id, _ = fixture(root)
            for selected in ([], [record_id, record_id], ['missing-record']):
                with self.subTest(selected=selected), self.assertRaises(ValueError):
                    load_attempt_evidence(root, [name], record_ids=selected, source_dir=root)

    def test_alternate_endpoint_uses_payload_attempt_ids_and_keeps_both_citations(self):
        from scripts.cmas_microplus_snapshot_observations import load_attempt_evidence
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, record_id, row = fixture(root)
            alias_url = row['citation']['url'].replace('/17/', '/16/')
            alias = dict(row['citation'], url=alias_url)
            row['alternate_citations'] = [alias]
            (root / 'unit-16-results.json').write_bytes((root / 'unit-17-results.json').read_bytes())
            receipt = json.loads((root / 'unit-17-results.receipt.json').read_text())
            (root / 'unit-16-results.receipt.json').write_text(json.dumps(dict(receipt, final_url=alias_url)))
            packet = json.loads((root / 'packet.json').read_text())
            packet['positions'][0] = row
            packet['sources'].append(dict(packet['sources'][0], unit_id=16, url=alias_url))
            (root / 'packet.json').write_text(json.dumps(packet))
            with sqlite3.connect(root / 'snapshot.sqlite') as db:
                db.execute('UPDATE records SET raw_json=?', (json.dumps(row),))
            manifest = json.loads((root / 'manifest.json').read_text())
            manifest['snapshot_sha256'] = sha((root / 'snapshot.sqlite').read_bytes())
            manifest['inputs'][name]['sha256'] = sha((root / 'packet.json').read_bytes())
            (root / 'manifest.json').write_text(json.dumps(manifest))

            result = load_attempt_evidence(root, [name], record_ids=[record_id], source_dir=root)
            self.assertEqual(1, result['summary']['snapshot_positions'])
            self.assertEqual(2, result['summary']['cited_view_observations'])
            self.assertEqual(1, result['summary']['source_objects'])
            self.assertIsNone(result['summary']['confirmed_distinct_attempts'])
            versions = result['evidence']['observation-versions']
            self.assertEqual([17, 17], [v['scope']['session'] for v in versions])
            self.assertEqual([91, 91], [v['scope']['attempt'] for v in versions])
            self.assertEqual({alias_url, row['citation']['url']},
                             {p['locator']['url'] for p in result['evidence']['positions']})
            self.assertEqual([record_id, record_id], [v['snapshot-record-id'] for v in versions])
            self.assertEqual([16, 17], sorted(s['transport-unit-id'] for s in result['evidence']['sources']))

    def test_original_json_and_snapshot_bind_without_attempt_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, record_id, row = fixture(root)
            result = load_source_observations(root, [name], source_dir=root)
            self.assertEqual(1, result['summary']['supported'])
            observed = result['observations'][0]
            self.assertEqual(record_id, observed['snapshot_record_id'])
            self.assertEqual(row['citation'], observed['citation'])
            self.assertEqual(7, observed['source_fields']['athlete_id'])
            self.assertEqual(91, observed['source_fields']['result_id'])
            self.assertIsNone(observed['confirmed_attempt_id'])
            self.assertIsNone(observed['approved_athlete_id'])
            self.assertIsNone(observed['pg_observation_ref'])

    def test_changed_source_bytes_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, _, _ = fixture(root)
            (root / 'unit-17-results.json').write_text('[{}]')
            with self.assertRaisesRegex(ValueError, 'source|receipt'):
                load_source_observations(root, [name], source_dir=root)

    def test_v2_requires_replay_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, _, _ = fixture(root, 'cmas-microplus-private-census/v2')
            with self.assertRaisesRegex(ValueError, 'initial packet'):
                load_source_observations(root, [name], source_dir=root)


if __name__ == '__main__':
    unittest.main()
