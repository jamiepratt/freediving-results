"""Private comparison activation preserves authority while changing derived code."""
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
import unittest
import urllib.error
from unittest import mock
import comparison_activate
sys.path.insert(0, str(Path(__file__).resolve().parent))
from owner_evidence_activate import Layout
from comparison_activate import capture_guard, activate_comparison, rollback_comparison, stage_payload


class ComparisonActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
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

    def test_relationship_ledger_schema_and_events_guard_activation_and_rollback(self):
        bridge=self.layout.state/'sporting-bridge';bridge.mkdir()
        for name in ('config.json','signing.pem','request.key','provision.json'):(bridge/name).write_text('synthetic '+name)
        ledger=bridge/'ledger/relationships.sqlite';ledger.parent.mkdir()
        with sqlite3.connect(ledger) as db:
            db.execute('CREATE TABLE relationship_events (revision INTEGER, action TEXT)')
        pins=self.guard()
        self.assertEqual(pins['sporting']['relationships']['tables']['relationship_events']['count'],0)
        activate_comparison(self.new,self.config,self.layout,pins,**self.kw)
        with sqlite3.connect(ledger) as db:db.execute("INSERT INTO relationship_events VALUES (1,'distinct')")
        before=(self.layout.app/'current').resolve()
        with self.assertRaisesRegex(ValueError,'sporting authority changed'):
            rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.app/'current').resolve(),before)
        with sqlite3.connect(ledger) as db:self.assertEqual(db.execute('SELECT max(revision) FROM relationship_events').fetchone()[0],1)

    def test_explicit_proof_reader_path_refuses_external_config_before_activation(self):
        with self.assertRaisesRegex(ValueError, 'sporting proof configuration path'):
            activate_comparison(self.new,self.config,self.layout,self.pins,
                                proof_config=self.root/'external-proof.json',**self.kw)
        self.assertEqual((self.layout.app/'current').resolve(), self.old.resolve())
        self.assertEqual(self.commands, [])

    def test_proof_capability_activation_and_rollback_pin_env_runtime_and_exact_grants(self):
        import provision_sporting_proof_reader
        directory=self.layout.state/'sporting-proof-reader';directory.mkdir()
        proof=directory/'config.json'
        runtime=self.root/'proof-runtime';runtime.mkdir()
        (runtime/'manifest.json').write_text(json.dumps({'candidate':'a'*40,'files':{}}))
        proof.write_text(json.dumps({'jdbc_url':'jdbc:postgresql://127.0.0.1:5432/source?user=sporting_proof_read&password=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&connectTimeout=5&socketTimeout=10',
            'database':'source','canonical_jdbc_url':'jdbc:postgresql://127.0.0.1:5432/canonical?user=sporting_proof_read&password=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&connectTimeout=5&socketTimeout=10',
            'canonical_database':'canonical','runtime_path':str(runtime),
            'runtime_manifest_sha256':self.sha(runtime/'manifest.json')}));proof.chmod(0o640)
        with mock.patch.object(provision_sporting_proof_reader,'verify_grants',return_value={'exact':'verified'}) as grants:
            pins=self.guard()
            probes=[]
            def probe(app,config,uid,gid):probes.append((config,uid,gid))
            activate_comparison(self.new,self.config,self.layout,pins,proof_config=proof,proof_probe=probe,**self.kw)
            self.assertEqual(probes,[(proof,os.geteuid(),os.getegid())])
            self.assertIn(('OWNER_EVIDENCE_SPORTING_PROOF_CONFIG='+str(proof)).encode(),self.layout.config.read_bytes())
            self.assertEqual(self.guard()['authority'],pins['authority'])
            with mock.patch.object(provision_sporting_proof_reader,'verify_grants',side_effect=ValueError('grants changed')):
                before=(self.layout.app/'current').resolve()
                with self.assertRaises(ValueError):rollback_comparison(self.layout,command=self.command)
                self.assertEqual((self.layout.app/'current').resolve(),before)
            active=(self.layout.app/'current').resolve()
            with self.assertRaisesRegex(ValueError,'source authority changed'):
                rollback_comparison(self.layout,command=self.command,proof_probe=probe,
                                    authority_reader=lambda db:{'synthetic_table':{'count':2,'sha256':'2'*64}})
            self.assertEqual((self.layout.app/'current').resolve(),active)
            with self.assertRaisesRegex(ValueError,'incompatible old runtime'):
                rollback_comparison(self.layout,command=self.command,authority_reader=self.pg,
                                    proof_probe=mock.Mock(side_effect=ValueError('incompatible old runtime')))
            self.assertEqual((self.layout.app/'current').resolve(),active)
            compatibility=[]
            def compatible(app,config,uid,gid):
                self.assertEqual((self.layout.app/'current').resolve(),active)
                compatibility.append((app,config))
            rollback_comparison(self.layout,command=self.command,proof_probe=compatible,authority_reader=self.pg)
            self.assertEqual(compatibility,[(self.old,proof)])
            self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
            self.assertNotIn(b'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG=',self.layout.config.read_bytes())
            self.assertEqual(set(call.args[0] for call in grants.call_args_list),{'source','canonical'})

    def test_source_review_activation_env_pins_and_stale_guards_refuse(self):
        import provision_source_review
        import provision_sporting_proof_reader
        runtime=self.root/'proof-runtime';runtime.mkdir()
        (runtime/'manifest.json').write_text(json.dumps({'candidate':'a'*40,'files':{}}))
        common={'database':'source','runtime_path':str(runtime),'runtime_manifest_sha256':self.sha(runtime/'manifest.json')}
        proof=self.layout.state/'sporting-proof-reader/config.json';proof.parent.mkdir()
        proof.write_text(json.dumps({**common,'jdbc_url':'jdbc:postgresql://127.0.0.1:5432/source?user=sporting_proof_read&password='+'a'*48+'&connectTimeout=5&socketTimeout=10',
            'canonical_database':'canonical','canonical_jdbc_url':'jdbc:postgresql://127.0.0.1:5432/canonical?user=sporting_proof_read&password='+'a'*48+'&connectTimeout=5&socketTimeout=10'}));proof.chmod(0o640)
        review=self.layout.state/'source-review/config.json';review.parent.mkdir();review.parent.chmod(0o750)
        review.write_text(json.dumps({**common,'jdbc_url':'jdbc:postgresql://127.0.0.1:5432/source?user=sporting_source_review&password='+'b'*48+'&connectTimeout=5&socketTimeout=10'}));review.chmod(0o640)
        with mock.patch.object(provision_source_review,'verify_grants',return_value={'receipt_append':2}),\
             mock.patch.object(provision_sporting_proof_reader,'verify_grants',return_value={'exact':'verified'}):
            pins=self.guard()
            with self.assertRaisesRegex(ValueError,'source review configuration path'):
                activate_comparison(self.new,self.config,self.layout,pins,source_review_config=self.root/'external.json',**self.kw)
            changed={**common,'jdbc_url':json.loads(review.read_text())['jdbc_url']+'changed'}
            review.write_text(json.dumps(changed))
            with self.assertRaises(ValueError):
                activate_comparison(self.new,self.config,self.layout,pins,source_review_config=review,**self.kw)
            self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
            review.write_text(json.dumps({**common,'jdbc_url':'jdbc:postgresql://127.0.0.1:5432/source?user=sporting_source_review&password='+'b'*48+'&connectTimeout=5&socketTimeout=10'}))
            activate_comparison(self.new,self.config,self.layout,pins,proof_config=proof,proof_probe=lambda *args:None,
                                source_review_config=review,**self.kw)
            expected=('OWNER_EVIDENCE_SOURCE_REVIEW_CONFIG='+str(review)).encode()
            self.assertIn(expected,self.layout.config.read_bytes())
            self.assertIn(expected,(self.layout.state/'active.env').read_bytes())
            with mock.patch.object(provision_source_review,'verify_grants',side_effect=ValueError('review grants changed')):
                with self.assertRaisesRegex(ValueError,'review grants changed'):
                    rollback_comparison(self.layout,command=self.command)
                self.assertNotEqual((self.layout.app/'current').resolve(),self.old.resolve())
            rollback_comparison(self.layout,command=self.command,authority_reader=self.pg,proof_probe=lambda *args:None)
            self.assertNotIn(expected,self.layout.config.read_bytes())
            self.assertNotIn(expected,(self.layout.state/'active.env').read_bytes())

    def test_sporting_schema_rows_and_key_pins_guard_activation_and_rollback(self):
        bridge=self.layout.state/'sporting-bridge';bridge.mkdir()
        for name in ('config.json','signing.pem','request.key','provision.json'):(bridge/name).write_text('synthetic '+name)
        ledger=bridge/'ledger/authority.sqlite';ledger.parent.mkdir()
        with sqlite3.connect(ledger) as db:
            db.execute('CREATE TABLE sporting_events (revision INTEGER, action TEXT)')
            db.execute("INSERT INTO sporting_events VALUES (1,'approved')")
        pins=self.guard()
        self.assertEqual(pins['sporting']['ledger']['tables']['sporting_events']['count'],1)
        activate_comparison(self.new,self.config,self.layout,pins,**self.kw)
        with sqlite3.connect(ledger) as db:db.execute("INSERT INTO sporting_events VALUES (2,'reversed')")
        before=(self.layout.app/'current').resolve()
        with self.assertRaisesRegex(ValueError,'sporting authority changed'):
            rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.app/'current').resolve(),before)
        with sqlite3.connect(ledger) as db:self.assertEqual(db.execute('SELECT max(revision) FROM sporting_events').fetchone()[0],2)

    def test_changed_sporting_signer_and_new_capability_refuse_stale_activation_or_rollback(self):
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        bridge=self.layout.state/'sporting-bridge';bridge.mkdir()
        for name in ('config.json','signing.pem','request.key','provision.json'):(bridge/name).write_text('synthetic '+name)
        (bridge/'ledger').mkdir()
        with self.assertRaisesRegex(ValueError,'sporting authority changed'):
            rollback_comparison(self.layout,command=self.command)
        pins=self.guard();(bridge/'signing.pem').write_text('changed')
        with self.assertRaisesRegex(ValueError,'live guard changed'):
            activate_comparison(self.new,self.config,self.layout,pins,**self.kw)

    def test_relocated_public_verifier_is_part_of_private_activation_guard(self):
        bridge=self.layout.state/'sporting-bridge';bridge.mkdir()
        for name in ('config.json','signing.pem','request.key','provision.json'):(bridge/name).write_text('synthetic '+name)
        (bridge/'ledger').mkdir()
        public=self.root/'relocated-public.json';public.write_text('synthetic public verifier')
        with mock.patch.object(comparison_activate,'PUBLIC_SPORTING_CONFIG',public):
            pins=self.guard()
            self.assertIn(str(public),pins['sporting']['files'])
            public.chmod(0o600)
            with self.assertRaisesRegex(ValueError,'live guard changed'):
                activate_comparison(self.new,self.config,self.layout,pins,**self.kw)

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

    def test_full_source_proof_503_rolls_back_derived_deployment_without_restoring_authority(self):
        values,bodies,requests,opener=SportingComparisonHealthTests().fixture(self.root)
        bodies['/owner-evidence/api/sporting-authority/proofs?limit=1']=urllib.error.HTTPError('http://synthetic',503,'Unavailable',{'Retry-After':'1'},None)
        before=self.layout.config.read_bytes()
        with mock.patch.object(comparison_activate,'_health'),mock.patch.object(comparison_activate.urllib.request,'build_opener',return_value=opener),mock.patch.object(comparison_activate.time,'sleep'):
            with self.assertRaises(urllib.error.HTTPError):
                activate_comparison(self.new,self.config,self.layout,self.pins,
                    **{**self.kw,'health':lambda:comparison_activate._comparison_health(values)})
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        self.assertEqual(self.layout.config.read_bytes(),before)
        self.assertEqual(self.guard()['authority'],self.pins['authority'])

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


    def test_service_preflight_closes_reader_after_success_and_failure(self):
        scripts=self.root/'probe-scripts';scripts.mkdir()
        sentinel=self.root/'closed'
        (scripts/'private_attempt_inspector.py').write_text("""
from pathlib import Path
class Reader:
    def __call__(self,*args):
        return {"counts":{"source_positions":138,"retained_observation_versions":276,"distinct_sporting_attempts":None,"eligible_peer_cohorts":0},"coverage":{"ranked":0}}
    def close(self):Path(%r).write_text('closed')
def create_reader(env):return Reader()
""" % str(sentinel))
        app=self.root/'probe-app';app.mkdir();(app/'scripts').symlink_to(scripts)
        comparison_activate._service_probe(app,self.config,os.geteuid(),os.getegid())
        self.assertEqual(sentinel.read_text(),'closed')
        sentinel.unlink()
        module=scripts/'private_attempt_inspector.py'
        module.write_text(module.read_text().replace('138','137'))
        with self.assertRaisesRegex(ValueError,'preflight refused'):
            comparison_activate._service_probe(app,self.config,os.geteuid(),os.getegid())
        self.assertEqual(sentinel.read_text(),'closed')


    def test_timed_out_service_preflight_terminates_owned_child(self):
        app=self.root/'slow-app';scripts=app/'scripts';scripts.mkdir(parents=True)
        heartbeat=self.root/'heartbeat'
        child="import time;from pathlib import Path\nwhile True:\n with Path(%r).open('a') as stream:stream.write('alive\\n')\n time.sleep(0.03)" % str(heartbeat)
        (scripts/'private_attempt_inspector.py').write_text("import subprocess,time\ndef create_reader(env):\n subprocess.Popen(['/usr/bin/python3','-c',%r])\n time.sleep(10)\n" % child)
        with mock.patch.object(comparison_activate,'SERVICE_PROBE_TIMEOUT',0.4):
            started=time.monotonic()
            with self.assertRaisesRegex(ValueError,'preflight refused'):
                comparison_activate._service_probe(app,self.config,os.geteuid(),os.getegid())
        self.assertLess(time.monotonic()-started,2)
        self.assertTrue(heartbeat.exists())
        before=heartbeat.read_bytes();time.sleep(0.15)
        self.assertEqual(heartbeat.read_bytes(),before)

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


    def add_candidate_service_unit(self):
        unit=self.new/'deploy'/'freediving-owner-evidence.service'
        unit.parent.mkdir();unit.write_bytes(b'[Service]\nTasksMax=64\nMemoryMax=1G\n')
        manifest=self.new/'private-owner-manifest.json'
        value=json.loads(manifest.read_text());value['files']['deploy/freediving-owner-evidence.service']=self.sha(unit)
        manifest.write_text(json.dumps(value))
        return unit.read_bytes()

    def test_packaged_unit_activates_and_rolls_back_with_derived_app(self):
        candidate=self.add_candidate_service_unit()
        protected=self.guard()
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        unit=self.layout.units/'freediving-owner-evidence.service'
        self.assertEqual(unit.read_bytes(),candidate)
        self.assertEqual(unit.stat().st_mode&0o777,0o644)
        self.assertEqual(self.commands,[('systemctl','daemon-reload'),('systemctl','restart','freediving-owner-evidence.service')])
        self.assertEqual(self.guard()['authority'],protected['authority'])
        self.assertEqual(self.guard()['protected'],protected['protected'])
        rollback_comparison(self.layout,command=self.command)
        self.assertEqual(unit.read_bytes(),b'retained-unit')
        self.assertEqual(self.commands[-2:],[('systemctl','daemon-reload'),('systemctl','restart','freediving-owner-evidence.service')])


    def test_failed_health_restores_packaged_unit_and_keeps_newer_human_event(self):
        self.add_candidate_service_unit()
        def unhealthy():
            with sqlite3.connect(self.ledger) as db:db.execute('INSERT INTO events VALUES (228)')
            raise RuntimeError('unhealthy')
        with self.assertRaisesRegex(RuntimeError,'unhealthy'):
            activate_comparison(self.new,self.config,self.layout,self.pins,**{**self.kw,'health':unhealthy})
        self.assertEqual((self.layout.units/'freediving-owner-evidence.service').read_bytes(),b'retained-unit')
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        with sqlite3.connect(self.ledger) as db:self.assertEqual(db.execute('SELECT max(revision) FROM events').fetchone()[0],228)

    def test_tampered_unit_backup_refuses_before_any_derived_restore(self):
        self.add_candidate_service_unit()
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        before=self.guard()
        (self.layout.state/'comparison-activation-checkpoint/before-unit').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError,'backup changed'):
            rollback_comparison(self.layout,command=self.command)
        self.assertEqual(self.guard(),before)

    def test_legacy_unit_checkpoint_can_restore_its_known_derived_state(self):
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        checkpoint=self.layout.state/'comparison-activation-checkpoint/record.json'
        record=json.loads(checkpoint.read_text())
        for state in ('before','after'):record[state].pop('unit')
        checkpoint.write_text(json.dumps(record))
        rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        self.assertEqual((self.layout.units/'freediving-owner-evidence.service').read_bytes(),b'retained-unit')


    def test_interrupted_unit_swap_rolls_back_only_recorded_old_or_new_unit(self):
        self.add_candidate_service_unit()
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        checkpoint=self.layout.state/'comparison-activation-checkpoint'
        record=json.loads((checkpoint/'record.json').read_text());record['status']='pending'
        (checkpoint/'record.json').write_text(json.dumps(record))
        (self.layout.units/'freediving-owner-evidence.service').write_bytes((checkpoint/'before-unit').read_bytes())
        rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.app/'current').resolve(),self.old.resolve())
        self.assertEqual((self.layout.units/'freediving-owner-evidence.service').read_bytes(),b'retained-unit')

    def test_changed_service_unit_refuses_rollback_before_restoring_derived_files(self):
        activate_comparison(self.new,self.config,self.layout,self.pins,**self.kw)
        (self.layout.units/'freediving-owner-evidence.service').write_bytes(b'newer-unit')
        before=(self.layout.app/'current').resolve()
        with self.assertRaisesRegex(ValueError,'unit changed'):
            rollback_comparison(self.layout,command=self.command)
        self.assertEqual((self.layout.app/'current').resolve(),before)


