"""Small, path-free local checkpoint mirror for the private owner origin."""
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def _atomic_json(path, value):
    fd, name = tempfile.mkstemp(prefix='.status-', dir=path.parent)
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

HASH = re.compile(r'[a-f0-9]{64}\Z')
RUN = re.compile(r'[A-Za-z0-9_-]{1,128}\Z')


class StatusConflict(ValueError):
    pass


def _hash(value):
    return value is None or isinstance(value, str) and HASH.fullmatch(value)


def _cutoff(value):
    if not isinstance(value, str) or not value.endswith('Z') or len(value) > 32:
        return False
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00')).tzinfo == timezone.utc
    except ValueError:
        return False


class PrivatePresentationStatus:
    def __init__(self, path):
        self.path = Path(path).resolve()
        if self.path.is_file():
            self.current = json.loads(self.path.read_text(encoding='utf-8'))
            self._validate(self.current, stored=True)
        else:
            self.current = None

    def read(self, active):
        if self.current is None:
            return {'status': 'unavailable', 'remote': {'active': active}}
        return {**self.current, 'remote': {**self.current['remote'], 'active': active}}

    def _validate(self, data, stored=False):
        keys = {'schema', 'run_id', 'revision', 'local', 'remote'}
        if not stored:
            keys.add('expected_revision')
        if not isinstance(data, dict) or set(data) != keys or data['schema'] != 'private-presentation-status/v1':
            raise ValueError('invalid status fields')
        if not isinstance(data['run_id'], str) or not RUN.fullmatch(data['run_id']):
            raise ValueError('invalid run ID')
        if type(data['revision']) is not int or not 1 <= data['revision'] < 2**53:
            raise ValueError('invalid revision')
        if not stored and (type(data['expected_revision']) is not int or data['expected_revision'] < 0):
            raise ValueError('invalid expected revision')
        local = data['local']
        if not isinstance(local, dict) or set(local) != {'snapshot_sha256', 'cutoff', 'gap_count'} or not _hash(local['snapshot_sha256']) or local['snapshot_sha256'] is None or not _cutoff(local['cutoff']) or type(local['gap_count']) is not int or not 0 <= local['gap_count'] <= 100000:
            raise ValueError('invalid local status')
        remote = data['remote']
        if not isinstance(remote, dict) or set(remote) != {'status', 'pending', 'failed', 'active'} or remote['status'] not in ('pending', 'failed', 'active') or any(not _hash(remote[key]) for key in ('pending', 'failed')):
            raise ValueError('invalid remote status')
        active = remote['active']
        if active is not None and (not isinstance(active, dict) or set(active) != {'snapshot_sha256', 'bundle_manifest_sha256'} or not all(isinstance(value, str) and HASH.fullmatch(value) for value in active.values())):
            raise ValueError('invalid active binding')
        if remote['pending'] not in (None, local['snapshot_sha256']) or remote['failed'] not in (None, local['snapshot_sha256']):
            raise StatusConflict('status snapshot binding mismatch')

    def update(self, data, active):
        self._validate(data)
        if data['remote']['active'] is not None and data['remote']['active'] != active:
            raise StatusConflict('active binding mismatch')
        if data['remote']['status'] == 'active' and (active is None or
                active.get('bundle_manifest_sha256') is None or
                data['local']['snapshot_sha256'] != active['snapshot_sha256'] or
                data['remote']['pending'] is not None or data['remote']['failed'] is not None):
            raise StatusConflict('unverified active status')
        candidate = {key: value for key, value in data.items() if key != 'expected_revision'}
        current = self.current
        if current == candidate:
            return self.read(active)
        revision = current['revision'] if current else 0
        if data['expected_revision'] != revision or data['revision'] != revision + 1:
            raise StatusConflict('status revision conflict')
        if current:
            if data['run_id'] == current['run_id']:
                if data['local'] != current['local']:
                    raise StatusConflict('run binding mismatch')
            elif data['local']['cutoff'] <= current['local']['cutoff']:
                raise StatusConflict('stale run cutoff')
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        _atomic_json(self.path, candidate)
        self.current = candidate
        return self.read(active)
