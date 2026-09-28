#!/usr/bin/env python3
"""Read-only queries for a private unified evidence snapshot."""
import json
import hashlib
from datetime import date
import sqlite3
from pathlib import Path


QUEUE_GROUPS = (
    'extraction_source_semantics', 'source_revision_same_result',
    'same_attempt_relationship', 'athlete_identity', 'coverage_finality',
    'event_publication',
)


def _queue_group(row, raw):
    if row['kind'] == 'relationship':
        relationship = raw.get('relationship_type')
        if relationship in ('source_revision', 'publisher_revision', 'same_result'):
            return 'source_revision_same_result'
        if relationship == 'same_attempt':
            return 'same_attempt_relationship'
        return None
    if row['collection'] == 'unparsed_rows':
        return 'extraction_source_semantics'
    if row['collection'] == 'source_relationship_gaps':
        return 'event_publication'
    if row['collection'] == 'retained_only_artifacts':
        return 'extraction_source_semantics'
    disposition = (raw.get('assessment') or {}).get('disposition')
    if disposition in ('duplicate_rendering', 'supporting_overlap', 'matching_timing_view'):
        return 'source_revision_same_result'
    if disposition in ('unsupported_image_results', 'supplemental_aggregate', 'supplemental_combined_ranking'):
        return 'extraction_source_semantics'
    return 'coverage_finality'


def _queue_item(row, input_data):
    raw = json.loads(row['raw_json'])
    assessment = raw.get('assessment') or {}
    group = _queue_group(row, raw)
    trigger = (assessment.get('failure_reason') or raw.get('reason') or raw.get('value')
               or assessment.get('disposition') or raw.get('relationship_type') or 'Explicit evidence record')
    supporting = [str(value) for value in (assessment.get('evidence'), raw.get('basis')) if value]
    contrary = [str(value) for value in (assessment.get('contrary_evidence'), raw.get('contrary_evidence')) if value]
    unknown = []
    if not supporting:
        unknown.append('Supporting evidence not recorded in this item')
    if not contrary:
        unknown.append('Contrary evidence not recorded in this item')
    if group in ('source_revision_same_result', 'event_publication'):
        unknown.append('Relationship or publication approval not recorded')
    return {
        'id': 'queue:' + row['record_id'], 'group': group, 'trigger': str(trigger),
        'supporting_evidence': supporting, 'contrary_evidence': contrary, 'unknown': unknown,
        'citation': {'record_id': row['record_id'], 'source_name': row['source_name'],
                     'collection': row['collection'], 'record_path': row['record_path'],
                     'source_id': row['source_id'], 'source_object_id': row['source_object_id'],
                     'page': row['page'], 'locator': json.loads(row['citation_json'])},
        'context': {'kind': row['kind'], 'review_status': row['review_status'],
                    'parser_version': row['parser_version'], 'observation_version': row['observation_version'],
                    'input_sha256': input_data['sha256'], 'source_sha256': input_data.get('source_sha256')},
    }


