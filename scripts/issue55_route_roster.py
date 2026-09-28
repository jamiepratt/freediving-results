#!/usr/bin/env python3
"""Validate and normalize a private, bounded official discovery route roster."""
import argparse
import json
import os
import re
import sys
import tempfile
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

SCHEMA = 'issue55-route-roster/v1'
STATUSES = ('checked', 'acquired', 'missing', 'inaccessible', 'unchecked')
ROLES = ('primary', 'mirror', 'corroboration')
RELATIONSHIPS = (*ROLES, 'unknown')
HEX = re.compile(r'[0-9a-f]{64}\Z')
TRANSITIONS = {
    'unchecked': {'checked', 'acquired', 'missing', 'inaccessible'},
    'checked': {'acquired', 'missing', 'inaccessible'},
    'missing': {'checked', 'acquired', 'inaccessible'},
    'inaccessible': {'checked', 'acquired', 'missing'},
    'acquired': set(),
}


def required_str(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f'{label} must be a nonempty string')
    return value


def url(value, label):
    required_str(value, label)
    parts = urlsplit(value)
    if parts.scheme not in ('http', 'https') or not parts.netloc or parts.username or parts.password:
        raise ValueError(f'{label} must be an HTTP(S) URL without credentials')
    return value


def timestamp(value, label):
    required_str(value, label)
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError as exc:
        raise ValueError(f'{label} must be an ISO timestamp') from exc
    if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0:
        raise ValueError(f'{label} must be UTC')
    return parsed.isoformat(timespec='seconds').replace('+00:00', 'Z')


def hash_value(value, label):
    if not isinstance(value, str) or not HEX.fullmatch(value):
        raise ValueError(f'{label} must be lowercase SHA-256')
    return value


def citation(value, label):
    if not isinstance(value, dict):
        raise ValueError(f'{label} must be a citation')
    return {'url': url(value.get('url'), label + '.url'),
            'sha256': hash_value(value.get('sha256'), label + '.sha256'),
            'locator': required_str(value.get('locator'), label + '.locator')}


def receipt(value, label):
    if not isinstance(value, dict):
        raise ValueError(f'{label} must be a receipt')
    status = value.get('http_status')
    size = value.get('bytes')
    if type(status) is not int or status < 100 or status > 599:
        raise ValueError(f'{label}.http_status invalid')
    if type(size) is not int or size < 0:
        raise ValueError(f'{label}.bytes invalid')
    return {'url': url(value.get('url'), label + '.url'),
            'final_url': url(value.get('final_url'), label + '.final_url'),
            'http_status': status, 'content_type': required_str(value.get('content_type'), label + '.content_type'),
            'bytes': size, 'sha256': hash_value(value.get('sha256'), label + '.sha256')}


def strings(value, label):
    if not isinstance(value, list) or any(not isinstance(x, str) or not x.strip() for x in value):
        raise ValueError(f'{label} must be a string list')
    if len(value) != len(set(value)):
        raise ValueError(f'{label} has duplicates')
    return sorted(value)


def optional_string(value, label):
    return None if value is None else required_str(value, label)


def checked_history(value, final_status, cutoff, label):
    if value is None or value == []:
        return []
    if not isinstance(value, list):
        raise ValueError(f'{label} must be a nonempty status history')
    normalized = []
    prior = None
    for entry in value:
        if not isinstance(entry, dict) or entry.get('status') not in STATUSES:
            raise ValueError(f'{label} has invalid status')
        state = entry['status']
        at = timestamp(entry.get('at'), label + '.at')
        if at > cutoff or (normalized and at <= normalized[-1]['at']):
            raise ValueError(f'{label} timestamps must increase and precede cutoff')
        if prior is not None and state not in TRANSITIONS[prior]:
            raise ValueError(f'{label} invalid transition {prior} to {state}')
        normalized.append({'status': state, 'at': at})
        prior = state
    if normalized[-1]['status'] != final_status:
        raise ValueError(f'{label} final status mismatch')
    return normalized


