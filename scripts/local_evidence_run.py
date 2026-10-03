#!/usr/bin/env python3
"""Checkpoint local evidence preparation without claiming remote presentation."""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SCHEMA = 'local-evidence-run-plan/v1'


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def atomic_json(path, value):
    fd, name = tempfile.mkstemp(prefix='.state-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, sort_keys=True, separators=(',', ':'))
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, 0o600)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def checked_plan(path):
    plan = json.loads(path.read_text())
    if plan.get('schema') != SCHEMA or not isinstance(plan.get('stages'), list) or not isinstance(plan.get('inputs'), list) or not isinstance(plan.get('excluded'), list):
        raise ValueError('invalid local run plan')
    cutoff = datetime.fromisoformat(plan['cutoff'].replace('Z', '+00:00'))
    if cutoff.tzinfo != timezone.utc or not plan['cutoff'].endswith('Z'):
        raise ValueError('cutoff must be UTC with Z suffix')
    if not plan['inputs'] or not plan.get('source_inventory'):
        raise ValueError('inputs and source_inventory required')
    names = [item['name'] for item in plan['inputs'] + plan['excluded']]
    if len(names) != len(set(names)) or any(not re.fullmatch(r'[A-Za-z0-9_.-]+', name) for name in names):
        raise ValueError('invalid or duplicate input names')
    stages = plan['stages']
    if len({stage['name'] for stage in stages}) != len(stages):
        raise ValueError('duplicate stage names')
    for stage in stages:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', stage['name']) or not stage.get('command') or not all(isinstance(x, str) for x in stage['command']) or not stage.get('outputs'):
            raise ValueError('each stage needs name, command argv and outputs')
    for item in plan['excluded']:
        if not item.get('reason'):
            raise ValueError('excluded input needs reason')
    reconciliation = plan.get('reconciliation')
    if reconciliation is not None:
        if not isinstance(reconciliation, dict) or reconciliation.get('mode') != 'synthetic' or not reconciliation.get('spec'):
            raise ValueError('reconciliation requires a synthetic specification')
        name = reconciliation.get('name_evidence')
        if not isinstance(name, dict) or name.get('status') not in ('gap', 'checked'):
            raise ValueError('reconciliation requires checked affiliate names or an explicit gap')
        if name['status'] == 'gap' and not name.get('reason'):
            raise ValueError('affiliate name gap requires reason')
        if name['status'] == 'checked' and not (name.get('path') and re.fullmatch(r'[0-9a-f]{64}', name.get('sha256', ''))):
            raise ValueError('checked affiliate names require path and SHA-256')
        if 'owner_sync_config' in reconciliation and not reconciliation['owner_sync_config']:
            raise ValueError('owner synchronization config path required')
    return plan


def command(argv):
    subprocess.run(argv, check=True)


