#!/usr/bin/env python3
"""Validate a dated historical discovery inventory and guard ordinary ingestion.

This bounded 2024 contract counts retained source objects, printed positions and
observation versions separately. It never establishes global completeness or
publication authority. All input and output evidence stays private.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from urllib.parse import urlsplit

try:
    from scripts import census_contract as census
except ModuleNotFoundError:
    import census_contract as census

SCHEMA = 'historical-cohort/v1'
STATES = {'checked', 'unchecked', 'unavailable', 'unsupported', 'unresolved'}
DISPOSITIONS = {'resolved', 'unchecked', 'unavailable', 'unsupported', 'unresolved'}
FIELDS = ('event_date', 'discipline', 'category', 'url')


def utc(value, label, cutoff=None):
    census.string(value, label)
    census.need(value.endswith('Z'), f'{label} requires UTC Z timestamp')
    census.timestamp(value, label)
    instant = datetime.fromisoformat(value[:-1] + '+00:00')
    census.need(instant.utcoffset() == timezone.utc.utcoffset(instant),
                f'{label} requires UTC')
    if cutoff is not None:
        census.need(instant <= cutoff, f'{label} exceeds cutoff')
    return instant


def url(value, label):
    census.string(value, label)
    parsed = urlsplit(value)
    census.need(parsed.scheme in {'https', 'http'} and parsed.netloc
                and not parsed.username and not parsed.password,
                f'{label} requires public HTTP URL without credentials')


def _state(record, field):
    census.need(record.get(field) in STATES, f'invalid {field}')
    census.need(record.get('disposition') in DISPOSITIONS, 'invalid disposition')
    census.string(record.get('evidence'), 'evidence')
    if record['disposition'] == 'resolved':
        census.need(record[field] == 'checked', 'resolved disposition requires checked evidence')


def validate(document):
    census.object_value(document, 'manifest')
    census.need(document.get('schema') == SCHEMA, 'unsupported historical schema')
    census.need(type(document.get('cohort_year')) is int
                and document['cohort_year'] == 2024, 'historical cohort must be 2024')
    cutoff = utc(document.get('cutoff'), 'cutoff')
    scope = census.object_value(document.get('scope'), 'scope')
    census.need(scope.get('from') == '2024-01-01' and scope.get('to') == '2024-12-31',
                '2024 date scope required')
    census.string(scope.get('description'), 'scope.description')
    routes = census.unique_id(document.get('routes'), 'routes')
    leads = census.unique_id(document.get('leads'), 'leads')
    census.need(routes, 'known discovery routes required')
    census.need(not (routes.keys() & leads.keys()), 'duplicate route/lead id')
    for route in routes.values():
        census.need(route.get('federation') in {'AIDA', 'CMAS', 'unknown'}, 'invalid federation')
        census.need(route.get('kind') in {'archive', 'calendar', 'national', 'organizer'},
                    'invalid route kind')
        url(route.get('url'), 'route.url')
        census.string(route.get('selector'), 'route.selector')
        census.need('query' in route, 'route.query must be explicit')
        census.nullable_string(route['query'], 'route.query')
        _state(route, 'status')
        pagination = census.object_value(route.get('pagination'), 'pagination')
        census.string(pagination.get('method'), 'pagination.method')
        pages = census.array(pagination.get('checked_pages'), 'checked_pages')
        for page in pages:
            census.string(page, 'checked page')
        census.need(len(pages) == len(set(pages)), 'duplicate checked page')
        census.need('remaining' in pagination, 'pagination.remaining must be explicit')
        census.nullable_string(pagination['remaining'], 'pagination.remaining')
        census.need('checked_at' in route, 'route.checked_at must be explicit')
        if route['checked_at'] is not None:
            utc(route['checked_at'], 'route.checked_at', cutoff)
        if route['status'] != 'unchecked':
            census.need(route['checked_at'] is not None, 'attempted route needs checked_at')
        if route['disposition'] == 'resolved':
            census.need(pagination['remaining'] is None, 'remaining pages prevent resolved route')
            census.need(pages, 'resolved route needs exact checked pages')
    evidence = census.validate(document.get('census'))
    census.need(document['census']['cutoff'] == document['cutoff'], 'census cutoff mismatch')
    source_ids = {item['id'] for item in document['census']['sources']}
    for event in document['census']['events']:
        census.need(event['held_from'].startswith('2024-')
                    and event['held_to'].startswith('2024-'), 'census event outside 2024')
    for source in document['census']['sources']:
        if source['retrieved_at'] is not None:
            utc(source['retrieved_at'], 'source.retrieved_at', cutoff)
        for field in ('discovery_url', 'final_url'):
            if source[field] is not None:
                url(source[field], f'source.{field}')
    for lead in leads.values():
        refs = census.array(lead.get('route_ids'), 'route_ids')
        census.need(refs and all(isinstance(ref, str) and ref in routes for ref in refs),
                    'lead has unknown route')
        census.need(len(refs) == len(set(refs)), 'duplicate lead route')
        census.string(lead.get('event_name'), 'event_name')
        unknown = census.array(lead.get('unknown_fields'), 'unknown_fields')
        census.need(all(isinstance(field, str) and field in FIELDS for field in unknown)
                    and len(unknown) == len(set(unknown)), 'invalid unknown_fields')
        for field in FIELDS:
            census.need(field in lead, f'lead.{field} must be explicit')
            census.nullable_string(lead[field], field)
            census.need((lead[field] is None) == (field in unknown),
                        f'{field} unknown state mismatch')
        if lead['event_date'] is not None:
            census.date_value(lead['event_date'], 'event_date')
            census.need(lead['event_date'].startswith('2024-'), 'lead date outside 2024')
        if lead['url'] is not None:
            url(lead['url'], 'lead.url')
        _state(lead, 'source_state')
        refs = census.array(lead.get('source_ids'), 'source_ids')
        census.need(all(isinstance(ref, str) and ref in source_ids for ref in refs),
                    'lead has unknown source')
        census.need(len(refs) == len(set(refs)), 'duplicate lead source')
        if lead['disposition'] == 'resolved':
            resolution = census.object_value(lead.get('resolution'), 'lead.resolution')
            census.need(resolution.get('kind') in {
                'attempt-results', 'not-attempt-source', 'no-competition-attempts'},
                'invalid lead resolution kind')
            census.string(resolution.get('evidence'), 'lead resolution evidence')
            if resolution['kind'] == 'attempt-results':
                census.need(refs, 'attempt-results resolution needs retained source_ids')
                for ref in refs:
                    positions = [item for item in document['census']['positions']
                                 if item['source_id'] == ref]
                    census.need(positions, 'attempt-results resolution needs cited positions')
                    census.need(all(item['status'] == 'parsed'
                                    and isinstance(item['raw_fields'], dict)
                                    and bool(item['raw_fields'])
                                    and item['observation_refs']
                                    and not item.get('unresolved_reason')
                                    for item in positions),
                                'attempt-results resolution needs parsed raw fields and versions')
    counts = dict(evidence['counts'], routes=len(routes), leads=len(leads))
    return {'schema': 'historical-cohort-report/v1', 'cohort_year': 2024,
            'cutoff': document['cutoff'], 'scope': scope, 'counts': counts,
            'distinct_attempts': None,
            'limits': ['Supplied bounded inventory only; no archive-wide completeness assertion.',
                       'Sources, printed positions and observation versions are separate counts.']}


def gate(document, year):
    census.need(type(year) is int and year == 2023, 'ordinary gate supports 2023 only')
    validate(document)
    blockers = [{'kind': kind, 'ref': item['id'], 'reason': item['evidence']}
                for kind, records in (('route', document['routes']),
                                      ('lead', document['leads']))
                for item in records if item['disposition'] != 'resolved']
    for lead in document['leads']:
        if (lead['unknown_fields'] and lead['disposition'] == 'resolved'
                and lead['resolution']['kind'] == 'attempt-results'):
            blockers.append({'kind': 'lead', 'ref': lead['id'],
                             'reason': 'Unknown event fields: ' + ', '.join(lead['unknown_fields'])})
    for gap in document['census']['gaps']:
        blockers.append({'kind': 'census-gap', 'ref': gap['id'], 'reason': gap['reason']})
    for source in document['census']['sources']:
        if source['provenance_gaps']:
            blockers.append({'kind': 'source', 'ref': source['id'],
                             'reason': '; '.join(source['provenance_gaps'])})
    for position in document['census']['positions']:
        if position['status'] != 'parsed' or position.get('unresolved_reason'):
            blockers.append({'kind': 'position', 'ref': position['id'],
                             'reason': position.get('unresolved_reason') or position['status']})
    return {'schema': 'historical-ingestion-gate/v1', 'year': year,
            'cohort_year': 2024, 'cutoff': document['cutoff'],
            'open': not blockers, 'blockers': blockers,
            'limits': ['Known supplied gaps only; an open gate does not attest global completeness.',
                       'No owner exception or publication authorization is implemented.']}


def ordinary_ingest(document, year, candidate):
    """Public ordinary-ingestion entrypoint: gate before reading candidate rows."""
    if not gate(document, year)['open']:
        raise ValueError(f'ordinary {year} ingestion closed by known 2024 gaps')
    census.validate(candidate)
    census.need(candidate['events'], 'ordinary ingestion requires dated events')
    for event in candidate['events']:
        census.need(event['held_from'].startswith(f'{year}-')
                    and event['held_to'].startswith(f'{year}-'), 'candidate outside ingestion year')
    return {'schema': 'historical-private-stage/v1', 'mode': 'ordinary', 'year': year,
            'gate_cutoff': document['cutoff'], 'census': candidate,
            'distinct_attempts': None}


def query(document, route=None, lead=None, status=None):
    """Select discovery records, keeping exact raw census citations and versions."""
    validate(document)
    for field, value, records in (('route', route, document['routes']),
                                  ('lead', lead, document['leads'])):
        if value is not None:
            census.need(any(item['id'] == value for item in records), f'unknown {field} filter')
    if status is not None:
        census.need(status in STATES, 'unknown status filter')
    leads = [item for item in document['leads']
             if (route is None or route in item['route_ids'])
             and (lead is None or lead == item['id'])
             and (status is None or status == item['source_state'])]
    referenced_routes = {ref for item in leads for ref in item['route_ids']}
    routes = [item for item in document['routes']
              if (route is None or route == item['id'])
              and (lead is None or item['id'] in referenced_routes)
              and (status is None or status == item['status'] or item['id'] in referenced_routes)]
    selected = dict(document['census'])
    if any(value is not None for value in (route, lead, status)):
        sources = {ref for item in leads for ref in item['source_ids']}
        selected['sources'] = [item for item in selected['sources'] if item['id'] in sources]
        events = {ref for item in selected['sources'] for ref in item['event_ids']}
        selected['events'] = [item for item in selected['events'] if item['id'] in events]
        selected['positions'] = [item for item in selected['positions'] if item['source_id'] in sources]
        positions = {item['id'] for item in selected['positions']}
        selected['gaps'] = [item for item in selected['gaps']
                            if item['scope'] == 'search'
                            or (item['scope'] == 'source' and item['ref'] in sources)
                            or (item['scope'] == 'event' and item['ref'] in events)]
        selected['relationships'] = [item for item in selected['relationships']
                                     if item['left'] in positions and item['right'] in positions]
    counts = dict(census.validate(selected)['counts'], routes=len(routes), leads=len(leads))
    return {'schema': 'historical-cohort-query/v1', 'cohort_year': 2024,
            'cutoff': document['cutoff'], 'scope': document['scope'],
            'routes': routes, 'leads': leads, 'census': selected,
            'counts': counts, 'distinct_attempts': None}



def _private_write(path, document):
    destination = Path(path).resolve()
    repository = Path(__file__).resolve().parents[1]
    census.need(not destination.is_relative_to(repository),
                'private historical output must be outside repository')
    missing = []
    parent = destination.parent
    while not parent.exists():
        missing.append(parent)
        parent = parent.parent
    for parent in reversed(missing):
        parent.mkdir(mode=0o700)
    census.need(destination.parent.stat().st_mode & 0o077 == 0,
                'private output directory must be mode 0700')
    encoded = (json.dumps(document, sort_keys=True, ensure_ascii=False,
                          separators=(',', ':')) + '\n').encode()
    descriptor, temporary = tempfile.mkstemp(prefix='.historical-', dir=destination.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return hashlib.sha256(encoded).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('validate', 'query', 'gate', 'ingest'):
        command = commands.add_parser(name)
        command.add_argument('manifest', type=Path)
        if name in ('gate', 'ingest'):
            command.add_argument('--year', type=int, default=2023)
        if name == 'query':
            command.add_argument('--route')
            command.add_argument('--lead')
            command.add_argument('--status')
        if name == 'ingest':
            command.add_argument('candidate', type=Path)
            command.add_argument('output', type=Path)
    args = parser.parse_args(argv)
    try:
        document = json.loads(args.manifest.read_text(encoding='utf-8'))
        if args.command == 'validate':
            result = validate(document)
        elif args.command == 'query':
            result = query(document, args.route, args.lead, args.status)
        elif args.command == 'gate':
            result = gate(document, args.year)
        else:
            # Enforce before loading or staging any ordinary historical candidate.
            if not gate(document, args.year)['open']:
                raise ValueError(f'ordinary {args.year} ingestion closed by known 2024 gaps')
            candidate = json.loads(args.candidate.read_text(encoding='utf-8'))
            stage = ordinary_ingest(document, args.year, candidate)
            digest = _private_write(args.output, stage)
            result = {'schema': 'historical-private-stage-report/v1',
                      'mode': 'ordinary', 'year': args.year, 'output_sha256': digest,
                      'counts': census.validate(candidate)['counts'], 'distinct_attempts': None}
    except (OSError, ValueError, UnicodeError) as error:
        print(f'historical cohort rejected: {error}', file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
    return 3 if args.command == 'gate' and not result['open'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
