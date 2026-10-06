"""Independent immutable owner review of an exhaustive exact retained inventory."""
from datetime import datetime, timezone
import copy
import json
from pathlib import Path
import re
import sqlite3
from urllib.parse import urlsplit
from sporting_authority import canonical, digest, HASH, ZERO, ConflictError


def inventory(context):
    return [{'reference': r['reference'], 'coordinates': r['coordinates']} for r in context['rows']]


class RelationshipReviews:
    def __init__(self, path, context_reader):
        path = Path(path)
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if any(p.is_symlink() for p in (path, *path.parents)):
            raise ValueError('relationship ledger symlink')
        self.db = sqlite3.connect(path, check_same_thread=False)
        path.chmod(0o600)
        self.db.row_factory = sqlite3.Row
        self.reader = context_reader
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS relationship_events(
            revision INTEGER PRIMARY KEY, head_sha256 TEXT NOT NULL,
            previous_sha256 TEXT NOT NULL, operation_key TEXT UNIQUE NOT NULL,
            request_sha256 TEXT NOT NULL, action TEXT NOT NULL, assertion_json TEXT NOT NULL,
            actor TEXT NOT NULL, created_at TEXT NOT NULL);
          CREATE TRIGGER IF NOT EXISTS relationship_no_update BEFORE UPDATE ON relationship_events
            BEGIN SELECT RAISE(ABORT, 'immutable relationship event'); END;
          CREATE TRIGGER IF NOT EXISTS relationship_no_delete BEFORE DELETE ON relationship_events
            BEGIN SELECT RAISE(ABORT, 'immutable relationship event'); END;
        ''')

    def close(self):
        self.db.close()

    def history(self):
        if hasattr(self, "read_lock"):
            with self.read_lock():
                return self.history_unlocked()
        return self.history_unlocked()

    def history_unlocked(self):
        events, previous = [], ZERO
        for row in self.db.execute('SELECT * FROM relationship_events ORDER BY revision'):
            event = dict(row); event['assertion'] = json.loads(event.pop('assertion_json'))
            if (event['revision'] != len(events) + 1 or event['previous_sha256'] != previous
                    or digest({k: v for k, v in event.items() if k != 'head_sha256'}) != event['head_sha256']):
                raise ValueError('relationship ledger integrity failed')
            previous = event['head_sha256']; events.append(event)
            if len(events) > 10000:
                raise ValueError('relationship event bound exceeded')
        return events

    def validate(self, assertion, context):
        fields = {'reference', 'coordinates', 'inventory_sha256', 'canonical_binding_sha256',
                  'reviewed_references', 'same-attempt', 'source-conflict', 'reason', 'citation'}
        if not isinstance(assertion, dict) or set(assertion) != fields:
            raise ValueError('invalid typed relationship review')
        rows = inventory(context)
        exact = {'reference': assertion['reference'], 'coordinates': assertion['coordinates']}
        if exact not in rows or rows.count(exact) != 1:
            raise ValueError('relationship exact source version absent')
        refs = [r['reference'] for r in rows]
        if (assertion['inventory_sha256'] != digest(rows)
                or assertion['reviewed_references'] != refs):
            raise ValueError('exhaustive relationship inventory review required')
        binding = context['pins'].get('canonical_upstream_sha256')
        if not isinstance(binding, str) or not HASH.fullmatch(binding) or assertion['canonical_binding_sha256'] != binding:
            raise ValueError('current canonical relationship binding required')
        matching = next(r for r in context['rows'] if r['reference'] == assertion['reference'] and r['coordinates'] == assertion['coordinates'])
        diagnostics = matching.get('diagnostics', {})
        canonical_rel = diagnostics.get('canonical_relationships', {}).get('same-attempt', {})
        if any(link.get('current') is True and link.get('type') == 'same-attempt'
               and link.get('action') == 'accept' for link in canonical_rel.get('exact_relationships', [])):
            raise ValueError('canonical same-attempt relationship contradicts distinct assertion')
        revisions = diagnostics.get('source-revision', {}).get('relationships', [])
        if any(link.get('status') == 'confirmed-replacement' and link.get('role') == 'predecessor' for link in revisions):
            raise ValueError('superseded exact source revision cannot be selected as distinct authority')
        if any(link.get('status') in ('unresolved', 'conflicting', 'possible-replacement', 'possible-revision') for link in revisions):
            raise ValueError('canonical source conflict requires separate cited source selection authority')
        if assertion['same-attempt'] != 'distinct' or assertion['source-conflict'] != 'resolved':
            raise ValueError('unsupported typed relationship assertion')
        if not isinstance(assertion['reason'], str) or not 10 <= len(assertion['reason'].strip()) <= 2000:
            raise ValueError('relationship review reason required')
        cite = assertion['citation']
        if not isinstance(cite, dict) or set(cite) != {'url', 'locator'}:
            raise ValueError('relationship authority citation required')
        url = urlsplit(cite['url'])
        if (url.scheme != 'https' or not url.hostname or url.username or url.password or url.fragment
                or len(cite['url']) > 500 or not isinstance(cite['locator'], str)
                or not 1 <= len(cite['locator']) <= 500):
            raise ValueError('invalid relationship authority citation')

    def act(self, assertion, *, action, expected_revision, idempotency_key, actor):
        if action not in ('review', 'reverse') or not re.fullmatch('[A-Za-z0-9_-]{1,128}', idempotency_key):
            raise ValueError('invalid relationship action')
        if not isinstance(actor, str) or not 1 <= len(actor) <= 320:
            raise ValueError('authenticated relationship reviewer required')
        request = {'actor': actor, 'assertion': assertion, 'action': action, 'expected_revision': expected_revision}
        self.db.execute('BEGIN IMMEDIATE')
        try:
            events = self.history()
            old = next((e for e in events if e['operation_key'] == idempotency_key), None)
            if old:
                if old['request_sha256'] != digest(request):
                    raise ConflictError('relationship operation key conflict')
                self.db.rollback(); return old
            if type(expected_revision) is not int or expected_revision != len(events):
                raise ConflictError('relationship revision changed')
            exact = {'reference': assertion.get('reference'), 'coordinates': assertion.get('coordinates')}
            prior = [e for e in events if {'reference': e['assertion']['reference'], 'coordinates': e['assertion']['coordinates']} == exact]
            if action == 'reverse':
                if not prior or prior[-1]['action'] != 'review' or prior[-1]['assertion'] != assertion:
                    raise ConflictError('relationship reversal predecessor changed')
                context = None
            else:
                context = self.reader(); self.validate(assertion, context)
                physical = lambda a: digest({'source-sha256': a['reference']['source-sha256'],
                    'coordinates': {k: v for k, v in a['coordinates'].items() if k in ('page', 'line', 'table', 'row')}})
                latest = {}
                for item in events:
                    latest[digest({'reference': item['assertion']['reference'], 'coordinates': item['assertion']['coordinates']})] = item
                if any(e['action'] == 'review' and e['assertion']['reference'] != assertion['reference']
                       and physical(e['assertion']) == physical(assertion) for e in latest.values()):
                    raise ConflictError('physical source position already has a reviewed representative; reverse it first')
            event = {'revision': len(events) + 1, 'previous_sha256': events[-1]['head_sha256'] if events else ZERO,
                     'operation_key': idempotency_key, 'request_sha256': digest(request), 'action': action,
                     'assertion': assertion, 'actor': actor,
                     'created_at': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')}
            event['head_sha256'] = digest(event)
            self.db.execute('INSERT INTO relationship_events VALUES(?,?,?,?,?,?,?,?,?)',
                (event['revision'], event['head_sha256'], event['previous_sha256'], idempotency_key,
                 event['request_sha256'], action, canonical(assertion).decode(), actor, event['created_at']))
            if context is not None and digest(self.reader()) != digest(context):
                raise ConflictError('canonical source changed during relationship review')
            self.db.commit(); return event
        except Exception:
            self.db.rollback(); raise

    def proofs(self, context):
        result = copy.deepcopy(context)
        events = self.history()
        result['pins']['relationship_head_sha256'] = events[-1]['head_sha256'] if events else ZERO
        result['pins']['relationship_revision'] = len(events)
        for row in result['rows']:
            prior = [e for e in events if e['assertion']['reference'] == row['reference']
                     and e['assertion']['coordinates'] == row['coordinates']]
            state, reason = 'unknown', 'Independent exhaustive owner relationship review absent'
            if prior:
                event = prior[-1]
                row['relationship_review'] = {'assertion': event['assertion'], 'action': event['action'],
                                               'created_at': event['created_at']}
                if event['action'] == 'reverse':
                    state, reason = 'revoked', 'Independent owner relationship review withdrawn'
                else:
                    try:
                        self.validate(event['assertion'], context)
                        for name in ('same-attempt', 'source-conflict'):
                            row['upstream'][name] = {'value': event['assertion'][name], 'event_sha256': event['head_sha256']}
                        state, reason = 'current', 'Owner reviewed the exact exhaustive retained inventory against current canonical state'
                    except ValueError:
                        state, reason = 'stale', 'Canonical state or exact retained inventory changed since owner review'
            row.setdefault('diagnostics', {})['relationship'] = {
                'state': state, 'reason': reason, 'revision': prior[-1]['revision'] if prior else None,
                'event_sha256': prior[-1]['head_sha256'] if prior else None,
                'authority': 'independent-exhaustive-owner-relationship-review/v1'}
        return result