def run(plan_path, run_dir, *, remote_config=None, owner_access_jwt=None,
        publisher_requests_stopped=False, vps_reachable=None, remote_factory=None,
        status_access_jwt=None, status_token=None):
    plan = checked_plan(plan_path)
    if plan.get('reconciliation') and remote_config is not None:
        raise ValueError('synthetic reconciliation cannot activate remote presentation')
    plan_hash = digest(plan_path)
    run_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    if run_dir.is_symlink() or (run_dir.stat().st_mode & 0o077):
        raise ValueError('run directory must be private')
    state_path = run_dir / 'state.json'
    if state_path.exists():
        state = json.loads(state_path.read_text())
        if state['plan_sha256'] != plan_hash:
            raise ValueError('run plan changed; use a new run directory')
    else:
        state = {'schema': 'local-evidence-run/v1', 'run_id': str(uuid.uuid4()), 'plan_sha256': plan_hash, 'stages': {},
                 'local': {'status': 'pending'}, 'remote': {'status': 'pending', 'active': None, 'pending': None, 'failed': None}}
        if plan.get('reconciliation'):
            state['reconciliation'] = {'status': 'pending'}
        atomic_json(state_path, state)
    try:
        if state['local']['status'] == 'complete':
            verify_staging(run_dir, state)
        else:
            for stage in plan['stages']:
                saved = state['stages'].get(stage['name'])
                if saved and saved['status'] == 'complete':
                    if all(Path(path).is_file() and digest(path) == saved['outputs'][path] for path in stage['outputs']):
                        continue
                    raise ValueError(f"checkpoint output changed: {stage['name']}")
                state['stages'][stage['name']] = {'status': 'running'}
                atomic_json(state_path, state)
                command(stage['command'])
                outputs = {path: digest(path) for path in stage['outputs']}
                state['stages'][stage['name']] = {'status': 'complete', 'outputs': outputs}
                atomic_json(state_path, state)
            snapshot = run_dir / 'snapshot'
            if not snapshot.exists():
                build_dir = Path(tempfile.mkdtemp(prefix='.snapshot-stage-', dir=run_dir))
                os.chmod(build_dir, 0o700)
                try:
                    argv = [sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'build', '--cutoff', plan['cutoff'], '--output-dir', str(build_dir)]
                    for item in plan['inputs']:
                        argv += ['--input', f"{item['name']}={item['path']}", '--required-input', f"{item['name']}={digest(item['path'])}"]
                    for item in plan['excluded']:
                        argv += ['--excluded', f"{item['name']}={item['path']}:{item['reason']}"]
                    command(argv)
                    command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify', '--output-dir', str(build_dir)])
                    build_dir.rename(snapshot)
                finally:
                    if build_dir.exists():
                        shutil.rmtree(build_dir)
            command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify', '--output-dir', str(snapshot)])
            snapshot_manifest = json.loads((snapshot / 'manifest.json').read_text())
            inventory = json.loads(Path(plan['source_inventory']).read_text())
            sources = inventory['sources']
            for identifier, path, content_type in (
                ('snapshot.sqlite', snapshot / 'snapshot.sqlite', 'application/vnd.sqlite3'),
                ('snapshot-manifest.json', snapshot / 'manifest.json', 'application/json')):
                if any(item['id'] == identifier for item in sources):
                    raise ValueError(f'reserved source id: {identifier}')
                sources.append({'id': identifier, 'sha256': digest(path), 'bytes': path.stat().st_size,
                                'content_type': content_type, 'source_path': str(path),
                                'receipt': {'kind': 'local-evidence-run', 'cutoff': plan['cutoff']},
                                'classification': 'eligible', 'metadata': {}})
            bound_inventory = run_dir / 'bound-inventory.json'
            atomic_json(bound_inventory, inventory)
            bundle = run_dir / 'bundle'
            if not bundle.exists():
                command([sys.executable, str(ROOT / 'private_source_bundle.py'), 'build', '--inventory', str(bound_inventory), '--bundle-dir', str(bundle)])
            command([sys.executable, str(ROOT / 'private_source_bundle.py'), 'verify', '--bundle-dir', str(bundle)])
            bundle_manifest = json.loads((bundle / 'manifest.json').read_text())
            for identifier, path in (('snapshot.sqlite', snapshot / 'snapshot.sqlite'), ('snapshot-manifest.json', snapshot / 'manifest.json')):
                matches = [item for item in bundle_manifest['sources'] if item['id'] == identifier and item['status'] == 'included' and item['sha256'] == digest(path)]
                if len(matches) != 1:
                    raise ValueError(f'bundle missing matching {identifier}')
            state['coverage'] = {'status': 'verified_partial', 'cutoff': plan['cutoff'],
                                 'gaps': [{'name': item['name'], 'reason': item['reason']} for item in plan['excluded']],
                                 'confirmed_distinct_attempts': snapshot_manifest['confirmed_distinct_attempts']}
            state['local'] = {'status': 'complete', 'snapshot_sha256': snapshot_manifest['snapshot_sha256'],
                              'snapshot_manifest_sha256': digest(snapshot / 'manifest.json'),
                              'bundle_manifest_sha256': digest(bundle / 'manifest.json')}
            state['remote']['pending'] = state['local']['snapshot_sha256']
            atomic_json(state_path, state)
    except Exception as error:
        state['local'] = {'status': 'failed', 'error': str(error)}
        atomic_json(state_path, state)
        raise

    if plan.get('reconciliation'):
        try:
            reconcile_local(plan['reconciliation'], run_dir, state)
            atomic_json(state_path, state)
        except Exception as error:
            state['reconciliation'] = {**state['reconciliation'], 'status': 'failed',
                                       'error': str(error)}
            atomic_json(state_path, state)
            raise

    if bool(status_access_jwt) != bool(status_token):
        raise ValueError('private status credentials incomplete')
    if status_access_jwt:
        from private_status_sync import sync_status
        sync_status(run_dir, status_access_jwt, status_token)

    if remote_config is not None:
        from evidence_presentation import present
        from private_evidence_remote import PrivateEvidenceRemote

        binding = {'snapshot_sha256': state['local']['snapshot_sha256'],
                   'bundle_manifest_sha256': state['local']['bundle_manifest_sha256']}
        try:
            if not publisher_requests_stopped:
                raise ValueError('publisher requests must stop before deployment')
            if not owner_access_jwt:
                raise ValueError('owner Access identity session required')
            config = json.loads(Path(remote_config).read_text(encoding='utf-8'))
            keys = {'host', 'root', 'remote_script', 'ssh', 'code_bundle',
                    'activation_script', 'owner_url', 'cited_record_id',
                    'cited_source_sha256'}
            if not isinstance(config, dict) or set(config) != keys:
                raise ValueError('remote configuration requires explicit destination and cited PDF binding')
            remote = (remote_factory or PrivateEvidenceRemote)(
                **config, owner_access_jwt=owner_access_jwt)
            probe = vps_reachable or (lambda: ssh_route_reachable(config['ssh'], config['host']))
            present(run_dir, remote, publisher_requests_stopped=True,
                    vps_reachable=probe,
                    status_sync=(lambda: sync_status(run_dir, status_access_jwt, status_token)) if status_access_jwt else None)
        except Exception as error:
            state = json.loads(state_path.read_text())
            state['remote'].update(status='failed', pending=binding,
                                   failed=binding, error=str(error))
            atomic_json(state_path, state)
            if status_access_jwt:
                try:
                    sync_status(run_dir, status_access_jwt, status_token)
                except Exception:
                    pass
            raise
        state = json.loads(state_path.read_text())
    if status_access_jwt:
        sync_status(run_dir, status_access_jwt, status_token)
    print(json.dumps(state, sort_keys=True))
    return state


