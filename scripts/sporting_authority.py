"""Separate, exact-evidence owner sporting reviews. Never imports old approvals.

The live context reader is a trusted private boundary, recomputed before every
stage, review and export. No publisher boolean grants currentness. Public facts
are signed with an owner-only Ed25519 key; the challenge HMAC is not a signer.
"""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
from urllib.parse import urlsplit

from owner_decision_store import ConflictError
from sporting_rule_bindings import SEMANTIC, require_meaning

POLICY = 'aida-baseline-v1'
ZERO = '0' * 64
HASH = re.compile(r'[a-f0-9]{64}\Z')
SOURCE_FACTS = {'finality', 'source-authority', 'sanction', 'listing',
                'international-sanction', 'official-event-placing', 'source-view'}
FACTS = {'source-view', 'source-authority', 'finality', 'sanction', 'review', 'outcome',
         'same-attempt', 'source-conflict', 'final', 'scoring-policy', 'source-gender',
         'comparable-category', 'represented-country', 'listing', 'international-sanction',
         'official-event-placing'}


def keys(value, required, optional=()):
    if not isinstance(value, dict) or not required <= set(value) or set(value) - required - set(optional):
        raise ValueError('unexpected sporting evidence fields')


def citation(value, hashes=True):
    keys(value, {'url'} | ({'source-sha256', 'artifact-sha256'} if hashes else set()),
         {'page', 'line', 'table', 'row', 'source-sha256', 'artifact-sha256'})
    url = urlsplit(value['url'])
    if (url.scheme != 'https' or not url.hostname or url.username or url.password or url.fragment
            or len(value['url']) > 500 or any(not isinstance(value[k], str) or not HASH.fullmatch(value[k])
                                               for k in ('source-sha256', 'artifact-sha256') if k in value)):
        raise ValueError('invalid sporting citation')
    for key in ('page', 'line', 'table', 'row'):
        if key in value and (type(value[key]) is not int or value[key] < 1):
            raise ValueError('invalid sporting coordinates')


def final_value(value):
    keys(value, {'value', 'unit', 'basis', 'decimal-places', 'conversion'})
    if (value['value'] is not None and (type(value['value']) not in (int, float) or value['value'] < 0)
            or value['unit'] != 'm' or value['basis'] not in ('verified-post-penalty', 'verified-source-achieved', 'unknown')
            or type(value['decimal-places']) is not int or not 0 <= value['decimal-places'] <= 6
            or value['conversion'] not in ('verified', 'unknown')
            or value['basis'] == 'unknown' and (value['value'] is not None or value['conversion'] != 'unknown')):
        raise ValueError('invalid sporting final value')
    canonical(value)


def unknown_fact(value):
    if isinstance(value, dict):
        return all(unknown_fact(item) for item in value.values())
    return value is None or value == 'unknown'


def require_source_fact(row, name, value):
    if unknown_fact(value):
        return
    proof = row.get('upstream', {}).get(name)
    if (not isinstance(proof, dict) or set(proof) != {'value', 'event_sha256'}
            or not isinstance(proof['event_sha256'], str) or not HASH.fullmatch(proof['event_sha256'])
            or proof['value'] != value):
        raise ValueError('independent exact upstream source fact absent or changed: ' + name)


