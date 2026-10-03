import json
import sys
from pathlib import Path

import unittest
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from test_private_evidence_transfer import prepared
from test_private_evidence_ssh import ssh_standin
from evidence_presentation import present
from macos_nordvpn import VpnState
from private_evidence_ssh import ssh_stage


class Remote:
    def __init__(self, destination):
        self.destination = destination
        self.active = {'snapshot_sha256': 'a' * 64, 'bundle_manifest_sha256': 'b' * 64}
        self.previous = None
        self.calls = []
        self.served = None

    def stage(self, run_dir):
        self.calls.append('stage')
        script = Path(__file__).resolve().parents[1] / 'scripts' / 'private_evidence_transfer.py'
        return ssh_stage(run_dir, 'safe-host', self.destination, script,
                         ssh_standin(self.destination.parent))

    def activate(self, receipt):
        self.calls.append('activate')
        self.previous = self.active
        self.active = {'snapshot_sha256': receipt['snapshot_sha256'],
                       'bundle_manifest_sha256': receipt['bundle_manifest_sha256']}
        return 'activated'

    def owner_overview(self):
        self.calls.append('overview')
        return {'status': 200, **(self.served if self.served is not None else self.active)}

    def rollback(self, receipt):
        self.calls.append('rollback')
        self.active = self.previous


def state(run_dir):
    return json.loads((run_dir / 'state.json').read_text())


def test_verified_run_becomes_active_only_after_bound_owner_response(tmp_path):
    run_dir, destination = prepared(tmp_path)
    remote = Remote(destination)
    result = present(run_dir, remote, publisher_requests_stopped=True,
                     vps_reachable=lambda: True)
    assert result['status'] == 'active'
    assert remote.calls == ['stage', 'activate', 'overview']
    assert state(run_dir)['remote']['active'] == remote.active
    assert state(run_dir)['local']['status'] == 'complete'
    assert present(run_dir, remote, publisher_requests_stopped=True,
                   vps_reachable=lambda: True)['status'] == 'active'
    assert remote.calls == ['stage', 'activate', 'overview', 'overview']


def test_bad_served_response_rolls_back_and_retains_retry(tmp_path, served):
    run_dir, destination = prepared(tmp_path)
    remote = Remote(destination)
    previous = remote.active.copy()
    remote.served = served
    with unittest.TestCase().assertRaisesRegex(ValueError, 'owner route'):
        present(run_dir, remote, publisher_requests_stopped=True,
                vps_reachable=lambda: True)
    assert remote.active == previous
    assert remote.calls[-1] == 'rollback'
    assert state(run_dir)['remote']['status'] == 'failed'
    assert state(run_dir)['remote']['active'] is None
    assert (run_dir / 'bundle' / 'manifest.json').is_file()


def test_stale_receipt_rejected_before_activation(tmp_path):
    run_dir, destination = prepared(tmp_path)
    remote = Remote(destination)
    original = remote.stage
    remote.stage = lambda run: {**original(run), 'snapshot_sha256': 'c' * 64}
    with unittest.TestCase().assertRaisesRegex(ValueError, 'staging receipt'):
        present(run_dir, remote, publisher_requests_stopped=True,
                vps_reachable=lambda: True)
    assert 'activate' not in remote.calls
    assert state(run_dir)['remote']['status'] == 'failed'


def test_owner_route_transport_failure_rolls_back(tmp_path):
    run_dir, destination = prepared(tmp_path)
    remote = Remote(destination)
    previous = remote.active.copy()
    remote.owner_overview = lambda: (_ for _ in ()).throw(OSError('owner route unavailable'))
    with unittest.TestCase().assertRaisesRegex(OSError, 'owner route unavailable'):
        present(run_dir, remote, publisher_requests_stopped=True,
                vps_reachable=lambda: True)
    assert remote.active == previous
    assert state(run_dir)['remote']['status'] == 'failed'


def test_pending_retry_repeats_safe_staging_after_interruption(tmp_path):
    run_dir, destination = prepared(tmp_path)
    remote = Remote(destination)
    remote.activate = lambda receipt: (_ for _ in ()).throw(KeyboardInterrupt())
    with unittest.TestCase().assertRaises(KeyboardInterrupt):
        present(run_dir, remote, publisher_requests_stopped=True,
                vps_reachable=lambda: True)
    assert state(run_dir)['remote']['status'] == 'pending'
    remote.activate = Remote.activate.__get__(remote)
    assert present(run_dir, remote, publisher_requests_stopped=True,
                   vps_reachable=lambda: True)['status'] == 'active'


def test_route_boundary_requires_stopped_publishers_and_skips_vpn_when_reachable(tmp_path):
    run_dir, destination = prepared(tmp_path)
    remote = Remote(destination)
    with unittest.TestCase().assertRaisesRegex(ValueError, 'publisher requests'):
        present(run_dir, remote, publisher_requests_stopped=False,
                vps_reachable=lambda: True)
    assert remote.calls == []


def test_blocked_route_uses_fake_vpn_after_local_completion_and_restores(tmp_path):
    run_dir, destination = prepared(tmp_path)
    remote = Remote(destination)
    prior = VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', True)

    class Control:
        state = prior
        events = []

        def snapshot(self):
            return self.state

        def disconnect(self, service):
            self.events.append('disconnect')
            self.state = VpnState(service, False, None, None, True)

        def restore(self, state):
            self.events.append('restore')
            self.state = state

    control = Control()
    probes = iter([False, True])
    journal = tmp_path / 'vpn.json'
    result = present(run_dir, remote, publisher_requests_stopped=True,
                     vps_reachable=lambda: next(probes), vpn_control=control,
                     vpn_journal=journal)
    assert result['status'] == 'active'
    assert control.state == prior
    assert control.events == ['disconnect', 'restore']
    assert json.loads(journal.read_text())['status'] == 'restored'


class PresentationTests(unittest.TestCase):
    def test_success(self):
        with TemporaryDirectory() as root:
            test_verified_run_becomes_active_only_after_bound_owner_response(Path(root))

    def test_served_mismatch(self):
        responses = [
            {'status': 200, 'snapshot_sha256': 'c' * 64, 'bundle_manifest_sha256': 'b' * 64},
            {'status': 200, 'snapshot_sha256': 'a' * 64, 'bundle_manifest_sha256': 'c' * 64},
            {'status': 503, 'snapshot_sha256': 'a' * 64, 'bundle_manifest_sha256': 'b' * 64},
        ]
        for served in responses:
            with self.subTest(served=served), TemporaryDirectory() as root:
                test_bad_served_response_rolls_back_and_retains_retry(Path(root), served)

    def test_stale_receipt(self):
        with TemporaryDirectory() as root:
            test_stale_receipt_rejected_before_activation(Path(root))

    def test_owner_route_failure(self):
        with TemporaryDirectory() as root:
            test_owner_route_transport_failure_rolls_back(Path(root))

    def test_resume(self):
        with TemporaryDirectory() as root:
            test_pending_retry_repeats_safe_staging_after_interruption(Path(root))

    def test_route_boundary(self):
        with TemporaryDirectory() as root:
            test_route_boundary_requires_stopped_publishers_and_skips_vpn_when_reachable(Path(root))

    def test_vpn_transition(self):
        with TemporaryDirectory() as root:
            test_blocked_route_uses_fake_vpn_after_local_completion_and_restores(Path(root))
