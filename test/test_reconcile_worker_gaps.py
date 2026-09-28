import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.reconcile_worker_gaps import build_supplement


def digest(data):
    return hashlib.sha256(data).hexdigest()


class WorkerGapSupplementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = self.root / 'bundle' / 'payload'
        self.payload.mkdir(parents=True)
        self.sources = []
        self.source_dispositions = {}
        files = {}
        for n in range(16):
            data = f'source {n}'.encode()
            sha = digest(data)
            path = f'archive/objects/{sha}'
            target = self.payload / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            files[path] = {'sha256': sha, 'bytes': len(data)}
            self.sources.append({'id': f'sha256:{sha}', 'original_sha256': sha,
                                 'source_status': 'no_imported_extraction',
                                 'acquisitions': [{'acquisition_id': f'acq-{n}',
                                                   'source_sha256': sha,
                                                   'final_url': f'https://example.test/{n}'}]})
            self.source_dispositions[sha] = {'disposition': 'supplemental_ranking',
                                              'evidence': 'Printed rank only',
                                              'next_step': 'Keep source gap explicit'}
        self.positions = []
        self.row_dispositions = {}
        for n in range(4):
            ident = f'source-position:{digest(str(n).encode())}'
            imported_hash = digest(f'imported {n}'.encode())
            self.sources.append({'id': f'sha256:{imported_hash}',
                                 'original_sha256': imported_hash,
                                 'source_status': 'imported_extraction'})
            row = {'id': ident, 'source_id': f'sha256:{imported_hash}',
                   'status': 'unparsed', 'locator': f'page 1 row {n}',
                   'raw_evidence': {'line': f'row {n}'}, 'raw_fields': None,
                   'unresolved_reason': 'Missing score',
                   'observation_refs': [{'job_id': f'job-{n}', 'ordinal': n,
                                         'artifact_sha256': digest(f'artifact {n}'.encode()),
                                         'citation': f'page 1 row {n}',
                                         'parser_version': 'parser/1'}]}
            self.positions.append(row)
            self.row_dispositions[ident] = {'disposition': 'quarantine',
                                             'failure_reason': 'Missing score',
                                             'next_step': 'Await source review'}
        index = json.dumps({'files': files}, sort_keys=True).encode()
        self.index_path = self.root / 'index.json'
        self.index_path.write_bytes(index)
        self.projection = {'schema': 'census-evidence/v1', 'cutoff': '2026-09-28T11:27:24Z',
                           'sources': self.sources, 'positions': self.positions,
                           'projection_provenance': {
                               'bundle_index_sha256': digest(index),
                               'reconciliation': {'sources': 20, 'positions': 4,
                                                  'observation_versions': 4},
                               'retained_only_artifacts': [{'job_id': 'separate',
                                                            'candidate_count': 136}],
                               'retained_only_jobs': 2,
                               'retained_only_candidates': 136}}
        self.assessment = {'source_dispositions': self.source_dispositions,
                           'row_dispositions': self.row_dispositions}

    def test_retains_all_gap_evidence_and_original_counts(self):
        result = build_supplement(self.projection, self.assessment,
                                  self.payload, self.index_path)
        self.assertEqual('worker-gap-reconciliation/v1', result['schema'])
        self.assertEqual(16, len(result['source_gaps']))
        self.assertEqual(4, len(result['unparsed_rows']))
        self.assertEqual(self.projection['projection_provenance']['reconciliation'],
                         result['projection_counts'])
        self.assertEqual([{'job_id': 'separate', 'candidate_count': 136}],
                         result['retained_only_artifacts'])
        self.assertEqual({f'page 1 row {n}' for n in range(4)},
                         {row['position']['locator'] for row in result['unparsed_rows']})
        self.assertEqual({'parsed': 0, 'unparsed': 4, 'quarantined': 0},
                         result['position_status_counts'])
        self.assertEqual('Printed rank only', result['source_gaps'][0]['assessment']['evidence'])
        self.assertIsNone(result['distinct_attempts'])

    def test_rejects_missing_disposition_and_changed_source_bytes(self):
        source_hash = self.sources[0]['original_sha256']
        saved = self.assessment['source_dispositions'].pop(source_hash)
        with self.assertRaisesRegex(ValueError, 'source disposition coverage'):
            build_supplement(self.projection, self.assessment, self.payload, self.index_path)
        self.assessment['source_dispositions'][source_hash] = saved
        (self.payload / 'archive/objects' / source_hash).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'source object hash mismatch'):
            build_supplement(self.projection, self.assessment, self.payload, self.index_path)


if __name__ == '__main__':
    unittest.main()
