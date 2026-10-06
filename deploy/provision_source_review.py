#!/usr/bin/env python3
"""Guarded owner source-review capability: exact reads and genuine receipt INSERT only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import grp
import sys
import provision_sporting_proof_reader as reader

ROLE = 'sporting_source_review'
DATABASE = reader.DATABASE
TABLES = reader.TABLES
RECEIPTS = ('extraction_reviews', 'pdf_extraction_reviews')
CONFIG = Path('/var/lib/freediving-owner-evidence/source-review/config.json')
digest = reader.digest
unlinked = reader.unlinked
pg = reader.pg


def grant_sql(password, database=DATABASE):
    sql = reader.grant_sql(password, database).replace(reader.ROLE, ROLE)
    return sql.replace('default_transaction_read_only=on', 'default_transaction_read_only=off').replace(
        '; COMMIT;', '; GRANT INSERT ON ' + ','.join('freediving.' + t for t in RECEIPTS)
        + ' TO ' + ROLE + '; COMMIT;')


def grant_report_sql():
    return reader.grant_report_sql().replace(reader.ROLE, ROLE)


def validate_grants(report):
    expected = {'freediving.' + t: ['SELECT', 'INSERT'] if t in RECEIPTS else ['SELECT'] for t in TABLES}
    if report.get('role') != ROLE or report.get('readonly') is not False or report.get('tables') != expected:
        raise ValueError('source review capability grants changed')
    normalized = {**report, 'role': reader.ROLE, 'readonly': True,
                  'tables': {'freediving.' + t: ['SELECT'] for t in TABLES}}
    reader.validate_grants(normalized)
    return report


def verify_grants(database=DATABASE, query=None):
    return validate_grants(json.loads((query or (lambda sql: pg(sql, database)))(grant_report_sql())))


def verify_config(path=CONFIG):
    path = unlinked(path, regular=True)
    info = path.stat()
    if info.st_uid not in (0, os.geteuid()) or info.st_mode & 0o027:
        raise ValueError('unsafe source review configuration')
    value = json.loads(path.read_text())
    if set(value) != {'jdbc_url', 'database', 'runtime_path', 'runtime_manifest_sha256'}:
        raise ValueError('invalid source review configuration')
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', value['database']):
        raise ValueError('invalid source review database')
    if not re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:5432/' + value['database']
                       + r'\?user=' + ROLE + r'&password=[A-Za-z0-9_-]{32,128}&connectTimeout=5&socketTimeout=10', value['jdbc_url']):
        raise ValueError('invalid source review connection')
    runtime = Path(value['runtime_path'])
    if not runtime.is_absolute() or digest(runtime / 'manifest.json') != value['runtime_manifest_sha256']:
        raise ValueError('source review runtime changed')
    return value


def _inputs(args):
    from comparison_activate import _tree
    if os.geteuid() != 0 or not args.runtime.is_absolute():
        raise ValueError('root and absolute staged source review runtime required')
    for path, pin in ((args.runtime / 'manifest.json', args.runtime_manifest_sha256),
                      (args.app_manifest, args.app_manifest_sha256), (args.guard, args.guard_sha256)):
        if not isinstance(pin, str) or not re.fullmatch('[0-9a-f]{64}', pin) or digest(path) != pin:
            raise ValueError('source review input pin changed')
    runtime = json.loads((args.runtime / 'manifest.json').read_text())
    app = json.loads(args.app_manifest.read_text())
    if (not re.fullmatch('[0-9a-f]{40}', runtime['candidate']) or runtime['candidate'] != app['candidate']
            or 'src/freediving/source_accuracy_review.clj' not in runtime['files']
            or _tree(args.runtime) != {**runtime['files'], 'manifest.json': args.runtime_manifest_sha256}):
        raise ValueError('source review runtime differs from private code')
    return json.loads(args.guard.read_text())


def provision(args):
    from comparison_activate import capture_guard
    from owner_evidence_activate import Layout
    expected = _inputs(args)
    layout = Layout(Path('/opt/freediving/owner-evidence/app'), Path('/var/lib/freediving-owner-evidence'),
                    Path('/etc/systemd/system'), Path('/etc/freediving/owner-evidence.env'))
    if expected.get('schema') != 'private-comparison-activation-guard/v1' or capture_guard(layout, args.public_database) != expected:
        raise ValueError('live authority guard changed before source review provisioning')
    proof = expected['protected'].get('sporting_proof')
    if (proof is None or proof['runtime']['path'] != str(args.runtime)
            or proof['runtime']['candidate'] != json.loads(args.app_manifest.read_text())['candidate']):
        raise ValueError('source reviewer requires exact current shared proof runtime')
    if CONFIG.exists() or CONFIG.is_symlink():
        value = verify_config(CONFIG)
        if (value['database'], value['runtime_path'], value['runtime_manifest_sha256']) != (
                args.database, str(args.runtime), args.runtime_manifest_sha256):
            raise ValueError('existing source review configuration differs')
        verify_grants(args.database)
        return False
    if pg("SELECT count(*) FROM pg_roles WHERE rolname='" + ROLE + "'", args.database) != '0':
        raise ValueError('unconfigured source review role exists')
    password = secrets.token_urlsafe(36)
    pg(grant_sql(password, args.database), args.database)
    created = False
    try:
        verify_grants(args.database)
        if capture_guard(layout, args.public_database) != expected:
            raise ValueError('live authority changed during source review provisioning')
        group = grp.getgrnam('freediving-evidence').gr_gid
        unlinked(CONFIG)
        CONFIG.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
        CONFIG.parent.chmod(0o750); os.chown(CONFIG.parent, 0, group)
        value = {'database': args.database, 'runtime_path': str(args.runtime),
                 'runtime_manifest_sha256': args.runtime_manifest_sha256,
                 'jdbc_url': 'jdbc:postgresql://127.0.0.1:5432/' + args.database + '?user=' + ROLE
                 + '&password=' + password + '&connectTimeout=5&socketTimeout=10'}
        fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
        created = True
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, sort_keys=True); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.chown(CONFIG, 0, group); CONFIG.chmod(0o640)
        verify_config(CONFIG); verify_grants(args.database)
        current = capture_guard(layout, args.public_database)
        if current['protected'].get('source_review') is None:
            raise ValueError('source reviewer guard missing after provision')
        current['protected']['source_review'] = expected['protected'].get('source_review')
        if current != expected:
            raise ValueError('live authority changed after source review provisioning')
        return True
    except BaseException:
        if created: CONFIG.unlink()
        pg('BEGIN; REVOKE SELECT ON ' + ','.join('freediving.' + t for t in TABLES) + ' FROM ' + ROLE
           + '; REVOKE INSERT ON ' + ','.join('freediving.' + t for t in RECEIPTS) + ' FROM ' + ROLE
           + '; REVOKE USAGE ON SCHEMA freediving FROM ' + ROLE + '; REVOKE CONNECT ON DATABASE '
           + args.database + ' FROM ' + ROLE + '; DROP ROLE ' + ROLE + '; COMMIT;', args.database)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true'); parser.add_argument('--verify', action='store_true')
    parser.add_argument('--database', default=DATABASE); parser.add_argument('--public-database', default=DATABASE)
    parser.add_argument('--runtime', type=Path); parser.add_argument('--runtime-manifest-sha256')
    parser.add_argument('--app-manifest', type=Path); parser.add_argument('--app-manifest-sha256')
    parser.add_argument('--guard', type=Path); parser.add_argument('--guard-sha256')
    args = parser.parse_args()
    try:
        if args.verify == args.execute: raise ValueError('explicit source review checkpoint action required')
        if args.verify:
            value = verify_config(); verify_grants(value['database']); changed = False
        else:
            if not all((args.runtime, args.app_manifest, args.guard)): raise ValueError('exact source review checkpoint inputs required')
            changed = provision(args)
        print(json.dumps({'role': ROLE, 'verified': True, 'changed': changed, 'receipt_writes': 0}))
        return 0
    except Exception:
        print('Source review capability checkpoint refused', file=sys.stderr)
        return 1

if __name__ == '__main__': sys.exit(main())
