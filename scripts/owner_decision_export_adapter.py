"""Verify a local reconciliation export against an immutable owner snapshot.

Only a trusted private caller can invoke this adapter. Publisher input cannot
register decisions, and the snapshot is opened read-only with hash verification.
"""

import argparse
import hashlib
import json
import os
import stat
import subprocess
from pathlib import Path

from unified_evidence_query import SnapshotQuery
from aida_snapshot_observations import (
    ADAPTER_VERSION as AIDA_VERSION, EVENT_ADAPTER_VERSION as AIDA_EVENT_VERSION,
    LEGACY_ADAPTER_VERSION as AIDA_LEGACY_VERSION,
    load_source_observations as load_aida)
from cmas_microplus_snapshot_observations import (
    ADAPTER_VERSION as MICROPLUS_VERSION,
    ATTEMPT_ADAPTER_VERSION as MICROPLUS_ATTEMPT_VERSION,
    load_source_observations as load_microplus,
    load_attempt_evidence as load_microplus_attempt)


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


def _verify_evidence(snapshot, snapshot_directory, item, binding,
                     recovered_packet_paths=None):
    revision = binding['observation_revision']
    _require(item['id'] == binding['snapshot_record_id'], 'snapshot record mapping changed')
    _require(item['version'] == revision, 'observation version changed')
    _require(item['citation']['evidence_id'] == binding['evidence_id'], 'source evidence ID changed')
    _require(item['citation']['observation_revision'] == revision, 'citation revision changed')
    if isinstance(revision, dict) and revision.get('kind') == 'source-derived':
        _require(revision.get('snapshot_sha256') == snapshot.manifest['snapshot_sha256'] and
                 revision.get('snapshot_record_id') == item['id'] and
                 isinstance(revision.get('source_name'), str),
                 'source observation snapshot binding changed')
        version = revision.get('adapter_version')
        _require(version in (MICROPLUS_VERSION, MICROPLUS_ATTEMPT_VERSION,
                             AIDA_VERSION, AIDA_EVENT_VERSION, AIDA_LEGACY_VERSION),
                 'unsupported source observation adapter')
        if version == MICROPLUS_ATTEMPT_VERSION:
            result = load_microplus_attempt(snapshot_directory, [revision['source_name']],
                                            record_ids=[item['id']])
            matches = [view for view in result['evidence']['observation-versions']
                       if view['snapshot-record-id'] == item['id'] and
                       view['observation-revision'] == revision]
            _require(len(matches) == 1 and
                     matches[0]['id'] == binding['evidence_id'],
                     'attempt view differs from verified original')
            _require(item['citation']['source_citation'] == {
                'source-sha256': revision['source_sha256'], 'locator': revision['citation']},
                'attempt view citation changed')
            return
        loader = load_microplus if version == MICROPLUS_VERSION else load_aida
        kwargs = {'adapter_version': version}
        if loader is load_aida and recovered_packet_paths:
            recovered = recovered_packet_paths.get(revision['source_name'])
            if recovered is not None:
                kwargs['recovered_packet_paths'] = {revision['source_name']: recovered}
        result = loader(snapshot_directory, [revision['source_name']], **kwargs)
        matches = [observation for observation in result['observations']
                   if observation['snapshot_record_id'] == item['id']]
        _require(len(matches) == 1 and
                 matches[0]['source_observation_ref'] == revision,
                 'source observation differs from verified original')
        citation = item['citation']['source_citation']
        _require(isinstance(citation, dict) and
                 citation.get('source-sha256') == revision['source_sha256'] and
                 citation.get('locator') == revision['citation'],
                 'source observation citation changed')
        return
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


