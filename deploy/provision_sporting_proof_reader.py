#!/usr/bin/env python3
"""Explicit exact-table sporting proof reader; no review or sporting data writes.

Host execution requires fresh comparison/authority guards and committed runtime
pins. Existing capabilities are verified, never broadened or silently replaced.
Resolved database credentials are written only to their intended host config.
"""
import argparse
import copy
import grp
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROLE = 'sporting_proof_read'
DATABASE = 'freediving_release_20260924_11'
TABLES = ('extractions', 'observations', 'extraction_reviews', 'pdf_extraction_reviews',
          'review_proposals', 'review_decisions', 'publication_decisions',
          'publication_policy_events', 'revision_proposals', 'revision_decisions',
          'event_selections')
CANONICAL_DATABASE = 'freediving_canonical'
CANONICAL_TABLES = ('extractions', 'observations', 'canonical_attempt_evidence',
                    'canonical_attempt_events', 'canonical_attempt_state')
CONFIG = Path('/var/lib/freediving-owner-evidence/sporting-proof-reader/config.json')


def grant_sql(password, database=DATABASE):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', database):
        raise ValueError('invalid proof authority database')
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', password):
        raise ValueError('invalid generated proof capability')
    return ("BEGIN; CREATE ROLE " + ROLE + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
            + password + "'; ALTER ROLE " + ROLE + " SET default_transaction_read_only=on; "
            "GRANT CONNECT ON DATABASE " + database + " TO " + ROLE + "; "
            "GRANT USAGE ON SCHEMA freediving TO " + ROLE + "; GRANT SELECT ON "
            + ','.join('freediving.' + table for table in TABLES) + ' TO ' + ROLE + '; COMMIT;')


def canonical_grant_sql(database=CANONICAL_DATABASE):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', database):
        raise ValueError('invalid proof relationship database')
    return ('BEGIN; GRANT CONNECT ON DATABASE ' + database + ' TO ' + ROLE
            + '; GRANT USAGE ON SCHEMA freediving TO ' + ROLE + '; GRANT SELECT ON '
            + ','.join('freediving.' + table for table in CANONICAL_TABLES) + ' TO ' + ROLE + '; COMMIT;')


def grant_report_sql():
    # Effective grants include inherited PUBLIC grants, not only explicit ACLs.
    return """WITH role AS (SELECT * FROM pg_roles WHERE rolname='%s'),
    tables AS (SELECT n.nspname||'.'||c.relname AS name,
        ARRAY(SELECT permission FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER'] || CASE WHEN current_setting('server_version_num')::int>=170000 THEN ARRAY['MAINTAIN'] ELSE ARRAY[]::text[] END) permission
            WHERE has_table_privilege('%s',c.oid,permission)) AS permissions
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE c.relkind IN ('r','p','v','m','f') AND n.nspname NOT LIKE 'pg_%%' AND n.nspname<>'information_schema'),
    sequences AS (SELECT n.nspname||'.'||c.relname AS name,
        ARRAY(SELECT permission FROM unnest(ARRAY['SELECT','UPDATE','USAGE']) permission
            WHERE has_sequence_privilege('%s',c.oid,permission)) AS permissions
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE c.relkind='S' AND n.nspname NOT LIKE 'pg_%%')
    SELECT json_build_object('role',rolname,'login',rolcanlogin,'superuser',rolsuper,
        'createdb',rolcreatedb,'createrole',rolcreaterole,'inherit',rolinherit,
        'replication',rolreplication,'bypassrls',rolbypassrls,
        'readonly', EXISTS(SELECT 1 FROM pg_db_role_setting s WHERE s.setrole=role.oid AND s.setdatabase=0
                           AND 'default_transaction_read_only=on'=ANY(s.setconfig))
                    AND NOT EXISTS(SELECT 1 FROM pg_db_role_setting s WHERE s.setrole=role.oid
                           AND 'default_transaction_read_only=off'=ANY(s.setconfig)),
        'memberships', ARRAY(SELECT parent.rolname FROM pg_auth_members m JOIN pg_roles parent ON parent.oid=m.roleid
                              WHERE m.member=role.oid OR m.roleid=role.oid),
        'owned', ARRAY(SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relowner=role.oid
                       UNION ALL SELECT nspname FROM pg_namespace WHERE nspowner=role.oid
                       UNION ALL SELECT proname FROM pg_proc WHERE proowner=role.oid
                       UNION ALL SELECT datname FROM pg_database WHERE datdba=role.oid),
        'tables', COALESCE((SELECT json_object_agg(name,permissions) FROM tables WHERE cardinality(permissions)>0),'{}'::json),
        'sequences', COALESCE((SELECT json_object_agg(name,permissions) FROM sequences WHERE cardinality(permissions)>0),'{}'::json),
        'schema_create', ARRAY(SELECT nspname FROM pg_namespace n WHERE nspname NOT LIKE 'pg_%%'
                               AND nspname<>'information_schema' AND has_schema_privilege(role.oid,n.oid,'CREATE')),
        'column_extras', ARRAY(SELECT n.nspname||'.'||c.relname||'.'||a.attname||':'||permission
                              FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                              JOIN pg_attribute a ON a.attrelid=c.oid
                              CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','REFERENCES']) permission
                              WHERE a.attnum>0 AND NOT a.attisdropped AND c.relkind IN ('r','p','v','m','f')
                              AND n.nspname NOT LIKE 'pg_%%' AND n.nspname<>'information_schema'
                              AND has_column_privilege(role.oid,c.oid,a.attnum,permission)
                              AND NOT has_table_privilege(role.oid,c.oid,permission)),
        'default_privileges', ARRAY(SELECT d.defaclobjtype::text||':'||x.privilege_type FROM pg_default_acl d,
                                   LATERAL aclexplode(d.defaclacl) x WHERE x.grantee IN (0,role.oid)),
        'definer_functions', ARRAY(SELECT p.oid::regprocedure::text FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                                  WHERE p.prosecdef AND n.nspname NOT LIKE 'pg_%%' AND has_function_privilege(role.oid,p.oid,'EXECUTE')))
        FROM role""" % (ROLE, ROLE, ROLE)


