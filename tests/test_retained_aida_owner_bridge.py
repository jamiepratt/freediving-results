import copy
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from scripts.retained_aida_owner_bridge import build_owner_export
from scripts.aida_snapshot_observations import load_source_observations
from scripts.owner_decision_store import DecisionStore
from scripts.owner_decision_export_adapter import register_verified_export
from tests.test_aida_snapshot_observations import source_fixture
from tests.test_retained_aida_promotion import fixture, SHA


def inputs():
    handoff, rows, owner = fixture()
    for row in rows:
        row['citation'] = row['source_observation_ref']['citation']
        row['observation_version'] = row['source_observation_ref']['observation_version']
    events = handoff['canonical_readback']['events']
    pair = events[1]['pair']
    refs = {('source-observation:' + row['snapshot_record_id']):
            row['source_observation_ref'] for row in rows}
    evidence = [{'evidence-id': 'identity-' + ident, 'citation': refs[ident]}
                for ident in pair]
    decision = {'id': 'identity-edge-2', 'family': 'identity', 'action': 'same-person',
                'choices': ['same-person', 'different-person', 'unknown'],
                'subject': {'pair': pair, 'target-id': pair[0],
                            'observation-versions': {ident: refs[ident] for ident in pair}},
                'candidates': list(pair), 'evidence': evidence, 'dependencies': [],
                'evidence-adequate?': True}
    flow = {'version': 'reconciliation-flow/1', 'events': [
        {'id': 'flow-edge-2', 'decision-id': decision['id'],
         'family': 'identity', 'action': 'same-person', 'origin': 'deterministic',
         'status': 'unresolved', 'reason': 'source-context-unverified',
         'rule-version': 'source-identity/1', 'policy-version': 'policy/1',
         'evidence': evidence, 'dependencies': []}]}
    request = copy.deepcopy(events[1]['request'])
    request['base-revision'] = 0
    package = {'schema': 'retained-aida-promotion-preflight/v1',
               'snapshot_sha256': SHA, 'source_rows': handoff['canonical_readback']['source-rows'],
               'expected_production_revision': 0,
               'canonical_replay': [request],
               'owner': {'binding_revision': 1, 'store_revision': 1,
                         'status': 'requires_reconciliation_binding',
                         'candidate_intents': [{'source_event_id': events[1]['id'],
                                                'pair': pair,
                                                'source_binding': events[1]['source-binding'],
                                                'status': 'pending'}]},
               'counts': {'source_rows': 3, 'canonical_events': 1,
                          'human_reversals': 0, 'owner_candidate_intents': 1}}
    return copy.deepcopy((package, {'snapshot_sha256': SHA, 'observations': rows, 'gaps': []},
                          owner, flow, [decision]))