def _verify_proposal(snapshot, snapshot_directory, proposal, run_revision,
                     recovered_packet_paths=None):
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
    _require(all(isinstance(entry.get('observation_revision'), dict) for entry in bindings),
             'invalid observation revision')
    identity = [(entry['snapshot_record_id'], _digest(entry['observation_revision']))
                for entry in bindings]
    _require(len(set(identity)) == len(identity), 'duplicate observation mapping')
    records = [record_id for record_id, _ in identity]
    repeated = {record_id for record_id in records if records.count(record_id) > 1}
    _require(all(entry['observation_revision'].get('adapter_version') == MICROPLUS_ATTEMPT_VERSION
                 for entry in bindings if entry['snapshot_record_id'] in repeated),
             'duplicate snapshot record mapping')
    for item, entry in zip(evidence, bindings):
        _verify_evidence(snapshot, snapshot_directory, item, entry,
                         recovered_packet_paths)


def build_verified_microplus_attempt_export(store, snapshot_directory, source_names,
                                            record_id, *, decision_id,
                                            reconciliation_run_revision,
                                            reconciliation_event_id):
    """Build a pending owner proposal for two acquired views of one frozen result.

    The caller supplies an existing reconciliation event identity. All evidence
    revisions and citations are derived from verified originals, without a
    PostgreSQL ingestion job reference. Registration still replays those bytes.
    """
    _require(isinstance(decision_id, str) and decision_id and
             isinstance(reconciliation_run_revision, int) and
             reconciliation_run_revision >= 1 and
             isinstance(reconciliation_event_id, str) and reconciliation_event_id,
             'reconciliation decision and event required')
    result = load_microplus_attempt(snapshot_directory, source_names,
                                    record_ids=[record_id])
    views = result['evidence']['observation-versions']
    _require(len(views) == 2 and all(view['snapshot-record-id'] == record_id for view in views),
             'two cited views of one Microplus result required')
    binding = store._binding()
    _require(binding is not None and binding['snapshot_sha256'] == result['snapshot_sha256'],
             'decision store binding changed')
    current = binding['observation_refs'].get(record_id) if binding['observation_refs'] else None
    _require(current is not None and
             {(_digest(view['observation-revision']), view['id']) for view in views} ==
             {(_digest(view['observation_revision']), view['evidence_id'])
              for view in current.get('attempt_view_refs', [])},
             'attempt views absent from active binding')
    revisions = [view['observation-revision'] for view in views]
    evidence = [{'id': record_id, 'version': revision,
                 'citation': {'evidence_id': view['id'],
                              'observation_revision': revision,
                              'source_citation': {'source-sha256': revision['source_sha256'],
                                                  'locator': revision['citation']}}}
                for view, revision in zip(views, revisions)]
    bindings = [{'evidence_id': view['id'], 'snapshot_record_id': record_id,
                 'observation_revision': revision}
                for view, revision in zip(views, revisions)]
    scope = views[0]['scope']
    _require(all(view['scope'] == scope for view in views),
             'Microplus cited views disagree on attempt scope')
    proposal = {
        'id': decision_id, 'type': 'same_attempt', 'subject_id': record_id,
        'source_name': ', '.join(source_names),
        'original': {'publisher_result': scope, 'cited_views': [view['id'] for view in views]},
        'proposed': {'pair': [view['id'] for view in views], 'action': 'same_attempt'},
        'selected_option': 'same_attempt', 'competing_options': ['distinct_attempts'],
        'evidence': evidence, 'supporting_evidence': [], 'conflicting_evidence': [],
        'depends_on': [], 'groups': [f"microplus-result:{scope['event']}:{scope['attempt']}"],
        'provider_confidence': None, 'score': None,
        'rule_version': MICROPLUS_ATTEMPT_VERSION, 'model_version': None,
        'policy_version': 'owner-attempt-review/1', 'status': 'pending',
        'canonical_binding': {
            'decision_id': decision_id,
            'reconciliation_run_revision': reconciliation_run_revision,
            'reconciliation_event_id': reconciliation_event_id,
            'observation_revisions': revisions, 'evidence_bindings': bindings}}
    return {'snapshot_sha256': result['snapshot_sha256'],
            'binding_revision': binding['revision'], 'store_revision': store.revision,
            'reconciliation_run_revision': reconciliation_run_revision,
            'proposals': [proposal]}


