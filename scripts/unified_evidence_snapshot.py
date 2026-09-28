#!/usr/bin/env python3
"""Build a private, dated, queryable census snapshot from explicit JSON inputs."""
import argparse
import hashlib
import json
import os
import sqlite3
import tempfile
from collections import Counter
from pathlib import Path

SCHEMA = 'unified-evidence-snapshot/v1'


def canon(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def named_path(spec):
    name, sep, path = spec.partition('=')
    if not sep or not name or not path:
        raise ValueError('expected NAME=PATH')
    return name, Path(path)


def excluded_path(spec):
    name, path = named_path(spec)
    path, sep, reason = str(path).rpartition(':')
    if not sep or not reason:
        raise ValueError('expected NAME=PATH:REASON')
    return name, Path(path), reason


def classify(collection, obj, parent=None):
    parent = parent or {}
    c = collection.lower()
    if obj.get('kind') == 'formula_placeholder':
        return 'other'
    if ('aggregate' in c or obj.get('kind') in ('aggregate', 'club_standing', 'standings', 'combined_score', 'secondary_score')
            or obj.get('role') == 'aggregate_standings' or parent.get('role') == 'aggregate_standings'
            or parent.get('page_kind') == 'aggregate'):
        return 'aggregate'
    if 'gap' in c or 'unparsed' in c or 'retained_only' in c:
        return 'gap'
    if 'relationship' in c:
        return 'relationship'
    if c == 'sources' or c == 'source_objects' or c == 'artifacts':
        return 'source'
    if c == 'candidate_versions' or 'candidate' in c or c in ('positions', 'pages.rows', 'pages.athlete_rows', 'athlete_appearances', 'sheets.rows'):
        return 'candidate_position'
    if c == 'events':
        return 'event'
    return 'other'


def value(*values):
    return next((v for v in values if v is not None), None)


def field(obj, *names):
    for name in names:
        if obj.get(name) is not None:
            return obj[name]
    return None


def normalize(obj, parent, root, kind):
    raw_fields = obj.get('fields_raw') or obj.get('raw_fields') or obj.get('fields') or {}
    parsed = obj.get('parsed_fields') or {}
    if isinstance(parsed, dict) and any(isinstance(v, dict) and 'value' in v for v in parsed.values()):
        parsed = {k: v.get('value') if isinstance(v, dict) else v for k, v in parsed.items()}
    if not isinstance(raw_fields, dict):
        raw_fields = {}
    if not isinstance(parsed, dict):
        parsed = {}
    citation_obj = obj.get('citation') if isinstance(obj.get('citation'), dict) else {}
    page = value(obj.get('page'), parent.get('page'), citation_obj.get('page'))
    date_scope = value(obj.get('date_scope'), parent.get('date_scope'))
    date_from = date_scope[0] if isinstance(date_scope, list) and len(date_scope) > 1 else None
    date_to = date_scope[1] if isinstance(date_scope, list) and len(date_scope) > 1 else None
    exact_date = date_from if date_from == date_to else None
    acquisition = obj.get('acquisition_id') or ((obj.get('source') or {}).get('acquisition_id') if isinstance(obj.get('source'), dict) else None)
    observations = obj.get('observation_refs') or []
    return {
        'source_id': obj.get('id'),
        'source_object_id': value(obj.get('source_id'), parent.get('source_id'), (root.get('source') or {}).get('id') if isinstance(root.get('source'), dict) else None),
        'acquisition_id': value(acquisition, (root.get('source') or {}).get('acquisition_id') if isinstance(root.get('source'), dict) else None),
        'parser_version': value(obj.get('parser_version'), observations[0].get('parser_version') if observations else None),
        'observation_version': value(obj.get('observation_version'), observations[0].get('candidate_id') if observations else None),
        'event_name': value(obj.get('event_name'), obj.get('name') if kind == 'event' else None, root.get('event_title_calendar')),
        'event_date': value(obj.get('event_date'), exact_date, parent.get('event_date'), root.get('event_date_calendar')),
        'date_from': date_from, 'date_to': date_to, 'date_scope_json': canon(date_scope),
        'session': value(obj.get('session'), parent.get('session')),
        'category': value(obj.get('category'), obj.get('category_raw'), parent.get('category_raw'), parsed.get('category')),
        'discipline': value(obj.get('discipline'), obj.get('discipline_raw'), parent.get('discipline_raw'), parsed.get('discipline')),
        'page': page,
        'citation': value(obj.get('citation'), obj.get('locator'), obj.get('source_lines')),
        'review_status': value(obj.get('review_status'), root.get('owner_review_status'), 'unreviewed' if kind == 'candidate_position' else None),
        'raw_fields_json': canon(raw_fields),
        'parsed_fields_json': canon(parsed),
    }


def records(name, root):
    for key in sorted(root):
        arr = root[key]
        if not isinstance(arr, list):
            continue
        for index, obj in enumerate(arr):
            if not isinstance(obj, dict):
                obj = {'value': obj}
            path = f'{key}[{index}]'
            yield key, path, obj, {}, False
            for nested in ('rows', 'aggregate_rows', 'athlete_rows'):
                children = obj.get(nested)
                if isinstance(children, list):
                    for child_index, child in enumerate(children):
                        if not isinstance(child, dict):
                            child = {'value': child}
                        yield f'{key}.{nested}', f'{path}.{nested}[{child_index}]', child, obj, True


def create_db(path):
    db = sqlite3.connect(path)
    db.executescript('''
        PRAGMA page_size=4096;
        PRAGMA journal_mode=DELETE;
        CREATE TABLE records (
          record_id TEXT PRIMARY KEY, source_name TEXT NOT NULL, collection TEXT NOT NULL,
          record_path TEXT NOT NULL, parent_path TEXT, kind TEXT NOT NULL,
          source_id TEXT, source_object_id TEXT, acquisition_id TEXT,
          parser_version TEXT, observation_version TEXT, event_name TEXT,
          event_date TEXT, date_from TEXT, date_to TEXT, date_scope_json TEXT,
          session TEXT, category TEXT, discipline TEXT,
          page INTEGER, citation_json TEXT, review_status TEXT,
          raw_fields_json TEXT NOT NULL, parsed_fields_json TEXT NOT NULL,
          raw_json TEXT NOT NULL
        );
        CREATE INDEX records_lookup ON records(kind,event_date,discipline,category);
        CREATE INDEX records_source ON records(source_name,collection);
        CREATE INDEX records_object ON records(source_object_id);
        CREATE TABLE source_metadata(source_name TEXT PRIMARY KEY, metadata_json TEXT NOT NULL);
    ''')
    return db


def build(args):
    included = dict(named_path(x) for x in args.input)
    excluded = [excluded_path(x) for x in args.excluded]
    names = list(included) + [x[0] for x in excluded]
    if len(names) != len(set(names)) or not included:
        raise ValueError('source names must be unique and at least one input is required')
    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    work = tempfile.TemporaryDirectory(prefix='.snapshot-build-', dir=out)
    db_path = Path(work.name) / 'snapshot.sqlite'
    db = create_db(db_path)
    manifest = {'schema': SCHEMA, 'cutoff': args.cutoff, 'coverage': 'dated partial census',
                'confirmed_distinct_attempts': None, 'inputs': {},
                'reconciliation': 'Input namespaces remain separate; cross-source attempt equivalence unassessed.'}
    try:
        for name in sorted(included):
            data = included[name].read_bytes()
            root = json.loads(data)
            if not isinstance(root, dict):
                raise ValueError(f'{name} must be a JSON object')
            counts = Counter()
            seen_paths = set()
            for collection, path, obj, parent, nested in records(name, root):
                if path in seen_paths:
                    raise ValueError(f'duplicate path {name}:{path}')
                seen_paths.add(path)
                kind = classify(collection, obj, parent)
                norm = normalize(obj, parent, root, kind)
                rid = sha(f'{name}:{path}'.encode())
                parent_path = path.rsplit('.', 1)[0] if nested else None
                db.execute('INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                           (rid, name, collection, path, parent_path, kind,
                            norm['source_id'], norm['source_object_id'], norm['acquisition_id'],
                            norm['parser_version'], norm['observation_version'], norm['event_name'],
                            norm['event_date'], norm['date_from'], norm['date_to'], norm['date_scope_json'],
                            norm['session'], norm['category'], norm['discipline'],
                            norm['page'], canon(norm['citation']), norm['review_status'],
                            norm['raw_fields_json'], norm['parsed_fields_json'], canon(obj)))
                counts[collection] += 1
            metadata = {k: v for k, v in root.items() if not isinstance(v, list)}
            db.execute('INSERT INTO source_metadata VALUES (?,?)', (name, canon(metadata)))
            manifest['inputs'][name] = {'status': 'included', 'sha256': sha(data),
                'bytes': len(data), 'path': str(included[name].resolve()),
                'source_schema': root.get('schema'), 'source_sha256': root.get('source_sha256') or (root.get('source') or {}).get('sha256') if isinstance(root.get('source'), dict) else root.get('source_sha256'),
                'collections': dict(sorted(counts.items())), 'record_count': sum(counts.values()), 'metadata_table': 'source_metadata',
                'top_level_list_lengths': {k: len(v) for k, v in sorted(root.items()) if isinstance(v, list)}}
        for name, path, reason in sorted(excluded):
            data = path.read_bytes()
            excluded_root = json.loads(data)
            observed = Counter(collection for collection, _, _, _, _ in records(name, excluded_root))
            manifest['inputs'][name] = {'status': 'excluded', 'reason': reason,
                'sha256': sha(data), 'bytes': len(data), 'path': str(path.resolve()),
                'record_count': 0, 'observed_collections': dict(sorted(observed.items())),
                'observed_record_count': sum(observed.values())}
        db.commit()
        db.execute('VACUUM')
    except Exception:
        db.close()
        work.cleanup()
        raise
    else:
        db.close()
    manifest['snapshot_sha256'] = sha(db_path.read_bytes())
    manifest_tmp = Path(work.name) / 'manifest.json'
    manifest_tmp.write_text(canon(manifest) + '\n', encoding='utf-8')
    os.replace(db_path, out / 'snapshot.sqlite')
    os.replace(manifest_tmp, out / 'manifest.json')
    work.cleanup()
    print(canon({'db': str(out / 'snapshot.sqlite'), 'records': sum(x['record_count'] for x in manifest['inputs'].values()),
                 'sha256': manifest['snapshot_sha256']}))


