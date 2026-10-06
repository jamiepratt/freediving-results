"""Source reviewer capability rejects any effective privilege beyond receipt append."""
import copy
import unittest
import provision_source_review as helper


class SourceReviewCapabilityTests(unittest.TestCase):
    def report(self):
        return {'role':helper.ROLE,'login':True,'superuser':False,'createdb':False,
                'createrole':False,'inherit':False,'replication':False,'bypassrls':False,
                'readonly':False,'memberships':[],'owned':[],'sequences':{},
                'definer_functions':[],'schema_create':[],'column_extras':[],
                'default_privileges':[], 'tables':{ 'freediving.'+name:
                ['SELECT','INSERT'] if name in helper.RECEIPTS else ['SELECT']
                for name in helper.TABLES}}

    def test_sql_only_appends_existing_receipts_and_verification_denies_elevation(self):
        sql=helper.grant_sql('a'*48)
        self.assertIn('CREATE ROLE sporting_source_review LOGIN NOSUPERUSER',sql)
        self.assertIn('NOINHERIT NOREPLICATION NOBYPASSRLS',sql)
        self.assertIn('default_transaction_read_only=off',sql)
        self.assertIn('GRANT INSERT ON freediving.extraction_reviews,freediving.pdf_extraction_reviews',sql)
        self.assertNotIn('canonical',sql)
        for operation in ('UPDATE','DELETE','TRUNCATE','GRANT ALL','CREATE FUNCTION'):
            self.assertNotIn(operation,sql)
        helper.validate_grants(self.report())
        mutations={'readonly':True,'memberships':['public_read'],'owned':['receipt'],
            'sequences':{'freediving.receipt_seq':['USAGE']},'schema_create':['freediving'],
            'definer_functions':['freediving.elevated()'],'column_extras':['extra:INSERT'],
            'default_privileges':['r:INSERT'],'superuser':True,
            'tables':{**self.report()['tables'],'freediving.observations':['SELECT','INSERT']}}
        for field,value in mutations.items():
            with self.subTest(field=field):
                bad=copy.deepcopy(self.report());bad[field]=value
                with self.assertRaisesRegex(ValueError,'grants changed'):helper.validate_grants(bad)
        with self.assertRaises(ValueError):helper.grant_sql("invalid'password")

if __name__=='__main__':unittest.main()

class SourceReviewProvisionTests(unittest.TestCase):
    def test_guarded_creation_reuse_and_stale_authority_never_write_receipts(self):
        import argparse
        import hashlib
        import json
        import os
        from pathlib import Path
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();runtime=root/'runtime';runtime.mkdir()
            source=runtime/'src/freediving/source_accuracy_review.clj';source.parent.mkdir(parents=True)
            source.write_text('(ns freediving.source-accuracy-review)\n')
            record={'candidate':'b'*40,'files':{'src/freediving/source_accuracy_review.clj':helper.digest(source)}}
            (runtime/'manifest.json').write_text(json.dumps(record))
            app=root/'app.json';app.write_text(json.dumps({'candidate':'b'*40}))
            config=root/'source-review/config.json';guard_file=root/'guard.json'
            expected={'schema':'private-comparison-activation-guard/v1','authority':{'receipts':0},
                      'protected':{'source_review':None,'sporting_proof':{'runtime':{'path':str(runtime),'candidate':'b'*40}}}}
            guard_file.write_text(json.dumps(expected))
            args=argparse.Namespace(runtime=runtime,runtime_manifest_sha256=helper.digest(runtime/'manifest.json'),
                app_manifest=app,app_manifest_sha256=helper.digest(app),guard=guard_file,
                guard_sha256=helper.digest(guard_file),database='source',public_database='source')
            def current(*unused):
                value=copy.deepcopy(expected)
                if config.exists():value['protected']['source_review']={'config_sha256':helper.digest(config)}
                return value
            with patch.object(helper,'CONFIG',config),patch.object(helper.os,'geteuid',return_value=0),\
                 patch.object(helper.os,'chown'),patch.object(helper.grp,'getgrnam') as group,\
                 patch.object(helper,'verify_grants',return_value={}),\
                 patch.object(helper,'verify_config',side_effect=lambda path:json.loads(path.read_text())),\
                 patch('comparison_activate.capture_guard',side_effect=current),\
                 patch.object(helper,'pg',side_effect=lambda sql,db:'0' if sql.startswith('SELECT count') else '') as pg:
                group.return_value.gr_gid=983
                self.assertTrue(helper.provision(args))
                before=config.read_bytes()
                self.assertEqual(config.stat().st_mode&0o777,0o640)
                self.assertEqual(config.parent.stat().st_mode&0o777,0o750)
                self.assertFalse(any('INSERT INTO' in call.args[0] for call in pg.call_args_list))
                expected['protected']['source_review']=current()['protected']['source_review']
                guard_file.write_text(json.dumps(expected));args.guard_sha256=helper.digest(guard_file)
                pg.reset_mock()
                self.assertFalse(helper.provision(args))
                self.assertEqual(config.read_bytes(),before);pg.assert_not_called()
                expected['authority']['receipts']=1
                with self.assertRaisesRegex(ValueError,'guard changed'):helper.provision(args)
                self.assertEqual(config.read_bytes(),before);pg.assert_not_called()
