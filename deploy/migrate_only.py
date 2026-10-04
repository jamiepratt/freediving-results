#!/usr/bin/env python3
"""Root-only, fail-closed migration checkpoint. Never activates a release."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import uuid

from prepare_database import endpoint, read_config

MIGRATION_DIR = Path(__file__).resolve().parents[1] / 'resources' / 'migrations'
PUBLIC_TABLES = ('extractions', 'observations', 'review_decisions',
                 'publication_decisions', 'publication_policy_events',
                 'public_projection_cache', 'public_results')
SOURCE_TABLES = ('source_identity_snapshot', 'source_identity_observations')
RELEASES_ROOT = Path('/opt/freediving/releases')
ROLES = ('freediving_migrator', 'observations_app', 'reviews_owner',
         'reviews_public', 'corrections_submit')


def run(argv, *, env=None, output=None, cwd=None, input_stream=None):
    if env is None:
        env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
    with open(os.devnull, 'wb') as null:
        return subprocess.run(argv, env=env, cwd=cwd,
                              stdin=input_stream if input_stream is not None else subprocess.DEVNULL,
                              stdout=output or subprocess.PIPE, stderr=null,
                              check=True).stdout


def postgres(*args):
    return run(['runuser', '-u', 'postgres', '--', *args])


def postgres_restore(backup, *args):
    with backup.open('rb') as stream:
        return run(['runuser', '-u', 'postgres', '--', 'pg_restore', *args],
                   input_stream=stream)


def query(database, sql, port):
    return postgres('psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql',
                    '-p', port, '-d', database, '-c', sql).decode().strip()


def expected_migrations():
    files = sorted(MIGRATION_DIR.glob('[0-9][0-9][0-9]-*.sql'))
    expected = {}
    for path in files:
        if path.name.endswith('.down.sql'):
            continue
        version = int(path.name[:3])
        if version in expected:
            raise ValueError('Duplicate migration version')
        data = path.read_bytes()
        # Version 6 predates the byte-hash convention: evaluation-labels/digest
        # hashes Clojure pr-str of the SQL string (JSON string form for this ASCII SQL).
        if version == 6:
            data = json.dumps(data.decode('utf-8'), ensure_ascii=False).encode('utf-8')
        expected[version] = hashlib.sha256(data).hexdigest()
    if set(expected) != set(range(1, 21)):
        raise ValueError('Release must contain exact migrations 1-20')
    return expected


def version_state(database, port):
    lines = query(database, 'SELECT version,sha256 FROM freediving.schema_migrations ORDER BY version', port)
    result = {}
    for line in lines.splitlines():
        version, digest = line.split('|', 1)
        result[int(version)] = digest
    return result


def verify_versions(actual, expected, allowed):
    if set(actual) not in allowed:
        raise ValueError('Unexpected migration set')
    if any(actual[version] != expected[version] for version in actual):
        raise ValueError('Migration checksum conflict')
    return max(actual)


def counts(database, port, tables):
    return tuple(int(query(database, f'SELECT count(*) FROM freediving.{table}', port))
                 for table in tables)


def file_digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def private_state(owner_current, owner_status):
    if not owner_current.is_symlink():
        raise ValueError('Private owner current must be a symlink')
    target = owner_current.resolve(strict=True)
    if not target.is_dir():
        raise ValueError('Private owner snapshot absent')
    return (str(target), file_digest(target / 'snapshot.sqlite'),
            file_digest(target / 'manifest.json'), file_digest(owner_status))


def deployment_state(public_current, owner_current, owner_status, public_origin):
    if not public_current.is_symlink():
        raise ValueError('Public current must be a symlink')
    public_target = str(public_current.resolve(strict=True))
    owner = private_state(owner_current, owner_status)
    service = run(['systemctl', 'is-active', 'freediving-public.service']).decode().strip()
    if service != 'active':
        raise ValueError('Public service inactive')
    health_request = urllib.request.Request(
        public_origin + '/', headers={'User-Agent': 'freediving-deploy-health/1.0'})
    with urllib.request.urlopen(health_request, timeout=10) as response:
        if response.status != 200:
            raise ValueError('Public site unhealthy')
    return public_target, owner


def deployment_config(config):
    migration = read_config(config / 'migration.env')
    public = read_config(config / 'public.env')
    if set(migration) != {'FREEDIVING_MIGRATION_URL'} or set(public) != {
            'FREEDIVING_PUBLIC_DATABASE_URL', 'FREEDIVING_SUBMIT_DATABASE_URL',
            'FREEDIVING_PUBLIC_ORIGIN', 'FREEDIVING_GATEWAY_SECRET'}:
        raise ValueError('Unexpected deployment configuration')
    port, database = endpoint(migration['FREEDIVING_MIGRATION_URL'], 'freediving_migrator')
    for key, role in (('FREEDIVING_PUBLIC_DATABASE_URL', 'reviews_public'),
                      ('FREEDIVING_SUBMIT_DATABASE_URL', 'corrections_submit')):
        if endpoint(public[key], role) != (port, database):
            raise ValueError('Inconsistent deployment configuration')
    if public['FREEDIVING_PUBLIC_ORIGIN'] != 'https://poc.alphacompose.com':
        raise ValueError('Unexpected public origin')
    return port, database, migration['FREEDIVING_MIGRATION_URL'], public['FREEDIVING_PUBLIC_ORIGIN']


def verify_roles(database, port):
    if query(database, 'SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()', port) != 'freediving_migrator':
        raise ValueError('Unexpected database owner')
    names = ','.join("'" + role + "'" for role in ROLES)
    actual = query(database, f'SELECT rolname,rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,rolbypassrls,rolinherit FROM pg_roles WHERE rolname IN ({names}) ORDER BY rolname', port)
    expected = '\n'.join('|'.join((role, 't' if role in ('freediving_migrator', 'reviews_public', 'corrections_submit') else 'f', 'f', 'f', 'f', 'f', 'f')) for role in sorted(ROLES))
    if actual != expected:
        raise ValueError('Unexpected database roles')


def restore_drill(backup, database, port, versions):
    disposable = 'freediving_migration_drill_' + uuid.uuid4().hex
    created = False
    try:
        postgres('createdb', '-h', '/var/run/postgresql', '-p', port, '-T', 'template0', disposable)
        created = True
        postgres_restore(backup, '--exit-on-error', '-h', '/var/run/postgresql', '-p', port,
                         '-d', disposable)
        if version_state(disposable, port) != versions:
            raise ValueError('Disposable restore schema differs from source')
        if counts(disposable, port, PUBLIC_TABLES) != counts(database, port, PUBLIC_TABLES):
            raise ValueError('Disposable restore public counts differ')
    finally:
        if created:
            postgres('dropdb', '-h', '/var/run/postgresql', '-p', port, disposable)


def apply_if_needed(current, migration_url, release):
    if current == 20:
        return
    env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
    env['FREEDIVING_MIGRATION_URL'] = migration_url
    run(['java', '-Xmx256m', '-cp', 'src:resources:lib/*', 'clojure.main',
         '-m', 'freediving.deployment'], env=env, cwd=release)


def ensure_private_dir(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink() or path.stat().st_uid != 0 or path.stat().st_mode & 0o077:
        raise ValueError('Backup directory must be root-private')


def migrate(args):
    if os.geteuid() != 0:
        raise ValueError('Root required')
    release = args.release.resolve(strict=True)
    if not re.fullmatch(r'[0-9a-f]{40}', args.expected_revision):
        raise ValueError('Exact commit SHA required')
    if release != RELEASES_ROOT / args.expected_revision:
        raise ValueError('Release path differs from commit SHA')
    if (release / 'REVISION').read_text().strip() != args.expected_revision:
        raise ValueError('Release revision mismatch')
    if MIGRATION_DIR.resolve() != (release / 'resources' / 'migrations').resolve():
        raise ValueError('Run this helper from the staged release')
    expected = expected_migrations()
    port, database, migration_url, public_origin = deployment_config(args.config_dir)
    verify_roles(database, port)
    before = version_state(database, port)
    current = verify_versions(before, expected, (set(range(1, 8)), set(range(1, 21))))
    if current == 20 and counts(database, port, SOURCE_TABLES) != (0, 0):
        raise ValueError('Source identity tables are not empty')
    public_before = counts(database, port, PUBLIC_TABLES)
    deployment_before = deployment_state(args.public_current, args.owner_current,
                                         args.owner_status, public_origin)
    ensure_private_dir(args.backup_dir)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    backup = args.backup_dir / f'pre-migration-{args.expected_revision[:12]}-{stamp}.dump'
    try:
        with backup.open('xb') as stream:
            os.chmod(backup, 0o600)
            run(['runuser', '-u', 'postgres', '--', 'pg_dump', '-Fc', '-h',
                 '/var/run/postgresql', '-p', port, '-d', database], output=stream)
    except Exception:
        backup.unlink(missing_ok=True)
        raise
    try:
        postgres_restore(backup, '--list')
        restore_drill(backup, database, port, before)
    except Exception:
        print('Dump retained for inspection: ' + str(backup), flush=True)
        raise
    print('Rollback checkpoint: ' + str(backup), flush=True)
    apply_if_needed(current, migration_url, release)
    verify_versions(version_state(database, port), expected, (set(range(1, 21)),))
    if counts(database, port, SOURCE_TABLES) != (0, 0):
        raise ValueError('Source identity tables changed')
    if counts(database, port, PUBLIC_TABLES) != public_before:
        raise ValueError('Public table counts changed')
    if deployment_state(args.public_current, args.owner_current,
                        args.owner_status, public_origin) != deployment_before:
        raise ValueError('Public or owner deployment state changed')
    print('Migration-only verified versions 1-20; public and owner checkpoints unchanged')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True, type=Path)
    parser.add_argument('--expected-revision', required=True)
    parser.add_argument('--config-dir', type=Path, default=Path('/etc/freediving'))
    parser.add_argument('--backup-dir', type=Path, default=Path('/var/backups/freediving'))
    parser.add_argument('--public-current', type=Path, default=Path('/opt/freediving/current'))
    parser.add_argument('--owner-current', type=Path, default=Path('/var/lib/freediving-owner-evidence/current'))
    parser.add_argument('--owner-status', type=Path, default=Path('/var/lib/freediving-owner-evidence/status/presentation-status.json'))
    args = parser.parse_args()
    os.umask(0o077)
    try:
        migrate(args)
    except Exception:
        print('Migration-only refused or failed; inspect private checkpoint before retry or restore.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
