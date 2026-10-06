#!/usr/bin/env python3
"""Read-only schema-23 guard for the exact retained June 3 AIDA source append.

No rows or capabilities are restored or written. New import timestamps are the
only generated fields: all other new row bytes must match the pinned rehearsal
manifest, and every preexisting full row fingerprint must remain present.
"""
import argparse
from collections import Counter
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess

SOURCE = '67933b6afa56c7c4cff1df14b9b415d2e32d33f49feb10f24be46d4b59fa3e93'
TABLES = ('extractions', 'observations')
JOBS = ('6e6f0e1bae9e3923576907eec3a9f9d98007bc7d35d406c5bb7632f52addbeae',
        'bf85d8f3c3c7076853d27b21f0624b465bb7cf10a76bd24f28590c115bb41fa0')
HEX = re.compile(r'^[0-9a-f]{64}$')


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
        separators=(',', ':')).encode()).hexdigest()


def _name(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_]+', value):
        raise ValueError('invalid database or table name')
    return value


def _hash(value):
    if not isinstance(value, str) or not HEX.fullmatch(value):
        raise ValueError('invalid immutable manifest pin')
    return value


def _import_row(table, row):
    result = {key: value for key, value in row.items() if table != 'extractions' or key != 'imported_at'}
    return digest(result)


def build_manifest(database, rows, selector, retained_manifest_sha256, *, public_database=None):
    """Build from independently rehearsed exact rows, before the live append."""
    _name(database); _hash(retained_manifest_sha256)
    if public_database is not None: _name(public_database)
    if selector != {'selected_date': '2026-06-03', 'filters': {}} or set(rows) != set(TABLES):
        raise ValueError('retained source selector or tables mismatch')
    extractions = rows['extractions']
    if len(extractions) != 2 or {x.get('parser_version') for x in extractions} != {'aida-html/1'} or {x.get('job_id') for x in extractions} != set(JOBS):
        raise ValueError('both exact retained artifact versions required')
    expected = {table: {} for table in TABLES}
    for row in extractions:
        if set(row) != {'job_id', 'artifact_sha256', 'source_sha256', 'parser_version', 'schema_version', 'artifact_bytes', 'imported_at'}:
            raise ValueError('extraction row schema mismatch')
        job = _hash(row['job_id']); _hash(row['artifact_sha256'])
        if row['source_sha256'] != SOURCE or row['schema_version'] != 4:
            raise ValueError('retained source hash or extraction schema mismatch')
        try:
            if not row['artifact_bytes'].startswith('\\x'): raise ValueError()
            artifact = bytes.fromhex(row['artifact_bytes'][2:])
        except (AttributeError, ValueError):
            raise ValueError('invalid extraction artifact bytes') from None
        if hashlib.sha256(artifact).hexdigest() != row['artifact_sha256']:
            raise ValueError('extraction artifact hash mismatch')
        if job in expected['extractions']: raise ValueError('duplicate retained job')
        expected['extractions'][job] = {'sha256': _import_row('extractions', row),
            'parser_version': row['parser_version'], 'artifact_sha256': row['artifact_sha256']}
    ordinals = {job: [] for job in expected['extractions']}
    for row in rows['observations']:
        if set(row) != {'job_id', 'ordinal', 'candidate_id', 'kind', 'classification_reason', 'payload_edn'}:
            raise ValueError('observation row schema mismatch')
        job = row['job_id']; ordinal = row['ordinal']; _hash(row['candidate_id'])
        if (job not in ordinals or type(ordinal) is not int or ordinal < 0 or row['kind'] != 'result-row'
                or row['classification_reason'] != 'parsed-or-explicit-result-fields' or not isinstance(row['payload_edn'], str)):
            raise ValueError('observation job or row mismatch')
        key = job + ':' + str(ordinal)
        if key in expected['observations']: raise ValueError('duplicate retained ordinal')
        ordinals[job].append(ordinal)
        expected['observations'][key] = {'sha256': _import_row('observations', row)}
    if any(sorted(values) != list(range(209)) for values in ordinals.values()):
        raise ValueError('full 209-position row accounting required per version')
    return {'schema': 'retained-source-append-manifest/v1', 'database': database,
        'source_sha256': SOURCE, 'selector': selector, 'retained_manifest_sha256': retained_manifest_sha256,
        'public_database': public_database,
        'positions_per_version': 209, 'selected_women_positions': 103, 'unselected_men_positions': 106,
        'expected': expected}


