"""Verify a local reconciliation export against an immutable owner snapshot.

Only a trusted private caller can invoke this adapter. Publisher input cannot
register decisions, and the snapshot is opened read-only with hash verification.
"""

import hashlib
import json

from unified_evidence_query import SnapshotQuery


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


def _require(condition, reason):
    if not condition:
        raise ValueError(reason)


def _source_hash(record):
    raw = record['raw']
    source_object = record.get('source_object_id') or ''
    return raw.get('source_sha256') or record.get('source_sha256') or source_object.removeprefix('sha256:')


def _verify_evidence(snapshot, item, binding):
    revision = binding['observation_revision']
    _require(item['id'] == binding['snapshot_record_id'], 'snapshot record mapping changed')
    _require(item['version'] == revision, 'observation version changed')
    _require(item['citation']['evidence_id'] == binding['evidence_id'], 'source evidence ID changed')
    _require(item['citation']['observation_revision'] == revision, 'citation revision changed')
    record = snapshot.detail(item['id'])
    _require(record is not None, 'snapshot record absent')
    _require(record['kind'] == 'candidate_position', 'snapshot record is not a source position')
    _require(_source_hash(record) == revision['source_sha256'], 'source hash changed')
    source_citation = item['citation']['source_citation']
    _require(isinstance(source_citation, dict) and
             source_citation.get('source-sha256') == revision['source_sha256'],
             'source citation changed')
    raw = record['raw']
    refs = raw.get('observation_refs') or raw.get('imported_observation_refs') or []
    matches = [ref for ref in refs if all(ref.get(key) == revision[key] for key in (
        'job_id', 'ordinal', 'candidate_id', 'artifact_sha256', 'parser_version'))]
    _require(len(matches) == 1, 'snapshot lacks exact immutable observation reference')


def _verify_proposal(snapshot, proposal, run_revision):
    binding = proposal['canonical_binding']
    _require(binding['decision_id'] == proposal['id'], 'decision identity changed')
    _require(binding['reconciliation_run_revision'] == run_revision and
             isinstance(run_revision, int) and run_revision >= 1 and
             isinstance(binding['reconciliation_event_id'], str) and
             binding['reconciliation_event_id'], 'reconciliation run revision changed')
    evidence = proposal['evidence']
    bindings = binding['evidence_bindings']
    _require(isinstance(evidence, list) and evidence and len(evidence) == len(bindings),
             'incomplete evidence bindings')
    _require(binding['observation_revisions'] == [entry['observation_revision'] for entry in bindings],
             'observation revision set changed')
    _require(len({entry['snapshot_record_id'] for entry in bindings}) == len(bindings),
             'duplicate snapshot record mapping')
    for item, entry in zip(evidence, bindings):
        _verify_evidence(snapshot, item, entry)


def register_verified_export(store, snapshot_directory, envelope):
    """Verify all bindings before the first idempotent DecisionStore.register call.

    A verified snapshot must already be bound by DecisionStore.bind_verified_snapshot.
    This never creates a binding, so an old export cannot replace a newer one.
    """
    _require(isinstance(envelope, dict), 'export must be an object')
    proposals = envelope.get('proposals')
    _require(isinstance(proposals, list) and proposals, 'export requires proposals')
    with SnapshotQuery(snapshot_directory) as snapshot:
        _require(snapshot.manifest['snapshot_sha256'] == envelope.get('snapshot_sha256'),
                 'snapshot SHA256 changed')
        row = store.db.execute('SELECT revision,snapshot_sha256 FROM bindings '
                               'ORDER BY revision DESC LIMIT 1').fetchone()
        _require(row is not None and row['revision'] == envelope.get('binding_revision') and
                 row['snapshot_sha256'] == envelope['snapshot_sha256'],
                 'decision store binding changed')
        ids = [proposal['id'] for proposal in proposals]
        _require(len(ids) == len(set(ids)), 'duplicate proposal ID')
        for proposal in proposals:
            _verify_proposal(snapshot, proposal, envelope.get('reconciliation_run_revision'))
    return store.register_batch(envelope['snapshot_sha256'], proposals,
                                idempotency_key='reconciliation-export:' + _digest(proposals))
