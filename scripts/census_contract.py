#!/usr/bin/env python3
"""Validate and query a private, dated evidence census projection.

JSON schema census-evidence/v1 carries explicit events, retained sources, source
positions, observation versions, discovery gaps and cited relationships. It is
a read-only projection, not a unique sporting-attempt or coverage calculation.
"""

import argparse
from datetime import date, datetime
import json
from pathlib import Path
import re
import sys

SCHEMA = 'census-evidence/v1'
SHA = re.compile(r'[0-9a-f]{64}\Z')
AUTHORITIES = {'primary', 'mirror', 'ranking', 'community'}
MEDIA = {'pdf', 'html', 'json', 'other'}
POSITION_STATUSES = {'parsed', 'quarantined', 'unparsed'}
GAP_STATUSES = {'checked', 'missing', 'inaccessible', 'unchecked'}
RELATIONSHIP_KINDS = {'same-attempt', 'source-duplicate', 'parser-revision',
                      'supporting-result', 'unknown'}


def need(test, message):
    if not test:
        raise ValueError(message)


def object_value(value, label):
    need(isinstance(value, dict), f'{label} must be an object')
    return value


def array(value, label):
    need(isinstance(value, list), f'{label} must be an array')
    return value


def string(value, label):
    need(isinstance(value, str) and bool(value.strip()), f'{label} must be nonempty')
    return value


def nullable_string(value, label):
    if value is not None:
        string(value, label)


def hash_value(value, label):
    need(isinstance(value, str) and SHA.fullmatch(value) is not None,
         f'{label} must be lowercase SHA-256')


def timestamp(value, label):
    string(value, label)
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise ValueError(f'{label} must be ISO 8601') from None
    need(result.tzinfo is not None, f'{label} needs timezone')


def date_value(value, label):
    string(value, label)
    try:
        need(date.fromisoformat(value).isoformat() == value, f'{label} must be ISO date')
    except ValueError:
        raise ValueError(f'{label} must be ISO date') from None


def unique_id(records, label):
    result = {}
    for index, item in enumerate(array(records, label)):
        object_value(item, f'{label}[{index}]')
        ident = string(item.get('id'), f'{label}[{index}].id')
        need(ident not in result, f'duplicate {label} id: {ident}')
        result[ident] = item
    return result


