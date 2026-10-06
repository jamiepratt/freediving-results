"""Read private retained attempts with the pinned JVM comparison contracts."""
import hashlib
import atexit
import json
import logging
import os
from pathlib import Path
import re
import selectors
import subprocess
import threading
import time
import uuid
from collections import deque
from datetime import datetime, timezone

SOURCES = ('private_attempt_inspector', 'attempt_view_adapter', 'attempt_comparison',
           'comparison_score', 'peer_scope', 'represented_geography')
JARS = ('clojure-1.12.0.jar', 'data.json-2.5.1.jar',
        'core.specs.alpha-0.4.74.jar', 'spec.alpha-0.5.238.jar')
RUNTIME_FILES = frozenset(['src/freediving/' + name + '.clj' for name in SOURCES]
                          + ['lib/' + name for name in JARS])
HASH = re.compile(r'[0-9a-f]{64}\Z')
READ_BUDGET_SECONDS = 12
MAX_RESPONSE = 4 * 1024 * 1024


class ComparisonBusy(ValueError):
    """Bounded admission full; the caller can retry."""


class ComparisonTimeout(ValueError):
    """The entire read exhausted its browser request budget."""


def _remaining(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ComparisonTimeout('private comparison request deadline expired; retry')
    return remaining


class _Runtime:
    """One serial JVM and bounded waiters, no background pipe threads."""
    def __init__(self, schema='private-attempt-inspector/v1', maximum=MAX_RESPONSE, max_waiters=3):
        if not isinstance(max_waiters, int) or not 0 <= max_waiters <= 3:
            raise ValueError('invalid private runtime admission bound')
        self.max_waiters = max_waiters
        self.schema = schema
        self.maximum = maximum
        self.condition = threading.Condition()
        self.process_lock = threading.Lock()
        self.active = False
        self.waiting = 0
        self.closed = False
        self.process = None
        self.command = None
        self.generation = None
        self.events = deque(maxlen=32)
        atexit.register(self.close)

    def _stop(self):
        with self.process_lock:
            process, self.process = self.process, None
            if process is not None:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=0.25)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=1)
                process.stdin.close()
                process.stdout.close()

    def close(self):
        atexit.unregister(self.close)
        with self.condition:
            self.closed = True
            self.condition.notify_all()
        self._stop()

    def exchange(self, command, value, deadline, generation=None):
        timing = {'started': time.monotonic(), 'admitted': None}
        outcome = 'success'
        try:
            return self._exchange(command, value, deadline, timing, generation)
        except ComparisonBusy:
            outcome = 'overload'
            raise
        except ComparisonTimeout:
            outcome = 'timeout'
            raise
        except Exception:
            outcome = 'cancelled' if self.closed else 'rejected'
            raise
        finally:
            ended = time.monotonic()
            admitted = timing['admitted'] or ended
            event = {'correlation': uuid.uuid4().hex, 'outcome': outcome,
                     'queue_ms': round((admitted - timing['started']) * 1000, 2),
                     'execution_ms': round((ended - admitted) * 1000, 2)}
            with self.condition:
                self.events.append(event)
            logging.getLogger(__name__).info(json.dumps(event, sort_keys=True))

    def diagnostics(self):
        with self.condition:
            return [dict(event) for event in self.events]

    def _exchange(self, command, value, deadline, timing, generation):
        with self.condition:
            if self.closed:
                raise ValueError('private comparison reader closed')
            if self.active:
                if self.waiting >= self.max_waiters:
                    raise ComparisonBusy('private comparison busy; retry')
                self.waiting += 1
                try:
                    while self.active and not self.closed:
                        self.condition.wait(_remaining(deadline))
                    if self.closed:
                        raise ValueError('private comparison reader closed')
                finally:
                    self.waiting -= 1
            _remaining(deadline)
            self.active = True
            timing['admitted'] = time.monotonic()
        try:
            if self.command != command or self.generation != generation:
                self._stop()
            with self.process_lock:
                if self.closed:
                    raise ValueError('private comparison reader closed')
                if self.process is None:
                    self.process = subprocess.Popen(command, stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                        env={key: value for key, value in os.environ.items()
                             if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')})
                    self.command = command
                    self.generation = generation
                    os.set_blocking(self.process.stdin.fileno(), False)
                    os.set_blocking(self.process.stdout.fileno(), False)
                process = self.process
            pending = memoryview(json.dumps(value).encode() + b'\n')
            output = bytearray()
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdin, selectors.EVENT_WRITE)
                selector.register(process.stdout, selectors.EVENT_READ)
                while True:
                    events = selector.select(_remaining(deadline))
                    if not events:
                        _remaining(deadline)
                    for key, _ in events:
                        if key.fileobj is process.stdin:
                            count = os.write(process.stdin.fileno(), pending[:65536])
                            pending = pending[count:]
                            if not pending:
                                selector.unregister(process.stdin)
                        else:
                            body = os.read(process.stdout.fileno(), 65536)
                            if not body:
                                raise ValueError('private comparison readback unavailable')
                            output.extend(body)
                            if len(output) > self.maximum:
                                raise ValueError('private comparison response exceeds bound')
                            if b'\n' in output:
                                if pending or output.count(b'\n') != 1 or not output.endswith(b'\n'):
                                    raise ValueError('private comparison readback invalid')
                                result = json.loads(output)
                                if not isinstance(result, dict) or result.get('schema') != self.schema:
                                    raise ValueError('private comparison readback invalid')
                                return result
        except Exception:
            self._stop()
            raise
        finally:
            with self.condition:
                self.active = False
                self.condition.notify_all()


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
    engine = _Runtime()

    def verified(authority):
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
        return data, packet, selected_runtime, bound_authority

    def verify_current(authority):
        data, _, _, bound = verified(authority)
        return _digest([data, bound])

    def read(filters=None, authority=None, *, deadline=None):
        deadline = min(deadline if deadline is not None else float('inf'),
                       time.monotonic() + READ_BUDGET_SECONDS)
        _remaining(deadline)
        data, packet, selected_runtime, bound_authority = verified(authority)
        command = ['/usr/bin/java', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp',
                   str(selected_runtime / 'src') + os.pathsep +
                   os.pathsep.join(str(selected_runtime / 'lib' / jar) for jar in JARS),
                   'clojure.main', '-m', 'freediving.private-attempt-inspector', 'serve']
        value = engine.exchange(command, {'packet_edn': packet.decode('utf-8'),
                                'filters': filters or {}, 'authority_edn': bound_authority}, deadline,
                                data['runtime_manifest_sha256'])
        fresh_data, _, _, fresh_bound = verified(authority)
        if data != fresh_data or bound_authority != fresh_bound:
            raise ValueError('private comparison authority or pins changed during read; retry')
        _remaining(deadline)
        return value

    def source_available(row):
        """Metadata hint only. Every opening re-verifies the exact row and pinned bytes."""
        if not isinstance(row, dict) or row.get('federation') != 'CMAS':
            return False
        reference = row.get('reference', {})
        source = config().get('source_objects', {}).get(reference.get('source-sha256'))
        return bool(source and row.get('source_id') == reference.get('source-sha256'))

    def source_bytes(row, *, deadline=None):
        deadline = min(deadline if deadline is not None else float('inf'),
                       time.monotonic() + READ_BUDGET_SECONDS)
        before = verify_current(None)
        if not isinstance(row, dict) or row.get('federation') != 'CMAS':
            raise ValueError('AIDA original source access is restricted')
        fresh = read({'federation': 'CMAS', 'limit': 200}, None, deadline=deadline)
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
        if before != verify_current(None):
            raise ValueError('private source exact row binding changed')
        _remaining(deadline)
        return body

    read.source_available = source_available
    read.source_bytes = source_bytes
    read.verify_current = verify_current
    read.close = engine.close
    read.deadline_supported = True
    read.diagnostics = engine.diagnostics
    return read
