"""Dedicated canonical migration checkpoint."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest import mock


class CanonicalMigrationTests(unittest.TestCase):
    def test_operator_has_a_dedicated_guarded_checkpoint(self):
        helper = Path(__file__).with_name('migrate_canonical_21.py')
        sys.path.insert(0, str(helper.parent))
        spec = importlib.util.spec_from_file_location('migrate_canonical_21', helper)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertTrue(callable(module.migrate))
        self.assertEqual(module.TARGET, 'freediving_canonical')

    def test_migration_uses_exact_transaction_and_migrator_role(self):
        helper = Path(__file__).with_name('migrate_canonical_21.py')
        sys.path.insert(0, str(helper.parent))
        spec = importlib.util.spec_from_file_location('migrate_canonical_21', helper)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        public_url = ('jdbc:postgresql://127.0.0.1:5432/public_db?'
                      'user=freediving_migrator&password=synthetic&'
                      'connectTimeout=5&socketTimeout=15')
        with mock.patch.object(module, 'run') as run:
            module.apply_21('5432', public_url, 'public_db', 'a' * 64)
        argv = run.call_args.args[0]
        self.assertEqual(argv[:10], ['psql', '-X', '-q', '-1', '-v',
                                     'ON_ERROR_STOP=1', '-h', '127.0.0.1',
                                     '-p', '5432'])
        self.assertEqual(argv[argv.index('-U') + 1], 'freediving_migrator')
        self.assertEqual(argv[argv.index('-d') + 1], 'freediving_canonical')
        self.assertIn("(21,'" + 'a' * 64 + "')", argv[-1])
        self.assertEqual(run.call_args.kwargs['env']['PGPASSWORD'], 'synthetic')
        self.assertNotIn('synthetic', ' '.join(argv))


if __name__ == '__main__':
    unittest.main()
