#!/usr/bin/env python3
"""Prepare a private, read-only activation checkpoint. Never applies live state."""

import argparse
from contextlib import closing
import hashlib
import json
import os
import re
import shutil
import sqlite3
import stat
import tempfile
from pathlib import Path

try:
    from scripts.aida_snapshot_observations import load_source_observations
except ModuleNotFoundError:
    from aida_snapshot_observations import load_source_observations


SHA = re.compile(r'[0-9a-f]{64}\Z')
REPO = Path(__file__).resolve().parents[1]


def need(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def checked_json(path, expected):
    need(isinstance(expected, str) and SHA.fullmatch(expected), 'input SHA256 required')
    path = Path(path)
    need(path.is_file() and not path.is_symlink() and digest(path) == expected,
         'input file changed')
    return json.loads(path.read_text())


def private_dir(path):
    path = Path(path)
    need(not path.resolve().is_relative_to(REPO), 'private output must be outside repository')
    info = path.lstat()
    need(stat.S_ISDIR(info.st_mode) and not path.is_symlink()
         and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0,
         'private output directory must be owner-only')
    return path


def atomic_json(path, value):
    data = (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()
    if path.exists() or path.is_symlink():
        need(not path.is_symlink() and path.is_file() and path.read_bytes() == data,
             f'{path.name} receipt changed')
        return
    fd, name = tempfile.mkstemp(prefix='.activation-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(name, 0o600)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def owner_state(db):
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA query_only=ON')
    db.execute('BEGIN')
    revision = int(db.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0])
    binding = db.execute('SELECT revision,snapshot_sha256,observation_refs_json '
                         'FROM bindings ORDER BY revision DESC LIMIT 1').fetchone()
    need(binding is not None, 'owner binding missing')
    state = {'store_revision': revision, 'binding_revision': binding['revision'],
             'snapshot_sha256': binding['snapshot_sha256'],
             'observation_refs': json.loads(binding['observation_refs_json']),
             'proposal_count': db.execute('SELECT COUNT(*) FROM proposals').fetchone()[0],
             'human_event_count': db.execute('SELECT COUNT(*) FROM events').fetchone()[0],
             'operation_count': db.execute('SELECT COUNT(*) FROM operations').fetchone()[0]}
    db.execute('COMMIT')
    return state


def open_owner(path):
    path = Path(path)
    info = path.lstat()
    need(stat.S_ISREG(info.st_mode) and not path.is_symlink()
         and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0,
         'owner DB must be an owner-only regular file')
    return sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)


def verify_inputs(preflight, envelope, target, owner, loaded):
    snapshot = preflight.get('snapshot_sha256')
    need(preflight.get('schema') == 'retained-aida-promotion-preflight/v1'
         and isinstance(snapshot, str) and SHA.fullmatch(snapshot)
         and preflight.get('authority') == 'read_only_preflight_recheck_targets_before_apply'
         and preflight.get('expected_production_revision') == 0,
         'preflight must pin an empty target')
    rows = preflight.get('source_rows')
    observations = loaded.get('observations')
    need(loaded.get('snapshot_sha256') == snapshot and not loaded.get('gaps')
         and isinstance(rows, list) and rows and isinstance(observations, list)
         and len(rows) == len(observations), 'source replay has gaps or changed row count')
    source = {}
    for row in observations:
        ident = 'source-observation:' + row['snapshot_record_id']
        need(ident not in source, 'duplicate source observation')
        source[ident] = row['source_observation_ref']
    registered = {}
    for row in rows:
        ident = row.get('observation-id')
        need(ident in source and ident not in registered
             and row.get('source-observation-ref') == source[ident]
             and row.get('citation') == source[ident], 'source row changed')
        registered[ident] = row
    need(set(source) == set(registered), 'source row set changed')
    refs = owner['observation_refs']
    need(owner['snapshot_sha256'] == snapshot
         and owner['binding_revision'] == preflight.get('owner', {}).get('binding_revision')
         and owner['store_revision'] == preflight['owner'].get('store_revision')
         and owner['proposal_count'] == 0 and owner['human_event_count'] == 0
         and isinstance(refs, dict)
         and all((refs.get(ident.removeprefix('source-observation:')) or {}).get(
             'source_derived_ref') == ref for ident, ref in source.items()),
         'owner revision, history, proposals, or source binding changed')
    replay = preflight.get('canonical_replay')
    need(isinstance(replay, list) and replay
         and len(replay) == preflight.get('counts', {}).get('canonical_events')
         and all(event.get('base-revision') == index
                 for index, event in enumerate(replay)),
         'canonical replay changed')
    accepted = {}
    reversed_ids = set()
    for event in replay:
        action = event.get('action')
        pair = (accepted.get(event.get('event-id')) if action == 'reverse'
                else event.get('pair'))
        binding = event.get('source-binding') or {}
        need(isinstance(pair, list) and len(pair) == 2 and pair[0] != pair[1]
             and all(ident in source for ident in pair)
             and binding.get('snapshot-sha256') == snapshot
             and binding.get('refs') == {ident: source[ident] for ident in pair},
             'canonical replay source binding changed')
        if action == 'accept':
            need(event.get('actor-kind') == 'automatic'
                 and event.get('id') not in accepted and event.get('id')
                 and isinstance(event.get('rule-version'), str)
                 and event['rule-version'], 'canonical accept changed')
            accepted[event['id']] = pair
        elif action == 'reverse':
            original = event.get('event-id')
            need(event.get('actor-kind') == 'human'
                 and original in accepted and original not in reversed_ids
                 and accepted[original] == pair
                 and isinstance(event.get('reason'), str)
                 and event['reason'].strip(), 'canonical human reversal changed')
            reversed_ids.add(original)
        else:
            raise ValueError('unsupported canonical replay action')
    need(target.get('schema') == 'retained-aida-target-state/v1'
         and target.get('snapshot_sha256') == snapshot
         and target.get('revision') == 0 and target.get('events') == []
         and target.get('source_rows') == []
         and target.get('non_source_row_count') == 0,
         'target state changed; prepare requires an empty target')
    intents = preflight['owner'].get('candidate_intents')
    proposals = envelope.get('proposals')
    counts = envelope.get('counts') or {}
    active_ids = set(accepted) - reversed_ids
    need(isinstance(intents, list) and isinstance(proposals, list) and proposals
         and {intent.get('source_event_id') for intent in intents} == active_ids
         and len(intents) == len(active_ids)
         and envelope.get('snapshot_sha256') == snapshot
         and envelope.get('binding_revision') == owner['binding_revision']
         and envelope.get('store_revision') == owner['store_revision']
         and counts.get('active_intents') == len(intents)
         and counts.get('supported') == len(proposals)
         and type(counts.get('unresolved')) is int and counts['unresolved'] >= 0
         and counts.get('supported') + counts.get('unresolved') == len(intents)
         and len({p.get('id') for p in proposals}) == len(proposals)
         and len({p.get('canonical_binding', {}).get('source_event_id')
                  for p in proposals}) == len(proposals)
         and all(p.get('status') == 'pending'
                 and p.get('canonical_binding', {}).get('source_event_id') in
                 {intent.get('source_event_id') for intent in intents}
                 for p in proposals),
         'pending owner envelope changed')
    return {'source_rows': len(rows), 'canonical_events': len(replay),
            'pending_proposals': len(proposals), 'unresolved_intents': counts['unresolved']}


def copy_sqlite(source, destination):
    if destination.exists() or destination.is_symlink():
        need(not destination.is_symlink() and destination.is_file(),
             f'{destination.name} changed')
        need(not any(destination.parent.glob(destination.name + '-*')),
             f'{destination.name} has SQLite sidecars')
        return
    fd, name = tempfile.mkstemp(prefix='.owner-backup-', dir=destination.parent)
    os.close(fd)
    try:
        with closing(sqlite3.connect(name)) as target:
            source.backup(target)
            target.execute('PRAGMA wal_checkpoint(TRUNCATE)')
            need(target.execute('PRAGMA journal_mode=DELETE').fetchone()[0] == 'delete',
                 'owner backup journal could not be made standalone')
        need(not any(Path(name).parent.glob(Path(name).name + '-*')),
             'owner backup temporary SQLite sidecars remain')
        os.chmod(name, 0o600)
        os.replace(name, destination)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def checked_copy(path, expected_owner, label):
    need(path.is_file() and not path.is_symlink()
         and path.stat().st_mode & 0o077 == 0
         and not any(path.parent.glob(path.name + '-*')), f'{label} changed')
    db = sqlite3.connect(path.resolve().as_uri() + '?mode=ro&immutable=1', uri=True)
    try:
        need(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok',
             f'{label} integrity check failed')
        need(owner_state(db) == expected_owner, f'{label} owner state changed')
    except sqlite3.DatabaseError as exc:
        raise ValueError(f'{label} is not a valid SQLite backup') from exc
    finally:
        db.close()


def restore_standalone(backup, restored):
    if restored.exists() or restored.is_symlink():
        need(not restored.is_symlink() and restored.is_file(), 'restore drill changed')
        return
    fd, name = tempfile.mkstemp(prefix='.owner-restore-', dir=restored.parent)
    os.close(fd)
    try:
        shutil.copyfile(backup, name)
        os.chmod(name, 0o600)
        os.replace(name, restored)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def prepare_checkpoint(preflight_path, preflight_sha, envelope_path, envelope_sha,
                       target_path, target_sha, owner_path, snapshot_dir,
                       recovered_packets, output_dir):
    output = private_dir(output_dir)
    preflight = checked_json(preflight_path, preflight_sha)
    envelope = checked_json(envelope_path, envelope_sha)
    target = checked_json(target_path, target_sha)
    names = sorted({row['source-observation-ref']['source_name']
                    for row in preflight['source_rows']})
    need(set(recovered_packets) <= set(names), 'unknown recovered packet source')
    loaded = load_source_observations(snapshot_dir, names,
                                      recovered_packet_paths=recovered_packets)
    owner = open_owner(owner_path)
    try:
        before = owner_state(owner)
        counts = verify_inputs(preflight, envelope, target, before, loaded)
        pins = {'preflight_sha256': preflight_sha, 'envelope_sha256': envelope_sha,
                'target_sha256': target_sha, 'snapshot_sha256': preflight['snapshot_sha256'],
                'owner_revision': before['store_revision']}
        atomic_json(output / 'phase-inputs.json', {'phase': 'inputs_verified', 'pins': pins})
        backup = output / 'owner-backup.sqlite'
        copy_sqlite(owner, backup)
        checked_copy(backup, before, 'owner backup')
        need(owner_state(owner) == before, 'owner changed during backup')
        backup_sha = digest(backup)
        atomic_json(output / 'phase-owner-backup.json',
                    {'phase': 'owner_backup_verified', 'pins': pins,
                     'owner_backup_sha256': backup_sha})
        restored = output / 'owner-restore-drill.sqlite'
        restore_standalone(backup, restored)
        checked_copy(restored, before, 'restore drill')
        need(digest(restored) == backup_sha, 'restore drill differs from backup')
        atomic_json(output / 'phase-restore-drill.json',
                    {'phase': 'restore_drill_verified', 'pins': pins,
                     'owner_backup_sha256': backup_sha,
                     'restore_sha256': digest(restored)})
        result = {'schema': 'retained-aida-activation-checkpoint/v1',
                  'authority': 'read_only_no_live_apply',
                  'next_checkpoint': 'fresh_live_target_and_owner_readback_before_any_write',
                  'pins': pins, 'counts': counts,
                  'owner_backup_sha256': backup_sha,
                  'restore_sha256': digest(restored)}
        atomic_json(output / 'checkpoint.json', result)
        return result
    finally:
        owner.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('preflight', 'envelope', 'target'):
        parser.add_argument('--' + name, type=Path, required=True)
        parser.add_argument('--' + name + '-sha256', required=True)
    parser.add_argument('--owner-db', type=Path, required=True)
    parser.add_argument('--snapshot-dir', type=Path, required=True)
    parser.add_argument('--recovered-packet', action='append', default=[])
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args(argv)
    recovered = {}
    for value in args.recovered_packet:
        name, separator, path = value.partition('=')
        need(separator and name and path and name not in recovered,
             'invalid recovered packet binding')
        recovered[name] = Path(path)
    result = prepare_checkpoint(args.preflight, args.preflight_sha256,
                                args.envelope, args.envelope_sha256,
                                args.target, args.target_sha256, args.owner_db,
                                args.snapshot_dir, recovered, args.output_dir)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
