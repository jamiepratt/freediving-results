import hashlib
import io
import json
import sys
import tempfile
import unittest
import sqlite3
from contextlib import redirect_stdout
import subprocess
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from owner_decision_store import DecisionStore
from owner_decision_export_adapter import register_verified_export, deliver_verified_owner_events, main
from unified_evidence_snapshot import create_db
from scripts.cmas_microplus_snapshot_observations import load_source_observations as load_microplus
from tests.test_cmas_microplus_snapshot_observations import fixture as microplus_fixture

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
                'store_revision': self.store.revision,
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

    def test_later_conflict_rolls_back_entire_verified_export(self):
        envelope = self.envelope()
        other = self.envelope()['proposals'][0]
        other['id'] = 'decision-2'
        other['canonical_binding']['decision_id'] = 'decision-2'
        envelope['proposals'].append(other)
        self.store.register(self.digest, other, idempotency_key='prior-decision-2')
        before = self.store.revision
        with self.assertRaises(ValueError):
            register_verified_export(self.store, self.snapshot, envelope)
        self.assertEqual(before, self.store.revision)
        with self.assertRaises(KeyError):
            self.store.inspect('decision-1')

    def test_changed_observation_invalidates_approval_even_when_record_id_repeats(self):
        register_verified_export(self.store, self.snapshot, self.envelope())
        db_path = self.snapshot / 'snapshot.sqlite'
        db = sqlite3.connect(db_path)
        raw = json.loads(db.execute('SELECT raw_json FROM records WHERE record_id=?',
                                    (RECORD,)).fetchone()[0])
        raw['observation_refs'][0]['parser_version'] = 'parser/2'
        db.execute('UPDATE records SET raw_json=? WHERE record_id=?', (json.dumps(raw), RECORD))
        db.commit()
        db.close()
        new_digest = hashlib.sha256(db_path.read_bytes()).hexdigest()
        manifest = json.loads((self.snapshot / 'manifest.json').read_text())
        manifest['snapshot_sha256'] = new_digest
        (self.snapshot / 'manifest.json').write_text(json.dumps(manifest))
        self.store.bind_verified_snapshot(self.snapshot, expected_revision=self.store.revision,
                                          idempotency_key='changed-observation-bind')
        decision = self.store.inspect('decision-1')
        self.assertEqual('automatic_approved', decision['status'])
        self.assertEqual('invalidated', decision['effective_status'])

    def test_microplus_export_rechecks_source_ref_before_registration(self):
        microplus = self.root / 'microplus'
        microplus.mkdir()
        name, record_id, _ = microplus_fixture(microplus)
        reference = load_microplus(microplus, [name])['observations'][0]['source_observation_ref']
        store = DecisionStore(self.root / 'microplus-decisions.sqlite')
        try:
            binding = store.bind_verified_snapshot(microplus, expected_revision=0,
                                                   idempotency_key='microplus-bind')
            envelope = self.envelope()
            envelope['snapshot_sha256'] = reference['snapshot_sha256']
            envelope['binding_revision'] = binding['revision']
            envelope['store_revision'] = store.revision
            proposal = envelope['proposals'][0]
            proposal['status'] = 'pending'
            proposal['evidence'][0]['id'] = record_id
            proposal['evidence'][0]['version'] = reference
            proposal['evidence'][0]['citation'] = {
                'evidence_id': 'source-row-1', 'observation_revision': reference,
                'source_citation': {'source-sha256': reference['source_sha256'],
                                    'locator': reference['citation']}}
            canonical = proposal['canonical_binding']
            canonical['observation_revisions'] = [reference]
            canonical['evidence_bindings'][0]['snapshot_record_id'] = record_id
            canonical['evidence_bindings'][0]['observation_revision'] = reference
            register_verified_export(store, microplus, envelope)
            self.assertEqual('pending', store.inspect('decision-1')['status'])
            automatic = json.loads(json.dumps(envelope))
            automatic['proposals'][0]['id'] = 'decision-auto'
            automatic['proposals'][0]['canonical_binding']['decision_id'] = 'decision-auto'
            automatic['proposals'][0]['status'] = 'automatic_approved'
            automatic['store_revision'] = store.revision
            with self.assertRaisesRegex(ValueError, 'source-derived observation has no canonical route'):
                register_verified_export(store, microplus, automatic)
            forged = json.loads(json.dumps(envelope))
            fake = dict(reference, observation_version='0' * 64)
            forged['proposals'][0]['id'] = 'decision-forged'
            forged['proposals'][0]['canonical_binding']['decision_id'] = 'decision-forged'
            forged['proposals'][0]['evidence'][0]['version'] = fake
            forged['proposals'][0]['evidence'][0]['citation']['observation_revision'] = fake
            forged['proposals'][0]['canonical_binding']['observation_revisions'] = [fake]
            forged['proposals'][0]['canonical_binding']['evidence_bindings'][0]['observation_revision'] = fake
            forged['store_revision'] = store.revision
            with self.assertRaisesRegex(ValueError, 'source observation differs'):
                register_verified_export(store, microplus, forged)
        finally:
            store.close()

    def test_export_from_before_human_correction_cannot_register(self):
        envelope = self.envelope()
        prior = self.envelope()['proposals'][0]
        prior['id'] = 'decision-prior'
        prior['subject_id'] = 'person-prior'
        prior['canonical_binding']['decision_id'] = 'decision-prior'
        self.store.register(self.digest, prior,
                            idempotency_key='prior-decision')
        self.store.act('decision-prior', action='correct', correction={'action': 'different_person'},
                       expected_revision=self.store.revision, idempotency_key='human-correction')
        before = self.store.revision
        with self.assertRaises(ValueError):
            register_verified_export(self.store, self.snapshot, envelope)
        self.assertEqual(before, self.store.revision)

    def test_export_from_before_new_snapshot_binding_cannot_register(self):
        envelope = self.envelope()
        self.store.bind_verified_snapshot(self.snapshot, expected_revision=self.store.revision,
                                          idempotency_key='later-binding')
        before = self.store.revision
        with self.assertRaises(ValueError):
            register_verified_export(self.store, self.snapshot, envelope)
        self.assertEqual(before, self.store.revision)

    def test_trusted_delivery_checkpoints_each_target_and_recovers_after_commit(self):
        register_verified_export(self.store, self.snapshot, self.envelope())
        self.store.act('decision-1', action='correct', correction={'action': 'different_person'},
                       expected_revision=self.store.revision, idempotency_key='owner-correction')
        event = self.store.human_events()['events'][0]
        config = self.root / 'delivery.edn'
        config.write_text('{:synthetic true}')
        config.chmod(0o600)
        calls = []

        def run(argv, **kwargs):
            self.assertEqual(argv[:5], ['clojure', '-M', '-m', 'freediving.owner-event-delivery',
                                        '--config'])
            self.assertEqual(argv[5], str(config.resolve()))
            self.assertEqual(argv[-3:], ['--event-id', event['id'],
                                          '--expected-event-stdin'])
            self.assertEqual(json.loads(kwargs['input']), event)
            target = argv[-4]
            calls.append(target)
            if target == 'postgresql' and calls.count(target) == 1:
                raise subprocess.CalledProcessError(1, argv, stderr='synthetic crash')
            return subprocess.CompletedProcess(argv, 0, stdout=json.dumps({
                'target': target, 'event_id': event['id'], 'receipt': target + ':committed'}))

        with patch('owner_decision_export_adapter.subprocess.run', side_effect=run):
            first = deliver_verified_owner_events(self.store, config)
            self.assertEqual(first['status'], 'retry_required')
            self.assertEqual(first['checkpoints']['flow-ledger'], event['store_revision'], first)
            self.assertEqual(first['checkpoints']['postgresql'], 0)
            self.store.close()
            self.store = DecisionStore(self.root / 'decisions.sqlite')
            second = deliver_verified_owner_events(self.store, config)
        self.assertEqual(second['status'], 'complete')
        self.assertEqual(calls, ['flow-ledger', 'postgresql', 'postgresql'])
        self.assertEqual(second['checkpoints']['postgresql'], event['store_revision'])

    def test_trusted_delivery_rejects_wrong_target_receipt(self):
        register_verified_export(self.store, self.snapshot, self.envelope())
        self.store.act('decision-1', action='approve', expected_revision=self.store.revision,
                       idempotency_key='owner-approval')
        config = self.root / 'delivery.edn'
        config.write_text('{:synthetic true}')
        config.chmod(0o600)
        bad = subprocess.CompletedProcess([], 0, stdout=json.dumps({
            'target': 'postgresql', 'event_id': 'owner-store:999', 'receipt': 'false'}))
        with patch('owner_decision_export_adapter.subprocess.run', return_value=bad):
            result = deliver_verified_owner_events(self.store, config)
        self.assertEqual(result['status'], 'retry_required')
        self.assertEqual(result['failed_target'], 'flow-ledger')
        self.assertEqual(result['checkpoints'], {'flow-ledger': 0, 'postgresql': 0})

    def test_delivery_requires_private_regular_config(self):
        config = self.root / 'delivery.edn'
        config.write_text('{:synthetic true}')
        config.chmod(0o644)
        with self.assertRaises(ValueError):
            deliver_verified_owner_events(self.store, config)
        config.chmod(0o600)
        link = self.root / 'delivery-link.edn'
        link.symlink_to(config)
        with self.assertRaises(ValueError):
            deliver_verified_owner_events(self.store, link)
        self.assertEqual(self.store.delivery_checkpoints(), {'flow-ledger': 0, 'postgresql': 0})

    def test_private_cli_runs_delivery_without_printing_config(self):
        register_verified_export(self.store, self.snapshot, self.envelope())
        self.store.act('decision-1', action='approve', expected_revision=self.store.revision,
                       idempotency_key='owner-approval')
        config = self.root / 'delivery.edn'
        config.write_text('{:private "secret-for-test"}')
        config.chmod(0o600)
        event_id = self.store.human_events()['events'][0]['id']

        def run(argv, **kwargs):
            target = argv[-4]
            self.assertEqual(kwargs['cwd'], Path(__file__).resolve().parents[1])
            return subprocess.CompletedProcess(argv, 0, stdout=json.dumps({
                'target': target, 'event_id': event_id, 'receipt': 'committed'}))

        self.store.close()
        output = io.StringIO()
        with patch('owner_decision_export_adapter.subprocess.run', side_effect=run), redirect_stdout(output):
            self.assertEqual(0, main(['deliver', '--decision-db', str(self.root / 'decisions.sqlite'),
                                      '--config', str(config)]))
        self.store = DecisionStore(self.root / 'decisions.sqlite')
        self.assertEqual('complete', json.loads(output.getvalue())['status'])
        self.assertNotIn('secret-for-test', output.getvalue())


if __name__ == '__main__':
    unittest.main()
