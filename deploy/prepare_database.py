#!/usr/bin/env python3
"""Back up the configured local database, then migrate without exposing credentials."""
import argparse
import datetime
import os
from pathlib import Path
import re
import subprocess
import sys


def read_config(path):
    values = {}
    with path.open() as source:
        text = source.read(16385)
    if len(text) > 16384:
        raise ValueError('Oversized deployment configuration')
    for line in text.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([A-Z_]+)='([^'\r\n]*)'", line)
        if not match or match[1] in values:
            raise ValueError('Invalid deployment configuration')
        values[match[1]] = match[2]
    return values


def endpoint(url, role):
    # Deliberately support only the format emitted by bootstrap.py. JDBC query
    # overrides and URL escapes can alter connection semantics, so refuse them.
    match = re.fullmatch(
        r'jdbc:postgresql://127\.0\.0\.1:([1-9][0-9]{0,4})/'
        r'([A-Za-z_][A-Za-z0-9_]{0,62})\?([^\s]+)', url)
    if not match or int(match[1]) > 65535:
        raise ValueError('Invalid database endpoint')
    query = {}
    for item in match[3].split('&'):
        key, value = item.split('=', 1)
        if key in query:
            raise ValueError('Duplicate database option')
        query[key] = value
    if (set(query) != {'user', 'password', 'connectTimeout', 'socketTimeout'}
            or query['user'] != role
            or not re.fullmatch(r'[A-Za-z0-9_.~-]{1,256}', query['password'])
            or any(not re.fullmatch(r'[1-9][0-9]{0,2}', query[key])
                   for key in ('connectTimeout', 'socketTimeout'))):
        raise ValueError('Unsupported database options')
    return match[1], match[2]


def prepare(config, backup_dir, backup_only):
    migration_values = read_config(config / 'migration.env')
    public = read_config(config / 'public.env')
    if (set(migration_values) != {'FREEDIVING_MIGRATION_URL'}
            or set(public) != {'FREEDIVING_PUBLIC_DATABASE_URL',
                              'FREEDIVING_SUBMIT_DATABASE_URL',
                              'FREEDIVING_PUBLIC_ORIGIN', 'FREEDIVING_GATEWAY_SECRET'}):
        raise ValueError('Unexpected deployment configuration')
    migration = migration_values['FREEDIVING_MIGRATION_URL']
    port, database = endpoint(migration, 'freediving_migrator')
    for key, role in [('FREEDIVING_PUBLIC_DATABASE_URL', 'reviews_public'),
                      ('FREEDIVING_SUBMIT_DATABASE_URL', 'corrections_submit')]:
        if endpoint(public[key], role) != (port, database):
            raise ValueError('Inconsistent deployment configuration')
    backup_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    backup = backup_dir / f'pre-deploy-{stamp}.dump'
    env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
    created = False
    try:
        with backup.open('xb') as output:
            created = True
            os.chmod(backup, 0o600)
            subprocess.run(['runuser', '-u', 'postgres', '--', 'pg_dump', '-Fc',
                            '-h', '/var/run/postgresql', '-p', port, '-d', database], stdout=output,
                           stderr=subprocess.DEVNULL, env=env, check=True)
    except Exception:
        if created:
            backup.unlink(missing_ok=True)
        raise
    if not backup_only:
        env['FREEDIVING_MIGRATION_URL'] = migration
        subprocess.run(['java', '-Xmx256m', '-cp', 'src:resources:lib/*',
                        'clojure.main', '-m', 'freediving.deployment'], env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config-dir', type=Path, default=Path('/etc/freediving'))
    parser.add_argument('--backup-dir', type=Path, default=Path('/var/backups/freediving'))
    parser.add_argument('--backup-only', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        prepare(args.config_dir, args.backup_dir, args.backup_only)
    except Exception:
        print('Database preparation failed; activation refused. Inspect private configuration and database availability.', file=sys.stderr)
        return 1
    print('Configured database backup complete' if args.backup_only else 'Configured database backup and migration complete')
    return 0


if __name__ == '__main__':
    sys.exit(main())
