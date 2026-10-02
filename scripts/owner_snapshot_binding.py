#!/usr/bin/env python3
"""Bind reconciliation evidence to one hash-verified snapshot source position."""

import json
import sys

try:
    from scripts.unified_evidence_query import SnapshotQuery
except ModuleNotFoundError:
    from unified_evidence_query import SnapshotQuery


REVISION_KEYS = ('job_id', 'ordinal', 'candidate_id', 'artifact_sha256',
                 'source_sha256', 'parser_version')


def _require(condition, reason):
    if not condition:
        raise ValueError(reason)


def _sha(value):
    return isinstance(value, str) and len(value) == 64 and all(
        digit in '0123456789abcdef' for digit in value)


def _revision(value):
    _require(isinstance(value, dict) and all(key in value for key in REVISION_KEYS),
             'incomplete observation revision')
    _require(all(_sha(value[key]) for key in ('job_id', 'artifact_sha256', 'source_sha256'))
             and type(value['ordinal']) is int and value['ordinal'] >= 0
             and isinstance(value['candidate_id'], str) and value['candidate_id']
             and isinstance(value['parser_version'], str) and value['parser_version'],
             'invalid observation revision')
    return {key: value[key] for key in REVISION_KEYS}


def load_verified_bindings(directory, evidence_positions, observation_revisions):
    """Return one exact candidate_position per evidence ID and PG observation.

    The caller obtains observation_revisions from immutable PostgreSQL rows.
    This function verifies the snapshot bytes before matching any position.
    """
    _require(isinstance(evidence_positions, list) and evidence_positions,
             'evidence positions required')
    _require(isinstance(observation_revisions, list) and observation_revisions,
             'observation revisions required')
    revisions = {}
    for supplied in observation_revisions:
        revision = _revision(supplied)
        position = (revision['job_id'], revision['ordinal'])
        _require(position not in revisions, 'duplicate observation revision')
        revisions[position] = revision
    with SnapshotQuery(directory) as snapshot:
        records = []
        for row in snapshot.db.execute(
                "SELECT record_id,source_name,kind,raw_json,source_object_id,parser_version "
                "FROM records WHERE kind='candidate_position'"):
            raw = json.loads(row['raw_json'])
            _require(isinstance(raw, dict), 'malformed snapshot candidate')
            refs = raw.get('observation_refs') or raw.get('imported_observation_refs') or []
            _require(isinstance(refs, list), 'malformed snapshot observation refs')
            records.append((row, raw, refs))
        bindings = {}
        verified = {}
        used_records = set()
        for evidence in evidence_positions:
            _require(isinstance(evidence, dict), 'invalid evidence position')
            evidence_id = evidence.get('evidence_id')
            position = (evidence.get('job_id'), evidence.get('ordinal'))
            _require(isinstance(evidence_id, str) and evidence_id and
                     evidence_id not in bindings and position in revisions,
                     'unbound evidence position')
            revision = revisions[position]
            matches = []
            for row, raw, refs in records:
                matching_refs = [ref for ref in refs if isinstance(ref, dict) and
                                 all(ref.get(key) == revision[key] for key in REVISION_KEYS)]
                if matching_refs:
                    _require(len(matching_refs) == 1, 'duplicate observation ref in record')
                    source_object = row['source_object_id'] or ''
                    source_hash = raw.get('source_sha256') or source_object.removeprefix('sha256:')
                    _require(source_hash == revision['source_sha256'] and
                             row['parser_version'] == revision['parser_version'],
                             'snapshot source version differs from observation')
                    matches.append((row, raw))
            _require(len(matches) == 1, 'observation has missing or ambiguous snapshot record')
            row, raw = matches[0]
            record_id = row['record_id']
            _require(_sha(record_id) and record_id not in used_records,
                     'duplicate or invalid snapshot record')
            used_records.add(record_id)
            name = raw.get('name') or raw.get('athlete_name')
            source_value = raw.get('source_value') or raw.get('raw_value') or raw.get('value')
            record = {'record-id': record_id, 'job-id': revision['job_id'],
                      'ordinal': revision['ordinal'], 'candidate-id': revision['candidate_id'],
                      'artifact-sha256': revision['artifact_sha256'],
                      'source-sha256': revision['source_sha256'],
                      'parser-version': revision['parser_version'],
                      'source-name': row['source_name']}
            if isinstance(name, str) and name:
                record['athlete-name'] = name
            if isinstance(source_value, str) and source_value:
                record['source-value'] = source_value
            bindings[evidence_id] = {'evidence-id': evidence_id,
                                     'snapshot-record-id': record_id,
                                     'job-id': revision['job_id'],
                                     'ordinal': revision['ordinal']}
            verified[record_id] = record
        return {'snapshot_sha256': snapshot.manifest['snapshot_sha256'],
                'evidence_bindings': bindings,
                'verified_snapshot_records': verified}


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2:
        raise SystemExit('usage: owner_snapshot_binding.py SNAPSHOT_DIR INPUT_JSON')
    with open(argv[1], encoding='utf-8') as source:
        request = json.load(source)
    result = load_verified_bindings(argv[0], request['evidence_bindings'],
                                    request['observation_revisions'])
    print(json.dumps(result, sort_keys=True, separators=(',', ':')))


if __name__ == '__main__':
    main()
