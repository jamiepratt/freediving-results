#!/usr/bin/env python3
"""Guarded private retained AIDA apply. Never publishes a public route."""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    from scripts import retained_aida_activation_checkpoint as activation
    from scripts.retained_aida_guarded_apply import build_guarded_cohort, verify_target_prefix
except ModuleNotFoundError:
    import retained_aida_activation_checkpoint as activation
    from retained_aida_guarded_apply import build_guarded_cohort, verify_target_prefix


SHA = re.compile(r'[0-9a-f]{64}\Z')
REPO = Path(__file__).resolve().parents[1]


def need(ok, reason):
    if not ok:
        raise ValueError(reason)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def private_directory(path):
    return activation.private_dir(path)


def receipt(directory, name, value):
    activation.atomic_json(directory / name, value)


def run_manual_apply(stores, preflight, envelope, pins, directory):
    """Apply one pinned package. Store methods are the host boundary for integration tests."""
    directory = private_directory(directory)
    stores.verify_active()
    need(all(isinstance(value, str) and SHA.fullmatch(value) for value in pins.values())
         and {'preflight_sha256', 'envelope_sha256', 'flow_sha256',
              'checkpoint_sha256'} <= set(pins), 'hash pins missing')
    target = stores.target_state()
    cohort = build_guarded_cohort(preflight, target)
    expected_owner = preflight.get('owner', {})
    owner = stores.owner_state()
    proposals = envelope.get('proposals')
    need(isinstance(proposals, list) and proposals
         and len({p.get('id') for p in proposals}) == len(proposals)
         and all(p.get('status') == 'pending' for p in proposals),
         'pending owner envelope changed')
    need(envelope.get('snapshot_sha256', preflight['snapshot_sha256'])
         == preflight['snapshot_sha256'], 'owner snapshot changed')
    expected_revision = expected_owner.get('store_revision', owner['store_revision'])
    expected_binding = expected_owner.get('binding_revision', owner['binding_revision'])
    count = len(proposals)
    stores.verify_status_application_expectation(len(cohort['events']),
                                                 expected_revision + count, count)
    need(owner['snapshot_sha256'] == preflight['snapshot_sha256']
         and owner['binding_revision'] == expected_binding
         and ((owner['store_revision'] == expected_revision
               and owner['human_event_count'] == 0
               and owner['proposal_count'] == 0)
              or (owner['store_revision'] == expected_revision + count
                  and owner['human_event_count'] == count
                  and owner['proposal_count'] == count
                  and stores.verify_owner(envelope))),
         'owner revision, human action, or proposal state changed')
    stores.verify_status_retry_state(target, owner, cohort,
                                     expected_revision + count, count)
    phase = {'schema': 'retained-aida-manual-apply-phase/v1', 'pins': pins,
             'snapshot_sha256': preflight['snapshot_sha256']}
    receipt(directory, 'phase-inputs.json', phase | {'phase': 'inputs_verified'})
    backup_name = stores.backup_postgres(directory)
    need(isinstance(backup_name, str) and '/' not in backup_name and
         stores.verify_postgres_backup(directory, backup_name),
         'PostgreSQL backup missing or changed')
    backup_sha = digest(directory / backup_name)
    receipt(directory, 'phase-pg-backup.json', phase | {'phase': 'pg_backup_verified',
             'backup_sha256': backup_sha})
    need(stores.restore_drill(directory, backup_name), 'PostgreSQL restore drill failed')
    receipt(directory, 'phase-pg-restore.json', phase | {'phase': 'pg_restore_verified',
             'backup_sha256': backup_sha})
    need(stores.target_state() == target and stores.owner_state() == owner,
         'target or owner changed after backup')
    if target['revision'] != len(cohort['events']) or not target['source_rows']:
        stores.verify_active()
        stores.apply_canonical(cohort)
    final_target = stores.target_state()
    need(verify_target_prefix(final_target, preflight['source_rows'],
                              preflight['canonical_replay']) == len(cohort['events'])
         and final_target['source_rows'], 'canonical replay incomplete')
    receipt(directory, 'phase-canonical.json', phase | {'phase': 'canonical_verified',
             'revision': final_target['revision']})
    if owner['proposal_count'] == 0:
        stores.verify_active()
        stores.register_owner(envelope)
    final_owner = stores.owner_state()
    need(final_owner['snapshot_sha256'] == preflight['snapshot_sha256']
         and final_owner['binding_revision'] == expected_binding
         and final_owner['store_revision'] == expected_revision + count
         and final_owner['proposal_count'] == count
         and final_owner['human_event_count'] == count
         and stores.verify_owner(envelope), 'owner batch readback changed')
    receipt(directory, 'phase-owner.json', phase | {'phase': 'pending_owner_verified',
             'owner_revision': final_owner['store_revision'], 'pending_count': count})
    need(stores.target_state() == final_target and stores.owner_state() == final_owner,
         'target or owner changed before status')
    stores.verify_active()
    remote_status = stores.commit_status(final_target, final_owner, envelope)
    need(isinstance(remote_status, dict), 'private status commit missing')
    result = phase | {'status': 'complete', 'canonical_revision': final_target['revision'],
                      'owner_revision': final_owner['store_revision'],
                      'pending_count': count, 'publication': 'blocked',
                      'private_status_revision': remote_status.get('revision')}
    receipt(directory, 'status.json', result)
    return result


