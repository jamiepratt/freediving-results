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


def multi_candidate_inputs(count=3):
    data = list(inputs())
    package, loaded, owner, flow, decisions = data
    while len(loaded['observations']) < count:
        row = copy.deepcopy(loaded['observations'][1])
        record = format(len(loaded['observations']) + 1, 'x') * 64
        row['snapshot_record_id'] = record
        row['source_observation_ref']['snapshot_record_id'] = record
        row['source_observation_ref']['citation'] = {'row': record}
        row['citation'] = {'row': record}
        loaded['observations'].append(row)
        owner['observation_refs'][record] = {'source_derived_ref': row['source_observation_ref']}
        package['source_rows'].append({'observation-id': 'source-observation:' + record,
                                       'source-observation-ref': row['source_observation_ref'],
                                       'citation': row['source_observation_ref']})
    pair = decisions[0]['subject']['pair']
    candidates = [pair[0], pair[1]] + [
        'source-observation:' + row['snapshot_record_id']
        for row in loaded['observations'] if 'source-observation:' + row['snapshot_record_id'] not in pair]
    candidates = candidates[:count]
    refs = {'source-observation:' + row['snapshot_record_id']: row['source_observation_ref']
            for row in loaded['observations']}
    decision = decisions[0]
    decision['candidates'] = candidates
    decision['subject']['observation-versions'] = {ident: refs[ident] for ident in candidates}
    decision['evidence'] = [{'evidence-id': 'identity-' + ident,
                             'citation': refs[ident]} for ident in candidates]
    flow['events'][0]['evidence'] = copy.deepcopy(decision['evidence'])
    return data


def corrected_coverage_inputs():
    data = list(inputs())
    package, loaded, owner, flow, decisions = data
    refs = {}
    for index in (4, 5, 6):
        row = copy.deepcopy(loaded['observations'][1])
        record = format(index, 'x') * 64
        row['snapshot_record_id'] = record
        row['source_observation_ref']['snapshot_record_id'] = record
        row['source_observation_ref']['citation'] = {'row': record}
        row['citation'] = {'row': record}
        row['source_fields']['name'] = 'Corrected'
        row['source_fields']['publisher_person']['id'] = 'corrected'
        row['source_observation_ref']['publisher_person']['id'] = 'corrected'
        loaded['observations'].append(row)
        owner['observation_refs'][record] = {'source_derived_ref': row['source_observation_ref']}
        ident = 'source-observation:' + record
        refs[ident] = row['source_observation_ref']
        package['source_rows'].append({'observation-id': ident,
                                       'source-observation-ref': row['source_observation_ref'],
                                       'citation': row['source_observation_ref']})
    ids = list(refs)
    def accept(name, pair, revision):
        return {'id': name, 'action': 'accept', 'actor-kind': 'automatic',
                'rule-version': 'source-rule/1', 'pair': pair,
                'base-revision': revision,
                'source-binding': {'snapshot-sha256': SHA,
                                   'refs': {ident: refs[ident] for ident in pair}}}
    corrected = accept('corrected-edge', [ids[0], ids[2]], 2)
    prior = accept('reversed-edge', [ids[0], ids[1]], 0)
    reverse = {'id': 'human-reversal', 'action': 'reverse', 'actor-kind': 'human',
               'event-id': prior['id'], 'base-revision': 1, 'reason': 'Different people',
               'source-binding': prior['source-binding']}
    package['canonical_replay'][0]['base-revision'] = 3
    package['canonical_replay'] = [prior, reverse, corrected] + package['canonical_replay']
    package['owner']['candidate_intents'].append(
        {'source_event_id': corrected['id'], 'pair': corrected['pair'],
         'source_binding': corrected['source-binding'], 'status': 'pending'})
    coverage = {'schema': 'retained-aida-flow-export/v1',
                'preflight_sha256': 'e' * 64,
                'snapshot_sha256': SHA,
                'counts': {'active_intents': 2, 'supported': 1, 'unresolved': 1},
                'decisions': copy.deepcopy(decisions), 'flow': copy.deepcopy(flow),
                'unresolved': [{'source_event_id': corrected['id'],
                                'reason': 'human-correction'}]}
    return data, coverage


class RetainedAidaOwnerBridgeTest(unittest.TestCase):
    def test_coverage_accounts_for_corrected_person_without_exporting_her_edge(self):
        data, coverage = corrected_coverage_inputs()
        result = build_owner_export(*data, coverage=coverage,
                                    expected_preflight_sha256='e' * 64)
        self.assertEqual(len(result['proposals']), 1)
        self.assertEqual(result['counts'],
                         {'active_intents': 2, 'supported': 1, 'unresolved': 1})

    def test_coverage_rejects_unproved_or_hidden_omissions(self):
        data, coverage = corrected_coverage_inputs()
        for mutation in (
            lambda c: c['unresolved'][0].update(reason='unsupported'),
            lambda c: c['unresolved'][0].update(source_event_id='other-edge'),
            lambda c: c['unresolved'][0].update(source_event_id='edge-2'),
            lambda c: c['counts'].update(active_intents=3),
            lambda c: c['decisions'].clear(),
        ):
            changed = copy.deepcopy(coverage)
            mutation(changed)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                build_owner_export(*data, coverage=changed,
                                   expected_preflight_sha256='e' * 64)
        with self.assertRaises(ValueError):
            build_owner_export(*data, coverage=coverage,
                               expected_preflight_sha256='f' * 64)
        package, loaded, owner, flow, decisions = inputs()
        unsupported = {'schema': 'retained-aida-flow-export/v1',
                       'snapshot_sha256': SHA,
                       'preflight_sha256': 'e' * 64,
                       'counts': {'active_intents': 1, 'supported': 0, 'unresolved': 1},
                       'decisions': [], 'flow': {'version': 'reconciliation-flow/1',
                                                'events': []},
                       'unresolved': [{'source_event_id': 'edge-2',
                                       'reason': 'human-correction'}]}
        with self.assertRaises(ValueError):
            build_owner_export(package, loaded, owner, unsupported['flow'], [],
                               coverage=unsupported,
                               expected_preflight_sha256='e' * 64)

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
        decisions[0]['candidates'].append('source-observation:' + 'f' * 64)
        with self.assertRaisesRegex(ValueError, 'source identity decision differs'):
            build_owner_export(package, loaded, owner, flow, decisions)

    def test_all_retrieved_candidates_are_bound_while_selected_pair_stays_exact(self):
        for count in (3, 10):
            with self.subTest(count=count):
                package, loaded, owner, flow, decisions = multi_candidate_inputs(count)
                proposal, = build_owner_export(package, loaded, owner, flow, decisions)['proposals']
                self.assertEqual(proposal['proposed']['subject']['pair'],
                                 decisions[0]['subject']['pair'])
                self.assertEqual(len(proposal['canonical_binding']['evidence_bindings']), count)
                self.assertEqual(len(proposal['evidence']), count)

    def test_unrelated_missing_or_stale_retrieved_candidate_fails_closed(self):
        for change in (
            lambda d: d[1]['observations'][1]['source_fields']['publisher_person'].update(id='other'),
            lambda d: d[4][0]['subject']['observation-versions'].pop(d[4][0]['candidates'][-1]),
            lambda d: d[3]['events'][0].update({'policy-version': ''}),
            lambda d: d[2].update(binding_revision=2),
        ):
            data = multi_candidate_inputs()
            change(data)
            with self.subTest(change=change), self.assertRaises(ValueError):
                build_owner_export(*data)

    def test_bridge_envelope_passes_store_contract_as_pending(self):
        package, loaded, owner, flow, decisions = multi_candidate_inputs()
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
