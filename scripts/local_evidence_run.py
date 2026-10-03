#!/usr/bin/env python3
"""Checkpoint local evidence preparation without claiming remote presentation."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
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
    return plan


def command(argv):
    subprocess.run(argv, check=True)


def run(plan_path, run_dir):
    plan = checked_plan(plan_path)
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
        state = {'schema': 'local-evidence-run/v1', 'plan_sha256': plan_hash, 'stages': {},
                 'local': {'status': 'pending'}, 'remote': {'status': 'pending', 'active': None, 'pending': None, 'failed': None}}
        atomic_json(state_path, state)
    try:
        if state['local']['status'] == 'complete':
            verify_staging(run_dir, state)
            print(json.dumps(state, sort_keys=True))
            return
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
        print(json.dumps(state, sort_keys=True))
    except Exception as error:
        state['local'] = {'status': 'failed', 'error': str(error)}
        atomic_json(state_path, state)
        raise


def verify_staging(run_dir, state):
    snapshot = run_dir / 'snapshot'
    bundle = run_dir / 'bundle'
    command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify', '--output-dir', str(snapshot)])
    command([sys.executable, str(ROOT / 'private_source_bundle.py'), 'verify', '--bundle-dir', str(bundle)])
    if digest(snapshot / 'manifest.json') != state['local']['snapshot_manifest_sha256'] or digest(bundle / 'manifest.json') != state['local']['bundle_manifest_sha256']:
        raise ValueError('completed staging manifest changed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['run'])
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    try:
        run(args.plan, args.run_dir)
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f'local run: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