def validate_publication(publication, evidence, rules, context):
    keys(publication, {'schema', 'rows', 'cutoff', 'cohort'})
    if publication['schema'] != 'public-sporting/v1' or not isinstance(publication['rows'], list) or not 1 <= len(publication['rows']) <= 1000:
        raise ValueError('invalid sporting publication')
    instant(publication['cutoff'])
    if not isinstance(evidence, list) or len(evidence) != len(publication['rows']):
        raise ValueError('exact sporting evidence required')
    optional_facts = set().union(*(set(row.get('facts', {})) - FACTS for row in publication['rows']))
    if optional_facts - {'source-selection'}:
        raise ValueError('unsupported sporting facts')
    rule_fields = FACTS | {'cohort'} | optional_facts
    if any(row.get('hypothetical') is not None for row in publication['rows']):
        rule_fields.add('hypothetical')
    keys(rules, rule_fields)
    for rule in rules.values():
        keys(rule, {'url', 'source-sha256', 'locator'}, {'edition', 'section', 'claim'})
        if (context['rules'].get(rule['source-sha256']) != rule['url']
                or not isinstance(rule['locator'], str) or not 1 <= len(rule['locator']) <= 500):
            raise ValueError('sporting rule not pinned in current private authority')
    ids, versions, positions = [], set(), set()
    for row, proof in zip(publication['rows'], evidence):
        keys(row, {'result-id', 'reference', 'source', 'facts'}, {'hypothetical'})
        ref = row['reference']
        keys(ref, {'result-id', 'observation-id', 'ordinal', 'source-sha256', 'artifact-sha256'})
        if (type(ref['ordinal']) is not int or ref['ordinal'] < 0
                or any(not isinstance(ref[k], str) or not HASH.fullmatch(ref[k]) for k in ref if k != 'ordinal')
                or row['result-id'] != ref['result-id'] or row['result-id'] in ids):
            raise ValueError('invalid or duplicate sporting reference')
        keys(proof, {'reference', 'retained_reference', 'coordinates'})
        if proof['reference'] != ref:
            raise ValueError('sporting public reference changed')
        coordinates = proof['coordinates']
        matches = [r for r in context['rows'] if r['reference'] == proof['retained_reference']
                   and r['coordinates'] == proof['coordinates']]
        if len(matches) != 1:
            raise ValueError('sporting parser or source coordinates changed')
        match, private_ref = matches[0], proof['retained_reference']
        if (not private_ref.get('parser-version')
                or any(ref[k] != private_ref.get(k) for k in ('ordinal', 'source-sha256', 'artifact-sha256'))
                or any(match[k] != v for k, v in {'year': '2026', 'environment': 'pool', 'discipline': 'dnf', 'gender': 'women'}.items())):
            raise ValueError('unsupported or unbound sporting scope')
        if match.get('public_reference') is not None and match['public_reference'] != ref:
            raise ValueError('independent public observation mapping changed')
        position = digest({'source-sha256': ref['source-sha256'],
                           'coordinates': {k: v for k, v in proof['coordinates'].items()
                                           if k in ('page', 'line', 'table', 'row')}})
        if position in positions:
            raise ValueError('duplicate physical sporting position')
        positions.add(position)
        ids.append(row['result-id']); versions.add(ref['artifact-sha256'])
        keys(row['source'], {'federation', 'event-id', 'view-id'})
        if row['source']['federation'] not in ('CMAS', 'AIDA') or any(not isinstance(row['source'][k], str) or not re.fullmatch('[a-z0-9-]{1,100}', row['source'][k]) for k in ('event-id', 'view-id')):
            raise ValueError('invalid sporting source')
        keys(row['facts'], FACTS, {'source-selection'})
        for name, fact in row['facts'].items():
            keys(fact, {'value', 'binding', 'policy', 'citation'})
            citation(fact['citation'])
            if (fact['binding'] != ref or fact['policy'] != POLICY
                    or any(fact['citation'][k] != ref[k] for k in ('source-sha256', 'artifact-sha256'))
                    or any(fact['citation'].get(k) != v for k, v in proof['coordinates'].items() if k in ('page', 'line', 'table', 'row'))):
                raise ValueError('sporting fact binding changed')
        facts = {k: f['value'] for k, f in row['facts'].items()}
        for name in SOURCE_FACTS:
            require_source_fact(match, name, facts[name])
        for name in SEMANTIC - {'hypothetical'}:
            require_meaning(match, name, facts[name], rules[name], POLICY)
        for name in ('review', 'same-attempt', 'source-conflict'):
            proof = match.get('upstream', {}).get(name)
            default = 'unresolved' if name == 'source-conflict' else 'unknown'
            if proof is None:
                if facts[name] != default:
                    raise ValueError('independent exact upstream review absent')
            elif (set(proof) != {'value', 'event_sha256'} or not HASH.fullmatch(proof['event_sha256'])
                  or proof['value'] != facts[name]):
                raise ValueError('independent exact upstream review changed')
        if 'source-selection' in facts:
            proof = match.get('upstream', {}).get('source-selection')
            if (not isinstance(proof, dict) or set(proof) != {'value', 'event_sha256'}
                    or proof['value'] != facts['source-selection'] or not HASH.fullmatch(proof['event_sha256'])):
                raise ValueError('independent exact source selection absent')
            choice = facts['source-selection']
            keys(choice, {'selected-reference', 'conflicting-references', 'basis', 'authority-citation',
                          'selection-citation'}, {'equal-authority', 'tie-break-rule'})
            if choice['selected-reference'] != ref or choice['basis'] != 'source-authority':
                raise ValueError('invalid exact source selection')
            citation(choice['authority-citation'], hashes=False); citation(choice['selection-citation'], hashes=False)
            if (not isinstance(choice['conflicting-references'], list)
                    or choice.get('equal-authority') is not None and type(choice['equal-authority']) is not bool
                    or choice.get('tie-break-rule') not in (None, 'exact-reference-lexical-v1')):
                raise ValueError('invalid exact source selection rule')
            for conflict in choice['conflicting-references']:
                keys(conflict, set(ref))
                if (type(conflict['ordinal']) is not int or conflict['ordinal'] < 0
                        or any(not isinstance(conflict[k], str) or not HASH.fullmatch(conflict[k]) for k in conflict if k != 'ordinal')):
                    raise ValueError('invalid conflicting source reference')
        for name, allowed in {
            'source-authority': ('official-results', 'unknown'), 'finality': ('verified-final', 'unknown'),
            'sanction': ('eligible', 'unknown'), 'review': ('verified', 'unknown'),
            'outcome': ('finally-valid', 'finally-valid-penalized', 'disqualified', 'unknown'),
            'same-attempt': ('distinct', 'unknown'), 'source-conflict': ('resolved', 'unresolved', 'selected-provisional'),
            'source-gender': ('Women', None, 'unknown'), 'scoring-policy': (POLICY,)}.items():
            if facts[name] not in allowed:
                raise ValueError('unchecked sporting fact')
        keys(facts['source-view'], {'federation', 'event-id', 'view-id', 'kind', 'environment'})
        if (any(facts['source-view'][k] != v for k, v in row['source'].items())
                or facts['source-view']['kind'] not in ('attempts', 'results', 'totals', 'unknown')
                or facts['source-view']['environment'] not in ('pool', 'unknown')):
            raise ValueError('sporting source view mismatch')
        final_value(facts['final'])
        if facts['outcome'] == 'disqualified' and facts['final']['value'] is not None:
            raise ValueError('disqualified source cannot report a real final distance')
        cat = facts['comparable-category']
        keys(cat, {'group', 'para-class', 'age-class', 'age-equivalence'})
        if (cat['group'] not in ('women', 'unknown') or cat['para-class'] not in ('non-para', 'para', 'unknown')
                or cat['age-class'] not in ('seniors', 'juniors', 'unknown') or cat['age-equivalence'] not in ('verified', 'unknown')):
            raise ValueError('invalid sporting category')
        if facts['represented-country'] is not None and (not isinstance(facts['represented-country'], str) or not re.fullmatch('[A-Z]{3}', facts['represented-country'])):
            raise ValueError('invalid sporting country')
        keys(facts['listing'], {'publisher', 'kind'})
        keys(facts['international-sanction'], {'authority', 'level', 'status'})
        if (facts['listing']['publisher'] not in ('CMAS', 'AIDA', 'local-organizer', 'unknown')
                or facts['listing']['kind'] not in ('archive', 'calendar', 'national-archive', 'local-results', 'unknown')
                or facts['international-sanction']['authority'] not in ('CMAS', 'AIDA', 'national-federation', 'unknown')
                or facts['international-sanction']['level'] not in ('international', 'national', 'unknown')
                or facts['international-sanction']['status'] not in ('verified', 'unsanctioned', 'unknown')
                or facts['official-event-placing'] is not None and (type(facts['official-event-placing']) is not int or facts['official-event-placing'] < 1)):
            raise ValueError('invalid listing, sanction or placing')
        if row.get('hypothetical') is not None:
            hypothetical = row['hypothetical']
            keys(hypothetical, {'value', 'binding', 'policy', 'citation'})
            final_value(hypothetical['value']); citation(hypothetical['citation'])
            require_meaning(match, 'hypothetical', hypothetical['value'], rules['hypothetical'], POLICY)
            if (facts['outcome'] != 'disqualified' or hypothetical['binding'] != ref
                    or hypothetical['policy'] != POLICY or hypothetical['value']['basis'] != 'verified-source-achieved'
                    or any(hypothetical['citation'][k] != ref[k] for k in ('source-sha256', 'artifact-sha256'))
                    or any(hypothetical['citation'].get(k) != v for k, v in coordinates.items() if k in ('page', 'line', 'table', 'row'))):
                raise ValueError('hypothetical must remain exact cited DQ achieved distance')
    cohort = publication['cohort']
    keys(cohort, {'binding', 'value', 'citation'})
    keys(cohort['binding'], {'cohort-id', 'policy', 'source-versions'})
    citation(cohort['citation'], hashes=False)
    if (not HASH.fullmatch(cohort['binding']['cohort-id']) or cohort['binding']['policy'] != POLICY
            or cohort['value'] != sorted(ids) or cohort['binding']['source-versions'] != sorted(versions)):
        raise ValueError('sporting selected cohort changed')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=False, allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def private_bytes(path, maximum=4 * 1024 * 1024):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('private sporting path is a symlink')
    info = path.stat()
    if (not path.is_file() or info.st_uid not in (0, os.geteuid())
            or info.st_mode & 0o027 or info.st_size > maximum):
        raise ValueError('private sporting file permissions invalid')
    return path.read_bytes()


