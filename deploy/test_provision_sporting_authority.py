"""Synthetic host provisioning through real files and OpenSSL, no live decisions."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
sys.path.insert(0,str(Path(__file__).parent))
import provision_sporting_authority as bridge

class BridgeProvisionTests(unittest.TestCase):
    def test_host_preparation_is_idempotent_separates_signer_and_never_seeds_decisions(self):
        with tempfile.TemporaryDirectory() as tmp:
            layout=bridge.Layout(Path(tmp).resolve()/'private',Path(tmp).resolve()/'public',Path(tmp).resolve()/'units',Path(tmp).resolve())
            layout.public.mkdir(); layout.private.mkdir()
            base={}
            for name in ('public.env','owner.env'):
                path=Path(tmp).resolve()/name;path.write_text('synthetic-independent-existing-secret')
                base[path]=hashlib.sha256(path.read_bytes()).hexdigest()
            result=bridge.provision(layout,os.getuid(),os.getgid(),os.getgid(),base)
            private=json.loads((layout.private/'config.json').read_text())
            public=json.loads((layout.public/'config.json').read_text())
            self.assertEqual(private['schema'],'sporting-authority-service/v1')
            self.assertNotIn('signing_key_path',public)
            self.assertNotIn('PRIVATE KEY',json.dumps(public))
            self.assertNotEqual(public['request_secret'],'synthetic-independent-existing-secret')
            self.assertFalse(Path(private['ledger_path']).exists())
            self.assertEqual((layout.private/'ledger').stat().st_mode&0o777,0o700)
            for path in (layout.private/'signing.pem',layout.private/'request.key',layout.public/'config.json'):
                self.assertEqual(path.stat().st_mode&0o777,0o640)
            self.assertNotIn(public['request_secret'],json.dumps(result))
            before={str(p):p.read_bytes() for p in Path(tmp).resolve().rglob('*') if p.is_file()}
            self.assertEqual(bridge.provision(layout,os.getuid(),os.getgid(),os.getgid(),base),result)
            self.assertEqual(before,{str(p):p.read_bytes() for p in Path(tmp).resolve().rglob('*') if p.is_file()})
            self.assertEqual(bridge.verify(layout,os.getuid(),os.getgid(),os.getgid()),result)

    def test_relationship_attachment_initializes_empty_schema_without_reviewing_or_restoring_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()
            layout=bridge.Layout(root/'private',root/'public',root/'units',root/'env')
            for directory in (layout.private,layout.public,layout.env_dir):directory.mkdir()
            bridge.provision(layout,os.getuid(),os.getgid(),os.getgid(),{})
            authority=layout.private/'ledger/authority.sqlite';authority.write_bytes(b'current genuine authority history')
            retained={str(p):p.read_bytes() for p in (layout.private/'signing.pem',layout.private/'request.key',authority)}
            bundle=root/'bundle';scripts=bundle/'scripts';scripts.mkdir(parents=True)
            module=scripts/'private_sporting_relationships.py'
            module.write_text("import sqlite3\nclass RelationshipReviews:\n def __init__(self,path,reader):\n  self.db=sqlite3.connect(path);self.db.execute('CREATE TABLE IF NOT EXISTS relationship_events(revision INTEGER)');self.db.commit()\n def history(self):return self.db.execute('SELECT * FROM relationship_events').fetchall()\n def close(self):self.db.close()\n")
            manifest=bundle/'private-owner-manifest.json';manifest.write_text(json.dumps({'candidate':'a'*40,'files':{'scripts/private_sporting_relationships.py':bridge.digest(module)}}))
            guard={'schema':'synthetic exact authority guard'}
            bridge.attach_relationships(layout,os.getuid(),os.getgid(),os.getgid(),bundle,
                bridge.digest(manifest),bridge.digest(layout.private/'provision.json'),
                bridge.digest(layout.private/'config.json'),guard,lambda:guard)
            config=json.loads((layout.private/'config.json').read_text())
            self.assertEqual(config['relationship_ledger_path'],str(layout.private/'ledger/relationships.sqlite'))
            self.assertEqual(retained,{p:Path(p).read_bytes() for p in retained})
            before={str(p):p.read_bytes() for p in root.rglob('*') if p.is_file()}
            bridge.attach_relationships(layout,os.getuid(),os.getgid(),os.getgid(),bundle,
                bridge.digest(manifest),bridge.digest(layout.private/'provision.json'),
                bridge.digest(layout.private/'config.json'),guard,lambda:guard)
            self.assertEqual(before,{str(p):p.read_bytes() for p in root.rglob('*') if p.is_file()})

    def test_relocation_preserves_locked_environment_and_existing_signer_and_ledger(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve();root.chmod(0o755);env=root/'etc';env.mkdir(mode=0o700)
            old=bridge.Layout(root/'private',env/'sporting-authority',root/'units',env)
            old.public.mkdir();old.private.mkdir()
            pins={}
            bridge.provision(old,os.getuid(),os.getgid(),os.getgid(),pins)
            ledger=old.private/'ledger/authority.sqlite';ledger.write_bytes(b'synthetic current immutable history')
            preserved={str(p):p.read_bytes() for p in (old.private/'signing.pem',old.private/'request.key',old.private/'config.json',ledger,env/'sporting-owner.env')}
            new=bridge.Layout(old.private,root/'readable-public',old.units,env)
            receipt=bridge.digest(old.private/'provision.json');config=bridge.digest(old.public/'config.json')
            environment=bridge.digest(env/'sporting-authority.env')
            # A distinct process identity in the same group cannot cross ancestor0700.
            with self.assertRaisesRegex(ValueError,'travers'):
                bridge.service_access(old.public/'config.json',os.getuid()+1,os.getgid(),boundary=root)
            bridge.relocate_public(new,os.getuid(),os.getgid(),os.getgid(),receipt,config,environment)
            bridge.service_access(new.public/'config.json',os.getuid()+1,os.getgid(),boundary=root)
            self.assertEqual(env.stat().st_mode&0o777,0o700)
            self.assertEqual(preserved,{p:Path(p).read_bytes() for p in preserved})
            self.assertEqual(config,bridge.digest(new.public/'config.json'))
            self.assertIn(str(new.public/'config.json'),(env/'sporting-authority.env').read_text())
            # Retry under the exact installed new pins leaves all files unchanged.
            current={str(p):p.read_bytes() for p in root.rglob('*') if p.is_file()}
            bridge.relocate_public(new,os.getuid(),os.getgid(),os.getgid(),bridge.digest(new.private/'provision.json'),config,bridge.digest(env/'sporting-authority.env'))
            self.assertEqual(current,{str(p):p.read_bytes() for p in root.rglob('*') if p.is_file()})

    def test_interrupted_receipt_update_resumes_exact_moved_config_without_new_keys(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve();env=root/'etc';env.mkdir(mode=0o700)
            old=bridge.Layout(root/'private',env/'sporting-authority',root/'units',env)
            old.private.mkdir();old.public.mkdir()
            bridge.provision(old,os.getuid(),os.getgid(),os.getgid(),{})
            new=bridge.Layout(old.private,root/'public',old.units,env)
            receipt=bridge.digest(old.private/'provision.json');config=bridge.digest(old.public/'config.json')
            key=(old.private/'signing.pem').read_bytes();write=bridge.atomic_write
            def interrupted(path,*args):
                if path==old.private/'provision.json':raise OSError('synthetic crash before receipt swap')
                return write(path,*args)
            with mock.patch.object(bridge,'atomic_write',side_effect=interrupted):
                with self.assertRaises(OSError):bridge.relocate_public(new,os.getuid(),os.getgid(),os.getgid(),receipt,config,bridge.digest(env/'sporting-authority.env'))
            self.assertEqual(receipt,bridge.digest(old.private/'provision.json'))
            self.assertEqual(config,bridge.digest(new.public/'config.json'))
            bridge.relocate_public(new,os.getuid(),os.getgid(),os.getgid(),receipt,config,bridge.digest(env/'sporting-authority.env'))
            self.assertEqual(key,(old.private/'signing.pem').read_bytes())
            self.assertEqual(env.stat().st_mode&0o777,0o700)

    @unittest.skipUnless(os.geteuid()==0,'Exact alternate-uid process probe requires root; verify always runs it on host')
    def test_real_service_uid_reads_relocated_config_under_locked_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve();root.chmod(0o755);env=root/'etc';env.mkdir(mode=0o700)
            old=bridge.Layout(root/'private',env/'sporting-authority',root/'units',env)
            old.private.mkdir();old.public.mkdir()
            bridge.provision(old,0,0,65534,{})
            with self.assertRaisesRegex(ValueError,'travers'):
                bridge.service_access(old.public/'config.json',65534,65534)
            new=bridge.Layout(old.private,root/'public',old.units,env)
            bridge.relocate_public(new,0,0,65534,bridge.digest(old.private/'provision.json'),bridge.digest(old.public/'config.json'),bridge.digest(env/'sporting-authority.env'),public_uid=65534)
            bridge.service_access(new.public/'config.json',65534,65534)
            self.assertEqual(env.stat().st_mode&0o777,0o700)

    def test_drift_links_and_changed_prerequisite_refuse_without_replacing_keys(self):
        for mutation in ('config','key','link','base'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as tmp:
                layout=bridge.Layout(Path(tmp).resolve()/'private',Path(tmp).resolve()/'public',Path(tmp).resolve()/'units',Path(tmp).resolve())
                layout.public.mkdir();layout.private.mkdir()
                base=Path(tmp).resolve()/'public.env';base.write_text('synthetic')
                pins={base:hashlib.sha256(base.read_bytes()).hexdigest()}
                bridge.provision(layout,os.getuid(),os.getgid(),os.getgid(),pins)
                key=(layout.private/'signing.pem').read_bytes()
                if mutation=='config':(layout.public/'config.json').write_text('{}')
                elif mutation=='key':(layout.private/'request.key').write_text('tampered')
                elif mutation=='link':
                    (layout.public/'config.json').unlink();(layout.public/'config.json').symlink_to(base)
                else:base.write_text('changed')
                with self.assertRaises(ValueError):bridge.provision(layout,os.getuid(),os.getgid(),os.getgid(),pins)
                self.assertEqual(key,(layout.private/'signing.pem').read_bytes())
