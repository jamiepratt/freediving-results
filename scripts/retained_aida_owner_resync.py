"""Read-only, exact no-op contract for an already active retained AIDA replay.

The retained cohort bundle and production source bundle have distinct schemas.
This contract maps their source inventory; it never promotes one hash as the
other, publishes a status, or writes either ledger. A changed pin requires a
new human-reviewed contract rather than replaying a stale local export.
"""

import hashlib
import json
import re


HEX = re.compile(r'[0-9a-f]{64}\Z')
ROLES = {'packet': 'packet_sha256', 'receipt': 'receipt_sha256',
         'original': 'original_sha256'}


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


def verify_noop_resync(pin, retained, retained_manifest_bytes, snapshot_manifest,
                       production_manifest_bytes, source_map, status, target, owner):
    """Return an unchanged receipt only after exact source and live-state reads.

    Inputs are readbacks. The caller must use authenticated status, immutable
    snapshot reads, and read-only canonical and owner transactions. A returned
    receipt authorizes no write. Retrying after interruption performs the same
    comparison and returns the same result.
    """
    _need(isinstance(pin, dict) and pin.get('schema') == 'retained-aida-owner-resync/v1'
          and isinstance(retained, dict)
          and retained.get('schema') == 'retained-aida-private-status/v1'
          and isinstance(snapshot_manifest, dict) and isinstance(source_map, dict)
          and isinstance(status, dict) and isinstance(target, dict)
          and isinstance(owner, dict), 'resync inputs incomplete')
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
          and application.get('owner_store_revision') == owner['store_revision']
          and application.get('pending_proposals') == owner.get('proposal_count')
          and application.get('provider_calls_recorded') == 0
          and application.get('publication_status') == 'private',
          'canonical, owner, or private application changed')
    return {'schema': 'retained-aida-owner-resync-receipt/v1',
            'outcome': 'unchanged', 'contract_sha256': _sha(pin),
            'status_sha256': pin['status_sha256'],
            'canonical_state_sha256': pin['canonical_state_sha256'],
            'owner_state_sha256': pin['owner_state_sha256']}
