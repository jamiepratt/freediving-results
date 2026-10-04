import hashlib
import json
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from scripts.aida_snapshot_observations import load_source_observations
from scripts.owner_snapshot_binding import load_verified_bindings
from scripts.owner_decision_store import ConflictError, DecisionStore
from scripts.owner_decision_export_adapter import register_verified_export


def source_fixture(root, heading, url, diver='Synthetic Athlete'):
    source = root / 'raw' / 'source.html'
    source.parent.mkdir()
    source.write_text('<html>' + heading +
                      '<li class="active"><a class="days" id="day_1">2025-08-30</a></li>'
                      '<table id="table_ajax"><thead><tr>' +
                      ''.join(f'<th>{h}</th>' for h in
                              ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline',
                               'OT', 'AP', 'RP', 'Card', 'Points', 'Remarks')) +
                      '</tr></thead><tbody id="body_ajax"><tr>' +
                      ''.join(f'<td>{v}</td>' for v in
                              ('1', diver, 'GER', 'F', 'CWTB', '09:40',
                               '25 m', '24 m', 'YELLOW', '19', 'Note')) +
                      '</tr></tbody></table></html>')
    body = source.read_bytes()
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
    name = 'aida-synthetic-2025-08-30'
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
    return name, packet


class AidaSnapshotObservationsTest(unittest.TestCase):
    def test_cited_aida_profile_is_a_scoped_person_id(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile = '123e4567-e89b-12d3-a456-426614174000'
            name, _ = source_fixture(
                root, '', 'https://www.aidainternational.org/EventPage/4408',
                f'<a href="https://www.aidainternational.org/Athletes/Profile-{profile}">Synthetic Athlete</a>')
            row = load_source_observations(root, [name])['observations'][0]
            expected = {'scope': 'AIDA', 'id': profile, 'id_kind': 'person',
                        'href': f'https://www.aidainternational.org/Athletes/Profile-{profile}'}
            self.assertEqual(expected, row['source_fields']['publisher_person'])
            self.assertEqual(expected, row['source_observation_ref']['publisher_person'])
            self.assertEqual('aida-snapshot-observation/3', row['adapter_version'])

    def test_missing_ambiguous_and_conflicting_profiles_are_not_person_evidence(self):
        profile = '123e4567-e89b-12d3-a456-426614174000'
        href = f'/Athletes/Profile-{profile}'
        for diver in ('Synthetic Athlete',
                      f'<a href="{href}">Synthetic Athlete</a><a href="{href}">Synthetic Athlete</a>',
                      f'<a href="{href}">Different Person</a> Synthetic Athlete',
                      '<a href="https://elsewhere.example/Athletes/Profile-' + profile + '">Synthetic Athlete</a>',
                      '<a href="/Athletes/Profile-invalid">Synthetic Athlete</a>'):
            with self.subTest(diver=diver), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                name, _ = source_fixture(
                    root, '', 'https://www.aidainternational.org/EventPage/4408', diver)
                row = load_source_observations(root, [name])['observations'][0]
                self.assertIsNone(row['source_fields']['publisher_person'])
                self.assertNotIn('publisher_person', row['source_observation_ref'])

    def test_missing_historical_packet_can_be_recovered_at_a_new_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, packet = source_fixture(root, '<div class="event-title--description">Synthetic Depth Open</div>',
                                          'https://www.aidainternational.org/EventPage/4408')
            original_packet = root / 'packet.json'
            original_hash = hashlib.sha256(original_packet.read_bytes()).hexdigest()
            frozen_manifest = (root / 'manifest.json').read_bytes()
            recovered = root / 'recovered'
            recovered.mkdir()
            shutil.copytree(root / 'raw', recovered / 'raw')
            shutil.copy2(root / 'receipt.json', recovered / 'receipt.json')
            object_path = root / 'historical-packet-object'
            shutil.copy2(original_packet, object_path)
            (recovered / 'packet.json').symlink_to(object_path)
            original_packet.unlink()

            self.assertEqual(1, len(load_source_observations(root, [name])['gaps']))
            result = load_source_observations(
                root, [name], recovered_packet_paths={name: recovered / 'packet.json'})
            self.assertEqual({'supported': 1, 'source_gaps': 0,
                              'confirmed_distinct_attempts': None,
                              'approved_athletes': None}, result['summary'])
            self.assertEqual(packet['positions'][0]['position'], result['observations'][0]['citation'])
            self.assertEqual(original_hash, result['observations'][0]['packet_sha256'])
            reference = result['observations'][0]['source_observation_ref']
            with self.assertRaisesRegex(ValueError, 'source observation revision differs'):
                load_verified_bindings(
                    root, [{'evidence_id': 'recovered-row', 'source_observation_ref': reference}],
                    [reference])
            binding = load_verified_bindings(
                root, [{'evidence_id': 'recovered-row', 'source_observation_ref': reference}],
                [reference], recovered_packet_paths={name: recovered / 'packet.json'})
            self.assertEqual(reference,
                             binding['evidence_bindings']['recovered-row']['observation-revision'])
            self.assertEqual(frozen_manifest, (root / 'manifest.json').read_bytes())

    def test_recovered_packet_rejects_provenance_drift_and_ambiguous_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, _ = source_fixture(root, '',
                                     'https://www.aidainternational.org/EventPage/4408')
            recovered = root / 'recovered'
            recovered.mkdir()
            shutil.copytree(root / 'raw', recovered / 'raw')
            shutil.copy2(root / 'receipt.json', recovered / 'receipt.json')
            shutil.copy2(root / 'packet.json', recovered / 'packet.json')
            with self.assertRaisesRegex(ValueError, 'already present'):
                load_source_observations(root, [name],
                                         recovered_packet_paths={name: recovered / 'packet.json'})
            (root / 'packet.json').unlink()
            with self.assertRaisesRegex(ValueError, 'unknown AIDA recovered packet source'):
                load_source_observations(root, [name],
                                         recovered_packet_paths={'other': recovered / 'packet.json'})
            with self.assertRaisesRegex(ValueError, 'duplicate AIDA recovered packet path'):
                load_source_observations(root, [name, 'other'],
                                         recovered_packet_paths={name: recovered / 'packet.json',
                                                                 'other': recovered / 'packet.json'})
            alias = root / 'packet-alias.json'
            alias.symlink_to(recovered / 'packet.json')
            with self.assertRaisesRegex(ValueError, 'duplicate AIDA recovered packet path'):
                load_source_observations(root, [name, 'other'],
                                         recovered_packet_paths={name: recovered / 'packet.json',
                                                                 'other': alias})
            receipt_path = recovered / 'receipt.json'
            receipt = json.loads(receipt_path.read_text())
            receipt['response_time'] = '2026-10-04T10:00:00Z'
            receipt_path.write_text(json.dumps(receipt))
            with self.assertRaisesRegex(ValueError, 'differs from source replay'):
                load_source_observations(root, [name],
                                         recovered_packet_paths={name: recovered / 'packet.json'})
            packet_path = recovered / 'packet.json'
            packet_path.write_bytes(packet_path.read_bytes() + b' ')
            with self.assertRaisesRegex(ValueError, 'packet hash mismatch'):
                load_source_observations(root, [name],
                                         recovered_packet_paths={name: packet_path})

    def test_event_results_heading_has_page_citation_and_generic_title_is_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, packet = source_fixture(root,
                '<h1>Wrong template event</h1><h2>Event Results</h2>'
                '<p class="u-type--medium u-type--delta">Synthetic Pool Open</p>',
                'https://www.aidainternational.org/Events/EventResults-4464')
            observed = load_source_observations(root, [name])['observations'][0]
            self.assertEqual('Synthetic Pool Open', observed['event_name'])
            self.assertEqual({'source_sha256': packet['source']['sha256'],
                              'locator': 'h2[Event Results] + p.u-type--medium',
                              'value': 'Synthetic Pool Open'},
                             observed['source_observation_ref']['event_context'])
            self.assertIsNone(observed['session'])
            self.assertIsNone(observed['category'])

    def test_conflicting_event_headings_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, _ = source_fixture(root,
                '<div class="event-title--description">First event</div>'
                '<div class="event-title--description">Second event</div>',
                'https://www.aidainternational.org/EventPage/4408')
            with self.assertRaisesRegex(ValueError, 'ambiguous AIDA event heading'):
                load_source_observations(root, [name])

    def test_commented_template_heading_is_ignored_and_legacy_revision_replays(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, packet = source_fixture(root,
                '<!--<div class="event-title--description">Wrong template</div>-->'
                '<div class="event-title--description">Actual event</div>',
                'https://www.aidainternational.org/EventPage/4408')
            current = load_source_observations(root, [name])['observations'][0]
            self.assertEqual('Actual event', current['event_name'])
            legacy = load_source_observations(
                root, [name], adapter_version='aida-snapshot-observation/1')['observations'][0]
            self.assertIsNone(legacy['event_name'])
            self.assertNotIn('event_context', legacy['source_observation_ref'])
            self.assertEqual('aida-snapshot-observation/1', legacy['adapter_version'])
            self.assertNotEqual(current['observation_version'], legacy['observation_version'])
            old_ref = legacy['source_observation_ref']
            bound = load_verified_bindings(root, [{'evidence_id': 'legacy-row',
                'source_observation_ref': old_ref}], [old_ref])
            self.assertEqual(old_ref,
                             bound['evidence_bindings']['legacy-row']['observation-revision'])

    def test_exact_original_packet_and_snapshot_bind_without_pg_or_attempt_claims(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            source = root / 'raw' / 'source.html'
            source.parent.mkdir()
            source.write_text('<html><div class="event-title--description">Synthetic Depth Open</div>'
                              '<li class="active"><a class="days" id="day_1">2025-08-30</a></li>'
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
            reference = observed['source_observation_ref']
            self.assertEqual('source-derived', reference['kind'])
            self.assertEqual(digest, reference['snapshot_sha256'])
            self.assertEqual(row['position'], reference['citation'])
            binding = load_verified_bindings(root, [{'evidence_id': 'source-row-1',
                'source_observation_ref': reference}], [reference])
            self.assertEqual(reference,
                             binding['evidence_bindings']['source-row-1']['observation-revision'])
            store = DecisionStore(root / 'decisions.sqlite')
            self.addCleanup(store.close)
            store.bind_verified_snapshot(root, expected_revision=0, idempotency_key='bind')
            proposal = {'id': 'source-decision-1', 'type': 'identity',
                        'subject_id': 'source-person-1', 'source_name': name,
                        'original': {'athlete': 'unknown'},
                        'proposed': {'athlete': 'source-person-1'},
                        'selected_option': 'unknown', 'competing_options': ['same_person'],
                        'evidence': [{'id': record_id, 'version': reference,
                                      'citation': {'evidence_id': 'source-row-1',
                                                   'source_citation': {'source-sha256':
                                                                       reference['source_sha256'],
                                                                       'locator': reference['citation']},
                                                   'observation_revision': reference}}],
                        'supporting_evidence': [], 'conflicting_evidence': [],
                        'depends_on': [], 'groups': ['event:unknown'],
                        'provider_confidence': None, 'score': None,
                        'rule_version': 'source/1', 'model_version': None,
                        'policy_version': 'source/1', 'status': 'pending',
                        'canonical_binding': {'decision_id': 'source-decision-1',
                            'reconciliation_run_revision': 1,
                            'reconciliation_event_id': 'synthetic-run-1',
                            'observation_revisions': [reference],
                            'evidence_bindings': [{'evidence_id': 'source-row-1',
                                'snapshot_record_id': record_id,
                                'observation_revision': reference}]}}
            envelope = {'snapshot_sha256': digest, 'binding_revision': store.revision,
                        'store_revision': store.revision,
                        'reconciliation_run_revision': 1, 'proposals': [proposal]}
            forged = json.loads(json.dumps(proposal))
            forged_reference = dict(reference, observation_version='0' * 64)
            forged['evidence'][0]['version'] = forged_reference
            forged['evidence'][0]['citation']['observation_revision'] = forged_reference
            forged['canonical_binding']['observation_revisions'] = [forged_reference]
            forged['canonical_binding']['evidence_bindings'][0]['observation_revision'] = forged_reference
            with self.assertRaisesRegex(ValueError, 'verified original'):
                register_verified_export(store, root, dict(envelope, proposals=[forged]))
            with self.assertRaises(ConflictError):
                store.register(digest, forged, idempotency_key='forged')
            self.assertEqual(1, store.revision)
            automatic = json.loads(json.dumps(proposal))
            automatic['status'] = 'automatic_approved'
            with self.assertRaisesRegex(ConflictError, 'canonical route'):
                register_verified_export(store, root, dict(envelope, proposals=[automatic]))
            self.assertEqual(1, store.revision)
            registered = register_verified_export(store, root, envelope)
            self.assertEqual('pending', registered[0]['status'])
            before = store.revision
            accepted = store.act('source-decision-1', action='approve',
                                 expected_revision=before, idempotency_key='source-approve')
            self.assertEqual('human_approved', accepted['status'])
            self.assertEqual('projection_pending', accepted['effective_status'])
            event = store.human_events()['events'][0]
            self.assertEqual(accepted, store.act('source-decision-1', action='approve',
                             expected_revision=before, idempotency_key='source-approve'))
            with self.assertRaises(ConflictError):
                store.act('source-decision-1', action='reverse',
                          expected_revision=before, idempotency_key='stale-reverse')
            store.acknowledge_human_event('flow-ledger', event, 'flow:committed')
            self.assertEqual('projection_pending', store.inspect('source-decision-1')['effective_status'])
            store.acknowledge_human_event('postgresql', event, 'pg:committed')
            self.assertEqual('human_approved', store.inspect('source-decision-1')['effective_status'])
            reversal = store.act('source-decision-1', action='reverse',
                                 expected_revision=store.revision,
                                 idempotency_key='source-reverse')
            self.assertEqual('projection_pending', reversal['effective_status'])
            reverse_event = store.human_events()['events'][-1]
            store.acknowledge_human_event('flow-ledger', reverse_event, 'flow:reversed')
            self.assertEqual('projection_pending', store.inspect('source-decision-1')['effective_status'])
            store.acknowledge_human_event('postgresql', reverse_event, 'pg:reversed')
            self.assertEqual('reversed', store.inspect('source-decision-1')['effective_status'])
            self.assertEqual('Synthetic Athlete', observed['source_fields']['name'])
            self.assertEqual('GER', observed['source_fields']['representation_raw'])
            self.assertEqual('2025-08-30', observed['event_date'])
            self.assertEqual('Synthetic Depth Open', observed['event_name'])
            self.assertEqual({'source_sha256': packet['source']['sha256'],
                              'locator': 'div.event-title--description',
                              'value': 'Synthetic Depth Open'}, reference['event_context'])
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
            with self.assertRaisesRegex(ValueError, 'verified original'):
                load_verified_bindings(root, [{'evidence_id': 'source-row-1',
                    'source_observation_ref': reference}], [reference])
            self.assertEqual([{'snapshot_record_id': record_id, 'source_name': name,
                               'citation': row['position'], 'event_date': '2025-08-30',
                               'source_object_id': 'sha256:' + packet['source']['sha256'],
                               'reason': 'retained-packet-missing'}], result['gaps'])


if __name__ == '__main__':
    unittest.main()