class SportingComparisonHealthTests(unittest.TestCase):
    def fixture(self,root):
        capability=root/'proof-config.json';capability.write_text('synthetic exact private proof capability')
        scopes={'source':'a'*64,'relationships':'b'*64}
        pins={'snapshot_sha256':'c'*64,'owner_revision':227,'owner_binding_revision':215,
              'canonical_upstream_config_sha256':hashlib.sha256(capability.read_bytes()).hexdigest(),
              'canonical_upstream_sha256':hashlib.sha256(json.dumps(scopes,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
              'canonical_scope_bindings':scopes}
        bodies={'/owner-evidence/api/sporting-authority/review':{'schema':'sporting-authority-review/v1','revision':0,
                    'currentness':'available','proposals':[],'csrf_token':'never-persist-review-token'},
                '/owner-evidence/api/sporting-authority/proofs?limit=1':{'schema':'sporting-exact-proof-diagnostics/v1',
                    'pins':pins,'source_positions':138,'pagination':{'offset':0,'limit':1,'total':276},
                    'relationship_available':True,'relationship_revision':0,'rows':[{'reference':{'source-sha256':'d'*64},
                    'coordinates':{'page':1,'line':1},'upstream':{},'diagnostics':{'mapping':{'state':'unmapped'}}}]},
                '/owner-evidence/api/attempt-inspector?limit=1':{'schema':'private-attempt-inspector/v1',
                    'counts':{'source_positions':138,'retained_observation_versions':276,'distinct_sporting_attempts':None,'eligible_peer_cohorts':0},
                    'coverage':{'ranked':0},'pagination':{'total':138}}}
        values={'OWNER_EVIDENCE_SNAPSHOT_SHA256':'c'*64,'OWNER_EVIDENCE_ORIGIN_HOST':'owner-origin.alphacompose.com',
                'OWNER_EVIDENCE_GATEWAY_SECRET':'synthetic-private-gateway','OWNER_EVIDENCE_EMAILS':'owner@example.invalid',
                'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG':str(capability)}
        requests=[]
        class Response(io.BytesIO):
            status=200
            headers={'Cache-Control':'no-store'}
        class Opener:
            def open(self,request,timeout):
                requests.append(request.full_url.split(':8081')[1])
                self.assert_auth(request)
                body=bodies[requests[-1]]
                if callable(body):body=body()
                if isinstance(body,Exception):raise body
                return Response(json.dumps(body).encode())
            def assert_auth(self,request):
                assert request.get_method()=='GET'
                assert request.get_header('X-freediving-owner-gateway')==values['OWNER_EVIDENCE_GATEWAY_SECRET']
                assert request.get_header('X-freediving-owner-email')=='owner@example.invalid'
        return values,bodies,requests,Opener()

    def test_health_warms_review_and_inspector_before_exact_proofs_and_accepts_unmapped_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            values,bodies,requests,opener=self.fixture(Path(directory))
            with mock.patch.object(comparison_activate,'_health'),mock.patch.object(comparison_activate.urllib.request,'build_opener',return_value=opener):
                comparison_activate._comparison_health(values)
            self.assertEqual(requests,['/owner-evidence/api/sporting-authority/review',
                '/owner-evidence/api/attempt-inspector?limit=1','/owner-evidence/api/sporting-authority/proofs?limit=1'])

    def test_retry_after_one_recovers_cold_read_within_three_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            values,bodies,requests,opener=self.fixture(Path(directory))
            path='/owner-evidence/api/sporting-authority/review'
            responses=iter([urllib.error.HTTPError('http://synthetic',503,'cold',{'Retry-After':'1'},None),
                            urllib.error.HTTPError('http://synthetic',503,'cold',{'Retry-After':'1'},None),bodies[path]])
            bodies[path]=lambda:next(responses)
            with mock.patch.object(comparison_activate,'_health'),mock.patch.object(comparison_activate.urllib.request,'build_opener',return_value=opener),mock.patch.object(comparison_activate.time,'sleep') as pause:
                comparison_activate._comparison_health(values)
            self.assertEqual(requests[:3],[path]*3)
            self.assertEqual(pause.call_args_list,[mock.call(1),mock.call(1)])
            self.assertEqual(requests[-2:],['/owner-evidence/api/attempt-inspector?limit=1','/owner-evidence/api/sporting-authority/proofs?limit=1'])

    def test_permanent_or_malformed_retry_denial_remains_a_failed_health_gate(self):
        for status,header,attempts in ((503,{'Retry-After':'1'},3),(503,{'Retry-After':'1.0'},1),(503,{},1),(500,{'Retry-After':'1'},1)):
            with self.subTest(header=header),tempfile.TemporaryDirectory() as directory:
                values,bodies,requests,opener=self.fixture(Path(directory))
                path='/owner-evidence/api/sporting-authority/proofs?limit=1'
                bodies[path]=urllib.error.HTTPError('http://synthetic',status,'unavailable',header,None)
                with mock.patch.object(comparison_activate,'_health'),mock.patch.object(comparison_activate.urllib.request,'build_opener',return_value=opener),mock.patch.object(comparison_activate.time,'sleep') as pause:
                    with self.assertRaises(urllib.error.HTTPError):comparison_activate._comparison_health(values)
                self.assertEqual(requests.count(path),attempts)
                self.assertEqual(pause.call_count,attempts-1)
                self.assertEqual(requests[-1],path)

    def test_current_nonempty_source_review_envelope_remains_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            values,bodies,requests,opener=self.fixture(Path(directory))
            review=bodies['/owner-evidence/api/sporting-authority/review']
            review['revision']=3;review['proposals']=[{'proposal':{'id':'retained-staged-proposal'},'action':'select-cohort','revision':3}]
            with mock.patch.object(comparison_activate,'_health'),mock.patch.object(comparison_activate.urllib.request,'build_opener',return_value=opener):
                comparison_activate._comparison_health(values)
            self.assertEqual(len(requests),3)

    def test_full_inventory_proof_denial_or_missing_current_binding_fails_health(self):
        for failure in ('unavailable-review','proof503','invalid-proof-binding','missing-capability'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as directory:
                values,bodies,requests,opener=self.fixture(Path(directory))
                if failure=='unavailable-review':bodies['/owner-evidence/api/sporting-authority/review']['currentness']='unavailable'
                if failure=='proof503':bodies['/owner-evidence/api/sporting-authority/proofs?limit=1']=urllib.error.HTTPError('http://synthetic',503,'Unavailable',{},None)
                if failure=='invalid-proof-binding':bodies['/owner-evidence/api/sporting-authority/proofs?limit=1']['pins']['canonical_upstream_sha256']='f'*64
                if failure=='missing-capability':bodies['/owner-evidence/api/sporting-authority/proofs?limit=1']['relationship_available']=False
                with mock.patch.object(comparison_activate,'_health'),mock.patch.object(comparison_activate.urllib.request,'build_opener',return_value=opener):
                    with self.assertRaises((ValueError,urllib.error.HTTPError)):comparison_activate._comparison_health(values)
                self.assertEqual(requests[-1],'/owner-evidence/api/sporting-authority/review' if failure=='unavailable-review' else '/owner-evidence/api/sporting-authority/proofs?limit=1')

if __name__=='__main__':unittest.main()

class SourceReviewGuardTests(unittest.TestCase):
    def test_absent_reviewer_has_no_capability(self):
        with tempfile.TemporaryDirectory() as directory:
            state=Path(directory)
            layout=Layout(state/'app',state,state/'units',state/'env')
            self.assertIsNone(comparison_activate.capture_source_review_guard(layout))

    def test_exact_reviewer_pins_reject_runtime_database_permissions_and_grant_tampering(self):
        import provision_source_review as helper
        import provision_sporting_proof_reader as reader
        with tempfile.TemporaryDirectory() as directory:
            state=Path(directory).resolve()
            layout=Layout(state/'app',state,state/'units',state/'env')
            runtime=state/'runtime';runtime.mkdir()
            manifest=runtime/'manifest.json'
            manifest.write_text(json.dumps({'candidate':'a'*40,'files':{}}))
            sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
            config=state/'source-review/config.json';config.parent.mkdir()
            value={'database':'source','jdbc_url':'jdbc:postgresql://127.0.0.1:5432/source?user=sporting_source_review&password='+'a'*48+'&connectTimeout=5&socketTimeout=10',
                   'runtime_path':str(runtime),'runtime_manifest_sha256':sha(manifest)}
            config.write_text(json.dumps(value));config.chmod(0o640)
            proof={'runtime':{'path':str(runtime),'files':{'manifest.json':sha(manifest)},'candidate':'a'*40}}
            with mock.patch.object(comparison_activate,'capture_proof_guard',return_value=proof),\
                 mock.patch.object(reader,'verify_config',return_value={'database':'source'}),\
                 mock.patch.object(helper,'verify_grants',return_value={'exact':True}):
                pins=comparison_activate.capture_source_review_guard(layout)
                self.assertEqual(pins['config']['sha256'],sha(config))
                self.assertEqual(pins['runtime'],proof['runtime'])
                self.assertEqual(pins['source_grants'],{'exact':True})
                config.chmod(0o644)
                with self.assertRaisesRegex(ValueError,'unsafe source review'):comparison_activate.capture_source_review_guard(layout)
                config.chmod(0o640)
                with mock.patch.object(helper,'verify_grants',side_effect=ValueError('grants changed')):
                    with self.assertRaisesRegex(ValueError,'grants changed'):comparison_activate.capture_source_review_guard(layout)
                with mock.patch.object(reader,'verify_config',return_value={'database':'other'}):
                    with self.assertRaisesRegex(ValueError,'shared proof'):comparison_activate.capture_source_review_guard(layout)
                (runtime/'extra.clj').write_text('tampered')
                with self.assertRaisesRegex(ValueError,'files changed'):comparison_activate.capture_source_review_guard(layout)
                (runtime/'extra.clj').unlink()
                proof['runtime']['candidate']='b'*40
                with self.assertRaisesRegex(ValueError,'shared proof'):comparison_activate.capture_source_review_guard(layout)
