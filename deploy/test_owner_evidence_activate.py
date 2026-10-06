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
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from owner_evidence_activate import Layout, activate, main, _health, _config
from private_presentation_status import PrivatePresentationStatus


class ActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.bundle = root / 'bundle'
        (self.bundle / 'scripts').mkdir(parents=True)
        (self.bundle / 'resources').mkdir()
        for name in ('owner_evidence_origin.py', 'private_presentation_status.py', 'private_canonical_status.py', 'private_attempt_inspector.py', 'unified_evidence_query.py', 'route_roster_query.py',
                     'aida_snapshot_observations.py', 'cmas_microplus_snapshot_observations.py',
                     'issue55_aida_selected_html.py', 'cmas_microplus_ingest.py',
                     'cmas_microplus_finalize.py',
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

    def test_private_status_settings_survive_activation_config_validation(self):
        text = self.config.read_text()
        text += ('OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n'
                 'OWNER_EVIDENCE_STATUS_TOKEN=separate-random-status-token-123456\n'
                 'OWNER_EVIDENCE_STATUS_CLIENT_ID=status123.access\n')
        self.config.write_text(text)
        assert _config(self.config, os.getuid(), self.digest)['OWNER_EVIDENCE_STATUS_CLIENT_ID'] == 'status123.access'
        self.config.write_text(text.replace('OWNER_EVIDENCE_STATUS_TOKEN=separate-random-status-token-123456\n', ''))
        with self.assertRaises(ValueError):
            _config(self.config, os.getuid(), self.digest)

    def test_canonical_reader_config_is_scoped_to_the_selected_private_layout(self):
        path = self.layout.state / 'canonical-reader' / 'config.json'
        self.config.write_text(self.config.read_text() +
                               'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG=' + str(path) + '\n')
        self.assertEqual(_config(self.config, os.getuid(), self.digest,
                                canonical_config_path=path)['OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG'], str(path))
        with self.assertRaises(ValueError):
            _config(self.config, os.getuid(), self.digest)

    def test_reader_permission_failure_uses_service_identity_before_activation(self):
        reader_config = self.layout.state / 'canonical-reader' / 'config.json'
        reader_config.parent.mkdir(parents=True)
        runtime = Path(self.temp.name) / 'runtime'
        runtime.mkdir()
        manifest = runtime / 'manifest.json'
        manifest.write_text(json.dumps({'candidate': 'a' * 40}))
        reader_config.write_text(json.dumps({'runtime_path': str(runtime),
                                            'runtime_manifest_sha256': hashlib.sha256(manifest.read_bytes()).hexdigest()}))
        reader_config.chmod(0o640)
        (self.bundle / 'private-owner-manifest.json').write_text(json.dumps({'candidate': 'a' * 40}))
        self.config.write_text(self.config.read_text() +
                               'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG=' + str(reader_config) + '\n')
        uid, gid = os.getuid() + 1000, os.getgid() + 1000
        def reader(command, **kwargs):
            self.assertEqual(kwargs.get('user'), uid)
            self.assertEqual(kwargs.get('group'), gid)
            self.assertEqual(kwargs.get('extra_groups'), [])
            self.assertEqual(kwargs.get('env'), {'PATH': '/usr/bin:/bin'})
            return SimpleNamespace(returncode=1, stdout=b'', stderr=b'secret diagnostics')
        prior = self.config.read_bytes()
        with mock.patch('owner_evidence_activate.subprocess.run', side_effect=reader):
            with self.assertRaisesRegex(ValueError, '^canonical status read capability unavailable$'):
                activate(self.bundle, self.source, self.digest, self.layout,
                         command=self.command, health=lambda: None, owner_uid=uid, owner_gid=gid)
        self.assertEqual(self.config.read_bytes(), prior)
        self.assertEqual(self.calls, [])
        self.assertFalse((self.layout.app / 'current').exists())
        self.assertFalse((self.layout.state / 'active.env').exists())

    def test_consolidated_queue_pin_requires_fixed_private_host_path_and_digest(self):
        base = self.config.read_text()
        settings = ('OWNER_EVIDENCE_ISSUE172_QUEUE_FILE=/var/lib/freediving-owner-evidence/issue172-queue/owner-queue-v1.json\n'
                    'OWNER_EVIDENCE_ISSUE172_QUEUE_SHA256=' + 'a' * 64 + '\n')
        self.config.write_text(base + settings)
        self.assertEqual(_config(self.config, os.getuid(), self.digest)['OWNER_EVIDENCE_ISSUE172_QUEUE_SHA256'], 'a' * 64)
        for invalid in (settings.replace('a' * 64, 'invalid'),
                        settings.replace('/var/lib/freediving-owner-evidence/issue172-queue/owner-queue-v1.json', '/tmp/queue.json'),
                        settings.splitlines()[0] + '\n'):
            self.config.write_text(base + invalid)
            with self.assertRaises(ValueError):
                _config(self.config, os.getuid(), self.digest)

    def test_import_and_status_credentials_survive_private_activation(self):
        settings = ('OWNER_EVIDENCE_DECISION_API_ENABLED=1\n'
                    'OWNER_EVIDENCE_IMPORT_TOKEN=separate-random-import-token-123456\n'
                    'OWNER_EVIDENCE_IMPORT_CLIENT_ID=import123.access\n'
                    'OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n'
                    'OWNER_EVIDENCE_STATUS_TOKEN=separate-random-status-token-123456\n'
                    'OWNER_EVIDENCE_STATUS_CLIENT_ID=status123.access\n')
        self.config.write_text(self.config.read_text() + settings)
        self.run_activation()
        active_env = (self.layout.state / 'active.env').read_text()
        self.assertIn(settings, active_env)
        self.assertIn('OWNER_EVIDENCE_DECISION_DB=', active_env)
        status_file = self.layout.state / 'status' / 'presentation-status.json'
        status = {'schema': 'private-presentation-status/v3', 'run_id': 'run-1',
                  'revision': 1,
                  'local': {'snapshot_sha256': self.digest,
                            'cutoff': '2026-10-03T00:00:00Z', 'gap_count': 0},
                  'remote': {'status': 'active', 'pending': None, 'failed': None,
                             'active': {'snapshot_sha256': self.digest,
                                        'bundle_manifest_sha256': 'b' * 64}},
                  'application': {'snapshot_sha256': self.digest,
                                  'canonical_revision': 211,
                                  'canonical_readback_sha256': 'c' * 64,
                                  'owner_store_revision': 1,
                                  'pending_proposals': 207,
                                  'unresolved_exclusions': 2,
                                  'provider_calls_recorded': 0,
                                  'publication_status': 'private'}}
        status_file.write_text(json.dumps(status))
        self.assertEqual(PrivatePresentationStatus(status_file).current, status)
        previous_env = active_env.encode()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        with self.assertRaises(RuntimeError):
            self.activate_candidate(digest, private, manifest_digest,
                                    health=lambda: (_ for _ in ()).throw(RuntimeError('unhealthy')))
        self.assertEqual((self.layout.state / 'active.env').read_bytes(), previous_env)
        self.assertEqual(json.loads(status_file.read_text()), status)

    def test_import_credentials_must_be_complete_and_independent(self):
        base = self.config.read_text()
        self.config.write_text(base + 'OWNER_EVIDENCE_IMPORT_TOKEN=separate-random-import-token-123456\n')
        with self.assertRaises(ValueError):
            _config(self.config, os.getuid(), self.digest)
        self.config.write_text(base + 'OWNER_EVIDENCE_IMPORT_TOKEN=short\n'
                               'OWNER_EVIDENCE_IMPORT_CLIENT_ID=import123.access\n')
        with self.assertRaises(ValueError):
            _config(self.config, os.getuid(), self.digest)
        self.config.write_text(base + 'OWNER_EVIDENCE_IMPORT_TOKEN=separate-random-import-token-123456\n'
                               'OWNER_EVIDENCE_IMPORT_CLIENT_ID=import123.access\n'
                               'OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n'
                               'OWNER_EVIDENCE_STATUS_TOKEN=separate-random-import-token-123456\n'
                               'OWNER_EVIDENCE_STATUS_CLIENT_ID=status123.access\n')
        with self.assertRaises(ValueError):
            _config(self.config, os.getuid(), self.digest)

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

    def queue_inputs(self, snapshot_digest=None):
        root = Path(self.temp.name)
        audit = root / 'audit-v1.json'
        audit.write_text(json.dumps({'schema': 'issue172-consolidated-audit-v1',
            'snapshot_sha256': snapshot_digest or self.digest,
            'totals': {'candidate_result_positions': 799, 'verified_staged_versions': 750,
                       'unresolved_positions': 49, 'ambiguous_candidate': 419,
                       'no_known_counterpart': 380}}))
        audit_digest = hashlib.sha256(audit.read_bytes()).hexdigest()
        queue = root / 'owner-queue-v1.json'
        def entry(n, kind):
            return {'id': f'{kind}-{n}', 'kind': kind, 'status': 'pending',
                    'source_key': 'source', 'source_sha256': 'c'*64,
                    'source_position': 'p1-r1', 'citation': 'page 1 row 1',
                    'evidence_version': 'v1', 'reason': 'unresolved', 'related_positions': []}
        entries = ([entry(n, 'unresolved_field') for n in range(49)] +
                   [entry(n, 'relationship_candidate') for n in range(419)])
        queue.write_text(json.dumps({'schema': 'issue172-owner-queue-v1',
            'audit_sha256': audit_digest, 'entries': entries}))
        return queue, hashlib.sha256(queue.read_bytes()).hexdigest(), audit, audit_digest

    def test_guarded_queue_activation_is_idempotent_and_restores_prior_on_failure(self):
        self.run_activation()
        queue, digest, audit, audit_digest = self.queue_inputs()
        from owner_evidence_activate import activate_queue
        arguments = dict(queue_source=queue, expected_queue_sha256=digest,
                         audit_source=audit, expected_audit_sha256=audit_digest,
                         layout=self.layout, expected_snapshot_sha256=self.digest,
                         command=self.command, health=lambda: None,
                         owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual(activate_queue(**arguments), 'activated')
        installed = self.layout.state / 'issue172-queue' / 'owner-queue-v1.json'
        self.assertEqual(installed.read_bytes(), queue.read_bytes())
        self.assertEqual(installed.stat().st_mode & 0o777, 0o600)
        self.assertEqual(activate_queue(**arguments), 'unchanged')
        before_config = self.config.read_bytes()
        before_env = (self.layout.state / 'active.env').read_bytes()
        queue.write_text(queue.read_text().replace('relationship_candidate-0', 'relationship_candidate-new'))
        new_digest = hashlib.sha256(queue.read_bytes()).hexdigest()
        with self.assertRaises(RuntimeError):
            activate_queue(**{**arguments, 'expected_queue_sha256': new_digest,
                'health': lambda: (_ for _ in ()).throw(RuntimeError('unhealthy'))})
        self.assertIn(b'"schema": "issue172-owner-queue-v1"', installed.read_bytes())
        self.assertEqual(hashlib.sha256(installed.read_bytes()).hexdigest(), digest)
        self.assertEqual(self.config.read_bytes(), before_config)
        self.assertEqual((self.layout.state / 'active.env').read_bytes(), before_env)

    def test_queue_refuses_wrong_audit_binding_without_changing_service(self):
        self.run_activation()
        queue, digest, audit, audit_digest = self.queue_inputs('a' * 64)
        from owner_evidence_activate import activate_queue
        self.calls.clear()
        with self.assertRaises(ValueError):
            activate_queue(queue, digest, audit, audit_digest, self.layout, self.digest,
                           command=self.command, owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual(self.calls, [])

    def test_queue_rollback_restores_prior_pins_and_removes_new_file(self):
        self.run_activation()
        original_config = self.config.read_bytes()
        original_env = (self.layout.state / 'active.env').read_bytes()
        queue, digest, audit, audit_digest = self.queue_inputs()
        from owner_evidence_activate import activate_queue, rollback_queue
        activate_queue(queue, digest, audit, audit_digest, self.layout, self.digest,
                       command=self.command, health=lambda: None,
                       owner_uid=os.getuid(), owner_gid=os.getgid())
        rollback_queue(self.layout, digest, command=self.command,
                       owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual(self.config.read_bytes(), original_config)
        self.assertEqual((self.layout.state / 'active.env').read_bytes(), original_env)
        self.assertFalse((self.layout.state / 'issue172-queue' / 'owner-queue-v1.json').exists())
        with self.assertRaises(ValueError):
            rollback_queue(self.layout, digest, command=self.command,
                           owner_uid=os.getuid(), owner_gid=os.getgid())

    def test_queue_activation_rolls_back_when_installed_app_lacks_queue_route(self):
        self.run_activation()
        before_config = self.config.read_bytes()
        before_env = (self.layout.state / 'active.env').read_bytes()
        queue, digest, audit, audit_digest = self.queue_inputs()
        from owner_evidence_activate import activate_queue
        class Response(io.BytesIO):
            status = 200
        def old_app(request, timeout):
            if '/api/issue172-queue' in request.full_url:
                raise urllib.error.HTTPError(request.full_url, 404, 'missing', {}, None)
            return Response(json.dumps({'snapshot_sha256': self.digest}).encode())
        with mock.patch('owner_evidence_activate.urllib.request.urlopen', side_effect=old_app):
            with self.assertRaises(urllib.error.HTTPError):
                activate_queue(queue, digest, audit, audit_digest, self.layout, self.digest,
                               command=self.command, owner_uid=os.getuid(), owner_gid=os.getgid())
        self.assertEqual(self.config.read_bytes(), before_config)
        self.assertEqual((self.layout.state / 'active.env').read_bytes(), before_env)
        self.assertFalse((self.layout.state / 'issue172-queue' / 'owner-queue-v1.json').exists())

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

    def test_owner_route_failure_can_roll_back_completed_host_candidate(self):
        from owner_evidence_activate import rollback_candidate
        self.run_activation()
        old_config = self.config.read_bytes()
        old_snapshot = (self.layout.state / 'current').resolve()
        digest, private, manifest_digest = self.candidate_with_source_bundle()
        self.assertEqual(self.activate_candidate(digest, private, manifest_digest), 'activated')
        with self.assertRaisesRegex(ValueError, 'differs'):
            rollback_candidate(self.layout, digest, '0' * 64, command=self.command)
        self.assertEqual((self.layout.state / 'current').resolve().name, digest)
        rollback_candidate(self.layout, digest, manifest_digest, command=self.command)
        self.assertEqual(self.config.read_bytes(), old_config)
        self.assertEqual((self.layout.state / 'current').resolve(), old_snapshot)
        self.assertEqual(json.loads((self.layout.state / 'activation-checkpoint' / 'status.json').read_text())['status'], 'failed')

    def test_owner_route_failure_restores_pre_status_application(self):
        from owner_evidence_activate import rollback_candidate
        self.run_activation()
        prior_app = (self.layout.app / 'current').resolve()
        (prior_app / 'scripts/private_presentation_status.py').unlink()
        prior_snapshot = (self.layout.state / 'current').resolve()
        prior_config = self.config.read_bytes()
        (self.bundle / 'scripts/private_presentation_status.py').write_text('print("new status")\n')
        digest, private, manifest_digest = self.candidate_with_source_bundle()

        self.assertEqual(self.activate_candidate(digest, private, manifest_digest), 'activated')
        rollback_candidate(self.layout, digest, manifest_digest, command=self.command)

        self.assertEqual((self.layout.app / 'current').resolve(), prior_app)
        self.assertEqual((self.layout.state / 'current').resolve(), prior_snapshot)
        self.assertEqual(self.config.read_bytes(), prior_config)
        self.assertEqual(json.loads((self.layout.state / 'activation-checkpoint' / 'status.json').read_text())['status'], 'failed')

    def test_owner_route_failure_restores_pre_decision_application(self):
        from owner_evidence_activate import rollback_candidate
        self.run_activation()
        prior_app = (self.layout.app / 'current').resolve()
        for name in ('private_presentation_status.py', 'owner_decision_store.py',
                     'aida_snapshot_observations.py', 'issue55_aida_selected_html.py',
                     'cmas_microplus_snapshot_observations.py',
                     'cmas_microplus_ingest.py', 'cmas_microplus_finalize.py'):
            (prior_app / 'scripts' / name).unlink()
        prior_snapshot = (self.layout.state / 'current').resolve()
        (self.bundle / 'scripts/private_presentation_status.py').write_text('print("new status")\n')
        digest, private, manifest_digest = self.candidate_with_source_bundle()

        self.assertEqual(self.activate_candidate(digest, private, manifest_digest), 'activated')
        rollback_candidate(self.layout, digest, manifest_digest, command=self.command)

        self.assertEqual((self.layout.app / 'current').resolve(), prior_app)
        self.assertEqual((self.layout.state / 'current').resolve(), prior_snapshot)
        self.assertEqual(json.loads((self.layout.state / 'activation-checkpoint' / 'status.json').read_text())['status'], 'failed')

    def test_owner_route_rollback_rejects_other_missing_prior_application_file(self):
        from owner_evidence_activate import rollback_candidate
        self.run_activation()
        prior_app = (self.layout.app / 'current').resolve()
        (prior_app / 'scripts/owner_evidence_origin.py').unlink()
        (self.bundle / 'scripts/private_presentation_status.py').write_text('print("new status")\n')
        digest, private, manifest_digest = self.candidate_with_source_bundle()

        self.assertEqual(self.activate_candidate(digest, private, manifest_digest), 'activated')
        with self.assertRaisesRegex(RuntimeError, 'recovery failed'):
            rollback_candidate(self.layout, digest, manifest_digest, command=self.command)
        self.assertNotEqual((self.layout.app / 'current').resolve(), prior_app)
        self.assertEqual(json.loads((self.layout.state / 'activation-checkpoint' / 'status.json').read_text())['status'], 'pending')
        self.assertIn(('systemctl', 'stop', 'freediving-owner-evidence.service'), self.calls)

    def test_new_activation_requires_status_script(self):
        (self.bundle / 'scripts/private_presentation_status.py').unlink()
        with self.assertRaisesRegex(ValueError, 'missing or linked'):
            self.run_activation()
        self.assertEqual(self.calls, [])

    def test_new_activation_requires_decision_observation_dependencies(self):
        (self.bundle / 'scripts/aida_snapshot_observations.py').unlink()
        with self.assertRaisesRegex(ValueError, 'missing or linked'):
            self.run_activation()
        self.assertEqual(self.calls, [])

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

    def test_local_health_waits_for_origin_startup_beyond_five_seconds(self):
        values = {'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-origin.alphacompose.com',
                  'OWNER_EVIDENCE_GATEWAY_SECRET': 'some-private-gateway-secret',
                  'OWNER_EVIDENCE_EMAILS': 'owner@example.com'}
        clock = [0.0]

        class Response(io.BytesIO):
            status = 200

        def response(request, timeout):
            if clock[0] < 6:
                raise urllib.error.URLError('origin starting')
            headers = {key.lower(): value for key, value in request.header_items()}
            if (headers['host'] != values['OWNER_EVIDENCE_ORIGIN_HOST'] or
                    headers['x-freediving-owner-gateway'] != values['OWNER_EVIDENCE_GATEWAY_SECRET'] or
                    headers['x-freediving-owner-email'] != values['OWNER_EVIDENCE_EMAILS']):
                raise urllib.error.HTTPError(request.full_url, 403, 'denied', {}, None)
            return Response(json.dumps({'snapshot_sha256': self.digest}).encode())

        def advance(seconds):
            clock[0] += seconds

        with (mock.patch('owner_evidence_activate.time.monotonic', side_effect=lambda: clock[0]),
              mock.patch('owner_evidence_activate.time.sleep', side_effect=advance),
              mock.patch('owner_evidence_activate.urllib.request.urlopen', side_effect=response)):
            _health(values, self.digest)
        self.assertGreaterEqual(clock[0], 6)

    def test_local_health_stops_when_origin_never_starts(self):
        values = {'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-origin.alphacompose.com',
                  'OWNER_EVIDENCE_GATEWAY_SECRET': 'some-private-gateway-secret',
                  'OWNER_EVIDENCE_EMAILS': 'owner@example.com'}
        clock = [0.0]

        def advance(seconds):
            clock[0] += seconds

        with (mock.patch('owner_evidence_activate.time.monotonic', side_effect=lambda: clock[0]),
              mock.patch('owner_evidence_activate.time.sleep', side_effect=advance),
              mock.patch('owner_evidence_activate.urllib.request.urlopen',
                         side_effect=urllib.error.URLError('origin unavailable')) as requests):
            with self.assertRaises(urllib.error.URLError):
                _health(values, self.digest)
        self.assertGreaterEqual(clock[0], 25)
        self.assertLessEqual(clock[0], 30)
        self.assertLessEqual(requests.call_count, 61)

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

    def test_queue_health_requires_pinned_cited_response_and_route_denials(self):
        values = {'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-origin.alphacompose.com',
                  'OWNER_EVIDENCE_GATEWAY_SECRET': 'some-private-gateway-secret',
                  'OWNER_EVIDENCE_EMAILS': 'owner@example.com'}
        queue_digest, audit_digest = 'd'*64, 'e'*64
        queue_item = {'citation': {'page': 1, 'region': {
            'bbox': [0, 0, 1, 1], 'units': 'relative'}}}
        class Response(io.BytesIO):
            status = 200
        def response(request, timeout):
            headers = {key.lower(): value for key, value in request.header_items()}
            if (headers['host'] != values['OWNER_EVIDENCE_ORIGIN_HOST'] or
                    headers['x-freediving-owner-gateway'] != values['OWNER_EVIDENCE_GATEWAY_SECRET'] or
                    headers['x-freediving-owner-email'] != values['OWNER_EVIDENCE_EMAILS']):
                raise urllib.error.HTTPError(request.full_url, 403, 'denied', {}, None)
            if '/api/issue172-queue' in request.full_url:
                return Response(json.dumps({'schema': 'issue172-owner-queue-v1',
                    'audit_sha256': audit_digest, 'queue_sha256': queue_digest,
                    'total': 468, 'items': [queue_item]}).encode())
            return Response(json.dumps({'snapshot_sha256': self.digest}).encode())
        with mock.patch('owner_evidence_activate.urllib.request.urlopen', side_effect=response):
            _health(values, self.digest, queue_digest=queue_digest, audit_digest=audit_digest)
            queue_item['citation'] = {'page': 1, 'region': 'printed result row',
                                      'bbox': [0, 0, 1, 1], 'units': 'relative'}
            _health(values, self.digest, queue_digest=queue_digest, audit_digest=audit_digest)
            for citation in ({}, {'page': 1}, {'page': 0, 'region': {'bbox': [0, 0, 1, 1], 'units': 'relative'}},
                             {'page': 1, 'region': {'bbox': [], 'units': 'relative'}}, ''):
                queue_item['citation'] = citation
                with self.subTest(citation=citation), self.assertRaises(RuntimeError):
                    _health(values, self.digest, queue_digest=queue_digest, audit_digest=audit_digest)
            queue_item['citation'] = {'page': 1, 'region': {'bbox': [0, 0, 1, 1], 'units': 'relative'}}
        def missing_route(request, timeout):
            if '/api/issue172-queue' in request.full_url:
                raise urllib.error.HTTPError(request.full_url, 404, 'missing', {}, None)
            return response(request, timeout)
        with mock.patch('owner_evidence_activate.urllib.request.urlopen', side_effect=missing_route):
            with self.assertRaises(urllib.error.HTTPError):
                _health(values, self.digest, queue_digest=queue_digest, audit_digest=audit_digest)


if __name__ == '__main__':
    unittest.main()