def register_verified_export(store, snapshot_directory, envelope, *,
                             recovered_packet_paths=None):
    """Verify all bindings before the first idempotent DecisionStore.register call.

    A verified snapshot must already be bound by DecisionStore.bind_verified_snapshot.
    This never creates a binding, so an old export cannot replace a newer one.
    Recovered AIDA packet paths are private caller input; source replay checks
    their frozen hashes and the original HTML before registration.
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
        _require(type(envelope.get('store_revision')) is int and
                 envelope['store_revision'] >= row['revision'],
                 'decision store revision absent from export')
        ids = [proposal['id'] for proposal in proposals]
        _require(len(ids) == len(set(ids)), 'duplicate proposal ID')
        for proposal in proposals:
            _verify_proposal(snapshot, snapshot_directory, proposal,
                             envelope.get('reconciliation_run_revision'),
                             recovered_packet_paths)
    return store.register_batch(envelope['snapshot_sha256'], proposals,
                                idempotency_key='reconciliation-export:' + _digest(proposals),
                                expected_revision=envelope['store_revision'])


def deliver_verified_owner_events(store, config_path, *, limit=100):
    """Run the private local importer, checkpointing each committed destination.

    The Clojure command fetches and authenticates the signed event feed itself.
    Its config is private local input; publisher bytes cannot select a command,
    destination, or canonical database. A failed or mismatched receipt leaves
    the corresponding SQLite outbox checkpoint unchanged for a safe retry.
    """
    config = Path(config_path)
    config_stat = config.lstat()
    if (not stat.S_ISREG(config_stat.st_mode) or config_stat.st_uid != os.getuid()
            or config_stat.st_mode & 0o077):
        raise ValueError('owner delivery config must be an owner-only regular file')
    config = config.resolve(strict=True)

    def callback(target):
        def deliver(event):
            args = ['clojure', '-M', '-m', 'freediving.owner-event-delivery',
                    '--config', str(config), '--target', target, '--event-id', event['id'],
                    '--expected-event-stdin']
            completed = subprocess.run(args, capture_output=True, text=True,
                                       input=json.dumps(event, sort_keys=True),
                                       check=True, timeout=120,
                                       cwd=Path(__file__).resolve().parents[1])
            try:
                result = json.loads(completed.stdout)
            except (ValueError, TypeError) as exc:
                raise ValueError('invalid owner delivery receipt') from exc
            if (not isinstance(result, dict) or set(result) != {'target', 'event_id', 'receipt'}
                    or result['target'] != target or result['event_id'] != event['id']
                    or not isinstance(result['receipt'], str)
                    or not 1 <= len(result['receipt']) <= 512
                    or '\x00' in result['receipt']):
                raise ValueError('invalid owner delivery receipt')
            return result['receipt']
        return deliver

    return store.deliver_human_events([('flow-ledger', callback('flow-ledger')),
                                       ('postgresql', callback('postgresql'))], limit=limit)


def main(argv=None):
    parser = argparse.ArgumentParser(description='Private local owner event delivery')
    subcommands = parser.add_subparsers(dest='command', required=True)
    delivery = subcommands.add_parser('deliver')
    delivery.add_argument('--decision-db', required=True)
    delivery.add_argument('--config', required=True)
    delivery.add_argument('--limit', type=int, default=100)
    args = parser.parse_args(argv)
    db_path = Path(args.decision_db)
    if not stat.S_ISREG(db_path.lstat().st_mode):
        raise ValueError('owner decision DB must be an existing regular file')
    from owner_decision_store import DecisionStore
    with_store = DecisionStore(db_path)
    try:
        result = deliver_verified_owner_events(with_store, args.config, limit=args.limit)
    finally:
        with_store.close()
    print(json.dumps(result, sort_keys=True))
    return 0 if result['status'] == 'complete' else 2


if __name__ == '__main__':
    raise SystemExit(main())
