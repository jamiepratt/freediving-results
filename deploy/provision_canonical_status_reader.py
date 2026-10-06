#!/usr/bin/env python3
"""Explicit host checkpoint for a SELECT-only canonical status capability.

Run only after the exact private code/runtime and fresh owner/status guards pass.
Default prints effects. Credentials stay on the host in the existing private state.
"""
import argparse
import grp
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROLE = 'canonical_status_read'
TABLES = ('canonical_attempt_evidence', 'canonical_attempt_events', 'canonical_attempt_state',
          'source_identity_snapshot', 'source_identity_observations', 'athlete_identity_events',
          'canonical_identity_view', 'observations', 'extractions')
CONFIG = Path('/var/lib/freediving-owner-evidence/canonical-reader/config.json')


def grant_sql(password):
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', password):
        raise ValueError('invalid generated capability')
    return ("BEGIN; CREATE ROLE " + ROLE + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '"
            + password + "'; ALTER ROLE " + ROLE + " SET default_transaction_read_only=on; "
            "GRANT CONNECT ON DATABASE freediving_canonical TO " + ROLE + "; "
            "GRANT USAGE ON SCHEMA freediving TO " + ROLE + "; GRANT SELECT ON "
            + ','.join('freediving.' + table for table in TABLES) + ' TO ' + ROLE + '; COMMIT;')


def provision(args):
    if os.geteuid() != 0:
        raise ValueError('root host checkpoint required')
    sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
    if (not re.fullmatch(r'[0-9a-f]{64}', args.snapshot)
            or sha(args.runtime / 'manifest.json') != args.runtime_manifest_sha256
            or sha(args.export) != args.export_sha256
            or sha('/var/lib/freediving-owner-evidence/status/presentation-status.json') != args.status_sha256):
        raise ValueError('canonical status checkpoint changed')
    if CONFIG.exists() or CONFIG.is_symlink():
        raise ValueError('capability already exists; preserve it and verify before reuse')
    def pg(sql):
        result = subprocess.run(['sudo', '-n', '-u', 'postgres', 'psql', '-X', '-v',
                                 'ON_ERROR_STOP=1', '-d', 'freediving_canonical', '-At'],
                                input=sql, text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise ValueError('canonical capability preparation refused')
        return result.stdout.strip()
    if pg("SELECT count(*) FROM pg_roles WHERE rolname='" + ROLE + "';") != '0':
        raise ValueError('existing role requires independent capability verification')
    # This generated database password is written only to its intended host config.
    password = secrets.token_urlsafe(36)
    pg(grant_sql(password))
    config = {'database': 'freediving_canonical', 'snapshot_sha256': args.snapshot,
              'jdbc_url': 'jdbc:postgresql://127.0.0.1:5432/freediving_canonical?user='
                          + ROLE + '&password=' + password + '&connectTimeout=5&socketTimeout=10',
              'runtime_path': str(args.runtime), 'runtime_manifest_sha256': args.runtime_manifest_sha256,
              'exports': {'same_attempt': {'path': str(args.export), 'sha256': args.export_sha256}}}
    created = False
    try:
        CONFIG.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
        group = grp.getgrnam('freediving-evidence').gr_gid
        os.chown(CONFIG.parent, 0, group)
        fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
        created = True
        with os.fdopen(fd, 'w') as stream:
            json.dump(config, stream, sort_keys=True)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chown(CONFIG, 0, group)
        CONFIG.chmod(0o640)
    except Exception:
        if created:
            CONFIG.unlink()
        # This role was created above, has no owned objects and has never been
        # published to the origin. Undo its exact grants without touching data.
        pg('BEGIN; REVOKE SELECT ON ' + ','.join('freediving.' + table for table in TABLES)
           + ' FROM ' + ROLE + '; REVOKE USAGE ON SCHEMA freediving FROM ' + ROLE
           + '; REVOKE CONNECT ON DATABASE freediving_canonical FROM ' + ROLE
           + '; DROP ROLE ' + ROLE + '; COMMIT;')
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--runtime-manifest-sha256', required=True)
    parser.add_argument('--export', required=True, type=Path)
    parser.add_argument('--export-sha256', required=True)
    parser.add_argument('--snapshot', required=True)
    parser.add_argument('--status-sha256', required=True)
    args = parser.parse_args()
    try:
        if args.execute:
            provision(args)
        print(json.dumps({'executed': args.execute, 'database': 'freediving_canonical',
                          'role': ROLE, 'grants': ['SELECT freediving.' + table for table in TABLES],
                          'config': str(CONFIG)}))
    except Exception:
        print('Canonical status capability checkpoint refused', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
