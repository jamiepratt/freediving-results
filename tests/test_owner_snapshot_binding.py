import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from scripts.owner_snapshot_binding import load_verified_bindings
from scripts.cmas_microplus_snapshot_observations import load_source_observations as load_microplus
from tests.test_cmas_microplus_snapshot_observations import fixture as microplus_fixture


SHA = 'a' * 64
JOB = 'b' * 64
ARTIFACT = 'c' * 64
RECORD = 'd' * 64


class OwnerSnapshotBindingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        db = sqlite3.connect(self.directory / 'snapshot.sqlite')
        db.execute('CREATE TABLE records (record_id TEXT PRIMARY KEY, kind TEXT, source_name TEXT, '
                   'raw_json TEXT, source_object_id TEXT, parser_version TEXT)')
        raw = {'source_sha256': SHA, 'name': 'Synthetic Athlete',
               'observation_refs': [self.revision()]}
        db.execute('INSERT INTO records VALUES (?,?,?,?,?,?)',
                   (RECORD, 'candidate_position', 'Synthetic source', json.dumps(raw),
                    'sha256:' + SHA, 'parser/1'))
        db.commit()
        db.close()
        self.write_manifest()

    def revision(self):
        return {'job_id': JOB, 'ordinal': 0, 'candidate_id': 'candidate-1',
                'artifact_sha256': ARTIFACT, 'source_sha256': SHA,
                'parser_version': 'parser/1'}

    def write_manifest(self):
        digest = hashlib.sha256((self.directory / 'snapshot.sqlite').read_bytes()).hexdigest()
        (self.directory / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': digest,
            'inputs': {'Synthetic source': {'sha256': SHA, 'source_sha256': SHA}},
        }))

    def test_unique_exact_observation_binding(self):
        result = load_verified_bindings(self.directory, [{'evidence_id': 'e1',
            'job_id': JOB, 'ordinal': 0}], [self.revision()])
        self.assertEqual(RECORD, result['evidence_bindings']['e1']['snapshot-record-id'])
        self.assertEqual('Synthetic Athlete',
                         result['verified_snapshot_records'][RECORD]['athlete-name'])

    def test_missing_or_ambiguous_binding_fails_closed(self):
        with self.assertRaises(ValueError):
            load_verified_bindings(self.directory, [{'evidence_id': 'e1',
                'job_id': JOB, 'ordinal': 1}], [self.revision()])
        db = sqlite3.connect(self.directory / 'snapshot.sqlite')
        row = db.execute('SELECT kind,source_name,raw_json,source_object_id,parser_version '
                         'FROM records').fetchone()
        db.execute('INSERT INTO records VALUES (?,?,?,?,?,?)', ('e' * 64, *row))
        db.commit()
        db.close()
        self.write_manifest()
        with self.assertRaises(ValueError):
            load_verified_bindings(self.directory, [{'evidence_id': 'e1',
                'job_id': JOB, 'ordinal': 0}], [self.revision()])

    def test_snapshot_hash_change_fails_before_binding(self):
        with (self.directory / 'snapshot.sqlite').open('ab') as file:
            file.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'snapshot hash mismatch'):
            load_verified_bindings(self.directory, [{'evidence_id': 'e1',
                'job_id': JOB, 'ordinal': 0}], [self.revision()])

    def test_microplus_source_ref_binds_and_forgery_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name, record_id, _ = microplus_fixture(root)
            observation = load_microplus(root, [name], source_dir=root)['observations'][0]
            reference = observation['source_observation_ref']
            evidence = [{'evidence_id': 'source-row-1',
                         'source_observation_ref': reference}]
            bound = load_verified_bindings(root, evidence, [reference])
            self.assertEqual(reference,
                             bound['evidence_bindings']['source-row-1']['observation-revision'])
            self.assertEqual(record_id,
                             bound['evidence_bindings']['source-row-1']['snapshot-record-id'])
            forged = dict(reference, observation_version='0' * 64)
            with self.assertRaisesRegex(ValueError, 'source observation revision differs'):
                load_verified_bindings(root, [{'evidence_id': 'source-row-1',
                    'source_observation_ref': forged}], [forged])


if __name__ == '__main__':
    unittest.main()
