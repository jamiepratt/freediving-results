#!/usr/bin/env python3
"""Read-only queries for a private unified evidence snapshot."""
import json
import hashlib
from datetime import date
import sqlite3
from pathlib import Path


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
