import unittest

from scripts.project_worker_positions import project_records


class WorkerPositionProjectionTest(unittest.TestCase):
    def test_groups_versions_by_source_position_without_attempt_inference(self):
        jobs = [
            {'job_id': 'job-a', 'source_sha256': 'a' * 64, 'artifact_sha256': 'b' * 64,
             'parser_version': 'parser/1', 'media': 'pdf', 'publisher': 'FFESSM',
             'acquisitions': [{'acquisition_id': 'acq-a', 'discovery_url': 'https://example.test/list',
                               'final_url': 'https://example.test/result.pdf', 'retrieved_at': '2026-09-27T00:00:00Z'}]},
            {'job_id': 'job-b', 'source_sha256': 'a' * 64, 'artifact_sha256': 'c' * 64,
             'parser_version': 'parser/2', 'media': 'pdf', 'publisher': 'FFESSM',
             'acquisitions': []},
        ]
        candidate = {'coordinates': {'page': 1, 'line': 10},
                     'raw': {'line': 'A 70', 'fields': {'name': 'A', 'score': '70'}},
                     'parsed': {'category': 'women', 'event-date': None, 'score': 70},
                     'parse-status': 'parsed', 'review-status': 'unreviewed'}
        observations = [
            {'job_id': 'job-a', 'ordinal': 0, 'kind': 'result-row', 'candidate_id': 'x',
             'classification_reason': 'parsed', 'candidate': candidate},
            {'job_id': 'job-b', 'ordinal': 0, 'kind': 'result-row', 'candidate_id': 'y',
             'classification_reason': 'parsed', 'candidate': candidate},
        ]
        doc = project_records(jobs, observations, '2026-09-28T00:00:00Z')
        self.assertEqual(1, len(doc['sources']))
        self.assertEqual(1, len(doc['positions']))
        self.assertEqual(2, len(doc['positions'][0]['observation_refs']))
        self.assertEqual(None, doc['distinct_attempts'])
        self.assertEqual(None, doc['positions'][0]['event_date'])
        self.assertEqual({'name': 'A', 'score': '70'}, doc['positions'][0]['raw_fields'])
        self.assertEqual(70, doc['positions'][0]['parsed_fields']['score'])
        self.assertEqual('page 1 line 10', doc['positions'][0]['locator'])
        self.assertEqual('https://example.test/result.pdf', doc['sources'][0]['final_url'])

    def test_links_exact_dated_event_without_inventing_other_event_dates(self):
        jobs = [{'job_id': 'job-a', 'source_sha256': 'a' * 64, 'artifact_sha256': 'b' * 64,
                 'parser_version': 'parser/1', 'media': 'pdf', 'publisher': 'CMAS',
                 'acquisitions': []}]
        observations = [{'job_id': 'job-a', 'ordinal': 0, 'kind': 'result-row',
                         'candidate_id': 'x', 'classification_reason': 'parsed',
                         'candidate': {'coordinates': {'page': 1, 'line': 2},
                                       'raw': {'fields': {'score': '75'}},
                                       'parsed': {'event-name': 'Example', 'event-date': '2026-06-01',
                                                  'federation': 'CMAS', 'session': 'day 1',
                                                  'category': 'women'}, 'parse-status': 'parsed'}}]
        doc = project_records(jobs, observations, '2026-09-28T00:00:00Z')
        self.assertEqual(1, len(doc['events']))
        self.assertEqual(['2026-06-01'], [event['held_from'] for event in doc['events']])
        self.assertEqual([doc['events'][0]['id']], doc['sources'][0]['event_ids'])
        self.assertEqual('Example', doc['positions'][0]['event_name'])
        self.assertEqual('day 1', doc['positions'][0]['session'])

    def test_retains_acquired_source_without_imported_positions(self):
        acquisition = {'acquisition_id': 'acq-only', 'source_sha256': 'd' * 64,
                       'discovery_url': 'https://example.test/list',
                       'final_url': 'https://example.test/image',
                       'retrieved_at': '2026-09-27T00:00:00Z',
                       'relationship': 'unknown', 'content_type': 'application/octet-stream'}
        doc = project_records([], [], '2026-09-28T00:00:00Z', [acquisition])
        self.assertEqual(1, len(doc['sources']))
        self.assertEqual('unknown', doc['sources'][0]['authority'])
        self.assertEqual('other', doc['sources'][0]['media'])
        self.assertEqual(0, len(doc['positions']))
        self.assertTrue(any(gap['scope'] == 'source' for gap in doc['gaps']))

    def test_preserves_quarantine_and_out_of_scope_date(self):
        jobs = [{'job_id': 'job-a', 'source_sha256': 'a' * 64, 'artifact_sha256': 'b' * 64,
                 'parser_version': 'parser/1', 'media': 'pdf', 'publisher': None,
                 'acquisitions': []}]
        observations = [{'job_id': 'job-a', 'ordinal': 1, 'kind': 'fragment',
                         'candidate_id': 'z', 'classification_reason': 'incomplete',
                         'candidate': {'coordinates': {'page': 2, 'line': 3},
                                       'raw': {'line': 'fragment'}, 'parsed': None,
                                       'parse-status': 'quarantined',
                                       'unresolved-reasons': ['missing result']}}]
        doc = project_records(jobs, observations, '2026-09-28T00:00:00Z')
        pos = doc['positions'][0]
        self.assertEqual('quarantined', pos['status'])
        self.assertEqual('missing result', pos['unresolved_reason'])
        self.assertIsNone(pos['event_date'])
        self.assertEqual([], doc['sources'][0]['event_ids'])
        self.assertTrue(doc['sources'][0]['provenance_gaps'])


if __name__ == '__main__':
    unittest.main()
