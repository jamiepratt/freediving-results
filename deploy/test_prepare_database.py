"""Regression tests at the deployment preparation command boundary."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HELPER = Path(__file__).with_name('prepare_database.py')
SECRET = 'private_password_sentinel'


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'config'
        self.config.mkdir()
        self.backups = self.root / 'backups'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.events = self.root / 'events'
        self.write_config()
        self.command('runuser', '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open(os.environ['EVENTS'], 'a') as f: f.write(json.dumps(sys.argv[1:])+'\\n')
print('selected_database_data:' + sys.argv[-1])
''')
        self.command('java', '''#!/usr/bin/env python3
import os, sys
assert 'private_password_sentinel' in os.environ['FREEDIVING_MIGRATION_URL']
assert all('private_password_sentinel' not in arg for arg in sys.argv)
with open(os.environ['EVENTS'], 'a') as f: f.write('migration\\n')
''')

    def command(self, name, text):
        path = self.bin / name
        path.write_text(text)
        path.chmod(0o700)

    def url(self, role, database='selected_release', port=5432):
        return f'jdbc:postgresql://127.0.0.1:{port}/{database}?user={role}&password={SECRET}&connectTimeout=5&socketTimeout=15'

    def write_config(self, database='selected_release'):
        (self.config / 'migration.env').write_text(f"FREEDIVING_MIGRATION_URL='{self.url('freediving_migrator', database)}'\n")
        (self.config / 'public.env').write_text(
            f"FREEDIVING_PUBLIC_DATABASE_URL='{self.url('reviews_public', database)}'\n"
            f"FREEDIVING_SUBMIT_DATABASE_URL='{self.url('corrections_submit', database)}'\n"
            "FREEDIVING_PUBLIC_ORIGIN='https://poc.alphacompose.com'\n"
            "FREEDIVING_GATEWAY_SECRET='gateway_sentinel'\n")

    def run_prepare(self, *args):
        env = dict(os.environ, PATH=str(self.bin)+os.pathsep+os.environ['PATH'], EVENTS=str(self.events))
        return subprocess.run([sys.executable, str(HELPER), '--config-dir', str(self.config),
                               '--backup-dir', str(self.backups), *args], env=env,
                              capture_output=True, text=True)

    def test_backup_uses_configured_nondefault_database_before_migration(self):
        result = self.run_prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        dumps = list(self.backups.glob('*.dump'))
        self.assertEqual(len(dumps), 1)
        self.assertEqual(dumps[0].read_text(), 'selected_database_data:selected_release\n')
        self.assertEqual(dumps[0].stat().st_mode & 0o777, 0o600)
        events = self.events.read_text().splitlines()
        self.assertIn('selected_release', events[0])
        self.assertEqual(events[1], 'migration')
        self.assertNotIn(SECRET, self.events.read_text()+result.stdout+result.stderr)

    def test_mismatched_public_database_refuses_before_backup_or_migration(self):
        path = self.config / 'public.env'
        path.write_text(path.read_text().replace('/selected_release?', '/old_database?', 1))
        result = self.run_prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.events.exists())
        self.assertFalse(self.backups.exists())
        self.assertNotIn(SECRET, result.stdout+result.stderr)

    def test_malformed_configuration_refuses_without_side_effects_or_secret_output(self):
        cases = [
            ('migration.env', '/selected_release?', '/'+'a'*64+'?'),
            ('migration.env', '/selected_release?', '/-option?'),
            ('migration.env', ':5432/', ':0/'),
            ('migration.env', ':5432/', ':65536/'),
            ('migration.env', '127.0.0.1', 'localhost'),
            ('migration.env', 'connectTimeout=5', 'connectTimeout=5&user=other'),
            ('migration.env', 'connectTimeout=5', 'connectTimeout=5&options=private_password_sentinel'),
            ('migration.env', SECRET, 'bad%ZZ'),
            ('migration.env', 'freediving_migrator', 'reviews_public'),
            ('public.env', 'corrections_submit', 'reviews_public'),
            ('public.env', ':5432/', ':5433/'),
            ('public.env', 'FREEDIVING_SUBMIT_DATABASE_URL', 'MISSING_SUBMISSION_URL'),
        ]
        for filename, old, new in cases:
            with self.subTest(filename=filename, old=old):
                self.write_config()
                path = self.config / filename
                path.write_text(path.read_text().replace(old, new))
                result = self.run_prepare()
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.events.exists())
                self.assertFalse(self.backups.exists())
                self.assertNotIn(SECRET, result.stdout+result.stderr)
        for suffix in ["FREEDIVING_MIGRATION_URL='duplicate'\n", "echo secret\n",
                       "INJECT=$(touch owned)\n", "BROKEN='unterminated\n"]:
            self.write_config()
            path = self.config / 'migration.env'
            path.write_text(path.read_text()+suffix)
            result = self.run_prepare()
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(self.events.exists())

    def test_backup_only_skips_migration_and_ignores_ambient_pg_settings(self):
        self.command('runuser', """#!/usr/bin/env python3
import os, sys
assert not any(key.startswith('PG') for key in os.environ)
assert sys.argv[sys.argv.index('-h')+1] == '/var/run/postgresql'
print('isolated backup')
""")
        with mock.patch.dict(os.environ, {'PGHOST': 'attacker', 'PGDATABASE': 'old',
                                                  'PGSERVICE': 'attacker', 'PGOPTIONS': 'secret'}):
            result = self.run_prepare('--backup-only')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.events.exists())
        self.assertEqual(len(list(self.backups.glob('*.dump'))), 1)

    def test_failed_dump_is_removed_and_never_migrates_or_logs_secrets(self):
        self.command('runuser', f"""#!/usr/bin/env python3
import sys
print('{SECRET}')
print('{SECRET}', file=sys.stderr)
sys.exit(1)
""")
        result = self.run_prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.events.exists())
        self.assertEqual(list(self.backups.glob('*.dump')), [])
        self.assertNotIn(SECRET, result.stdout+result.stderr)

    def test_failed_migration_retains_backup_and_sanitizes_child_output(self):
        self.command('java', f"""#!/usr/bin/env python3
import os, sys
assert '{SECRET}' in os.environ['FREEDIVING_MIGRATION_URL']
assert all('{SECRET}' not in arg for arg in sys.argv)
print(os.environ['FREEDIVING_MIGRATION_URL'])
print(os.environ['FREEDIVING_MIGRATION_URL'], file=sys.stderr)
sys.exit(1)
""")
        result = self.run_prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(list(self.backups.glob('*.dump'))), 1)
        self.assertNotIn(SECRET, result.stdout+result.stderr)


if __name__ == '__main__':
    unittest.main()
