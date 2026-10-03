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
            vpn_control=None, vpn_journal=None):
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
    if not publisher_requests_stopped:
        raise ValueError('publisher requests must stop before deployment')
    if remote_state.get('active') == {
            'snapshot_sha256': local['snapshot_sha256'],
            'bundle_manifest_sha256': local['bundle_manifest_sha256']}:
        if _served(remote, expected):
            return remote_state
        remote_state.update(status='failed', failed=local['snapshot_sha256'],
                            pending=local['snapshot_sha256'], error='owner route mismatch')
        atomic_json(state_path, state)
        raise ValueError('owner route mismatch')

    remote_state.update(status='pending', pending=local['snapshot_sha256'],
                        failed=None, error=None)
    atomic_json(state_path, state)
    activated = False

    def deploy():
        nonlocal activated
        staged = remote.stage(run_dir)
        if {key: staged.get(key) for key in expected} != expected:
            raise ValueError('staging receipt mismatch')
        result = remote.activate(expected)
        if result not in ('activated', 'unchanged'):
            raise ValueError('activation receipt mismatch')
        activated = True
        if not _served(remote, expected):
            raise ValueError('owner route mismatch')

    try:
        if vpn_control is None:
            if not vps_reachable():
                raise ValueError('VPS route unavailable without verified VPN control')
            deploy()
        else:
            if vpn_journal is None:
                raise ValueError('VPN restoration journal required')
            deployment_route(vpn_control, vps_reachable, vpn_journal,
                             publisher_requests_stopped=True, deploy=deploy)
        remote_state.update(status='active',
                            active={'snapshot_sha256': local['snapshot_sha256'],
                                    'bundle_manifest_sha256': local['bundle_manifest_sha256']},
                            pending=None, failed=None, error=None)
        atomic_json(state_path, state)
        return remote_state
    except Exception as error:
        if activated:
            try:
                remote.rollback(expected)
            except Exception as rollback_error:
                error = RuntimeError(f'{error}; remote rollback failed: {rollback_error}')
        remote_state.update(status='failed', failed=local['snapshot_sha256'],
                            pending=local['snapshot_sha256'], error=str(error))
        atomic_json(state_path, state)
        raise error
