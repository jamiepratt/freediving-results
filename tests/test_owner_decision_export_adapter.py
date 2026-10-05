import hashlib
import copy
import io
import json
import os
import shutil
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
from owner_decision_export_adapter import (register_verified_export,
    build_verified_microplus_attempt_export, deliver_verified_owner_events, main)
from unified_evidence_snapshot import create_db
from scripts.cmas_microplus_snapshot_observations import (
    load_source_observations as load_microplus, load_attempt_evidence)
from tests.test_cmas_microplus_snapshot_observations import fixture as microplus_fixture
from deploy.private_owner_preflight import FILES as PRIVATE_OWNER_FILES
from scripts.reconciliation_flow_host_proof import (Keyword, LedgerError, load_ledger,
                                                    pr_str, verify as verify_host_flow)

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

    def test_microplus_two_cited_views_register_one_pending_attempt(self):
        microplus = self.root / 'views'
        microplus.mkdir()
        name, record_id, row = microplus_fixture(microplus)
        alternate_url = row['citation']['url'].replace('/17/', '/16/')
        alternate_bytes = json.dumps([{'unrelated': True}, row['raw_fields']]).encode()
        alternate_hash = hashlib.sha256(alternate_bytes).hexdigest()
        (microplus / 'unit-16-results.json').write_bytes(alternate_bytes)
        (microplus / 'unit-16-results.receipt.json').write_text(json.dumps({
            'status': 200, 'sha256': alternate_hash, 'bytes': len(alternate_bytes),
            'final_url': alternate_url}))
        alternate = {'url': alternate_url, 'source_sha256': alternate_hash, 'json_pointer': '/1'}
        row['alternate_citations'] = [alternate]
        packet_path = microplus / 'packet.json'
        packet = json.loads(packet_path.read_text())
        packet['positions'][0] = row
        packet['sources'].append(dict(packet['sources'][0], id='sha256:' + alternate_hash,
                                      unit_id=16, url=alternate_url, sha256=alternate_hash,
                                      bytes=len(alternate_bytes), row_count=2))
        packet_path.write_text(json.dumps(packet))
        with sqlite3.connect(microplus / 'snapshot.sqlite') as db:
            db.execute('UPDATE records SET raw_json=?', (json.dumps(row),))
        manifest_path = microplus / 'manifest.json'
        manifest = json.loads(manifest_path.read_text())
        manifest['snapshot_sha256'] = hashlib.sha256((microplus / 'snapshot.sqlite').read_bytes()).hexdigest()
        manifest['inputs'][name]['sha256'] = hashlib.sha256(packet_path.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest))
        views = load_attempt_evidence(microplus, [name], record_ids=[record_id])
        self.assertEqual(2, views['summary']['cited_view_observations'])
        flow_path = self.root / 'microplus-flow.edn'
        generated = subprocess.run(
            ['clojure', '-Sdeps', '{:paths ["src" "resources" "test"]}', '-M', '-m',
             'freediving.microplus-flow-fixture'],
            input=json.dumps({'evidence': views['evidence'], 'decision_id': 'decision-1',
                              'flow_path': str(flow_path)}),
            text=True, capture_output=True, check=True,
            cwd=Path(__file__).resolve().parents[1])
        flow = json.loads(generated.stdout)
        store = DecisionStore(self.root / 'view-decisions.sqlite')
        try:
            bound = store.bind_verified_snapshot(microplus, expected_revision=0,
                                                 idempotency_key='bind-views',
                                                 attempt_record_ids=[record_id])
            envelope = build_verified_microplus_attempt_export(
                store, microplus, [name], record_id, decision_id='decision-1',
                reconciliation_run_revision=flow['run_revision'],
                reconciliation_event_id=flow['event_id'],
                reconciliation_flow_path=flow_path)
            ledger = load_ledger(flow_path)
            proof_binding = envelope['proposals'][0]['canonical_binding']
            proof_request = {'flow_path': str(flow_path), 'decision_id': 'decision-1',
                             'reconciliation_run_revision': flow['run_revision'],
                             'reconciliation_event_id': flow['event_id'],
                             'evidence_bindings': proof_binding['evidence_bindings']}

            def parity(name, mutate=None, expected=False):
                candidate = copy.deepcopy(ledger)
                if mutate is not None:
                    mutate(candidate)
                payload = pr_str(candidate)
                source = self.root / ('parity-' + name + '.edn')
                source.write_text(pr_str({Keyword(':sha256'): hashlib.sha256(
                    payload.encode()).hexdigest(), Keyword(':ledger'): candidate}))
                request = dict(proof_request, flow_path=str(source))
                clojure = subprocess.run(
                    ['clojure', '-M', '-m', 'freediving.reconciliation-flow-proof'],
                    input=json.dumps(request), text=True, capture_output=True,
                    cwd=Path(__file__).resolve().parents[1])
                try:
                    verify_host_flow(source, request['decision_id'],
                                     request['reconciliation_run_revision'],
                                     request['reconciliation_event_id'],
                                     request['evidence_bindings'])
                    portable = True
                except (LedgerError, ValueError):
                    portable = False
                self.assertEqual(expected, portable, name)
                self.assertEqual(expected, clojure.returncode == 0, name)

            event = lambda candidate: candidate[Keyword(':events')][-1]
            parity('valid', expected=True)
            parity('newer', lambda candidate: candidate[Keyword(':events')].append(
                copy.deepcopy(event(candidate))))
            parity('event-id', lambda candidate: event(candidate).__setitem__(
                Keyword(':id'), 'fabricated'))
            parity('candidate', lambda candidate: event(candidate).__setitem__(
                Keyword(':candidates'), ['fabricated', 'other']))
            parity('view', lambda candidate: event(candidate)[Keyword(':evidence')][0]
                   .__setitem__(Keyword(':evidence-id'), 'fabricated'))
            parity('source', lambda candidate: event(candidate)[Keyword(':evidence')][0]
                   [Keyword(':canonical-subject')][Keyword(':source')]
                   .__setitem__(Keyword(':sha256'), 'fabricated'))
            parity('citation', lambda candidate: event(candidate)[Keyword(':evidence')][0]
                   [Keyword(':citation')].__setitem__(Keyword(':locator'), 'fabricated'))
            parity('human', lambda candidate: event(candidate).__setitem__(
                Keyword(':origin'), Keyword(':human')))
            parity('revision', lambda candidate: event(candidate)[Keyword(':evidence')][0]
                   [Keyword(':canonical-subject')][Keyword(':version')]
                   .__setitem__(Keyword(':observation-revision'), {'changed': True}))
            malformed = self.root / 'malformed.edn'
            malformed.write_text('{:ledger')
            with self.assertRaises(LedgerError):
                load_ledger(malformed)
            for name, contents in [('duplicate-map', '{:ledger {} :ledger {}}'),
                                   ('unsupported-tag', '{:ledger #unknown {}}')]:
                invalid = self.root / (name + '.edn')
                invalid.write_text(contents)
                with self.assertRaises(LedgerError):
                    load_ledger(invalid)
                request = dict(proof_request, flow_path=str(invalid))
                clojure = subprocess.run(
                    ['clojure', '-M', '-m', 'freediving.reconciliation-flow-proof'],
                    input=json.dumps(request), text=True, capture_output=True,
                    cwd=Path(__file__).resolve().parents[1])
                self.assertNotEqual(0, clojure.returncode, name)
            unsupported = self.root / 'unsupported-version.edn'
            other = copy.deepcopy(ledger)
            other[Keyword(':version')] = 'reconciliation-flow/unknown'
            unsupported.write_text(pr_str({Keyword(':ledger'): other,
                Keyword(':sha256'): hashlib.sha256(pr_str(other).encode()).hexdigest()}))
            with self.assertRaises(LedgerError):
                load_ledger(unsupported)
            altered = self.root / 'tampered-digest.edn'
            altered.write_text(flow_path.read_text().replace(':sha256 "', ':sha256 "0', 1))
            with self.assertRaises(LedgerError):
                load_ledger(altered)
            linked = self.root / 'flow-link.edn'
            linked.symlink_to(flow_path)
            with self.assertRaises(OSError):
                load_ledger(linked)
            staged = self.root / 'stripped-owner-code'
            for name in PRIVATE_OWNER_FILES:
                target = staged / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(Path(__file__).resolve().parents[1] / name, target)
            export_path = self.root / 'pending-export.json'
            export_path.write_text(json.dumps(envelope))
            command = [sys.executable, str(staged / 'scripts/owner_decision_export_adapter.py'),
                       'register-pending', '--decision-db', str(self.root / 'view-decisions.sqlite'),
                       '--snapshot', str(microplus), '--export', str(export_path),
                       '--flow', str(flow_path)]
            env = dict(os.environ, PATH='')
            staged_result = subprocess.run(command, cwd=staged, env=env,
                                           text=True, capture_output=True)
            self.assertEqual(0, staged_result.returncode, staged_result.stderr)
            self.assertEqual('registered', json.loads(staged_result.stdout)['status'])
            self.assertEqual({'pending': 1}, json.loads(staged_result.stdout)['statuses'])
            self.assertEqual('pending', store.inspect('decision-1')['effective_status'])
            retry = subprocess.run(command, cwd=staged, env=env,
                                   text=True, capture_output=True)
            self.assertEqual(0, retry.returncode, retry.stderr)
            self.assertEqual(store.revision, json.loads(retry.stdout)['store_revision'])
            self.assertEqual('unchanged_replay', json.loads(retry.stdout)['status'])
            later_db = self.root / 'later-action.sqlite'
            with sqlite3.connect(later_db) as copy_db:
                store.db.backup(copy_db)
            later_store = DecisionStore(later_db)
            try:
                later_store.act('decision-1', action='approve',
                                expected_revision=later_store.revision,
                                idempotency_key='later-human-approval')
            finally:
                later_store.close()
            later_command = command[:]
            later_command[later_command.index('--decision-db') + 1] = str(later_db)
            later_result = subprocess.run(later_command, cwd=staged, env=env,
                                          text=True, capture_output=True)
            self.assertEqual(0, later_result.returncode, later_result.stderr)
            self.assertEqual('unchanged_replay', json.loads(later_result.stdout)['status'])
            self.assertEqual({'human_approved': 1}, json.loads(later_result.stdout)['statuses'])
            later_store = DecisionStore(later_db)
            try:
                approval = later_store.human_events()['events'][0]
                later_store.acknowledge_human_event('flow-ledger', approval, 'test-flow-committed')
                later_store.acknowledge_human_event('postgresql', approval, 'test-canonical-committed')
                later_store.act('decision-1', action='reverse',
                                expected_revision=later_store.revision,
                                idempotency_key='later-human-reversal')
            finally:
                later_store.close()
            reversed_result = subprocess.run(later_command, cwd=staged, env=env,
                                             text=True, capture_output=True)
            self.assertEqual(0, reversed_result.returncode, reversed_result.stderr)
            self.assertEqual('unchanged_replay', json.loads(reversed_result.stdout)['status'])
            self.assertEqual({'reversed': 1}, json.loads(reversed_result.stdout)['statuses'])
            forged_export = copy.deepcopy(envelope)
            forged_export['proposals'][0]['canonical_binding']['reconciliation_event_id'] = 'forged'
            forged_path = self.root / 'forged-export.json'
            forged_path.write_text(json.dumps(forged_export))
            forged_command = command[:]
            forged_command[forged_command.index('--export') + 1] = str(forged_path)
            forged_result = subprocess.run(forged_command, cwd=staged, env=env,
                                           text=True, capture_output=True)
            self.assertNotEqual(0, forged_result.returncode)
            self.assertIn('persisted flow', forged_result.stderr)
            stale_export = copy.deepcopy(envelope)
            stale_export['store_revision'] += 1
            stale_path = self.root / 'stale-export.json'
            stale_path.write_text(json.dumps(stale_export))
            stale_command = command[:]
            stale_command[stale_command.index('--export') + 1] = str(stale_path)
            stale_result = subprocess.run(stale_command, cwd=staged, env=env,
                                          text=True, capture_output=True)
            self.assertNotEqual(0, stale_result.returncode)
            link_command = command[:]
            link_command[link_command.index('--flow') + 1] = str(linked)
            link_result = subprocess.run(link_command, cwd=staged, env=env,
                                         text=True, capture_output=True)
            self.assertNotEqual(0, link_result.returncode)
            original = (microplus / 'unit-16-results.json').read_bytes()
            try:
                (microplus / 'unit-16-results.json').write_bytes(b'changed')
                changed_result = subprocess.run(command, cwd=staged, env=env,
                                                text=True, capture_output=True)
                self.assertNotEqual(0, changed_result.returncode)
                self.assertIn('source receipt mismatch', changed_result.stderr)
            finally:
                (microplus / 'unit-16-results.json').write_bytes(original)
            proposal = envelope['proposals'][0]
            self.assertEqual([record_id, record_id], [item['id'] for item in proposal['evidence']])
            self.assertEqual({view['id'] for view in views['evidence']['observation-versions']},
                             {item['citation']['evidence_id'] for item in proposal['evidence']})
            first = register_verified_export(store, microplus, envelope,
                                             reconciliation_flow_path=flow_path)
            self.assertEqual(first, register_verified_export(
                store, microplus, envelope, reconciliation_flow_path=flow_path))
            self.assertEqual('pending', store.inspect('decision-1')['effective_status'])
            self.assertEqual(2, len(store.inspect('decision-1')['canonical_binding']['evidence_bindings']))
            for field, bad in [('observation_version', '0' * 64),
                               ('citation', dict(alternate, json_pointer='/0')),
                               ('adapter_version', 'unsupported/1')]:
                forged = json.loads(json.dumps(envelope))
                forged['proposals'][0]['id'] = 'forged-' + field.replace('_', '-')
                forged['proposals'][0]['canonical_binding']['decision_id'] = forged['proposals'][0]['id']
                revision = forged['proposals'][0]['evidence'][1]['version']
                revision[field] = bad
                forged['proposals'][0]['evidence'][1]['citation']['observation_revision'] = revision
                forged['proposals'][0]['evidence'][1]['citation']['source_citation']['locator'] = revision['citation']
                forged['proposals'][0]['canonical_binding']['observation_revisions'][1] = revision
                forged['proposals'][0]['canonical_binding']['evidence_bindings'][1]['observation_revision'] = revision
                forged['store_revision'] = store.revision
                with self.subTest(field=field), self.assertRaises(ValueError):
                    register_verified_export(store, microplus, forged,
                                             reconciliation_flow_path=flow_path)
            store.act('decision-1', action='approve', expected_revision=store.revision,
                      idempotency_key='approve-view')
            approval = store.human_events()['events'][0]
            store.acknowledge_human_event('flow-ledger', approval, 'flow-committed')
            store.acknowledge_human_event('postgresql', approval, 'canonical-committed')
            self.assertEqual('human_approved', store.inspect('decision-1')['effective_status'])
            store.bind_verified_snapshot(microplus, expected_revision=store.revision,
                                         idempotency_key='base-only-bind')
            self.assertEqual('invalidated', store.inspect('decision-1')['effective_status'])
            store.bind_verified_snapshot(microplus, expected_revision=store.revision,
                                         idempotency_key='restored-view-bind',
                                         attempt_record_ids=[record_id])
            self.assertEqual('human_approved', store.inspect('decision-1')['effective_status'])
            store.act('decision-1', action='reverse', expected_revision=store.revision,
                      idempotency_key='reverse-view')
            reversal = store.human_events()['events'][1]
            store.acknowledge_human_event('flow-ledger', reversal, 'flow-reversed')
            store.acknowledge_human_event('postgresql', reversal, 'canonical-reversed')
            store.close()
            store = DecisionStore(self.root / 'view-decisions.sqlite')
            store.bind_verified_snapshot(microplus, expected_revision=store.revision,
                                         idempotency_key='unchanged-view-bind',
                                         attempt_record_ids=[record_id])
            self.assertEqual('reversed', store.inspect('decision-1')['effective_status'])
            self.assertEqual('verified', store.inspect('decision-1')['canonical_projection_status'])
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
