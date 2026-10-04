import copy
import unittest

from scripts.aida_identity_replay import plan_replay
from scripts.retained_aida_cohort import build_cohort
from test_aida_identity_replay import SHA, PERSON_ONE, observation


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


if __name__ == '__main__':
    unittest.main()
