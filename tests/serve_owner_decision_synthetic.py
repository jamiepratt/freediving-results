"""Loopback-only synthetic owner workspace for internal browser inspection.

Run `python3 tests/serve_owner_decision_synthetic.py` and open the printed URL.
The proxy injects synthetic origin headers. It is not an Access emulator.
"""

import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'tests'))
from owner_evidence_origin import make_server
from test_owner_decision_store import proposal
from test_unified_evidence_query import snapshot


def main():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        snapshot_dir = snapshot(root)
        digest = json.loads((snapshot_dir / 'manifest.json').read_text())['snapshot_sha256']
        env = {'OWNER_EVIDENCE_GATEWAY_SECRET': 'synthetic-private-gateway-secret',
               'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
               'OWNER_EVIDENCE_SNAPSHOT_SHA256': digest,
               'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
               'OWNER_EVIDENCE_DECISION_DB': str(root / 'decisions.sqlite')}
        origin = make_server(snapshot_dir, env)
        origin_thread = threading.Thread(target=origin.serve_forever, daemon=True)
        origin_thread.start()
        evidence = origin.query.browse(kind='candidate_position', limit=1)['records'][0]['record_id']
        for ident, dependencies in [('root', ()), ('child', ('root',)), ('corrected', ())]:
            origin.decisions.register(digest, proposal(ident, evidence=evidence,
                                                       depends_on=dependencies,
                                                       status='automatic_approved'),
                                      idempotency_key='synthetic-register-' + ident)

        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.forward()

            def do_POST(self):
                self.forward()

            def forward(self):
                if self.path == '/':
                    self.send_response(302)
                    self.send_header('Location', '/owner-evidence')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                if not self.path.startswith('/owner-evidence'):
                    self.send_error(404)
                    return
                length = int(self.headers.get('Content-Length', '0'))
                body = self.rfile.read(length) if length else None
                headers = {'Host': 'owner-private.alphacompose.com',
                           'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                           'X-Freediving-Owner-Email': 'owner@example.com'}
                if self.command == 'POST':
                    headers.update({'Origin': 'https://poc.alphacompose.com',
                                    'Content-Type': self.headers.get('Content-Type', ''),
                                    'X-Freediving-CSRF': self.headers.get('X-Freediving-CSRF', ''),
                                    'Content-Length': str(length)})
                connection = http.client.HTTPConnection('127.0.0.1', origin.server_port, timeout=5)
                connection.request(self.command, self.path, body=body, headers=headers)
                response = connection.getresponse()
                payload = response.read()
                self.send_response(response.status)
                for name, value in response.getheaders():
                    if name.lower() not in {'server', 'date', 'connection', 'content-length'}:
                        self.send_header(name, value)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                connection.close()

        proxy = ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
        print(f'http://127.0.0.1:{proxy.server_port}/owner-evidence', flush=True)
        try:
            proxy.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            proxy.server_close()
            origin.shutdown()
            origin_thread.join(timeout=2)
            origin.server_close()


if __name__ == '__main__':
    main()
