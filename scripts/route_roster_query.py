"""Pinned, read-only route coverage queries for the owner evidence workspace."""

import hashlib
import json
from pathlib import Path


STATUSES = frozenset({'checked', 'acquired', 'missing', 'inaccessible', 'unchecked'})
RELATIONSHIPS = frozenset({'primary', 'corroboration', 'mirror', 'unknown'})
SCOPE = {'complete': False,
         'description': 'Dated, incomplete 2025-2026 route census; unchecked and inaccessible routes are not evidence of absent results.'}


def _page(limit, offset):
    if type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError('limit must be an integer from 1 to 100')
    if type(offset) is not int or not 0 <= offset <= 100000:
        raise ValueError('offset must be an integer from 0 to 100000')


def _filter(value, label, allowed=None):
    if value is None:
        return
    if not isinstance(value, str) or not value or len(value) > 200 or '\x00' in value:
        raise ValueError(f'invalid {label}')
    if allowed is not None and value not in allowed:
        raise ValueError(f'invalid {label}')


class RouteRosterQuery:
    @classmethod
    def from_snapshot(cls, snapshot):
        """Read a roster embedded as v3 records in the verified immutable snapshot."""
        source = snapshot.manifest['inputs'].get('route-roster-v3')
        if source is None or source.get('source_schema') != 'issue55-route-roster/v3':
            raise ValueError('snapshot has no supported embedded route roster')
        rows = snapshot.db.execute(
            "SELECT collection, raw_json FROM records WHERE source_name='route-roster-v3' "
            "AND collection IN ('routes','leads') ORDER BY record_path")
        entries = {'routes': [], 'leads': []}
        for row in rows:
            entries[row['collection']].append(json.loads(row['raw_json']))
        routes, leads = entries['routes'], entries['leads']
        if (len(routes) != source['collections'].get('routes')
                or len(leads) != source['collections'].get('leads')
                or len({r['id'] for r in routes}) != len(routes)
                or len({l['id'] for l in leads}) != len(leads)):
            raise ValueError('embedded roster counts do not match manifest')
        route_ids = {route['id'] for route in routes}
        if any(lead['route_id'] not in route_ids for lead in leads):
            raise ValueError('embedded roster has unknown route')
        years = ('2025', '2026', 'unknown')
        def year_of(lead):
            return str(lead['competition_year']) if lead.get('competition_year') is not None else 'unknown'
        summary = {'route_count': len(routes), 'lead_count': len(leads),
                   'confirmed_distinct_attempts': snapshot.manifest['confirmed_distinct_attempts'],
                   'routes_by_status': {s: sum(r['status'] == s for r in routes) for s in sorted(STATUSES)},
                   'leads_by_status': {s: sum(l['status'] == s for l in leads) for s in sorted(STATUSES)},
                   'leads_by_year': {y: sum(year_of(l) == y for l in leads) for y in years},
                   'leads_by_route': {}}
        for route_id in sorted(route_ids):
            subset = [lead for lead in leads if lead['route_id'] == route_id]
            summary['leads_by_route'][route_id] = {
                'count': len(subset),
                'by_status': {s: sum(l['status'] == s for l in subset) for s in sorted(STATUSES)},
                'by_year': {y: sum(year_of(l) == y for l in subset) for y in years}}
        metadata_row = snapshot.db.execute(
            "SELECT metadata_json FROM source_metadata WHERE source_name='route-roster-v3'").fetchone()
        if metadata_row is None:
            raise ValueError('embedded roster metadata missing')
        metadata = json.loads(metadata_row['metadata_json'])
        if metadata.get('schema') != 'issue55-route-roster/v3' or metadata.get('summary') != summary:
            raise ValueError('embedded roster summary does not match metadata')
        self = cls.__new__(cls)
        self.roster = {'cutoff': metadata['cutoff'], 'routes': routes, 'leads': leads,
                       'summary': summary}
        self.sha256 = source['sha256']
        self.snapshot = snapshot
        self.route_ids = route_ids
        return self

    def __init__(self, directory, expected_sha256, snapshot=None, snapshot_sha256=None):
        if not isinstance(expected_sha256, str) or len(expected_sha256) != 64 or any(
                c not in '0123456789abcdef' for c in expected_sha256):
            raise ValueError('invalid roster digest')
        directory = Path(directory)
        raw = (directory / 'roster.json').read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected_sha256:
            raise ValueError('roster does not match configured digest')
        roster = json.loads(raw)
        manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        digest = snapshot_sha256 or (snapshot.manifest['snapshot_sha256'] if snapshot is not None else None)
        if roster.get('schema') != 'issue55-route-roster/v1' or manifest.get('schema') != 'issue55-route-receipts/v1':
            raise ValueError('unsupported route roster schema')
        if manifest.get('roster_sha256') != expected_sha256 or manifest.get('snapshot_sqlite_sha256') != digest:
            raise ValueError('route roster manifest does not match pinned snapshot')
        if roster.get('cutoff') != manifest.get('cutoff') or roster.get('summary') != manifest.get('summary'):
            raise ValueError('route roster manifest summary mismatch')
        routes, leads = roster.get('routes'), roster.get('leads')
        if not isinstance(routes, list) or not isinstance(leads, list):
            raise ValueError('invalid route roster')
        summary = roster.get('summary') or {}
        if (summary.get('route_count') != len(routes) or summary.get('lead_count') != len(leads)
                or len({r['id'] for r in routes}) != len(routes)
                or len({lead['id'] for lead in leads}) != len(leads)):
            raise ValueError('route roster counts do not match')
        self.roster = roster
        self.sha256 = expected_sha256
        self.snapshot = snapshot
        self.route_ids = {route['id'] for route in routes}

    def _base(self, total, limit, offset, items):
        return {'roster_sha256': self.sha256, 'cutoff': self.roster['cutoff'],
                'scope': SCOPE, 'summary': self.roster['summary'],
                'total': total, 'limit': limit, 'offset': offset, 'items': items}

    def routes(self, *, route_id=None, status=None, limit=50, offset=0):
        _page(limit, offset)
        _filter(route_id, 'route_id', self.route_ids)
        _filter(status, 'status', STATUSES)
        routes = [route for route in self.roster['routes']
                  if (route_id is None or route['id'] == route_id)
                  and (status is None or route['status'] == status)]
        routes.sort(key=lambda route: route['id'])
        items = []
        for route in routes[offset:offset + limit]:
            counts = self.roster['summary']['leads_by_route'][route['id']]
            items.append({**route, 'lead_count': counts['count'], 'lead_status_counts': counts['by_status']})
        return self._base(len(routes), limit, offset, items)

    def _source_records(self, ids, url, *, exact):
        if self.snapshot is None:
            return []
        records = []
        for record_id in ids:
            record = self.snapshot.detail(record_id)
            if record is None or record['kind'] != 'source' or record['collection'] != 'sources':
                continue
            if exact and not (record['raw'].get('final_url') == url and any(
                    acquisition.get('final_url') == url
                    for acquisition in record['raw'].get('acquisitions', []))):
                continue
            records.append({key: record[key] for key in ('record_id', 'source_name', 'record_path')})
        return records

    def leads(self, *, route_id=None, status=None, year=None, relationship=None, limit=50, offset=0):
        _page(limit, offset)
        _filter(route_id, 'route_id', self.route_ids)
        _filter(status, 'status', STATUSES)
        _filter(year, 'year', {'2025', '2026', 'unknown'})
        _filter(relationship, 'relationship', RELATIONSHIPS)
        leads = [lead for lead in self.roster['leads']
                 if (route_id is None or lead['route_id'] == route_id)
                 and (status is None or lead['status'] == status)
                 and (year is None or ('unknown' if lead['competition_year'] is None else str(lead['competition_year'])) == year)
                 and (relationship is None or lead['relationship'] == relationship)]
        leads.sort(key=lambda lead: (lead['route_id'], lead['competition_year'] or 9999,
                                    lead['competition_date'] or '', lead['event_key'] or '', lead['id']))
        items = []
        for lead in leads[offset:offset + limit]:
            items.append({**lead,
                          'exact_source_records': self._source_records(lead['snapshot_ids'], lead['url'], exact=True),
                          'candidate_source_records': self._source_records(lead['candidate_snapshot_ids'], lead['url'], exact=False)})
        return self._base(len(leads), limit, offset, items)
