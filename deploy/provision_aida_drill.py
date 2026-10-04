#!/usr/bin/env python3
"""Root-only provisioning of an empty private AIDA PostgreSQL restore database."""
import argparse
import json
import os
from pathlib import Path
import re
import sys

from migrate_only import (deployment_state, ensure_private_dir, expected_migrations,
                          file_digest, postgres, query, version_state, verify_roles)
from provision_canonical import (database_exists, database_identity, private_acl,
                                 public_config, public_counts, target_versions)

MARKER = 'freediving-aida-restore-drill-v1'
TARGET = 'freediving_canonical'
PUBLIC_ORIGIN = 'https://poc.alphacompose.com'


def failure_message():
    return 'Private AIDA restore database provisioning refused or failed; inspect root-private intent.'


def database_empty(database, port):
    """An untouched template0 database has no user relations, routines or extensions."""
    checks = (
        "SELECT count(*) FROM pg_namespace WHERE nspname NOT IN "
        "('pg_catalog','information_schema','pg_toast','public') "
        "AND nspname NOT LIKE 'pg_temp_%' AND nspname NOT LIKE 'pg_toast_temp_%'",
        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
        "WHERE n.nspname NOT IN ('pg_catalog','information_schema','pg_toast') "
        "AND n.nspname NOT LIKE 'pg_temp_%' AND n.nspname NOT LIKE 'pg_toast_temp_%'",
        "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
        "WHERE n.nspname NOT IN ('pg_catalog','information_schema','pg_toast')",
        "SELECT count(*) FROM pg_extension WHERE extname <> 'plpgsql'",
    )
    return all(query(database, sql, port) == '0' for sql in checks)


def private_connect_acl(database, port):
    # Database owner keeps implicit access. No other role may CONNECT or TEMP.
    sql = ("SELECT EXISTS(SELECT 1 FROM pg_database d, "
           "LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a "
           "WHERE d.datname='" + database + "' AND a.grantee <> d.datdba "
           "AND a.privilege_type IN ('CONNECT','TEMPORARY'))")
    return query('postgres', sql, port) == 'f' and private_acl(database, port)


def target_ready(port):
    owner, marker = database_identity(TARGET, port)
    return (owner == 'freediving_migrator' and marker == 'freediving-private-canonical-v1'
            and private_acl(TARGET, port)
            and target_versions(TARGET, port) == expected_migrations())


def public_checkpoint(args, public_database, port):
    return (version_state(public_database, port), public_counts(public_database, port),
            tuple(file_digest(args.config_dir / name) for name in ('migration.env', 'public.env')),
            deployment_state(args.public_current, args.owner_current, args.owner_status,
                             PUBLIC_ORIGIN))


def intent_file(directory, database):
    legacy = directory / 'aida-drill-intent.json'
    named = directory / ('aida-drill-intent-' + database + '.json')
    if legacy.exists() or legacy.is_symlink():
        value = private_intent_value(legacy)
        if value.get('schema') != MARKER or not re.fullmatch(
                r'aida_drill_[a-z0-9_]{1,52}', value.get('database', '')):
            raise ValueError('Legacy drill intent changed')
        if value['database'] == database:
            if named.exists() or named.is_symlink():
                raise ValueError('Duplicate drill intents')
            return legacy
    return named


def private_intent_value(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_uid != 0 or path.stat().st_mode & 0o077:
        raise ValueError('Drill intent is not root-private')
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError('Drill intent format changed')
    return value


def read_intent(path, expected):
    if private_intent_value(path) != expected:
        raise ValueError('Drill intent binding changed')


def provision(args):
    if os.geteuid() != 0:
        raise ValueError('Root required')
    if (not re.fullmatch(r'aida_drill_[a-z0-9_]{1,52}', args.database)
            or args.expected_target_database != TARGET
            or args.expected_public_database in (TARGET, args.database)):
        raise ValueError('Unexpected drill or canonical target')
    port, public_database, _ = public_config(args.config_dir, args.expected_public_database)
    verify_roles(public_database, port)
    if not target_ready(port):
        raise ValueError('Dedicated canonical target changed')
    expected_versions = expected_migrations()
    before = public_checkpoint(args, public_database, port)
    if before[0] != expected_versions:
        raise ValueError('Public migration checksums changed')
    intent = {'schema': MARKER, 'database': args.database, 'target': TARGET,
              'public_database': public_database, 'port': port,
              'public_config_sha256': dict(zip(('migration.env', 'public.env'), before[2]))}
    path = intent_file(args.intent_dir, args.database)
    exists = database_exists(args.database, port)
    if exists and not path.exists():
        raise ValueError('Unmarked existing drill database')
    ensure_private_dir(args.intent_dir)
    if path.exists():
        read_intent(path, intent)
    else:
        with path.open('x') as stream:
            os.chmod(path, 0o600)
            json.dump(intent, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
    if not exists:
        postgres('createdb', '-h', '/var/run/postgresql', '-p', port, '-T', 'template0',
                 '-O', 'freediving_migrator', args.database)
    owner, marker = database_identity(args.database, port)
    if owner != 'freediving_migrator' or marker not in ('', MARKER):
        raise ValueError('Drill owner or marker changed')
    if not database_empty(args.database, port):
        raise ValueError('Drill database is not empty')
    if marker == '':
        postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                 '-p', port, '-d', 'postgres', '-c',
                 "COMMENT ON DATABASE " + args.database + " IS '" + MARKER + "'")
    if not private_connect_acl(args.database, port):
        # Only repair the default ACL on an otherwise empty, owned drill.
        postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                 '-p', port, '-d', 'postgres', '-c',
                 'REVOKE ALL ON DATABASE ' + args.database + ' FROM PUBLIC')
    if not private_connect_acl(args.database, port):
        raise ValueError('Drill CONNECT ACL is not private')
    if public_checkpoint(args, public_database, port) != before or not target_ready(port):
        raise ValueError('Public or canonical checkpoint changed')
    print('Private AIDA restore database ready: ' + args.database)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    parser.add_argument('--expected-target-database', required=True)
    parser.add_argument('--expected-public-database', required=True)
    parser.add_argument('--config-dir', type=Path, default=Path('/etc/freediving'))
    parser.add_argument('--intent-dir', type=Path, default=Path('/var/backups/freediving/aida-drill'))
    parser.add_argument('--public-current', type=Path, default=Path('/opt/freediving/current'))
    parser.add_argument('--owner-current', type=Path, default=Path('/var/lib/freediving-owner-evidence/current'))
    parser.add_argument('--owner-status', type=Path, default=Path('/var/lib/freediving-owner-evidence/status/presentation-status.json'))
    args = parser.parse_args()
    os.umask(0o077)
    try:
        provision(args)
    except Exception:
        print(failure_message(), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
