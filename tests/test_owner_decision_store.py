import sys
import tempfile
import unittest
import sqlite3
import json
import hashlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))

from owner_decision_store import ConflictError, DecisionStore


SNAP_A = 'a' * 64
SNAP_B = 'b' * 64


def proposal(ident, *, evidence='row-1', score=0.4, depends_on=(), status='pending',
             subject=None, decision_type='same_attempt', source='Synthetic source'):
    return {
        'id': ident, 'type': decision_type, 'subject_id': subject or ident,
        'source_name': source, 'original': {'attempt': 'unknown'},
        'proposed': {'attempt': 'one'}, 'selected_option': 'one',
        'competing_options': ['two'],
        'evidence': [{'id': evidence, 'citation': {'row': 1}, 'version': 'v1'}],
        'supporting_evidence': ['same publisher ID'],
        'conflicting_evidence': [], 'depends_on': list(depends_on),
        'provider_confidence': score, 'score': score,
        'rule_version': 'rule/1', 'model_version': 'model/1',
        'policy_version': 'policy/1', 'status': status,
        'groups': ['event:one'],
    }


class DecisionStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'decisions.sqlite'
        self.store = DecisionStore(self.path)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def bind(self, digest=SNAP_A, evidence=('row-1', 'row-2', 'row-3')):
        return self.store.bind_snapshot(digest, evidence, expected_revision=self.store.revision,
                                        idempotency_key='bind-' + digest)

    def test_approval_survives_snapshot_replacement_with_same_evidence(self):
        self.bind()
        self.store.register(SNAP_A, proposal('d1'), idempotency_key='register-d1')
        approved = self.store.act('d1', action='approve', expected_revision=self.store.revision,
                                  idempotency_key='approve-d1')
        self.assertEqual(approved['status'], 'human_approved')
        self.store.close()
        self.store = DecisionStore(self.path)
        self.bind(SNAP_B)
        inspected = self.store.inspect('d1')
        self.assertEqual(inspected['status'], 'human_approved')
        self.assertEqual(inspected['snapshot_sha256'], SNAP_A)
        self.assertEqual(inspected['active_snapshot_sha256'], SNAP_B)
        self.assertEqual(len(inspected['history']), 2)

    def test_pending_scored_queue_is_ascending_and_scoreless_is_separate(self):
        self.bind()
        for ident, score in [('high', .9), ('low-b', .2), ('gap', None), ('low-a', .2)]:
            self.store.register(SNAP_A, proposal(ident, score=score), idempotency_key='register-' + ident)
        queue = self.store.queue()
        self.assertEqual([p['id'] for p in queue['items']], ['low-a', 'low-b', 'high'])
        self.assertEqual([p['id'] for p in queue['scoreless_items']], ['gap'])
        self.assertIn('uncalibrated', queue['score_note'])
        self.assertEqual(self.store.queue(decision_type='same_attempt', source_name='Other')['total'], 0)

    def test_reversal_invalidates_dependent_chain_and_keeps_independent_fact(self):
        self.bind()
        for ident, dependencies in [('root', ()), ('child', ('root',)),
                                    ('grandchild', ('child',)), ('independent', ())]:
            self.store.register(SNAP_A, proposal(ident, depends_on=dependencies,
                                                 status='automatic_approved'),
                                idempotency_key='register-' + ident)
        self.assertEqual(self.store.projection()['overlay_decision_counts_by_group']['event:one'], 4)
        preview = self.store.preview('root', action='reverse')
        self.assertEqual(preview['after'], {'root': 'reversed', 'child': 'invalidated',
                                            'grandchild': 'invalidated'})
        self.store.act('root', action='reverse', expected_revision=self.store.revision,
                       idempotency_key='reverse-root')
        self.assertEqual(self.store.projection()['overlay_decision_counts_by_group']['event:one'], 1)
        self.assertEqual(self.store.inspect('independent')['effective_status'], 'automatic_approved')
        self.assertEqual(self.store.inspect('child')['status'], 'automatic_approved')
        self.assertEqual(self.store.inspect('child')['effective_status'], 'invalidated')

    def test_stale_and_idempotent_writes_preserve_human_correction(self):
        self.bind()
        self.store.register(SNAP_A, proposal('d1', subject='person-1'), idempotency_key='register-d1')
        revision = self.store.revision
        corrected = self.store.act('d1', action='correct', correction={'athlete': 'p-2'},
                                   expected_revision=revision, idempotency_key='correct-d1')
        self.assertEqual(corrected['correction'], {'athlete': 'p-2'})
        retry = self.store.act('d1', action='correct', correction={'athlete': 'p-2'},
                               expected_revision=revision, idempotency_key='correct-d1')
        self.assertEqual(retry, corrected)
        with self.assertRaises(ConflictError):
            self.store.act('d1', action='reverse', expected_revision=revision,
                           idempotency_key='reverse-stale')
        with self.assertRaises(ConflictError):
            self.store.register(SNAP_A, proposal('d2', subject='person-1',
                                                 status='automatic_approved'),
                                idempotency_key='register-d2')
        self.assertEqual(self.store.inspect('d1')['status'], 'human_corrected')

    def test_snapshot_missing_evidence_invalidates_projection_but_keeps_ledger(self):
        self.bind()
        self.store.register(SNAP_A, proposal('d1', status='automatic_approved'),
                            idempotency_key='register-d1')
        self.store.register(SNAP_A, proposal('d2'), idempotency_key='register-d2')
        self.bind(SNAP_B, evidence=('row-2',))
        self.assertEqual(self.store.inspect('d1')['effective_status'], 'invalidated')
        self.assertEqual(self.store.projection()['active_decisions'], [])
        self.assertEqual(self.store.inspect('d1')['status'], 'automatic_approved')
        self.assertEqual(self.store.inspect('d2')['effective_status'], 'invalidated')
        self.assertEqual(self.store.queue()['total'], 0)

    def test_audit_sample_is_stable_and_nonblocking(self):
        self.bind()
        for ident in ('c', 'a', 'b'):
            self.store.register(SNAP_A, proposal(ident, status='automatic_approved'),
                                idempotency_key='register-' + ident)
        before = self.store.revision
        sample = self.store.audit_sample(limit=2)
        self.assertEqual(len(sample['items']), 2)
        self.assertEqual(sample, self.store.audit_sample(limit=2))
        self.assertEqual(self.store.revision, before)
        self.assertEqual(self.store.queue(status='automatic_approved')['total'], 3)

    def test_verified_snapshot_binding_uses_exact_record_ids(self):
        directory = Path(self.tmp.name) / 'snapshot'
        directory.mkdir()
        db_path = directory / 'snapshot.sqlite'
        db = sqlite3.connect(db_path)
        db.execute('CREATE TABLE records (record_id TEXT PRIMARY KEY)')
        db.execute("INSERT INTO records VALUES ('row-1')")
        db.commit()
        db.close()
        digest = hashlib.sha256(db_path.read_bytes()).hexdigest()
        (directory / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': digest}))
        result = self.store.bind_verified_snapshot(directory, expected_revision=0,
                                                   idempotency_key='verified-bind')
        self.assertEqual(result['snapshot_sha256'], digest)
        self.store.register(digest, proposal('d1'), idempotency_key='registered-d1')
        with self.assertRaises(ConflictError):
            self.store.register(digest, proposal('d2', evidence='unknown'),
                                idempotency_key='registered-d2')
        db_path.write_bytes(db_path.read_bytes() + b'tamper')
        with self.assertRaises(ValueError):
            self.store.bind_verified_snapshot(directory, expected_revision=self.store.revision,
                                              idempotency_key='tampered-bind')

    def test_dependent_approval_waits_for_active_prerequisite(self):
        self.bind()
        self.store.register(SNAP_A, proposal('root'), idempotency_key='register-root')
        self.store.register(SNAP_A, proposal('child', depends_on=('root',)),
                            idempotency_key='register-child')
        with self.assertRaises(ConflictError):
            self.store.act('child', action='approve', expected_revision=self.store.revision,
                           idempotency_key='approve-child-early')
        self.assertEqual(self.store.inspect('child')['status'], 'pending')
        self.store.act('root', action='approve', expected_revision=self.store.revision,
                       idempotency_key='approve-root')
        self.store.act('child', action='approve', expected_revision=self.store.revision,
                       idempotency_key='approve-child')
        self.assertEqual(self.store.inspect('child')['effective_status'], 'human_approved')

    def test_human_event_feed_preserves_action_binding_and_idempotent_revision(self):
        self.bind()
        p = proposal('d1')
        p['canonical_binding'] = {'decision_id': 'd1', 'reconciliation_run_revision': 3,
                                  'reconciliation_event_id': 'flow-3',
                                  'observation_revisions': [], 'evidence_bindings': []}
        self.store.register(SNAP_A, p, idempotency_key='register-d1')
        before = self.store.revision
        self.store.act('d1', action='correct', correction={'athlete': 'person-2'},
                       expected_revision=before, idempotency_key='correct-d1')
        feed = self.store.human_events(after_revision=0)
        self.assertEqual(len(feed['events']), 1)
        event = feed['events'][0]
        self.assertEqual(event['store_revision'], before + 1)
        self.assertEqual(event['action'], 'correct')
        self.assertEqual(event['correction'], {'athlete': 'person-2'})
        self.assertEqual(event['binding_revision'], 1)
        self.assertEqual(event['snapshot_sha256'], SNAP_A)
        self.assertEqual(event['proposal'], p)
        self.assertEqual(self.store.human_events(after_revision=before + 1)['events'], [])


if __name__ == '__main__':
    unittest.main()
