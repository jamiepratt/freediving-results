"""Small, path-free local checkpoint mirror for the private owner origin."""
import json
import hashlib
import fcntl
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


def status_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     ensure_ascii=False).encode('utf-8')).hexdigest()


def validate_authority(value):
    keys = {'owner_store_revision', 'snapshot_sha256', 'signed_owner_feed_sha256',
            'signed_owner_feed_hmac', 'owner_metrics', 'canonical'}
    if (not isinstance(value, dict) or set(value) != keys
            or type(value['owner_store_revision']) is not int or not 0 <= value['owner_store_revision'] < 2**53
            or any(not isinstance(value[key], str) or not HASH.fullmatch(value[key])
                   for key in ('snapshot_sha256', 'signed_owner_feed_sha256', 'signed_owner_feed_hmac'))
            or not isinstance(value['owner_metrics'], dict)
            or set(value['owner_metrics']) - {'pending', 'human_approved', 'human_corrected',
                'automatic_approved', 'rejected', 'reversed', 'invalidated', 'projection_pending'}
            or any(type(count) is not int or not 0 <= count <= 1000000
                   for count in value['owner_metrics'].values())
            or not isinstance(value['canonical'], dict)
            or set(value['canonical']) != {'identity', 'same_attempt'}):
        raise ValueError('invalid authoritative status')
    for scope in value['canonical'].values():
        if (not isinstance(scope, dict) or set(scope) != {'status', 'revision',
                'owner_event_revision', 'export_sha256', 'accepted_count', 'evidence_sha256'}
                or scope['status'] not in ('verified', 'stale', 'unknown')
                or any(scope[key] is not None and (type(scope[key]) is not int or
                       not 0 <= scope[key] < 2**53) for key in
                       ('revision', 'owner_event_revision', 'accepted_count'))
                or any(not _hash(scope[key]) for key in ('export_sha256', 'evidence_sha256'))
                or scope['status'] == 'verified' and any(scope[key] is None for key in
                       ('revision', 'owner_event_revision', 'export_sha256', 'evidence_sha256'))):
            raise ValueError('invalid canonical status scope')


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

    def _write(self, candidate):
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        lock = self.path.with_name(self.path.name + '.lock')
        fd = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            durable = json.loads(self.path.read_text(encoding='utf-8')) if self.path.is_file() else None
            if durable != self.current:
                raise StatusConflict('durable status revision changed')
            _atomic_json(self.path, candidate)
            self.current = candidate
        finally:
            os.close(fd)

    def read(self, active, *, owner_revision=None, owner_snapshot=None,
             include_stale_checkpoint=False, authority=None):
        if self.current is None:
            return {'status': 'unavailable', 'remote': {'active': active}}
        if self.current['schema'] == 'private-presentation-status/v4':
            if authority != self.current['authority'] or active != self.current['remote']['active']:
                stale = {'status': 'stale', 'remote': {'active': active},
                         'reason': 'owner decision, canonical scope or active snapshot changed'}
                if include_stale_checkpoint:
                    stale.update(schema=self.current['schema'], revision=self.current['revision'],
                                 run_id=self.current['run_id'], cutoff=self.current['local']['cutoff'])
                return stale
        elif self.current['schema'] in ('private-presentation-status/v2', 'private-presentation-status/v3'):
            binding = (self.current['reconciliation'] if self.current['schema'].endswith('/v2')
                       else self.current['application'])
            if (owner_revision != binding['owner_store_revision'] or
                    owner_snapshot != binding['snapshot_sha256'] or
                    (active if active and active.get('bundle_manifest_sha256') else None) != self.current['remote']['active']):
                stale = {'status': 'stale', 'remote': {'active': active},
                         'reason': 'owner decision or active snapshot changed'}
                if include_stale_checkpoint:
                    stale.update({'schema': self.current['schema'],
                                  'revision': self.current['revision'],
                                  'run_id': self.current['run_id'],
                                  'cutoff': self.current['local']['cutoff']})
                return stale
        return {**self.current, 'remote': {**self.current['remote'], 'active': active}}

    def _validate(self, data, stored=False):
        keys = {'schema', 'run_id', 'revision', 'local', 'remote'}
        if isinstance(data, dict) and data.get('schema') == 'private-presentation-status/v2':
            keys.add('reconciliation')
        if isinstance(data, dict) and data.get('schema') == 'private-presentation-status/v3':
            keys.add('application')
        if isinstance(data, dict) and data.get('schema') == 'private-presentation-status/v4':
            keys.update(('authority', 'historical'))
        if not stored:
            keys.add('expected_revision')
        if not isinstance(data, dict) or set(data) != keys or data['schema'] not in ('private-presentation-status/v1', 'private-presentation-status/v2', 'private-presentation-status/v3', 'private-presentation-status/v4'):
            raise ValueError('invalid status fields')
        if data['schema'] == 'private-presentation-status/v4':
            validate_authority(data['authority'])
            history = data['historical']
            if (not isinstance(history, dict) or set(history) != {'receipt', 'sha256'}
                    or not isinstance(history['receipt'], dict)
                    or history['receipt'].get('schema') not in ('private-presentation-status/v1',
                        'private-presentation-status/v2', 'private-presentation-status/v3')
                    or status_digest(history['receipt']) != history['sha256']
                    or data['authority']['snapshot_sha256'] != data['local']['snapshot_sha256']):
                raise ValueError('invalid status history')
            self._validate(history['receipt'], stored=True)
        if data['schema'] == 'private-presentation-status/v2':
            self._validate_reconciliation(data['reconciliation'], data['local'])
        if data['schema'] == 'private-presentation-status/v3':
            self._validate_application(data['application'], data['local'])
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

    def _validate_reconciliation(self, result, local):
        keys = {'snapshot_sha256', 'decision_revision', 'owner_store_revision', 'metrics'}
        if not isinstance(result, dict) or set(result) != keys or result['snapshot_sha256'] != local['snapshot_sha256']:
            raise ValueError('invalid reconciliation binding')
        if any(type(result[key]) is not int or result[key] < 0 for key in ('decision_revision', 'owner_store_revision')):
            raise ValueError('invalid reconciliation revisions')
        metrics = result['metrics']
        counts = {'decision_denominator', 'automatic_approved', 'unknown', 'error', 'conflict',
                  'pending_review', 'source_gaps', 'provider_calls_recorded'}
        unknown = {'accepted_athletes', 'distinct_attempts', 'actual_monetary_cost'}
        if not isinstance(metrics, dict) or set(metrics) != counts | unknown | {'sampled_error'}:
            raise ValueError('invalid metrics summary')
        if any(type(metrics[key]) is not int or not 0 <= metrics[key] <= 1000000 for key in counts) or any(metrics[key] is not None for key in unknown):
            raise ValueError('invalid metrics counts')
        sample = metrics['sampled_error']
        if sample is not None:
            words = ('sampling_frame', 'selection', 'selection_bias')
            if (not isinstance(sample, dict) or set(sample) != set(words) | {'numerator', 'denominator'}
                    or any(not isinstance(sample[key], str) or not re.fullmatch(r'[A-Za-z][A-Za-z -]{0,79}', sample[key]) for key in words)
                    or type(sample['numerator']) is not int or type(sample['denominator']) is not int
                    or not 0 <= sample['numerator'] <= sample['denominator'] <= 1000000
                    or sample['denominator'] == 0):
                raise ValueError('invalid sample summary')

    def _validate_application(self, result, local):
        keys = {'snapshot_sha256', 'canonical_revision', 'canonical_readback_sha256',
                'owner_store_revision', 'pending_proposals', 'unresolved_exclusions',
                'provider_calls_recorded', 'publication_status'}
        if (not isinstance(result, dict) or set(result) != keys
                or result['snapshot_sha256'] != local['snapshot_sha256']
                or not isinstance(result['canonical_readback_sha256'], str)
                or not HASH.fullmatch(result['canonical_readback_sha256'])
                or type(result['canonical_revision']) is not int
                or not 1 <= result['canonical_revision'] < 2**53
                or type(result['owner_store_revision']) is not int
                or not 0 <= result['owner_store_revision'] < 2**53
                or any(type(result[key]) is not int or not 0 <= result[key] <= 1000000
                       for key in ('pending_proposals', 'unresolved_exclusions'))
                or result['provider_calls_recorded'] != 0
                or type(result['provider_calls_recorded']) is not int
                or result['publication_status'] != 'private'):
            raise ValueError('invalid private application receipt')

    def update(self, data, active, *, owner_revision=None, owner_snapshot=None):
        self._validate(data)
        if data['schema'] == 'private-presentation-status/v4' or (
                self.current and self.current['schema'] == 'private-presentation-status/v4'):
            raise StatusConflict('authoritative status requires verified refresh')
        if data['schema'] in ('private-presentation-status/v2', 'private-presentation-status/v3'):
            binding = (data['reconciliation'] if data['schema'].endswith('/v2')
                       else data['application'])
            if (owner_revision != binding['owner_store_revision'] or
                    owner_snapshot != binding['snapshot_sha256']):
                raise StatusConflict('owner decision binding mismatch')
        if data['remote']['active'] is not None and data['remote']['active'] != active:
            raise StatusConflict('active binding mismatch')
        if data['remote']['status'] == 'active' and (active is None or
                active.get('bundle_manifest_sha256') is None or
                data['local']['snapshot_sha256'] != active['snapshot_sha256'] or
                data['remote']['pending'] is not None or data['remote']['failed'] is not None):
            raise StatusConflict('unverified active status')
        candidate = {key: value for key, value in data.items() if key != 'expected_revision'}
        current = self.current
        if current and int(data['schema'].rsplit('v', 1)[1]) < int(current['schema'].rsplit('v', 1)[1]):
            raise StatusConflict('authoritative status cannot downgrade')
        if current and current['schema'] in ('private-presentation-status/v2', 'private-presentation-status/v3') and self.read(
                active, owner_revision=owner_revision, owner_snapshot=owner_snapshot).get('status') == 'stale':
            applying_same_run = (
                current['schema'] == 'private-presentation-status/v2'
                and data['schema'] == 'private-presentation-status/v3'
                and data['run_id'] == current['run_id']
                and data['local'] == current['local']
                and current['remote']['active'] == active
                and data['remote']['active'] == active
                and data['remote']['status'] == 'active'
                and current['reconciliation']['snapshot_sha256'] == data['application']['snapshot_sha256']
                and owner_revision > current['reconciliation']['owner_store_revision'])
            if not applying_same_run and (data['schema'] not in ('private-presentation-status/v2', 'private-presentation-status/v3') or
                    data['run_id'] == current['run_id'] or
                    data['local']['cutoff'] <= current['local']['cutoff']):
                raise StatusConflict('stale run cannot replace owner correction')
        if current == candidate:
            return self.read(active, owner_revision=owner_revision, owner_snapshot=owner_snapshot)
        revision = current['revision'] if current else 0
        if data['expected_revision'] != revision or data['revision'] != revision + 1:
            raise StatusConflict('status revision conflict')
        if current:
            if data['run_id'] == current['run_id']:
                if data['local'] != current['local']:
                    raise StatusConflict('run binding mismatch')
            elif data['local']['cutoff'] <= current['local']['cutoff']:
                raise StatusConflict('stale run cutoff')
        self._write(candidate)
        return self.read(active, owner_revision=owner_revision, owner_snapshot=owner_snapshot)

    def refresh(self, command, active, authority):
        """Commit origin-collected authority. Clients submit pins, never receipts."""
        keys = {'schema', 'expected_revision', 'expected_authority_sha256', 'run_id',
                'local', 'snapshot_sha256', 'bundle_manifest_sha256', 'export_sha256', 'remote'}
        if (not isinstance(command, dict) or set(command) != keys
                or command['schema'] != 'private-presentation-refresh/v1'
                or type(command['expected_revision']) is not int
                or not 1 <= command['expected_revision'] < 2**53):
            raise ValueError('invalid status refresh command')
        validate_authority(authority)
        current = self.current
        if current is None:
            raise StatusConflict('refresh requires retained status provenance')
        if json.loads(self.path.read_text(encoding='utf-8')) != current:
            raise StatusConflict('durable status revision changed')
        if (command['expected_revision'] != current['revision'] or
                command['expected_authority_sha256'] != status_digest(authority)):
            raise StatusConflict('status or authority revision conflict')
        if (active != {'snapshot_sha256': command['snapshot_sha256'],
                       'bundle_manifest_sha256': command['bundle_manifest_sha256']}
                or authority['snapshot_sha256'] != command['snapshot_sha256']):
            raise StatusConflict('refresh active snapshot binding mismatch')
        scope = authority['canonical']['same_attempt']
        if scope['export_sha256'] != command['export_sha256'] or scope['status'] != 'verified':
            raise StatusConflict('refresh export or canonical scope changed')
        history = (current['historical'] if current['schema'] == 'private-presentation-status/v4'
                   else {'receipt': current, 'sha256': status_digest(current)})
        candidate = {'schema': 'private-presentation-status/v4', 'revision': current['revision'] + 1,
                     'run_id': command['run_id'], 'local': command['local'],
                     'remote': {**command['remote'], 'active': active},
                     'authority': authority, 'historical': history}
        self._validate(candidate, stored=True)
        if (set(command['remote']) != {'status', 'pending', 'failed'} or
                command['remote']['status'] == 'active' and
                (command['remote']['pending'] is not None or command['remote']['failed'] is not None)):
            raise ValueError('invalid refresh processing status')
        if candidate['local']['snapshot_sha256'] != command['snapshot_sha256']:
            raise StatusConflict('refresh local snapshot binding mismatch')
        if current['schema'] == 'private-presentation-status/v4' and all(
                current[key] == value for key, value in candidate.items() if key != 'revision'):
            return self.read(active, authority=authority)
        if (current['schema'] == 'private-presentation-status/v4'
                and authority['owner_store_revision'] < current['authority']['owner_store_revision']):
            raise StatusConflict('owner revision cannot regress')
        if current['schema'] == 'private-presentation-status/v4':
            for name, scope in authority['canonical'].items():
                old = current['authority']['canonical'][name]
                if (scope['status'] == old['status'] == 'verified'
                        and authority['snapshot_sha256'] == current['authority']['snapshot_sha256']
                        and scope['export_sha256'] == old['export_sha256']
                        and (scope['revision'] < old['revision'] or
                             scope['owner_event_revision'] < old['owner_event_revision'])):
                    raise StatusConflict('canonical revision cannot regress')
        elif current['schema'] in ('private-presentation-status/v2', 'private-presentation-status/v3'):
            old = current.get('application') or current.get('reconciliation')
            if (old['snapshot_sha256'] == authority['snapshot_sha256'] and
                    authority['owner_store_revision'] < old['owner_store_revision']):
                raise StatusConflict('owner revision cannot regress historical authority')
        # Snapshot rollback can restore an older presentation binding, but it
        # cannot lower owner authority or replace the original immutable receipt.
        if candidate['local']['cutoff'] < current['local']['cutoff']:
            raise StatusConflict('refresh local cutoff cannot regress')
        # Machine GET also carries a bounded refresh pin. Reserve its envelope
        # before committing, so a successful write stays readable by the client.
        if len(json.dumps(candidate, ensure_ascii=False).encode('utf-8')) > 3584:
            raise ValueError('authoritative status response too large')
        self._write(candidate)
        return self.read(active, authority=authority)
