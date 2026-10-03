import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from macos_nordvpn import RestorationError, VpnState, deployment_route, recover_connection


class FakeControl:
    def __init__(self, state):
        self.state = state
        self.events = []

    def snapshot(self):
        self.events.append('snapshot')
        return self.state

    def disconnect(self, service):
        self.events.append(('disconnect', service))
        self.state = VpnState(service, False, None, None, True)

    def restore(self, state):
        self.events.append(('restore', state.service))
        self.state = state


def test_working_vps_route_never_changes_vpn(tmp_path):
    original = VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', True)
    control = FakeControl(original)
    result = deployment_route(control, lambda: True, tmp_path / 'vpn.json',
                              publisher_requests_stopped=True, deploy=lambda: 'staged')
    assert result == 'staged'
    assert control.state == original
    assert all(not isinstance(event, tuple) for event in control.events)
    assert not (tmp_path / 'vpn.json').exists()


def test_blocked_vps_route_journals_then_switches_and_restores_only_nordvpn(tmp_path):
    original = VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', True)
    control = FakeControl(original)
    unrelated = {'Tailscale': 'Connected'}
    journal = tmp_path / 'vpn.json'
    probes = iter([False, True])

    def deploy():
        assert control.state.connected is False
        assert json.loads(journal.read_text())['status'] == 'restore_pending'
        assert unrelated == {'Tailscale': 'Connected'}
        return 'staged'

    result = deployment_route(control, lambda: next(probes), journal,
                              publisher_requests_stopped=True, deploy=deploy)
    assert result == 'staged'
    assert control.state == original
    assert control.events.count(('disconnect', 'NordVPN NordLynx')) == 1
    assert control.events.count(('restore', 'NordVPN NordLynx')) == 1
    assert json.loads(journal.read_text())['status'] == 'restored'
    assert unrelated == {'Tailscale': 'Connected'}


@pytest.mark.parametrize('state', [
    VpnState('NordVPN NordLynx', True, 'UK #1', None, True),
    VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', False),
    VpnState('Tailscale', True, 'home', 'other', True),
])
def test_unverifiable_prior_state_refuses_network_change(tmp_path, state):
    control = FakeControl(state)
    journal = tmp_path / 'vpn.json'
    with pytest.raises(ValueError, match='state and category'):
        deployment_route(control, lambda: False, journal,
                         publisher_requests_stopped=True, deploy=lambda: None)
    assert not journal.exists()
    assert not any(isinstance(event, tuple) for event in control.events)


def test_publisher_requests_must_be_stopped_before_probe_or_switch(tmp_path):
    control = FakeControl(VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', True))
    with pytest.raises(ValueError, match='publisher requests'):
        deployment_route(control, lambda: pytest.fail('route probed too early'),
                         tmp_path / 'vpn.json', publisher_requests_stopped=False,
                         deploy=lambda: None)
    assert control.events == []


def test_restoration_journal_requires_private_directory(tmp_path):
    shared = tmp_path / 'shared'
    shared.mkdir(mode=0o755)
    shared.chmod(0o755)
    control = FakeControl(VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', True))
    with pytest.raises(ValueError, match='private'):
        deployment_route(control, lambda: False, shared / 'vpn.json',
                         publisher_requests_stopped=True, deploy=lambda: None)
    assert not (shared / 'vpn.json').exists()
    assert not any(isinstance(event, tuple) for event in control.events)


@pytest.mark.parametrize('failure', [RuntimeError('deploy failed'), KeyboardInterrupt()])
def test_failure_or_interrupt_restores_prior_connection(tmp_path, failure):
    original = VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', True)
    control = FakeControl(original)
    probes = iter([False, True])

    def fail():
        raise failure

    with pytest.raises(type(failure)):
        deployment_route(control, lambda: next(probes), tmp_path / 'vpn.json',
                         publisher_requests_stopped=True, deploy=fail)
    assert control.state == original
    assert json.loads((tmp_path / 'vpn.json').read_text())['status'] == 'restored'


def test_failed_restoration_is_recoverable_from_journal(tmp_path):
    original = VpnState('NordVPN NordLynx', True, 'UK #1', 'Obfuscated', True)

    class FailingRestore(FakeControl):
        def restore(self, state):
            raise OSError('VPN service unavailable')

    control = FailingRestore(original)
    journal = tmp_path / 'vpn.json'
    probes = iter([False, True])
    with pytest.raises(RestorationError, match='restoration pending'):
        deployment_route(control, lambda: next(probes), journal,
                         publisher_requests_stopped=True, deploy=lambda: 'staged')
    assert json.loads(journal.read_text())['status'] == 'restore_pending'
    assert control.state.connected is False
    recoverable = FakeControl(control.state)
    recover_connection(recoverable, journal)
    assert recoverable.state == original
    assert json.loads(journal.read_text())['status'] == 'restored'
