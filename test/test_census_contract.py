import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CLI = Path(__file__).resolve().parents[1] / 'scripts' / 'census_contract.py'
SHA = 'a' * 64
DERIVED = 'b' * 64


def fixture():
    return {
        'schema': 'census-evidence/v1', 'scope': '2025-2026 retained baseline',
        'cutoff': '2026-09-28T10:00:00Z',
        'events': [{'id': 'event-1', 'federation': 'CMAS', 'held_from': '2025-06-01',
                    'held_to': '2025-06-02', 'name': 'Example event'}],
        'sources': [{'id': 'source-1', 'event_ids': ['event-1'], 'authority': 'primary',
                     'media': 'pdf', 'original_sha256': SHA, 'derived_sha256': DERIVED,
                     'acquisition_id': 'acq-1', 'retrieved_at': '2026-09-27T10:00:00Z',
                     'discovery_url': 'https://example.org/results',
                     'final_url': 'https://example.org/results.pdf',
                     'selected_view': None, 'provenance_gaps': ['selected view not applicable']}],
        'positions': [{'id': 'row-1', 'source_id': 'source-1', 'locator': 'page 1 line 10',
                       'session': 'day 1', 'category': 'women CWT', 'status': 'parsed',
                       'raw_fields': {'name': 'Diver A', 'result': '70'},
                       'unresolved_reason': None,
                       'observation_refs': [{'job_id': 'job-1', 'ordinal': 1,
                                             'artifact_sha256': DERIVED, 'parser_version': 'v1',
                                             'citation': 'page 1 line 10'}]}],
        'gaps': [{'id': 'lead-1', 'scope': 'event', 'ref': 'event-1',
                  'status': 'unchecked', 'reason': 'organizer index not searched'}],
        'relationships': [{'id': 'rel-1', 'left': 'row-1', 'right': 'row-1',
                           'kind': 'unknown', 'status': 'unknown',
                           'basis': 'No distinct-attempt assertion'}],
    }


class CensusContractTest(unittest.TestCase):
    def run_cli(self, document, *args, ok=True):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private.json'
            path.write_text(json.dumps(document))
            result = subprocess.run([sys.executable, str(CLI), *args, str(path)],
                                    capture_output=True, text=True)
        self.assertEqual(0 if ok else 2, result.returncode, result.stderr)
        return json.loads(result.stdout) if ok else result.stderr

    def test_validate_and_query_retains_provenance_without_attempt_inference(self):
        data = fixture()
        summary = self.run_cli(data, 'validate')
        self.assertEqual('census-evidence-report/v1', summary['schema'])
        self.assertEqual({'events': 1, 'sources': 1, 'positions': 1,
                          'observation_versions': 1, 'gaps': 1, 'relationships': 1},
                         summary['counts'])
        self.assertIsNone(summary['distinct_attempts'])
        result = self.run_cli(data, 'query', '--event', 'event-1')
        self.assertEqual(SHA, result['sources'][0]['original_sha256'])
        self.assertEqual('page 1 line 10', result['positions'][0]['observation_refs'][0]['citation'])
        self.assertEqual('unchecked', result['gaps'][0]['status'])
        self.assertIsNone(result['distinct_attempts'])

    def test_rejects_uncited_or_dangling_row(self):
        data = fixture()
        data['positions'][0]['source_id'] = 'missing'
        self.assertIn('unknown source', self.run_cli(data, 'validate', ok=False))
        data = fixture()
        data['positions'][0]['observation_refs'][0]['citation'] = ''
        self.assertIn('citation', self.run_cli(data, 'validate', ok=False))

    def test_query_filters_position_status_and_retains_unknown_relationship(self):
        data = fixture()
        row = dict(data['positions'][0], id='row-2', locator='page 1 line 11',
                   status='quarantined', raw_fields=None,
                   unresolved_reason='printed score ambiguous', observation_refs=[])
        data['positions'].append(row)
        data['relationships'][0].update(left='row-1', right='row-2')
        result = self.run_cli(data, 'query', '--status', 'quarantined')
        self.assertEqual(['row-2'], [item['id'] for item in result['positions']])
        self.assertEqual([], result['relationships'])
        full = self.run_cli(data, 'query')
        self.assertEqual('unknown', full['relationships'][0]['kind'])
        self.assertEqual(2, self.run_cli(data, 'validate')['counts']['positions'])

    def test_relationship_status_distinguishes_exact_from_unknown(self):
        data = fixture()
        data['positions'].append(dict(data['positions'][0], id='row-2',
                                      locator='page 1 line 11'))
        data['relationships'][0].update(right='row-2')
        data['relationships'].append({'id': 'rel-2', 'left': 'row-1', 'right': 'row-2',
                                      'kind': 'supporting-result', 'status': 'exact',
                                      'basis': 'Printed row citation'})
        result = self.run_cli(data, 'query')
        self.assertEqual({'unknown', 'exact'},
                         {item['status'] for item in result['relationships']})
        data['relationships'][0]['status'] = 'exact'
        self.assertIn('unknown relationship', self.run_cli(data, 'validate', ok=False))
        data['relationships'][0]['status'] = 'unknown'
        del data['relationships'][1]['status']
        self.assertIn('relationship status', self.run_cli(data, 'validate', ok=False))

    def test_requires_reason_for_null_source_provenance(self):
        data = fixture()
        data['sources'][0]['provenance_gaps'] = []
        self.assertIn('provenance_gaps', self.run_cli(data, 'validate', ok=False))

    def test_unparsed_position_preserves_unknown_raw_fields(self):
        data = fixture()
        row = data['positions'][0]
        row.update(status='unparsed', raw_fields=None, unresolved_reason='OCR row unresolved',
                   observation_refs=[])
        report = self.run_cli(data, 'validate')
        self.assertEqual(0, report['counts']['observation_versions'])
        self.assertIsNone(self.run_cli(data, 'query')['positions'][0]['raw_fields'])
        row['unresolved_reason'] = None
        self.assertIn('unresolved_reason', self.run_cli(data, 'validate', ok=False))


if __name__ == '__main__':
    unittest.main()
