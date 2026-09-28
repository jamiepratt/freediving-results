#!/usr/bin/env python3
"""Reconcile the B45 worker projection's retained source and parser gaps.

Reads immutable private inputs and writes a deterministic private supplement.
Assessments are evidence dispositions, not owner approvals or attempt counts.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def encoded(document):
    return (json.dumps(document, sort_keys=True, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def need(condition, message):
    if not condition:
        raise ValueError(message)


def checked_assessments(assessments, expected, label, fields):
    need(isinstance(assessments, dict), f'{label} must be an object')
    need(set(assessments) == expected, f'{label} coverage differs: missing={sorted(expected - set(assessments))} extra={sorted(set(assessments) - expected)}')
    for key, value in assessments.items():
        need(isinstance(value, dict) and set(value) == set(fields), f'{label} {key} needs exactly {fields}')
        need(all(isinstance(value[field], str) and value[field].strip() for field in fields),
             f'{label} {key} has empty assessment field')


def build_supplement(projection, assessment, payload, index_path,
                     *, projection_bytes=None, assessment_bytes=None):
    """Return a complete, deterministic gap supplement after integrity checks."""
    need(projection.get('schema') == 'census-evidence/v1', 'unsupported projection schema')
    need(isinstance(assessment, dict), 'assessment must be an object')
    sources = projection['sources']
    positions = projection['positions']
    source_gaps = [item for item in sources if item.get('source_status') == 'no_imported_extraction']
    unparsed = [item for item in positions if item.get('status') == 'unparsed']
    need(len(source_gaps) == 16, 'expected exactly 16 no-import source hashes')
    need(len(unparsed) == 4, 'expected exactly four unparsed imported positions')
    source_hashes = {item['original_sha256'] for item in source_gaps}
    row_ids = {item['id'] for item in unparsed}
    need(len(source_hashes) == 16 and len(row_ids) == 4, 'duplicate gap identity')
    checked_assessments(assessment.get('source_dispositions'), source_hashes,
                        'source disposition', ('disposition', 'evidence', 'next_step'))
    checked_assessments(assessment.get('row_dispositions'), row_ids,
                        'row disposition', ('disposition', 'failure_reason', 'next_step'))

    index_bytes = Path(index_path).read_bytes()
    index_hash = sha_bytes(index_bytes)
    provenance = projection['projection_provenance']
    need(index_hash == provenance['bundle_index_sha256'], 'bundle index differs from projection')
    index = json.loads(index_bytes)
    indexed_files = index['files']
    source_records = []
    for source in sorted(source_gaps, key=lambda item: item['original_sha256']):
        source_hash = source['original_sha256']
        need(source['id'] == f'sha256:{source_hash}', 'source id/hash mismatch')
        relative = f'archive/objects/{source_hash}'
        info = indexed_files.get(relative)
        need(isinstance(info, dict) and info.get('sha256') == source_hash,
             f'source object absent or mismatched in bundle index: {source_hash}')
        data = (Path(payload) / relative).read_bytes()
        need(sha_bytes(data) == source_hash and len(data) == info.get('bytes'),
             f'source object hash mismatch: {source_hash}')
        source_records.append({'source': source, 'bundle_path': relative,
                               'verified_sha256': source_hash, 'bytes': len(data),
                               'assessment': assessment['source_dispositions'][source_hash]})

    row_records = []
    for position in sorted(unparsed, key=lambda item: item['id']):
        need(position.get('observation_refs'), f'unparsed position has no imported observation: {position["id"]}')
        row_records.append({'position': position,
                            'assessment': assessment['row_dispositions'][position['id']]})
    counts = provenance['reconciliation']
    need(counts['sources'] == len(sources) and counts['positions'] == len(positions),
         'projection source or position count differs from provenance')
    need(counts['observation_versions'] == sum(len(item['observation_refs']) for item in positions),
         'projection observation count differs from provenance')
    pbytes = projection_bytes if projection_bytes is not None else encoded(projection)
    abytes = assessment_bytes if assessment_bytes is not None else encoded(assessment)
    status_counts = {status: sum(item.get('status') == status for item in positions)
                     for status in ('parsed', 'unparsed', 'quarantined')}
    need(sum(status_counts.values()) == len(positions), 'projection has unknown position status')
    return {'schema': 'worker-gap-reconciliation/v1', 'cutoff': projection['cutoff'],
            'input_sha256': {'projection': sha_bytes(pbytes), 'assessment': sha_bytes(abytes),
                             'bundle_index': index_hash},
            'projection_counts': counts, 'position_status_counts': status_counts,
            'source_gaps': source_records,
            'unparsed_rows': row_records,
            'retained_only_artifacts': provenance['retained_only_artifacts'],
            'retained_only_jobs': provenance['retained_only_jobs'],
            'retained_only_candidates': provenance['retained_only_candidates'],
            'distinct_attempts': None}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('projection', type=Path)
    parser.add_argument('assessment', type=Path)
    parser.add_argument('bundle_payload', type=Path)
    parser.add_argument('bundle_index', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args(argv)
    try:
        root = Path(__file__).resolve().parents[1]
        destination = args.output.resolve()
        if destination.is_relative_to(root):
            ignored = subprocess.run(['git', '-C', str(root), 'check-ignore', '-q', str(destination)],
                                     check=False)
            need(ignored.returncode == 0, 'private output inside repository must be ignored by Git')
        projection_bytes = args.projection.read_bytes()
        assessment_bytes = args.assessment.read_bytes()
        doc = build_supplement(json.loads(projection_bytes), json.loads(assessment_bytes),
                               args.bundle_payload, args.bundle_index,
                               projection_bytes=projection_bytes, assessment_bytes=assessment_bytes)
        output_bytes = encoded(doc)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(output_bytes)
        print(json.dumps({'output': str(args.output), 'sha256': sha_bytes(output_bytes),
                          'source_gaps': len(doc['source_gaps']),
                          'unparsed_rows': len(doc['unparsed_rows'])}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'gap reconciliation rejected: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
