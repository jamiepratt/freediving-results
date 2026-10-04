import copy
import unittest

from scripts.retained_aida_guarded_apply import build_guarded_cohort, verify_target_prefix


class GuardedApplyTest(unittest.TestCase):
    def setUp(self):
        self.ref = {'snapshot_sha256': 'a' * 64, 'snapshot_record_id': '1' * 64}
        self.row = {'observation-id': 'source-observation:' + '1' * 64,
                    'source-observation-ref': self.ref, 'citation': self.ref}
        self.events = [
            {'id': 'edge', 'action': 'accept', 'actor-kind': 'automatic',
             'pair': ['one', 'two'], 'base-revision': 0},
            {'id': 'reverse', 'action': 'reverse', 'actor-kind': 'human',
             'event-id': 'edge', 'reason': 'correction', 'base-revision': 1},
        ]
        self.target = {'schema': 'retained-aida-target-state/v1',
                       'snapshot_sha256': 'a' * 64, 'revision': 0,
                       'events': [], 'source_rows': [], 'non_source_row_count': 0}

    def test_exact_empty_and_interrupted_prefix_resume(self):
        self.assertEqual(verify_target_prefix(self.target, [self.row], self.events), 0)
        self.target['source_rows'] = [self.row]
        self.assertEqual(verify_target_prefix(self.target, [self.row], self.events), 0)
        changed = copy.deepcopy(self.target)
        changed['source_rows'].append({'observation-id': 'foreign'})
        with self.assertRaisesRegex(ValueError, 'canonical target'):
            verify_target_prefix(changed, [self.row], self.events)
        self.target['revision'] = 1
        self.target['events'] = [{'id': 'edge', 'request': self.events[0]}]
        self.assertEqual(verify_target_prefix(self.target, [self.row], self.events), 1)

    def test_changed_reversal_or_source_row_stops_replay(self):
        self.target['revision'] = 2
        self.target['source_rows'] = [self.row]
        self.target['events'] = [{'id': event['id'], 'request': event}
                                 for event in self.events]
        self.assertEqual(verify_target_prefix(self.target, [self.row], self.events), 2)
        changed = copy.deepcopy(self.target)
        changed['events'][1]['request']['reason'] = 'different correction'
        with self.assertRaisesRegex(ValueError, 'canonical target'):
            verify_target_prefix(changed, [self.row], self.events)
        changed = copy.deepcopy(self.target)
        changed['source_rows'][0]['source-observation-ref']['snapshot_record_id'] = '2' * 64
        with self.assertRaisesRegex(ValueError, 'canonical target'):
            verify_target_prefix(changed, [self.row], self.events)
        changed = copy.deepcopy(self.target)
        changed['snapshot_sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'canonical target'):
            verify_target_prefix(changed, [self.row], self.events)

    def test_guarded_cohort_preserves_exact_reversal_and_target_binding(self):
        preflight = {'schema': 'retained-aida-promotion-preflight/v1',
                     'snapshot_sha256': 'a' * 64, 'source_rows': [self.row],
                     'canonical_replay': self.events,
                     'expected_production_revision': 0,
                     'counts': {'source_rows': 1, 'canonical_events': 2}}
        cohort = build_guarded_cohort(preflight, self.target)
        self.assertEqual(cohort['events'][1], self.events[1])
        self.assertEqual(cohort['binding'],
                         {'identity_revision': 0, 'history_event_ids': []})
        self.assertEqual(cohort['registration']['verified_refs'],
                         {self.row['observation-id']: self.ref})
        changed = copy.deepcopy(self.target)
        changed['revision'] = 1
        changed['events'] = [{'id': 'unrelated', 'request': {}}]
        with self.assertRaisesRegex(ValueError, 'canonical target'):
            build_guarded_cohort(preflight, changed)
        resumed = copy.deepcopy(self.target)
        resumed['revision'] = 1
        resumed['source_rows'] = [self.row]
        resumed['events'] = [{'id': self.events[0]['id'], 'request': self.events[0]}]
        self.assertEqual(build_guarded_cohort(preflight, resumed), cohort)


if __name__ == '__main__':
    unittest.main()