class SnapshotQuery:
    def __init__(self, directory):
        directory = Path(directory)
        self.manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        if self.manifest.get('schema') != 'unified-evidence-snapshot/v1':
            raise ValueError('unsupported snapshot schema')
        path = (directory / 'snapshot.sqlite').resolve()
        digest = hashlib.sha256()
        with path.open('rb') as file:
            for block in iter(lambda: file.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != self.manifest.get('snapshot_sha256'):
            raise ValueError('snapshot hash mismatch')
        self.db = sqlite3.connect(path.as_uri() + '?mode=ro&immutable=1', uri=True)
        self.db.row_factory = sqlite3.Row

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        self.db.close()

    def overview(self):
        counts = [dict(row) for row in self.db.execute(
            'SELECT source_name, collection, kind, count(*) AS records FROM records '
            'GROUP BY source_name, collection, kind ORDER BY source_name, collection, kind')]
        return {
            'schema': self.manifest['schema'], 'cutoff': self.manifest['cutoff'],
            'coverage': self.manifest['coverage'],
            'confirmed_distinct_attempts': self.manifest['confirmed_distinct_attempts'],
            'snapshot_sha256': self.manifest['snapshot_sha256'],
            'counts': counts,
        }

    def queue(self, *, group=None, source_name=None, limit=50, offset=0):
        if group is not None and group not in QUEUE_GROUPS:
            raise ValueError('invalid queue group')
        if source_name is not None and (not isinstance(source_name, str) or not source_name or len(source_name) > 200 or '\x00' in source_name):
            raise ValueError('invalid source_name')
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('limit must be an integer from 1 to 100')
        if type(offset) is not int or not 0 <= offset <= 100000:
            raise ValueError('offset must be an integer from 0 to 100000')
        rows = self.db.execute("SELECT * FROM records WHERE kind IN ('gap','relationship') ORDER BY source_name, collection, record_path, record_id")
        items = []
        for row in rows:
            item = _queue_item(row, self.manifest['inputs'][row['source_name']])
            if item['group'] is not None:
                items.append(item)
        aggregate_parents = set()
        for row in self.db.execute("SELECT * FROM records WHERE kind='aggregate' AND collection='sheets.rows' ORDER BY source_name, record_path"):
            raw = json.loads(row['raw_json'])
            if row['parent_path'] in aggregate_parents or not any('not individual attempts' in str(note).lower() for note in raw.get('ambiguities', [])):
                continue
            aggregate_parents.add(row['parent_path'])
            item = _queue_item(row, self.manifest['inputs'][row['source_name']])
            item['group'] = 'extraction_source_semantics'
            item['trigger'] = '; '.join(map(str, raw['ambiguities']))
            item['supporting_evidence'] = ['Aggregate row ambiguity recorded in retained packet']
            item['unknown'] = ['Attempt-level equivalence is not recorded', 'Contrary evidence not recorded in this item']
            items.append(item)
        for row in self.db.execute("SELECT * FROM records WHERE kind='candidate_position' ORDER BY source_name, collection, record_path, record_id"):
            raw = json.loads(row['raw_json'])
            notes = raw.get('uncertainties') or raw.get('ambiguities') or []
            if not isinstance(notes, list) or not notes:
                continue
            item = _queue_item(row, self.manifest['inputs'][row['source_name']])
            item['group'] = 'athlete_identity' if any('surname' in str(note).lower() or 'athlete identity' in str(note).lower() for note in notes) else 'extraction_source_semantics'
            item['trigger'] = '; '.join(map(str, notes))
            item['supporting_evidence'] = ['Uncertainty explicitly recorded on cited candidate row']
            item['unknown'] = ['No owner resolution recorded in this snapshot', 'Contrary evidence not recorded in this item']
            items.append(item)
        for name, data in sorted(self.manifest['inputs'].items()):
            if data['status'] != 'excluded':
                continue
            items.append({
                'id': 'queue:source:' + hashlib.sha256(name.encode()).hexdigest(),
                'group': 'coverage_finality', 'trigger': 'Source excluded from this partial snapshot: ' + data.get('reason', 'reason unknown'),
                'supporting_evidence': [], 'contrary_evidence': [],
                'unknown': ['Source records absent from this snapshot', 'Supporting and contrary evidence not recorded in the snapshot'],
                'citation': {'source_name': name, 'record_id': None, 'collection': None, 'record_path': None,
                             'source_id': None, 'source_object_id': None, 'page': None, 'locator': None},
                'context': {'kind': 'excluded_source', 'review_status': None, 'parser_version': None,
                            'observation_version': None, 'input_sha256': data['sha256'],
                            'source_sha256': data.get('source_sha256')},
            })
        items.sort(key=lambda item: (item['group'], item['citation']['source_name'], item['citation']['record_path'] or '', item['id']))
        counts = {name: sum(item['group'] == name for item in items) for name in QUEUE_GROUPS}
        if group:
            items = [item for item in items if item['group'] == group]
        if source_name:
            items = [item for item in items if item['citation']['source_name'] == source_name]
        total = len(items)
        return {'coverage': self.manifest['coverage'], 'cutoff': self.manifest['cutoff'],
                'snapshot_sha256': self.manifest['snapshot_sha256'],
                'denominators': {'candidate_positions': self.db.execute("SELECT count(*) FROM records WHERE kind='candidate_position'").fetchone()[0],
                                 'confirmed_distinct_attempts': self.manifest['confirmed_distinct_attempts']},
                'group_counts': counts, 'total': total, 'limit': limit, 'offset': offset,
                'items': items[offset:offset + limit]}

    def browse(self, *, source_name=None, collection=None, kind=None, event_name=None,
               date_from=None, date_to=None, session=None, discipline=None,
               category=None, limit=50, offset=0):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('limit must be an integer from 1 to 100')
        if type(offset) is not int or not 0 <= offset <= 100000:
            raise ValueError('offset must be an integer from 0 to 100000')
        filters = {'source_name': source_name, 'collection': collection, 'kind': kind,
                   'event_name': event_name, 'session': session,
                   'discipline': discipline, 'category': category}
        clauses, args = [], []
        for column, value in filters.items():
            if value is not None:
                if not isinstance(value, str) or not value or len(value) > 200 or '\x00' in value:
                    raise ValueError(f'{column} must be a nonempty string of at most 200 characters')
                clauses.append(f'{column} = ?')
                args.append(value)
        for label, value in (('date_from', date_from), ('date_to', date_to)):
            if value is not None:
                if not isinstance(value, str) or len(value) != 10 or date.fromisoformat(value).isoformat() != value:
                    raise ValueError(f'{label} must be YYYY-MM-DD')
        if date_from and date_to and date_from > date_to:
            raise ValueError('date_from must not exceed date_to')
        if date_from:
            clauses.append('COALESCE(date_to, event_date) >= ?')
            args.append(date_from)
        if date_to:
            clauses.append('COALESCE(date_from, event_date) <= ?')
            args.append(date_to)
        where = (' WHERE ' + ' AND '.join(clauses)) if clauses else ''
        total = self.db.execute('SELECT count(*) FROM records' + where, args).fetchone()[0]
        rows = self.db.execute(
            'SELECT record_id, source_name, collection, record_path, parent_path, kind, '
            'event_name, event_date, date_from, date_to, session, discipline, category, '
            'page, review_status FROM records' + where +
            ' ORDER BY source_name, collection, record_path, record_id LIMIT ? OFFSET ?',
            [*args, limit, offset])
        return {'total': total, 'limit': limit, 'offset': offset,
                'records': [dict(row) for row in rows]}

    def detail(self, record_id):
        if not isinstance(record_id, str) or len(record_id) != 64 or any(c not in '0123456789abcdef' for c in record_id):
            raise ValueError('record_id must be a lowercase SHA256 hex string')
        row = self.db.execute('SELECT * FROM records WHERE record_id = ?', (record_id,)).fetchone()
        if row is None:
            return None
        result = dict(row)
        for column, key in (('citation_json', 'citation'), ('date_scope_json', 'date_scope'),
                            ('raw_fields_json', 'raw_fields'), ('parsed_fields_json', 'parsed_fields'),
                            ('raw_json', 'raw')):
            result[key] = json.loads(result.pop(column))
        source = self.manifest['inputs'][result['source_name']]
        result['input_sha256'] = source['sha256']
        result['source_sha256'] = source.get('source_sha256')
        result['source_schema'] = source.get('source_schema')
        result['snapshot_sha256'] = self.manifest['snapshot_sha256']
        return result

    def gaps(self, **filters):
        if 'kind' in filters:
            raise ValueError('kind is fixed for gaps')
        return self.browse(kind='gap', **filters)

    def relationships(self, **filters):
        if 'kind' in filters:
            raise ValueError('kind is fixed for relationships')
        return self.browse(kind='relationship', **filters)

    def sources(self):
        return [{'source_name': name, **data} for name, data in sorted(self.manifest['inputs'].items())]

    def source(self, source_name):
        if not isinstance(source_name, str) or not source_name or len(source_name) > 200 or '\x00' in source_name:
            raise ValueError('source_name must be a nonempty string of at most 200 characters')
        data = self.manifest['inputs'].get(source_name)
        if data is None:
            return None
        result = {'source_name': source_name, **data}
        if data['status'] == 'included':
            row = self.db.execute('SELECT metadata_json FROM source_metadata WHERE source_name = ?', (source_name,)).fetchone()
            result['metadata'] = json.loads(row[0]) if row else None
        return result


def main(argv=None):
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot-dir', required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('overview')
    commands.add_parser('sources')
    for name in ('browse', 'gaps', 'relationships'):
        command = commands.add_parser(name)
        for field in ('source_name', 'collection', 'event_name', 'date_from', 'date_to',
                      'session', 'discipline', 'category'):
            command.add_argument('--' + field.replace('_', '-'))
        if name == 'browse':
            command.add_argument('--kind')
        command.add_argument('--limit', type=int, default=50)
        command.add_argument('--offset', type=int, default=0)
    commands.add_parser('detail').add_argument('record_id')
    commands.add_parser('source').add_argument('source_name')
    args = parser.parse_args(argv)
    try:
        with SnapshotQuery(args.snapshot_dir) as query:
            if args.command in ('browse', 'gaps', 'relationships'):
                options = {k: v for k, v in vars(args).items()
                           if k not in ('snapshot_dir', 'command') and v is not None}
                result = getattr(query, args.command)(**options)
            elif args.command == 'detail':
                result = query.detail(args.record_id)
            elif args.command == 'source':
                result = query.source(args.source_name)
            else:
                result = getattr(query, args.command)()
    except (ValueError, OSError, sqlite3.Error, KeyError) as exc:
        parser.error(str(exc))
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == '__main__':
    main()
