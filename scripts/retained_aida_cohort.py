#!/usr/bin/env python3
"""Prepare a currently reverified, source-cited AIDA cohort for isolated replay."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

try:
    from scripts.aida_identity_replay import plan_replay
    from scripts.aida_snapshot_observations import load_source_observations
except ModuleNotFoundError:
    from aida_identity_replay import plan_replay
    from aida_snapshot_observations import load_source_observations


SHA = re.compile(r'[0-9a-f]{64}\Z')


def _need(value, message):
    if not value:
        raise ValueError(message)


def _digest(data):
    return hashlib.sha256(data).hexdigest()


def _identity(row):
    return 'source-observation:' + row['snapshot_record_id']


def _encoded(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\n').encode()


def build_cohort(loaded, frozen_observations, frozen_plan, *, observations_sha256,
                 plan_sha256, prior_events=(), canonical_history=None):
    """Use only rows reverified from original bytes; missing packet rows stay gaps."""
    snapshot = loaded.get('snapshot_sha256')
    _need(isinstance(snapshot, str) and SHA.fullmatch(snapshot), 'verified snapshot required')
    _need(isinstance(frozen_observations, list) and frozen_observations,
          'frozen AIDA observations required')
    _need(all(isinstance(x, str) and SHA.fullmatch(x)
              for x in (observations_sha256, plan_sha256)), 'input hashes required')
    _need(plan_replay(frozen_observations, expected_snapshot_sha256=snapshot) == frozen_plan,
          'frozen AIDA plan differs from observations')
    frozen = {_identity(row): row for row in frozen_observations}
    _need(len(frozen) == len(frozen_observations), 'duplicate frozen AIDA observation')
    rows = loaded.get('observations')
    gaps = loaded.get('gaps')
    _need(isinstance(rows, list) and isinstance(gaps, list), 'source replay required')
    verified = {}
    for row in rows:
        ident = _identity(row)
        _need(ident not in verified and ident in frozen and row == frozen[ident],
              'current source observation differs from frozen export')
        verified[ident] = row
    missing = {}
    for gap in gaps:
        ident = 'source-observation:' + gap['snapshot_record_id']
        _need(gap.get('reason') == 'retained-packet-missing' and ident in frozen
              and ident not in verified and ident not in missing
              and gap.get('source_name') == frozen[ident]['source_name'],
              'invalid AIDA source gap')
        missing[ident] = gap
    _need(set(verified) | set(missing) == set(frozen),
          'source replay does not cover frozen AIDA cohort')
    _need(verified, 'no currently reverified AIDA rows')
    binding = {'snapshot_sha256': snapshot,
               'observations_sha256': observations_sha256,
               'plan_sha256': plan_sha256}
    if canonical_history is not None:
        _need(not prior_events, 'ambiguous prior AIDA history')
        _need(isinstance(canonical_history, dict)
              and canonical_history.get('schema') == 'retained-aida-canonical-history/v1'
              and canonical_history.get('snapshot_sha256') == snapshot,
              'canonical AIDA history binding mismatch')
        revision = canonical_history.get('revision')
        events = canonical_history.get('events')
        _need(type(revision) is int and revision >= 0
              and isinstance(events, list) and len(events) == revision
              and all(isinstance(event, dict) and isinstance(event.get('id'), str)
                      and event['id'] for event in events)
              and len({event['id'] for event in events}) == revision,
              'invalid canonical AIDA history')
        prior_events = events
        binding['identity_revision'] = revision
        binding['history_event_ids'] = [event['id'] for event in events]
    replay = plan_replay(list(verified.values()), expected_snapshot_sha256=snapshot,
                         prior_events=prior_events)
    registration_rows = []
    refs = {}
    for ident, row in sorted(verified.items()):
        ref = row['source_observation_ref']
        person = row['source_fields']['publisher_person']
        registration_rows.append({'observation_id': ident,
                                  'source_name': row['source_fields']['name'],
                                  'parse_status': 'parsed', 'citation': ref,
                                  'source_observation_ref': ref,
                                  'publisher_scope': person['scope'],
                                  'publisher_athlete_id': person['id'],
                                  'publisher_id_kind': 'person'})
        refs[ident] = ref
    events = []
    for edge in replay['edges']:
        pair = edge['pair']
        event_id = 'aida-source-' + _digest(_encoded([snapshot, pair, edge['refs']]))[:64]
        events.append({'id': event_id, 'action': 'accept',
                       'actor_kind': 'automatic', 'pair': pair,
                       'rule_version': 'athlete-identity/1',
                       'source_binding': {'snapshot_sha256': snapshot,
                                          'refs': edge['refs']}})
    return {'schema': 'retained-aida-cohort/v1',
            'binding': binding,
            'registration': {'snapshot_sha256': snapshot,
                             'rows': registration_rows, 'verified_refs': refs},
            'events': events,
            'counts': {'source_rows': len(verified), 'source_gaps': len(missing),
                       'candidate_edges': len(events),
                       'repeated_person_groups': replay['denominator']['repeated_person_groups'],
                       'blocked_groups': replay['counts']['blocked_groups']},
            'gaps': [missing[key] for key in sorted(missing)],
            'global_accepted_athletes': None, 'distinct_attempts': None}


def _checked_json(path, expected):
    _need(SHA.fullmatch(expected or ''), 'expected SHA256 required')
    data = Path(path).read_bytes()
    _need(_digest(data) == expected, 'checked input hash mismatch')
    return json.loads(data)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot_dir', type=Path)
    parser.add_argument('observations_json', type=Path)
    parser.add_argument('plan_json', type=Path)
    parser.add_argument('output_json', type=Path)
    parser.add_argument('--observations-sha256', required=True)
    parser.add_argument('--plan-sha256', required=True)
    parser.add_argument('--prior-events', type=Path)
    parser.add_argument('--canonical-history', type=Path)
    parser.add_argument('--recovered-packet', action='append', default=[], metavar='SOURCE=PATH')
    args = parser.parse_args(argv)
    frozen = _checked_json(args.observations_json, args.observations_sha256)
    plan = _checked_json(args.plan_json, args.plan_sha256)
    names = sorted({row['source_name'] for row in frozen})
    recovered = {}
    for binding in args.recovered_packet:
        source, separator, path = binding.partition('=')
        _need(separator and source and path and source not in recovered,
              'invalid recovered packet binding')
        recovered[source] = Path(path)
    loaded = load_source_observations(args.snapshot_dir, names,
                                      recovered_packet_paths=recovered)
    _need(not (args.prior_events and args.canonical_history),
          'ambiguous prior AIDA history')
    prior = json.loads(args.prior_events.read_text()) if args.prior_events else ()
    history = json.loads(args.canonical_history.read_text()) if args.canonical_history else None
    result = build_cohort(loaded, frozen, plan,
                          observations_sha256=args.observations_sha256,
                          plan_sha256=args.plan_sha256, prior_events=prior,
                          canonical_history=history)
    destination = args.output_json.resolve()
    _need(not destination.is_relative_to(Path(__file__).resolve().parents[1]),
          'private cohort output must be outside repository')
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    data = _encoded(result)
    fd, temporary = tempfile.mkstemp(prefix='.aida-cohort-', dir=destination.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(json.dumps({'output_sha256': _digest(data), 'counts': result['counts']},
                     sort_keys=True))


if __name__ == '__main__':
    main()
