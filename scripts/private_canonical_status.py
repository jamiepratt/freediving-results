"""Read verified canonical scopes using the packaged, read-only JVM verifier."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

SOURCES = ('private_canonical_status', 'canonical_attempt_store', 'athlete_identity',
           'source_relationships', 'candidates', 'reconciliation_flow',
           'reconciliation_budget', 'reconciliation_jev', 'reconciliation_policy')
JARS = ('clojure-1.12.0.jar', 'data.json-2.5.1.jar', 'postgresql-42.7.8.jar',
        'core.specs.alpha-0.4.74.jar', 'spec.alpha-0.5.238.jar')
RUNTIME_FILES = frozenset(['src/freediving/' + name + '.clj' for name in SOURCES]
                          + ['lib/' + name for name in JARS])


def create_reader(env, *, runtime=None):
    name = env.get('OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG')
    if not name:
        return None
    path = Path(name)
    def config():
        info = path.lstat()
        if (path.is_symlink() or not path.is_file() or info.st_uid not in (0, os.geteuid())
                or info.st_mode & 0o027):
            raise ValueError('private canonical configuration permissions invalid')
        return json.loads(path.read_text())
    config()

    def read():
        data = config()
        if (set(data) != {'jdbc_url', 'database', 'snapshot_sha256', 'exports',
                         'runtime_path', 'runtime_manifest_sha256'}
                or not isinstance(data['jdbc_url'], str)
                or not data['jdbc_url'].startswith('jdbc:postgresql://127.0.0.1:')
                or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', data['database'])
                or not re.fullmatch(r'[0-9a-f]{64}', data['snapshot_sha256'])
                or not isinstance(data['exports'], dict)
                or set(data['exports']) - {'identity', 'same_attempt'}):
            raise ValueError('invalid private canonical configuration')
        exports = {}
        for scope, spec in data['exports'].items():
            if set(spec) != {'path', 'sha256'} or not re.fullmatch(r'[0-9a-f]{64}', spec['sha256']):
                raise ValueError('invalid canonical export pin')
            source = Path(spec['path'])
            if source.is_symlink() or not source.is_file() or source.stat().st_size > 8 * 1024 * 1024:
                raise ValueError('canonical export changed')
            body = source.read_bytes()
            if hashlib.sha256(body).hexdigest() != spec['sha256']:
                raise ValueError('canonical export changed')
            exports[scope] = {'sha256': spec['sha256'], 'export': json.loads(body)}
        selected_runtime = Path(runtime or data['runtime_path'])
        manifest = selected_runtime / 'manifest.json'
        if (selected_runtime.is_symlink() or manifest.is_symlink()
                or hashlib.sha256(manifest.read_bytes()).hexdigest() != data['runtime_manifest_sha256']):
            raise ValueError('canonical verifier runtime changed')
        files = json.loads(manifest.read_text())['files']
        if set(files) != RUNTIME_FILES:
            raise ValueError('canonical verifier runtime changed')
        for relative, digest in files.items():
            member = selected_runtime / relative
            if (Path(relative).is_absolute() or '..' in Path(relative).parts
                    or member.is_symlink() or not member.is_file()
                    or hashlib.sha256(member.read_bytes()).hexdigest() != digest):
                raise ValueError('canonical verifier runtime changed')
        command = ['/usr/bin/java', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp',
                   str(selected_runtime / 'src') + os.pathsep +
                   os.pathsep.join(str(selected_runtime / 'lib' / jar) for jar in JARS),
                   'clojure.main', '-m', 'freediving.private-canonical-status']
        result = subprocess.run(command, input=json.dumps({**data, 'exports': exports}),
                                text=True, capture_output=True, timeout=60,
                                env={key: value for key, value in os.environ.items()
                                     if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')})
        if result.returncode or len(result.stdout) > 4096:
            raise ValueError('canonical readback unavailable')
        value = json.loads(result.stdout)
        if (value.get('schema') != 'private-canonical-status-readback/v1'
                or value.get('snapshot_sha256') != data['snapshot_sha256']):
            raise ValueError('canonical readback binding changed')
        return value
    return read
