"""Independent exact sporting proof capability boundaries."""
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
from unittest.mock import patch
import unittest


class SportingProofCapabilityTests(unittest.TestCase):
    def helper(self):
        spec = importlib.util.spec_from_file_location('sporting_proof_provision',
            Path(__file__).with_name('provision_sporting_proof_reader.py'))
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        return helper

    def test_narrow_new_reader_grants_leave_existing_capabilities_untouched(self):
        helper = self.helper()
        sql = helper.grant_sql('a' * 48)
        self.assertIn('CREATE ROLE sporting_proof_read LOGIN NOSUPERUSER', sql)
        self.assertIn('NOINHERIT', sql)
        self.assertIn('NOBYPASSRLS', sql)
        self.assertIn('default_transaction_read_only=on', sql)
        self.assertEqual(len(helper.TABLES), 11)
        self.assertNotIn('canonical_status_read', sql)
        self.assertNotIn('public_read', sql)
        for operation in ('GRANT ALL', 'GRANT INSERT', 'GRANT UPDATE', 'GRANT DELETE'):
            self.assertNotIn(operation, sql)
        with self.assertRaises(ValueError):
            helper.grant_sql("bad'capability")

    def test_unknown_grants_memberships_and_elevation_refuse_reuse(self):
        helper = self.helper()
        report = {'role': helper.ROLE, 'login': True, 'superuser': False,
            'createdb': False, 'createrole': False, 'inherit': False,
            'replication': False, 'bypassrls': False,
            'readonly': True, 'memberships': [], 'owned': [],
            'tables': {'freediving.' + table: ['SELECT'] for table in helper.TABLES},
            'sequences': {}, 'definer_functions': [], 'schema_create': [],
            'column_extras': [], 'default_privileges': []}
        helper.validate_grants(report)
        for field, value in [('superuser', True), ('readonly', False),
                ('memberships', ['another_reader']), ('owned', ['freediving.extractions']),
                ('sequences', {'freediving.events_id_seq': ['USAGE']}),
                ('definer_functions', ['freediving.import()']), ('schema_create', ['freediving']),
                ('column_extras', ['freediving.private_keys.key:SELECT']),
                ('default_privileges', ['r:SELECT'])]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                helper.validate_grants({**report, field: value})
        for changed in ({**report['tables'], 'freediving.private_keys': ['SELECT']},
                {**report['tables'], 'freediving.extractions': ['SELECT', 'UPDATE']},
                {key: value for key, value in report['tables'].items() if key != 'freediving.extractions'}):
            with self.assertRaises(ValueError):
                helper.validate_grants({**report, 'tables': changed})


@unittest.skipUnless(os.environ.get('SPORTING_PROOF_POSTGRES_TEST') == '1', 'explicit isolated PostgreSQL test')
class SportingProofPostgresTests(SportingProofCapabilityTests):
    def test_effective_grants_and_actual_denials_in_two_isolated_databases(self):
        helper = self.helper()
        suffix = secrets.token_hex(6)
        source, canonical, role = ('b34_source_' + suffix, 'b34_canonical_' + suffix, 'b34_proof_' + suffix)
        def query(database, sql, *, allowed=True):
            result = subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-d', database],
                                    input=sql, text=True, capture_output=True, timeout=15)
            if allowed:
                self.assertEqual(result.returncode, 0, 'isolated database operation failed')
            else:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('permission denied', result.stderr)
            return result.stdout.strip()
        query('postgres', 'CREATE DATABASE ' + source + '; CREATE DATABASE ' + canonical + ';')
        try:
            for db, tables in ((source, helper.TABLES), (canonical, helper.CANONICAL_TABLES)):
                query(db, 'CREATE SCHEMA freediving; ' + ''.join('CREATE TABLE freediving.' + name + ' (value text);' for name in tables)
                      + "CREATE TABLE freediving.private_keys (value text); INSERT INTO freediving.extractions VALUES ('synthetic');"
                      if db == source else 'CREATE SCHEMA freediving; ' + ''.join('CREATE TABLE freediving.' + name + ' (value text);' for name in tables)
                      + 'CREATE TABLE freediving.private_keys (value text);')
            with patch.object(helper, 'ROLE', role):
                query(source, helper.grant_sql('a' * 48, source))
                query(canonical, helper.canonical_grant_sql(canonical))
                for db, tables in ((source, helper.TABLES), (canonical, helper.CANONICAL_TABLES)):
                    helper.verify_grants(db, tables, query=lambda sql: query(db, sql))
                    target = tables[0]
                    query(db, 'SET ROLE ' + role + '; SELECT * FROM freediving.' + target + ';')
                    for sql in ('SELECT * FROM freediving.private_keys;', 'INSERT INTO freediving.' + target + " VALUES ('bad');"):
                        query(db, 'SET ROLE ' + role + '; SET default_transaction_read_only=off; ' + sql, allowed=False)
                    query(db, 'GRANT SELECT ON freediving.private_keys TO PUBLIC;')
                    with self.assertRaisesRegex(ValueError, 'grants changed'):
                        helper.verify_grants(db, tables, query=lambda sql: query(db, sql))
                    query(db, 'REVOKE SELECT ON freediving.private_keys FROM PUBLIC;')
                    query(db, 'GRANT UPDATE ON freediving.' + target + ' TO ' + role + ';')
                    with self.assertRaisesRegex(ValueError, 'grants changed'):
                        helper.verify_grants(db, tables, query=lambda sql: query(db, sql))
                    query(db, 'REVOKE UPDATE ON freediving.' + target + ' FROM ' + role + ';')
                    helper.verify_grants(db, tables, query=lambda sql: query(db, sql))
                    for grant, revoke in (
                            ('GRANT SELECT(value) ON freediving.private_keys TO '+role,
                             'REVOKE SELECT(value) ON freediving.private_keys FROM '+role),
                            ('GRANT CREATE ON SCHEMA freediving TO '+role,
                             'REVOKE CREATE ON SCHEMA freediving FROM '+role),
                            ('ALTER DEFAULT PRIVILEGES IN SCHEMA freediving GRANT SELECT ON TABLES TO '+role,
                             'ALTER DEFAULT PRIVILEGES IN SCHEMA freediving REVOKE SELECT ON TABLES FROM '+role)):
                        query(db, grant+';')
                        with self.assertRaisesRegex(ValueError,'grants changed'):
                            helper.verify_grants(db,tables,query=lambda sql:query(db,sql))
                        query(db,revoke+';')
                    query(db,"CREATE FUNCTION freediving.elevated() RETURNS int LANGUAGE SQL SECURITY DEFINER AS 'SELECT 1';")
                    with self.assertRaisesRegex(ValueError,'grants changed'):
                        helper.verify_grants(db,tables,query=lambda sql:query(db,sql))
                    query(db,'REVOKE EXECUTE ON FUNCTION freediving.elevated() FROM PUBLIC;')
                    helper.verify_grants(db,tables,query=lambda sql:query(db,sql))
        finally:
            for db in (source, canonical):
                query('postgres', 'DROP DATABASE ' + db + ' WITH (FORCE);')
            query('postgres', 'DROP ROLE IF EXISTS ' + role + ';')


if __name__ == '__main__':
    unittest.main()
