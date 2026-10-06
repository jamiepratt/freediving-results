#!/usr/bin/env python3
"""Root-only migration 21 checkpoint for the retained private canonical database."""
import argparse
import datetime
import hashlib
import os
from pathlib import Path
import re
import sys
import uuid

from migrate_only import (MIGRATION_DIR, PUBLIC_TABLES, RELEASES_ROOT, counts, deployment_state,
                          ensure_private_dir, expected_migrations, file_digest,
                          postgres, postgres_restore, query, run, verify_roles,
                          version_state)
from prepare_database import endpoint
from provision_canonical import (MARKER, NAME, database_identity, private_acl,
                                 public_config, target_url)

TARGET = NAME
IMMUTABLE_COUNTS = ('athlete_identity_events', 'source_identity_observations',
                    'canonical_identity_view')


def data_digest(database, port):
    # PostgreSQL 17 otherwise generates a fresh psql restriction key per dump.
    # These dumps are hashed only, never executed as SQL.
    data = postgres('pg_dump', '--data-only', '--no-owner', '--no-acl',
                    '--restrict-key=' + '0' * 64,
                    '--exclude-table=freediving.schema_migrations', '-h',
                    '/var/run/postgresql', '-p', port, '-d', database)
    return hashlib.sha256(data).hexdigest()


def target_state(database, port):
    revision = query(database, 'SELECT coalesce(max(revision),0) FROM '
                     'freediving.canonical_attempt_state', port)
    return int(revision), counts(database, port, IMMUTABLE_COUNTS), data_digest(database, port)


def restore_drill(backup, source, port, versions, state):
    disposable = 'freediving_can21_drill_' + uuid.uuid4().hex
    created = False
    try:
        postgres('createdb', '-h', '/var/run/postgresql', '-p', port,
                 '-T', 'template0', '-O', 'freediving_migrator', disposable)
        created = True
        postgres_restore(backup, '--exit-on-error', '-h', '/var/run/postgresql',
                         '-p', port, '-d', disposable)
        if version_state(disposable, port) != versions or target_state(disposable, port) != state:
            raise ValueError('Canonical restore differs from source')
    finally:
        if created:
            postgres('dropdb', '-h', '/var/run/postgresql', '-p', port, disposable)


def apply_21(port, public_url, public_db, expected_digest):
    url = target_url(public_url, public_db, TARGET)
    if endpoint(url, 'freediving_migrator') != (port, TARGET):
        raise ValueError('Canonical migration URL changed')
    password = re.search(r'[?&]password=([^&]+)', url).group(1)
    env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
    env['PGPASSWORD'] = password
    sql = ('INSERT INTO freediving.schema_migrations(version,sha256) VALUES '
           f"(21,'{expected_digest}')")
    run(['psql', '-X', '-q', '-1', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1',
         '-p', port, '-U', 'freediving_migrator', '-d', TARGET,
         '-f', str(MIGRATION_DIR / '021-automatic-identity-invalidation.sql'),
         '-c', sql], env=env)


def migrate(args):
    if os.geteuid() != 0:
        raise ValueError('Root required')
    release = args.release.resolve(strict=True)
    if (not re.fullmatch(r'[a-f0-9]{40}', args.expected_revision)
            or release != RELEASES_ROOT / args.expected_revision
            or (release / 'REVISION').read_text().strip() != args.expected_revision
            or MIGRATION_DIR.resolve() != (release / 'resources' / 'migrations').resolve()):
        raise ValueError('Pinned release required')
    expected = expected_migrations()
    port, public_db, public_url = public_config(args.config_dir,
                                                  args.expected_public_database)
    verify_roles(public_db, port)
    if version_state(public_db, port) != expected:
        raise ValueError('Public migration state changed')
    if database_identity(TARGET, port) != ('freediving_migrator', MARKER) or not private_acl(TARGET, port):
        raise ValueError('Canonical owner, marker or private ACL changed')
    before = version_state(TARGET, port)
    if set(before) not in (set(range(1, 21)), set(range(1, 22))) or any(
            before[n] != expected[n] for n in before):
        raise ValueError('Canonical migration checksum or version changed')
    prior_state = target_state(TARGET, port)
    if prior_state[:2] != (args.expected_attempt_revision,
                           (args.expected_events, args.expected_source_rows,
                            args.expected_identity_rows)):
        raise ValueError('Canonical prior-state CAS failed')
    config_digests = tuple(file_digest(args.config_dir / name)
                           for name in ('migration.env', 'public.env'))
    public_state = (counts(public_db, port, PUBLIC_TABLES),
                    deployment_state(args.public_current, args.owner_current,
                                     args.owner_status, 'https://poc.alphacompose.com'))
    ensure_private_dir(args.backup_dir)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    backup = args.backup_dir / f'pre-canonical-21-{args.expected_revision[:12]}-{stamp}.dump'
    try:
        with backup.open('xb') as stream:
            os.chmod(backup, 0o600)
            run(['runuser', '-u', 'postgres', '--', 'pg_dump', '-Fc', '-h',
                 '/var/run/postgresql', '-p', port, '-d', TARGET], output=stream)
    except Exception:
        backup.unlink(missing_ok=True)
        raise
    postgres_restore(backup, '--list')
    restore_drill(backup, TARGET, port, before, prior_state)
    print('Canonical rollback checkpoint: ' + str(backup) + '; sha256: ' + file_digest(backup), flush=True)
    if 21 not in before:
        apply_21(port, public_url, public_db, expected[21])
    if version_state(TARGET, port) != expected or target_state(TARGET, port) != prior_state:
        raise ValueError('Canonical migration changed retained data or state')
    if (version_state(public_db, port) != expected
            or database_identity(TARGET, port) != ('freediving_migrator', MARKER)
            or not private_acl(TARGET, port)
            or tuple(file_digest(args.config_dir / name)
                     for name in ('migration.env', 'public.env')) != config_digests
            or (counts(public_db, port, PUBLIC_TABLES),
                deployment_state(args.public_current, args.owner_current,
                                 args.owner_status, 'https://poc.alphacompose.com')) != public_state):
        raise ValueError('Public or owner state changed')
    print('Canonical migration 21 verified; retained rows and public/owner state unchanged')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--expected-revision', required=True)
    parser.add_argument('--expected-public-database', required=True)
    parser.add_argument('--expected-attempt-revision', type=int, required=True)
    parser.add_argument('--expected-events', type=int, required=True)
    parser.add_argument('--expected-source-rows', type=int, required=True)
    parser.add_argument('--expected-identity-rows', type=int, required=True)
    parser.add_argument('--config-dir', type=Path, default=Path('/etc/freediving'))
    parser.add_argument('--backup-dir', type=Path, default=Path('/var/backups/freediving'))
    parser.add_argument('--public-current', type=Path, default=Path('/opt/freediving/current'))
    parser.add_argument('--owner-current', type=Path, default=Path('/var/lib/freediving-owner-evidence/current'))
    parser.add_argument('--owner-status', type=Path, default=Path('/var/lib/freediving-owner-evidence/status/presentation-status.json'))
    os.umask(0o077)
    try:
        migrate(parser.parse_args())
    except Exception:
        print('Canonical migration refused or failed; inspect private checkpoint before retry or restore.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
