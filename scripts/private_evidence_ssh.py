"""Private SSH staging protocol. The remote host must have matching repository code installed."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys

from private_evidence_transfer import (BLOCK, SCHEMA, digest, private_dir, regular,
                                       verified_input)
from private_source_bundle import verify as verify_bundle

PROTOCOL = 'private-evidence-ssh/v1'
KEY = re.compile(r'[0-9a-f]{64}-[0-9a-f]{64}\Z')
HOST = re.compile(r'(?:[A-Za-z0-9_][A-Za-z0-9_.-]*@)?[A-Za-z0-9_][A-Za-z0-9_.-]*\Z')
REL = re.compile(r'(?:snapshot/(?:manifest\.json|snapshot\.sqlite)|source-bundle/(?:manifest\.json|objects/[0-9a-f]{64}))\Z')
SHA = re.compile(r'[0-9a-f]{64}\Z')


def receipt(local):
    key = local['snapshot_sha256'] + '-' + local['bundle_manifest_sha256']
    return {'schema': SCHEMA, 'snapshot_sha256': local['snapshot_sha256'],
            'snapshot_manifest_sha256': local['snapshot_manifest_sha256'],
            'bundle_manifest_sha256': local['bundle_manifest_sha256'],
            'staging_path': 'staging/' + key}


def file_plan(snapshot, bundle, manifest, local):
    result = [(snapshot / 'manifest.json', 'snapshot/manifest.json', local['snapshot_manifest_sha256']),
              (snapshot / 'snapshot.sqlite', 'snapshot/snapshot.sqlite', local['snapshot_sha256']),
              (bundle / 'manifest.json', 'source-bundle/manifest.json', local['bundle_manifest_sha256'])]
    seen = set()
    for item in manifest['sources']:
        if item.get('status') == 'included' and item['sha256'] not in seen:
            name = item['sha256']
            result.append((bundle / 'objects' / name, 'source-bundle/objects/' + name, name))
            seen.add(name)
    return result


def remote_target(root, key, rel=None):
    if not KEY.fullmatch(key) or (rel is not None and not REL.fullmatch(rel)):
        raise ValueError('invalid remote staging identifier')
    private_dir(root)
    staging = root / 'staging'
    if staging.exists() or staging.is_symlink():
        private_dir(staging)
    else:
        staging.mkdir(mode=0o700)
    target = staging / key
    if target.exists() or target.is_symlink():
        private_dir(target)
    else:
        target.mkdir(mode=0o700)
    for name in ('snapshot', 'source-bundle', 'source-bundle/objects'):
        path = target / name
        if path.exists() or path.is_symlink():
            private_dir(path)
        else:
            path.mkdir(mode=0o700)
    return target / rel if rel else target


def remote_state(root, key, rel, expected, size):
    target = remote_target(root, key, rel)
    if target.exists() or target.is_symlink():
        regular(target)
        if target.stat().st_size != size or digest(target) != expected:
            raise ValueError('remote object mismatch')
        return {'state': 'complete', 'size': size, 'sha256': expected}
    part = target.with_name(target.name + '.part')
    if part.exists() or part.is_symlink():
        regular(part)
        if part.stat().st_size > size:
            raise ValueError('remote partial mismatch')
        return {'state': 'partial', 'size': part.stat().st_size, 'sha256': digest(part)}
    return {'state': 'absent', 'size': 0, 'sha256': hashlib.sha256(b'').hexdigest()}


def remote_put(root, key, rel, expected, size, offset):
    if not isinstance(offset, int) or offset < 0:
        raise ValueError('remote offset mismatch')
    state = remote_state(root, key, rel, expected, size)
    if state['state'] == 'complete' or state['size'] != offset:
        raise ValueError('remote offset mismatch')
    target = remote_target(root, key, rel)
    part = target.with_name(target.name + '.part')
    mode = 'ab' if part.exists() else 'xb'
    with part.open(mode) as output:
        os.chmod(part, 0o600)
        while block := sys.stdin.buffer.read(BLOCK):
            output.write(block)
        output.flush()
        os.fsync(output.fileno())
    if part.stat().st_size != size or digest(part) != expected:
        raise ValueError('remote object mismatch')
    os.replace(part, target)


def remote_finalize(root, key, expected):
    target = remote_target(root, key)
    for name, sha in (('snapshot/manifest.json', expected['snapshot_manifest_sha256']),
                      ('snapshot/snapshot.sqlite', expected['snapshot_sha256']),
                      ('source-bundle/manifest.json', expected['bundle_manifest_sha256'])):
        path = target / name
        regular(path)
        if digest(path) != sha:
            raise ValueError('remote object mismatch')
    bundle_manifest = target / 'source-bundle' / 'manifest.json'
    for item in json.loads(bundle_manifest.read_text(encoding='utf-8'))['sources']:
        if item.get('status') == 'included':
            path = target / 'source-bundle' / 'objects' / item['sha256']
            regular(path)
    verify_bundle(target / 'source-bundle')
    subprocess.run([sys.executable, str(Path(__file__).with_name('unified_evidence_snapshot.py')),
                    'verify', '--output-dir', str(target / 'snapshot')],
                   check=True, capture_output=True)
    saved = target / 'transfer.json'
    if saved.exists() or saved.is_symlink():
        regular(saved)
        if json.loads(saved.read_text(encoding='utf-8')) != expected:
            raise ValueError('remote receipt mismatch')
    else:
        part = target / 'transfer.json.part'
        if part.exists() or part.is_symlink():
            raise ValueError('remote receipt mismatch')
        with part.open('x', encoding='utf-8') as output:
            os.chmod(part, 0o600)
            json.dump(expected, output, sort_keys=True, separators=(',', ':'))
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(part, saved)
    return json.loads(saved.read_text(encoding='utf-8'))


def ssh_call(ssh, host, remote_script, command, source=None, offset=0):
    remote = shlex.join(['python3', str(remote_script), 'remote-ssh', command])
    if source is None:
        process = subprocess.run([str(ssh), '-T', '--', host, remote], capture_output=True)
    else:
        with source.open('rb') as stream:
            stream.seek(offset)
            process = subprocess.run([str(ssh), '-T', '--', host, remote],
                                     stdin=stream, capture_output=True)
    if process.returncode:
        message = process.stderr.decode('utf-8', errors='replace').strip()
        allowed = ('remote object mismatch', 'remote partial mismatch', 'remote receipt mismatch',
                   'remote offset mismatch', 'remote protocol mismatch', 'unsafe remote staging')
        raise ValueError(message if message in allowed else 'SSH transport command failed')
    try:
        return json.loads(process.stdout)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError('invalid SSH transport response') from error


def ssh_stage(run_dir, host, root, remote_script, ssh):
    if not HOST.fullmatch(host) or str(host).startswith('-'):
        raise ValueError('invalid SSH host')
    if not root.is_absolute() or not remote_script.is_absolute() or any(c in str(root) + str(remote_script) for c in '\r\n\x00'):
        raise ValueError('remote paths must be absolute')
    local, snapshot, bundle, manifest = verified_input(run_dir)
    expected = receipt(local)
    key = local['snapshot_sha256'] + '-' + local['bundle_manifest_sha256']
    def call(payload, source=None, offset=0):
        return ssh_call(ssh, host, remote_script, json.dumps(payload, separators=(',', ':')), source, offset)
    if call({'op': 'hello'}) != {'protocol': PROTOCOL}:
        raise ValueError('remote protocol mismatch')
    copied = 0
    for source, rel, sha in file_plan(snapshot, bundle, manifest, local):
        spec = {'root': str(root), 'key': key, 'rel': rel, 'sha256': sha, 'size': source.stat().st_size}
        state = call({'op': 'state', **spec})
        if state['state'] == 'complete':
            continue
        offset = state['size']
        with source.open('rb') as stream:
            prefix = hashlib.sha256()
            remaining = offset
            while remaining:
                block = stream.read(min(BLOCK, remaining))
                if not block:
                    raise ValueError('remote partial mismatch')
                prefix.update(block)
                remaining -= len(block)
            if prefix.hexdigest() != state['sha256']:
                raise ValueError('remote partial mismatch')
        response = call({'op': 'put', **spec, 'offset': offset}, source, offset)
        if response != {'state': 'complete'}:
            raise ValueError('invalid SSH transport response')
        copied += source.stat().st_size - offset
    result = call({'op': 'finalize', 'root': str(root), 'key': key, 'receipt': expected})
    if result != expected:
        raise ValueError('remote receipt mismatch')
    return {**result, 'bytes_copied': copied}


def remote_command(payload):
    op = payload['op']
    if op == 'hello':
        return {'protocol': PROTOCOL}
    root = Path(payload['root'])
    if not root.is_absolute():
        raise ValueError('unsafe remote staging')
    key = payload['key']
    if op in ('state', 'put'):
        rel = payload['rel']
        sha = payload['sha256']
        size = payload['size']
        if not SHA.fullmatch(sha) or not isinstance(size, int) or size < 0:
            raise ValueError('unsafe remote staging')
        if op == 'state':
            return remote_state(root, key, rel, sha, size)
        remote_put(root, key, rel, sha, size, payload['offset'])
        return {'state': 'complete'}
    if op == 'finalize':
        if payload['receipt'] != receipt(payload['receipt']) or key != (payload['receipt']['snapshot_sha256'] + '-' + payload['receipt']['bundle_manifest_sha256']):
            raise ValueError('remote receipt mismatch')
        return remote_finalize(root, key, payload['receipt'])
    raise ValueError('remote protocol mismatch')
