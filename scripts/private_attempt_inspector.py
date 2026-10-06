"""Read private retained attempts with the pinned JVM comparison contracts."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone

SOURCES = ('private_attempt_inspector', 'attempt_view_adapter', 'attempt_comparison',
           'comparison_score', 'peer_scope', 'represented_geography')
JARS = ('clojure-1.12.0.jar', 'data.json-2.5.1.jar',
        'core.specs.alpha-0.4.74.jar', 'spec.alpha-0.5.238.jar')
RUNTIME_FILES = frozenset(['src/freediving/' + name + '.clj' for name in SOURCES]
                          + ['lib/' + name for name in JARS])
HASH = re.compile(r'[0-9a-f]{64}\Z')


def _no_links(path):
    path = Path(path).absolute()
    if any(member.is_symlink() for member in (path, *path.parents)):
        raise ValueError('private comparison permissions reject symlink paths')


def _private_bytes(path, maximum=32 * 1024 * 1024):
    path = Path(path)
    _no_links(path)
    info = path.lstat()
    if (path.is_symlink() or not path.is_file() or info.st_uid not in (0, os.geteuid())
            or info.st_mode & 0o027 or info.st_size > maximum):
        raise ValueError('private comparison permissions invalid')
    return path.read_bytes()


def _pinned(spec):
    if (not isinstance(spec, dict) or set(spec) != {'path', 'sha256'}
            or not isinstance(spec['sha256'], str) or not HASH.fullmatch(spec['sha256'])):
        raise ValueError('invalid private comparison pin')
    body = _private_bytes(spec['path'])
    if hashlib.sha256(body).hexdigest() != spec['sha256']:
        raise ValueError('private comparison evidence changed')
    return body


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     ensure_ascii=False).encode()).hexdigest()


def _authority(spec, packet_hash, current, now):
    if spec is None:
        return '{:status :absent :reason "No pinned exact-row sporting evidence"}'
    data = json.loads(_pinned(spec))
    if (set(data) != {'schema', 'packet_sha256', 'authority_sha256', 'valid_until', 'rows_edn'}
            or data['schema'] != 'private-comparison-authority/v1'
            or not all(isinstance(data[k], str) for k in data)
            or not HASH.fullmatch(data['packet_sha256']) or not HASH.fullmatch(data['authority_sha256'])
            or not data['rows_edn'].strip().startswith('[')):
        raise ValueError('invalid exact-row sporting evidence')
    try:
        expires = datetime.fromisoformat(data['valid_until'].replace('Z', '+00:00'))
        if expires.tzinfo is None:
            raise ValueError('missing timezone')
    except (ValueError, TypeError):
        raise ValueError('invalid sporting evidence expiry') from None
    if (current is None or data['packet_sha256'] != packet_hash
            or data['authority_sha256'] != _digest(current) or now >= expires):
        return '{:status :stale :reason "Exact-row authority absent, changed or expired"}'
    return '{:status :current :reason "Pinned current exact-row evidence" :rows ' + data['rows_edn'] + '}'


def create_reader(env, *, runtime=None, clock=None):
    name = env.get('OWNER_EVIDENCE_COMPARISON_CONFIG')
    if not name:
        return None
    path = Path(name)
    def config():
        data = json.loads(_private_bytes(path, 65536))
        if (set(data) - {'source_objects'} != {'packet', 'runtime_path', 'runtime_manifest_sha256', 'authority_evidence'}
                or not isinstance(data['runtime_manifest_sha256'], str)
                or not HASH.fullmatch(data['runtime_manifest_sha256'])
                or not isinstance(data['runtime_path'], str)):
            raise ValueError('invalid private comparison configuration')
        sources = data.get('source_objects', {})
        if (not isinstance(sources, dict) or len(sources) > 1
                or any(not isinstance(digest, str) or not HASH.fullmatch(digest)
                       or not isinstance(source, dict) or set(source) != {'path', 'sha256', 'mime_type'}
                       or source['sha256'] != digest or source['mime_type'] != 'application/pdf'
                       or not isinstance(source['path'], str) for digest, source in sources.items())):
            raise ValueError('invalid private CMAS source configuration')
        return data
    config()

    def read(filters=None, authority=None):
        data = config()
        packet = _pinned(data['packet'])
        selected_runtime = Path(runtime or data['runtime_path'])
        manifest = selected_runtime / 'manifest.json'
        _no_links(manifest)
        if (selected_runtime.is_symlink() or manifest.is_symlink()
                or hashlib.sha256(manifest.read_bytes()).hexdigest() != data['runtime_manifest_sha256']):
            raise ValueError('comparison runtime changed')
        files = json.loads(manifest.read_text())['files']
        if set(files) != RUNTIME_FILES:
            raise ValueError('comparison runtime changed')
        for relative, digest in files.items():
            member = selected_runtime / relative
            _no_links(member)
            if (Path(relative).is_absolute() or '..' in Path(relative).parts
                    or member.is_symlink() or not member.is_file()
                    or hashlib.sha256(member.read_bytes()).hexdigest() != digest):
                raise ValueError('comparison runtime changed')
        bound_authority = _authority(data['authority_evidence'], data['packet']['sha256'], authority,
                                     clock() if clock else datetime.now(timezone.utc))
        command = ['/usr/bin/java', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp',
                   str(selected_runtime / 'src') + os.pathsep +
                   os.pathsep.join(str(selected_runtime / 'lib' / jar) for jar in JARS),
                   'clojure.main', '-m', 'freediving.private-attempt-inspector']
        result = subprocess.run(command, input=json.dumps({'packet_edn': packet.decode('utf-8'),
                                'filters': filters or {}, 'authority_edn': bound_authority}),
                                text=True, capture_output=True, timeout=60,
                                env={key: value for key, value in os.environ.items()
                                     if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')})
        if result.returncode or len(result.stdout) > 4 * 1024 * 1024:
            raise ValueError('private comparison readback unavailable')
        value = json.loads(result.stdout)
        if value.get('schema') != 'private-attempt-inspector/v1':
            raise ValueError('private comparison readback invalid')
        return value

    def source_available(row):
        """Metadata hint only. Every opening re-verifies the exact row and pinned bytes."""
        if not isinstance(row, dict) or row.get('federation') != 'CMAS':
            return False
        reference = row.get('reference', {})
        source = config().get('source_objects', {}).get(reference.get('source-sha256'))
        return bool(source and row.get('source_id') == reference.get('source-sha256'))

    def source_bytes(row):
        if not isinstance(row, dict) or row.get('federation') != 'CMAS':
            raise ValueError('AIDA original source access is restricted')
        fresh = read({'federation': 'CMAS', 'limit': 200}, None)
        if not any(item['reference'] == row.get('reference')
                   and item['row_coordinate'] == row.get('row_coordinate')
                   and item['source_id'] == row.get('source_id') for item in fresh['rows']):
            raise ValueError('private source exact row binding changed')
        source_sha = row['reference']['source-sha256']
        source = config().get('source_objects', {}).get(source_sha)
        if source is None:
            raise ValueError('private CMAS source unavailable')
        body = _pinned({'path': source['path'], 'sha256': source_sha})
        if not body.startswith(b'%PDF-'):
            raise ValueError('private CMAS source format changed')
        return body

    read.source_available = source_available
    read.source_bytes = source_bytes
    return read