def snapshot_rows(databases, schemas, protected=None, legacy_hashes=None):
    """Keep full row fingerprints, without persisting athlete or artifact payloads."""
    result = {'schema': 'retained-source-append-snapshot/v1', 'databases': {}, 'protected': protected}
    if set(databases) != set(schemas): raise ValueError('database schema pins mismatch')
    for database, tables in databases.items():
        _name(database); _hash(schemas[database])
        values = {}
        for table, rows in tables.items():
            _name(table)
            entries = []
            for row in rows:
                entry = {'sha256': digest(row)}
                if table in TABLES:
                    entry.update({'import_sha256': _import_row(table, row), 'job_id': row['job_id']})
                    if table == 'observations': entry['ordinal'] = row['ordinal']
                    else: entry['imported_at'] = row['imported_at']
                entries.append(entry)
            values[table] = {'count': len(entries), 'rows': sorted(entries, key=lambda x: x['sha256'])}
            if legacy_hashes is not None: values[table]['legacy_sha256'] = legacy_hashes[database][table]
        result['databases'][database] = {'schema_sha256': schemas[database], 'tables': values}
    return result


def _instant(value):
    try:
        value = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
        if value.tzinfo is None: raise ValueError()
        return value
    except (AttributeError, ValueError):
        raise ValueError('invalid bounded import timestamp') from None


def _key(table, entry):
    return entry['job_id'] if table == 'extractions' else entry['job_id'] + ':' + str(entry['ordinal'])


def _protected(snapshot, manifest):
    value = json.loads(json.dumps(snapshot['protected']))
    if value is not None:
        database = manifest['database']
        try:
            aliases = [value['authority']['source_review_tables'][database]]
            if manifest.get('public_database') == database:
                aliases.append(value['authority']['public_tables'])
            for tables in aliases:
                for table in TABLES:
                    captured = snapshot['databases'][database]['tables'][table]
                    if tables[table] != {'count': captured['count'], 'sha256': captured['legacy_sha256']}:
                        raise ValueError('protected table alias does not match exact captured database')
                    tables[table] = 'exact-source-only-append-checked-separately'
        except (KeyError, TypeError):
            raise ValueError('protected comparison guard lacks exact source database fingerprints') from None
    return value


def preflight(before, manifest):
    """Declare exact remaining inserts; refuse conflicting or partial prior jobs."""
    if before.get('source_database', manifest['database']) != manifest['database']:
        raise ValueError('captured source database differs from append manifest')
    _protected(before, manifest)
    tables = before['databases'][manifest['database']]['tables']
    seen = {}
    for table in TABLES:
        seen[table] = set()
        for row in tables[table]['rows']:
            key = _key(table, row)
            if key in manifest['expected'][table]:
                if key in seen[table] or row['import_sha256'] != manifest['expected'][table][key]['sha256']:
                    raise ValueError('preexisting exact source row conflicts with manifest')
                seen[table].add(key)
    for job in manifest['expected']['extractions']:
        present = {key for key in seen['observations'] if key.startswith(job + ':')}
        if (job in seen['extractions'] and len(present) != 209) or (job not in seen['extractions'] and present):
            raise ValueError('partial existing source job refused')
    return {'schema': 'retained-source-append-preflight/v1', 'manifest_sha256': digest(manifest),
        'before_sha256': digest(before), 'expected_deltas': {
            table: len(manifest['expected'][table]) - len(seen[table]) for table in TABLES},
        'authority_granted': False}


