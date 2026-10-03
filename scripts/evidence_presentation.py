"""Coordinate a verified local run with injected private remote operations.

The remote adapter stages, activates, observes the authenticated owner route,
and rolls back a candidate. No live remote or VPN implementation is supplied.
"""
import json
from pathlib import Path

from local_evidence_run import atomic_json
from macos_nordvpn import deployment_route
from private_evidence_ssh import receipt
from private_evidence_transfer import verified_input


def _served(remote, expected):
    overview = remote.owner_overview()
    return (overview.get('status') == 200 and
            overview.get('snapshot_sha256') == expected['snapshot_sha256'] and
            overview.get('bundle_manifest_sha256') == expected['bundle_manifest_sha256'])


def present(run_dir, remote, *, publisher_requests_stopped, vps_reachable,
            vpn_control=None, vpn_journal=None, status_sync=None):
    """Present one completed run, keeping the prior presentation on failure.

    remote must implement stage(run_dir), activate(receipt), owner_overview(),
    and rollback(receipt). The owner response must carry a verified source-bundle
    manifest digest as well as the served snapshot digest.
    """
    run_dir = Path(run_dir)
    local, _, _, _ = verified_input(run_dir)
    expected = receipt(local)
    state_path = run_dir / 'state.json'
    state = json.loads(state_path.read_text())
    remote_state = state['remote']
    binding = {'snapshot_sha256': local['snapshot_sha256'],
               'bundle_manifest_sha256': local['bundle_manifest_sha256']}
    if not publisher_requests_stopped:
        raise ValueError('publisher requests must stop before deployment')
    if remote_state.get('active') == binding:
        if _served(remote, expected):
            return remote_state
        remote_state.update(status='failed', active=None, failed=binding,
                            pending=binding, error='owner route mismatch',
                            rollback_error=None)
        atomic_json(state_path, state)
        raise ValueError('owner route mismatch')

    remote_state.update(status='pending', pending=binding,
                        failed=None, error=None, rollback_error=None)
    atomic_json(state_path, state)
    if status_sync:
        status_sync()
    activated = False

    def deploy():
        nonlocal activated
        staged = remote.stage(run_dir)
        if {key: staged.get(key) for key in expected} != expected:
            raise ValueError('staging receipt mismatch')
        result = remote.activate(expected)
        activated = True
        if result not in ('activated', 'unchanged'):
            raise ValueError('activation receipt mismatch')
        if not _served(remote, expected):
            raise ValueError('owner route mismatch')

    try:
        reachable = vps_reachable()
        if reachable:
            deploy()
        else:
            if vpn_control is None:
                raise ValueError('VPS route unavailable without verified VPN control')
            if vpn_journal is None:
                raise ValueError('VPN restoration journal required')
            initial_probe = True

            def route_probe():
                nonlocal initial_probe
                if initial_probe:
                    initial_probe = False
                    return False
                return vps_reachable()

            deployment_route(vpn_control, route_probe, vpn_journal,
                             publisher_requests_stopped=True, deploy=deploy)
        remote_state.update(status='active',
                            active=binding, pending=None, failed=None, error=None,
                            rollback_error=None)
        atomic_json(state_path, state)
        return remote_state
    except Exception as error:
        if activated:
            try:
                remote.rollback(expected)
            except Exception as rollback_error:
                remote_state['active'] = None
                remote_state['rollback_error'] = str(rollback_error)
                error = RuntimeError(f'{error}; remote rollback failed: {rollback_error}')
        remote_state.update(status='failed', failed=binding,
                            pending=binding, error=str(error))
        atomic_json(state_path, state)
        if status_sync:
            try:
                status_sync()
            except Exception:
                pass
        raise error
