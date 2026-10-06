"""Private comparison activation preserves authority while changing derived code."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent))
from owner_evidence_activate import Layout
from comparison_activate import capture_guard, activate_comparison, rollback_comparison, stage_payload


class ComparisonActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.layout = Layout(self.root/'app', self.root/'state', self.root/'units', self.root/'config')
        for p in (self.layout.app, self.layout.state, self.layout.units): p.mkdir()
        self.old = self.layout.app/'old'; self.old.mkdir(); (self.old/'app.py').write_text('old')
        (self.layout.app/'current').symlink_to(self.old)
        self.layout.config.write_bytes(b'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG=retained\n')
        (self.layout.state/'active.env').write_bytes(self.layout.config.read_bytes())
        (self.layout.units/'freediving-owner-evidence.service').write_bytes(b'retained-unit')
        for name, content in [('status/presentation-status.json', '{}'), ('current/snapshot.sqlite', 'frozen'),
                              ('current/manifest.json', '{}'), ('current-source/manifest.json','{}')]:
            p=self.layout.state/name;p.parent.mkdir(exist_ok=True);p.write_text(content)
        reader=self.layout.state/'canonical-reader';reader.mkdir();runtime=self.root/'canonical-runtime';runtime.mkdir()
        (runtime/'manifest.json').write_text(json.dumps({'files':{}}))
        (reader/'config.json').write_text(json.dumps({'runtime_path':str(runtime),'runtime_manifest_sha256':self.sha(runtime/'manifest.json'),'exports':{}}))
        decisions=self.layout.state/'decisions';decisions.mkdir()
        self.ledger=decisions/'ledger.sqlite'
        with sqlite3.connect(self.ledger) as db:db.execute('CREATE TABLE events (revision INTEGER)');db.execute('INSERT INTO events VALUES (227)')
        self.public=self.root/'public';self.public.mkdir();(self.public/'app.py').write_text('public')
        self.public_config=self.root/'public.env';self.public_config.write_text('public')
        self.new=self.root/'bundle';self.new.mkdir();(self.new/'app.py').write_text('new')
        (self.new/'private-owner-manifest.json').write_text(json.dumps({'candidate':'a'*40,'files':{'app.py':self.sha(self.new/'app.py')}}))
        self.packet=self.root/'packet.edn';self.packet.write_text('{:synthetic true}')
        self.runtime=self.root/'runtime';self.runtime.mkdir();(self.runtime/'manifest.json').write_text(json.dumps({'candidate':'a'*40,'files':{}}))
        self.config=self.root/'comparison.json';self.config.write_text(json.dumps({'packet':{'path':str(self.packet),'sha256':self.sha(self.packet)},'runtime_path':str(self.runtime),'runtime_manifest_sha256':self.sha(self.runtime/'manifest.json'),'authority_evidence':None}))
        self.config.chmod(0o640)
        self.pg=lambda db: {'synthetic_table':{'count':1,'sha256':'1'*64}}
        self.guard=lambda:capture_guard(self.layout,'synthetic_public',public_app=self.public,public_configs=[self.public_config],table_reader=self.pg)
        self.pins=self.guard()
        self.commands=[]
        self.command=lambda *args:self.commands.append(args)
        self.kw={'guard':self.guard,'command':self.command,'health':lambda:None,'owner_uid':os.geteuid(),'owner_gid':os.getegid(),'service_probe':lambda *args:None}

    def sha(self,path):return hashlib.sha256(path.read_bytes()).hexdigest()

    def test_activation_changes_only_private_app_and_comparison_configuration(self):
        result=activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        self.assertEqual(result,'activated')
        self.assertEqual((self.layout.app/'current').resolve().name, self.sha(self.new/'private-owner-manifest.json'))
        self.assertIn(b'OWNER_EVIDENCE_COMPARISON_CONFIG=',self.layout.config.read_bytes())
        self.assertIn(b'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG=retained',self.layout.config.read_bytes())
        self.assertEqual(self.guard()['authority'],self.pins['authority'])
        self.assertEqual(self.guard()['protected'],self.pins['protected'])
        self.assertEqual((self.layout.state/'comparison/config.json').stat().st_mode & 0o777,0o640)
        self.assertEqual((self.layout.state/'comparison').stat().st_mode & 0o777,0o750)
        rollback_comparison(self.layout,**{k:v for k,v in self.kw.items() if k in ('command','owner_uid','owner_gid')})
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        self.assertEqual(self.layout.config.read_bytes(),b'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG=retained\n')

    def test_stale_authority_guard_refuses_before_app_or_environment_changes(self):
        with sqlite3.connect(self.ledger) as db:db.execute('INSERT INTO events VALUES (228)')
        with self.assertRaisesRegex(ValueError,'live guard changed'):
            activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        self.assertEqual(self.commands,[])

    def test_tampered_runtime_refuses_before_activation(self):
        self.packet.write_text('changed')
        with self.assertRaisesRegex(ValueError,'evidence pin changed'):
            activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        self.assertEqual(self.commands,[])

    def test_guard_change_after_staging_refuses_without_switching_app(self):
        calls=[0]
        def guard():
            calls[0]+=1
            if calls[0]==2:
                with sqlite3.connect(self.ledger) as db:db.execute('INSERT INTO events VALUES (228)')
            return self.guard()
        with self.assertRaisesRegex(ValueError,'changed before activation'):
            activate_comparison(self.new,self.config,self.layout,self.pins,**{**self.kw,'guard':guard})
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        self.assertEqual(self.commands,[])

    def test_failed_health_rolls_back_derived_files_and_preserves_new_human_event(self):
        def health():
            with sqlite3.connect(self.ledger) as db:db.execute('INSERT INTO events VALUES (228)')
            (self.layout.state/'status/presentation-status.json').write_text('{"revision":5}')
            raise RuntimeError('unhealthy')
        with self.assertRaisesRegex(RuntimeError,'unhealthy'):
            activate_comparison(self.new,self.config,self.layout,self.pins,**{**self.kw,'health':health})
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        with sqlite3.connect(self.ledger) as db:self.assertEqual(db.execute('SELECT max(revision) FROM events').fetchone()[0],228)
        self.assertEqual((self.layout.state/'status/presentation-status.json').read_text(),'{"revision":5}')
        self.assertFalse((self.layout.state/'comparison/config.json').exists())

    def test_changed_rollback_backup_refuses_without_mutation(self):
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        (self.layout.state/'comparison-activation-checkpoint/before-env').write_text('tampered')
        before=(self.layout.app/'current').resolve()
        with self.assertRaisesRegex(ValueError,'backup changed'):
            rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.app/'current').resolve(),before)

    def test_newer_deployment_environment_is_not_overwritten_by_rollback(self):
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        (self.layout.state/'active.env').write_text('newer-deployment')
        with self.assertRaisesRegex(ValueError,'derived state changed'):
            rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.state/'active.env').read_text(),'newer-deployment')

    def test_pending_partial_swap_can_restore_only_known_derived_files(self):
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        checkpoint=self.layout.state/'comparison-activation-checkpoint'
        record=json.loads((checkpoint/'record.json').read_text());record['status']='pending'
        (checkpoint/'record.json').write_text(json.dumps(record))
        # A crash between individual atomic writes leaves old/new derived files.
        (self.layout.state/'active.env').write_bytes((checkpoint/'before-env').read_bytes())
        rollback_comparison(self.layout,command=self.command,owner_uid=os.geteuid(),owner_gid=os.getegid())
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())

    def test_private_payload_is_installed_with_service_readable_pinned_versions(self):
        output=stage_payload(self.config,self.layout.state,os.geteuid(),os.getegid(),self.sha(self.config))
        installed=json.loads(output.read_text())
        self.assertEqual(self.sha(Path(installed['packet']['path'])),installed['packet']['sha256'])
        for path in (output,Path(installed['packet']['path']),Path(installed['runtime_path'])/'manifest.json'):
            self.assertEqual(path.stat().st_mode&0o777,0o640)
            self.assertEqual(path.stat().st_gid,os.getegid())
            self.assertEqual(path.parent.stat().st_mode&0o777,0o750)
        self.assertEqual(stage_payload(self.config,self.layout.state,os.geteuid(),os.getegid(),self.sha(self.config)),output)

    def test_uploaded_package_relocates_only_fixed_pinned_paths_and_private_pdf(self):
        source=self.root/'sources'/'synthetic.pdf';source.parent.mkdir();source.write_bytes(b'%PDF-synthetic')
        digest=self.sha(source)
        renamed=source.with_name(digest+'.pdf');source.rename(renamed)
        value=json.loads(self.config.read_text())
        value['packet']['path']='/missing-local-package/packet.edn'
        value['runtime_path']='/missing-local-package/runtime'
        value['source_objects']={digest:{'path':'/missing-local-package/sources/'+digest+'.pdf','sha256':digest,'mime_type':'application/pdf'}}
        self.config.write_text(json.dumps(value))
        output=stage_payload(self.config,self.layout.state,os.geteuid(),os.getegid(),self.sha(self.config))
        installed=json.loads(output.read_text())
        pdf=Path(installed['source_objects'][digest]['path'])
        self.assertEqual(pdf.read_bytes(),b'%PDF-synthetic')
        self.assertEqual(pdf.stat().st_mode&0o777,0o640)
        self.assertEqual(pdf.parent.stat().st_mode&0o777,0o750)

    def test_service_read_execution_failure_refuses_before_active_swaps(self):
        def refused(*args):raise ValueError('service comparison reader preflight refused')
        with self.assertRaisesRegex(ValueError,'service comparison reader preflight refused'):
            activate_comparison(self.new,self.config,self.layout,self.pins,**{**self.kw,'service_probe':refused})
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        self.assertEqual(self.commands,[])

    def test_rollback_restores_root_style_config_ownership_not_service_ownership(self):
        installed=self.layout.state/'comparison/config.json';installed.parent.mkdir();installed.write_text('prior-root-config');installed.chmod(0o640)
        activate_comparison(self.new,self.config,self.layout,self.guard(),**self.kw)
        rollback_comparison(self.layout,command=self.command,owner_uid=os.geteuid()+1,owner_gid=os.getegid())
        self.assertEqual(installed.read_text(),'prior-root-config')
        self.assertEqual(installed.stat().st_uid,os.geteuid())
        self.assertEqual(installed.stat().st_mode&0o777,0o640)

    def test_changed_service_unit_refuses_rollback_before_restoring_derived_files(self):
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        (self.layout.units/'freediving-owner-evidence.service').write_bytes(b'newer-unit')
        before=(self.layout.app/'current').resolve()
        with self.assertRaisesRegex(ValueError,'unit changed'):
            rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.app/'current').resolve(),before)

if __name__=='__main__':unittest.main()