def verify_append(before, after, manifest, started_at, ended_at):
    """Refuse changes outside the exact declared rows; permit zero-write replay."""
    if (before.get('schema') != 'retained-source-append-snapshot/v1' or after.get('schema') != before['schema']
            or manifest.get('schema') != 'retained-source-append-manifest/v1'):
        raise ValueError('invalid append guard schema')
    database = manifest['database']
    if any(snapshot.get('source_database', database) != database for snapshot in (before, after)):
        raise ValueError('captured source database differs from append manifest')
    start = _instant(started_at); end = _instant(ended_at)
    if start > end: raise ValueError('invalid bounded import timestamp window')
    if (manifest['source_sha256'] != SOURCE or manifest['selector'] != {'selected_date': '2026-06-03', 'filters': {}}
            or set(manifest['expected']) != set(TABLES) or len(manifest['expected']['extractions']) != 2
            or len(manifest['expected']['observations']) != 418):
        raise ValueError('retained source manifest mismatch')
    if set(before['databases']) != set(after['databases']) or database not in before['databases']:
        raise ValueError('database inventory changed')
    if _protected(before, manifest) != _protected(after, manifest):
        raise ValueError('protected authority, public binding or capability changed')
    deltas = {}
    for db, old in before['databases'].items():
        new = after['databases'][db]
        if old['schema_sha256'] != new['schema_sha256'] or set(old['tables']) != set(new['tables']):
            raise ValueError('database schema or table inventory changed')
        deltas[db] = {}
        for table, previous in old['tables'].items():
            current = new['tables'][table]
            if previous['count'] != len(previous['rows']) or current['count'] != len(current['rows']):
                raise ValueError('row fingerprint accounting mismatch')
            previous_hashes = Counter(x['sha256'] for x in previous['rows'])
            current_hashes = Counter(x['sha256'] for x in current['rows'])
            if previous_hashes - current_hashes: raise ValueError('existing row changed or removed')
            added = current_hashes - previous_hashes
            deltas[db][table] = sum(added.values())
            if db != database or table not in TABLES:
                if added: raise ValueError('unrelated table writes refused')
                continue
            expected = manifest['expected'][table]
            seen = set()
            for row in current['rows']:
                key = _key(table, row)
                if added[row['sha256']]:
                    if key not in expected or row['import_sha256'] != expected[key]['sha256']:
                        raise ValueError('unexpected source row or payload refused')
                    if table == 'extractions' and not start <= _instant(row['imported_at']) <= end:
                        raise ValueError('new extraction imported_at outside apply window')
                    added[row['sha256']] -= 1
                if key in expected:
                    if key in seen or row['import_sha256'] != expected[key]['sha256']:
                        raise ValueError('retained source row identity or payload mismatch')
                    seen.add(key)
            if seen != set(expected): raise ValueError('expected retained source rows missing')
    return {'schema': 'retained-source-append-receipt/v1', 'manifest_sha256': digest(manifest),
        'before_sha256': digest(before), 'after_sha256': digest(after), 'started_at': started_at,
        'ended_at': ended_at, 'deltas': deltas, 'existing_rows_preserved': True,
        'authority_granted': False, 'generated_fields': ['new extractions.imported_at'],
        'checked_guard_aliases': ['authority.source_review_tables.' + database]
            + (['authority.public_tables'] if manifest.get('public_database') == database else [])}


def capture_databases(databases, *, source_database, peer=False, psql='psql', protected=None):
    """Require source schema 23; preserve other existing contiguous inventories."""
    _name(source_database)
    if source_database not in databases or len(set(databases)) != len(databases):
        raise ValueError('exact source database missing or duplicate capture database')
    rows = {}; schemas = {}; legacy_hashes = {}
    for database in databases:
        _name(database)
        command = ([] if peer else ['sudo', '-n', '-u', 'postgres']) + [psql, '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-d', database, '-At']
        # Catalog and rows share a repeatable snapshot. Payloads stay in memory.
        sql = r"""BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object('catalog', COALESCE(json_agg(x ORDER BY x::text)::text,'[]')) FROM (
 SELECT json_build_object('kind','column','table',table_name,'name',column_name,'type',data_type,'udt',udt_name,'nullable',is_nullable,'default',column_default,'ordinal',ordinal_position) x FROM information_schema.columns WHERE table_schema='freediving'
 UNION ALL SELECT json_build_object('kind','constraint','table',c.relname,'name',a.conname,'definition',pg_get_constraintdef(a.oid)) FROM pg_constraint a JOIN pg_class c ON c.oid=a.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='freediving'
 UNION ALL SELECT json_build_object('kind','index','table',tablename,'name',indexname,'definition',indexdef) FROM pg_indexes WHERE schemaname='freediving'
 UNION ALL SELECT json_build_object('kind','trigger','table',c.relname,'name',t.tgname,'definition',pg_get_triggerdef(t.oid)) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='freediving' AND NOT t.tgisinternal
 UNION ALL SELECT json_build_object('kind','function','name',p.proname,'definition',pg_get_functiondef(p.oid)) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='freediving' AND p.prokind IN ('f','p')
 UNION ALL SELECT json_build_object('kind','relation','name',c.relname,'owner',c.relowner,'acl',c.relacl,'relrowsecurity',c.relrowsecurity,'force',c.relforcerowsecurity) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='freediving'
 UNION ALL SELECT json_build_object('kind','view','name',viewname,'definition',definition) FROM pg_views WHERE schemaname='freediving'
) q;
SELECT format('SELECT json_build_object(''table'',%L,''rows_text'',COALESCE(json_agg(row_to_json(t) ORDER BY row_to_json(t)::text)::text,''[]'')) FROM freediving.%I t;',tablename,tablename) FROM pg_tables WHERE schemaname='freediving' ORDER BY tablename \gexec
COMMIT;
"""
        process = subprocess.run(command, input=sql, capture_output=True, text=True, timeout=120)
        if process.returncode: raise ValueError('read-only source append snapshot refused')
        tables = {}; catalog = None; legacy = {}
        for line in process.stdout.splitlines():
            value = json.loads(line)
            if 'catalog' in value: catalog = value['catalog']
            else:
                table = _name(value['table']); tables[table] = json.loads(value['rows_text'])
                legacy[table] = hashlib.sha256(value['rows_text'].encode()).hexdigest()
        versions = [x['version'] for x in sorted(tables.get('schema_migrations', []), key=lambda x: x['version'])]
        if database == source_database and (catalog is None or versions != list(range(1, 24))):
            raise ValueError('source database requires exact migration inventory 1 through 23')
        if catalog is None or not versions or any(type(version) is not int for version in versions) or versions != list(range(1, versions[-1]+1)):
            raise ValueError('guarded database requires existing contiguous migration inventory')
        rows[database] = tables; schemas[database] = hashlib.sha256(catalog.encode()).hexdigest(); legacy_hashes[database] = legacy
    snapshot = snapshot_rows(rows, schemas, protected, legacy_hashes)
    snapshot['source_database'] = source_database
    return snapshot


