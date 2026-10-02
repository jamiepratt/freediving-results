#!/usr/bin/env python3
"""Private, append-only owner decisions bound to immutable evidence revisions.

The snapshot mount is never opened for writes. A deployment must supply IDs from
a verified SnapshotQuery when binding a revision; this store cannot authenticate
the publisher or decide which evidence was present on its own.
"""

import hashlib
import json
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from unified_evidence_query import SnapshotQuery


ACCEPTED = {'automatic_approved', 'human_approved', 'human_corrected'}
ACTIONS = {'approve', 'reject', 'reverse', 'correct'}
PUBLIC_ID = re.compile(r'[A-Za-z0-9_-]{1,128}\Z')


class ConflictError(ValueError):
    """The caller's revision, state, or idempotency key conflicts with history."""


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def _digest(value):
    return hashlib.sha256(_json(value).encode('utf-8')).hexdigest()


def _sha(value):
    if not isinstance(value, str) or len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
        raise ValueError('snapshot_sha256 must be a lowercase SHA256 digest')


def _text(value, name):
    if not isinstance(value, str) or not value or len(value) > 512 or '\x00' in value:
        raise ValueError(f'invalid {name}')


class DecisionStore:
    """A transactional ledger with one monotonically increasing store revision."""

    def __init__(self, path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, timeout=15, isolation_level=None, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            INSERT OR IGNORE INTO meta VALUES ('revision', '0');
            CREATE TABLE IF NOT EXISTS bindings (
                revision INTEGER PRIMARY KEY, snapshot_sha256 TEXT NOT NULL,
                evidence_ids_json TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS proposals (
                id TEXT PRIMARY KEY, snapshot_sha256 TEXT NOT NULL,
                registered_revision INTEGER NOT NULL, payload_json TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (
                revision INTEGER PRIMARY KEY, decision_id TEXT NOT NULL REFERENCES proposals(id),
                action TEXT NOT NULL, actor TEXT NOT NULL, reason TEXT NOT NULL,
                correction_json TEXT, created_at TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS events_decision ON events(decision_id, revision);
            CREATE TABLE IF NOT EXISTS operations (
                idempotency_key TEXT PRIMARY KEY, request_sha256 TEXT NOT NULL,
                response_json TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS human_event_bindings (
                event_revision INTEGER PRIMARY KEY REFERENCES events(revision),
                binding_revision INTEGER NOT NULL, snapshot_sha256 TEXT NOT NULL);
        ''')
        if 'observation_refs_json' not in [row[1] for row in self.db.execute('PRAGMA table_info(bindings)')]:
            self.db.execute('ALTER TABLE bindings ADD COLUMN observation_refs_json TEXT')

    def close(self):
        self.db.close()

    @property
    def revision(self):
        return int(self.db.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0])

    def _next_revision(self):
        revision = self.revision + 1
        self.db.execute("UPDATE meta SET value=? WHERE key='revision'", (str(revision),))
        return revision

    def _binding(self):
        row = self.db.execute('SELECT * FROM bindings ORDER BY revision DESC LIMIT 1').fetchone()
        if row is None:
            return None
        return {'revision': row['revision'], 'snapshot_sha256': row['snapshot_sha256'],
                'evidence_ids': set(json.loads(row['evidence_ids_json'])),
                'observation_refs': (json.loads(row['observation_refs_json'])
                                     if row['observation_refs_json'] is not None else None)}

    def _begin(self, key, request, expected_revision=None):
        _text(key, 'idempotency_key')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            old = self.db.execute('SELECT * FROM operations WHERE idempotency_key=?', (key,)).fetchone()
            fingerprint = _digest(request)
            if old:
                if old['request_sha256'] != fingerprint:
                    raise ConflictError('idempotency key reused for another request')
                result = json.loads(old['response_json'])
                self.db.execute('ROLLBACK')
                return result, fingerprint
            if expected_revision is not None and expected_revision != self.revision:
                raise ConflictError('stale decision store revision')
            return None, fingerprint
        except Exception:
            if self.db.in_transaction:
                self.db.execute('ROLLBACK')
            raise

    def _finish(self, key, fingerprint, result):
        self.db.execute('INSERT INTO operations VALUES (?,?,?)', (key, fingerprint, _json(result)))
        self.db.execute('COMMIT')
        return result

    def bind_snapshot(self, snapshot_sha256, available_evidence_ids, *, expected_revision, idempotency_key,
                      _observation_refs=None):
        """Bind a verified snapshot revision; decisions retain their original binding."""
        _sha(snapshot_sha256)
        evidence_ids = sorted(set(available_evidence_ids))
        if any(not isinstance(item, str) or not item or len(item) > 512 for item in evidence_ids):
            raise ValueError('invalid evidence ID')
        request = ['bind_snapshot', snapshot_sha256, evidence_ids, expected_revision, _observation_refs]
        old, fingerprint = self._begin(idempotency_key, request, expected_revision)
        if old is not None:
            return old
        try:
            revision = self._next_revision()
            self.db.execute('INSERT INTO bindings(revision,snapshot_sha256,evidence_ids_json,observation_refs_json) VALUES (?,?,?,?)',
                            (revision, snapshot_sha256, _json(evidence_ids),
                             _json(_observation_refs) if _observation_refs is not None else None))
            return self._finish(idempotency_key, fingerprint,
                                {'revision': revision, 'snapshot_sha256': snapshot_sha256,
                                 'evidence_count': len(evidence_ids)})
        except Exception:
            self.db.execute('ROLLBACK')
            raise

    def bind_verified_snapshot(self, directory, *, expected_revision, idempotency_key):
        """Bind only record IDs from a hash-verified immutable snapshot.

        This is the production binding entry point. Decision proposals must cite
        these exact record IDs. The private publisher has no access to this DB.
        """
        with SnapshotQuery(directory) as snapshot:
            columns = {row[1] for row in snapshot.db.execute('PRAGMA table_info(records)')}
            selected = ['record_id'] + [column for column in
                                        ('raw_json', 'source_sha256', 'source_object_id') if column in columns]
            rows = snapshot.db.execute('SELECT ' + ','.join(selected) +
                                       ' FROM records ORDER BY record_id').fetchall()
            evidence_ids = [row['record_id'] for row in rows]
            observation_refs = {}
            for row in rows:
                raw = json.loads(row['raw_json']) if 'raw_json' in columns else {}
                observation_refs[row['record_id']] = {
                    'source_sha256': (raw.get('source_sha256') or
                                      (row['source_sha256'] if 'source_sha256' in columns else None) or
                                      (row['source_object_id'].removeprefix('sha256:')
                                       if 'source_object_id' in columns and row['source_object_id'] else None)),
                    'refs': raw.get('observation_refs') or raw.get('imported_observation_refs') or []}
            digest = snapshot.manifest['snapshot_sha256']
        return self.bind_snapshot(digest, evidence_ids, expected_revision=expected_revision,
                                  idempotency_key=idempotency_key,
                                  _observation_refs=observation_refs)

    def register(self, snapshot_sha256, proposal, *, idempotency_key):
        """Record an immutable proposal. Automatic approval is a distinct event."""
        _sha(snapshot_sha256)
        self._validate_proposal(proposal)
        request = ['register', snapshot_sha256, proposal]
        old, fingerprint = self._begin(idempotency_key, request)
        if old is not None:
            return old
        try:
            result = self._register_uncommitted(snapshot_sha256, proposal)
            return self._finish(idempotency_key, fingerprint, result)
        except Exception:
            self.db.execute('ROLLBACK')
            raise

    def register_batch(self, snapshot_sha256, proposals, *, idempotency_key):
        """Register a verified export as one ledger transaction, including retries."""
        _sha(snapshot_sha256)
        if not isinstance(proposals, list) or not proposals:
            raise ValueError('batch requires proposals')
        for proposal in proposals:
            self._validate_proposal(proposal)
        if len({p['id'] for p in proposals}) != len(proposals):
            raise ValueError('duplicate decision ID')
        old, fingerprint = self._begin(idempotency_key,
                                       ['register_batch', snapshot_sha256, proposals])
        if old is not None:
            return old
        try:
            results = [self._register_uncommitted(snapshot_sha256, proposal)
                       for proposal in proposals]
            return self._finish(idempotency_key, fingerprint, results)
        except Exception:
            self.db.execute('ROLLBACK')
            raise

    def _register_uncommitted(self, snapshot_sha256, proposal):
        binding = self._binding()
        if binding is None or binding['snapshot_sha256'] != snapshot_sha256:
            raise ConflictError('proposal is not bound to active snapshot')
        if not {item['id'] for item in proposal['evidence']} <= binding['evidence_ids']:
            raise ConflictError('proposal cites evidence absent from active snapshot')
        if not self._revisions_current(proposal, binding):
            raise ConflictError('proposal observation revisions absent from active snapshot')
        if self.db.execute('SELECT 1 FROM proposals WHERE id=?', (proposal['id'],)).fetchone():
            raise ConflictError('decision ID already registered')
        for row in self.db.execute('SELECT id FROM proposals'):
            existing = self.inspect(row['id'])
            if (existing['type'], existing['subject_id']) == (proposal['type'], proposal['subject_id']) and existing['status'] == 'human_corrected':
                raise ConflictError('human correction prevents automatic reapplication')
        for dependency in proposal['depends_on']:
            if dependency == proposal['id'] or not self.db.execute('SELECT 1 FROM proposals WHERE id=?', (dependency,)).fetchone():
                raise ValueError('dependency must be an existing different decision')
            if proposal['status'] == 'automatic_approved' and self.inspect(dependency)['effective_status'] not in ACCEPTED:
                raise ConflictError('automatic approval requires active prerequisites')
        revision = self._next_revision()
        self.db.execute('INSERT INTO proposals VALUES (?,?,?,?)',
                        (proposal['id'], snapshot_sha256, revision, _json(proposal)))
        self._event(revision, proposal['id'], 'register', 'system', '', None)
        if proposal['status'] == 'automatic_approved':
            revision = self._next_revision()
            self._event(revision, proposal['id'], 'automatic_approve', 'system', '', None)
        return self.inspect(proposal['id'])

    @staticmethod
    def _revisions_current(proposal, binding):
        refs = binding['observation_refs']
        canonical = proposal.get('canonical_binding')
        if refs is None or canonical is None:
            return True
        evidence_bindings = canonical.get('evidence_bindings')
        if not isinstance(evidence_bindings, list) or len(evidence_bindings) != len(proposal['evidence']):
            return False
        for item, evidence in zip(proposal['evidence'], evidence_bindings):
            revision = evidence.get('observation_revision')
            source = refs.get(item['id'])
            if (not isinstance(revision, dict) or evidence.get('snapshot_record_id') != item['id']
                    or not source or source['source_sha256'] != revision.get('source_sha256')
                    or not any(all(ref.get(key) == revision.get(key) for key in
                                   ('job_id', 'ordinal', 'candidate_id', 'artifact_sha256', 'parser_version'))
                               for ref in source['refs'])):
                return False
        return True

    @staticmethod
    def _validate_proposal(p):
        if not isinstance(p, dict):
            raise ValueError('proposal must be an object')
        for field in ('id', 'type', 'subject_id', 'source_name', 'rule_version', 'policy_version'):
            _text(p.get(field), field)
        if not PUBLIC_ID.fullmatch(p['id']):
            raise ValueError('decision ID must be URL-safe and at most 128 characters')
        if p.get('status') not in ('pending', 'automatic_approved'):
            raise ValueError('invalid initial status')
        if not isinstance(p.get('evidence'), list) or not p['evidence']:
            raise ValueError('evidence required')
        for item in p['evidence']:
            if not isinstance(item, dict):
                raise ValueError('invalid evidence')
            _text(item.get('id'), 'evidence ID')
            if 'citation' not in item or 'version' not in item:
                raise ValueError('evidence citation and version required')
        for field in ('competing_options', 'supporting_evidence', 'conflicting_evidence', 'depends_on', 'groups'):
            if not isinstance(p.get(field), list):
                raise ValueError(f'{field} must be a list')
        for field in ('score', 'provider_confidence'):
            value = p.get(field)
            if value is not None and (type(value) not in (int, float) or not 0 <= value <= 1):
                raise ValueError(f'{field} must be a probability or null')
        if p.get('selected_option') is None or 'original' not in p or 'proposed' not in p:
            raise ValueError('original, proposed and selected option required')

    def _event(self, revision, decision_id, action, actor, reason, correction):
        self.db.execute('INSERT INTO events VALUES (?,?,?,?,?,?,?)',
                        (revision, decision_id, action, actor, reason,
                         _json(correction) if correction is not None else None,
                         datetime.now(timezone.utc).isoformat()))

    def human_events(self, *, after_revision=0, limit=100):
        """Export durable owner actions with the exact proposal and action-time binding."""
        if type(after_revision) is not int or after_revision < 0 or type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('invalid event cursor')
        rows = self.db.execute('''SELECT e.*, b.binding_revision, b.snapshot_sha256,
                              p.payload_json FROM events e
                              LEFT JOIN human_event_bindings b ON b.event_revision=e.revision
                              JOIN proposals p ON p.id=e.decision_id
                              WHERE e.revision>? AND e.action IN ('approve','correct','reject','reverse')
                              ORDER BY e.revision LIMIT ?''', (after_revision, limit)).fetchall()
        events = []
        for row in rows:
            if row['binding_revision'] is None:
                raise ConflictError('owner event lacks immutable action binding')
            proposal = json.loads(row['payload_json'])
            events.append({'id': f"owner-store:{row['revision']}", 'store_revision': row['revision'],
                           'decision_id': row['decision_id'], 'binding_revision': row['binding_revision'],
                           'snapshot_sha256': row['snapshot_sha256'], 'action': row['action'],
                           'actor': row['actor'], 'reason': row['reason'],
                           'correction': json.loads(row['correction_json']) if row['correction_json'] else None,
                           'created_at': row['created_at'], 'proposal': proposal,
                           'proposal_sha256': _digest(proposal)})
        return {'store_revision': self.revision, 'events': events,
                'next_revision': events[-1]['store_revision'] if events else after_revision}

    def _base(self, decision_id):
        row = self.db.execute('SELECT * FROM proposals WHERE id=?', (decision_id,)).fetchone()
        if row is None:
            raise KeyError(decision_id)
        p = json.loads(row['payload_json'])
        history = []
        status = 'pending'
        correction = None
        for event in self.db.execute('SELECT * FROM events WHERE decision_id=? ORDER BY revision', (decision_id,)):
            history.append({key: event[key] for key in ('revision', 'action', 'actor', 'reason', 'created_at')}
                           | {'correction': json.loads(event['correction_json']) if event['correction_json'] else None})
            status = {'register': 'pending', 'automatic_approve': 'automatic_approved',
                      'approve': 'human_approved', 'reject': 'rejected',
                      'reverse': 'reversed', 'correct': 'human_corrected'}[event['action']]
            if event['action'] == 'correct':
                correction = json.loads(event['correction_json'])
        return p | {'snapshot_sha256': row['snapshot_sha256'], 'status': status,
                    'correction': correction, 'history': history}

    def inspect(self, decision_id):
        p = self._base(decision_id)
        binding = self._binding()
        effective = p['status']
        missing = []
        if binding:
            missing = [item['id'] for item in p['evidence'] if item['id'] not in binding['evidence_ids']]
        if p['status'] in ACCEPTED | {'pending'} and (binding is None or missing or
                                                    not self._revisions_current(p, binding)):
            effective = 'invalidated'
        elif p['status'] in ACCEPTED:
            for dependency in p['depends_on']:
                if self.inspect(dependency)['effective_status'] not in ACCEPTED:
                    effective = 'invalidated'
                    break
        return p | {'active_snapshot_sha256': binding['snapshot_sha256'] if binding else None,
                    'binding_revision': binding['revision'] if binding else None,
                    'store_revision': self.revision, 'effective_status': effective,
                    'missing_evidence_ids': missing,
                    'canonical_projection_status': 'unavailable'}

    def queue(self, *, decision_type=None, status='pending', source_name=None, limit=50, offset=0):
        if type(limit) is not int or not 1 <= limit <= 100 or type(offset) is not int or not 0 <= offset <= 100000:
            raise ValueError('invalid page')
        items = [self.inspect(row['id']) for row in self.db.execute('SELECT id FROM proposals')]
        items = [p for p in items if (decision_type is None or p['type'] == decision_type)
                 and (status is None or p['effective_status'] == status)
                 and (source_name is None or p['source_name'] == source_name)]
        scored = sorted((p for p in items if p['provider_confidence'] is not None),
                        key=lambda p: (p['provider_confidence'], p['id']))
        scoreless = sorted((p for p in items if p['provider_confidence'] is None),
                           key=lambda p: p['id'])
        return {'revision': self.revision, 'items': scored[offset:offset + limit],
                'total': len(items), 'scoreless_total': len(scoreless),
                'scoreless_items': scoreless[offset:offset + limit],
                'score_note': 'Provider confidence is uncalibrated and is not measured accuracy.'}

    def audit_sample(self, *, limit=10):
        """Read-only stable sample; sampling never gates automatic decisions."""
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('invalid sample limit')
        binding = self._binding()
        seed = binding['snapshot_sha256'] if binding else ''
        items = [self.inspect(row['id']) for row in self.db.execute('SELECT id FROM proposals')]
        items = [p for p in items if p['effective_status'] == 'automatic_approved']
        items.sort(key=lambda p: (_digest([seed, p['id']]), p['id']))
        return {'revision': self.revision, 'items': items[:limit],
                'sample_size': min(limit, len(items)), 'population_size': len(items),
                'sampling_basis': 'stable hash of snapshot SHA256 and decision ID',
                'blocking': False}

    def projection(self):
        """Rebuild a bounded view from authoritative proposals/events each read."""
        active = [self.inspect(row['id']) for row in self.db.execute('SELECT id FROM proposals')]
        groups = {}
        for p in active:
            if p['effective_status'] in ACCEPTED:
                for group in p['groups']:
                    groups[group] = groups.get(group, 0) + 1
        return {'revision': self.revision, 'snapshot_sha256': (self._binding() or {}).get('snapshot_sha256'),
                'overlay_decision_counts_by_group': groups,
                'active_decisions': [p['id'] for p in active if p['effective_status'] in ACCEPTED],
                'canonical_projection_status': 'unavailable'}

    def _affected(self, decision_id):
        affected = {decision_id}
        changed = True
        while changed:
            changed = False
            for row in self.db.execute('SELECT id, payload_json FROM proposals'):
                if row['id'] not in affected and affected.intersection(json.loads(row['payload_json'])['depends_on']):
                    affected.add(row['id'])
                    changed = True
        return sorted(affected)

    def preview(self, decision_id, *, action):
        if action not in ACTIONS:
            raise ValueError('invalid action')
        current = self.inspect(decision_id)
        affected = self._affected(decision_id)
        before = {ident: self.inspect(ident)['effective_status'] for ident in affected}
        after = dict(before)
        after[decision_id] = {'approve': 'human_approved', 'reject': 'rejected',
                              'reverse': 'reversed', 'correct': 'human_corrected'}[action]
        while True:
            changed = False
            for ident in affected:
                if ident == decision_id:
                    continue
                p = self._base(ident)
                if p['status'] in ACCEPTED and any(after.get(dep, self.inspect(dep)['effective_status']) not in ACCEPTED for dep in p['depends_on']) and after[ident] != 'invalidated':
                    after[ident] = 'invalidated'
                    changed = True
            if not changed:
                break
        return {'revision': self.revision, 'decision_id': decision_id, 'action': action,
                'affected_decisions': affected, 'affected_groups': sorted(set(
                    group for ident in affected for group in self._base(ident)['groups'])),
                'before': before, 'after': after,
                'original': current['original'], 'proposed': current['proposed'],
                'canonical_projection_status': 'unavailable'}

    def act(self, decision_id, *, action, expected_revision, idempotency_key,
            actor='owner', reason='', correction=None):
        if action not in ACTIONS:
            raise ValueError('invalid action')
        _text(actor, 'actor')
        if not isinstance(reason, str) or len(reason) > 2000:
            raise ValueError('invalid reason')
        if action == 'correct' and correction is None:
            raise ValueError('correction value required')
        request = ['act', decision_id, action, expected_revision, actor, reason, correction]
        old, fingerprint = self._begin(idempotency_key, request, expected_revision)
        if old is not None:
            return old
        try:
            current = self.inspect(decision_id)
            status = current['effective_status']
            if action in ('approve', 'correct') and status not in ('pending', 'automatic_approved', 'human_approved', 'human_corrected'):
                raise ConflictError('decision cannot be approved in current state')
            if action == 'reject' and status != 'pending':
                raise ConflictError('only pending decisions can be rejected')
            if action == 'reverse' and status not in ACCEPTED:
                raise ConflictError('only active approvals can be reversed')
            if action == 'approve' and current['status'] == 'human_corrected':
                raise ConflictError('human correction cannot be overwritten by approval')
            if action in ('approve', 'correct') and any(
                    self.inspect(dependency)['effective_status'] not in ACCEPTED
                    for dependency in current['depends_on']):
                raise ConflictError('approval requires active prerequisites')
            revision = self._next_revision()
            self._event(revision, decision_id, action, actor, reason, correction)
            binding = self._binding()
            if binding is None:
                raise ConflictError('no active snapshot binding')
            self.db.execute('INSERT INTO human_event_bindings VALUES (?,?,?)',
                            (revision, binding['revision'], binding['snapshot_sha256']))
            result = self.inspect(decision_id)
            return self._finish(idempotency_key, fingerprint, result)
        except Exception:
            self.db.execute('ROLLBACK')
            raise
