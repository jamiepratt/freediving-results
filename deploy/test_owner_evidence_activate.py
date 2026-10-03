"""Private origin activation tests at the staging and service boundary."""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parent))
from owner_evidence_activate import Layout, activate, main, _health


class ActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.bundle = root / 'bundle'
        (self.bundle / 'scripts').mkdir(parents=True)
        (self.bundle / 'resources').mkdir()
        for name in ('owner_evidence_origin.py', 'unified_evidence_query.py', 'route_roster_query.py',
                     'owner_source_view.py', 'owner_decision_store.py', 'vestico_safe_derivative.py'):
            (self.bundle / 'scripts' / name).write_text('print("test")\n')
        (self.bundle / 'scripts/private_source_bundle.py').write_bytes(
            (Path(__file__).resolve().parents[1] / 'scripts/private_source_bundle.py').read_bytes())
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

    def candidate_with_source_bundle(self):
        data = b'candidate snapshot bytes'
        digest = hashlib.sha256(data).hexdigest()
        (self.source / 'snapshot.sqlite').write_bytes(data)
        (self.source / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': digest}))
        private = Path(self.temp.name) / 'candidate-source-bundle'
        (private / 'objects').mkdir(parents=True, mode=0o700)
        private.chmod(0o700)
        (private / 'objects' / digest).write_bytes(data)
        (private / 'objects' / digest).chmod(0o600)
        manifest = private / 'manifest.json'
        manifest.write_text(json.dumps({'schema': 'private-source-bundle/v1', 'sources': [{
            'id': 'sha256:' + digest, 'status': 'included', 'sha256': digest,
            'bytes': len(data), 'content_type': 'application/vnd.sqlite3',
            'object': 'objects/' + digest}]}))
        manifest.chmod(0o600)
        return digest, private, hashlib.sha256(manifest.read_bytes()).hexdigest()

    def activate_candidate(self, digest, private, manifest_digest, **kwargs):
        return activate(self.bundle, self.source, digest, self.layout,
                        source_bundle=private, expected_source_manifest_sha256=manifest_digest,
                        update_config_pin=True, command=kwargs.get('command', self.command),
                        health=kwargs.get('health', lambda: None),
                        owner_uid=os.getuid(), owner_gid=os.getgid())

    def test_candidate_pin_changes_only_after_private_inputs_match_and_converges(self):
        self.run_activation()
        old_config = self.config.read_bytes()
        old_snapshot = (self.layout.state / 'current').resolve()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        (private / 'objects' / digest).write_bytes(b'tampered')
        with self.assertRaises(subprocess.CalledProcessError):
            self.activate_candidate(digest, private, manifest_digest)
        self.assertEqual(self.config.read_bytes(), old_config)
        self.assertEqual((self.layout.state / 'current').resolve(), old_snapshot)
        (private / 'objects' / digest).write_bytes(b'candidate snapshot bytes')
        self.assertEqual(self.activate_candidate(digest, private, manifest_digest), 'activated')
        self.assertIn(digest.encode(), self.config.read_bytes())
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.layout.state / 'current').resolve(),
                         (self.layout.state / 'snapshots' / digest).resolve())
        self.assertEqual(self.activate_candidate(digest, private, manifest_digest), 'unchanged')

    def test_candidate_pin_preserves_secret_containing_prior_pin_assignment(self):
        secret = 'prefixOWNER_EVIDENCE_SNAPSHOT_SHA256=' + self.digest + 'suffix'
        self.config.write_text(self.config.read_text().replace(
            'some-private-gateway-secret', secret))
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        self.activate_candidate(digest, private, manifest_digest)
        self.assertIn('OWNER_EVIDENCE_GATEWAY_SECRET=' + secret + '\n', self.config.read_text())
        self.assertIn('OWNER_EVIDENCE_SNAPSHOT_SHA256=' + digest + '\n', self.config.read_text())

    def test_failed_candidate_restores_inactive_disabled_service(self):
        self.run_activation()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        calls = []
        def inactive_service(*args):
            calls.append(args)
            if args[:2] == ('systemctl', 'is-active') or args[:2] == ('systemctl', 'is-enabled'):
                return False
            return True
        with self.assertRaises(RuntimeError):
            self.activate_candidate(digest, private, manifest_digest,
                                    command=inactive_service,
                                    health=lambda: (_ for _ in ()).throw(RuntimeError('unhealthy')))
        self.assertIn(('systemctl', 'stop', 'freediving-owner-evidence.service'), calls)
        self.assertIn(('systemctl', 'disable', 'freediving-owner-evidence.service'), calls)
        self.assertNotIn(('systemctl', 'restart', 'freediving-owner-evidence.service'), calls[-3:])

    def test_interrupted_candidate_restores_inactive_disabled_state_on_retry(self):
        self.run_activation()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        def interrupt(*args):
            if args[:2] in (('systemctl', 'is-active'), ('systemctl', 'is-enabled')):
                return False
            if args == ('systemctl', 'restart', 'freediving-owner-evidence.service'):
                raise KeyboardInterrupt()
            return True
        with self.assertRaises(KeyboardInterrupt):
            self.activate_candidate(digest, private, manifest_digest, command=interrupt)
        calls = []
        def retry_command(*args):
            calls.append(args)
            if args[:2] in (('systemctl', 'is-active'), ('systemctl', 'is-enabled')):
                return False
            return True
        self.assertEqual(self.activate_candidate(digest, private, manifest_digest,
                                                 command=retry_command), 'activated')
        stop = calls.index(('systemctl', 'stop', 'freediving-owner-evidence.service'))
        disable = calls.index(('systemctl', 'disable', 'freediving-owner-evidence.service'))
        restart = calls.index(('systemctl', 'restart', 'freediving-owner-evidence.service'))
        self.assertLess(stop, restart)
        self.assertLess(disable, restart)

    def test_cli_recovers_pending_activation_before_rejecting_missing_candidate(self):
        self.run_activation()
        old_config = self.config.read_bytes()
        old_snapshot = (self.layout.state / 'current').resolve()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        def interrupt(*args):
            if args == ('systemctl', 'restart', 'freediving-owner-evidence.service'):
                raise KeyboardInterrupt()
            return True
        with self.assertRaises(KeyboardInterrupt):
            self.activate_candidate(digest, private, manifest_digest, command=interrupt)
        (private / 'objects' / digest).unlink()
        argv = ['owner_evidence_activate.py', '--bundle-dir', str(self.bundle),
                '--snapshot-source', str(self.source), '--expected-sha256', digest,
                '--source-bundle', str(private),
                '--expected-source-manifest-sha256', manifest_digest]
        real_run = subprocess.run
        commands = []
        def local_run(args, **kwargs):
            if args[0] == 'systemctl':
                commands.append(tuple(args))
                return SimpleNamespace(returncode=0)
            return real_run(args, **kwargs)
        identity = SimpleNamespace(pw_uid=os.getuid(), pw_gid=os.getgid(),
                                   pw_dir='/nonexistent', pw_shell='/usr/sbin/nologin')
        actual_euid = os.geteuid()
        euid_calls = 0
        def cli_euid():
            nonlocal euid_calls
            euid_calls += 1
            return 0 if euid_calls == 1 else actual_euid
        with (mock.patch('owner_evidence_activate.Layout', return_value=self.layout),
              mock.patch('owner_evidence_activate.os.geteuid', side_effect=cli_euid),
              mock.patch('owner_evidence_activate.pwd.getpwnam', return_value=identity),
              mock.patch('owner_evidence_activate.subprocess.run', side_effect=local_run),
              mock.patch.object(sys, 'argv', argv)):
            with self.assertRaises(SystemExit):
                main()
        self.assertEqual(self.config.read_bytes(), old_config)
        self.assertEqual((self.layout.state / 'current').resolve(), old_snapshot)
        self.assertIn(('systemctl', 'restart', 'freediving-owner-evidence.service'), commands)
        checkpoint = self.layout.state / 'activation-checkpoint' / 'status.json'
        self.assertEqual(json.loads(checkpoint.read_text())['status'], 'failed')

    def test_candidate_health_failure_restores_config_and_prior_activation(self):
        self.run_activation()
        old_config = self.config.read_bytes()
        old_env = (self.layout.state / 'active.env').read_bytes()
        old_snapshot = (self.layout.state / 'current').resolve()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        with self.assertRaises(RuntimeError):
            self.activate_candidate(digest, private, manifest_digest,
                                    health=lambda: (_ for _ in ()).throw(RuntimeError('unhealthy')))
        self.assertEqual(self.config.read_bytes(), old_config)
        self.assertEqual((self.layout.state / 'active.env').read_bytes(), old_env)
        self.assertEqual((self.layout.state / 'current').resolve(), old_snapshot)
        self.assertEqual(json.loads((self.layout.state / 'activation-checkpoint' / 'status.json').read_text())['status'], 'failed')

    def test_interrupted_candidate_recovers_before_same_candidate_retry(self):
        self.run_activation()
        old_snapshot = (self.layout.state / 'current').resolve()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        def interrupt(*args):
            if args == ('systemctl', 'restart', 'freediving-owner-evidence.service'):
                raise KeyboardInterrupt()
            return True
        with self.assertRaises(KeyboardInterrupt):
            self.activate_candidate(digest, private, manifest_digest, command=interrupt)
        checkpoint = self.layout.state / 'activation-checkpoint' / 'status.json'
        self.assertEqual(json.loads(checkpoint.read_text())['status'], 'pending')
        self.assertEqual(self.activate_candidate(digest, private, manifest_digest), 'activated')
        self.assertEqual(json.loads(checkpoint.read_text())['status'], 'active')
        self.assertTrue(old_snapshot.exists())

    def test_missing_prior_snapshot_on_retry_stops_service_and_keeps_pending(self):
        self.run_activation()
        old_snapshot = (self.layout.state / 'current').resolve()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        def interrupt(*args):
            if args == ('systemctl', 'restart', 'freediving-owner-evidence.service'):
                raise KeyboardInterrupt()
            return True
        with self.assertRaises(KeyboardInterrupt):
            self.activate_candidate(digest, private, manifest_digest, command=interrupt)
        shutil.rmtree(old_snapshot)
        self.calls.clear()
        with self.assertRaises(RuntimeError):
            self.activate_candidate(digest, private, manifest_digest)
        self.assertIn(('systemctl', 'stop', 'freediving-owner-evidence.service'), self.calls)
        checkpoint = self.layout.state / 'activation-checkpoint' / 'status.json'
        self.assertEqual(json.loads(checkpoint.read_text())['status'], 'pending')

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

    def test_decision_state_is_writable_and_survives_snapshot_activation(self):
        self.config.write_text(self.config.read_text() + 'OWNER_EVIDENCE_DECISION_API_ENABLED=1\n')
        self.run_activation()
        decision_dir = self.layout.state / 'decisions'
        self.assertEqual(decision_dir.stat().st_mode & 0o777, 0o700)
        ledger = decision_dir / 'ledger.sqlite'
        ledger.write_bytes(b'owner review history')
        self.assertIn(f'OWNER_EVIDENCE_DECISION_DB={ledger}',
                      (self.layout.state / 'active.env').read_text())
        self.assertTrue((self.layout.app / 'current' / 'scripts/owner_decision_store.py').exists())
        unit = (self.layout.units / 'freediving-owner-evidence.service').read_text()
        self.assertIn('ReadWritePaths=/var/lib/freediving-owner-evidence/decisions', unit)
        new_data = b'another snapshot'
        new_digest = hashlib.sha256(new_data).hexdigest()
        (self.source / 'snapshot.sqlite').write_bytes(new_data)
        (self.source / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': new_digest}))
        self.config.write_text(self.config.read_text().replace(self.digest, new_digest))
        activate(self.bundle, self.source, new_digest, self.layout,
                 command=self.command, health=lambda: None,
                 owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual(ledger.read_bytes(), b'owner review history')

    def test_normal_activation_does_not_expose_parallel_decision_authority(self):
        self.run_activation()
        self.assertNotIn('OWNER_EVIDENCE_DECISION_DB=',
                         (self.layout.state / 'active.env').read_text())

    def test_failed_activation_preserves_review_history(self):
        self.config.write_text(self.config.read_text() + 'OWNER_EVIDENCE_DECISION_API_ENABLED=1\n')
        self.run_activation()
        ledger = self.layout.state / 'decisions' / 'ledger.sqlite'
        ledger.write_bytes(b'approved owner correction')
        prior_env = (self.layout.state / 'active.env').read_bytes()
        new_data = b'failed replacement snapshot'
        new_digest = hashlib.sha256(new_data).hexdigest()
        (self.source / 'snapshot.sqlite').write_bytes(new_data)
        (self.source / 'manifest.json').write_text(json.dumps({
            'schema': 'unified-evidence-snapshot/v1', 'snapshot_sha256': new_digest}))
        self.config.write_text(self.config.read_text().replace(self.digest, new_digest))
        def fail_once(*args):
            if args == ('systemctl', 'restart', 'freediving-owner-evidence.service'):
                raise RuntimeError('restart failed')
            return True
        with self.assertRaises(RuntimeError):
            activate(self.bundle, self.source, new_digest, self.layout,
                     command=fail_once, health=lambda: None,
                     owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual(ledger.read_bytes(), b'approved owner correction')
        self.assertEqual((self.layout.state / 'active.env').read_bytes(), prior_env)

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

    def test_stages_verified_source_bundle_for_original_inspection(self):
        private = Path(self.temp.name) / 'source-bundle'
        private.mkdir(mode=0o700)
        objects = private / 'objects'
        objects.mkdir(mode=0o700)
        (objects / self.digest).write_bytes(b'private snapshot bytes')
        (objects / self.digest).chmod(0o600)
        manifest = private / 'manifest.json'
        manifest.write_text(json.dumps({'schema': 'private-source-bundle/v1', 'sources': [{
            'id': 'sha256:' + self.digest, 'status': 'included', 'sha256': self.digest,
            'bytes': len(b'private snapshot bytes'), 'content_type': 'application/vnd.sqlite3',
            'object': 'objects/' + self.digest}]}))
        manifest.chmod(0o600)
        manifest_digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        activate(self.bundle, self.source, self.digest, self.layout,
                 source_bundle=private, expected_source_manifest_sha256=manifest_digest,
                 command=self.command, health=lambda: None,
                 owner_uid=os.getuid(), owner_gid=os.getgid())
        staged = self.layout.state / 'sources' / manifest_digest
        self.assertEqual((staged / 'objects' / self.digest).read_bytes(), b'private snapshot bytes')
        self.assertEqual((staged / 'objects' / self.digest).stat().st_mode & 0o777, 0o600)
        self.assertIn(f'OWNER_EVIDENCE_SOURCE_BUNDLE_DIR={staged}',
                      (self.layout.state / 'active.env').read_text())
        (private / 'objects' / self.digest).write_bytes(b'tampered')
        with self.assertRaises(subprocess.CalledProcessError):
            activate(self.bundle, self.source, self.digest, self.layout,
                     source_bundle=private, expected_source_manifest_sha256=manifest_digest,
                     command=self.command, health=lambda: None,
                     owner_uid=os.getuid(), owner_gid=os.getgid())

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
