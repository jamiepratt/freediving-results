import copy
import unittest

from scripts.aida_identity_replay import plan_replay


SHA = 'a' * 64
PERSON_ONE = '11111111-1111-1111-1111-111111111111'
PERSON_TWO = '22222222-2222-2222-2222-222222222222'


def identity_id(number):
    return f'source-observation:{number:064x}'


def observation(number, person, *, name='Source Athlete', view='view-one'):
    record = f'{number:064x}'
    publisher = {'scope': 'AIDA', 'id': person, 'id_kind': 'person'}
    ref = {'kind': 'source-derived', 'snapshot_sha256': SHA,
           'snapshot_record_id': record, 'source_name': view,
           'observation_version': f'{number + 100:064x}',
           'adapter_version': 'aida-snapshot-observation/3',
           'publisher_person': dict(publisher)}
    return {'snapshot_record_id': record, 'source_name': view,
            'source_observation_ref': ref, 'observation_version': ref['observation_version'],
            'adapter_version': ref['adapter_version'],
            'source_fields': {'name': name, 'publisher_person': publisher}}


class AidaIdentityReplayTest(unittest.TestCase):
    def test_repeated_person_gets_minimal_stable_edges_with_source_refs(self):
        rows = [observation(3, PERSON_ONE, view='view-two'),
                observation(1, PERSON_ONE), observation(2, PERSON_ONE),
                observation(4, PERSON_TWO)]
        report = plan_replay(rows, expected_snapshot_sha256=SHA)
        self.assertEqual(report['denominator'], {'rows': 4, 'publisher_person_ids': 2,
                         'repeated_person_groups': 1, 'repeated_person_rows': 3,
                         'within_person_pairs': 3})
        self.assertEqual(len(report['edges']), 2)
        self.assertEqual([edge['pair'] for edge in report['edges']],
                         [[identity_id(1), identity_id(2)],
                          [identity_id(1), identity_id(3)]])
        self.assertEqual(report['counts']['eligible_groups'], 1)
        self.assertEqual(report['counts']['candidate_edges'], 2)
        self.assertEqual(report['scope'], 'AIDA source observations')
        self.assertIsNone(report['global_accepted_athletes'])
        self.assertEqual(report['edges'][0]['refs'][identity_id(1)],
                         rows[1]['source_observation_ref'])
        self.assertEqual(plan_replay(list(reversed(rows)), expected_snapshot_sha256=SHA), report)

    def test_rejects_missing_or_conflicting_publisher_evidence(self):
        baseline = observation(1, PERSON_ONE)
        for mutate in (
            lambda row: row['source_fields'].pop('publisher_person'),
            lambda row: row['source_observation_ref']['publisher_person'].update(id=PERSON_TWO),
            lambda row: row['source_observation_ref'].update(observation_version='b' * 64),
            lambda row: row['source_fields']['publisher_person'].update(scope='Other'),
            lambda row: (row['source_fields']['publisher_person'].update(id='not-a-profile'),
                         row['source_observation_ref']['publisher_person'].update(id='not-a-profile')),
        ):
            row = copy.deepcopy(baseline)
            mutate(row)
            with self.subTest(row=row), self.assertRaises(ValueError):
                plan_replay([row], expected_snapshot_sha256=SHA)

    def test_human_reversal_freezes_group_and_rerun_does_not_restore_edge(self):
        rows = [observation(1, PERSON_ONE), observation(2, PERSON_ONE),
                observation(3, PERSON_ONE)]
        pair = [identity_id(1), identity_id(2)]
        human = [{'action': 'reverse', 'actor_kind': 'human', 'pair': pair}]
        report = plan_replay(rows, expected_snapshot_sha256=SHA, prior_events=human)
        self.assertEqual(report['edges'], [])
        self.assertEqual(report['counts']['blocked_groups'], 1)
        self.assertEqual(report['counts']['candidate_edges'], 0)
        self.assertEqual(plan_replay(rows, expected_snapshot_sha256=SHA, prior_events=human), report)

    def test_prior_automatic_edge_is_not_replanned(self):
        rows = [observation(1, PERSON_ONE), observation(2, PERSON_ONE)]
        pair = [identity_id(1), identity_id(2)]
        report = plan_replay(rows, expected_snapshot_sha256=SHA,
                             prior_events=[{'action': 'accept', 'actor_kind': 'automatic',
                                            'pair': pair}])
        self.assertEqual(report['edges'], [])
        self.assertEqual(report['counts']['already_active_edges'], 1)

    def test_existing_nonstar_edge_is_not_closed_into_a_cycle(self):
        rows = [observation(1, PERSON_ONE), observation(2, PERSON_ONE),
                observation(3, PERSON_ONE)]
        report = plan_replay(rows, expected_snapshot_sha256=SHA,
                             prior_events=[{'action': 'accept', 'actor_kind': 'automatic',
                                            'pair': [identity_id(2), identity_id(3)]}])
        self.assertEqual([edge['pair'] for edge in report['edges']],
                         [[identity_id(1), identity_id(2)]])
        self.assertEqual(report['counts']['already_active_edges'], 1)


if __name__ == '__main__':
    unittest.main()