def route(item, cutoff):
    if not isinstance(item, dict):
        raise ValueError('route must be an object')
    rid = required_str(item.get('id'), 'route.id')
    status = item.get('status')
    if status not in STATUSES:
        raise ValueError(f'{rid}: invalid status')
    role = item.get('role')
    if role not in ROLES:
        raise ValueError(f'{rid}: invalid role')
    checked_at = item.get('checked_at')
    raw_receipt = item.get('receipt')
    if status == 'unchecked':
        if checked_at is not None or raw_receipt is not None:
            raise ValueError(f'{rid}: unchecked route cannot have check receipt')
    else:
        checked_at = timestamp(checked_at, rid + '.checked_at')
        if checked_at > cutoff:
            raise ValueError(f'{rid}: checked after cutoff')
        raw_receipt = receipt(raw_receipt, rid + '.receipt')
    normalized = {'id': rid, 'authority': required_str(item.get('authority'), rid + '.authority'),
                  'role': role, 'discovery_url': url(item.get('discovery_url'), rid + '.discovery_url'),
                  'status': status, 'checked_at': checked_at, 'receipt': raw_receipt,
                  'citation': citation(item.get('citation'), rid + '.citation'),
                  'gaps': strings(item.get('gaps'), rid + '.gaps'),
                  'status_history': checked_history(item.get('status_history'), status, cutoff, rid + '.status_history')}
    if normalized['status_history'] and checked_at and normalized['status_history'][-1]['at'] != checked_at:
        raise ValueError(f'{rid}: history/check timestamp mismatch')
    return normalized


def competition_date(value, label):
    if value is None:
        return None
    required_str(value, label)
    try:
        parsed = date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f'{label} must be ISO date') from exc
    if parsed.isoformat() != value or parsed.year not in (2025, 2026):
        raise ValueError(f'{label} outside 2025-2026 competition scope')
    return value


def lead(item, routes, cutoff):
    if not isinstance(item, dict):
        raise ValueError('lead must be an object')
    lid = required_str(item.get('id'), 'lead.id')
    route_id = required_str(item.get('route_id'), lid + '.route_id')
    if route_id not in routes:
        raise ValueError(f'{lid}: unknown route')
    status = item.get('status')
    if status not in STATUSES:
        raise ValueError(f'{lid}: invalid status')
    first = competition_date(item.get('competition_date'), lid + '.competition_date')
    last = competition_date(item.get('competition_date_to'), lid + '.competition_date_to')
    if last and (not first or last < first):
        raise ValueError(f'{lid}: invalid competition date range')
    year = item.get('competition_year')
    if year not in (2025, 2026, None) or type(year) is bool:
        raise ValueError(f'{lid}: invalid competition year')
    if first and (year != int(first[:4]) or (last and int(last[:4]) != year)):
        raise ValueError(f'{lid}: competition year mismatch')
    date_citation = item.get('date_evidence')
    if first and date_citation is None:
        raise ValueError(f'{lid}: known competition date requires date evidence')
    if year is not None and date_citation is None:
        date_citation = item.get('citation')
    evidence = citation(item.get('citation'), lid + '.citation')
    if routes[route_id]['receipt'] and evidence['sha256'] != routes[route_id]['receipt']['sha256']:
        raise ValueError(f'{lid}: citation must match route receipt SHA-256')
    if date_citation is not None:
        date_citation = citation(date_citation, lid + '.date_evidence')
    relationship = item.get('relationship', 'unknown')
    if relationship not in RELATIONSHIPS:
        raise ValueError(f'{lid}: invalid relationship')
    snapshot_ids = strings(item.get('snapshot_ids'), lid + '.snapshot_ids')
    candidates = strings(item.get('candidate_snapshot_ids'), lid + '.candidate_snapshot_ids')
    if set(snapshot_ids) & set(candidates):
        raise ValueError(f'{lid}: proven and candidate snapshot IDs overlap')
    return {'id': lid, 'route_id': route_id, 'event_key': optional_string(item.get('event_key'), lid + '.event_key'),
            'title': required_str(item.get('title'), lid + '.title'),
            'competition_date': first, 'competition_date_to': last, 'competition_year': year,
            'date_evidence': date_citation, 'session': optional_string(item.get('session'), lid + '.session'),
            'discipline': optional_string(item.get('discipline'), lid + '.discipline'),
            'category': optional_string(item.get('category'), lid + '.category'),
            'relationship': relationship, 'status': status, 'url': url(item.get('url'), lid + '.url'),
            'candidate_event_keys': strings(item.get('candidate_event_keys', []), lid + '.candidate_event_keys'),
            'citation': evidence, 'snapshot_ids': snapshot_ids, 'candidate_snapshot_ids': candidates,
            'gaps': strings(item.get('gaps'), lid + '.gaps'),
            'status_history': checked_history(item.get('status_history'), status, cutoff, lid + '.status_history')}


