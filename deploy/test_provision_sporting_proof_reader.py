"""Independent exact sporting proof capability boundaries."""
import argparse
from contextlib import ExitStack
import copy
import hashlib
import importlib.util
import json
import tempfile
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


class SportingProofRuntimeUpdateTests(SportingProofCapabilityTests):
    def test_guarded_update_preserves_credentials_and_all_authority_and_refuses_stale_pins(self):
        helper=self.helper()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();config=root/'config.json'
            old=root/'old';old.mkdir();(old/'manifest.json').write_text(json.dumps({'candidate':'a'*40,'files':{}}))
            new=root/'new';new.mkdir();(new/'manifest.json').write_text(json.dumps({'candidate':'b'*40,'files':{}}))
            manifest=root/'app-manifest.json';manifest.write_text(json.dumps({'candidate':'b'*40}))
            value={'jdbc_url':'jdbc:postgresql://127.0.0.1:5432/source?user=sporting_proof_read&password='+'a'*48+'&connectTimeout=5&socketTimeout=10',
                'database':'source','canonical_jdbc_url':'jdbc:postgresql://127.0.0.1:5432/canonical?user=sporting_proof_read&password='+'a'*48+'&connectTimeout=5&socketTimeout=10',
                'canonical_database':'canonical','runtime_path':str(old),'runtime_manifest_sha256':helper.digest(old/'manifest.json')}
            config.write_text(json.dumps(value,sort_keys=True)+'\n');config.chmod(0o640)
            info=config.stat()
            protected={'schema':'private-comparison-activation-guard/v1','authority':{'events':227},'protected':{'sporting_proof':{
                'config':{'path':str(config),'sha256':helper.digest(config),'uid':info.st_uid,'gid':info.st_gid,'mode':0o640},
                'runtime':{'path':str(old),'files':{},'candidate':'a'*40},
                'source_grants':{'exact':11},'canonical_grants':{'exact':5}}}}
            guard_file=root/'guard.json';guard_file.write_text(json.dumps(protected))
            args=argparse.Namespace(runtime=new,runtime_manifest_sha256=helper.digest(new/'manifest.json'),
                app_manifest=manifest,app_manifest_sha256=helper.digest(manifest),guard=guard_file,
                guard_sha256=helper.digest(guard_file),config_sha256=helper.digest(config),
                database='source',canonical_database='canonical',public_database='source')
            def guard(*unused):
                current=copy.deepcopy(protected)
                data=json.loads(config.read_text())
                proof=current['protected']['sporting_proof']
                proof['config']['sha256']=helper.digest(config)
                proof['runtime']={'path':data['runtime_path'],'files':{},'candidate':'a'*40 if data['runtime_path']==str(old) else 'b'*40}
                return current
            with ExitStack() as stack:
                stack.enter_context(patch.object(helper,'CONFIG',config))
                stack.enter_context(patch.object(helper.os,'geteuid',return_value=0))
                stack.enter_context(patch.object(helper.os,'chown'))
                stack.enter_context(patch.object(helper,'verify_grants',return_value={'exact':'verified'}))
                stack.enter_context(patch.object(helper,'verify_config',side_effect=lambda path:json.loads(path.read_text())))
                stack.enter_context(patch('comparison_activate.capture_guard',side_effect=guard))
                self.assertTrue(helper.update_runtime(args))
                updated=json.loads(config.read_text())
                self.assertEqual({k:v for k,v in updated.items() if k not in ('runtime_path','runtime_manifest_sha256')},
                                 {k:v for k,v in value.items() if k not in ('runtime_path','runtime_manifest_sha256')})
                self.assertEqual(updated['runtime_path'],str(new))
                self.assertEqual(config.stat().st_mode&0o777,0o640)
                after=config.read_bytes()
                with self.assertRaises(ValueError):helper.update_runtime(args)
                self.assertEqual(config.read_bytes(),after)
                # An authority change after the config CAS restores only our config;
                # the simulated newer authority remains visible to the caller.
                original=(json.dumps(value,sort_keys=True)+'\n').encode()
                config.write_bytes(original)
                calls=[0]
                def changing_guard(*unused):
                    calls[0]+=1
                    current=guard()
                    if calls[0]>=3:current['authority']['events']=228
                    return current
                with patch('comparison_activate.capture_guard',side_effect=changing_guard):
                    with self.assertRaisesRegex(ValueError,'during proof runtime update'):
                        helper.update_runtime(args)
                self.assertEqual(config.read_bytes(),original)
                # Changed effective grants after a swap also deny and retain credentials.
                with patch.object(helper,'verify_grants',side_effect=[{'exact':'verified'},{'exact':'verified'},ValueError('widened grant')]):
                    with self.assertRaisesRegex(ValueError,'widened grant'):helper.update_runtime(args)
                self.assertEqual(config.read_bytes(),original)


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