class HostStores:
    def __init__(self, owner_db, snapshot_dir, recovered, snapshot, directory, run_dir,
                 unresolved_exclusions, isolated_rehearsal=False, bundle_sha256=None,
                 active_binding_path=None, active_binding_sha256=None,
                 status_from_current=False):
        self.owner_db = Path(owner_db)
        self.snapshot_dir = Path(snapshot_dir)
        self.recovered = recovered
        self.snapshot = snapshot
        self.directory = directory
        self.run_dir = Path(run_dir) if run_dir is not None else None
        self.unresolved_exclusions = unresolved_exclusions
        self.isolated_rehearsal = isolated_rehearsal
        self.bundle_sha256 = bundle_sha256
        self.active_binding_path = active_binding_path
        self.active_binding_sha256 = active_binding_sha256
        self.status_from_current = status_from_current
        self.status_pin = None

    def verify_active(self):
        need(isinstance(self.bundle_sha256, str) and SHA.fullmatch(self.bundle_sha256),
             'active bundle SHA256 required')
        if self.isolated_rehearsal:
            active = activation.checked_json(self.active_binding_path,
                                             self.active_binding_sha256)
        else:
            sys.path.insert(0, str(REPO / 'scripts'))
            from private_status_sync import (STATUS_URL, _NoRedirect, _request,
                                             _active_provenance, assert_status_pin)
            from urllib.request import build_opener
            credentials = [os.getenv('CF_ACCESS_CLIENT_ID'),
                           os.getenv('CF_ACCESS_CLIENT_SECRET'),
                           os.getenv('OWNER_EVIDENCE_STATUS_TOKEN')]
            need(all(credentials), 'private active status credentials missing')
            current = _request(build_opener(_NoRedirect()), 'GET', STATUS_URL,
                               *credentials)
            if self.status_from_current:
                if self.status_pin is None:
                    self.status_pin = _active_provenance(current, self.snapshot,
                                                          self.bundle_sha256)
                else:
                    assert_status_pin(self.status_pin, current)
            active = current.get('remote', {}).get('active')
        need(isinstance(active, dict)
             and active.get('snapshot_sha256') == self.snapshot
             and active.get('bundle_manifest_sha256') == self.bundle_sha256,
             'active snapshot or bundle binding changed')

    def verify_status_application_expectation(self, canonical_revision,
                                              owner_revision, pending_count):
        if not self.status_from_current or self.status_pin['schema'] != 'private-presentation-status/v3':
            return
        application = self.status_pin.get('application', {})
        expected = {'snapshot_sha256': self.snapshot,
                    'canonical_revision': canonical_revision,
                    'owner_store_revision': owner_revision,
                    'pending_proposals': pending_count,
                    'unresolved_exclusions': self.unresolved_exclusions,
                    'provider_calls_recorded': 0,
                    'publication_status': 'private'}
        need(all(application.get(key) == value for key, value in expected.items()),
             'existing private application differs')

    def verify_status_retry_state(self, target, owner, cohort,
                                  owner_revision, pending_count):
        if not self.status_from_current or self.status_pin['schema'] != 'private-presentation-status/v3':
            return
        need(target['revision'] == len(cohort['events'])
             and target['source_rows'] == cohort['registration']['rows']
             and owner['store_revision'] == owner_revision
             and owner['proposal_count'] == pending_count
             and owner['human_event_count'] == pending_count,
             'existing private application requires completed stores')

    def _clojure(self, command, *args):
        need(os.getenv('FREEDIVING_REVIEW_URL'), 'FREEDIVING_REVIEW_URL required')
        completed = subprocess.run(['clojure', '-M', '-m', 'freediving.retained-aida-apply',
                                    command, *map(str, args)], cwd=REPO,
                                   capture_output=True, text=True, timeout=3600)
        if completed.returncode:
            raise RuntimeError('canonical ' + command + ' failed')

    def target_state(self):
        with tempfile.TemporaryDirectory(dir=self.directory) as temporary:
            path = Path(temporary) / 'target.json'
            self._clojure('target-state', self.snapshot, path)
            state = json.loads(path.read_text())
            if not hasattr(self, 'initial_target'):
                self.initial_target = state
            return state

    def canonical_readback_digest(self, expected):
        with tempfile.TemporaryDirectory(dir=self.directory) as temporary:
            path = Path(temporary) / 'target.json'
            self._clojure('target-state', self.snapshot, path)
            need(json.loads(path.read_text()) == expected, 'canonical readback changed')
            return digest(path)

    def owner_state(self):
        db = activation.open_owner(self.owner_db)
        try:
            return activation.owner_state(db)
        finally:
            db.close()

    def backup_postgres(self, directory):
        jdbc = os.getenv('FREEDIVING_REVIEW_URL', '')
        match = re.fullmatch(r'jdbc:postgresql://([^/?#]+)/([^?]+)(?:\?.*)?', jdbc)
        host_port = match.group(1).split(':', 1) if match else []
        need(match is not None and os.getenv('PGDATABASE') == match.group(2)
             and os.getenv('PGUSER') and os.getenv('PGHOST') == host_port[0]
             and os.getenv('PGPORT', '5432') == (host_port[1] if len(host_port) == 2 else '5432')
             and not any(os.getenv(name) for name in ('PGSERVICE', 'PGDATABASE_OVERRIDE')),
             'PostgreSQL backup target must match canonical target')
        path = directory / 'postgres.dump'
        if path.exists():
            marker = directory / 'pg-backup.json'
            need(marker.is_file() and not marker.is_symlink(),
                 'PostgreSQL backup identity receipt missing')
            expected = {'backup_sha256': digest(path),
                        'target': hashlib.sha256((match.group(1) + '/' + match.group(2)
                                                  + '/' + os.environ['PGUSER']).encode()).hexdigest()}
            recorded = json.loads(marker.read_text())
            need(all(recorded.get(key) == value for key, value in expected.items())
                 and isinstance(recorded.get('target_state_sha256'), str)
                 and SHA.fullmatch(recorded['target_state_sha256']),
                 'PostgreSQL backup target or bytes changed')
            return path.name
        fd, temporary = tempfile.mkstemp(prefix='.postgres-', dir=directory)
        os.close(fd)
        try:
            completed = subprocess.run(['pg_dump', '--format=custom', '--file', temporary],
                                       capture_output=True, timeout=3600)
            need(completed.returncode == 0, 'PostgreSQL backup failed')
            os.chmod(temporary, 0o600)
            os.replace(temporary, path)
            receipt(directory, 'pg-backup.json',
                    {'backup_sha256': digest(path),
                     'target': hashlib.sha256((match.group(1) + '/' + match.group(2)
                                               + '/' + os.environ['PGUSER']).encode()).hexdigest(),
                     'target_state_sha256': hashlib.sha256(json.dumps(
                         self.initial_target, sort_keys=True).encode()).hexdigest()})
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        return path.name

    def verify_postgres_backup(self, directory, name):
        path = directory / name
        if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
            return False
        return subprocess.run(['pg_restore', '--list', str(path)], capture_output=True,
                              timeout=120).returncode == 0

    def restore_drill(self, directory, name):
        # A disposable database must be supplied by the operator. This command
        # never creates or drops a production database.
        drill = os.getenv('FREEDIVING_PG_DRILL_DATABASE')
        need(drill and re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]{0,62}', drill)
             and drill != os.getenv('PGDATABASE'), 'disposable PG drill database required')
        marker = directory / 'pg-restore-drill.json'
        backup_sha = digest(directory / name)
        expected = {'backup_sha256': backup_sha, 'database': drill,
                    'target_state_sha256': None, 'restored': True}
        jdbc = os.environ['FREEDIVING_REVIEW_URL']
        target_database = os.environ['PGDATABASE']
        drill_jdbc = jdbc.replace('/' + target_database, '/' + drill, 1)
        need(drill_jdbc != jdbc, 'drill JDBC URL mismatch')

        def readback():
            env = os.environ.copy()
            env['FREEDIVING_REVIEW_URL'] = drill_jdbc
            with tempfile.TemporaryDirectory(dir=directory) as temporary:
                output = Path(temporary) / 'drill-target.json'
                completed = subprocess.run(
                    ['clojure', '-M', '-m', 'freediving.retained-aida-apply',
                     'target-state', self.snapshot, str(output)],
                    cwd=REPO, capture_output=True, env=env, timeout=3600)
                need(completed.returncode == 0, 'restored PostgreSQL target unreadable')
                return json.loads(output.read_text())

        if marker.exists():
            value = json.loads(marker.read_text())
            expected['target_state_sha256'] = value.get('target_state_sha256')
            need(value == expected and value['target_state_sha256'] ==
                 hashlib.sha256(json.dumps(readback(), sort_keys=True).encode()).hexdigest()
                 and value['target_state_sha256'] == json.loads(
                     (directory / 'pg-backup.json').read_text())['target_state_sha256'],
                 'PostgreSQL restore drill receipt changed')
            return True
        env = os.environ.copy()
        env['PGDATABASE'] = drill
        completed = subprocess.run(['pg_restore', '--exit-on-error', '--no-owner',
                                    '--dbname', drill, str(directory / name)],
                                   capture_output=True, env=env, timeout=3600)
        need(completed.returncode == 0, 'PostgreSQL restore drill failed')
        expected['target_state_sha256'] = hashlib.sha256(
            json.dumps(readback(), sort_keys=True).encode()).hexdigest()
        backup_marker = json.loads((directory / 'pg-backup.json').read_text())
        need(expected['target_state_sha256'] == backup_marker['target_state_sha256'],
             'restored PostgreSQL target differs from backup checkpoint')
        receipt(directory, marker.name, expected)
        return True

    def apply_canonical(self, cohort):
        path = self.directory / 'cohort.json'
        receipt(self.directory, path.name, cohort)
        output = self.directory / 'canonical-apply-receipt.json'
        self._clojure('apply', path, digest(path), output)

    def register_owner(self, envelope):
        sys.path.insert(0, str(REPO / 'scripts'))
        from owner_decision_export_adapter import register_verified_export
        from owner_decision_store import DecisionStore
        store = DecisionStore(self.owner_db)
        try:
            register_verified_export(store, self.snapshot_dir, envelope,
                                     recovered_packet_paths=self.recovered)
        finally:
            store.close()

    def verify_owner(self, envelope):
        db = activation.open_owner(self.owner_db)
        try:
            db.row_factory = sqlite3.Row
            rows = db.execute('SELECT id,payload_json FROM proposals').fetchall()
            expected = {p['id']: p for p in envelope['proposals']}
            return (len(rows) == len(expected)
                    and all(row['id'] in expected
                            and json.loads(row['payload_json']) == expected[row['id']]
                            for row in rows)
                    and db.execute("SELECT COUNT(*) FROM events WHERE action!='register'").fetchone()[0] == 0)
        finally:
            db.close()

    def commit_status(self, target, owner, envelope):
        if self.isolated_rehearsal:
            return {'revision': None, 'status': 'isolated_rehearsal'}
        sys.path.insert(0, str(REPO / 'scripts'))
        from private_status_sync import sync_application_status, sync_application_from_pin
        application = {'snapshot_sha256': self.snapshot,
                       'canonical_revision': target['revision'],
                       'canonical_readback_sha256': self.canonical_readback_digest(target),
                       'owner_store_revision': owner['store_revision'],
                       'pending_proposals': len(envelope['proposals']),
                       'unresolved_exclusions': self.unresolved_exclusions,
                       'provider_calls_recorded': 0,
                       'publication_status': 'private'}
        credentials = [os.getenv('CF_ACCESS_CLIENT_ID'),
                       os.getenv('CF_ACCESS_CLIENT_SECRET'),
                       os.getenv('OWNER_EVIDENCE_STATUS_TOKEN')]
        need(all(credentials), 'private status credentials missing')
        if self.status_from_current:
            return sync_application_from_pin(self.status_pin, application, *credentials)
        return sync_application_status(self.run_dir, application, *credentials)