def validate_grants(report, tables=TABLES):
    expected = {'freediving.' + name: ['SELECT'] for name in tables}
    if (set(report) != {'role', 'login', 'superuser', 'createdb', 'createrole', 'inherit',
                       'replication', 'bypassrls', 'readonly', 'memberships', 'owned',
                       'tables', 'sequences', 'definer_functions', 'schema_create', 'column_extras', 'default_privileges'}
            or report['role'] != ROLE or report['login'] is not True
            or report['readonly'] is not True
            or any(report[key] is not False for key in
                   ('superuser', 'createdb', 'createrole', 'inherit', 'replication', 'bypassrls'))
            or any(report[key] for key in ('memberships', 'owned', 'sequences', 'definer_functions', 'schema_create', 'column_extras', 'default_privileges'))
            or report['tables'] != expected):
        raise ValueError('sporting proof capability grants changed')
    return report


def pg(sql, database=DATABASE):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', database):
        raise ValueError('invalid proof authority database')
    result = subprocess.run(['sudo', '-n', '-u', 'postgres', 'psql', '-X', '-v',
                             'ON_ERROR_STOP=1', '-d', database, '-At'],
                            input=sql, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise ValueError('sporting proof capability checkpoint refused')
    return result.stdout.strip()


def verify_grants(database=DATABASE, tables=TABLES, query=None):
    query = query or (lambda sql: pg(sql, database))
    return validate_grants(json.loads(query(grant_report_sql())), tables)


def unlinked(path, *, regular=False):
    path = Path(path).absolute()
    if any(part.is_symlink() for part in (path, *path.parents)) or (regular and not path.is_file()):
        raise ValueError('linked or missing sporting proof capability input')
    return path


def digest(path):
    return hashlib.sha256(unlinked(path, regular=True).read_bytes()).hexdigest()


def verify_config(path=CONFIG):
    unlinked(path,regular=True)
    info=path.stat()
    if info.st_uid not in (0,os.geteuid()) or info.st_mode&0o027:
        raise ValueError('unsafe sporting proof capability file')
    data=json.loads(path.read_text())
    if set(data)!={'jdbc_url','database','canonical_jdbc_url','canonical_database','runtime_path','runtime_manifest_sha256'}:
        raise ValueError('invalid sporting proof capability configuration')
    passwords=[]
    for dbkey,urlkey in (('database','jdbc_url'),('canonical_database','canonical_jdbc_url')):
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}',data[dbkey]):
            raise ValueError('invalid sporting proof capability database')
        match=re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:5432/'+data[dbkey]+
                           r'\?user='+ROLE+r'&password=([A-Za-z0-9_-]{32,128})&connectTimeout=5&socketTimeout=10',data[urlkey])
        if not match:raise ValueError('invalid sporting proof capability connection')
        passwords.append(match[1])
    if passwords[0]!=passwords[1]:raise ValueError('sporting proof capabilities differ')
    if digest(Path(data['runtime_path'])/'manifest.json')!=data['runtime_manifest_sha256']:
        raise ValueError('sporting proof capability runtime changed')
    return data


