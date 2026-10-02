import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from owner_decision_store import DecisionStore
from owner_decision_export_adapter import register_verified_export
from unified_evidence_snapshot import create_db

SHA = 'a' * 64
ARTIFACT = 'b' * 64
JOB = 'c' * 64
RECORD = 'd' * 64


class ExportAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.snapshot = self.root / 'snapshot'
        self.snapshot.mkdir()
        db = create_db(self.snapshot / 'snapshot.sqlite')
        raw = {'source_sha256': SHA, 'observation_refs': [{
            'job_id': JOB, 'ordinal': 0, 'candidate_id': 'candidate-1',
            'artifact_sha256': ARTIFACT, 'parser_version': 'parser/1'}]}
        db.execute('''INSERT INTO records(record_id,source_name,collection,record_path,kind,
                     citation_json,date_scope_json,raw_fields_json,parsed_fields_json,raw_json) VALUES(?,?,?,?,?,?,?,?,?,?)''',
                   (RECORD, 'synthetic', 'positions', 'positions[0]', 'candidate_position',
                    '{}', '{}', '{}', '{}', json.dumps(raw)))
        db.commit()
        db.close()
        digest = hashlib.sha256((self.snapshot / 'snapshot.sqlite').read_bytes()).hexdigest()
        (self.snapshot / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': digest,
            'inputs': {'synthetic': {'sha256': SHA, 'source_sha256': SHA}}}))
        self.store = DecisionStore(self.root / 'decisions.sqlite')
        binding = self.store.bind_verified_snapshot(self.snapshot, expected_revision=0,
                                                    idempotency_key='bind')
        self.revision = binding['revision']
        self.digest = digest

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def envelope(self):
        observation = {'job_id': JOB, 'ordinal': 0, 'candidate_id': 'candidate-1',
                       'artifact_sha256': ARTIFACT, 'source_sha256': SHA,
                       'parser_version': 'parser/1'}
        proposal = {'id': 'decision-1', 'type': 'identity', 'subject_id': 'person-a',
                    'source_name': 'Synthetic', 'original': {'athlete': 'unknown'},
                    'proposed': {'athlete': 'person-a'}, 'selected_option': 'same_person',
                    'competing_options': ['different_person'],
                    'evidence': [{'id': RECORD, 'citation': {'evidence_id': 'source-row-1',
                                 'source_citation': {'source-sha256': SHA, 'locator': 'row 1'},
                                 'observation_revision': observation}, 'version': observation}],
                    'supporting_evidence': [], 'conflicting_evidence': [], 'depends_on': [],
                    'groups': ['event:test'], 'provider_confidence': .96, 'score': .94,
                    'rule_version': 'rule/1', 'model_version': 'model/1',
                    'policy_version': 'policy/1', 'status': 'automatic_approved',
                    'canonical_binding': {'decision_id': 'decision-1',
                        'reconciliation_run_revision': 1, 'reconciliation_event_id': 'event-1',
                        'observation_revisions': [observation],
                        'evidence_bindings': [{'evidence_id': 'source-row-1',
                            'snapshot_record_id': RECORD, 'observation_revision': observation}]}}
        return {'snapshot_sha256': self.digest, 'binding_revision': self.revision,
                'reconciliation_run_revision': 1, 'proposals': [proposal]}

    def test_registers_verified_proposal_once_and_preserves_revision_binding(self):
        envelope = self.envelope()
        first = register_verified_export(self.store, self.snapshot, envelope)
        second = register_verified_export(self.store, self.snapshot, envelope)
        self.assertEqual(first, second)
        self.assertEqual('automatic_approved', self.store.inspect('decision-1')['status'])
        self.assertEqual(envelope['proposals'][0]['canonical_binding'],
                         self.store.inspect('decision-1')['canonical_binding'])

    def test_rejects_stale_binding_or_unmatched_observation_without_registering(self):
        for mutation in [lambda x: x.update(binding_revision=x['binding_revision'] + 1),
                         lambda x: x['proposals'][0]['canonical_binding']['observation_revisions'][0].update(
                             candidate_id='other'),
                         lambda x: (x['proposals'][0]['canonical_binding']['observation_revisions'][0].update(
                             candidate_id='other'),
                             x['proposals'][0]['canonical_binding']['evidence_bindings'][0]
                             ['observation_revision'].update(candidate_id='other'),
                             x['proposals'][0]['evidence'][0]['version'].update(candidate_id='other'),
                             x['proposals'][0]['evidence'][0]['citation']
                             ['observation_revision'].update(candidate_id='other'))]:
            envelope = self.envelope()
            mutation(envelope)
            with self.assertRaises(ValueError):
                register_verified_export(self.store, self.snapshot, envelope)
            self.assertEqual(self.revision, self.store.revision)


if __name__ == '__main__':
    unittest.main()
