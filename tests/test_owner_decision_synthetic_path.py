"""Synthetic browser-to-signed-feed trace through the real Worker and origin."""

import hashlib
import hmac
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'tests'))
from owner_evidence_origin import make_server
from test_owner_decision_store import proposal
from test_unified_evidence_query import snapshot


class OwnerDecisionSyntheticPathTest(unittest.TestCase):
    def test_browser_action_reaches_signed_feed_and_preserves_correction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot_dir = snapshot(root)
            digest = json.loads((snapshot_dir / 'manifest.json').read_text())['snapshot_sha256']
            env = {
                'OWNER_EVIDENCE_GATEWAY_SECRET': 'synthetic-private-gateway-secret',
                'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
                'OWNER_EVIDENCE_SNAPSHOT_SHA256': digest,
                'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
                'OWNER_EVIDENCE_DECISION_DB': str(root / 'decisions.sqlite'),
                'OWNER_EVIDENCE_IMPORT_TOKEN': 'synthetic-separate-machine-import-token',
                'OWNER_EVIDENCE_IMPORT_CLIENT_ID': 'synthetic1.access',
            }
            server = make_server(snapshot_dir, env)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                evidence = server.query.browse(kind='candidate_position', limit=1)['records'][0]['record_id']
                for ident, dependencies in [('root', ()), ('child', ('root',)), ('corrected', ())]:
                    server.decisions.register(
                        digest,
                        proposal(ident, evidence=evidence, depends_on=dependencies,
                                 status='automatic_approved'),
                        idempotency_key='synthetic-register-' + ident,
                    )
                command = ['node', str(ROOT / 'tests' / 'owner_decision_synthetic_browser.mjs'),
                           str(server.server_port)]
                result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True,
                                        timeout=45)
                self.assertEqual(result.returncode, 0, result.stderr)
                trace = json.loads(result.stdout)
                self.assertEqual(trace['rejected'], {
                    'expired_access': 403,
                    'foreign_origin': 403,
                    'stale_revision': 409,
                    'browser_event_feed': 403,
                })
                self.assertEqual(trace['actions'], {'reverse': 200, 'retry': 200,
                                                    'correct': 200})
                self.assertEqual(trace['inspection']['root'], 'reversed')
                self.assertEqual(trace['inspection']['child'], 'invalidated')
                self.assertEqual(trace['inspection']['corrected'], 'human_corrected')
                self.assertEqual(trace['inspection']['correction']['action'], 'two')
                events = json.loads(trace['feed']['payload_json'])['events']
                self.assertEqual(len(events), 2)
                self.assertEqual([item['action'] for item in events], ['reverse', 'correct'])
                self.assertEqual(len({item['id'] for item in events}), 2)
                self.assertEqual(server.decisions.revision, trace['revision'])
                self.assertEqual(server.decisions.inspect('corrected')['status'], 'human_corrected')
                self.assertEqual(server.decisions.projection()['overlay_decision_counts_by_group'].get('event:one'), 1)
                self.assertEqual(trace['feed']['signature'], hmac.new(
                    env['OWNER_EVIDENCE_IMPORT_TOKEN'].encode(),
                    trace['feed']['payload_json'].encode(), hashlib.sha256).hexdigest())
            finally:
                server.shutdown()
                thread.join(timeout=2)
                server.server_close()


if __name__ == '__main__':
    unittest.main()
