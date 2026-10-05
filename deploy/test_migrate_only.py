"""Guarded migration-only operator boundary, with synthetic dependencies."""
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

HELPER = Path(__file__).with_name('migrate_only.py')
sys.path.insert(0, str(HELPER.parent))
spec = importlib.util.spec_from_file_location('migrate_only', HELPER)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MigrationOnlyTests(unittest.TestCase):
    def test_existing_host_normal_activation_uses_guarded_migration(self):
        activation = HELPER.with_name('activate.sh').read_text()
        self.assertIn('migrate_only.py', activation)
        self.assertIn('prepare_database.py', activation)
        self.assertIn('existing_deployment', activation)

    def test_operator_wrapper_has_no_public_release_commands(self):
        wrapper = HELPER.with_suffix('.sh').read_text()
        for command in ('activate.sh', 'release.sh', 'publish-edge.py',
                        'cloudflare.py', 'systemctl restart', 'ln -s'):
            self.assertNotIn(command, wrapper)

    def test_release_has_exact_checksums_and_refuses_partial_or_altered_database(self):
        expected = module.expected_migrations()
        self.assertEqual(set(expected), set(range(1, 22)))
        self.assertEqual(expected[6], '53c690232ff844de6237b7e82c9bdc4b5996c19ac77a38aff2598ab779f07b92')
        self.assertEqual(expected[21], '4ac5a9b06e10b8785500e4bf7009c392a1d9562ea6aaf626c6620d26e18c5939')
        self.assertEqual(module.verify_versions({n: expected[n] for n in range(1, 8)}, expected,
                                                (set(range(1, 8)), set(range(1, 21)), set(range(1, 22)))), 7)
        self.assertEqual(module.verify_versions({n: expected[n] for n in range(1, 21)}, expected,
                                                (set(range(1, 8)), set(range(1, 21)), set(range(1, 22)))), 20)
        self.assertEqual(module.verify_versions(expected, expected,
                                                (set(range(1, 8)), set(range(1, 21)), set(range(1, 22)))), 21)
        for actual in ({n: expected[n] for n in range(1, 7)},
                       {**expected, 22: 'extra'},
                       {**expected, 7: 'altered'}):
            with self.subTest(actual=actual), self.assertRaises(ValueError):
                module.verify_versions(actual, expected,
                                       (set(range(1, 8)), set(range(1, 21)), set(range(1, 22))))

    def test_private_backup_is_streamed_to_postgres_without_exposing_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            private = Path(tmp) / 'private'
            private.mkdir(mode=0o700)
            backup = private / 'backup.dump'
            backup.write_bytes(b'synthetic archive')
            backup.chmod(0o600)
            observed = []

            def fake_run(argv, **kwargs):
                observed.append((argv, kwargs['input_stream'].read()))
                return b''

            with mock.patch.object(module, 'run', side_effect=fake_run):
                module.postgres_restore(backup, '--list')
            self.assertEqual(observed, [
                (['runuser', '-u', 'postgres', '--', 'pg_restore', '--list'],
                 b'synthetic archive')])
            with backup.open('rb') as stream:
                self.assertEqual(module.run(
                    [sys.executable, '-c',
                     'import sys; sys.stdout.buffer.write(sys.stdin.buffer.read())'],
                    input_stream=stream), b'synthetic archive')
            self.assertEqual(private.stat().st_mode & 0o777, 0o700)
            self.assertEqual(backup.stat().st_mode & 0o777, 0o600)

    def test_disposable_restore_checks_rows_and_drops_only_its_generated_database(self):
        expected = {n: 'digest' for n in range(1, 8)}
        commands = []
        with mock.patch.object(module, 'postgres', side_effect=lambda *a: commands.append(a)), \
             mock.patch.object(module, 'postgres_restore',
                               side_effect=lambda backup, *a: commands.append(('pg_restore', backup, *a))), \
             mock.patch.object(module, 'version_state', return_value=expected), \
             mock.patch.object(module, 'counts', return_value=(1, 2, 3, 4, 5, 6, 7)):
            module.restore_drill(Path('/private/backup.dump'), 'production', '5432', expected)
        self.assertEqual([args[0] for args in commands], ['createdb', 'pg_restore', 'dropdb'])
        disposable = commands[0][-1]
        self.assertTrue(disposable.startswith('freediving_migration_drill_'))
        self.assertNotEqual(disposable, 'production')
        self.assertEqual(commands[1][1], Path('/private/backup.dump'))
        self.assertEqual(commands[1][-1], disposable)
        self.assertEqual(commands[2][-1], disposable)

    def test_failed_restore_still_drops_disposable_database(self):
        commands = []
        def postgres(*args):
            commands.append(args)
        def postgres_restore(backup, *args):
            commands.append(('pg_restore', backup, *args))
            raise OSError('synthetic restore failure')
        with mock.patch.object(module, 'postgres', side_effect=postgres), \
             mock.patch.object(module, 'postgres_restore', side_effect=postgres_restore), \
             self.assertRaises(OSError):
            module.restore_drill(Path('/private/backup.dump'), 'production', '5432', {})
        self.assertEqual([args[0] for args in commands], ['createdb', 'pg_restore', 'dropdb'])
        self.assertEqual(commands[0][-1], commands[2][-1])

    def test_disposable_restore_checks_retained_source_rows_at_version_twenty(self):
        versions = {n: 'digest' for n in range(1, 21)}
        def counts(database, _port, tables):
            if tables == module.SOURCE_TABLES:
                return (3, 5) if database == 'production' else (3, 4)
            return (1, 2, 3, 4, 5, 6, 7)
        with mock.patch.object(module, 'postgres'), \
             mock.patch.object(module, 'postgres_restore'), \
             mock.patch.object(module, 'version_state', return_value=versions), \
             mock.patch.object(module, 'counts', side_effect=counts), \
             self.assertRaisesRegex(ValueError, 'source counts'):
            module.restore_drill(Path('/private/backup.dump'), 'production', '5432', versions)

    def test_config_mismatch_refuses_before_commands(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp)
            def url(role, db='production'):
                return (f'jdbc:postgresql://127.0.0.1:5432/{db}?user={role}'
                        '&password=synthetic&connectTimeout=5&socketTimeout=15')
            (config / 'migration.env').write_text(f"FREEDIVING_MIGRATION_URL='{url('freediving_migrator')}'\n")
            (config / 'public.env').write_text(
                f"FREEDIVING_PUBLIC_DATABASE_URL='{url('reviews_public', 'other')}'\n"
                f"FREEDIVING_SUBMIT_DATABASE_URL='{url('corrections_submit')}'\n"
                "FREEDIVING_PUBLIC_ORIGIN='https://poc.alphacompose.com'\n"
                "FREEDIVING_GATEWAY_SECRET='synthetic'\n")
            with self.assertRaises(ValueError), mock.patch.object(module, 'run') as run:
                module.deployment_config(config)
            run.assert_not_called()

    def test_public_and_owner_state_is_read_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            public = root / 'public'
            owner = root / 'owner'
            snapshot = root / 'snapshot'
            current = root / 'current'
            status = root / 'status.json'
            public.mkdir()
            snapshot.mkdir()
            (snapshot / 'snapshot.sqlite').write_bytes(b'synthetic snapshot')
            (snapshot / 'manifest.json').write_text('{}')
            status.write_text('{}')
            current.symlink_to(snapshot, target_is_directory=True)
            owner.symlink_to(snapshot, target_is_directory=True)
            class Response:
                status = 200
                def __enter__(self): return self
                def __exit__(self, *args): pass
            with mock.patch.object(module, 'run', return_value=b'active\n') as run, \
                 mock.patch.object(module.urllib.request, 'urlopen', return_value=Response()) as urlopen:
                before = module.deployment_state(current, owner, status, 'https://poc.alphacompose.com')
                status.write_text('{"changed": true}')
                after = module.deployment_state(current, owner, status, 'https://poc.alphacompose.com')
            self.assertNotEqual(before, after)
            request = urlopen.call_args.args[0]
            self.assertIsInstance(request, module.urllib.request.Request)
            self.assertEqual(request.get_header('User-agent'), 'freediving-deploy-health/1.0')
            self.assertEqual([call.args[0][0] for call in run.call_args_list], ['systemctl', 'systemctl'])
            self.assertEqual(os.readlink(current), str(snapshot))

    def test_host_checkpoint_migrates_twenty_one_then_retries_without_public_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            revision = 'a' * 40
            release = root / 'releases' / revision
            migrations = release / 'resources' / 'migrations'
            migrations.mkdir(parents=True)
            (release / 'REVISION').write_text(revision + '\n')
            backups = root / 'backups'
            args = SimpleNamespace(release=release, expected_revision=revision,
                                   config_dir=root / 'config', backup_dir=backups,
                                   public_current=root / 'public', owner_current=root / 'owner',
                                   owner_status=root / 'status')
            expected = {n: 'digest' for n in range(1, 22)}
            versions = {n: 'digest' for n in range(1, 21)}
            events = []

            def fake_run(argv, **kwargs):
                events.append(tuple(argv))
                if argv[0] == 'runuser' and 'pg_dump' in argv:
                    kwargs['output'].write(b'synthetic complete dump')
                if argv[0] == 'java':
                    versions.update(expected)
                    self.assertEqual(kwargs['cwd'], release.resolve())
                    self.assertNotIn('PGHOST', kwargs['env'])
                    self.assertIn('synthetic_password', kwargs['env']['FREEDIVING_MIGRATION_URL'])
                return b''

            def fake_postgres(*argv):
                events.append(tuple(argv))
                return b''

            def fake_postgres_restore(backup, *argv):
                events.append(('pg_restore', backup, *argv))
                return b''

            def fake_drill(*argv):
                events.append(('restore_drill',))
                self.assertEqual(argv[-1], versions)

            def fake_private_dir(path):
                path.mkdir(mode=0o700, exist_ok=True)

            with mock.patch.object(module, 'RELEASES_ROOT', release.parent.resolve()), \
                 mock.patch.object(module, 'MIGRATION_DIR', migrations), \
                 mock.patch.object(module.os, 'geteuid', return_value=0), \
                 mock.patch.object(module, 'expected_migrations', return_value=expected), \
                 mock.patch.object(module, 'deployment_config', return_value=(
                     '5432', 'production', 'jdbc:synthetic_password', 'https://poc.alphacompose.com')), \
                 mock.patch.object(module, 'verify_roles'), \
                 mock.patch.object(module, 'version_state', side_effect=lambda *a: dict(versions)), \
                 mock.patch.object(module, 'counts', side_effect=lambda _db, _port, tables:
                                   (3, 5) if tables == module.SOURCE_TABLES else
                                   (0, 0, 0, 0, 0, 0, 81)), \
                 mock.patch.object(module, 'deployment_state', return_value=('prior-release', 'owner-state')), \
                 mock.patch.object(module, 'ensure_private_dir', side_effect=fake_private_dir), \
                 mock.patch.object(module, 'restore_drill', side_effect=fake_drill), \
                 mock.patch.object(module, 'postgres', side_effect=fake_postgres), \
                 mock.patch.object(module, 'postgres_restore', side_effect=fake_postgres_restore), \
                 mock.patch.object(module, 'run', side_effect=fake_run), \
                 mock.patch.dict(os.environ, {'PGHOST': 'attacker'}):
                module.migrate(args)
                first_events = list(events)
                self.assertEqual([x[0] for x in first_events],
                                 ['runuser', 'pg_restore', 'restore_drill', 'java'])
                self.assertEqual(first_events[-1][-2:], ('-m', 'freediving.deployment'))
                module.migrate(args)
            retry_events = events[len(first_events):]
            self.assertEqual([x[0] for x in retry_events],
                             ['runuser', 'pg_restore', 'restore_drill'])
            self.assertTrue(all('activate.sh' not in str(x) and
                                'release.sh' not in str(x) and
                                'cloudflare' not in str(x) and
                                'systemctl' not in str(x) for x in events))
            self.assertEqual(len(list(backups.glob('*.dump'))), 2)


if __name__ == '__main__':
    unittest.main()
