"""Synthetic contract examples only; no retained athlete or source originals."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import historical_cohort


CUTOFF = '2026-10-07T12:00:00Z'


def manifest():
    return {
        'schema': 'historical-cohort/v1', 'cohort_year': 2024,
        'cutoff': CUTOFF,
        'scope': {'from': '2024-01-01', 'to': '2024-12-31',
                  'description': 'Synthetic bounded year-index example'},
        'routes': [{
            'id': 'index', 'federation': 'AIDA', 'kind': 'archive',
            'url': 'https://example.org/results?year=2024',
            'selector': 'year=2024; result links', 'query': None,
            'pagination': {'method': 'single page', 'checked_pages': ['1'],
                           'remaining': None},
            'checked_at': CUTOFF, 'status': 'checked',
            'disposition': 'resolved', 'evidence': 'Synthetic index observed'},
            {'id': 'national', 'federation': 'CMAS', 'kind': 'national',
             'url': 'https://example.org/national', 'selector': '2024 archive',
             'query': None,
             'pagination': {'method': 'unknown', 'checked_pages': [],
                            'remaining': 'National routes unexamined'},
             'checked_at': None, 'status': 'unchecked',
             'disposition': 'unchecked', 'evidence': 'Known unchecked scope'}],
        'leads': [{
            'id': 'event', 'route_ids': ['index'], 'event_name': 'Synthetic event',
            'event_date': '2024-07-01', 'discipline': None, 'category': None,
            'unknown_fields': ['discipline', 'category'],
            'url': 'https://example.org/results/1', 'source_state': 'unchecked',
            'disposition': 'unchecked', 'evidence': 'Index link only', 'source_ids': []}],
        'census': {'schema': 'census-evidence/v1', 'scope': 'Synthetic 2024',
                   'cutoff': CUTOFF, 'events': [], 'sources': [], 'positions': [],
                   'gaps': [], 'relationships': []}}


def retain_synthetic_results(document):
    document['census']['sources'] = [{
        'id': 's1', 'event_ids': [], 'authority': 'primary', 'media': 'html',
        'original_sha256': 'a' * 64, 'derived_sha256': None,
        'acquisition_id': 'acquired', 'retrieved_at': CUTOFF,
        'discovery_url': 'https://example.org/results?year=2024',
        'final_url': 'https://example.org/results/1',
        'selected_view': {'state': 'retained', 'ref': 'original'},
        'provenance_gaps': []}]
    document['census']['positions'] = [{
        'id': 'p1', 'source_id': 's1', 'locator': 'table 1 row 1',
        'session': None, 'category': None, 'status': 'parsed',
        'raw_fields': {'printed_status': 'synthetic'}, 'unresolved_reason': None,
        'observation_refs': [
            {'job_id': job, 'ordinal': 0, 'artifact_sha256': 'b' * 64,
             'parser_version': version, 'citation': 'table 1 row 1'}
            for job, version in [('j1', 'schema3'), ('j2', 'schema4')]]}]
    document['leads'][0]['source_ids'] = ['s1']


class HistoricalCohortTest(unittest.TestCase):
    def test_checked_year_index_does_not_open_ordinary_2023_ingestion(self):
        document = manifest()
        result = historical_cohort.gate(document, 2023)
        self.assertFalse(result['open'])
        self.assertEqual({gap['ref'] for gap in result['blockers']}, {'national', 'event'})
        with self.assertRaisesRegex(ValueError, '2023.*closed'):
            historical_cohort.ordinary_ingest(document, 2023, None)

    def test_validate_reports_sources_positions_versions_separately_and_rejects_unknown_scope(self):
        document = manifest()
        report = historical_cohort.validate(document)
        self.assertEqual(report['counts']['routes'], 2)
        self.assertEqual(report['counts']['leads'], 1)
        self.assertEqual(report['counts']['sources'], 0)
        self.assertEqual(report['counts']['positions'], 0)
        self.assertEqual(report['counts']['observation_versions'], 0)
        self.assertIsNone(report['distinct_attempts'])
        for modify in (
            lambda item: item['leads'][0].pop('disposition'),
            lambda item: item['routes'].append(copy.deepcopy(item['routes'][0])),
            lambda item: item['leads'][0].update(route_ids=['missing']),
            lambda item: item['scope'].update(to='2025-01-01'),
            lambda item: item['leads'][0].update(event_date='2023-07-01'),
            lambda item: item['routes'][0].update(checked_at='2026-10-08T00:00:00Z'),
        ):
            changed = copy.deepcopy(document)
            modify(changed)
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                historical_cohort.validate(changed)

    def test_query_returns_selected_routes_leads_and_source_citations(self):
        document = manifest()
        retain_synthetic_results(document)
        result = historical_cohort.query(document, route='index')
        self.assertEqual([item['id'] for item in result['routes']], ['index'])
        self.assertEqual([item['id'] for item in result['leads']], ['event'])
        self.assertEqual(result['census']['positions'], document['census']['positions'])
        self.assertEqual(result['counts']['sources'], 1)
        self.assertEqual(result['counts']['positions'], 1)
        self.assertEqual(result['counts']['observation_versions'], 2)
        self.assertEqual(historical_cohort.query(document, route='national')['census']['sources'], [])
        with self.assertRaisesRegex(ValueError, 'unknown route filter'):
            historical_cohort.query(document, route='missing')

    def test_cli_ingestion_rejects_closed_gate_before_reading_candidate_or_writing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            snapshot = base / 'manifest.json'
            snapshot.write_text(json.dumps(manifest()))
            output = base / 'stage.json'
            result = subprocess.run(
                [sys.executable, 'scripts/historical_cohort.py', 'ingest',
                 str(snapshot), str(base / 'not-read.json'), str(output), '--year', '2023'],
                text=True, capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn('ordinary 2023 ingestion closed', result.stderr)
            self.assertFalse(output.exists())
            gate = subprocess.run(
                [sys.executable, 'scripts/historical_cohort.py', 'gate', str(snapshot),
                 '--year', '2023'], text=True, capture_output=True)
            self.assertEqual(gate.returncode, 3)
            self.assertFalse(json.loads(gate.stdout)['open'])
            missing = subprocess.run(
                [sys.executable, 'scripts/historical_cohort.py', 'ingest',
                 str(base / 'missing.json'), str(base / 'not-read.json'), str(output)],
                text=True, capture_output=True)
            self.assertEqual(missing.returncode, 2)
            self.assertFalse(output.exists())

    def test_explicit_unknown_event_fields_remain_a_gap_even_after_source_check(self):
        document = manifest()
        document['routes'] = document['routes'][:1]
        retain_synthetic_results(document)
        document['leads'][0].update(source_state='checked', disposition='resolved',
                                    evidence='Synthetic source checked',
                                    resolution={'kind': 'attempt-results',
                                                'evidence': 'Synthetic table 1 row 1'})
        self.assertFalse(historical_cohort.gate(document, 2023)['open'])
        for state in ('unchecked', 'unavailable', 'unsupported', 'unresolved'):
            with self.subTest(state=state):
                changed = copy.deepcopy(document)
                changed['leads'][0].update(source_state=state, disposition=state)
                with self.assertRaisesRegex(ValueError, '2023.*closed'):
                    historical_cohort.ordinary_ingest(changed, 2023, None)
        for changed in (None, {}, dict(document, routes=[])):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                historical_cohort.ordinary_ingest(changed, 2023, None)

    def test_census_gap_is_unresolved_even_when_its_search_was_checked(self):
        document = manifest()
        document['routes'] = document['routes'][:1]
        document['leads'] = []
        document['census']['gaps'] = [{
            'id': 'missing-results', 'scope': 'search', 'ref': 'index',
            'status': 'checked', 'reason': 'Known result source still missing'}]
        result = historical_cohort.gate(document, 2023)
        self.assertFalse(result['open'])
        self.assertEqual(result['blockers'][0]['kind'], 'census-gap')

    def test_valid_known_resolutions_allow_only_dated_2023_private_stage(self):
        document = manifest()
        document['routes'] = document['routes'][:1]
        document['leads'] = []
        self.assertTrue(historical_cohort.gate(document, 2023)['open'])
        candidate = copy.deepcopy(document['census'])
        candidate['scope'] = 'Synthetic 2023'
        candidate['events'] = [{'id': 'old-event', 'federation': 'AIDA',
                                'name': 'Synthetic old event',
                                'held_from': '2023-01-01', 'held_to': '2023-01-01'}]
        stage = historical_cohort.ordinary_ingest(document, 2023, candidate)
        self.assertEqual(stage['mode'], 'ordinary')
        self.assertEqual(stage['census'], candidate)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            snapshot = base / 'manifest.json'
            payload = base / 'candidate.json'
            destination = base / 'private' / 'nested' / 'stage.json'
            snapshot.write_text(json.dumps(document))
            payload.write_text(json.dumps(candidate))
            result = subprocess.run(
                [sys.executable, 'scripts/historical_cohort.py', 'ingest',
                 str(snapshot), str(payload), str(destination)], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(destination.stat().st_mode & 0o777, 0o600)
            self.assertEqual(destination.parent.stat().st_mode & 0o777, 0o700)
            self.assertEqual(destination.parent.parent.stat().st_mode & 0o777, 0o700)
            self.assertEqual(json.loads(destination.read_text()), stage)
        candidate['events'][0]['held_from'] = '2022-12-31'
        with self.assertRaisesRegex(ValueError, 'outside ingestion year'):
            historical_cohort.ordinary_ingest(document, 2023, candidate)

    def test_cli_query_preserves_unknown_values_and_validate_does_not_infer_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory) / 'manifest.json'
            snapshot.write_text(json.dumps(manifest()))
            for command in ('query', 'validate'):
                result = subprocess.run(
                    [sys.executable, 'scripts/historical_cohort.py', command, str(snapshot)],
                    text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                decoded = json.loads(result.stdout)
                self.assertIsNone(decoded['distinct_attempts'])
                if command == 'query':
                    self.assertIsNone(decoded['leads'][0]['discipline'])
                    self.assertEqual(decoded['routes'][1]['status'], 'unchecked')

    def test_freeform_resolved_lead_cannot_open_gate_without_typed_row_accounting(self):
        document = manifest()
        document['routes'] = document['routes'][:1]
        document['leads'][0].update(source_state='checked', disposition='resolved',
                                    discipline='synthetic', category='synthetic', unknown_fields=[])
        with self.assertRaisesRegex(ValueError, 'resolution'):
            historical_cohort.ordinary_ingest(document, 2023, None)

    def test_attempt_resolution_requires_retained_positions_and_versions(self):
        document = manifest()
        document['routes'] = document['routes'][:1]
        retain_synthetic_results(document)
        document['leads'][0].update(
            source_state='checked', disposition='resolved', discipline='synthetic',
            category='synthetic', unknown_fields=[],
            resolution={'kind': 'attempt-results', 'evidence': 'Synthetic table 1 row 1'})
        self.assertTrue(historical_cohort.gate(document, 2023)['open'])
        for modify in (
            lambda item: item['leads'][0].update(source_ids=[]),
            lambda item: item['census'].update(positions=[]),
            lambda item: item['census']['positions'][0].update(observation_refs=[]),
            lambda item: item['census']['positions'][0].update(raw_fields={}),
            lambda item: item['census']['positions'][0].update(status='unparsed'),
        ):
            changed = copy.deepcopy(document)
            modify(changed)
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, 'resolution'):
                historical_cohort.gate(changed, 2023)

    def test_cited_nonattempt_resolution_does_not_require_attempt_classifications(self):
        for kind in ('not-attempt-source', 'no-competition-attempts'):
            document = manifest()
            document['routes'] = document['routes'][:1]
            document['leads'][0].update(
                source_state='checked', disposition='resolved',
                resolution={'kind': kind, 'evidence': 'Synthetic cancellation notice URL and text'})
            with self.subTest(kind=kind):
                self.assertTrue(historical_cohort.gate(document, 2023)['open'])


if __name__ == '__main__':
    unittest.main()