def rebind_verified_owner(args):
    """First live mutation: CAS rebind frozen refs after both backup drills."""
    directory = private_directory(args.phase_dir)
    need(isinstance(args.snapshot_sha256, str) and SHA.fullmatch(args.snapshot_sha256),
         'snapshot SHA256 required')
    recovered = {}
    for item in args.recovered_packet:
        name, sep, path = item.partition('=')
        need(sep and name and path and name not in recovered, 'invalid recovered packet')
        recovered[name] = Path(path)
    sys.path.insert(0, str(REPO / 'scripts'))
    from unified_evidence_query import SnapshotQuery
    from owner_decision_store import DecisionStore
    with SnapshotQuery(args.snapshot_dir) as snapshot:
        need(snapshot.manifest['snapshot_sha256'] == args.snapshot_sha256,
             'frozen snapshot changed')
        names = sorted(name for name, item in snapshot.manifest.get('inputs', {}).items()
                       if item.get('source_schema') == 'aida-selected-html-packet/v1')
    need(names and set(recovered) <= set(names), 'recovered packet source changed')
    loaded = activation.load_source_observations(args.snapshot_dir, names,
                                                 recovered_packet_paths=recovered)
    need(not loaded.get('gaps') and loaded['snapshot_sha256'] == args.snapshot_sha256,
         'verified recovered packet required')
    stores = HostStores(args.owner_db, args.snapshot_dir, recovered,
                        args.snapshot_sha256, directory, None, 0,
                        args.isolated_rehearsal, args.bundle_sha256,
                        args.active_binding, args.active_binding_sha256)
    stores.verify_active()
    target = stores.target_state()
    need(target['schema'] == 'retained-aida-target-state/v1'
         and target['snapshot_sha256'] == args.snapshot_sha256
         and target['revision'] == 0 and target['events'] == []
         and target['source_rows'] == [] and target['non_source_row_count'] == 0,
         'canonical target changed before rebind')
    owner = stores.owner_state()
    need(owner['snapshot_sha256'] == args.snapshot_sha256
         and owner['proposal_count'] == 0 and owner['human_event_count'] == 0
         and owner['store_revision'] in (args.expected_owner_revision,
                                         args.expected_owner_revision + 1),
         'owner revision changed before rebind')
    if owner['store_revision'] == args.expected_owner_revision:
        need(all((owner['observation_refs'].get(row['snapshot_record_id']) or {})
                 .get('source_derived_ref') is None for row in loaded['observations']),
             'owner already has source refs at expected pre-rebind revision')
    backup = directory / 'owner-pre-rebind.sqlite'
    if owner['store_revision'] == args.expected_owner_revision:
        source = activation.open_owner(args.owner_db)
        try:
            activation.copy_sqlite(source, backup)
        finally:
            source.close()
        activation.checked_copy(backup, owner, 'pre-rebind owner backup')
        restored = directory / 'owner-pre-rebind-restore.sqlite'
        activation.restore_standalone(backup, restored)
        activation.checked_copy(restored, owner, 'pre-rebind restore drill')
        receipt(directory, 'phase-owner-pre-rebind.json',
                {'phase': 'owner_backup_verified', 'snapshot_sha256': args.snapshot_sha256,
                 'owner_revision': args.expected_owner_revision,
                 'backup_sha256': digest(backup)})
    else:
        pin = activation.checked_json(directory / 'phase-owner-pre-rebind.json',
                                      digest(directory / 'phase-owner-pre-rebind.json'))
        need(pin['owner_revision'] == args.expected_owner_revision
             and pin['backup_sha256'] == digest(backup),
             'pre-rebind backup receipt changed')
    pg_name = stores.backup_postgres(directory)
    need(stores.verify_postgres_backup(directory, pg_name)
         and stores.restore_drill(directory, pg_name),
         'PostgreSQL backup or restore drill failed')
    receipt(directory, 'phase-pg-pre-rebind.json',
            {'phase': 'pg_restore_verified', 'snapshot_sha256': args.snapshot_sha256,
             'backup_sha256': digest(directory / pg_name)})
    need(stores.target_state() == target and stores.owner_state() == owner,
         'target or owner changed before rebind')
    stores.verify_active()
    key = 'retained-aida-rebind:' + args.snapshot_sha256
    store = DecisionStore(args.owner_db)
    try:
        store.bind_verified_snapshot(args.snapshot_dir,
                                     expected_revision=args.expected_owner_revision,
                                     idempotency_key=key,
                                     recovered_packet_paths=recovered)
    finally:
        store.close()
    after = stores.owner_state()
    need(after['store_revision'] == args.expected_owner_revision + 1
         and after['binding_revision'] == after['store_revision']
         and after['snapshot_sha256'] == args.snapshot_sha256
         and after['proposal_count'] == 0 and after['human_event_count'] == 0
         and all((after['observation_refs'].get(row['snapshot_record_id']) or {})
                 .get('source_derived_ref') == row['source_observation_ref']
                 for row in loaded['observations']),
         'rebound owner source refs changed')
    need(stores.target_state() == target, 'canonical target changed during rebind')
    result = {'schema': 'retained-aida-owner-rebind/v1',
              'snapshot_sha256': args.snapshot_sha256,
              'owner_revision': after['store_revision'],
              'source_rows': len(loaded['observations']),
              'status': 'rebound_pending_new_preflight'}
    receipt(directory, 'rebind-status.json', result)
    return result


