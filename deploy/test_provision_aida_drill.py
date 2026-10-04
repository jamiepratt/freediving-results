"""Public command contract for an isolated AIDA restore database."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

HELPER = Path(__file__).with_name('provision_aida_drill.py')
sys.path.insert(0, str(HELPER.parent))


def load():
    spec = importlib.util.spec_from_file_location('provision_aida_drill', HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProvisionAidaDrillTests(unittest.TestCase):
    def test_cli_creates_private_empty_drill_and_retries_only_matching_intent(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config = root / 'config'
            config.mkdir()
            url = 'jdbc:postgresql://127.0.0.1:5432/public_release?user=freediving_migrator&password=secret_sentinel&connectTimeout=5&socketTimeout=15'
            (config / 'migration.env').write_text(f"FREEDIVING_MIGRATION_URL='{url}'\n")
            (config / 'public.env').write_text("FREEDIVING_PUBLIC_DATABASE_URL='public'\n")
            argv = ['provision_aida_drill.py', '--database', 'aida_drill_b22',
                    '--expected-target-database', 'freediving_canonical',
                    '--expected-public-database', 'public_release',
                    '--config-dir', str(config), '--intent-dir', str(root / 'private')]
            state = {'exists': False, 'owner': 'freediving_migrator',
                     'marker': '', 'private': False, 'empty': True}
            calls = []

            def postgres(*args):
                calls.append(args)
                if args[0] == 'createdb':
                    state['exists'] = True
                if 'COMMENT ON DATABASE' in str(args):
                    state['marker'] = module.MARKER
                if 'REVOKE ALL ON DATABASE' in str(args):
                    state['private'] = True

            def identity(*_):
                return state['owner'], state['marker']

            with mock.patch.object(sys, 'argv', argv), \
                 mock.patch.object(module.os, 'geteuid', return_value=0), \
                 mock.patch.object(module, 'public_config', return_value=('5432', 'public_release', url)), \
                 mock.patch.object(module, 'database_exists', side_effect=lambda *_: state['exists']), \
                 mock.patch.object(module, 'database_identity', side_effect=identity), \
                 mock.patch.object(module, 'private_acl', side_effect=lambda *_: state['private']), \
                 mock.patch.object(module, 'database_empty', side_effect=lambda *_: state['empty']), \
                 mock.patch.object(module, 'private_connect_acl', side_effect=lambda *_: state['private']), \
                 mock.patch.object(module, 'postgres', side_effect=postgres), \
                 mock.patch.object(module, 'target_ready', return_value=True), \
                 mock.patch.object(module, 'public_checkpoint', return_value=(module.expected_migrations(), 'public', ('a', 'b'), 'owner')), \
                 mock.patch.object(module, 'verify_roles'), \
                 mock.patch.object(module, 'read_intent', side_effect=lambda path, expected: self.assertEqual(json.loads(path.read_text()), expected)), \
                 mock.patch.object(module, 'ensure_private_dir', side_effect=lambda path: path.mkdir(mode=0o700, exist_ok=True)):
                self.assertEqual(module.main(), 0)
                self.assertEqual(module.main(), 0)
                self.assertEqual(sum(command[0] == 'createdb' for command in calls), 1)
                self.assertIn(('createdb', '-h', '/var/run/postgresql', '-p', '5432',
                               '-T', 'template0', '-O', 'freediving_migrator', 'aida_drill_b22'), calls)
                self.assertTrue(any('REVOKE ALL ON DATABASE aida_drill_b22 FROM PUBLIC' in str(c) for c in calls))
                intent = json.loads((root / 'private' / 'aida-drill-intent-aida_drill_b22.json').read_text())
                self.assertEqual(intent['database'], 'aida_drill_b22')
                self.assertNotIn('secret_sentinel', str(intent))
                state['empty'] = False
                before = len(calls)
                self.assertEqual(module.main(), 1)
                self.assertEqual(len(calls), before)

    def test_cli_refuses_wrong_name_unmarked_existing_and_changed_binding_before_write(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            argv = ['provision_aida_drill.py', '--database', 'freediving_canonical',
                    '--expected-target-database', 'freediving_canonical',
                    '--expected-public-database', 'public_release',
                    '--config-dir', str(root), '--intent-dir', str(root / 'private')]
            with mock.patch.object(sys, 'argv', argv), \
                 mock.patch.object(module.os, 'geteuid', return_value=0), \
                 mock.patch.object(module, 'postgres') as postgres:
                self.assertEqual(module.main(), 1)
                postgres.assert_not_called()
            argv[2] = 'aida_drill_b22'
            with mock.patch.object(sys, 'argv', argv), \
                 mock.patch.object(module.os, 'geteuid', return_value=0), \
                 mock.patch.object(module, 'public_config', return_value=('5432', 'public_release', 'url')), \
                 mock.patch.object(module, 'target_ready', return_value=True), \
                 mock.patch.object(module, 'public_checkpoint', return_value=('public',)), \
                 mock.patch.object(module, 'database_exists', return_value=True), \
                 mock.patch.object(module, 'postgres') as postgres:
                self.assertEqual(module.main(), 1)
                postgres.assert_not_called()

    def test_existing_intent_binding_drift_refuses_before_database_write(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            private = root / 'private'
            private.mkdir(mode=0o700)
            (private / 'aida-drill-intent-aida_drill_b22.json').write_text('{}')
            args = mock.Mock(database='aida_drill_b22',
                             expected_target_database='freediving_canonical',
                             expected_public_database='public_release',
                             config_dir=root, intent_dir=private,
                             public_current=root, owner_current=root, owner_status=root)
            with mock.patch.object(module.os, 'geteuid', return_value=0), \
                 mock.patch.object(module, 'public_config', return_value=('5432', 'public_release', 'url')), \
                 mock.patch.object(module, 'target_ready', return_value=True), \
                 mock.patch.object(module, 'public_checkpoint', return_value=(module.expected_migrations(), 'public', ('a', 'b'), 'owner')), \
                 mock.patch.object(module, 'database_exists', return_value=True), \
                 mock.patch.object(module, 'ensure_private_dir'), \
                 mock.patch.object(module, 'verify_roles'), \
                 mock.patch.object(module, 'postgres') as postgres:
                with self.assertRaises(ValueError):
                    module.provision(args)
                postgres.assert_not_called()

    def test_cli_creates_second_drill_while_legacy_drill_is_filled(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            private = root / 'private'
            private.mkdir(mode=0o700)
            legacy = {'schema': module.MARKER, 'database': 'aida_drill_b22',
                      'target': module.TARGET, 'public_database': 'public_release',
                      'port': '5432',
                      'public_config_sha256': {'migration.env': 'a', 'public.env': 'b'}}
            (private / 'aida-drill-intent.json').write_text(json.dumps(legacy))
            argv = ['provision_aida_drill.py', '--database', 'aida_drill_b23',
                    '--expected-target-database', 'freediving_canonical',
                    '--expected-public-database', 'public_release',
                    '--config-dir', str(root), '--intent-dir', str(private)]
            state = {'exists': False, 'marker': '', 'private': False}
            calls = []

            def postgres(*args):
                calls.append(args)
                if args[0] == 'createdb':
                    state['exists'] = True
                if 'COMMENT ON DATABASE' in str(args):
                    state['marker'] = module.MARKER
                if 'REVOKE ALL ON DATABASE' in str(args):
                    state['private'] = True

            with mock.patch.object(sys, 'argv', argv), \
                 mock.patch.object(module.os, 'geteuid', return_value=0), \
                 mock.patch.object(module, 'public_config', return_value=('5432', 'public_release', 'url')), \
                 mock.patch.object(module, 'target_ready', return_value=True), \
                 mock.patch.object(module, 'public_checkpoint', return_value=(module.expected_migrations(), 'public', ('a', 'b'), 'owner')), \
                 mock.patch.object(module, 'database_exists', side_effect=lambda *_: state['exists']), \
                 mock.patch.object(module, 'database_identity', side_effect=lambda *_: ('freediving_migrator', state['marker'])), \
                 mock.patch.object(module, 'database_empty', return_value=True), \
                 mock.patch.object(module, 'private_connect_acl', side_effect=lambda *_: state['private']), \
                 mock.patch.object(module, 'postgres', side_effect=postgres), \
                 mock.patch.object(module, 'verify_roles'), \
                 mock.patch.object(module, 'private_intent_value', side_effect=lambda path: json.loads(path.read_text())), \
                 mock.patch.object(module, 'ensure_private_dir'):
                self.assertEqual(module.main(), 0)
                self.assertEqual(module.main(), 0)
            self.assertEqual(sum(command[0] == 'createdb' for command in calls), 1)
            self.assertEqual(json.loads((private / 'aida-drill-intent.json').read_text()), legacy)
            self.assertEqual(json.loads((private / 'aida-drill-intent-aida_drill_b23.json').read_text())['database'], 'aida_drill_b23')

    def test_cli_resumes_original_legacy_intent_without_creating_named_copy(self):
        module = load()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            private = root / 'private'
            private.mkdir(mode=0o700)
            intent = {'schema': module.MARKER, 'database': 'aida_drill_b22',
                      'target': module.TARGET, 'public_database': 'public_release',
                      'port': '5432',
                      'public_config_sha256': {'migration.env': 'a', 'public.env': 'b'}}
            (private / 'aida-drill-intent.json').write_text(json.dumps(intent))
            argv = ['provision_aida_drill.py', '--database', 'aida_drill_b22',
                    '--expected-target-database', 'freediving_canonical',
                    '--expected-public-database', 'public_release',
                    '--config-dir', str(root), '--intent-dir', str(private)]
            with mock.patch.object(sys, 'argv', argv), \
                 mock.patch.object(module.os, 'geteuid', return_value=0), \
                 mock.patch.object(module, 'public_config', return_value=('5432', 'public_release', 'url')), \
                 mock.patch.object(module, 'target_ready', return_value=True), \
                 mock.patch.object(module, 'public_checkpoint', return_value=(module.expected_migrations(), 'public', ('a', 'b'), 'owner')), \
                 mock.patch.object(module, 'database_exists', return_value=True), \
                 mock.patch.object(module, 'database_identity', return_value=('freediving_migrator', module.MARKER)), \
                 mock.patch.object(module, 'database_empty', return_value=True), \
                 mock.patch.object(module, 'private_connect_acl', return_value=True), \
                 mock.patch.object(module, 'private_intent_value', side_effect=lambda path: json.loads(path.read_text())), \
                 mock.patch.object(module, 'verify_roles'), \
                 mock.patch.object(module, 'ensure_private_dir'), \
                 mock.patch.object(module, 'postgres') as postgres:
                self.assertEqual(module.main(), 0)
                postgres.assert_not_called()
            self.assertFalse((private / 'aida-drill-intent-aida_drill_b22.json').exists())


if __name__ == '__main__':
    unittest.main()
