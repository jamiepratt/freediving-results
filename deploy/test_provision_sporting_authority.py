"""Synthetic host provisioning through real files and OpenSSL, no live decisions."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).parent))
import provision_sporting_authority as bridge

class BridgeProvisionTests(unittest.TestCase):
    def test_host_preparation_is_idempotent_separates_signer_and_never_seeds_decisions(self):
        with tempfile.TemporaryDirectory() as tmp:
            layout=bridge.Layout(Path(tmp).resolve()/'private',Path(tmp).resolve()/'public',Path(tmp).resolve()/'units')
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

    def test_drift_links_and_changed_prerequisite_refuse_without_replacing_keys(self):
        for mutation in ('config','key','link','base'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as tmp:
                layout=bridge.Layout(Path(tmp).resolve()/'private',Path(tmp).resolve()/'public',Path(tmp).resolve()/'units')
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