def main(argv=None):
    if argv is None:
        argv = sys.argv[1:]
    if argv and argv[0] == 'rebind':
        parser = argparse.ArgumentParser(description='CAS rebind verified AIDA owner source refs')
        parser.add_argument('--snapshot-dir', type=Path, required=True)
        parser.add_argument('--snapshot-sha256', required=True)
        parser.add_argument('--owner-db', type=Path, required=True)
        parser.add_argument('--expected-owner-revision', type=int, required=True)
        parser.add_argument('--recovered-packet', action='append', default=[])
        parser.add_argument('--phase-dir', type=Path, required=True)
        parser.add_argument('--bundle-sha256', required=True)
        parser.add_argument('--active-binding', type=Path)
        parser.add_argument('--active-binding-sha256')
        parser.add_argument('--isolated-rehearsal', action='store_true')
        args = parser.parse_args(argv[1:])
        need(not args.isolated_rehearsal or
             (args.active_binding is not None and args.active_binding_sha256
              and os.getenv('FREEDIVING_PG_ISOLATED') == '1'
              and os.getenv('PGDATABASE', '').startswith('aida_rehearsal_')
              and os.getenv('FREEDIVING_PG_DRILL_DATABASE', '').startswith('aida_drill_')),
             'isolated active binding input required')
        print(json.dumps(rebind_verified_owner(args), sort_keys=True))
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('preflight', 'envelope', 'flow', 'checkpoint'):
        parser.add_argument('--' + name, type=Path, required=True)
        parser.add_argument('--' + name + '-sha256', required=True)
    parser.add_argument('--owner-db', type=Path, required=True)
    parser.add_argument('--snapshot-dir', type=Path, required=True)
    parser.add_argument('--recovered-packet', action='append', default=[])
    parser.add_argument('--phase-dir', type=Path, required=True)
    status_source = parser.add_mutually_exclusive_group()
    status_source.add_argument('--run-dir', type=Path)
    status_source.add_argument('--status-from-current', action='store_true')
    parser.add_argument('--isolated-rehearsal', action='store_true')
    parser.add_argument('--bundle-sha256', required=True)
    parser.add_argument('--active-binding', type=Path)
    parser.add_argument('--active-binding-sha256')
    args = parser.parse_args(argv)
    need(args.isolated_rehearsal or args.run_dir is not None or args.status_from_current,
         'private status provenance required')
    if args.isolated_rehearsal:
        need(os.getenv('FREEDIVING_PG_ISOLATED') == '1'
             and os.getenv('PGDATABASE', '').startswith('aida_rehearsal_')
             and os.getenv('FREEDIVING_PG_DRILL_DATABASE', '').startswith('aida_drill_')
             and args.active_binding is not None and args.active_binding_sha256,
             'isolated rehearsal requires disposable databases')
    directory = private_directory(args.phase_dir)
    paths = {name: getattr(args, name) for name in ('preflight', 'envelope', 'flow', 'checkpoint')}
    pins = {name + '_sha256': getattr(args, name + '_sha256') for name in paths}
    values = {name: activation.checked_json(path, pins[name + '_sha256'])
              for name, path in paths.items()}
    preflight, envelope, checkpoint = (values[name] for name in
                                      ('preflight', 'envelope', 'checkpoint'))
    need(checkpoint.get('schema') == 'retained-aida-activation-checkpoint/v1'
         and checkpoint.get('pins', {}).get('preflight_sha256') == pins['preflight_sha256']
         and checkpoint['pins'].get('envelope_sha256') == pins['envelope_sha256']
         and checkpoint.get('owner_backup_sha256')
         and checkpoint.get('restore_sha256') == checkpoint['owner_backup_sha256'],
         'activation checkpoint changed')
    need(values['flow'].get('schema') == 'retained-aida-flow-export/v1'
         and values['flow'].get('preflight_sha256') == pins['preflight_sha256'],
         'private flow export changed')
    backup = paths['checkpoint'].parent / 'owner-backup.sqlite'
    restored = paths['checkpoint'].parent / 'owner-restore-drill.sqlite'
    need(digest(backup) == checkpoint['owner_backup_sha256']
         and digest(restored) == checkpoint['restore_sha256'],
         'SQLite backup or restore drill changed')
    backup_db = sqlite3.connect(backup.resolve().as_uri() + '?mode=ro&immutable=1', uri=True)
    try:
        backup_owner = activation.owner_state(backup_db)
    finally:
        backup_db.close()
    activation.checked_copy(backup, backup_owner, 'owner backup')
    activation.checked_copy(restored, backup_owner, 'owner restore drill')
    recovered = {}
    for item in args.recovered_packet:
        name, sep, path = item.partition('=')
        need(sep and name and path and name not in recovered, 'invalid recovered packet')
        recovered[name] = Path(path)
    names = sorted({row['source-observation-ref']['source_name']
                    for row in preflight['source_rows']})
    loaded = activation.load_source_observations(args.snapshot_dir, names,
                                                 recovered_packet_paths=recovered)
    need(not loaded.get('gaps') and loaded.get('snapshot_sha256') == preflight['snapshot_sha256'],
         'source packet changed')
    empty_target = {'schema': 'retained-aida-target-state/v1',
                    'snapshot_sha256': preflight['snapshot_sha256'], 'revision': 0,
                    'events': [], 'source_rows': [], 'non_source_row_count': 0}
    activation.verify_inputs(preflight, envelope, empty_target, backup_owner, loaded)
    sys.path.insert(0, str(REPO / 'scripts'))
    from retained_aida_owner_bridge import build_owner_export
    flow = values['flow']
    rebuilt = build_owner_export(preflight, loaded, backup_owner, flow['flow'],
                                 flow['decisions'], coverage=flow,
                                 expected_preflight_sha256=pins['preflight_sha256'])
    need(rebuilt == envelope, 'pending owner envelope differs from flow')
    stores = HostStores(args.owner_db, args.snapshot_dir, recovered,
                        preflight['snapshot_sha256'], directory, args.run_dir,
                        envelope['counts']['unresolved'], args.isolated_rehearsal,
                        args.bundle_sha256, args.active_binding,
                        args.active_binding_sha256, args.status_from_current)
    result = run_manual_apply(stores, preflight, envelope, pins, directory)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
