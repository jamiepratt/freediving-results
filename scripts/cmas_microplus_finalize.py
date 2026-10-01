#!/usr/bin/env python3
"""Replace the initial Nordic PDF gap with its audited final-results positions."""

import argparse
import hashlib
import json
import os
from pathlib import Path


INITIAL_SHA256 = '995edfa624624e0059ddf12e3a5be52a4c977cf672a38e390ffe1127e18dcfb2'
NORDIC_SHA256 = '6fe02528a2e744958b7428d9731ee96817363c76fe8a91d190998b5f653d98e9'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def build(initial_path, reconciliation_path,
          expected_initial=INITIAL_SHA256, expected_reconciliation=NORDIC_SHA256):
    initial_bytes = Path(initial_path).read_bytes()
    reconciliation_bytes = Path(reconciliation_path).read_bytes()
    if sha(initial_bytes) != expected_initial or sha(reconciliation_bytes) != expected_reconciliation:
        raise ValueError('input packet hash mismatch')
    initial = json.loads(initial_bytes)
    nordic = json.loads(reconciliation_bytes)
    if (initial.get('schema') != 'cmas-microplus-private-census/v1'
            or nordic.get('schema') != 'nordic-final-pdf-reconciliation/v1'):
        raise ValueError('unexpected packet schema')
    source_id = nordic['source']['id']
    if len([s for s in initial['sources'] if s['id'] == source_id and s['kind'] == 'result_pdf_supporting']) != 1:
        raise ValueError('Nordic PDF source not in initial census')
    api_by_id = {row['id']: row for row in initial['positions']
                 if row['raw_fields']['DCCmpID'] == 33}
    pdf_by_id = {row['id']: row for row in nordic['positions']}
    relationships = nordic['relationships']
    if (len(api_by_id) != 92 or len(pdf_by_id) != 76 or len(relationships) != 76
            or {rel['pdf_position_id'] for rel in relationships} != set(pdf_by_id)
            or len({rel['api_position_id'] for rel in relationships}) != 76
            or any(rel['api_position_id'] not in api_by_id
                   or pdf_by_id[rel['pdf_position_id']]['source_object_id'] != source_id
                   or api_by_id[rel['api_position_id']]['raw_fields']['ResID'] != rel['api_res_id']
                   for rel in relationships)):
        raise ValueError('Nordic PDF/API relationship mismatch')
    unmatched = set(api_by_id) - {rel['api_position_id'] for rel in relationships}
    if len(unmatched) != 16 or unmatched != {row['id'] for row in nordic['api_only']}:
        raise ValueError('Nordic API-only rows mismatch')
    prior_gap = 'nordic-pdf-positions-unreconciled'
    if len([gap for gap in initial['gaps'] if gap['id'] == prior_gap]) != 1:
        raise ValueError('expected one initial Nordic PDF gap')
    output = dict(initial)
    output['schema'] = 'cmas-microplus-private-census/v2'
    output['prior_packet_sha256'] = expected_initial
    output['gaps'] = [gap for gap in initial['gaps'] if gap['id'] != prior_gap]
    output['pdf_positions'] = nordic['positions']
    output['relationships'] = relationships
    output['reconciliation'] = {
        'nordic_pdf_packet_sha256': expected_reconciliation,
        'nordic_pdf_source_sha256': nordic['source_sha256'],
        'matched_pdf_positions': 76,
        'api_only_position_ids': sorted(unmatched),
        'different_printed_vs_unit_ranks': nordic['counts']['different_printed_vs_unit_ranks'],
        'same_attempt': 'unreviewed',
    }
    output['source_relationship_status'] = (
        'Nordic PDF fields match 76 API rows; 16 API rows are outside the PDF final-results view; '
        'sporting-attempt identity remains unreviewed')
    output['counts'] = dict(initial['counts'])
    output['counts'].update({
        'api_positions': len(initial['positions']),
        'pdf_positions': len(nordic['positions']),
        'source_positions': len(initial['positions']) + len(nordic['positions']),
        'pdf_api_relationships': len(relationships),
        'nordic_api_only_positions': len(unmatched),
        'gaps': len(output['gaps']),
    })
    return output


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initial', required=True, type=Path)
    parser.add_argument('--reconciliation', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    packet = build(args.initial, args.reconciliation)
    raw = (json.dumps(packet, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()
    args.output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    args.output.write_bytes(raw)
    args.output.chmod(0o600)
    print(json.dumps({'output': str(args.output), 'sha256': sha(raw), **packet['counts']}, sort_keys=True))


if __name__ == '__main__':
    main()
