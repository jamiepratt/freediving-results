import argparse
import hashlib
import importlib.util
import json
import os
from contextlib import contextmanager, ExitStack
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import tempfile

spec = importlib.util.spec_from_file_location('provision', Path(__file__).with_name('provision_canonical_status_reader.py'))
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class ProvisionTests(unittest.TestCase):
    def test_select_capability_has_no_write_or_role_inheritance(self):
        sql = helper.grant_sql('a' * 48)
        self.assertIn('NOINHERIT', sql)
        self.assertIn('default_transaction_read_only=on', sql)
        self.assertEqual(sql.count('GRANT SELECT ON'), 1)
        for word in ('GRANT INSERT', 'GRANT UPDATE', 'GRANT DELETE', 'GRANT ALL'):
            self.assertNotIn(word, sql)

    @contextmanager
    def host(self, root, *, existing_role=False):
        runtime = root / 'runtime'
        runtime.mkdir()
        (runtime / 'manifest.json').write_text('{}')
        export = root / 'export.json'
        export.write_text('{}')
        status = root / 'status.json'
        status.write_text('{}')
        sha = hashlib.sha256(b'{}').hexdigest()
        args = argparse.Namespace(snapshot='a' * 64, runtime=runtime,
                                  runtime_manifest_sha256=sha, export=export,
                                  export_sha256=sha, status_sha256=sha)
        statements = []
        def run(command, **kwargs):
            statements.append(kwargs['input'])
            return SimpleNamespace(returncode=0, stdout=str(int(existing_role))
                                   if 'SELECT count' in kwargs['input'] else '')
        real_path = helper.Path
        with ExitStack() as stack:
            stack.enter_context(patch.object(helper, 'CONFIG', root / 'config' / 'config.json'))
            stack.enter_context(patch.object(helper.os, 'geteuid', return_value=0))
            stack.enter_context(patch.object(helper, 'Path', side_effect=lambda p: status
                if str(p).endswith('status/presentation-status.json') else real_path(p)))
            stack.enter_context(patch.object(helper.subprocess, 'run', side_effect=run))
            stack.enter_context(patch.object(helper.grp, 'getgrnam', return_value=SimpleNamespace(gr_gid=0)))
            stack.enter_context(patch.object(helper.os, 'chown'))
            yield args, statements

    def test_private_umask_keeps_app_group_traversal_and_secret_file_modes(self):
        with tempfile.TemporaryDirectory() as root, self.host(Path(root).resolve()) as (args, statements):
            before_export = args.export.read_bytes()
            old_mask = os.umask(0o077)
            try:
                helper.provision(args)
            finally:
                os.umask(old_mask)
            self.assertEqual(helper.CONFIG.parent.stat().st_mode & 0o777, 0o750)
            self.assertEqual(helper.CONFIG.stat().st_mode & 0o777, 0o640)
            self.assertEqual(args.export.stat().st_mode & 0o777, 0o640)
            self.assertEqual(args.export.read_bytes(), before_export)
            self.assertEqual(json.loads(helper.CONFIG.read_text())['exports']['same_attempt']['sha256'], args.export_sha256)
            before_config = helper.CONFIG.read_bytes()
            before_sql = list(statements)
            with self.assertRaises(ValueError):
                helper.provision(args)
            self.assertEqual(helper.CONFIG.read_bytes(), before_config)
            self.assertEqual(statements, before_sql)
            self.assertEqual(sum('CREATE ROLE' in sql for sql in statements), 1)

    def test_linked_config_parent_or_export_refuses_before_role_creation(self):
        for linked in ('config', 'export'):
            with self.subTest(linked=linked), tempfile.TemporaryDirectory() as root, self.host(Path(root).resolve()) as (args, statements):
                if linked == 'config':
                    target = helper.CONFIG.parent.with_name('other')
                    target.mkdir()
                    helper.CONFIG.parent.symlink_to(target, target_is_directory=True)
                else:
                    target = args.export.with_name('retained.json')
                    args.export.rename(target)
                    args.export.symlink_to(target)
                with self.assertRaises(ValueError):
                    helper.provision(args)
                self.assertEqual(statements, [])

    def test_existing_role_refuses_without_config_or_grant_changes(self):
        with tempfile.TemporaryDirectory() as root, self.host(Path(root).resolve(), existing_role=True) as (args, statements):
            with self.assertRaises(ValueError):
                helper.provision(args)
            self.assertFalse(helper.CONFIG.exists())
            self.assertEqual(len(statements), 1)
            self.assertNotIn('CREATE ROLE', statements[0])

    def test_config_failure_revokes_only_new_capability(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root).resolve()
            runtime = root / 'runtime'
            runtime.mkdir()
            (runtime / 'manifest.json').write_text('{}')
            export = root / 'export.json'
            export.write_text('{}')
            status = root / 'status.json'
            status.write_text('{}')
            sha = hashlib.sha256(b'{}').hexdigest()
            args = argparse.Namespace(snapshot='a' * 64, runtime=runtime,
                                      runtime_manifest_sha256=sha, export=export,
                                      export_sha256=sha, status_sha256=sha)
            def run(command, **kwargs):
                return SimpleNamespace(returncode=0, stdout='0' if 'SELECT count' in kwargs['input'] else '')
            real_path = helper.Path
            with patch.object(helper, 'CONFIG', root / 'config' / 'config.json'), \
                    patch.object(helper.os, 'geteuid', return_value=0), \
                    patch.object(helper, 'Path', side_effect=lambda p: status if str(p).endswith('status/presentation-status.json') else real_path(p)), \
                    patch.object(helper.subprocess, 'run', side_effect=run) as pg, \
                    patch.object(helper.grp, 'getgrnam', return_value=SimpleNamespace(gr_gid=0)), \
                    patch.object(helper.os, 'chown'), \
                    patch.object(helper.os, 'open', side_effect=OSError('disk failure')):
                with self.assertRaises(OSError):
                    helper.provision(args)
                sql = [call.kwargs['input'] for call in pg.call_args_list]
                self.assertTrue(any('DROP ROLE canonical_status_read' in value for value in sql))
                self.assertFalse((root / 'config' / 'config.json').exists())


if __name__ == '__main__':
    unittest.main()
