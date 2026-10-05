"""Portable verifier for the EDN ledger written by reconciliation_flow.clj.

This intentionally accepts only the persisted EDN subset used by the flow writer.
Unknown EDN forms fail closed. The digest matches canonical/pr-str in that writer.
"""

import hashlib
import json
import os
import re
import stat
from pathlib import Path


class LedgerError(ValueError):
    pass


class Keyword(str):
    def __eq__(self, other):
        return type(other) is Keyword and str.__eq__(self, other)

    def __ne__(self, other):
        return not self.__eq__(other)

    def __hash__(self):
        return hash((Keyword, str(self)))


class Parser:
    def __init__(self, source):
        self.source = source
        self.index = 0

    def space(self):
        while self.index < len(self.source):
            char = self.source[self.index]
            if char.isspace() or char == ',':
                self.index += 1
            elif char == ';':
                end = self.source.find('\n', self.index)
                self.index = len(self.source) if end < 0 else end + 1
            else:
                break

    def value(self):
        self.space()
        if self.index >= len(self.source):
            raise LedgerError('incomplete EDN')
        char = self.source[self.index]
        if char in '{[(':
            closing = {'{': '}', '[': ']', '(': ')'}[char]
            self.index += 1
            items = []
            while True:
                self.space()
                if self.index >= len(self.source):
                    raise LedgerError('incomplete EDN collection')
                if self.source[self.index] == closing:
                    self.index += 1
                    break
                items.append(self.value())
            if char == '{':
                if len(items) % 2:
                    raise LedgerError('odd EDN map')
                result = {}
                for key, item in zip(items[::2], items[1::2]):
                    if not isinstance(key, (str, int, float)) or key in result:
                        raise LedgerError('duplicate or unsupported EDN map key')
                    result[key] = item
                return result
            return items
        if char == '"':
            start = self.index
            self.index += 1
            escaped = False
            while self.index < len(self.source):
                current = self.source[self.index]
                self.index += 1
                if escaped and current not in '"\\ntrbfu':
                    raise LedgerError('unsupported EDN string escape')
                if current == '"' and not escaped:
                    try:
                        return json.loads(self.source[start:self.index])
                    except ValueError as error:
                        raise LedgerError('unsupported EDN string') from error
                if current == '\\' and not escaped:
                    escaped = True
                else:
                    escaped = False
            raise LedgerError('incomplete EDN string')
        start = self.index
        while (self.index < len(self.source) and
               not self.source[self.index].isspace() and
               self.source[self.index] not in ',{}[]();'):
            self.index += 1
        token = self.source[start:self.index]
        if not token:
            raise LedgerError('unsupported EDN form')
        if re.fullmatch(r':[A-Za-z_*!?+./<>=-][A-Za-z0-9_*!?+./<>=:-]*', token):
            return Keyword(token)
        if token == 'nil':
            return None
        if token == 'true':
            return True
        if token == 'false':
            return False
        if re.fullmatch(r'-?(?:0|[1-9][0-9]*)', token):
            return int(token)
        if re.fullmatch(r'-?(?:0|[1-9][0-9]*)\.[0-9]+(?:[eE][+-]?[0-9]+)?', token):
            return float(token)
        raise LedgerError('unsupported EDN atom')


def parse(source):
    parser = Parser(source)
    result = parser.value()
    parser.space()
    if parser.index != len(source):
        raise LedgerError('trailing EDN forms')
    return result


def pr_str(value):
    if isinstance(value, dict):
        return '{' + ', '.join(pr_str(key) + ' ' + pr_str(item)
                                for key, item in sorted(value.items(), key=lambda pair: pr_str(pair[0]))) + '}'
    if isinstance(value, list):
        return '[' + ' '.join(map(pr_str, value)) + ']'
    if isinstance(value, Keyword):
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if value is None:
        return 'nil'
    if value is True:
        return 'true'
    if value is False:
        return 'false'
    if type(value) is int:
        return str(value)
    if type(value) is float and value == value and abs(value) != float('inf'):
        return str(value)
    raise LedgerError('unsupported EDN value')


def load_ledger(path):
    path = Path(path)
    if not path.is_absolute():
        raise LedgerError('private ledger path must be absolute')
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise LedgerError('private ledger must be a regular file')
        with os.fdopen(descriptor, 'r', encoding='utf-8') as stream:
            descriptor = None
            envelope = parse(stream.read())
    finally:
        if descriptor is not None:
            os.close(descriptor)
    if not isinstance(envelope, dict) or not isinstance(envelope.get(Keyword(':ledger')), dict):
        raise LedgerError('invalid private ledger envelope')
    ledger = envelope[Keyword(':ledger')]
    if (ledger.get(Keyword(':version')) != 'reconciliation-flow/1' or
            not isinstance(ledger.get(Keyword(':events')), list) or
            hashlib.sha256(pr_str(ledger).encode('utf-8')).hexdigest() !=
            envelope.get(Keyword(':sha256'))):
        raise LedgerError('private ledger integrity check failed')
    return ledger


def _get(value, *keys):
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(Keyword(':' + key))
    return value


def _edn_shape(value):
    if isinstance(value, dict):
        return {Keyword(':' + key): _edn_shape(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [_edn_shape(item) for item in value]
    return value


def verify(path, decision_id, run_revision, event_id, bindings):
    ledger = load_ledger(path)
    events = _get(ledger, 'events')
    if type(run_revision) is not int or run_revision < 1 or run_revision != len(events):
        raise LedgerError('stale reconciliation run')
    event = next((item for item in reversed(events)
                  if _get(item, 'decision-id') == decision_id), None)
    if (not isinstance(event, dict) or _get(event, 'id') != event_id or
            _get(event, 'family') != Keyword(':same-attempt') or
            _get(event, 'origin') == Keyword(':human') or
            not isinstance(bindings, list) or len(bindings) != 2 or
            not isinstance(_get(event, 'evidence'), list) or
            len(_get(event, 'evidence')) != 2):
        raise LedgerError('reconciliation event is stale or unbound')
    evidence = _get(event, 'evidence')
    candidates = _get(event, 'candidates')
    if (not isinstance(candidates, list) or
            len(set(candidates)) != len(candidates) or
            set(candidates) != {binding['evidence_id'] for binding in bindings}):
        raise LedgerError('reconciliation candidates changed')
    for item, binding in zip(evidence, bindings):
        version = _get(item, 'canonical-subject', 'version')
        position = _get(item, 'canonical-subject', 'position')
        source = _get(item, 'canonical-subject', 'source')
        if (not isinstance(binding, dict) or
                _get(item, 'evidence-id') != binding.get('evidence_id') or
                _get(version, 'snapshot-record-id') != binding.get('snapshot_record_id') or
                _get(version, 'observation-revision') != _edn_shape(binding.get('observation_revision')) or
                _get(source, 'sha256') != _get(item, 'citation', 'source-sha256') or
                _get(source, 'sha256') != binding['observation_revision'].get('source_sha256') or
                _get(item, 'citation', 'position-id') != _get(position, 'id') or
                _get(item, 'citation', 'locator') != _get(position, 'locator')):
            raise LedgerError('reconciliation evidence changed')
    return {'verified': True, 'event_id': event_id, 'run_revision': run_revision}
