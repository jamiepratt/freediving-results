"""Read-only, exact no-op contract for an already active retained AIDA replay.

The retained cohort bundle and production source bundle have distinct schemas.
This contract maps their source inventory; it never promotes one hash as the
other, publishes a status, or writes either ledger. A changed pin requires a
new human-reviewed contract rather than replaying a stale local export.
"""

import hashlib
import json
import os
import re
import stat
import argparse
from pathlib import Path

try:
    from scripts.local_evidence_run import retained_status
    from scripts import private_status_sync
except ModuleNotFoundError:
    from local_evidence_run import retained_status
    import private_status_sync


HEX = re.compile(r'[0-9a-f]{64}\Z')
ROLES = {'packet': 'packet_sha256', 'receipt': 'receipt_sha256',
         'original': 'original_sha256'}
REPO = Path(__file__).resolve().parents[1]


def _need(condition, reason):
    if not condition:
        raise ValueError(reason)


def _sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def _manifest(raw, expected, schema):
    _need(isinstance(raw, bytes) and isinstance(expected, str)
          and HEX.fullmatch(expected)
          and hashlib.sha256(raw).hexdigest() == expected,
          'bundle manifest bytes changed')
    value = json.loads(raw)
    _need(isinstance(value, dict) and value.get('schema') == schema,
          'bundle manifest schema changed')
    return value


def _source_row(row):
    return {'observation-id': row['observation_id'],
            'source-name': row['source_name'],
            'parse-status': row['parse_status'],
            'citation': row['citation'],
            'source-observation-ref': row['source_observation_ref'],
            'publisher-scope': row['publisher_scope'],
            'publisher-athlete-id': row['publisher_athlete_id'],
            'publisher-id-kind': row['publisher_id_kind']}


def _source_event(event, revision):
    binding = event['source_binding']
    request = {'id': event['id'], 'action': event['action'],
               'actor-kind': event['actor_kind'], 'base-revision': revision,
               'source-binding': {'snapshot-sha256': binding['snapshot_sha256'],
                                  'refs': binding['refs']}}
    if event['action'] == 'accept':
        request.update({'pair': event['pair'], 'rule-version': event['rule_version']})
    elif event['action'] == 'reverse':
        request.update({'event-id': event['event_id'], 'reason': event['reason']})
    else:
        raise ValueError('unsupported retained canonical event')
    return request


def _verify_canonical_export(export, readback, target, snapshot):
    _need(export.get('schema') == 'retained-aida-cohort/v1'
          and export.get('binding', {}).get('snapshot_sha256') == snapshot
          and export.get('registration', {}).get('snapshot_sha256') == snapshot,
          'retained canonical export binding changed')
    binding = export['binding']
    prior_ids = binding.get('history_event_ids')
    base = binding.get('identity_revision')
    rows = export['registration'].get('rows')
    events = export.get('events')
    _need(type(base) is int and base >= 0
          and isinstance(prior_ids, list) and len(prior_ids) == base
          and isinstance(rows, list) and isinstance(events, list)
          and target.get('revision') == base + len(events),
          'retained canonical export revision changed')
    expected_rows = [_source_row(row) for row in rows]
    actual_rows = target.get('source_rows')
    _need(isinstance(actual_rows, list)
          and len({row.get('observation-id') for row in expected_rows}) == len(expected_rows)
          and {row['observation-id']: row for row in expected_rows} ==
              {row.get('observation-id'): row for row in actual_rows},
          'retained canonical source rows differ')
    _need(readback.get('schema') == 'retained-aida-canonical-readback/v1'
          and readback.get('snapshot-sha256') == snapshot
          and readback.get('non-source-row-count') == 0
          and readback.get('projection', {}).get('revision') == target['revision']
          and readback.get('source-rows') == expected_rows
          and readback.get('source-rows') == actual_rows,
          'retained canonical source rows differ')
    actual_events = target.get('events')
    history = readback.get('events')
    _need(isinstance(history, list) and len(history) == target['revision']
          and all(event.get('base-revision') == index
                  and isinstance(event.get('request'), dict)
                  and event['request'].get('base-revision') == index
                  and event.get('id') == event['request'].get('id')
                  for index, event in enumerate(history))
          and actual_events == [{'id': event['id'], 'request': event['request']}
                                for event in history],
          'retained canonical event differs')
    _need(isinstance(actual_events, list)
          and [event.get('id') for event in actual_events[:base]] == prior_ids,
          'retained canonical history prefix differs')
    for offset, event in enumerate(events):
        request = _source_event(event, base + offset)
        _need(actual_events[base + offset] == {'id': event['id'], 'request': request}
              and history[base + offset]['request'] == request,
              'retained canonical event differs')


