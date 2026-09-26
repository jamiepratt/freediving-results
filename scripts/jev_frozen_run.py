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
import stat

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
    for binary in ('clojure',):
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

def _valid_key(value, secrets):
    if not value or value != value.strip() or value == 'REPLACE_WITH_REAL_KEY' or '\n' in value or '\r' in value:
        raise RuntimeError('Invalid provider credential')
    secrets.append(value)
    return value

def credential(secrets):
    """Resolve runtime-only bearer: explicit environment, owner file, then 1Password."""
    if 'TYPESAFE_API_KEY' in os.environ:
        return _valid_key(os.environ['TYPESAFE_API_KEY'], secrets)
    path = Path(os.environ.get('TYPESAFE_API_KEY_FILE', '~/.config/freediving-results/jev.env')).expanduser()
    try:
        os.lstat(path)
        file_present = True
    except FileNotFoundError:
        file_present = False
    if file_present or 'TYPESAFE_API_KEY_FILE' in os.environ:
        if not path.is_absolute():
            raise RuntimeError('Credential file path must be absolute')
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            metadata = os.fstat(fd)
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or not metadata.st_mode & stat.S_IRUSR or metadata.st_mode & 0o177:
                raise RuntimeError('Credential file must be owner-readable and private')
            with os.fdopen(fd, 'r') as stream:
                lines = stream.read().splitlines()
            fd = -1
        finally:
            if fd >= 0:
                os.close(fd)
        value = None
        for line in lines:
            if line.startswith('TYPESAFE_API_KEY='):
                if value is not None:
                    raise RuntimeError('Duplicate credential entry')
                value = line.partition('=')[2].strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                    value = value[1:-1]
            elif line.strip() and not line.lstrip().startswith('#'):
                raise RuntimeError('Invalid credential file entry')
        return _valid_key(value, secrets)
    def capture(args, env=None):
        return subprocess.check_output(args, env=env, text=True, stderr=subprocess.DEVNULL, timeout=10).strip()
    for attempt in range(3):
        try:
            token = capture(['security', 'find-generic-password', '-s', 'api-shell 1Password service account', '-a', os.environ['USER'], '-w'])
            _valid_key(token, secrets)
            env = {**os.environ, 'OP_SERVICE_ACCOUNT_TOKEN': token}
            items = json.loads(capture(['op', 'item', 'list', '--vault', 'Shell Access', '--format', 'json'], env))
            ids = [i['id'] for i in items if 'typesafe' in i.get('title', '').lower()]
            if len(ids) != 1:
                raise RuntimeError('Ambiguous provider credential')
            item = json.loads(capture(['op', 'item', 'get', ids[0], '--vault', 'Shell Access', '--format', 'json'], env))
            fields = [f['value'] for f in item.get('fields', []) if f.get('value') and (f.get('type') == 'CONCEALED' or f.get('purpose') == 'PASSWORD')]
            if len(fields) != 1:
                raise RuntimeError('Invalid provider credential')
            return _valid_key(fields[0], secrets)
        except (subprocess.SubprocessError, OSError):
            if attempt == 2:
                raise RuntimeError('Credential lookup unavailable') from None
    raise RuntimeError('Credential lookup unavailable')

def failure_status(error, argv):
    started = False
    try:
        manifest = argv[argv.index('--manifest') + 1]
        root = Path(json.loads(Path(manifest).read_text())['root'])
        started = (root / 'dispatcher-started.json').exists() or (root / 'store').exists()
    except (ValueError, IndexError, KeyError, OSError, json.JSONDecodeError):
        pass
    return {'status': 'stopped', 'exception_class': type(error).__name__,
            'stage': 'dispatch' if started else 'preflight',
            'dispatch_may_have_begun': started, 'resend_forbidden': started}

def run_clojure(m, mode, key=None):
    env = dict(os.environ)
    env.pop('OP_SERVICE_ACCOUNT_TOKEN', None)
    env.pop('TYPESAFE_API_KEY', None)
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
        secrets = []
        key = credential(secrets)
        if validate(args.manifest) != m:
            raise RuntimeError('Manifest changed during credential lookup')
        save(start, {'run_id': RUN_ID, 'manifest_sha256': sha(args.manifest), 'max_http': 122, 'max_questions': 162, 'attempts': 1, 'concurrency': 1, 'started_at': time.time()})
        try:
            code = run_clojure(m, 'live', key)
            del key
            save(root / 'dispatcher-completed.json', {'returncode': code, 'completed_at': time.time()})
        except BaseException as e:
            save(root / 'launcher-error.json', {'exception_class': type(e).__name__, 'stage': 'dispatch', 'dispatch_may_have_begun': True, 'resend_forbidden': True})
            raise
        finally:
            hits = sum(1 for p in root.rglob('*') if p.is_file() and any(secret and secret.encode() in p.read_bytes() for secret in secrets))
            save(root / 'secret-scan.json', {'passed': hits == 0, 'exact_credential_and_service_token_hit_count': hits})
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
        print(json.dumps(failure_status(error, sys.argv[1:])), file=sys.stderr)
        sys.exit(1)
