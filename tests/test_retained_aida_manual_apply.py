import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.retained_aida_manual_apply import HostStores, run_manual_apply


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
        self.active = True
        self.active_checks = 0
        self.expire_active_on_check = None

    def verify_active(self):
        self.active_checks += 1
        if not self.active or self.active_checks == self.expire_active_on_check:
            raise ValueError('active snapshot binding changed')

    def verify_status_application_expectation(self, *_):
        pass

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

    def test_changed_active_binding_stops_before_canonical_write(self):
        self.stores.active = False
        with self.assertRaisesRegex(ValueError, 'active snapshot'):
            self.run_apply()
        self.assertEqual(self.stores.target['revision'], 0)
        self.assertEqual(self.stores.owner['proposal_count'], 0)

    def test_active_binding_expiring_after_backups_stops_write(self):
        self.stores.expire_active_on_check = 2
        with self.assertRaisesRegex(ValueError, 'active snapshot'):
            self.run_apply()
        self.assertEqual(self.stores.target['revision'], 0)
        self.assertEqual(self.stores.owner['proposal_count'], 0)

    def test_isolated_active_binding_is_hash_pinned(self):
        path = self.directory / 'active.json'
        path.write_text(json.dumps({'snapshot_sha256': self.stores.snapshot,
                                    'bundle_manifest_sha256': 'b' * 64}))
        path.chmod(0o600)
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        host = HostStores(self.directory / 'owner.sqlite', self.directory, {},
                          self.stores.snapshot, self.directory, None, 0, True,
                          'b' * 64, path, sha)
        host.verify_active()
        path.write_text(json.dumps({'snapshot_sha256': self.stores.snapshot,
                                    'bundle_manifest_sha256': 'c' * 64}))
        with self.assertRaisesRegex(ValueError, 'input file changed'):
            host.verify_active()

    def test_live_status_provenance_blocks_changed_revision_before_write(self):
        snap = self.stores.snapshot
        active = {'snapshot_sha256': snap, 'bundle_manifest_sha256': 'b' * 64}
        current = {'schema': 'private-presentation-status/v1', 'revision': 1,
                   'run_id': 'completed-run',
                   'local': {'snapshot_sha256': snap,
                             'cutoff': '2026-10-03T00:00:00Z', 'gap_count': 0},
                   'remote': {'status': 'active', 'pending': None,
                              'failed': None, 'active': active}}
        host = HostStores(self.directory / 'owner.sqlite', self.directory, {},
                          snap, self.directory, None, 0, False, 'b' * 64,
                          status_from_current=True)
        env = {'CF_ACCESS_CLIENT_ID': 'id', 'CF_ACCESS_CLIENT_SECRET': 'secret',
               'OWNER_EVIDENCE_STATUS_TOKEN': 'token'}
        with patch.dict('os.environ', env), patch('private_status_sync._request',
              side_effect=[current, {**current, 'revision': 2}]):
            host.verify_active()
            with self.assertRaisesRegex(RuntimeError, 'revision changed'):
                host.verify_active()

    def test_existing_v3_must_match_expected_apply_before_backup(self):
        snap = self.stores.snapshot
        active = {'snapshot_sha256': snap, 'bundle_manifest_sha256': 'b' * 64}
        current = {'schema': 'private-presentation-status/v3', 'revision': 2,
                   'run_id': 'completed-run',
                   'local': {'snapshot_sha256': snap,
                             'cutoff': '2026-10-03T00:00:00Z', 'gap_count': 0},
                   'remote': {'status': 'active', 'pending': None,
                              'failed': None, 'active': active},
                   'application': {'snapshot_sha256': snap,
                                   'canonical_revision': 999,
                                   'owner_store_revision': 2,
                                   'pending_proposals': 1,
                                   'unresolved_exclusions': 0,
                                   'provider_calls_recorded': 0,
                                   'publication_status': 'private'}}
        host = HostStores(self.directory / 'owner.sqlite', self.directory, {},
                          snap, self.directory, None, 0, False, 'b' * 64,
                          status_from_current=True)
        env = {'CF_ACCESS_CLIENT_ID': 'id', 'CF_ACCESS_CLIENT_SECRET': 'secret',
               'OWNER_EVIDENCE_STATUS_TOKEN': 'token'}
        with patch.dict('os.environ', env), patch('private_status_sync._request',
              return_value=current):
            host.verify_active()
            with self.assertRaisesRegex(ValueError, 'existing private application differs'):
                host.verify_status_application_expectation(2, 2, 1)


if __name__ == '__main__':
    unittest.main()