class RetainedAidaOwnerBridgeTest(unittest.TestCase):
    def test_active_canonical_edge_becomes_pending_cited_owner_proposal(self):
        package, loaded, owner, flow, decisions = inputs()
        result = build_owner_export(package, loaded, owner, flow, decisions)
        proposal, = result['proposals']
        self.assertEqual(result['reconciliation_run_revision'], 1)
        self.assertEqual(proposal['status'], 'pending')
        self.assertEqual(proposal['selected_option'], 'same_person')
        self.assertEqual(proposal['canonical_binding']['reconciliation_event_id'], 'flow-edge-2')
        self.assertEqual(proposal['canonical_binding']['source_event_id'], 'edge-2')
        self.assertEqual({item['id'] for item in proposal['evidence']},
                         {row['snapshot_record_id'] for row in loaded['observations'][::2]})
        self.assertIsNone(proposal['score'])

    def test_stale_collision_and_human_reversal_fail_closed(self):
        baseline = inputs()
        mutations = [
            lambda x: x[2].update(store_revision=2),
            lambda x: x[1]['observations'][0]['source_observation_ref'].update(source_sha256='0' * 64),
            lambda x: x[3]['events'][0].update(reason='thresholds-met'),
            lambda x: x[4][0]['evidence'][0].update(citation={'forged': True}),
            lambda x: x[1]['observations'][2]['source_fields'].update(name='Different name'),
            lambda x: x[0]['canonical_replay'].append({
                'id': 'reverse-edge-2', 'action': 'reverse', 'actor-kind': 'human',
                'base-revision': 1, 'event-id': 'edge-2', 'reason': 'different people',
                'source-binding': x[0]['canonical_replay'][0]['source-binding']}),
        ]
        for mutation in mutations:
            data = copy.deepcopy(baseline)
            mutation(data)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                build_owner_export(*data)

    def test_missing_flow_and_extra_canonical_candidates_stop_export(self):
        package, loaded, owner, flow, decisions = inputs()
        with self.assertRaisesRegex(ValueError, 'private reconciliation lineage missing'):
            build_owner_export(package, loaded, owner, {}, decisions)
        decisions[0]['candidates'].append('source-observation:' + '2' * 64)
        with self.assertRaisesRegex(ValueError, 'source identity decision differs'):
            build_owner_export(package, loaded, owner, flow, decisions)

    def test_bridge_envelope_passes_store_contract_as_pending(self):
        package, loaded, owner, flow, decisions = inputs()
        envelope = build_owner_export(package, loaded, owner, flow, decisions)
        with tempfile.TemporaryDirectory() as temporary:
            store = DecisionStore(Path(temporary) / 'owner.sqlite')
            try:
                store.db.execute("UPDATE meta SET value='1' WHERE key='revision'")
                store.db.execute('INSERT INTO bindings '
                                 '(revision,snapshot_sha256,evidence_ids_json,observation_refs_json) '
                                 'VALUES (?,?,?,?)',
                                 (1, SHA, json.dumps([row['snapshot_record_id']
                                                      for row in loaded['observations']]),
                                  json.dumps(owner['observation_refs'])))
                proposal, = envelope['proposals']
                result = store.register_batch(SHA, [proposal],
                                              idempotency_key='synthetic-bridge',
                                              expected_revision=1)
                self.assertEqual(result[0]['effective_status'], 'pending')
                self.assertEqual(store.inspect(proposal['id'])['canonical_binding'],
                                 proposal['canonical_binding'])
            finally:
                store.close()

    def test_verified_registration_replays_recovered_packet(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot = root / 'snapshot'
            snapshot.mkdir()
            name, _ = source_fixture(snapshot,
                '<div class="event-title--description">Synthetic event</div>',
                'https://www.aidainternational.org/EventPage/4408')
            loaded = load_source_observations(snapshot, [name])
            row, = loaded['observations']
            ref = row['source_observation_ref']
            store = DecisionStore(root / 'owner.sqlite')
            try:
                store.bind_verified_snapshot(snapshot, expected_revision=0,
                                             idempotency_key='bind')
                recovered = root / 'recovered'
                recovered.mkdir()
                shutil.copytree(snapshot / 'raw', recovered / 'raw')
                shutil.move(snapshot / 'packet.json', recovered / 'packet.json')
                shutil.move(snapshot / 'receipt.json', recovered / 'receipt.json')
                proposal = {'id': 'source-decision-1', 'type': 'identity',
                            'subject_id': 'source-person-1', 'source_name': name,
                            'original': {'athlete_names': ['Synthetic Athlete']},
                            'proposed': {'action': 'unknown'}, 'selected_option': 'unknown',
                            'competing_options': ['same_person'],
                            'evidence': [{'id': row['snapshot_record_id'], 'version': ref,
                                'citation': {'evidence_id': 'identity-1',
                                             'source_citation': {'source-sha256': ref['source_sha256'],
                                                                 'locator': ref['citation']},
                                             'observation_revision': ref}}],
                            'supporting_evidence': [], 'conflicting_evidence': [],
                            'depends_on': [], 'groups': [], 'score': None,
                            'provider_confidence': None, 'rule_version': 'rule/1',
                            'model_version': None, 'policy_version': 'policy/1',
                            'status': 'pending',
                            'canonical_binding': {'decision_id': 'source-decision-1',
                                'reconciliation_run_revision': 1,
                                'reconciliation_event_id': 'flow-event-1',
                                'observation_revisions': [ref],
                                'evidence_bindings': [{'evidence_id': 'identity-1',
                                    'snapshot_record_id': row['snapshot_record_id'],
                                    'observation_revision': ref}]}}
                envelope = {'snapshot_sha256': loaded['snapshot_sha256'],
                            'binding_revision': store.revision, 'store_revision': store.revision,
                            'reconciliation_run_revision': 1, 'proposals': [proposal]}
                with self.assertRaises(ValueError):
                    register_verified_export(store, snapshot, envelope)
                result = register_verified_export(
                    store, snapshot, envelope,
                    recovered_packet_paths={name: recovered / 'packet.json'})
                self.assertEqual(result[0]['status'], 'pending')
                self.assertEqual(store.inspect(proposal['id'])['effective_status'], 'pending')
            finally:
                store.close()


if __name__ == '__main__':
    unittest.main()