def verify_noop_resync(pin, retained, export_bytes, readback_bytes,
                       retained_manifest_bytes, snapshot_manifest,
                       production_manifest_bytes, source_map, status, target_bytes, owner):
    """Return an unchanged receipt only after exact source and live-state reads.

    Inputs are readbacks. The caller must use authenticated status, immutable
    snapshot reads, and read-only canonical and owner transactions. A returned
    receipt authorizes no write. Retrying after interruption performs the same
    comparison and returns the same result. The owner_state projection contains
    counts and revision, not proposal payloads: its hash attests only the
    supplied current readback, not proposal lineage. A later write-capable
    route must inspect the owner ledger and compare exact proposal payloads.
    """
    _need(isinstance(pin, dict) and pin.get('schema') == 'retained-aida-owner-resync/v1'
          and isinstance(retained, dict)
          and retained.get('schema') == 'retained-aida-private-status/v1'
          and isinstance(snapshot_manifest, dict) and isinstance(source_map, dict)
          and isinstance(status, dict) and isinstance(target_bytes, bytes)
          and isinstance(owner, dict), 'resync inputs incomplete')
    target_raw_sha256 = hashlib.sha256(target_bytes).hexdigest()
    target = json.loads(target_bytes)
    _need(isinstance(target, dict), 'canonical target readback invalid')
    snapshot = pin.get('snapshot_sha256')
    _need(isinstance(snapshot, str) and HEX.fullmatch(snapshot)
          and retained.get('binding', {}).get('snapshot_sha256') == snapshot
          and snapshot_manifest.get('snapshot_sha256') == snapshot
          and retained.get('cutoff') == pin.get('cutoff')
          and retained.get('source_gaps') == 0
          and retained.get('provider_calls') == 0
          and retained.get('canonical', {}).get('status') == 'applied'
          and retained['canonical'].get('identity_revision') == pin.get('canonical_revision'),
          'retained checkpoint or cutoff changed')
    cohort = _manifest(retained_manifest_bytes, pin.get('retained_bundle_sha256'),
                       'retained-aida-cohort-bundle/v1')
    production = _manifest(production_manifest_bytes,
                           pin.get('production_bundle_sha256'),
                           'private-source-bundle/v1')
    _need(retained['binding'].get('source_bundle_sha256') == pin['retained_bundle_sha256']
          and cohort.get('snapshot', {}).get('snapshot_sha256') == snapshot,
          'retained bundle identity changed')
    _need(source_map.get('schema') == 'retained-aida-production-source-map/v1'
          and _sha(source_map) == pin.get('source_map_sha256')
          and source_map.get('snapshot_sha256') == snapshot
          and source_map.get('retained_bundle_sha256') == pin['retained_bundle_sha256']
          and source_map.get('production_bundle_sha256') == pin['production_bundle_sha256'],
          'source inventory mapping changed')
    recovered = cohort.get('recovered_packets')
    cohort_inputs = cohort.get('inputs')
    inputs = snapshot_manifest.get('inputs')
    _need(isinstance(recovered, dict) and isinstance(cohort_inputs, dict)
          and isinstance(inputs, dict),
          'retained AIDA source inventory incomplete')
    expected = {}
    for name, value in cohort_inputs.items():
        for role in ROLES:
            suffix = '_' + role
            if name.endswith(suffix):
                key = (name[:-len(suffix)], role)
                _need(key not in expected, 'duplicate retained source')
                expected[key] = value
    _need(expected and {name for name, _ in expected} ==
          {name for name, role in expected if role == 'packet'}
          and all((name, role) in expected
                  for name, role in expected for role in ROLES),
          'retained cohort source triples incomplete')
    for name, item in recovered.items():
        _need(isinstance(item, dict) and name not in {n for n, _ in expected},
              'recovered source overlaps cohort inputs')
        for role, key in ROLES.items():
            expected[name, role] = item.get(key)
    _need(all(isinstance(value, str) and HEX.fullmatch(value)
              for value in expected.values()), 'retained source hash missing')
    sources = production.get('sources')
    mapped = source_map.get('sources')
    _need(isinstance(sources, list) and isinstance(mapped, list)
          and len(mapped) == len(expected), 'source inventory mapping incomplete')
    by_id = {item.get('id'): item for item in sources if isinstance(item, dict)}
    _need(len(by_id) == len(sources), 'duplicate production source')
    seen = set()
    ids = set()
    for item in mapped:
        _need(isinstance(item, dict)
              and set(item) == {'retained_source', 'snapshot_input', 'role',
                                'production_source_id', 'sha256'},
              'source inventory mapping malformed')
        key = item['retained_source'], item['role']
        ident = item['production_source_id']
        value = by_id.get(ident)
        expected_status = 'restricted' if item['role'] == 'original' else 'included'
        snapshot_item = inputs.get(item['snapshot_input'])
        _need(key in expected and key not in seen and ident not in ids
              and item['sha256'] == expected[key]
              and isinstance(value, dict) and value.get('sha256') == expected[key]
              and value.get('status') == expected_status
              and isinstance(snapshot_item, dict)
              and snapshot_item.get('source_schema') == 'aida-selected-html-packet/v1'
              and snapshot_item.get('status') == 'included'
              and (item['role'] != 'packet'
                   or snapshot_item.get('sha256') == expected[key]),
              'retained source differs from production inventory')
        seen.add(key)
        ids.add(ident)
    _need(seen == set(expected), 'source inventory mapping incomplete')
    grouped = {}
    for item in mapped:
        grouped.setdefault(item['retained_source'], set()).add(item['snapshot_input'])
    _need(all(len(names) == 1 for names in grouped.values())
          and len({next(iter(names)) for names in grouped.values()}) == len(grouped),
          'snapshot source mapping ambiguous')
    active = status.get('remote', {}).get('active')
    local = status.get('local', {})
    application = status.get('application', {})
    _need(status.get('schema') == 'private-presentation-status/v3'
          and status.get('status') != 'stale'
          and status.get('revision') == pin.get('status_revision')
          and _sha(status) == pin.get('status_sha256')
          and local.get('snapshot_sha256') == snapshot
          and local.get('cutoff') == pin['cutoff']
          and type(local.get('gap_count')) is int
          and status.get('remote', {}).get('status') == 'active'
          and status['remote'].get('pending') is None
          and status['remote'].get('failed') is None
          and active == {'snapshot_sha256': snapshot,
                         'bundle_manifest_sha256': pin['production_bundle_sha256']},
          'remote status revision or active binding changed')
    _need(target.get('schema') == 'retained-aida-target-state/v1'
          and target.get('snapshot_sha256') == snapshot
          and target.get('revision') == pin.get('canonical_revision')
          and target.get('revision') == len(target.get('events', []))
          and isinstance(target.get('source_rows'), list)
          and len(target['source_rows']) == retained.get('counts', {}).get('source_rows')
          and _sha(target) == pin.get('canonical_state_sha256')
          and owner.get('snapshot_sha256') == snapshot
          and owner.get('store_revision') == pin.get('owner_revision')
          and owner.get('binding_revision') == pin.get('owner_binding_revision')
          and _sha(owner) == pin.get('owner_state_sha256')
          and application.get('snapshot_sha256') == snapshot
          and application.get('canonical_revision') == target['revision']
          and application.get('canonical_readback_sha256') == target_raw_sha256
          and pin.get('canonical_readback_sha256') == target_raw_sha256
          and application.get('owner_store_revision') == owner['store_revision']
          and application.get('pending_proposals') == owner.get('proposal_count')
          and application.get('provider_calls_recorded') == 0
          and application.get('publication_status') == 'private',
          'canonical readback bytes, owner, or private application changed')
    _need(isinstance(export_bytes, bytes)
          and hashlib.sha256(export_bytes).hexdigest() ==
              retained['binding'].get('export_sha256'),
          'retained export bytes changed')
    _need(isinstance(readback_bytes, bytes)
          and hashlib.sha256(readback_bytes).hexdigest() ==
              pin.get('isolated_readback_sha256'),
          'isolated readback bytes changed')
    _verify_canonical_export(json.loads(export_bytes),
                             json.loads(readback_bytes), target, snapshot)
    return {'schema': 'retained-aida-owner-resync-receipt/v1',
            'outcome': 'unchanged', 'contract_sha256': _sha(pin),
            'status_sha256': pin['status_sha256'],
            'canonical_state_sha256': pin['canonical_state_sha256'],
            'owner_state_sha256': pin['owner_state_sha256']}


