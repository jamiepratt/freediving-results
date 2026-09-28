"""Private origin activation tests at the staging and service boundary."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parent))
from owner_evidence_activate import Layout, activate, _health


class ActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.bundle = root / 'bundle'
        (self.bundle / 'scripts').mkdir(parents=True)
        (self.bundle / 'resources').mkdir()
        for name in ('owner_evidence_origin.py', 'unified_evidence_query.py', 'route_roster_query.py'):
            (self.bundle / 'scripts' / name).write_text('print("test")\n')
        for name in ('evidence_workspace.html', 'evidence_workspace.js', 'evidence_workspace.css'):
            (self.bundle / 'resources' / name).write_text('test')
        self.source = root / 'source'
        self.source.mkdir()
        data = b'private snapshot bytes'
        self.digest = hashlib.sha256(data).hexdigest()
        (self.source / 'snapshot.sqlite').write_bytes(data)
        (self.source / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': self.digest}))
        self.config = root / 'etc' / 'owner-evidence.env'
        self.config.parent.mkdir()
        self.config.write_text('OWNER_EVIDENCE_GATEWAY_SECRET=some-private-gateway-secret\n'
                               'OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com\n'
                               'OWNER_EVIDENCE_EMAILS=owner@example.com\n'
                               f'OWNER_EVIDENCE_SNAPSHOT_SHA256={self.digest}\n')
        self.config.chmod(0o600)
        self.layout = Layout(root / 'opt', root / 'var', root / 'units', self.config)
        self.calls = []
        self.roster = root / 'roster'
        self.roster.mkdir()
        roster_bytes = json.dumps({'schema': 'issue55-route-roster/v1', 'routes': [], 'leads': []}).encode()
        self.roster_digest = hashlib.sha256(roster_bytes).hexdigest()
        (self.roster / 'roster.json').write_bytes(roster_bytes)
        (self.roster / 'manifest.json').write_text(json.dumps({
            'schema': 'issue55-route-receipts/v1', 'roster_sha256': self.roster_digest,
            'snapshot_sqlite_sha256': self.digest}))

    def command(self, *args):
        self.calls.append(args)
        if args == ('systemctl', 'is-active', '--quiet', 'freediving-owner-evidence.service'):
            return True
        return True

    def run_activation(self):
        return activate(self.bundle, self.source, self.digest, self.layout,
                        command=self.command, health=lambda: None,
                        owner_uid=os.getuid(), owner_gid=os.getgid())

    def test_stages_private_snapshot_and_idempotently_starts_dedicated_service(self):
        self.run_activation()
        private = self.layout.state / 'snapshots' / self.digest
        self.assertEqual((private / 'snapshot.sqlite').read_bytes(), b'private snapshot bytes')
        self.assertEqual(private.stat().st_mode & 0o777, 0o700)
        self.assertEqual((private / 'snapshot.sqlite').stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.layout.state / 'current').resolve(), private.resolve())
        self.assertIn(('systemctl', 'restart', 'freediving-owner-evidence.service'), self.calls)
        self.calls.clear()
        self.run_activation()
        self.assertNotIn(('systemctl', 'restart', 'freediving-owner-evidence.service'), self.calls)

    def test_stages_pinned_roster_with_snapshot_binding(self):
        activate(self.bundle, self.source, self.digest, self.layout,
                 roster_source=self.roster, expected_roster_sha256=self.roster_digest,
                 command=self.command, health=lambda: None,
                 owner_uid=os.getuid(), owner_gid=os.getgid())
        staged = self.layout.state / 'rosters' / self.roster_digest
        self.assertEqual((staged / 'roster.json').read_bytes(), (self.roster / 'roster.json').read_bytes())
        self.assertEqual(staged.stat().st_mode & 0o777, 0o700)
        self.assertEqual((staged / 'roster.json').stat().st_mode & 0o777, 0o600)
        active_env = (self.layout.state / 'active.env').read_text()
        self.assertIn('OWNER_EVIDENCE_ROSTER_DIR=' + str(staged), active_env)
        self.assertIn('OWNER_EVIDENCE_ROSTER_SHA256=' + self.roster_digest, active_env)
        (self.roster / 'roster.json').write_bytes(b'tampered')
        self.calls.clear()
        with self.assertRaises(ValueError):
            activate(self.bundle, self.source, self.digest, self.layout,
                     roster_source=self.roster, expected_roster_sha256=self.roster_digest,
                     command=self.command, health=lambda: None,
                     owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual(self.calls, [])

    def test_missing_or_tampered_prerequisite_refuses_without_service_change(self):
        self.config.unlink()
        with self.assertRaises(ValueError):
            self.run_activation()
        self.assertEqual(self.calls, [])
        self.assertFalse(self.layout.state.exists())
        self.config.write_text('OWNER_EVIDENCE_SNAPSHOT_SHA256=' + self.digest + '\n')
        self.config.chmod(0o600)
        (self.source / 'snapshot.sqlite').write_bytes(b'tampered')
        with self.assertRaises(ValueError):
            self.run_activation()
        self.assertEqual(self.calls, [])

    def test_failed_service_restart_restores_previous_snapshot_and_unit(self):
        self.run_activation()
        previous = (self.layout.state / 'current').resolve()
        previous_unit = (self.layout.units / 'freediving-owner-evidence.service').read_bytes()
        new_data = b'new snapshot'
        new_digest = hashlib.sha256(new_data).hexdigest()
        (self.source / 'snapshot.sqlite').write_bytes(new_data)
        (self.source / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': new_digest}))
        self.config.write_text(self.config.read_text().replace(self.digest, new_digest))
        self.calls.clear()
        def fail_restart(*args):
            self.calls.append(args)
            if args == ('systemctl', 'restart', 'freediving-owner-evidence.service') and self.calls.count(args) == 1:
                raise RuntimeError('service failed')
            return True
        with self.assertRaises(RuntimeError):
            activate(self.bundle, self.source, new_digest, self.layout,
                     command=fail_restart, health=lambda: None,
                     owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual((self.layout.state / 'current').resolve(), previous)
        self.assertEqual((self.layout.units / 'freediving-owner-evidence.service').read_bytes(), previous_unit)
        self.assertIn(self.digest.encode(), (self.layout.state / 'active.env').read_bytes())

    def test_health_failure_removes_first_activation(self):
        with self.assertRaises(RuntimeError):
            activate(self.bundle, self.source, self.digest, self.layout,
                     command=self.command, health=lambda: (_ for _ in ()).throw(RuntimeError('unhealthy')),
                     owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertFalse((self.layout.state / 'current').exists())
        self.assertFalse((self.layout.app / 'current').exists())
        self.assertFalse((self.layout.units / 'freediving-owner-evidence.service').exists())
        self.assertIn(('systemctl', 'stop', 'freediving-owner-evidence.service'), self.calls)

    def test_staged_snapshot_tamper_blocks_reactivation(self):
        self.run_activation()
        (self.layout.state / 'snapshots' / self.digest / 'snapshot.sqlite').write_bytes(b'tampered')
        self.calls.clear()
        with self.assertRaises(ValueError):
            self.run_activation()
        self.assertEqual(self.calls, [])

    def test_local_health_requires_three_denials_after_valid_overview(self):
        values = {'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-origin.alphacompose.com',
                  'OWNER_EVIDENCE_GATEWAY_SECRET': 'some-private-gateway-secret',
                  'OWNER_EVIDENCE_EMAILS': 'owner@example.com'}
        class Response(io.BytesIO):
            status = 200
        def deny_negative(request, timeout):
            headers = {key.lower(): value for key, value in request.header_items()}
            if (headers['host'] == values['OWNER_EVIDENCE_ORIGIN_HOST'] and
                    headers['x-freediving-owner-gateway'] == values['OWNER_EVIDENCE_GATEWAY_SECRET'] and
                    headers['x-freediving-owner-email'] == values['OWNER_EVIDENCE_EMAILS']):
                return Response(json.dumps({'snapshot_sha256': self.digest}).encode())
            raise urllib.error.HTTPError(request.full_url, 403, 'denied', {}, None)
        with mock.patch('owner_evidence_activate.urllib.request.urlopen', side_effect=deny_negative):
            _health(values, self.digest)
        with mock.patch('owner_evidence_activate.urllib.request.urlopen',
                        side_effect=lambda *_args, **_kwargs: Response(json.dumps({'snapshot_sha256': self.digest}).encode())):
            with self.assertRaises(RuntimeError):
                _health(values, self.digest)

    def test_local_health_checks_pinned_route_roster(self):
        values = {'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-origin.alphacompose.com',
                  'OWNER_EVIDENCE_GATEWAY_SECRET': 'some-private-gateway-secret',
                  'OWNER_EVIDENCE_EMAILS': 'owner@example.com'}
        class Response(io.BytesIO):
            status = 200
        def response(request, timeout):
            headers = {key.lower(): value for key, value in request.header_items()}
            if headers['host'] != values['OWNER_EVIDENCE_ORIGIN_HOST'] or headers['x-freediving-owner-gateway'] != values['OWNER_EVIDENCE_GATEWAY_SECRET'] or headers['x-freediving-owner-email'] != values['OWNER_EVIDENCE_EMAILS']:
                raise urllib.error.HTTPError(request.full_url, 403, 'denied', {}, None)
            if request.full_url.endswith('/routes'):
                return Response(json.dumps({'roster_sha256': self.roster_digest}).encode())
            return Response(json.dumps({'snapshot_sha256': self.digest}).encode())
        with mock.patch('owner_evidence_activate.urllib.request.urlopen', side_effect=response):
            _health(values, self.digest, self.roster_digest)
        with mock.patch('owner_evidence_activate.urllib.request.urlopen', side_effect=lambda *_a, **_k: Response(b'{}')):
            with self.assertRaises(RuntimeError):
                _health(values, self.digest, self.roster_digest)


if __name__ == '__main__':
    unittest.main()
