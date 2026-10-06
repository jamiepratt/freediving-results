import argparse
import hashlib
import importlib.util
import json
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

    def test_config_failure_revokes_only_new_capability(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
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