def verify(args):
    out = Path(args.output_dir)
    manifest = json.loads((out / 'manifest.json').read_text())
    if manifest['snapshot_sha256'] != sha((out / 'snapshot.sqlite').read_bytes()):
        raise ValueError('snapshot hash mismatch')
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        for name, item in manifest['inputs'].items():
            if item['status'] == 'included':
                actual = dict(db.execute('SELECT collection,count(*) FROM records WHERE source_name=? GROUP BY collection', (name,)))
                if actual != item['collections']:
                    raise ValueError(f'collection count mismatch: {name}')
    print('verified')



def replay(args):
    out = Path(args.output_dir)
    manifest = json.loads((out / 'manifest.json').read_text())
    inputs = []
    excluded = []
    for name, item in manifest['inputs'].items():
        path = Path(item['path'])
        if sha(path.read_bytes()) != item['sha256']:
            raise ValueError(f'input hash mismatch: {name}')
        if item['status'] == 'included':
            inputs.append(f'{name}={path}')
        else:
            excluded.append(f"{name}={path}:{item['reason']}")
    build(argparse.Namespace(output_dir=str(out), cutoff=manifest['cutoff'],
                             input=inputs, excluded=excluded))
    new = json.loads((out / 'manifest.json').read_text())
    if new['snapshot_sha256'] != manifest['snapshot_sha256']:
        raise ValueError('replay hash mismatch')
    print('replayed and verified')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    b = sub.add_parser('build')
    b.add_argument('--cutoff', required=True)
    b.add_argument('--input', action='append', default=[], metavar='NAME=PATH')
    b.add_argument('--excluded', action='append', default=[], metavar='NAME=PATH:REASON')
    b.add_argument('--output-dir', required=True)
    v = sub.add_parser('verify')
    v.add_argument('--output-dir', required=True)
    r = sub.add_parser('replay')
    r.add_argument('--output-dir', required=True)
    args = p.parse_args()
    {'build': build, 'verify': verify, 'replay': replay}[args.command](args)


if __name__ == '__main__':
    main()
