"""Public rollback through real files and a stand-in PostgreSQL capability."""
import copy
from contextlib import contextmanager
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0,str(Path(__file__).parent))
import public_app_rollback as rollback

class PublicRollbackTests(unittest.TestCase):
    @contextmanager
    def case(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); prior=root/'releases'/('a'*40); candidate=root/'releases'/('b'*40)
            for release in (prior,candidate):
                (release/'deploy').mkdir(parents=True)
                (release/'REVISION').write_text(release.name+'\n')
                (release/'resources/migrations').mkdir(parents=True)
                (release/'resources/migrations/022-public-sporting-comparison.sql').write_text('new')
                (release/'deploy/freediving-public.service').write_text('unit '+release.name)
            config=root/'config';config.mkdir()
            for name in ('public.env','migration.env'):(config/name).write_text('synthetic')
            unit=root/'public.service';unit.write_bytes((prior/'deploy/freediving-public.service').read_bytes())
            current=root/'current';current.symlink_to(prior,target_is_directory=True)
            layout=rollback.Layout(root/'releases',current,config,unit)
            before={'database':'production','port':'5432','owner':'freediving_migrator','tables':{'observations':{'count':81,'sha256':'retained'}},'versions':{str(n):'old' for n in range(1,22)},'role':{'select':rollback.BASE_SELECT.copy(),'unsafe':False,'view_column_acl':False},'view':None,'policy':[]}
            live=copy.deepcopy(before)
            checkpoint=rollback.capture(candidate,layout,reader=lambda:copy.deepcopy(live))
            current.unlink();current.symlink_to(candidate,target_is_directory=True)
            unit.write_bytes((candidate/'deploy/freediving-public.service').read_bytes())
            live['versions']['22']=rollback.sha(candidate/'resources/migrations/022-public-sporting-comparison.sql')
            live['tables'].update({name:{'count':count,'sha256':'new'} for name,count in [('public_sporting_policy_events',1),('public_sporting_authority_events',0),('public_sporting_members',0)]})
            live['policy']=[{'revision':1,'policy_version':'aida-baseline-v1','db_role':'freediving_migrator'}]
            live['view']={'kind':'v','owner':'freediving_migrator'}
            live['role']['select'].append(rollback.VIEW)
            yield prior,candidate,layout,before,live,checkpoint

    def test_schema_twenty_three_absent_authority_allows_empty_prior_app(self):
        with self.case() as (prior,candidate,layout,before,live,checkpoint):
            migration=candidate/'resources/migrations/023-sporting-authority-bridge.sql'
            migration.write_text('bridge')
            layout.current.unlink();layout.current.symlink_to(prior)
            layout.unit.write_bytes((prior/'deploy/freediving-public.service').read_bytes())
            before=copy.deepcopy(live)
            checkpoint=rollback.capture(candidate,layout,reader=lambda:copy.deepcopy(before))
            layout.current.unlink();layout.current.symlink_to(candidate)
            layout.unit.write_bytes((candidate/'deploy/freediving-public.service').read_bytes())
            live['versions']['23']=rollback.sha(migration)
            live['tables']['public_sporting_bridge_receipts']={'count':0,'sha256':'empty'}
            rollback.rollback(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live))
            self.assertEqual(layout.current.resolve(),prior.resolve())
            self.assertIn('23',live['versions'])

    def test_live_bridge_or_retained_facts_refuse_prior_unguarded_app(self):
        for configured in (False,True):
            with self.subTest(configured=configured),self.case() as (prior,candidate,layout,before,live,checkpoint):
                migration=candidate/'resources/migrations/023-sporting-authority-bridge.sql'
                migration.write_text('bridge')
                if configured:(layout.config/'sporting-authority.env').write_text('configured')
                layout.current.unlink();layout.current.symlink_to(prior)
                layout.unit.write_bytes((prior/'deploy/freediving-public.service').read_bytes())
                checkpoint=rollback.capture(candidate,layout,reader=lambda:copy.deepcopy(live))
                layout.current.unlink();layout.current.symlink_to(candidate)
                layout.unit.write_bytes((candidate/'deploy/freediving-public.service').read_bytes())
                live['versions']['23']=rollback.sha(migration)
                live['tables']['public_sporting_bridge_receipts']={'count':0 if configured else 1,'sha256':'retained'}
                with self.assertRaisesRegex(ValueError,'unguarded'):
                    rollback.rollback(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live))
                self.assertEqual(layout.current.resolve(),candidate.resolve())

    def test_relocated_public_config_hash_and_permission_drift_are_guarded(self):
        with self.case() as (prior,candidate,layout,before,live,checkpoint):
            directory=layout.config.parent/'relocated-sporting';directory.mkdir()
            config=directory/'config.json';config.write_text('synthetic')
            with mock.patch.object(rollback,'PUBLIC_SPORTING_DIRECTORY',directory):
                layout.current.unlink();layout.current.symlink_to(prior)
                layout.unit.write_bytes((prior/'deploy/freediving-public.service').read_bytes())
                checkpoint=rollback.capture(candidate,layout,reader=lambda:copy.deepcopy(live))
                self.assertIn(str(config),checkpoint['bridge_files'])
                layout.current.unlink();layout.current.symlink_to(candidate)
                layout.unit.write_bytes((candidate/'deploy/freediving-public.service').read_bytes())
                config.chmod(0o600)
                with self.assertRaisesRegex(ValueError,'configuration changed'):
                    rollback.assess(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live))

    def test_old_app_restarts_after_new_grant_without_restoring_data(self):
        with self.case() as (prior,candidate,layout,before,live,checkpoint):
            calls=[]
            def revoke():
                calls.append('revoke');live['role']['select'].remove(rollback.VIEW)
            def start_old():
                calls.append('start')
                if live['role']!=before['role']:raise RuntimeError('old public app refuses unexpected SELECT grant')
            retained=copy.deepcopy(live['tables'])
            rollback.rollback(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live),revoke=revoke)
            start_old()
            self.assertEqual(calls,['revoke','start'])
            self.assertEqual(layout.current.resolve(),prior.resolve())
            self.assertEqual(layout.unit.read_bytes(),(prior/'deploy/freediving-public.service').read_bytes())
            self.assertEqual(live['tables'],retained)
            self.assertEqual(live['versions']['21'],'old');self.assertIn('22',live['versions'])

    def test_readonly_assessment_leaves_files_and_privileges_unchanged(self):
        with self.case() as (prior,candidate,layout,before,live,checkpoint):
            snapshot=copy.deepcopy(live)
            rollback.assess(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live))
            self.assertEqual(snapshot,live)
            self.assertEqual(layout.current.resolve(),candidate.resolve())

    def test_correction_sporting_policy_and_capability_drift_refuse_before_revoke(self):
        changes=[lambda s:s['tables']['observations'].update(sha256='correction'),
                 lambda s:s['tables']['public_sporting_authority_events'].update(count=1),
                 lambda s:s['tables']['public_sporting_members'].update(count=1),
                 lambda s:s['policy'].append({'revision':2,'policy_version':'new','db_role':'freediving_migrator'}),
                 lambda s:s['versions'].update({'21':'tampered'}),
                 lambda s:s['role'].update(unsafe=True),
                 lambda s:s['role'].update(view_column_acl=True),
                 lambda s:s['role']['select'].append('freediving.observations'),
                 lambda s:s['view'].update(owner='unexpected')]
        for change in changes:
            with self.subTest(change=change),self.case() as (_,candidate,layout,_,live,checkpoint):
                change(live)
                revoke=mock.Mock()
                with self.assertRaises(ValueError):rollback.rollback(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live),revoke=revoke)
                revoke.assert_not_called()
                self.assertEqual(layout.current.resolve(),candidate.resolve())

    def test_prior_and_candidate_tree_config_unit_and_pointer_drift_refuse(self):
        mutations=[lambda prior,candidate,layout:(prior/'REVISION').write_text('changed'),
                   lambda prior,candidate,layout:(prior/'deploy/extra').write_text('changed'),
                   lambda prior,candidate,layout:(candidate/'deploy/extra').write_text('changed'),
                   lambda prior,candidate,layout:(layout.config/'public.env').write_text('changed'),
                   lambda prior,candidate,layout:layout.unit.write_text('changed'),
                   lambda prior,candidate,layout:(candidate/'linked').symlink_to(prior/'REVISION')]
        for mutation in mutations:
            with self.subTest(mutation=mutation),self.case() as (prior,candidate,layout,_,live,checkpoint):
                mutation(prior,candidate,layout);revoke=mock.Mock()
                with self.assertRaises(ValueError):rollback.rollback(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live),revoke=revoke)
                revoke.assert_not_called()
                self.assertEqual(layout.current.resolve(),candidate.resolve())

    def test_failed_revocation_cannot_switch_to_prior_app(self):
        with self.case() as (_,candidate,layout,_,live,checkpoint):
            with self.assertRaises(ValueError):rollback.rollback(checkpoint,candidate,layout,reader=lambda:copy.deepcopy(live),revoke=lambda:None)
            self.assertEqual(layout.current.resolve(),candidate.resolve())

    def test_grant_change_locks_and_checks_exact_tables_without_fact_writes(self):
        state={'tables':{'observations':{'count':81,'sha256':'a'*64},'public_sporting_authority_events':{'count':0,'sha256':'b'*64}}}
        with mock.patch.object(rollback,'query') as query:
            rollback.revoke_view(rollback.Layout(),state)
        sql=query.call_args.args[1]
        self.assertIn(' IN SHARE MODE',sql)
        self.assertIn("'"+'a'*64+"'",sql)
        self.assertIn('REVOKE SELECT ON freediving.public_sporting_comparison FROM reviews_public',sql)
        for forbidden in ['DELETE','INSERT','UPDATE','TRUNCATE','DROP']:self.assertNotIn(forbidden,sql)
        self.assertTrue(query.call_args.kwargs['write'])

    def test_activation_captures_before_migration_and_restores_before_restart(self):
        shell=Path(__file__).with_name('activate.sh').read_text()
        self.assertLess(shell.index('public_app_rollback.py" capture'),shell.index('migrate_only.py'))
        failed=shell[shell.index('if ! python3'):]
        self.assertLess(failed.index('public_app_rollback.py" rollback'),failed.index('systemctl restart'))
        self.assertIn('systemctl daemon-reload',failed)
        self.assertNotIn('ln -sfn',failed)