def verify_staging(run_dir, state):
    snapshot = run_dir / 'snapshot'
    bundle = run_dir / 'bundle'
    command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify', '--output-dir', str(snapshot)])
    command([sys.executable, str(ROOT / 'private_source_bundle.py'), 'verify', '--bundle-dir', str(bundle)])
    if digest(snapshot / 'manifest.json') != state['local']['snapshot_manifest_sha256'] or digest(bundle / 'manifest.json') != state['local']['bundle_manifest_sha256']:
        raise ValueError('completed staging manifest changed')


def reconcile_local(config, run_dir, state):
    spec = Path(config['spec'])
    if not spec.is_file() or spec.is_symlink():
        raise ValueError('reconciliation specification unavailable')
    spec_hash = digest(spec)
    name = config['name_evidence']
    gaps = []
    if name['status'] == 'checked':
        from affiliate_name_query import AffiliateNameQuery
        from unified_evidence_query import SnapshotQuery
        snapshot_dir = run_dir / 'snapshot'
        AffiliateNameQuery(name['path'], name['sha256'], snapshot_dir,
                           SnapshotQuery(snapshot_dir)).listing()
    else:
        gaps.append({'name': 'affiliate-names', 'reason': name['reason']})
    completed = state.get('reconciliation', {})
    ledger = run_dir / 'reconciliation' / 'flow.edn'
    owner_config = config.get('owner_sync_config')
    if owner_config:
        owner_config = Path(owner_config)
        info = owner_config.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077):
            raise ValueError('owner synchronization config must be an owner-only regular file')
        owner_config = owner_config.resolve(strict=True)
    if ledger.is_symlink():
        raise ValueError('reconciliation ledger must be a private file')
    if completed.get('status') == 'complete':
        if (completed.get('spec_sha256') != spec_hash
                or completed.get('snapshot_sha256') != state['local']['snapshot_sha256']
                or not ledger.is_file()
                or digest(ledger) != completed.get('ledger_sha256')):
            raise ValueError('completed reconciliation checkpoint changed')
        if not owner_config:
            return
    ledger.parent.mkdir(mode=0o700, exist_ok=True)
    known_calls = completed.get('metrics', {}).get('provider', {}).get(
        'calls_recorded', completed.get('calls_recorded_before_execution', 0))
    interrupted = completed.get('prior_interrupted', False) or completed.get('status') in ('running', 'failed')
    state['reconciliation'] = {'status': 'running', 'spec_sha256': spec_hash,
                               'calls_recorded_before_execution': known_calls,
                               'prior_interrupted': interrupted}
    atomic_json(run_dir / 'state.json', state)
    argv = ['clojure', '-M', '-m', 'freediving.local-reconciliation',
            str(spec), str(ledger), state['local']['snapshot_sha256']]
    if owner_config:
        argv.append(str(owner_config))
    result = subprocess.run(argv,
                            cwd=ROOT.parent, capture_output=True, text=True, check=True)
    if digest(spec) != spec_hash:
        raise ValueError('reconciliation specification changed during run')
    report = json.loads(result.stdout)
    report['metrics']['provider']['calls_recorded'] = known_calls + report['provider_calls']
    report['metrics']['provider']['interrupted_call_count_unknown'] = interrupted
    report['metrics']['binding']['run_id'] = state['run_id']
    report['metrics']['binding']['stage_checkpoint'] = 'reconciliation-complete'
    report['metrics']['binding']['spec_sha256'] = spec_hash
    report['metrics']['binding']['ledger_sha256'] = digest(ledger)
    report['metrics']['source_gaps'] = len(state['coverage']['gaps']) + len(gaps)
    state['reconciliation'] = {**report, 'status': 'complete', 'mode': 'synthetic',
                               'spec_sha256': spec_hash, 'ledger_sha256': digest(ledger),
                               'gaps': gaps}


