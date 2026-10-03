"""Fail-closed VPN boundary for a future verified macOS NordVPN control.

No installed NordVPN control surface currently proves obfuscated category and
exact restoration. This module deliberately provides no live NetworkControl.
"""

import json
import os
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class VpnState:
    service: str
    connected: bool
    endpoint: str | None
    category: str | None
    verified: bool


class RestorationError(RuntimeError):
    """The journal still contains the exact state that needs restoration."""


def _checked_state(state):
    if (not isinstance(state, VpnState) or not state.verified or
            state.service not in ('NordVPN NordLynx', 'NordVPN NordWhisper') or
            (state.connected and (not state.endpoint or not state.category))):
        raise ValueError('exact NordVPN state and category are not verified')
    return state


def _write_journal(path, status, state):
    path = Path(path)
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError('VPN journal cannot be a symlink')
    if path.parent.stat().st_mode & 0o077:
        raise ValueError('VPN journal requires a private directory')
    fd, temporary = tempfile.mkstemp(prefix='.vpn-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump({'schema': 'nordvpn-restoration/v1', 'status': status,
                       'prior': asdict(state)}, stream, sort_keys=True)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        if status == 'restore_pending':
            os.link(temporary, path)
        else:
            os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def recover_connection(control, journal_path):
    """Repeat an interrupted restoration using the durable prior-state journal."""
    path = Path(journal_path)
    if path.is_symlink():
        raise ValueError('VPN journal cannot be a symlink')
    record = json.loads(path.read_text())
    if record.get('schema') != 'nordvpn-restoration/v1' or record.get('status') not in ('restore_pending', 'restored'):
        raise ValueError('invalid VPN restoration journal')
    prior = _checked_state(VpnState(**record['prior']))
    if record['status'] == 'restored':
        if control.snapshot() != prior:
            raise RestorationError(f'VPN state differs from restored journal: {path}')
        return
    try:
        control.restore(prior)
        if control.snapshot() != prior:
            raise ValueError('restored VPN state/category could not be verified')
        _write_journal(path, 'restored', prior)
    except BaseException as error:
        raise RestorationError(f'VPN restoration pending in {path}: {error}') from error


def deployment_route(control, vps_reachable, journal_path, *, publisher_requests_stopped, deploy):
    """Deploy through a verified route, restoring any switched VPN state."""
    if not publisher_requests_stopped:
        raise ValueError('publisher requests must stop before deployment')
    path = Path(journal_path)
    if path.exists() or path.is_symlink():
        raise ValueError(f'VPN journal exists; inspect/recover before retry: {path}')
    if vps_reachable():
        return deploy()
    prior = _checked_state(control.snapshot())
    if not prior.connected:
        raise ValueError('VPS unreachable and NordVPN is already disconnected')
    _write_journal(path, 'restore_pending', prior)
    try:
        control.disconnect(prior.service)
        changed = control.snapshot()
        if (not isinstance(changed, VpnState) or not changed.verified or
                changed.service != prior.service or changed.connected):
            raise ValueError('NordVPN disconnect could not be verified')
        if not vps_reachable():
            raise ValueError('VPS still unreachable after NordVPN disconnect')
        return deploy()
    finally:
        recover_connection(control, path)
