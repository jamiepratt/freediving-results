#!/usr/bin/env python3
"""Provision a separate, empty private canonical database. Root operator only."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile

from migrate_only import (PUBLIC_TABLES, expected_migrations, ensure_private_dir,
                          file_digest, query, run, postgres, RELEASES_ROOT,
                          deployment_state, MIGRATION_DIR, verify_roles,
                          version_state)
from prepare_database import endpoint, read_config

MARKER = 'freediving-private-canonical-v1'
NAME = 'freediving_canonical'
MIGRATION_ORDER = (1, 7, 11, 2, 12, 13, 14, 15, 16, 17, 18, 20,
                   3, 4, 5, 6, 8, 9, 10, 19)


def failure_message():
    return 'Private canonical provisioning refused or failed; inspect private checkpoint before retry.'


def public_config(config, expected_public):
    migration = read_config(config / 'migration.env')
    public = read_config(config / 'public.env')
    if set(migration) != {'FREEDIVING_MIGRATION_URL'} or set(public) != {
            'FREEDIVING_PUBLIC_DATABASE_URL', 'FREEDIVING_SUBMIT_DATABASE_URL',
            'FREEDIVING_PUBLIC_ORIGIN', 'FREEDIVING_GATEWAY_SECRET'}:
        raise ValueError('Unexpected deployment configuration')
    url = migration['FREEDIVING_MIGRATION_URL']
    port, database = endpoint(url, 'freediving_migrator')
    if database != expected_public or database == NAME:
        raise ValueError('Public database binding changed')
    for key, role in (('FREEDIVING_PUBLIC_DATABASE_URL', 'reviews_public'),
                      ('FREEDIVING_SUBMIT_DATABASE_URL', 'corrections_submit')):
        if endpoint(public[key], role) != (port, database):
            raise ValueError('Public database binding changed')
    if public['FREEDIVING_PUBLIC_ORIGIN'] != 'https://poc.alphacompose.com':
        raise ValueError('Unexpected public origin')
    return port, database, url


def target_url(public_url, public_database, target_database):
    return public_url.replace('/' + public_database + '?', '/' + target_database + '?', 1)


def database_exists(database, port):
    return query('postgres', "SELECT count(*) FROM pg_database WHERE datname='" + database + "'", port) == '1'


def database_identity(database, port):
    row = query('postgres', "SELECT pg_get_userbyid(datdba),coalesce(shobj_description(oid,'pg_database'),'') "
                "FROM pg_database WHERE datname='" + database + "'", port)
    return tuple(row.split('|', 1))


def public_counts(database, port):
    return tuple(int(query(database, 'SELECT count(*) FROM freediving.' + table, port))
                 for table in PUBLIC_TABLES)


def target_versions(database, port):
    if query(database, "SELECT to_regclass('freediving.schema_migrations') IS NOT NULL", port) != 't':
        return {}
    result = {}
    for line in query(database, 'SELECT version,sha256 FROM freediving.schema_migrations ORDER BY version', port).splitlines():
        version, digest = line.split('|', 1)
        result[int(version)] = digest
    return result


def verify_target_versions(actual, expected):
    if set(actual) not in (set(MIGRATION_ORDER[:n]) for n in range(len(MIGRATION_ORDER) + 1)):
        raise ValueError('Unexpected canonical migration')
    if any(actual[version] != expected[version] for version in actual):
        raise ValueError('Canonical migration checksum conflict')


def target_empty(database, port):
    # Only the two migration-owned singleton rows may exist.
    rows = query(database, "SELECT tablename FROM pg_tables WHERE schemaname='freediving' ORDER BY tablename", port)
    for table in rows.splitlines():
        if not re.fullmatch(r'[a-z_][a-z0-9_]*', table):
            raise ValueError('Unexpected canonical table name')
        if table == 'schema_migrations':
            continue
        count = int(query(database, 'SELECT count(*) FROM freediving.' + table, port))
        if count != (1 if table in ('publication_policy_events', 'evaluation_corpus') else 0):
            return False
        if table == 'evaluation_corpus' and query(
                database, 'SELECT mode FROM freediving.evaluation_corpus', port) != 'real':
            return False
        if table == 'publication_policy_events' and query(
                database, 'SELECT policy_version FROM freediving.publication_policy_events', port
        ) != 'extraction-publication/1':
            return False
    return True


def private_acl(database, port):
    return query('postgres', "SELECT EXISTS(SELECT 1 FROM pg_database d, "
                 "LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a "
                 "WHERE d.datname='" + database + "' AND a.grantee=0 "
                 "AND a.privilege_type IN ('CONNECT','TEMPORARY'))", port) == 'f'


def readback_empty(database, port):
    return (target_empty(database, port)
            and query(database, "SELECT count(*) FROM freediving.canonical_identity_view", port) == '0')


def canonical_target_state(release, url, database, port, snapshot, private_dir):
    env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
    env.update(FREEDIVING_REVIEW_URL=url, FREEDIVING_APP_URL=url,
               PGDATABASE=database, PGUSER='freediving_migrator',
               PGHOST='127.0.0.1', PGPORT=port)
    with tempfile.TemporaryDirectory(prefix='.target-state-', dir=private_dir) as directory:
        path = Path(directory) / 'target.json'
        run(['java', '-Xmx256m', '-cp', 'src:resources:lib/*', 'clojure.main',
             '-m', 'freediving.retained-aida-apply', 'target-state', snapshot, str(path)],
            env=env, cwd=release)
        state = json.loads(path.read_text())
    if state != {'schema': 'retained-aida-target-state/v1',
                 'snapshot_sha256': snapshot, 'revision': 0, 'events': [],
                 'source_rows': [], 'non_source_row_count': 0}:
        raise ValueError('Canonical target-state is not empty')


def restore_drill(backup, database, port, versions):
    """Restore the pre-migration archive into a disposable database."""
    from migrate_only import postgres_restore
    import uuid
    disposable = 'freediving_canonical_drill_' + uuid.uuid4().hex
    created = False
    try:
        postgres('createdb', '-h', '/var/run/postgresql', '-p', port, '-T', 'template0', disposable)
        created = True
        postgres_restore(backup, '--exit-on-error', '-h', '/var/run/postgresql',
                         '-p', port, '-d', disposable)
        if target_versions(disposable, port) != versions or not target_empty(disposable, port):
            raise ValueError('Canonical backup restore differs')
    finally:
        if created:
            postgres('dropdb', '-h', '/var/run/postgresql', '-p', port, disposable)


def intent_path(backup_dir):
    return backup_dir / 'canonical-intent.json'


def ensure_intent(backup_dir, public_database, port, revision, public_digests):
    path = intent_path(backup_dir)
    intent = {'schema': MARKER, 'target': NAME, 'public_database': public_database,
              'port': port, 'release_revision': revision, 'public_config_sha256': public_digests}
    if path.exists():
        old = json.loads(path.read_text())
        if (path.is_symlink() or path.stat().st_mode & 0o077
                or not re.fullmatch(r'[a-f0-9]{40}', old.get('release_revision', ''))
                or {key: value for key, value in old.items() if key != 'release_revision'}
                != {key: value for key, value in intent.items() if key != 'release_revision'}):
            raise ValueError('Canonical provisioning intent changed')
    else:
        with path.open('x') as output:
            os.chmod(path, 0o600)
            json.dump(intent, output, sort_keys=True)
            output.flush()
            os.fsync(output.fileno())
    return path


def provision(args):
    if os.geteuid() != 0:
        raise ValueError('Root required')
    release = args.release.resolve(strict=True)
    if not re.fullmatch(r'[a-f0-9]{40}', args.expected_revision):
        raise ValueError('Exact commit SHA required')
    if release != RELEASES_ROOT / args.expected_revision or (release / 'REVISION').read_text().strip() != args.expected_revision:
        raise ValueError('Pinned release required')
    if MIGRATION_DIR.resolve() != (release / 'resources' / 'migrations').resolve():
        raise ValueError('Run helper from pinned migration release')
    if not re.fullmatch(r'[a-f0-9]{64}', args.snapshot_sha256):
        raise ValueError('Exact source snapshot SHA256 required')
    if args.database != NAME or args.expected_public_database == NAME:
        raise ValueError('Unexpected canonical target')
    expected = expected_migrations()
    port, public_db, public_url = public_config(args.config_dir, args.expected_public_database)
    verify_roles(public_db, port)
    if version_state(public_db, port) != expected:
        raise ValueError('Public migration checksums changed')
    public_digests = {name: file_digest(args.config_dir / name) for name in ('migration.env', 'public.env')}
    public_before = public_counts(public_db, port)
    deployment_before = deployment_state(args.public_current, args.owner_current,
                                         args.owner_status, 'https://poc.alphacompose.com')
    target_preexists = database_exists(NAME, port)
    # A preexisting target without our root-private intent is never adopted.
    if target_preexists and not intent_path(args.backup_dir).exists():
        raise ValueError('Unmarked canonical database exists')
    ensure_private_dir(args.backup_dir)
    ensure_intent(args.backup_dir, public_db, port, args.expected_revision, public_digests)
    if not target_preexists:
        postgres('createdb', '-h', '/var/run/postgresql', '-p', port, '-T', 'template0',
                 '-O', 'freediving_migrator', NAME)
        # The intent permits recovery if interrupted between createdb and COMMENT.
        postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                 '-p', port, '-d', 'postgres', '-c',
                 "COMMENT ON DATABASE " + NAME + " IS '" + MARKER + "'")
        postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                 '-p', port, '-d', 'postgres', '-c',
                 'REVOKE ALL ON DATABASE ' + NAME + ' FROM PUBLIC')
    owner, marker = database_identity(NAME, port)
    if owner != 'freediving_migrator' or marker not in ('', MARKER):
        raise ValueError('Canonical database ownership or marker changed')
    if marker == '':
        if target_versions(NAME, port) or not target_empty(NAME, port):
            raise ValueError('Unmarked canonical database is not pristine')
        postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                 '-p', port, '-d', 'postgres', '-c',
                 "COMMENT ON DATABASE " + NAME + " IS '" + MARKER + "'")
        postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                 '-p', port, '-d', 'postgres', '-c',
                 'REVOKE ALL ON DATABASE ' + NAME + ' FROM PUBLIC')
    if not private_acl(NAME, port):
        if target_versions(NAME, port) or not target_empty(NAME, port):
            raise ValueError('Canonical database access is not private')
        postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                 '-p', port, '-d', 'postgres', '-c',
                 'REVOKE ALL ON DATABASE ' + NAME + ' FROM PUBLIC')
        if not private_acl(NAME, port):
            raise ValueError('Canonical database access is not private')
    if not target_empty(NAME, port):
        raise ValueError('Canonical database is not empty')
    before = target_versions(NAME, port)
    verify_target_versions(before, expected)
    backup = args.backup_dir / ('pre-canonical-' + args.expected_revision[:12] + '-' + str(len(list(args.backup_dir.glob('pre-canonical-*.dump')))) + '.dump')
    try:
        with backup.open('xb') as output:
            os.chmod(backup, 0o600)
            run(['runuser', '-u', 'postgres', '--', 'pg_dump', '-Fc', '-h',
                 '/var/run/postgresql', '-p', port, '-d', NAME], output=output)
    except Exception:
        backup.unlink(missing_ok=True)
        raise
    restore_drill(backup, NAME, port, before)
    if before != expected:
        env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
        env['PGDATABASE'] = NAME
        env['FREEDIVING_MIGRATION_URL'] = target_url(public_url, public_db, NAME)
        run(['java', '-Xmx256m', '-cp', 'src:resources:lib/*', 'clojure.main',
             '-m', 'freediving.deployment'], env=env, cwd=release)
    after = target_versions(NAME, port)
    verify_target_versions(after, expected)
    if after != expected or not readback_empty(NAME, port):
        raise ValueError('Canonical target migration or empty state incomplete')
    canonical_target_state(release, target_url(public_url, public_db, NAME), NAME, port,
                           args.snapshot_sha256, args.backup_dir)
    if version_state(public_db, port) != expected or public_counts(public_db, port) != public_before or any(
            file_digest(args.config_dir / name) != digest for name, digest in public_digests.items()) or \
            deployment_state(args.public_current, args.owner_current, args.owner_status,
                             'https://poc.alphacompose.com') != deployment_before:
        raise ValueError('Public database or configuration changed')
    print('Private canonical target ready; backup: ' + str(backup) + '; sha256: ' + file_digest(backup))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True, type=Path)
    parser.add_argument('--expected-revision', required=True)
    parser.add_argument('--database', required=True)
    parser.add_argument('--expected-public-database', required=True)
    parser.add_argument('--snapshot-sha256', required=True)
    parser.add_argument('--config-dir', type=Path, default=Path('/etc/freediving'))
    parser.add_argument('--backup-dir', type=Path, default=Path('/var/backups/freediving/canonical'))
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