def provision(args):
    if os.geteuid() != 0:
        raise ValueError('root proof capability checkpoint required')
    from comparison_activate import capture_guard
    from owner_evidence_activate import Layout
    runtime_manifest = args.runtime / 'manifest.json'
    for path, pin in ((runtime_manifest, args.runtime_manifest_sha256),
                      (args.app_manifest, args.app_manifest_sha256), (args.guard, args.guard_sha256)):
        if not re.fullmatch(r'[0-9a-f]{64}', pin) or digest(path) != pin:
            raise ValueError('sporting proof capability input pin changed')
    runtime_record = json.loads(runtime_manifest.read_text())
    app_record = json.loads(args.app_manifest.read_text())
    if (runtime_record['candidate'] != app_record['candidate']
            or not re.fullmatch('[0-9a-f]{40}', runtime_record['candidate'])):
        raise ValueError('sporting proof runtime differs from private code')
    config = {'database': args.database, 'canonical_database': args.canonical_database, 'runtime_path': str(args.runtime),
              'runtime_manifest_sha256': args.runtime_manifest_sha256}
    unlinked(CONFIG)
    if CONFIG.parent.exists():
        info = CONFIG.parent.stat()
        if not CONFIG.parent.is_dir() or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('unsafe sporting proof capability directory')
    layout = Layout(Path('/opt/freediving/owner-evidence/app'),
                    Path('/var/lib/freediving-owner-evidence'), Path('/etc/systemd/system'),
                    Path('/etc/freediving/owner-evidence.env'))
    expected = json.loads(args.guard.read_text())
    if capture_guard(layout, args.public_database) != expected:
        raise ValueError('live authority guard changed before proof capability provisioning')
    if CONFIG.exists():
        unlinked(CONFIG, regular=True)
        verify_grants(args.database)
        verify_grants(args.canonical_database, CANONICAL_TABLES)
        existing = verify_config(CONFIG)
        if any(existing.get(key) != value for key, value in config.items()):
            raise ValueError('existing proof capability differs; guarded runtime update required')
        return False
    if pg("SELECT count(*) FROM pg_roles WHERE rolname='" + ROLE + "';", args.database) != '0':
        raise ValueError('existing role requires independent capability verification')
    password = secrets.token_urlsafe(36)
    pg(grant_sql(password, args.database), args.database)
    created = False
    try:
        pg(canonical_grant_sql(args.canonical_database), args.canonical_database)
        verify_grants(args.database)
        verify_grants(args.canonical_database, CANONICAL_TABLES)
        # Role provisioning must not change any source, sporting ledger or live app.
        if capture_guard(layout, args.public_database) != expected:
            raise ValueError('live authority changed during proof capability provisioning')
        config['jdbc_url'] = ('jdbc:postgresql://127.0.0.1:5432/' + args.database + '?user='
                              + ROLE + '&password=' + password + '&connectTimeout=5&socketTimeout=10')
        config['canonical_jdbc_url'] = ('jdbc:postgresql://127.0.0.1:5432/' + args.canonical_database + '?user='
                                       + ROLE + '&password=' + password + '&connectTimeout=5&socketTimeout=10')
        group = grp.getgrnam('freediving-evidence').gr_gid
        CONFIG.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
        os.chown(CONFIG.parent, 0, group)
        CONFIG.parent.chmod(0o750)
        fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
        created = True
        with os.fdopen(fd, 'w') as stream:
            json.dump(config, stream, sort_keys=True)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chown(CONFIG, 0, group)
        CONFIG.chmod(0o640)
        return True
    except Exception:
        if created:
            CONFIG.unlink()
        # Undo only the exact grants created here; never restore source data.
        for database, tables in ((args.canonical_database, CANONICAL_TABLES), (args.database, TABLES)):
            pg('BEGIN; REVOKE SELECT ON ' + ','.join('freediving.' + table for table in tables)
               + ' FROM ' + ROLE + '; REVOKE USAGE ON SCHEMA freediving FROM ' + ROLE
               + '; REVOKE CONNECT ON DATABASE ' + database + ' FROM ' + ROLE + '; COMMIT;', database)
        pg('DROP ROLE ' + ROLE + ';', args.database)
        raise