def ssh_route_reachable(ssh, host):
    try:
        result = subprocess.run(
            [str(ssh), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=5',
             '-o', 'ConnectionAttempts=1', '-T', '--', host, 'true'],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=7, check=False)
        return result.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['run', 'metrics'])
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--remote-config', type=Path)
    parser.add_argument('--owner-access-jwt-env')
    parser.add_argument('--publisher-requests-stopped', action='store_true')
    parser.add_argument('--status-access-jwt-env')
    parser.add_argument('--status-token-env')
    args = parser.parse_args()
    try:
        if args.command == 'metrics':
            state = json.loads((args.run_dir / 'state.json').read_text())
            receipt = state['reconciliation']['metrics']
            if (state['reconciliation']['status'] != 'complete'
                    or receipt['binding']['run_id'] != state['run_id']
                    or receipt['binding']['snapshot_sha256'] != state['local']['snapshot_sha256']
                    or receipt['binding']['decision_revision'] != state['reconciliation']['decision_revision']
                    or receipt['binding']['spec_sha256'] != state['reconciliation']['spec_sha256']
                    or receipt['binding']['ledger_sha256'] != state['reconciliation']['ledger_sha256']
                    or receipt['binding']['ledger_sha256'] != digest(args.run_dir / 'reconciliation' / 'flow.edn')):
                raise ValueError('metrics checkpoint binding changed')
            print(json.dumps(receipt, sort_keys=True))
            return 0
        if args.plan is None:
            raise ValueError('--plan required for run')
        run(args.plan, args.run_dir, remote_config=args.remote_config,
            owner_access_jwt=os.environ.get(args.owner_access_jwt_env) if args.owner_access_jwt_env else None,
            publisher_requests_stopped=args.publisher_requests_stopped,
            status_access_jwt=os.environ.get(args.status_access_jwt_env) if args.status_access_jwt_env else None,
            status_token=os.environ.get(args.status_token_env) if args.status_token_env else None)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f'local run: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
