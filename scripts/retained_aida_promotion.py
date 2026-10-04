#!/usr/bin/env python3
"""Private, read-only preflight for a retained AIDA canonical replay.

Output is a guarded intent package, not an owner DecisionStore export. In
particular source-derived automatic approvals cannot enter DecisionStore.
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import tempfile
from pathlib import Path

try:
    from scripts.aida_snapshot_observations import load_source_observations
except ModuleNotFoundError:
    from aida_snapshot_observations import load_source_observations


SHA = re.compile(r'[0-9a-f]{64}\Z')
SOURCE_ID = re.compile(r'source-observation:([0-9a-f]{64})\Z')


def _need(ok, reason):
    if not ok:
        raise ValueError(reason)


def _sha_file(path, expected):
    _need(isinstance(expected, str) and SHA.fullmatch(expected), 'expected SHA256 required')
    data = Path(path).read_bytes()
    _need(hashlib.sha256(data).hexdigest() == expected, 'input SHA256 changed')
    return json.loads(data)


def _owner_state(path):
    """Read an existing owner store without creating files or migrating schema."""
    db = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    try:
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        revision = int(db.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0])
        binding = db.execute('SELECT revision,snapshot_sha256,observation_refs_json FROM bindings '
                             'ORDER BY revision DESC LIMIT 1').fetchone()
        _need(binding is not None, 'owner snapshot binding missing')
        proposal_count = db.execute('SELECT COUNT(*) FROM proposals').fetchone()[0]
        state = {'snapshot_sha256': binding['snapshot_sha256'],
                'binding_revision': binding['revision'], 'store_revision': revision,
                'proposal_count': proposal_count,
                'observation_refs': (json.loads(binding['observation_refs_json'])
                                     if binding['observation_refs_json'] else None)}
        db.execute('COMMIT')
        return state
    finally:
        db.close()


def build_promotion_preflight(handoff, current_readback, loaded, owner,
                              *, expected_owner_revision, expected_production_revision,
                              production_history):
    """Require exact isolated readback, source replay, and target revision pins."""
    _need(handoff.get('schema') == 'retained-aida-isolated-handoff/v1'
          and handoff.get('authority_scope') == 'selected_isolated_store_only',
          'isolated handoff authority mismatch')
    readback = handoff.get('canonical_readback')
    status = handoff.get('status') or {}
    snapshot = (status.get('binding') or {}).get('snapshot_sha256')
    _need(isinstance(snapshot, str) and SHA.fullmatch(snapshot), 'snapshot binding required')
    _need(readback == current_readback
          and readback.get('schema') == 'retained-aida-canonical-readback/v1'
          and readback.get('snapshot-sha256') == snapshot
          and readback.get('non-source-row-count') == 0,
          'isolated canonical readback changed')
    events = readback.get('events')
    rows = readback.get('source-rows')
    projection = readback.get('projection') or {}
    canonical = status.get('canonical') or {}
    _need(isinstance(events, list) and isinstance(rows, list) and rows
          and projection.get('revision') == len(events)
          and canonical.get('identity_revision') == len(events)
          and status.get('provider_calls') == 0,
          'isolated canonical revision or provider count changed')
    _need(loaded.get('snapshot_sha256') == snapshot
          and isinstance(loaded.get('observations'), list)
          and not loaded.get('gaps'), 'full source re-verification required')
    source = {}
    for row in loaded['observations']:
        ident = 'source-observation:' + row['snapshot_record_id']
        _need(SOURCE_ID.fullmatch(ident) and ident not in source,
              'duplicate or invalid source observation')
        source[ident] = row['source_observation_ref']
    registered = {}
    for row in rows:
        ident = row.get('observation-id')
        ref = row.get('source-observation-ref')
        _need(isinstance(ident, str) and SOURCE_ID.fullmatch(ident)
              and ident not in registered and ref == source.get(ident)
              and row.get('citation') == ref
              and ref.get('snapshot_sha256') == snapshot
              and ref.get('snapshot_record_id') == SOURCE_ID.fullmatch(ident).group(1),
              'registered source row differs from current source')
        registered[ident] = row
    _need(set(registered) == set(source), 'source row set changed')
    _need(isinstance(owner, dict)
          and type(expected_owner_revision) is int and expected_owner_revision >= 1
          and owner.get('snapshot_sha256') == snapshot
          and owner.get('store_revision') == expected_owner_revision
          and owner.get('proposal_count') == 0
          and type(owner.get('binding_revision')) is int
          and 1 <= owner['binding_revision'] <= expected_owner_revision,
          'owner snapshot or store revision changed')
    owner_refs = owner.get('observation_refs')
    _need(isinstance(owner_refs, dict)
          and all((owner_refs.get(ref['snapshot_record_id']) or {}).get('source_derived_ref') == ref
                  for ref in source.values()),
          'owner binding lacks exact source-derived refs')
    _need(type(expected_production_revision) is int and expected_production_revision >= 0
          and production_history.get('schema') == 'retained-aida-target-state/v1'
          and production_history.get('snapshot_sha256') == snapshot
          and production_history.get('revision') == expected_production_revision
          and isinstance(production_history.get('events'), list)
          and len(production_history['events']) == expected_production_revision,
          'production canonical revision changed')
    target_rows = production_history.get('source_rows')
    _need(production_history.get('non_source_row_count') == 0
          and isinstance(target_rows, list)
          and len({row.get('observation-id') for row in target_rows
                   if isinstance(row, dict)}) == len(target_rows)
          and all(isinstance(row, dict)
                  and row == registered.get(row.get('observation-id'))
                  for row in target_rows),
          'production target source rows differ from isolated source')
    accepted = {}
    reversed_ids = set()
    correction_revisions = []
    requests = []
    for index, event in enumerate(events):
        request = event.get('request')
        action = event.get('action')
        actor = event.get('actor-kind')
        binding = event.get('source-binding')
        pair = event.get('pair')
        _need(isinstance(request, dict) and request.get('id') == event.get('id')
              and request.get('action') == action
              and request.get('actor-kind') == actor
              and request.get('base-revision') == index
              and event.get('base-revision') == index
              and request.get('source-binding') == binding
              and isinstance(binding, dict)
              and binding.get('snapshot-sha256') == snapshot
              and isinstance(pair, list) and len(pair) == 2 and pair[0] != pair[1]
              and all(ident in registered for ident in pair)
              and binding.get('refs') == {ident: source[ident] for ident in pair},
              'canonical event binding differs from current source')
        if action == 'accept':
            _need(actor == 'automatic' and request.get('pair') == pair
                  and isinstance(request.get('rule-version'), str)
                  and request['rule-version'], 'automatic event request changed')
            accepted[event['id']] = event
        elif action == 'reverse':
            previous = accepted.get(event.get('event-id'))
            _need(actor == 'human' and previous is not None
                  and event['event-id'] not in reversed_ids
                  and previous['pair'] == pair
                  and request.get('event-id') == event['event-id']
                  and isinstance(request.get('reason'), str)
                  and request['reason'].strip(), 'human correction provenance changed')
            reversed_ids.add(event['event-id'])
            correction_revisions.append(index + 1)
        else:
            raise ValueError('unsupported canonical event in retained replay')
        requests.append(request)
    _need(correction_revisions
          and readback.get('human-correction-revision') == correction_revisions[-1]
          and canonical.get('human_correction_revision') == correction_revisions[-1],
          'human correction revision changed')
    prior = production_history['events']
    _need(len(prior) <= len(events)
          and all(prior_event.get('id') == event['id']
                  and prior_event.get('request') == event['request']
                  for prior_event, event in zip(prior, events)),
          'production history conflicts with isolated replay')
    intents = [{'source_event_id': event['id'], 'pair': event['pair'],
                'source_binding': event['source-binding'], 'status': 'pending'}
               for event in events if event['action'] == 'accept'
               and event['id'] not in reversed_ids]
    return {'schema': 'retained-aida-promotion-preflight/v1',
            'snapshot_sha256': snapshot,
            'source_rows': rows,
            'expected_production_revision': expected_production_revision,
            'canonical_replay': requests[expected_production_revision:],
            'isolated_human_correction_revision': correction_revisions[-1],
            'owner': {'binding_revision': owner['binding_revision'],
                      'store_revision': expected_owner_revision,
                      'status': 'requires_reconciliation_binding',
                      'candidate_intents': intents},
            'counts': {'source_rows': len(rows), 'canonical_events': len(events),
                       'human_reversals': len(correction_revisions),
                       'owner_candidate_intents': len(intents)},
            'authority': 'read_only_preflight_recheck_targets_before_apply'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--handoff', type=Path, required=True)
    parser.add_argument('--handoff-sha256', required=True)
    parser.add_argument('--current-readback', type=Path, required=True)
    parser.add_argument('--current-readback-sha256', required=True)
    parser.add_argument('--production-history', type=Path, required=True)
    parser.add_argument('--production-history-sha256', required=True)
    parser.add_argument('--expected-production-revision', type=int, required=True)
    parser.add_argument('--owner-db', type=Path, required=True)
    parser.add_argument('--expected-owner-revision', type=int, required=True)
    parser.add_argument('--snapshot-dir', type=Path, required=True)
    parser.add_argument('--recovered-packet', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    handoff = _sha_file(args.handoff, args.handoff_sha256)
    current = _sha_file(args.current_readback, args.current_readback_sha256)
    production = _sha_file(args.production_history, args.production_history_sha256)
    names = sorted({row['source-observation-ref']['source_name']
                    for row in handoff['canonical_readback']['source-rows']})
    recovered = {}
    for entry in args.recovered_packet:
        name, separator, path = entry.partition('=')
        _need(separator and name in names and path and name not in recovered,
              'invalid recovered packet binding')
        recovered[name] = Path(path)
    loaded = load_source_observations(args.snapshot_dir, names,
                                      recovered_packet_paths=recovered)
    package = build_promotion_preflight(
        handoff, current, loaded, _owner_state(args.owner_db),
        expected_owner_revision=args.expected_owner_revision,
        expected_production_revision=args.expected_production_revision,
        production_history=production)
    output = args.output.resolve()
    _need(not output.is_relative_to(Path(__file__).resolve().parents[1]),
          'private output must be outside repository')
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    _need(not output.parent.is_symlink() and output.parent.stat().st_mode & 0o077 == 0,
          'private output directory must be owner-only')
    data = (json.dumps(package, sort_keys=True, separators=(',', ':')) + '\n').encode()
    if output.exists() or output.is_symlink():
        _need(not output.is_symlink() and output.is_file()
              and output.read_bytes() == data,
              'existing promotion package differs')
        print(json.dumps({'schema': package['schema'],
                          'sha256': hashlib.sha256(data).hexdigest(),
                          'counts': package['counts'], 'authority': package['authority']},
                         sort_keys=True))
        return 0
    fd, temporary = tempfile.mkstemp(prefix='.promotion-', dir=output.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(json.dumps({'schema': package['schema'],
                      'sha256': hashlib.sha256(data).hexdigest(),
                      'counts': package['counts'], 'authority': package['authority']},
                     sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
