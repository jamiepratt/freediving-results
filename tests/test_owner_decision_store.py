import sys
import tempfile
import unittest
import sqlite3
import json
import hashlib
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))

from owner_decision_store import ConflictError, DecisionStore
from aida_snapshot_observations import load_source_observations
from issue55_aida_selected_html import build as build_aida_packet


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

    def recovered_aida_snapshot(self):
        root = Path(self.tmp.name)
        recovered = root / 'recovered'
        (recovered / 'raw').mkdir(parents=True)
        source = recovered / 'raw' / 'source.html'
        source.write_text('<html><div class="event-title--description">Synthetic Open</div>'
                          '<li class="active"><a class="days" id="day_1">2025-08-30</a></li>'
                          '<table id="table_ajax"><thead><tr>'
                          + ''.join(f'<th>{h}</th>' for h in
                                    ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline',
                                     'OT', 'AP', 'RP', 'Card', 'Points', 'Remarks'))
                          + '</tr></thead><tbody id="body_ajax"><tr>'
                          + ''.join(f'<td>{v}</td>' for v in
                                    ('1', 'Synthetic Athlete', 'GER', 'F', 'CWTB', '09:40',
                                     '25 m', '24 m', 'YELLOW', '19', 'Note'))
                          + '</tr></tbody></table></html>')
        url = 'https://www.aidainternational.org/EventPage/4408'
        body = source.read_bytes()
        receipt = {'schema': 'aida-selected-html-browser-receipt/v1',
                   'requested_url': url, 'final_url': url, 'http_status': 200,
                   'content_type': 'text/html', 'response_time': '2026-09-28T18:43:07Z',
                   'selected_view': {'date': '2025-08-30', 'selector': 'day_1'},
                   'body': {'path': 'raw/source.html', 'bytes': len(body),
                            'sha256': hashlib.sha256(body).hexdigest()},
                   'source_citation': {'url': url, 'selected_date': '2025-08-30',
                                       'table': 'table_ajax', 'tbody': 'body_ajax'}}
        receipt_path = recovered / 'receipt.json'
        receipt_path.write_text(json.dumps(receipt))
        packet = build_aida_packet(source, receipt_path)
        packet_path = recovered / 'packet.json'
        packet_path.write_text(json.dumps(packet))
        name = 'aida-synthetic-2025-08-30'
        record_id = hashlib.sha256(f'{name}:positions[0]'.encode()).hexdigest()
        snapshot = root / 'snapshot'
        snapshot.mkdir()
        db_path = snapshot / 'snapshot.sqlite'
        db = sqlite3.connect(db_path)
        db.execute('CREATE TABLE records (record_id TEXT, source_name TEXT, collection TEXT, '
                   'record_path TEXT, kind TEXT, raw_json TEXT, citation_json TEXT, '
                   'source_object_id TEXT, event_date TEXT, parser_version TEXT, '
                   'observation_version TEXT, event_name TEXT, session TEXT, category TEXT)')
        row = packet['positions'][0]
        db.execute('INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                   (record_id, name, 'positions', 'positions[0]', 'candidate_position',
                    json.dumps(row), json.dumps(row['position']),
                    'sha256:' + packet['source']['sha256'], '2025-08-30',
                    None, None, None, None, None))
        db.commit()
        db.close()
        digest = hashlib.sha256(db_path.read_bytes()).hexdigest()
        (snapshot / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': digest,
            'inputs': {name: {'source_schema': 'aida-selected-html-packet/v1',
                              'path': str(snapshot / 'missing-packet.json'),
                              'sha256': hashlib.sha256(packet_path.read_bytes()).hexdigest(),
                              'collections': {'positions': 1}}}}))
        return snapshot, name, packet_path, record_id, digest

    def test_recovered_aida_binding_registers_only_exact_pending_source_ref(self):
        snapshot, name, packet_path, record_id, digest = self.recovered_aida_snapshot()
        first = self.store.bind_verified_snapshot(snapshot, expected_revision=0,
                                                  idempotency_key='initial-bind')
        self.assertEqual(first['evidence_count'], 1)
        self.assertNotIn('source_derived_ref', self.store._binding()['observation_refs'][record_id])
        recovered = {name: packet_path}
        second = self.store.bind_verified_snapshot(
            snapshot, expected_revision=self.store.revision,
            idempotency_key='recovered-bind', recovered_packet_paths=recovered)
        self.assertEqual(second['revision'], first['revision'] + 1)
        self.assertEqual(second, self.store.bind_verified_snapshot(
            snapshot, expected_revision=first['revision'],
            idempotency_key='recovered-bind', recovered_packet_paths=recovered))
        reference = load_source_observations(
            snapshot, [name], recovered_packet_paths=recovered)['observations'][0]['source_observation_ref']
        self.assertEqual(reference, self.store._binding()['observation_refs'][record_id]['source_derived_ref'])
        p = proposal('source-1', evidence=record_id)
        p['evidence'][0] = {'id': record_id, 'version': reference,
                            'citation': {'source_citation': {'source-sha256': reference['source_sha256'],
                                                            'locator': reference['citation']},
                                         'observation_revision': reference}}
        p['canonical_binding'] = {'decision_id': p['id'], 'observation_revisions': [reference],
                                  'evidence_bindings': [{'snapshot_record_id': record_id,
                                                         'observation_revision': reference}]}
        self.assertEqual(self.store.register(digest, p, idempotency_key='register-source')['effective_status'],
                         'pending')
        unbound = json.loads(json.dumps(p))
        unbound['id'] = 'source-unbound'
        del unbound['canonical_binding']
        with self.assertRaises(ConflictError):
            self.store.register(digest, unbound, idempotency_key='register-unbound')
        forged = json.loads(json.dumps(p))
        forged['id'] = 'source-forged'
        forged['canonical_binding']['decision_id'] = forged['id']
        forged['evidence'][0]['version']['observation_version'] = '0' * 64
        forged['canonical_binding']['observation_revisions'][0]['observation_version'] = '0' * 64
        forged['canonical_binding']['evidence_bindings'][0]['observation_revision']['observation_version'] = '0' * 64
        with self.assertRaises(ConflictError):
            self.store.register(digest, forged, idempotency_key='register-forged')
        miscited = json.loads(json.dumps(p))
        miscited['id'] = 'source-miscited'
        miscited['canonical_binding']['decision_id'] = miscited['id']
        miscited['evidence'][0]['citation']['source_citation']['locator'] = {'row': 99}
        with self.assertRaises(ConflictError):
            self.store.register(digest, miscited, idempotency_key='register-miscited')
        mismatched_citation = json.loads(json.dumps(p))
        mismatched_citation['id'] = 'source-citation-mismatch'
        mismatched_citation['canonical_binding']['decision_id'] = mismatched_citation['id']
        mismatched_citation['evidence'][0]['citation']['observation_revision']['observation_version'] = '0' * 64
        with self.assertRaises(ConflictError):
            self.store.register(digest, mismatched_citation,
                                idempotency_key='register-citation-mismatch')
        approved = json.loads(json.dumps(p))
        approved['id'] = 'source-automatic'
        approved['canonical_binding']['decision_id'] = approved['id']
        approved['status'] = 'automatic_approved'
        with self.assertRaisesRegex(ConflictError, 'no canonical route'):
            self.store.register(digest, approved, idempotency_key='register-automatic')

    def test_recovered_aida_binding_rejects_stale_or_changed_original(self):
        snapshot, name, packet_path, _, _ = self.recovered_aida_snapshot()
        self.store.bind_verified_snapshot(snapshot, expected_revision=0,
                                          idempotency_key='initial-bind')
        recovered = {name: packet_path}
        with self.assertRaises(ValueError):
            self.store.bind_verified_snapshot(snapshot, expected_revision=self.store.revision,
                                              idempotency_key='unknown-source',
                                              recovered_packet_paths={'unknown': packet_path})
        with self.assertRaises(ConflictError):
            self.store.bind_verified_snapshot(snapshot, expected_revision=0,
                                              idempotency_key='stale-bind',
                                              recovered_packet_paths=recovered)
        (packet_path.parent / 'raw' / 'source.html').write_text('changed original')
        with self.assertRaises(ValueError):
            self.store.bind_verified_snapshot(snapshot, expected_revision=self.store.revision,
                                              idempotency_key='changed-bind',
                                              recovered_packet_paths=recovered)

    def test_remote_outbox_ack_requires_exact_event_and_order(self):
        self.bind()
        self.store.register(SNAP_A, proposal('d1'), idempotency_key='register-d1')
        self.store.act('d1', action='approve', expected_revision=self.store.revision,
                       idempotency_key='approve-d1')
        self.store.act('d1', action='reverse', expected_revision=self.store.revision,
                       idempotency_key='reverse-d1')
        first, second = self.store.human_events()['events']
        with self.assertRaises(ConflictError):
            self.store.acknowledge_human_event('postgresql', first, 'pg:committed')
        with self.assertRaises(ConflictError):
            self.store.acknowledge_human_event('flow-ledger', second, 'flow:committed')
        changed = json.loads(json.dumps(first))
        changed['action'] = 'reject'
        with self.assertRaises(ConflictError):
            self.store.acknowledge_human_event('flow-ledger', changed, 'flow:committed')
        receipt = self.store.acknowledge_human_event('flow-ledger', first, 'flow:committed')
        self.assertEqual(receipt['checkpoints']['flow-ledger'], first['store_revision'])
        self.assertEqual(self.store.acknowledge_human_event('flow-ledger', first, 'flow:committed'), receipt)
        self.store.close()
        self.store = DecisionStore(self.path)
        self.store.acknowledge_human_event('postgresql', first, 'pg:committed')
        self.store.acknowledge_human_event('flow-ledger', second, 'flow:second')
        self.store.acknowledge_human_event('postgresql', second, 'pg:second')
        self.assertEqual(self.store.delivery_checkpoints()['postgresql'], second['store_revision'])

    def test_approval_survives_snapshot_replacement_with_same_evidence(self):
        self.assertIsNone(self.store.active_snapshot_sha256)
        self.bind()
        self.assertEqual(self.store.active_snapshot_sha256, SNAP_A)
        self.store.register(SNAP_A, proposal('d1'), idempotency_key='register-d1')
        approved = self.store.act('d1', action='approve', expected_revision=self.store.revision,
                                  idempotency_key='approve-d1')
        self.assertEqual(approved['status'], 'human_approved')
        self.store.close()
        self.store = DecisionStore(self.path)
        self.bind(SNAP_B)
        self.assertEqual(self.store.active_snapshot_sha256, SNAP_B)
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

    def test_queue_summary_keeps_review_fields_and_full_cited_inspection(self):
        self.bind()
        p = proposal('cited', score=0.2)
        p['evidence'][0]['citation']['context'] = 'synthetic evidence ' * 1000
        p['canonical_binding'] = {'decision_id': 'cited', 'context': 'synthetic binding ' * 1000}
        self.store.register(SNAP_A, p, idempotency_key='register-cited')
        summary = self.store.queue(summary=True)['items'][0]
        self.assertEqual(summary, {key: self.store.inspect('cited')[key] for key in (
            'id', 'type', 'source_name', 'status', 'effective_status',
            'canonical_projection_status', 'provider_confidence', 'proposed')})
        self.assertNotIn('evidence', summary)
        self.assertNotIn('canonical_binding', summary)
        self.assertIn('synthetic evidence', self.store.inspect('cited')['evidence'][0]['citation']['context'])
        self.assertIn('synthetic binding', self.store.inspect('cited')['canonical_binding']['context'])

    def test_source_derived_owner_views_fit_worker_deadline_at_207_proposals(self):
        evidence = [f'row-{i}' for i in range(16000)]
        refs = {ident: {'source_sha256': 'c' * 64, 'refs': [],
                        'source_derived_ref': {'kind': 'source-derived',
                                               'source_sha256': 'c' * 64,
                                               'citation': {'row': i}}}
                for i, ident in enumerate(evidence)}
        self.store.bind_snapshot(SNAP_A, evidence, expected_revision=0,
                                 idempotency_key='large-bind', _observation_refs=refs)
        proposals = []
        for i in range(207):
            ident = f'row-{i}'
            revision = refs[ident]['source_derived_ref']
            p = proposal(f'candidate-{i}', evidence=ident, score=i / 207)
            p['evidence'][0] = {'id': ident, 'version': revision,
                                'citation': {'source_citation': {'source-sha256': 'c' * 64,
                                                                 'locator': revision['citation']},
                                             'observation_revision': revision}}
            p['canonical_binding'] = {
                'decision_id': p['id'], 'observation_revisions': [revision],
                'evidence_bindings': [{'snapshot_record_id': ident,
                                       'observation_revision': revision}]}
            proposals.append(p)
        self.store.register_batch(SNAP_A, proposals, idempotency_key='large-register')
        started = time.monotonic()
        queue = self.store.queue(limit=50)
        projection = self.store.projection()
        elapsed = time.monotonic() - started
        self.assertEqual(queue['total'], 207)
        self.assertEqual([item['id'] for item in queue['items']],
                         [f'candidate-{i}' for i in range(50)])
        self.assertEqual(queue['items'][0]['evidence'][0]['citation']['source_citation']['locator'],
                         {'row': 0})
        self.assertEqual(projection['snapshot_sha256'], SNAP_A)
        self.assertEqual(projection['active_decisions'], [])
        self.assertLess(elapsed, 1.5, f'owner views took {elapsed:.2f}s')

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

    def test_batch_reuses_active_binding_and_checks_corrections_once(self):
        self.bind(evidence=tuple(f'row-{i}' for i in range(4000)))
        statements = []
        self.store.db.set_trace_callback(statements.append)
        try:
            revisions = self.store.revision
            batch = [proposal(f'batch-{i}', evidence=f'row-{i}') for i in range(30)]
            results = self.store.register_batch(
                SNAP_A, batch, idempotency_key='large-batch', expected_revision=revisions)
        finally:
            self.store.db.set_trace_callback(None)
        self.assertEqual(len(results), 30)
        self.assertTrue(all(item['effective_status'] == 'pending' for item in results))
        binding_reads = [sql for sql in statements if 'FROM bindings ORDER BY' in sql]
        proposal_scans = [sql for sql in statements if sql.strip() == 'SELECT id FROM proposals']
        correction_reads = [sql for sql in statements if "e.action='correct'" in sql]
        self.assertLessEqual(len(binding_reads), 1)
        self.assertEqual(proposal_scans, [])
        self.assertEqual(len(correction_reads), 1)

    def test_batch_conflict_rolls_back_when_human_correction_exists(self):
        self.bind()
        self.store.register(SNAP_A, proposal('corrected', subject='person-1'),
                            idempotency_key='register-corrected')
        self.store.act('corrected', action='correct', correction={'athlete': 'person-2'},
                       expected_revision=self.store.revision, idempotency_key='correct-person')
        before = self.store.revision
        with self.assertRaisesRegex(ConflictError, 'human correction'):
            self.store.register_batch(
                SNAP_A, [proposal('safe'), proposal('blocked', subject='person-1')],
                idempotency_key='corrected-batch', expected_revision=before)
        self.assertEqual(self.store.revision, before)
        with self.assertRaises(KeyError):
            self.store.inspect('safe')
        with self.assertRaises(ConflictError):
            self.store.register_batch(
                SNAP_A, [proposal('stale')], idempotency_key='stale-batch',
                expected_revision=before - 1)

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

    def test_delivery_resumes_after_target_commits_before_owner_checkpoint(self):
        self.bind()
        self.store.register(SNAP_A, proposal('d1'), idempotency_key='register-d1')
        self.store.act('d1', action='approve', expected_revision=self.store.revision,
                       idempotency_key='approve-d1')
        event = self.store.human_events()['events'][0]
        target = sqlite3.connect(Path(self.tmp.name) / 'target.sqlite')
        target.execute('CREATE TABLE imported (id TEXT PRIMARY KEY, event_sha256 TEXT NOT NULL)')
        calls = []

        def flow(item):
            calls.append(('flow', item['id']))
            digest = hashlib.sha256(json.dumps(item, sort_keys=True,
                                               separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
            old = target.execute('SELECT event_sha256 FROM imported WHERE id=?', (item['id'],)).fetchone()
            if old and old[0] != digest:
                raise ValueError('conflicting replay')
            target.execute('INSERT OR IGNORE INTO imported VALUES (?,?)', (item['id'], digest))
            target.commit()
            if len([name for name, _ in calls if name == 'flow']) == 1:
                raise RuntimeError('crash after target commit')
            return digest

        def postgres(item):
            calls.append(('postgres', item['id']))
            return 'postgres-commit:' + item['id']

        first = self.store.deliver_human_events([('flow-ledger', flow), ('postgresql', postgres)])
        self.assertEqual(first['status'], 'retry_required')
        self.assertEqual(first['failed_target'], 'flow-ledger')
        self.assertEqual(first['checkpoints'], {'flow-ledger': 0, 'postgresql': 0})
        self.assertEqual(target.execute('SELECT count(*) FROM imported').fetchone()[0], 1)
        self.store.close()
        self.store = DecisionStore(self.path)
        second = self.store.deliver_human_events([('flow-ledger', flow), ('postgresql', postgres)])
        self.assertEqual(second['status'], 'complete')
        self.assertEqual(second['checkpoints'], {'flow-ledger': event['store_revision'],
                                                 'postgresql': event['store_revision']})
        self.assertEqual(calls, [('flow', event['id']), ('flow', event['id']),
                                 ('postgres', event['id'])])
        self.assertEqual(target.execute('SELECT count(*) FROM imported').fetchone()[0], 1)
        target.close()

    def test_delivery_preserves_destination_order_and_checkpoint_after_failure(self):
        self.bind()
        for ident in ('d1', 'd2'):
            self.store.register(SNAP_A, proposal(ident), idempotency_key='register-' + ident)
            self.store.act(ident, action='approve', expected_revision=self.store.revision,
                           idempotency_key='approve-' + ident)
        events = self.store.human_events()['events']
        calls = []

        def flow(item):
            calls.append(('flow', item['id']))
            return 'flow:' + item['id']

        def postgres(item):
            calls.append(('postgres', item['id']))
            if item['id'] == events[1]['id'] and calls.count(('postgres', item['id'])) == 1:
                raise OSError('temporary target failure')
            return 'postgres:' + item['id']

        first = self.store.deliver_human_events([('flow-ledger', flow), ('postgresql', postgres)])
        self.assertEqual(first['status'], 'retry_required')
        self.assertEqual(first['checkpoints'], {'flow-ledger': events[1]['store_revision'],
                                                'postgresql': events[0]['store_revision']})
        self.store.close()
        self.store = DecisionStore(self.path)
        second = self.store.deliver_human_events([('flow-ledger', flow), ('postgresql', postgres)])
        self.assertEqual(second['status'], 'complete')
        self.assertEqual(second['delivered'], {'flow-ledger': 0, 'postgresql': 1})
        self.assertEqual(calls, [('flow', events[0]['id']), ('flow', events[1]['id']),
                                 ('postgres', events[0]['id']), ('postgres', events[1]['id']),
                                 ('postgres', events[1]['id'])])
        with self.assertRaises(ValueError):
            self.store.deliver_human_events([('flow-ledger', flow)])

    def test_delivery_reports_new_event_appended_during_transfer(self):
        self.bind()
        for ident in ('d1', 'd2'):
            self.store.register(SNAP_A, proposal(ident), idempotency_key='register-' + ident)
        self.store.act('d1', action='approve', expected_revision=self.store.revision,
                       idempotency_key='approve-d1')

        def flow(item):
            return 'flow:' + item['id']

        def postgres(item):
            self.store.act('d2', action='approve', expected_revision=self.store.revision,
                           idempotency_key='approve-d2')
            return 'postgres:' + item['id']

        first = self.store.deliver_human_events([('flow-ledger', flow), ('postgresql', postgres)])
        self.assertEqual(first['status'], 'retry_required')
        self.assertEqual(first['reason'], 'new_events')


if __name__ == '__main__':
    unittest.main()
