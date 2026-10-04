import copy
import tempfile
import unittest
from pathlib import Path

from scripts.retained_aida_manual_apply import run_manual_apply


class SyntheticStores:
    def __init__(self):
        self.snapshot = 'a' * 64
        self.row = {'observation-id': 'source-observation:' + '1' * 64,
                    'source-observation-ref': {'snapshot_sha256': self.snapshot},
                    'citation': {'snapshot_sha256': self.snapshot}}
        self.events = [
            {'id': 'accept', 'action': 'accept', 'actor-kind': 'automatic',
             'pair': ['one', 'two'], 'base-revision': 0},
            {'id': 'reverse', 'action': 'reverse', 'actor-kind': 'human',
             'event-id': 'accept', 'reason': 'correction', 'base-revision': 1},
        ]
        self.target = {'schema': 'retained-aida-target-state/v1',
                       'snapshot_sha256': self.snapshot, 'revision': 0,
                       'events': [], 'source_rows': [], 'non_source_row_count': 0}
        self.owner = {'store_revision': 1, 'binding_revision': 1,
                      'snapshot_sha256': self.snapshot, 'proposal_count': 0,
                      'human_event_count': 0}
        self.fail_at = None
        self.status = None

    def target_state(self):
        return copy.deepcopy(self.target)

    def owner_state(self):
        return copy.deepcopy(self.owner)

    def backup_postgres(self, directory):
        (directory / 'postgres.dump').write_bytes(b'backup')
        return 'postgres.dump'

    def verify_postgres_backup(self, directory, name):
        return (directory / name).read_bytes() == b'backup'

    def restore_drill(self, directory, name):
        return self.verify_postgres_backup(directory, name)

    def apply_canonical(self, cohort):
        self.target['source_rows'] = copy.deepcopy(cohort['registration']['rows'])
        if self.fail_at == 'source':
            self.fail_at = None
            raise RuntimeError('interrupted after source registration')
        for event in cohort['events'][self.target['revision']:]:
            self.target['events'].append({'id': event['id'], 'request': copy.deepcopy(event)})
            self.target['revision'] += 1
            if self.fail_at == 'reversal' and event['action'] == 'reverse':
                self.fail_at = None
                raise RuntimeError('interrupted after reversal')

    def register_owner(self, envelope):
        self.owner['proposal_count'] = len(envelope['proposals'])
        self.owner['human_event_count'] = len(envelope['proposals'])
        self.owner['store_revision'] += len(envelope['proposals'])
        if self.fail_at == 'owner':
            self.fail_at = None
            raise RuntimeError('interrupted after owner registration')

    def verify_owner(self, envelope):
        return self.owner['proposal_count'] == len(envelope['proposals'])

    def commit_status(self, target, owner, envelope):
        self.status = {'canonical_revision': target['revision'],
                       'owner_revision': owner['store_revision']}
        if self.fail_at == 'status':
            self.fail_at = None
            raise RuntimeError('interrupted after status write')
        return self.status


class ManualApplyTest(unittest.TestCase):
    def setUp(self):
        self.stores = SyntheticStores()
        self.preflight = {'schema': 'retained-aida-promotion-preflight/v1',
                          'snapshot_sha256': self.stores.snapshot,
                          'expected_production_revision': 0,
                          'source_rows': [self.stores.row],
                          'canonical_replay': self.stores.events,
                          'owner': {'store_revision': 1, 'binding_revision': 1},
                          'counts': {'source_rows': 1, 'canonical_events': 2}}
        self.envelope = {'proposals': [{'id': 'pending', 'status': 'pending'}]}
        self.pins = {'preflight_sha256': 'b' * 64, 'envelope_sha256': 'c' * 64,
                     'flow_sha256': 'd' * 64, 'checkpoint_sha256': 'e' * 64}
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.directory.chmod(0o700)

    def run_apply(self):
        return run_manual_apply(self.stores, self.preflight, self.envelope,
                                self.pins, self.directory)

    def test_retries_after_source_registration_and_human_reversal(self):
        for stage in ('source', 'reversal'):
            with self.subTest(stage=stage):
                self.stores.fail_at = stage
                with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                    self.run_apply()
                result = self.run_apply()
                self.assertEqual(result['status'], 'complete')
                self.assertEqual(self.stores.target['revision'], 2)
                self.assertEqual(self.run_apply(), result)
                self.stores = SyntheticStores()
                self.directory = Path(tempfile.mkdtemp())
                self.directory.chmod(0o700)

    def test_changed_target_fails_closed(self):
        self.stores.target['events'] = [{'id': 'foreign', 'request': {}}]
        self.stores.target['revision'] = 1
        with self.assertRaisesRegex(ValueError, 'canonical target'):
            self.run_apply()
        self.assertEqual(self.stores.owner['proposal_count'], 0)

    def test_retry_after_owner_and_status_writes(self):
        for stage in ('owner', 'status'):
            with self.subTest(stage=stage):
                self.stores.fail_at = stage
                with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                    self.run_apply()
                result = self.run_apply()
                self.assertEqual(result['status'], 'complete')
                self.assertEqual(self.stores.owner['proposal_count'], 1)
                self.assertEqual(self.stores.status['canonical_revision'], 2)
                self.stores = SyntheticStores()
                self.directory = Path(tempfile.mkdtemp())
                self.directory.chmod(0o700)

    def test_exact_prefix_through_revision_189_reversal(self):
        accepts = [{'id': f'accept-{index}', 'action': 'accept',
                    'actor-kind': 'automatic', 'pair': ['one', 'two'],
                    'base-revision': index} for index in range(189)]
        reversal = {'id': 'human-reversal', 'action': 'reverse',
                    'actor-kind': 'human', 'event-id': 'accept-0',
                    'reason': 'owner correction', 'base-revision': 189}
        self.stores.events = accepts + [reversal]
        self.preflight['canonical_replay'] = self.stores.events
        self.preflight['counts']['canonical_events'] = 190
        self.stores.target['source_rows'] = [self.stores.row]
        self.stores.target['events'] = [{'id': event['id'], 'request': event}
                                        for event in accepts]
        self.stores.target['revision'] = 189
        self.assertEqual(self.run_apply()['canonical_revision'], 190)
        self.assertEqual(self.stores.target['events'][189]['request']['actor-kind'], 'human')

    def test_owner_human_action_after_registration_blocks_status_retry(self):
        self.run_apply()
        self.stores.owner['human_event_count'] += 1
        with self.assertRaisesRegex(ValueError, 'owner revision'):
            self.run_apply()


if __name__ == '__main__':
    unittest.main()