def _read(path, pin=None):
    path = Path(path)
    if path.is_symlink() or not path.is_file(): raise ValueError('missing or linked guard file')
    data = path.read_bytes()
    if pin is not None and hashlib.sha256(data).hexdigest() != _hash(pin): raise ValueError('guard file pin changed')
    return json.loads(data)


def _write(path, value):
    # Exclusive creation avoids overwriting an earlier checkpoint or receipt.
    with open(path, 'x', encoding='utf-8') as stream:
        import os
        os.chmod(path, 0o600)
        json.dump(value, stream, sort_keys=True, ensure_ascii=False, indent=2); stream.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    capture = commands.add_parser('capture'); capture.add_argument('--database', action='append', required=True)
    capture.add_argument('--source-database', required=True)
    capture.add_argument('--peer', action='store_true'); capture.add_argument('--psql', default='psql')
    capture.add_argument('--protected-guard', required=True); capture.add_argument('--protected-guard-sha256', required=True)
    capture.add_argument('--output', required=True)
    manifest = commands.add_parser('manifest'); manifest.add_argument('--expected-rows', required=True)
    manifest.add_argument('--expected-rows-sha256', required=True); manifest.add_argument('--database', required=True)
    manifest.add_argument('--public-database'); manifest.add_argument('--retained-manifest-sha256', required=True); manifest.add_argument('--output', required=True)
    pre = commands.add_parser('preflight')
    for name in ('before', 'manifest'):
        pre.add_argument('--' + name, required=True); pre.add_argument('--' + name + '-sha256', required=True)
    pre.add_argument('--output', required=True)
    verify = commands.add_parser('verify')
    for name in ('before', 'after', 'manifest'):
        verify.add_argument('--' + name, required=True); verify.add_argument('--' + name + '-sha256', required=True)
    verify.add_argument('--started-at', required=True); verify.add_argument('--ended-at', required=True); verify.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.command == 'capture':
        value = capture_databases(args.database, source_database=args.source_database, peer=args.peer, psql=args.psql,
            protected=_read(args.protected_guard, args.protected_guard_sha256))
    elif args.command == 'manifest':
        value = build_manifest(args.database, _read(args.expected_rows, args.expected_rows_sha256),
            {'selected_date': '2026-06-03', 'filters': {}}, args.retained_manifest_sha256, public_database=args.public_database)
    elif args.command == 'preflight':
        value = preflight(*[_read(getattr(args, name), getattr(args, name + '_sha256')) for name in ('before', 'manifest')])
    else:
        value = verify_append(*[_read(getattr(args, name), getattr(args, name + '_sha256')) for name in ('before', 'after', 'manifest')], args.started_at, args.ended_at)
    _write(args.output, value)
    print(json.dumps({'schema': value['schema'], 'sha256': hashlib.sha256(Path(args.output).read_bytes()).hexdigest(),
        **({'deltas': value['deltas']} if 'deltas' in value else {})}, sort_keys=True))


if __name__ == '__main__':
    main()
