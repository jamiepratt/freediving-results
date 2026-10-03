#!/usr/bin/env python3
"""Resumably stage a verified local evidence run in private destination storage."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

from private_source_bundle import verify as verify_bundle

SCHEMA = 'private-evidence-transfer/v1'
HEX = re.compile(r'[0-9a-f]{64}\Z')
BLOCK = 1024 * 1024


def digest(path):
    sha = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(BLOCK), b''):
            sha.update(block)
    return sha.hexdigest()


def private_dir(path):
    if path.is_symlink() or not path.is_dir() or path.stat().st_mode & 0o077:
        raise ValueError('destination directory must be private and unlinked')


def regular(path):
    if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise ValueError(f'unsafe staged file: {path.name}')


def verified_input(run_dir):
    state = json.loads((run_dir / 'state.json').read_text(encoding='utf-8'))
    local = state['local']
    if local['status'] != 'complete':
        raise ValueError('local run is not complete')
    for key in ('snapshot_sha256', 'snapshot_manifest_sha256', 'bundle_manifest_sha256'):
        if not HEX.fullmatch(local.get(key, '')):
            raise ValueError('invalid local hash binding')
    snapshot = run_dir / 'snapshot'
    bundle = run_dir / 'bundle'
    if snapshot.is_symlink() or bundle.is_symlink():
        raise ValueError('linked local evidence rejected')
    for path in (snapshot / 'manifest.json', snapshot / 'snapshot.sqlite'):
        if path.is_symlink() or not path.is_file():
            raise ValueError('missing or linked snapshot input')
    if digest(snapshot / 'manifest.json') != local['snapshot_manifest_sha256']:
        raise ValueError('snapshot manifest mismatch')
    manifest = json.loads((snapshot / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('snapshot_sha256') != local['snapshot_sha256'] or digest(snapshot / 'snapshot.sqlite') != local['snapshot_sha256']:
        raise ValueError('snapshot hash mismatch')
    subprocess.run([sys.executable, str(Path(__file__).with_name('unified_evidence_snapshot.py')),
                    'verify', '--output-dir', str(snapshot)], check=True, capture_output=True)
    if digest(bundle / 'manifest.json') != local['bundle_manifest_sha256']:
        raise ValueError('bundle manifest mismatch')
    bundle_manifest = verify_bundle(bundle)
    for name, path in (('snapshot.sqlite', snapshot / 'snapshot.sqlite'),
                       ('snapshot-manifest.json', snapshot / 'manifest.json')):
        matches = [item for item in bundle_manifest['sources'] if item.get('id') == name and
                   item.get('status') == 'included' and item.get('sha256') == digest(path)]
        if len(matches) != 1:
            raise ValueError(f'bundle does not match {name}')
    return local, snapshot, bundle, bundle_manifest


def copy_verified(source, target, expected):
    """Copy only missing bytes, validating a partial prefix before appending."""
    if target.exists() or target.is_symlink():
        regular(target)
        if digest(target) != expected or target.stat().st_size != source.stat().st_size:
            raise ValueError(f'staged object mismatch: {target.name}')
        return 0
    part = target.with_name(target.name + '.part')
    offset = 0
    if part.exists() or part.is_symlink():
        regular(part)
        offset = part.stat().st_size
        if offset > source.stat().st_size:
            raise ValueError(f'staged partial mismatch: {target.name}')
        with source.open('rb') as original, part.open('rb') as existing:
            while block := existing.read(BLOCK):
                if original.read(len(block)) != block:
                    raise ValueError(f'staged partial mismatch: {target.name}')
    with source.open('rb') as original, part.open('ab') as output:
        os.chmod(part, 0o600)
        original.seek(offset)
        copied = 0
        while block := original.read(BLOCK):
            output.write(block)
            copied += len(block)
        output.flush()
        os.fsync(output.fileno())
    if digest(part) != expected or part.stat().st_size != source.stat().st_size:
        raise ValueError(f'copied object mismatch: {target.name}')
    os.replace(part, target)
    return copied


def stage(run_dir, destination):
    local, snapshot, bundle, manifest = verified_input(run_dir)
    private_dir(destination)
    key = local['snapshot_sha256'] + '-' + local['bundle_manifest_sha256']
    stage_root = destination / 'staging'
    if not stage_root.exists():
        stage_root.mkdir(mode=0o700)
    private_dir(stage_root)
    target = stage_root / key
    if not target.exists():
        target.mkdir(mode=0o700)
    private_dir(target)
    snap_target = target / 'snapshot'
    bundle_target = target / 'source-bundle'
    objects_target = bundle_target / 'objects'
    for directory in (snap_target, bundle_target, objects_target):
        if not directory.exists():
            directory.mkdir(mode=0o700)
        private_dir(directory)
    files = [(snapshot / 'manifest.json', snap_target / 'manifest.json', local['snapshot_manifest_sha256']),
             (snapshot / 'snapshot.sqlite', snap_target / 'snapshot.sqlite', local['snapshot_sha256']),
             (bundle / 'manifest.json', bundle_target / 'manifest.json', local['bundle_manifest_sha256'])]
    seen = set()
    for item in manifest['sources']:
        if item.get('status') == 'included' and item['sha256'] not in seen:
            name = item['sha256']
            files.append((bundle / 'objects' / name, objects_target / name, name))
            seen.add(name)
    receipt = {'schema': SCHEMA, 'snapshot_sha256': local['snapshot_sha256'],
               'snapshot_manifest_sha256': local['snapshot_manifest_sha256'],
               'bundle_manifest_sha256': local['bundle_manifest_sha256'],
               'staging_path': 'staging/' + key}
    receipt_path = target / 'transfer.json'
    if receipt_path.exists() or receipt_path.is_symlink():
        regular(receipt_path)
        saved = json.loads(receipt_path.read_text(encoding='utf-8'))
        if saved != receipt:
            raise ValueError('staged receipt mismatch')
    copied = sum(copy_verified(source, dest, expected) for source, dest, expected in files)
    verify_bundle(bundle_target)
    subprocess.run([sys.executable, str(Path(__file__).with_name('unified_evidence_snapshot.py')),
                    'verify', '--output-dir', str(snap_target)], check=True, capture_output=True)
    if not receipt_path.exists():
        part = receipt_path.with_name('transfer.json.part')
        if part.exists() or part.is_symlink():
            raise ValueError('unexpected partial receipt')
        with part.open('x', encoding='utf-8') as output:
            os.chmod(part, 0o600)
            json.dump(receipt, output, sort_keys=True, separators=(',', ':'))
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(part, receipt_path)
    return {**receipt, 'bytes_copied': copied}


def main():
    if len(sys.argv) > 1 and sys.argv[1] == 'remote-ssh':
        from private_evidence_ssh import remote_command
        try:
            result = remote_command(json.loads(sys.argv[2]))
            print(json.dumps(result, sort_keys=True))
            return 0
        except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError,
                json.JSONDecodeError):
            print('remote object mismatch', file=sys.stderr)
            return 1
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['stage', 'ssh-stage'])
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--host')
    parser.add_argument('--remote-root', type=Path)
    parser.add_argument('--remote-script', type=Path)
    parser.add_argument('--ssh', type=Path, default=Path('ssh'))
    args = parser.parse_args()
    try:
        if args.command == 'stage':
            if args.destination is None:
                parser.error('--destination is required for stage')
            result = stage(args.run_dir, args.destination)
        else:
            if not args.host or not args.remote_root or not args.remote_script:
                parser.error('--host, --remote-root and --remote-script are required for ssh-stage')
            from private_evidence_ssh import ssh_stage
            result = ssh_stage(args.run_dir, args.host, args.remote_root, args.remote_script, args.ssh)
        print(json.dumps(result, sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError,
            json.JSONDecodeError) as error:
        message = str(error) if args.command == 'stage' else 'SSH staging failed: ' + (
            str(error) if str(error) in ('remote object mismatch', 'remote partial mismatch',
                                       'remote receipt mismatch', 'remote offset mismatch',
                                       'remote protocol mismatch', 'invalid SSH host',
                                       'remote paths must be absolute', 'SSH transport command failed')
            else 'invalid or unverified input')
        print('private transfer: ' + message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