def instant(value):
    if not isinstance(value, str) or len(value) > 40:
        raise ValueError('invalid sporting expiry')
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0):
        raise ValueError('sporting time must be UTC')
    return parsed


class SportingAuthority:
    def __init__(self, ledger_path, signing_key_path, context_reader, *, clock=None):
        self.path = Path(ledger_path).absolute()
        self.signing_key_path = Path(signing_key_path).absolute()
        self.context_reader = context_reader
        self.clock = clock or (lambda: datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'))
        private_bytes(self.signing_key_path, 8192)
        if any(p.is_symlink() for p in (self.path, *self.path.parents)):
            raise ValueError('private sporting ledger symlink')
        if not self.path.parent.exists():
            self.path.parent.mkdir(mode=0o700, parents=True)
        if self.path.parent.stat().st_mode & 0o077:
            raise ValueError('private sporting ledger directory permissions invalid')
        if self.path.exists():
            private_bytes(self.path, 32 * 1024 * 1024)
        self.db = sqlite3.connect(self.path, timeout=15, isolation_level=None, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.path.chmod(0o600)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS sporting_events(
            revision INTEGER PRIMARY KEY, safe_json TEXT NOT NULL,
            private_json TEXT NOT NULL, operation_key TEXT NOT NULL UNIQUE,
            request_sha256 TEXT NOT NULL);
          CREATE TRIGGER IF NOT EXISTS sporting_no_update BEFORE UPDATE ON sporting_events
            BEGIN SELECT RAISE(ABORT, 'immutable sporting event'); END;
          CREATE TRIGGER IF NOT EXISTS sporting_no_delete BEFORE DELETE ON sporting_events
            BEGIN SELECT RAISE(ABORT, 'immutable sporting event'); END;
        ''')

    def close(self):
        self.db.close()

    def _history(self):
        if hasattr(self, "read_lock"):
            with self.read_lock():
                return self._history_unlocked()
        return self._history_unlocked()

    def _history_unlocked(self):
        events, previous = [], ZERO
        for row in self.db.execute('SELECT * FROM sporting_events ORDER BY revision'):
            event, detail = json.loads(row['safe_json']), json.loads(row['private_json'])
            unsigned = {k: v for k, v in event.items() if k != 'head_sha256'}
            if (event['revision'] != len(events) + 1 or event['previous_sha256'] != previous
                    or digest(unsigned) != event['head_sha256']
                    or digest(detail) != event['decision_sha256']):
                raise ValueError('sporting ledger integrity failed')
            previous = event['head_sha256']
            events.append((event, detail))
            if len(events) > 10000:
                raise ValueError('sporting ledger event bound exceeded')
        return events

    def _context(self, review=False):
        context = (self.context_reader.review() if review and hasattr(self.context_reader, 'review')
                   else self.context_reader())
        if not isinstance(context, dict) or set(context) != {'pins', 'rows', 'rules'}:
            raise ValueError('current source authority unavailable')
        canonical(context)
        return context

    def _commit(self, request, expected_revision, key, proposal, action, actor=None, reason=None):
        if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', key):
            raise ValueError('invalid sporting operation key')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            old = self.db.execute('SELECT * FROM sporting_events WHERE operation_key=?', (key,)).fetchone()
            if old:
                if old['request_sha256'] != digest(request):
                    raise ConflictError('sporting operation key conflict')
                self.db.execute('ROLLBACK')
                return json.loads(old['safe_json'])
            history = self._history()
            if type(expected_revision) is not int or expected_revision != len(history):
                raise ConflictError('sporting authority revision changed')
            # Withdrawal authenticates the owner and exact prior decision. It
            # needs no positive source attestation and remains possible offline.
            context = None if action == 'reverse' else self._context(review=True)
            if action == 'stage':
                self._validate(proposal, context)
                if any(d['proposal']['id'] == proposal['id'] for _, d in history):
                    raise ConflictError('sporting proposal ID already staged')
                proposal = {**proposal, 'context_sha256': digest(context)}
            else:
                found = [(e, d) for e, d in history if d['proposal']['id'] == proposal['id']]
                required = {'source-approve': ('stage', 'reverse'), 'select-cohort': ('source-approve',),
                            'publish': ('select-cohort',), 'reverse': ('source-approve', 'select-cohort', 'publish')}
                if not found or found[-1][1]['domain_action'] not in required[action]:
                    raise ConflictError('sporting review domain sequence changed')
            if action not in ('stage', 'reverse'):
                if proposal['context_sha256'] != digest(context):
                    raise ConflictError('sporting source binding changed')
                self._validate({k: v for k, v in proposal.items() if k != 'context_sha256'}, context)
            if action == 'publish':
                for item in proposal['evidence']:
                    row = next(r for r in context['rows'] if r['reference'] == item['retained_reference'])
                    proof = row.get('upstream', {}).get('publication')
                    if (not isinstance(proof, dict) or set(proof) != {'value', 'event_sha256', 'reference'}
                            or proof['value'] != 'approved' or not HASH.fullmatch(proof['event_sha256'])
                            or proof['reference'] != item['reference'] or row.get('public_reference') != item['reference']):
                        raise ValueError('independent exact public eligibility absent')
            detail = {'proposal': proposal, 'actor': actor, 'reason': reason, 'created_at': self.clock(),
                      'domain_action': action, 'current_context_sha256': digest(context) if context is not None else None}
            event = {'revision': len(history) + 1,
                     'previous_sha256': history[-1][0]['head_sha256'] if history else ZERO,
                     'action': 'approve' if action == 'publish' else 'reverse' if action == 'reverse' else 'stage',
                     'publication_sha256': digest(proposal['publication']) if action != 'reverse' else None,
                     'binding_sha256': digest(context) if context is not None else proposal['context_sha256'], 'policy': POLICY,
                     'decision_sha256': digest(detail)}
            event['head_sha256'] = digest(event)
            self.db.execute('INSERT INTO sporting_events VALUES(?,?,?,?,?)',
                            (event['revision'], canonical(event).decode(), canonical(detail).decode(), key, digest(request)))
            if context is not None and digest(self._context(review=True)) != digest(context):
                raise ConflictError('sporting authority changed during operation')
            self.db.execute('COMMIT')
            return event
        except Exception:
            if self.db.in_transaction:
                self.db.execute('ROLLBACK')
            raise

    def _validate(self, proposal, context):
        if not isinstance(proposal, dict) or set(proposal) != {'id', 'publication', 'evidence', 'rules', 'valid_until'}:
            raise ValueError('invalid sporting proposal')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', proposal['id']):
            raise ValueError('invalid sporting proposal ID')
        if instant(proposal['valid_until']) <= instant(self.clock()):
            raise ValueError('sporting proposal expired')
        validate_publication(proposal['publication'], proposal['evidence'], proposal['rules'], context)

    def stage(self, proposal, *, expected_revision, idempotency_key):
        return self._commit({'proposal': proposal, 'expected_revision': expected_revision},
                            expected_revision, idempotency_key, proposal, 'stage')

    def act(self, proposal_id, *, action, actor, reason, expected_revision, idempotency_key):
        if action not in ('source-approve', 'select-cohort', 'publish', 'reverse') or not isinstance(actor, str) or '@' not in actor:
            raise ValueError('authenticated owner action required')
        if not isinstance(reason, str) or not 1 <= len(reason) <= 1000:
            raise ValueError('review reason required')
        found = [(e, d) for e, d in self._history() if d['proposal']['id'] == proposal_id]
        if not found:
            raise KeyError(proposal_id)
        return self._commit({'id': proposal_id, 'action': action, 'actor': actor, 'reason': reason,
                             'expected_revision': expected_revision}, expected_revision, idempotency_key,
                            found[-1][1]['proposal'], action, actor, reason)

    def review(self):
        history = self._history()
        current = {}
        try:
            binding = digest(self._context(review=bool(history)))
        except (ValueError, OSError, sqlite3.Error, KeyError, TypeError, subprocess.SubprocessError):
            binding = None
        for event, detail in history:
            status = ('unavailable' if binding is None else 'stale'
                      if detail['proposal']['context_sha256'] != binding or instant(detail['proposal']['valid_until']) <= instant(self.clock())
                      else 'withdrawn' if detail['domain_action'] == 'reverse' else 'current')
            current[detail['proposal']['id']] = {**detail, 'action': detail['domain_action'], 'revision': event['revision'],
                                                'authority_status': status}
        return {'schema': 'sporting-authority-review/v1', 'revision': len(history),
                'currentness': 'available' if binding is not None else 'unavailable', 'proposals': list(current.values())}

    def current(self, nonce):
        if not isinstance(nonce, str) or not HASH.fullmatch(nonce):
            raise ValueError('invalid sporting challenge')
        history = self._history()
        publication, binding, status = None, None, 'unavailable'
        try:
            context = self._context(review=bool(history))
            binding, status = digest(context), 'current'
            if history:
                event, detail = history[-1]
                proposal = detail['proposal']
                if (event['action'] == 'approve' and proposal['context_sha256'] == binding
                        and instant(proposal['valid_until']) > instant(self.clock())):
                    self._validate({k: v for k, v in proposal.items() if k != 'context_sha256'}, context)
                    publication = proposal['publication']
            if digest(self._context(review=bool(history))) != binding:
                raise ConflictError('sporting source changed during export')
        except (ValueError, OSError, sqlite3.Error, KeyError, TypeError):
            publication, binding, status = None, None, 'unavailable'
        latest = self._history()
        if ([e['head_sha256'] for e, _ in latest] != [e['head_sha256'] for e, _ in history]):
            history = latest
            publication, binding, status = None, None, 'unavailable'
        now = instant(self.clock())
        payload = {'schema': 'sporting-authority-current/v1', 'nonce': nonce, 'revision': len(history),
                   'head_sha256': history[-1][0]['head_sha256'] if history else ZERO,
                   'previous_sha256': history[-1][0]['previous_sha256'] if history else ZERO,
                   'issued_at': now.isoformat().replace('+00:00', 'Z'),
                   'expires_at': (now + timedelta(seconds=5)).isoformat().replace('+00:00', 'Z'),
                   'status': status, 'binding_sha256': binding, 'publication': publication,
                   'publication_sha256': digest(publication) if publication is not None else None,
                   'policy': POLICY, 'events': [e for e, _ in history]}
        return self._sign(payload)

    def _sign(self, payload):
        private_bytes(self.signing_key_path, 8192)
        public = subprocess.run(['openssl', 'pkey', '-in', str(self.signing_key_path), '-pubout', '-outform', 'DER'],
                                check=True, capture_output=True, timeout=5).stdout
        with tempfile.NamedTemporaryFile() as data:
            data.write(canonical(payload)); data.flush()
            signature = subprocess.run(['openssl', 'pkeyutl', '-sign', '-rawin', '-inkey', str(self.signing_key_path),
                                        '-in', data.name], check=True, capture_output=True, timeout=5).stdout
        return {'schema': 'sporting-authority-envelope/v1', 'payload': payload,
                'signature_base64': base64.b64encode(signature).decode('ascii'),
                'key_id': hashlib.sha256(public).hexdigest()}