def _private_bytes(path):
    path = Path(path)
    info = path.lstat()
    _need(stat.S_ISREG(info.st_mode) and not path.is_symlink()
          and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0,
          'readback file must be owner-only and unlinked')
    return path.read_bytes()


def _current_status():
    credentials = [os.getenv('CF_ACCESS_CLIENT_ID'),
                   os.getenv('CF_ACCESS_CLIENT_SECRET'),
                   os.getenv('OWNER_EVIDENCE_STATUS_TOKEN')]
    _need(all(credentials), 'private status credentials missing')
    return private_status_sync._request(
        private_status_sync.build_opener(private_status_sync._NoRedirect()),
        'GET', private_status_sync.STATUS_URL, *credentials)


def run_read_only_preflight(run_dir, paths, receipt_path, *,
                            status_reader=None, retained_reader=None):
    """Check owner-only readback files and authenticated status; write no host state.

    The caller creates fresh canonical target and owner readback files separately.
    This command only authenticates status and verifies their exact pinned bytes.
    The target file must be the exact raw target-state output whose SHA256 was
    recorded in status v3 by canonical_readback_digest; reformatting JSON fails.
    A status revision race or changed input leaves no new receipt.
    """
    required = {'pin', 'export', 'isolated_readback', 'retained_manifest', 'snapshot_manifest',
                'production_manifest', 'source_map', 'target', 'owner'}
    _need(isinstance(paths, dict) and set(paths) == required,
          'resync input paths incomplete')
    receipt_path = Path(receipt_path)
    parent = receipt_path.parent
    info = parent.lstat()
    _need(stat.S_ISDIR(info.st_mode) and not parent.is_symlink()
          and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0,
          'receipt directory must be owner-only and unlinked')
    _need(not parent.resolve().is_relative_to(REPO),
          'receipt directory must be outside repository')
    raw = {name: _private_bytes(path) for name, path in paths.items()}
    pin = json.loads(raw['pin'])
    retained = (retained_reader or retained_status)(Path(run_dir))
    status_reader = status_reader or _current_status
    first_status = status_reader()
    result = verify_noop_resync(
        pin, retained, raw['export'], raw['isolated_readback'], raw['retained_manifest'],
        json.loads(raw['snapshot_manifest']),
        raw['production_manifest'], json.loads(raw['source_map']), first_status,
        raw['target'], json.loads(raw['owner']))
    _need(status_reader() == first_status, 'status changed during preflight')
    _need(all(_private_bytes(paths[name]) == value for name, value in raw.items()),
          'readback file changed during preflight')
    data = (json.dumps(result, sort_keys=True, separators=(',', ':')) + '\n').encode()
    if receipt_path.exists() or receipt_path.is_symlink():
        _need(_private_bytes(receipt_path) == data, 'resync receipt changed')
        return result
    fd = os.open(receipt_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        receipt_path.unlink(missing_ok=True)
        raise
    return result


def main():
    parser = argparse.ArgumentParser(description='Read-only retained AIDA owner no-op preflight')
    parser.add_argument('--run-dir', required=True, type=Path)
    for name in ('pin', 'export', 'isolated-readback', 'retained-manifest', 'snapshot-manifest',
                 'production-manifest', 'source-map', 'target', 'owner'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    args = parser.parse_args()
    paths = {name: getattr(args, name) for name in
             ('pin', 'export', 'isolated_readback', 'retained_manifest', 'snapshot_manifest',
              'production_manifest', 'source_map', 'target', 'owner')}
    receipt = run_read_only_preflight(args.run_dir, paths, args.receipt)
    print(json.dumps({'outcome': receipt['outcome'],
                      'contract_sha256': receipt['contract_sha256']}, sort_keys=True))


if __name__ == '__main__':
    main()
