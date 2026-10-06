import hashlib
import http.client
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from sporting_authority_fixture import Fixture, HOST, OWNER, GATE
from test_retained_aida_diff import fixture
from retained_aida_diff import compare, canonical

PATH = '/owner-evidence/sporting/aida-diff'


class RetainedAidaDiffHTTPTest(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()
        self.addCleanup(self.f.close)
        source, artifacts, shas = fixture()
        self.report = compare(source,artifacts,shas,expected_positions=1,expected_selected=1)
        self.report['input_pins'] = {'hashes_sha256':'d'*64}
        self.path = self.f.root/'report.json'
        self.path.write_bytes(canonical(self.report)); self.path.chmod(0o600)
        self.config = self.f.root/'diff-config.json'
        self.data = {'schema':'retained-aida-diff-service/v1',
                     'report':{'path':str(self.path),'sha256':hashlib.sha256(self.path.read_bytes()).hexdigest()},
                     'source_sha256':self.report['source']['sha256'], 'artifact_sha256s':shas,
                     'hashes_sha256':'d'*64,'selected_positions':1,'source_positions':1}
        self.write_config()
        self.f.server.aida_diff_config = self.config

    def write_config(self):
        self.config.write_bytes(canonical(self.data)); self.config.chmod(0o600)

    def get(self, path=PATH, headers=None):
        c = http.client.HTTPConnection('127.0.0.1',self.f.server.server_port,timeout=5)
        c.request('GET',path,headers=headers or {'Host':HOST,'X-Freediving-Owner-Gateway':GATE,'X-Freediving-Owner-Email':OWNER})
        r = c.getresponse(); result=(r.status,r.read(),dict(r.getheaders())); c.close(); return result

    def test_authenticated_owner_reads_complete_safe_report_no_authority_writes(self):
        before=self.f.review()['revision']
        status,body,headers=self.get()
        self.assertEqual(200,status)
        self.assertIn(b'complete retained comparison',body)
        self.assertIn(b'&lt;script&gt;Synthetic&lt;/script&gt;',body)
        self.assertNotIn(b'<script>',body)
        self.assertEqual('no-store',headers['Cache-Control'])
        self.assertEqual(before,self.f.review()['revision'])
        self.assertEqual(403,self.get(headers={'Host':HOST,'X-Freediving-Owner-Gateway':GATE})[0])

    def test_pin_tamper_source_version_and_query_refuse(self):
        self.assertEqual(400,self.get(PATH+'?source=other')[0])
        self.path.write_bytes(self.path.read_bytes()+b' ')
        self.assertEqual(503,self.get()[0])
        self.path.write_bytes(canonical(self.report))
        for key in ('source_sha256','artifact_sha256s','hashes_sha256'):
            old=self.data[key]; self.data[key]=['0'*64,'b'*64] if isinstance(old,list) else '0'*64
            self.write_config(); self.assertEqual(503,self.get()[0]); self.data[key]=old
        self.write_config()
        self.assertEqual(200,self.get()[0])
