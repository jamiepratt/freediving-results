#!/usr/bin/env python3
"""One-shot guidance question-local Jev comparison launcher. A durable start is irrevocable, even on failure."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import re

from jev_frozen_run import credential, save, sha, PACKET_HASHES

def validate(path):
    m = json.loads(Path(path).read_text())
    cwd, root, packet, original = (Path(m[k]) for k in ('cwd', 'root', 'packet', 'original_packet'))
    if not re.fullmatch('[0-9a-f]{64}', m['expected_run_id']):
        raise RuntimeError('Invalid expected run identity')
    for p in (cwd, root, packet, original):
        if not p.is_absolute() or not p.is_dir() or p.resolve() != p:
            raise RuntimeError('Paths must be existing absolute directories without symlinks')
    if root in (packet, cwd, original):
        raise RuntimeError('Separate output directory required')
    required = {Path(sys.executable).resolve(), Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java').resolve(), Path(__file__).resolve(), cwd / 'scripts/jev_guidance_runner.clj', cwd / 'scripts/jev_frozen_runner.clj', cwd / 'scripts/jev_frozen_run.py', cwd / 'deps.edn'}
    required.update(p.resolve() for base in ('src', 'resources') for p in (cwd / base).rglob('*') if p.is_file())
    required.update(original / name for name in PACKET_HASHES)
    required.update(packet / name for name in ('candidate-frozen.edn', 'requests.json'))
    for binary in ('clojure', 'security', 'op'):
        resolved = shutil.which(binary)
        if not resolved:
            raise RuntimeError('Required executable unavailable')
        required.add(Path(resolved).resolve())
    if type(m.get('max_http')) is not int or m['max_http'] != 34:
        raise RuntimeError('Invalid dispatch bound')
    rows = json.loads((packet / 'requests.json').read_text())
    if len(rows) != m['max_http'] or sum(len(row['case-ids']) for row in rows) != 162:
        raise RuntimeError('Manifest dispatch bound mismatch')
    files = m['files']
    if not required.issubset({Path(p) for p in files}):
        raise RuntimeError('Incomplete executable/input manifest')
    for name, expected in files.items():
        if not Path(name).is_absolute() or sha(name) != expected:
            raise RuntimeError('Manifest hash mismatch')
    for name, expected in PACKET_HASHES.items():
        if sha(original / name) != expected:
            raise RuntimeError('Frozen packet hash mismatch')
    return m

def run_clojure(m, mode, key=None):
    env = dict(os.environ)
    env.pop('OP_SERVICE_ACCOUNT_TOKEN', None)
    env['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'
    command = ['clojure', '-Sdeps', '{:paths ["src" "resources" "scripts"]}', '-M', '-m', 'jev-guidance-runner', mode, m['packet'], m['root'], m['original_packet'], m['expected_run_id']]
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
        save(start, {'run_id': m['expected_run_id'], 'manifest_sha256': sha(args.manifest), 'max_http': m['max_http'], 'max_questions': 162, 'attempts': 1, 'concurrency': 1, 'started_at': time.time()})
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
    print(json.dumps({'mode': args.mode, 'run_id': m['expected_run_id'], 'status': 'ok', 'prepared_objects_verified': m['max_http'], 'wire_hashes_verified': m['max_http'], 'original_cases_verified': 81}))
    return 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'status': 'stopped', 'exception_class': type(error).__name__, 'resend_forbidden': True}), file=sys.stderr)
        sys.exit(1)