def normalize(data):
    if not isinstance(data, dict) or data.get('schema') != SCHEMA:
        raise ValueError('schema must be issue55-route-roster/v1')
    cutoff = timestamp(data.get('cutoff'), 'cutoff')
    raw_routes = data.get('routes')
    raw_leads = data.get('leads')
    if not isinstance(raw_routes, list) or not isinstance(raw_leads, list):
        raise ValueError('routes and leads must be arrays')
    routes = [route(item, cutoff) for item in raw_routes]
    by_id = {item['id']: item for item in routes}
    if len(by_id) != len(routes):
        raise ValueError('duplicate route ID')
    leads = [lead(item, by_id, cutoff) for item in raw_leads]
    if len({item['id'] for item in leads}) != len(leads):
        raise ValueError('duplicate lead ID')
    routes.sort(key=lambda x: x['id'])
    leads.sort(key=lambda x: x['id'])
    route_counts = Counter(item['status'] for item in routes)
    lead_counts = Counter(item['status'] for item in leads)
    years = Counter(str(item['competition_year']) if item['competition_year'] else 'unknown' for item in leads)
    by_route = {}
    for item in routes:
        members = [lead for lead in leads if lead['route_id'] == item['id']]
        route_statuses = Counter(lead['status'] for lead in members)
        route_years = Counter(str(lead['competition_year']) if lead['competition_year'] else 'unknown'
                              for lead in members)
        by_route[item['id']] = {
            'count': len(members),
            'by_status': {key: route_statuses[key] for key in STATUSES},
            'by_year': {key: route_years[key] for key in ('2025', '2026', 'unknown')},
        }
    summary = {'route_count': len(routes), 'lead_count': len(leads),
               'leads_by_route': by_route,
               'routes_by_status': {k: route_counts[k] for k in STATUSES},
               'leads_by_status': {k: lead_counts[k] for k in STATUSES},
               'leads_by_year': {k: years[k] for k in ('2025', '2026', 'unknown')},
               'confirmed_distinct_attempts': None}
    return {'schema': SCHEMA, 'cutoff': cutoff, 'routes': routes, 'leads': leads, 'summary': summary}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        normalized = normalize(json.loads(args.input.read_text(encoding='utf-8')))
        payload = json.dumps(normalized, ensure_ascii=False, indent=2, sort_keys=True) + '\n'
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=args.output.parent,
                                         prefix='.route-roster-', suffix='.tmp', delete=False) as stream:
            temp_path = Path(stream.name)
            os.chmod(temp_path, 0o600)
            stream.write(payload)
        try:
            os.replace(temp_path, args.output)
        finally:
            temp_path.unlink(missing_ok=True)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(2, f'error: {exc}\n')


if __name__ == '__main__':
    main()