def validate(document):
    object_value(document, 'snapshot')
    need(document.get('schema') == SCHEMA, 'unsupported census schema')
    string(document.get('scope'), 'scope')
    timestamp(document.get('cutoff'), 'cutoff')
    events = unique_id(document.get('events'), 'events')
    sources = unique_id(document.get('sources'), 'sources')
    positions = unique_id(document.get('positions'), 'positions')
    gaps = unique_id(document.get('gaps'), 'gaps')
    relationships = unique_id(document.get('relationships'), 'relationships')
    for event in events.values():
        string(event.get('federation'), 'federation')
        string(event.get('name'), 'event name')
        date_value(event.get('held_from'), 'held_from')
        date_value(event.get('held_to'), 'held_to')
        need(event['held_from'] <= event['held_to'], 'event date range reversed')
    for source in sources.values():
        event_ids = array(source.get('event_ids'), 'source event_ids')
        need(len(event_ids) == len(set(event_ids)), 'duplicate source event_id')
        need(all(event_id in events for event_id in event_ids), 'source has unknown event')
        need(source.get('authority') in AUTHORITIES, 'invalid source authority')
        need(source.get('media') in MEDIA, 'invalid source media')
        hash_value(source.get('original_sha256'), 'original_sha256')
        if source.get('derived_sha256') is not None:
            hash_value(source['derived_sha256'], 'derived_sha256')
        for field in ('acquisition_id', 'discovery_url', 'final_url'):
            nullable_string(source.get(field), field)
        if source.get('retrieved_at') is not None:
            timestamp(source['retrieved_at'], 'retrieved_at')
        selected = source.get('selected_view')
        if selected is not None:
            object_value(selected, 'selected_view')
            string(selected.get('state'), 'selected_view.state')
            string(selected.get('ref'), 'selected_view.ref')
        provenance_gaps = array(source.get('provenance_gaps'), 'provenance_gaps')
        for reason in provenance_gaps:
            string(reason, 'provenance gap reason')
        if any(source.get(field) is None for field in
               ('acquisition_id', 'retrieved_at', 'discovery_url', 'final_url', 'selected_view')):
            need(provenance_gaps, 'null source provenance needs provenance_gaps')
    observed = {}
    for position in positions.values():
        need(position.get('source_id') in sources, 'position has unknown source')
        string(position.get('locator'), 'position locator')
        for field in ('session', 'category'):
            nullable_string(position.get(field), field)
        need(position.get('status') in POSITION_STATUSES, 'invalid position status')
        raw = position.get('raw_fields')
        need(raw is None or isinstance(raw, dict), 'raw_fields must be object or null')
        if raw is None:
            string(position.get('unresolved_reason'), 'unresolved_reason')
        else:
            nullable_string(position.get('unresolved_reason'), 'unresolved_reason')
        for ref in array(position.get('observation_refs'), 'observation_refs'):
            object_value(ref, 'observation ref')
            job = string(ref.get('job_id'), 'job_id')
            ordinal = ref.get('ordinal')
            need(type(ordinal) is int and ordinal >= 0, 'ordinal must be nonnegative integer')
            hash_value(ref.get('artifact_sha256'), 'artifact_sha256')
            string(ref.get('parser_version'), 'parser_version')
            string(ref.get('citation'), 'citation')
            key = (job, ordinal)
            if key in observed:
                need(observed[key] == ref, 'conflicting observation version reference')
            observed[key] = ref
    for gap in gaps.values():
        need(gap.get('scope') in {'event', 'source', 'search'}, 'invalid gap scope')
        if gap['scope'] == 'event':
            need(gap.get('ref') in events, 'gap has unknown event')
        elif gap['scope'] == 'source':
            need(gap.get('ref') in sources, 'gap has unknown source')
        else:
            string(gap.get('ref'), 'search route ref')
        need(gap.get('status') in GAP_STATUSES, 'invalid gap status')
        string(gap.get('reason'), 'gap reason')
    for relation in relationships.values():
        need(relation.get('kind') in RELATIONSHIP_KINDS, 'invalid relationship kind')
        need(relation.get('status') in {'exact', 'unknown'}, 'invalid relationship status')
        need(relation['kind'] != 'unknown' or relation['status'] == 'unknown',
             'unknown relationship kind requires unknown status')
        need(relation.get('left') in positions and relation.get('right') in positions,
             'relationship has unknown position')
        string(relation.get('basis'), 'relationship basis')
    return {'schema': 'census-evidence-report/v1', 'scope': document['scope'],
            'cutoff': document['cutoff'],
            'counts': {'events': len(events), 'sources': len(sources),
                       'positions': len(positions), 'observation_versions': len(observed),
                       'gaps': len(gaps), 'relationships': len(relationships)},
            'distinct_attempts': None,
            'limits': ['Supplied records only; no event completeness assertion.',
                       'Observation versions and source positions are not distinct attempts.']}


def query(document, event=None, source=None, status=None):
    summary = validate(document)
    if event is not None:
        need(any(item['id'] == event for item in document['events']), 'unknown event filter')
    if source is not None:
        need(any(item['id'] == source for item in document['sources']), 'unknown source filter')
    if status is not None:
        need(status in POSITION_STATUSES, 'unknown position status filter')
    events = [item for item in document['events'] if event is None or item['id'] == event]
    sources = [item for item in document['sources'] if
               (event is None or event in item['event_ids']) and
               (source is None or item['id'] == source)]
    source_ids = {item['id'] for item in sources}
    positions = [item for item in document['positions'] if item['source_id'] in source_ids
                 and (status is None or item['status'] == status)]
    position_ids = {item['id'] for item in positions}
    gaps = [item for item in document['gaps'] if
            item['scope'] == 'search' or
            (item['scope'] == 'event' and item['ref'] in {e['id'] for e in events}) or
            (item['scope'] == 'source' and item['ref'] in source_ids)]
    relationships = [item for item in document['relationships'] if
                     item['left'] in position_ids and item['right'] in position_ids]
    return {'schema': SCHEMA, 'scope': document['scope'], 'cutoff': document['cutoff'],
            'events': events, 'sources': sources, 'positions': positions,
            'gaps': gaps, 'relationships': relationships,
            'distinct_attempts': summary['distinct_attempts']}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    validate_cmd = commands.add_parser('validate')
    validate_cmd.add_argument('snapshot', type=Path)
    query_cmd = commands.add_parser('query')
    query_cmd.add_argument('--event')
    query_cmd.add_argument('--source')
    query_cmd.add_argument('--status')
    query_cmd.add_argument('snapshot', type=Path)
    args = parser.parse_args(argv)
    try:
        data = json.loads(args.snapshot.read_text(encoding='utf-8'))
        result = validate(data) if args.command == 'validate' else query(
            data, args.event, args.source, args.status)
    except (OSError, ValueError, UnicodeError) as error:
        print(f'census rejected: {error}', file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
