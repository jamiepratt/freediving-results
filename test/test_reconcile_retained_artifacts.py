import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.reconcile_retained_artifacts import build_supplement


def sha(data):
    return hashlib.sha256(data).hexdigest()


class RetainedArtifactSupplementTest(unittest.TestCase):
    def test_replays_prior_parser_candidates_against_imported_printed_positions(self):
        with tempfile.TemporaryDirectory() as temp:
            payload = Path(temp)
            source = b'original PDF bytes'
            source_hash = sha(source)
            object_path = payload / 'archive/objects' / source_hash
            object_path.parent.mkdir(parents=True)
            object_path.write_bytes(source)
            candidate = {'coordinates': {'page': 1, 'line': 9},
                         'raw': {'line': 'A 70', 'fields': {'name': 'A', 'score': '70'}},
                         'parsed': {'source-name': 'A', 'event-date': '2025-07-01',
                                    'discipline': 'FIM', 'category': 'Senior'},
                         'parse-status': 'parsed', 'review-status': 'unreviewed',
                         'unresolved-reasons': ['owner-review-required']}
            imported = {'id': 'source-position:' + 'd' * 64,
                        'source_id': 'sha256:' + source_hash,
                        'locator': 'page 1 line 9',
                        'coordinates': candidate['coordinates'],
                        'raw_evidence': {**candidate['raw'], 'lines': ['A 70']},
                        'raw_fields': candidate['raw']['fields'],
                        'parsed_fields': dict(candidate['parsed']), 'status': 'parsed',
                        'observation_refs': [{'job_id': 'new-job', 'ordinal': 0,
                                              'artifact_sha256': 'e' * 64,
                                              'parser_version': 'parser/2',
                                              'citation': 'page 1 line 9'}]}
            acquisition = {'acquisition_id': 'acq', 'source_sha256': source_hash,
                           'discovery_url': 'https://example.test/index',
                           'final_url': 'https://example.test/result.pdf',
                           'retrieved_at': '2026-09-27T00:00:00Z'}
            projection = {'schema': 'census-evidence/v1', 'scope': 'test',
                          'cutoff': '2026-09-28T00:00:00Z', 'events': [],
                          'sources': [{'id': 'sha256:' + source_hash,
                                       'original_sha256': source_hash,
                                       'event_ids': [], 'authority': 'primary',
                                       'media': 'pdf', 'derived_sha256': None,
                                       'acquisition_id': 'acq', 'retrieved_at': acquisition['retrieved_at'],
                                       'discovery_url': acquisition['discovery_url'],
                                       'final_url': acquisition['final_url'],
                                       'selected_view': None,
                                       'provenance_gaps': ['Selected public view not established'],
                                       'acquisitions': [acquisition]}],
                          'positions': [imported], 'gaps': [], 'relationships': [],
                          'distinct_attempts': None}
            artifact = {'job-id': 'old-job', 'source-sha256': source_hash,
                        'parser-version': 'parser/1', 'candidates': [candidate],
                        'config': {'event': 'Example Invitational 2025',
                                   'source-dates': ['2025-07-01', '2025-07-11']},
                        'acquisitions': [{'acquisition-id': 'acq', 'manifest': {
                            'sha256': source_hash, 'discovery-url': acquisition['discovery_url'],
                            'final-url': acquisition['final_url'],
                            'retrieved-at': acquisition['retrieved_at']}}]}
            artifact_bytes = b'immutable artifact fixture'
            artifact_hash = sha(artifact_bytes)
            artifact_path = payload / 'archive/derived-objects' / artifact_hash
            artifact_path.parent.mkdir(parents=True)
            artifact_path.write_bytes(artifact_bytes)
            receipt_bytes = ('{:job-id "old-job" :artifact-sha256 "' + artifact_hash + '"}\n').encode()
            receipt_path = payload / 'archive/derivations/old-job.edn'
            receipt_path.parent.mkdir(parents=True)
            receipt_path.write_bytes(receipt_bytes)
            index = {'files': {f'archive/objects/{source_hash}': {'sha256': source_hash, 'bytes': len(source)},
                               f'archive/derived-objects/{artifact_hash}': {'sha256': artifact_hash,
                                                                             'bytes': len(artifact_bytes)},
                               'archive/derivations/old-job.edn': {'sha256': sha(receipt_bytes),
                                                                    'bytes': len(receipt_bytes)}}}
            index_bytes = json.dumps(index).encode()
            projection['projection_provenance'] = {
                'bundle_index_sha256': sha(index_bytes), 'retained_only_jobs': 1,
                'retained_only_candidates': 1,
                'retained_only_artifacts': [{'job_id': 'old-job', 'artifact_sha256': artifact_hash,
                                             'candidate_count': 1}]}
            result = build_supplement(projection, [(artifact_hash, artifact)], payload,
                                      index_bytes, b'projection fixture')
            self.assertEqual(1, result['counts']['source_positions_reused'])
            self.assertEqual(0, result['counts']['new_source_positions'])
            self.assertEqual(1, result['counts']['retained_candidate_versions'])
            self.assertEqual('same_printed_line_raw_fields', result['candidate_versions'][0]['match_basis'])
            self.assertEqual(imported['id'], result['candidate_versions'][0]['imported_position_id'])
            self.assertEqual(candidate, result['candidate_versions'][0]['candidate'])
            self.assertEqual(sha(receipt_bytes), result['artifacts'][0]['derivation_receipt']['sha256'])
            self.assertEqual('2025-07-01', result['candidate_versions'][0]['event_date'])
            self.assertEqual('FIM', result['candidate_versions'][0]['discipline'])
            self.assertEqual('Example Invitational 2025', result['candidate_versions'][0]['event_name'])
            self.assertEqual('artifact_config', result['candidate_versions'][0]['event_name_basis'])
            self.assertEqual(['2025-07-01', '2025-07-11'],
                             result['artifacts'][0]['source_dates_context'])
            self.assertEqual('2025-07-01', result['candidate_versions'][0]['event_date'])
            self.assertIsNone(result['distinct_attempts'])
            self.assertEqual(result, build_supplement(projection, [(artifact_hash, artifact)], payload,
                                                      index_bytes, b'projection fixture'))
            candidate['parsed']['category'] = 'Different'
            with self.assertRaisesRegex(ValueError, 'parsed fields differ'):
                build_supplement(projection, [(artifact_hash, artifact)], payload,
                                 index_bytes, b'projection fixture')
            candidate['parsed']['category'] = 'Senior'
            object_path.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'source object hash mismatch'):
                build_supplement(projection, [(artifact_hash, artifact)], payload,
                                 index_bytes, b'projection fixture')


if __name__ == '__main__':
    unittest.main()
