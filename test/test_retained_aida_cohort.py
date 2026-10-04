import copy
import unittest

from scripts.aida_identity_replay import plan_replay
from scripts.retained_aida_cohort import build_cohort
from test_aida_identity_replay import SHA, PERSON_ONE, PERSON_TWO, identity_id, observation


class RetainedAidaCohortTest(unittest.TestCase):
    def test_only_currently_reverified_rows_enter_canonical_registration(self):
        current = [observation(1, PERSON_ONE), observation(2, PERSON_ONE)]
        missing = observation(3, PERSON_ONE)
        frozen = current + [missing]
        plan = plan_replay(frozen, expected_snapshot_sha256=SHA)
        loaded = {'snapshot_sha256': SHA, 'observations': current,
                  'gaps': [{'snapshot_record_id': missing['snapshot_record_id'],
                            'source_name': missing['source_name'], 'reason': 'retained-packet-missing'}]}
        cohort = build_cohort(loaded, frozen, plan, observations_sha256='b' * 64,
                              plan_sha256='c' * 64)
        self.assertEqual(cohort['schema'], 'retained-aida-cohort/v1')
        self.assertEqual(cohort['counts']['source_rows'], 2)
        self.assertEqual(cohort['counts']['source_gaps'], 1)
        self.assertEqual(len(cohort['registration']['rows']), 2)
        self.assertEqual(len(cohort['events']), 1)
        self.assertEqual(cohort['events'][0]['source_binding']['refs'],
                         plan['edges'][0]['refs'])

    def test_changed_current_source_ref_fails_closed(self):
        rows = [observation(1, PERSON_ONE), observation(2, PERSON_ONE)]
        plan = plan_replay(rows, expected_snapshot_sha256=SHA)
        changed = copy.deepcopy(rows)
        changed[0]['source_observation_ref']['observation_version'] = 'd' * 64
        loaded = {'snapshot_sha256': SHA, 'observations': changed, 'gaps': []}
        with self.assertRaisesRegex(ValueError, 'differs from frozen'):
            build_cohort(loaded, rows, plan, observations_sha256='b' * 64,
                         plan_sha256='c' * 64)

    def test_expanded_replay_preserves_human_reversal_and_adds_safe_group(self):
        rows = [observation(number, person) for number, person in
                ((1, PERSON_ONE), (2, PERSON_ONE), (3, PERSON_ONE),
                 (4, PERSON_TWO), (5, PERSON_TWO))]
        prior = {'schema': 'retained-aida-canonical-history/v1',
                 'revision': 3, 'snapshot_sha256': SHA,
                 'events': [
                     {'id': 'a1', 'action': 'accept', 'actor_kind': 'automatic',
                      'pair': [identity_id(1), identity_id(2)]},
                     {'id': 'a2', 'action': 'accept', 'actor_kind': 'automatic',
                      'pair': [identity_id(1), identity_id(3)]},
                     {'id': 'h1', 'action': 'reverse', 'actor_kind': 'human',
                      'event_id': 'a1'}]}
        cohort = build_cohort({'snapshot_sha256': SHA, 'observations': rows, 'gaps': []},
                              rows, plan_replay(rows, expected_snapshot_sha256=SHA),
                              observations_sha256='b' * 64, plan_sha256='c' * 64,
                              canonical_history=prior)
        self.assertEqual(cohort['binding']['identity_revision'], 3)
        self.assertEqual(cohort['binding']['history_event_ids'], ['a1', 'a2', 'h1'])
        self.assertEqual(cohort['counts']['blocked_groups'], 1)
        self.assertEqual(cohort['counts']['candidate_edges'], 1)
        self.assertEqual(cohort['events'][0]['pair'], [identity_id(4), identity_id(5)])

    def test_incomplete_or_mismatched_canonical_history_fails_closed(self):
        rows = [observation(1, PERSON_ONE), observation(2, PERSON_ONE)]
        loaded = {'snapshot_sha256': SHA, 'observations': rows, 'gaps': []}
        plan = plan_replay(rows, expected_snapshot_sha256=SHA)
        history = {'schema': 'retained-aida-canonical-history/v1',
                   'revision': 1, 'snapshot_sha256': SHA,
                   'events': [{'id': 'a1', 'action': 'accept',
                               'actor_kind': 'automatic',
                               'pair': [identity_id(1), identity_id(2)]}]}
        for changed in (
            dict(history, revision=2),
            dict(history, snapshot_sha256='d' * 64),
            dict(history, events=history['events'] * 2, revision=2),
        ):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                build_cohort(loaded, rows, plan, observations_sha256='b' * 64,
                             plan_sha256='c' * 64, canonical_history=changed)


if __name__ == '__main__':
    unittest.main()