def update_runtime(args):
    """CAS update of two derived runtime pins; preserve every existing credential/grant."""
    if os.geteuid()!=0:raise ValueError('root proof runtime update checkpoint required')
    if not args.runtime.is_absolute():raise ValueError('absolute staged proof runtime required')
    from comparison_activate import capture_guard, _tree
    from owner_evidence_activate import Layout, _atomic_write
    for path,pin in ((CONFIG,args.config_sha256),(args.runtime/'manifest.json',args.runtime_manifest_sha256),
                     (args.app_manifest,args.app_manifest_sha256),(args.guard,args.guard_sha256)):
        if not isinstance(pin,str) or not re.fullmatch('[0-9a-f]{64}',pin) or digest(path)!=pin:
            raise ValueError('sporting proof runtime update input pin changed')
    existing=verify_config(CONFIG)
    if (existing['database'],existing['canonical_database'])!=(args.database,args.canonical_database):
        raise ValueError('sporting proof runtime update database differs')
    runtime=json.loads((args.runtime/'manifest.json').read_text())
    app=json.loads(args.app_manifest.read_text())
    if not re.fullmatch('[0-9a-f]{40}',runtime['candidate']) or runtime['candidate']!=app['candidate']:
        raise ValueError('sporting proof runtime update differs from private code')
    unlinked(args.runtime)
    if _tree(args.runtime)!={**runtime['files'],'manifest.json':args.runtime_manifest_sha256}:
        raise ValueError('sporting proof runtime update files changed')
    source_grants=verify_grants(args.database)
    canonical_grants=verify_grants(args.canonical_database,CANONICAL_TABLES)
    layout=Layout(Path('/opt/freediving/owner-evidence/app'),Path('/var/lib/freediving-owner-evidence'),
                  Path('/etc/systemd/system'),Path('/etc/freediving/owner-evidence.env'))
    expected=json.loads(args.guard.read_text())
    if (expected.get('schema')!='private-comparison-activation-guard/v1'
            or expected.get('protected',{}).get('sporting_proof') is None
            or capture_guard(layout,args.public_database)!=expected):
        raise ValueError('live authority guard changed before proof runtime update')
    source_pin = getattr(args, 'source_review_config_sha256', None)
    source_update = None
    source_review = None
    if expected['protected'].get('source_review') is not None:
        import provision_source_review as source_review
        if (not isinstance(source_pin, str) or not re.fullmatch('[0-9a-f]{64}', source_pin)
                or digest(source_review.CONFIG) != source_pin):
            raise ValueError('exact existing source review config pin required')
        source_existing = source_review.verify_config(source_review.CONFIG)
        if (source_existing['database'] != args.database
                or (source_existing['runtime_path'], source_existing['runtime_manifest_sha256']) !=
                   (existing['runtime_path'], existing['runtime_manifest_sha256'])
                or 'src/freediving/source_accuracy_review.clj' not in runtime['files']):
            raise ValueError('source review runtime update binding differs')
        source_review_grants = source_review.verify_grants(args.database)
        source_update = {**source_existing, 'runtime_path': str(args.runtime),
                         'runtime_manifest_sha256': args.runtime_manifest_sha256}
    elif source_pin is not None:
        raise ValueError('source review config pin has no existing guarded capability')
    updated={**existing,'runtime_path':str(args.runtime),'runtime_manifest_sha256':args.runtime_manifest_sha256}
    if updated==existing:return False
    changes = [(CONFIG, updated, args.config_sha256, 'sporting_proof')]
    if source_update is not None:
        changes.append((source_review.CONFIG, source_update, source_pin, 'source_review'))
    writes = []
    for path, value, pin, name in changes:
        before = path.read_bytes(); info = path.stat()
        if hashlib.sha256(before).hexdigest() != pin:
            raise ValueError('proof capability changed before runtime update')
        after = (json.dumps(value, sort_keys=True) + '\n').encode()
        writes.append((path, before, after, info, name))
    # Both capabilities share one guard. Capture only after both derived swaps.
    if capture_guard(layout,args.public_database)!=expected:
        raise ValueError('live authority changed before proof runtime swap')
    completed = []
    try:
        for path, before, after, info, name in writes:
            if path.read_bytes() != before:
                raise ValueError('proof capability changed before runtime swap')
            _atomic_write(path,after,info.st_mode&0o777)
            completed.append((path,before,after,info,name))
            os.chown(path,info.st_uid,info.st_gid)
        if (verify_grants(args.database)!=source_grants or
                verify_grants(args.canonical_database,CANONICAL_TABLES)!=canonical_grants or
                (source_update is not None and source_review.verify_grants(args.database)!=source_review_grants)):
            raise ValueError('proof runtime update grants changed')
        current=copy.deepcopy(capture_guard(layout,args.public_database))
        for path, before, after, info, name in writes:
            prior=expected['protected'][name]
            capability=current['protected'][name]
            if capability['config']['sha256']!=hashlib.sha256(after).hexdigest():
                raise ValueError('proof runtime update configuration changed')
            capability['config']['sha256']=prior['config']['sha256']
            capability['runtime']=prior['runtime']
        if current!=expected:raise ValueError('live authority changed during proof runtime update')
        return True
    except BaseException:
        # Undo only our exact config writes. Never restore DB history or a newer config.
        for path, before, after, info, name in reversed(completed):
            if digest(path)==hashlib.sha256(after).hexdigest():
                _atomic_write(path,before,info.st_mode&0o777);os.chown(path,info.st_uid,info.st_gid)
        raise



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--update-runtime', action='store_true')
    parser.add_argument('--config-sha256')
    parser.add_argument('--source-review-config-sha256')
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--runtime-manifest-sha256')
    parser.add_argument('--app-manifest', type=Path)
    parser.add_argument('--app-manifest-sha256')
    parser.add_argument('--guard', type=Path)
    parser.add_argument('--guard-sha256')
    parser.add_argument('--public-database', default=DATABASE)
    parser.add_argument('--database', default=DATABASE)
    parser.add_argument('--canonical-database', default=CANONICAL_DATABASE)
    args = parser.parse_args()
    try:
        if args.source_review_config_sha256 and not (args.execute and args.update_runtime):
            raise ValueError('source review config pin requires guarded runtime update')
        if args.verify and (args.execute or args.update_runtime):
            raise ValueError('choose one proof capability action')
        if args.verify:
            data=verify_config()
            if (data['database'],data['canonical_database'])!=(args.database,args.canonical_database):
                raise ValueError('proof capability database differs')
            verify_grants(args.database)
            verify_grants(args.canonical_database, CANONICAL_TABLES)
        elif args.execute:
            if not all((args.runtime, args.runtime_manifest_sha256, args.app_manifest,
                        args.app_manifest_sha256, args.guard, args.guard_sha256)):
                raise ValueError('exact proof capability inputs and guard required')
            if args.update_runtime:
                if not args.config_sha256:raise ValueError('exact existing proof config pin required')
                update_runtime(args)
            else:provision(args)
        print(json.dumps({'executed': args.execute, 'verified': args.verify, 'runtime_update':args.update_runtime, 'role': ROLE,
                          'grants': {args.database: ['SELECT freediving.' + table for table in TABLES],
                                     args.canonical_database: ['SELECT freediving.' + table for table in CANONICAL_TABLES]},
                          'config': str(CONFIG), 'data_writes': 0}))
    except Exception:
        print('Sporting proof capability checkpoint refused', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
