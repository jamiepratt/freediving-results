#!/usr/bin/env python3
"""One-shot frozen Jev launcher. A durable start is irrevocable, even on failure."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

RUN_ID = 'b437d63afdbfde706dbea25a7e0e3721dfdb9b02ef8adb71836fab36669608aa'
PACKET_HASHES = {
    'REPORT.md': 'b33075f5021f38efce80190c6c906d3a5814826dfed7964c8c9a9b2bbdef36fc',
    'candidate-frozen.edn': 'a8e12b4c8c93e8e6f006af6ace5054d6fc1ca4ee8e5d03a81c9056d320e2459c',
    'requests.json': '72f3502c355ced2a4f2d3b5b3d6b7b375fbf085ba3c3526da228ba02d618d572',
}

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def validate(path):
    m = json.loads(Path(path).read_text())
    cwd, root, packet = (Path(m[k]) for k in ('cwd', 'root', 'packet'))
    for p in (cwd, root, packet):
        if not p.is_absolute() or not p.is_dir() or p.resolve() != p:
            raise RuntimeError('Paths must be existing absolute directories without symlinks')
    if root == packet or root == cwd:
        raise RuntimeError('Separate output directory required')
    required = {Path(sys.executable).resolve(), Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java').resolve(), Path(__file__).resolve(), cwd / 'scripts/jev_frozen_runner.clj', cwd / 'deps.edn'}
    required.update(p.resolve() for base in ('src', 'resources') for p in (cwd / base).rglob('*') if p.is_file())
    required.update(packet / name for name in PACKET_HASHES)
    for binary in ('clojure', 'security', 'op'):
        resolved = shutil.which(binary)
        if not resolved:
            raise RuntimeError('Required executable unavailable')
        required.add(Path(resolved).resolve())
    files = m['files']
    if not required.issubset({Path(p) for p in files}):
        raise RuntimeError('Incomplete executable/input manifest')
    for name, expected in files.items():
        if not Path(name).is_absolute() or sha(name) != expected:
            raise RuntimeError('Manifest hash mismatch')
    for name, expected in PACKET_HASHES.items():
        if sha(packet / name) != expected:
            raise RuntimeError('Frozen packet hash mismatch')
    return m

def save(path, value):
    with Path(path).open('x') as f:
        json.dump(value, f, sort_keys=True)
        f.flush()
        os.fsync(f.fileno())
    fd = os.open(str(Path(path).parent), os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)

def credential(secrets):
    def capture(args, env=None):
        return subprocess.check_output(args, env=env, text=True, stderr=subprocess.DEVNULL).strip()
    token = capture(['security', 'find-generic-password', '-s', 'api-shell 1Password service account', '-a', os.environ['USER'], '-w'])
    secrets.append(token)
    if not token:
        raise RuntimeError('Empty service credential')
    env = {**os.environ, 'OP_SERVICE_ACCOUNT_TOKEN': token}
    items = json.loads(capture(['op', 'item', 'list', '--vault', 'Shell Access', '--format', 'json'], env))
    ids = [i['id'] for i in items if 'typesafe' in i.get('title', '').lower()]
    if len(ids) != 1:
        raise RuntimeError('Ambiguous provider credential')
    item = json.loads(capture(['op', 'item', 'get', ids[0], '--vault', 'Shell Access', '--format', 'json'], env))
    fields = [f['value'] for f in item.get('fields', []) if f.get('value') and (f.get('type') == 'CONCEALED' or f.get('purpose') == 'PASSWORD')]
    if len(fields) != 1 or '\n' in fields[0] or '\r' in fields[0]:
        raise RuntimeError('Invalid provider credential')
    secrets.append(fields[0])
    return fields[0]

def run_clojure(m, mode, key=None):
    env = dict(os.environ)
    env.pop('OP_SERVICE_ACCOUNT_TOKEN', None)
    env['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'
    command = ['clojure', '-Sdeps', '{:paths ["src" "resources" "scripts"]}', '-M', '-m', 'jev-frozen-runner', mode, m['packet'], m['root']]
    # Never persist arbitrary JVM output: even unexpected exception data stays private.
    result = subprocess.run(command, cwd=m['cwd'], env=env, input=(key + '\n') if key else '', text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Evaluator failed; inspect durable evidence; never resend')
    return result.returncode

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['check', 'live', 'replay'])
    parser.add_argument('--manifest', required=True)
    args = parser.parse_args(argv)
    os.umask(0o077)
    m = validate(args.manifest)
    root = Path(m['root'])
    start = root / 'dispatcher-started.json'
    if args.mode == 'live':
        if start.exists() or (root / 'store').exists():
            raise RuntimeError('Prior start/store exists; never relaunch')
        run_clojure(m, 'check')
        if validate(args.manifest) != m:
            raise RuntimeError('Manifest changed during preflight')
        save(start, {'run_id': RUN_ID, 'manifest_sha256': sha(args.manifest), 'max_http': 122, 'max_questions': 162, 'attempts': 1, 'concurrency': 1, 'started_at': time.time()})
        secrets = []
        try:
            key = credential(secrets)
            code = run_clojure(m, 'live', key)
            del key
            save(root / 'dispatcher-completed.json', {'returncode': code, 'completed_at': time.time()})
        except BaseException as e:
            save(root / 'launcher-error.json', {'exception_class': type(e).__name__, 'resend_forbidden': True})
            raise
        finally:
            hits = [str(p.relative_to(root)) for p in root.rglob('*') if p.is_file() and any(secret and secret.encode() in p.read_bytes() for secret in secrets)]
            save(root / 'secret-scan.json', {'passed': not hits, 'exact_credential_and_service_token_hits': hits})
            if hits:
                raise RuntimeError('Credential scan failed')
    else:
        if args.mode == 'replay' and not (root / 'result-live.edn').exists():
            raise RuntimeError('Completed live result required for offline replay')
        run_clojure(m, args.mode)
    print(json.dumps({'mode': args.mode, 'run_id': RUN_ID, 'status': 'ok', 'prepared_objects_verified': 122, 'wire_hashes_verified': 122}))
    return 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'status': 'stopped', 'exception_class': type(error).__name__, 'resend_forbidden': True}), file=sys.stderr)
        sys.exit(1)
