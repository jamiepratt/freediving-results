"""Guarded private canonical database provisioning contract."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

HELPER = Path(__file__).with_name('provision_canonical.py')
sys.path.insert(0, str(HELPER.parent))


def load():
    spec = importlib.util.spec_from_file_location('provision_canonical', HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProvisionCanonicalTests(unittest.TestCase):
    def test_migration_seed_and_new_merged_release_can_resume_original_intent(self):
        module = load()
        def query(_, sql, __):
            if 'FROM pg_tables' in sql:
                return 'evaluation_corpus\npublication_policy_events\nschema_migrations'
            if 'SELECT mode' in sql:
                return 'real'
            if 'SELECT policy_version' in sql:
                return 'extraction-publication/1'
            if 'evaluation_corpus' in sql:
                return '1'
            if 'publication_policy_events' in sql:
                return '1'
            return '20'
        with mock.patch.object(module, 'query', side_effect=query):
            self.assertTrue(module.target_empty('freediving_canonical', '5432'))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module.ensure_intent(root, 'public_release', '5432', 'a' * 40,
                                 {'migration.env': 'x', 'public.env': 'y'})
            module.ensure_intent(root, 'public_release', '5432', 'b' * 40,
                                 {'migration.env': 'x', 'public.env': 'y'})

    def test_public_target_and_unmarked_existing_database_refuse_before_mutation(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            args = self.args(root)
            self.config(root)
            with mock.patch.object(module, 'database_exists', return_value=True), \
                 mock.patch.object(module, 'postgres') as postgres:
                with self.assertRaises(ValueError):
                    module.provision(args)
                postgres.assert_not_called()
            args.database = 'public_release'
            with mock.patch.object(module, 'postgres') as postgres:
                with self.assertRaises(ValueError):
                    module.provision(args)
                postgres.assert_not_called()

    def test_empty_target_retry_is_pinned_and_preserves_public_checkpoint(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            args = self.args(root)
            self.config(root)
            state = {'exists': False, 'versions': {}, 'public': (1575, 81)}
            events = []
            expected = module.expected_migrations()

            def postgres(*argv):
                events.append(argv)
                if argv[0] == 'createdb':
                    state['exists'] = True
                return b''

            def run(argv, **kw):
                events.append(tuple(argv))
                if 'pg_dump' in argv:
                    kw['output'].write(b'archive')
                if argv[0] == 'java':
                    self.assertEqual(kw['env']['PGDATABASE'], args.database)
                    self.assertIn('/freediving_canonical?', kw['env']['FREEDIVING_MIGRATION_URL'])
                    self.assertNotIn('PGHOST', kw['env'])
                    state['versions'] = expected.copy()
                return b''

            def drill(*argv):
                events.append(('restore_drill',))
                self.assertEqual(argv[-1], state['versions'])

            with mock.patch.object(module, 'RELEASES_ROOT', args.release.parent.resolve()), \
                 mock.patch.object(module, 'MIGRATION_DIR', args.release / 'resources' / 'migrations'), \
                 mock.patch.object(module, 'database_exists', side_effect=lambda *a: state['exists']), \
                 mock.patch.object(module, 'database_identity', return_value=('freediving_migrator', module.MARKER)), \
                 mock.patch.object(module, 'private_acl', side_effect=[False, True, True, True, True]), \
                 mock.patch.object(module, 'public_counts', side_effect=lambda *a: state['public']), \
                 mock.patch.object(module, 'version_state', return_value=expected), \
                 mock.patch.object(module, 'target_versions', side_effect=lambda *a: state['versions']), \
                 mock.patch.object(module, 'target_empty', return_value=True), \
                 mock.patch.object(module, 'postgres', side_effect=postgres), \
                 mock.patch.object(module, 'run', side_effect=run), \
                 mock.patch.object(module, 'restore_drill', side_effect=drill), \
                 mock.patch.object(module, 'readback_empty', return_value=True), \
                 mock.patch.object(module, 'canonical_target_state'), \
                 mock.patch.object(module, 'deployment_state', return_value=('public', 'owner')), \
                 mock.patch.object(module, 'verify_roles'), \
                 mock.patch.object(module, 'ensure_private_dir', side_effect=lambda path: path.mkdir(mode=0o700, exist_ok=True)), \
                 mock.patch.object(module.os, 'geteuid', return_value=0):
                module.provision(args)
                state['versions'] = {n: expected[n] for n in (1, 7, 11)}
                module.provision(args)
                module.provision(args)
                state['versions'] = {1: 'drift'}
                before_drift = len(events)
                with self.assertRaises(ValueError):
                    module.provision(args)
                self.assertEqual(len(events), before_drift)
            self.assertEqual(sum(command[0] == 'createdb' for command in events), 1)
            self.assertEqual(sum(command[0] == 'java' for command in events), 2)
            self.assertLess(events.index(('restore_drill',)), next(i for i, event in enumerate(events) if event[0] == 'java'))
            self.assertTrue(all('public_release' not in str(command) for command in events if command[0] in ('createdb', 'java')))
            self.assertEqual(len(list(args.backup_dir.glob('*.dump'))), 3)
            self.assertNotIn('secret_sentinel', (args.backup_dir / 'canonical-intent.json').read_text())

    def test_partial_migration_recovers_but_checksum_drift_and_rows_refuse(self):
        module = load()
        expected = module.expected_migrations()
        module.verify_target_versions({n: expected[n] for n in (1, 7, 11)}, expected)
        for versions in ({1: 'drift'}, {21: 'unexpected'},
                         {1: expected[1], 2: expected[2]}):
            with self.assertRaises(ValueError):
                module.verify_target_versions(versions, expected)

    def test_current_normal_migration_prefix_and_empty_sporting_policy_seed(self):
        module = load()
        expected = module.expected_migrations()
        current_order = (1, 7, 11, 2, 12, 13, 14, 15, 16, 17, 18, 20, 21,
                         3, 4, 5, 6, 8, 9, 10, 19, 22)
        for count in range(len(current_order) + 1):
            with self.subTest(count=count):
                module.verify_target_versions({n: expected[n] for n in current_order[:count]}, expected)
        module.verify_target_versions({n: expected[n] for n in range(1, 22)}, expected)
        def query(_, sql, __):
            if 'FROM pg_tables' in sql:
                return 'public_sporting_authority_events\npublic_sporting_members\npublic_sporting_policy_events\nschema_migrations'
            if 'SELECT policy_version' in sql:
                return 'aida-baseline-v1'
            return '1' if 'public_sporting_policy_events' in sql else '0'
        with mock.patch.object(module, 'query', side_effect=query):
            self.assertTrue(module.target_empty('freediving_canonical', '5432'))
        def changed_policy(_, sql, __):
            return 'different-policy' if 'SELECT policy_version' in sql else query(_, sql, __)
        with mock.patch.object(module, 'query', side_effect=changed_policy):
            self.assertFalse(module.target_empty('freediving_canonical', '5432'))
        def nonempty_authority(_, sql, __):
            return '1' if 'count(*) FROM freediving.public_sporting_authority_events' in sql else query(_, sql, __)
        with mock.patch.object(module, 'query', side_effect=nonempty_authority):
            self.assertFalse(module.target_empty('freediving_canonical', '5432'))

    def test_environment_and_output_never_disclose_credentials(self):
        module = load()
        with mock.patch('subprocess.run', side_effect=RuntimeError('secret_sentinel')):
            with self.assertRaises(RuntimeError):
                module.run(['java'], env={'FREEDIVING_MIGRATION_URL': 'secret_sentinel'})
        self.assertNotIn('secret_sentinel', module.failure_message())

    def test_clojure_target_state_requires_exact_empty_revision_zero(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            snapshot = 'b' * 64
            def run(argv, **kw):
                Path(argv[-1]).write_text(json.dumps({
                    'schema': 'retained-aida-target-state/v1',
                    'snapshot_sha256': snapshot, 'revision': 1,
                    'events': [], 'source_rows': [], 'non_source_row_count': 0}))
                self.assertEqual(kw['env']['PGDATABASE'], 'freediving_canonical')
                self.assertEqual(kw['env']['PGPORT'], '5544')
                self.assertEqual(kw['env']['FREEDIVING_REVIEW_URL'], kw['env']['FREEDIVING_APP_URL'])
            with mock.patch.object(module, 'run', side_effect=run), self.assertRaises(ValueError):
                module.canonical_target_state(root, 'jdbc:synthetic', 'freediving_canonical', '5544', snapshot, root)

    @staticmethod
    def args(root):
        revision = 'a' * 40
        release = root / 'releases' / revision
        (release / 'resources' / 'migrations').mkdir(parents=True)
        (release / 'REVISION').write_text(revision + '\n')
        return SimpleNamespace(release=release, expected_revision=revision,
                               database='freediving_canonical', expected_public_database='public_release',
                               snapshot_sha256='b' * 64, config_dir=root / 'config',
                               backup_dir=root / 'private', public_current=root / 'current',
                               owner_current=root / 'owner', owner_status=root / 'status')

    @staticmethod
    def config(root):
        config = root / 'config'
        config.mkdir()
        def url(role):
            return (f'jdbc:postgresql://127.0.0.1:5432/public_release?user={role}'
                    '&password=secret_sentinel&connectTimeout=5&socketTimeout=15')
        (config / 'migration.env').write_text(f"FREEDIVING_MIGRATION_URL='{url('freediving_migrator')}'\n")
        (config / 'public.env').write_text(
            f"FREEDIVING_PUBLIC_DATABASE_URL='{url('reviews_public')}'\n"
            f"FREEDIVING_SUBMIT_DATABASE_URL='{url('corrections_submit')}'\n"
            "FREEDIVING_PUBLIC_ORIGIN='https://poc.alphacompose.com'\n"
            "FREEDIVING_GATEWAY_SECRET='secret_sentinel'\n")


if __name__ == '__main__':
    unittest.main()
