#!/usr/bin/env python3
"""Build a source-bound private census packet from retained CMAS timing JSON."""

import argparse
import hashlib
import json
from pathlib import Path


COMPETITIONS = (28, 33, 34, 35)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def retained(directory, name):
    path = directory / name
    raw = path.read_bytes()
    receipt = json.loads(path.with_suffix('.receipt.json').read_bytes())
    if receipt['status'] != 200 or receipt['sha256'] != digest(raw) or receipt['bytes'] != len(raw):
        raise ValueError(f'invalid source receipt: {name}')
    return json.loads(raw), receipt


def units_in(competition):
    for discipline in competition['Disciplines']:
        for category in discipline['Categories']:
            for event in category['Events']:
                for phase in event['Phases']:
                    for unit in phase.get('Units', []):
                        yield unit['UtID'], unit


def build(directory):
    sources = []
    positions = []
    aggregate_rows = []
    gaps = []
    expected_units = {}
    titles = {}
    date_ranges = {}
    appearances = {}
    for cid in COMPETITIONS:
        name = f'competition-{cid}.json'
        data, receipt = retained(directory, name)
        if len(data) != 1 or data[0]['CmpID'] != cid:
            raise ValueError(f'competition identity mismatch: {cid}')
        competition = data[0]
        titles[cid] = competition['CmpTitLongDescr']
        date_ranges[cid] = (competition['CmpStartDate'][:10], competition['CmpEndDate'][:10])
        sources.append({'id': f'sha256:{receipt["sha256"]}', 'kind': 'competition_metadata',
                        'competition_id': cid, 'url': receipt['final_url'],
                        'sha256': receipt['sha256'], 'bytes': receipt['bytes']})
        for uid, unit in units_in(competition):
            if uid in expected_units:
                raise ValueError(f'duplicate unit: {uid}')
            expected_units[uid] = (cid, unit['UtStartDate'][:10])
        docs, doc_receipt = retained(directory, f'competition-{cid}-documents.json')
        sources.append({'id': f'sha256:{doc_receipt["sha256"]}', 'kind': 'document_index',
                        'competition_id': cid, 'url': doc_receipt['final_url'],
                        'sha256': doc_receipt['sha256'], 'bytes': doc_receipt['bytes'],
                        'document_count': len(docs)})
        view_kind = 'cumulative' if cid in (28, 34) else 'schedule'
        view, view_receipt = retained(directory, f'competition-{cid}-{view_kind}.json')
        if not isinstance(view, list):
            raise ValueError(f'{view_kind} is not an array: {cid}')
        if view_kind == 'schedule' and {row.get('UtID') for row in view} != {
                uid for uid, (unit_cid, _) in expected_units.items() if unit_cid == cid}:
            raise ValueError(f'schedule unit set mismatch: {cid}')
        view_source_id = f'sha256:{view_receipt["sha256"]}'
        sources.append({'id': view_source_id, 'kind': view_kind,
                        'competition_id': cid, 'url': view_receipt['final_url'],
                        'sha256': view_receipt['sha256'], 'bytes': view_receipt['bytes'],
                        'row_count': len(view)})
        if view_kind == 'cumulative':
            for index, row in enumerate(view):
                if row.get('DCCmpID') != cid:
                    raise ValueError(f'cumulative row identity mismatch: {cid}/{index}')
                aggregate_rows.append({
                    'id': f'cmas-microplus-cumulative:{cid}:{index}',
                    'source_object_id': view_source_id,
                    'event_name': titles[cid],
                    'date_from': date_ranges[cid][0], 'date_to': date_ranges[cid][1],
                    'discipline': row.get('EvLongDescr'),
                    'category': row.get('AGCodeDescr'),
                    'review_status': 'unreviewed',
                    'disposition': 'cumulative_ranking_not_attempt',
                    'citation': {'url': view_receipt['final_url'],
                                 'source_sha256': view_receipt['sha256'],
                                 'json_pointer': f'/{index}'},
                    'raw_fields': row,
                })
        if cid == 33:
            if len(docs) != 1 or docs[0]['DocTypeCode'] != 'RES':
                raise ValueError('Nordic result document changed')
            pdf_path = directory / 'competition-33-results.pdf'
            pdf = pdf_path.read_bytes()
            pdf_receipt = json.loads(pdf_path.with_suffix('.receipt.json').read_bytes())
            if not pdf.startswith(b'%PDF-') or digest(pdf) != pdf_receipt['sha256'] or len(pdf) != pdf_receipt['bytes']:
                raise ValueError('Nordic PDF receipt mismatch')
            sources.append({'id': f'sha256:{pdf_receipt["sha256"]}', 'kind': 'result_pdf_supporting',
                            'competition_id': cid, 'url': pdf_receipt['final_url'],
                            'sha256': pdf_receipt['sha256'], 'bytes': pdf_receipt['bytes']})
            gaps.append({'id': 'nordic-pdf-positions-unreconciled', 'competition_id': cid,
                         'reason': 'PDF positions not transcribed or reconciled against API rows',
                         'source_object_id': f'sha256:{pdf_receipt["sha256"]}'})
    transport_rows = 0
    for uid, (cid, scheduled_date) in sorted(expected_units.items()):
        name = f'unit-{uid}-results.json'
        rows, receipt = retained(directory, name)
        if not isinstance(rows, list) or not receipt['final_url'].endswith(f'/api/units/{uid}/results'):
            raise ValueError(f'unit response mismatch: {uid}')
        source_id = f'sha256:{receipt["sha256"]}'
        sources.append({'id': source_id, 'kind': 'unit_results', 'competition_id': cid,
                        'unit_id': uid, 'url': receipt['final_url'], 'sha256': receipt['sha256'],
                        'bytes': receipt['bytes'], 'row_count': len(rows)})
        if not rows:
            gaps.append({'id': f'unit-{uid}-empty', 'competition_id': cid, 'unit_id': uid,
                         'reason': 'publisher unit results array empty', 'source_object_id': source_id})
        for index, row in enumerate(rows):
            if row.get('UtID') not in expected_units or row.get('DCCmpID') != cid or row.get('ResID') is None:
                raise ValueError(f'row identity mismatch: {uid}/{index}')
            if expected_units[row['UtID']][0] != cid:
                raise ValueError(f'cross-competition unit row: {uid}/{index}')
            key = (cid, row['ResID'])
            citation = {'url': receipt['final_url'], 'source_sha256': receipt['sha256'],
                        'json_pointer': f'/{index}'}
            appearances.setdefault(key, []).append((uid, index, row, source_id, citation))
            transport_rows += 1
    for (cid, res_id), views in sorted(appearances.items()):
        if any(item[2] != views[0][2] for item in views[1:]):
            raise ValueError(f'conflicting API rows for result {cid}/{res_id}')
        views.sort(key=lambda item: (item[0] != item[2]['UtID'], item[0], item[1]))
        requested_uid, index, row, source_id, citation = views[0]
        date = (row.get('EvStartDate') or expected_units[row['UtID']][1])[:10]
        positions.append({
                'id': f'cmas-microplus:{cid}:result:{res_id}',
                'source_object_id': source_id,
                'event_name': titles[cid], 'event_date': date,
                'discipline': row.get('EvShortDescr'),
                'category': row.get('AGCodeDescr') or row.get('CatLongDescr'),
                'review_status': 'unreviewed',
                'disposition': 'api_transport_result_row',
                'citation': citation,
                'alternate_citations': [item[4] for item in views[1:]],
                'raw_fields': row,
        })
    if len(sources) != 3 * len(COMPETITIONS) + 1 + len(expected_units):
        raise ValueError('source accounting mismatch')
    return {'schema': 'cmas-microplus-private-census/v1', 'issue_namespace': '#55',
            'competition_scope': {'ids': list(COMPETITIONS)},
            'source_relationship_status': 'identical API rows grouped by competition and ResID; sporting attempts unresolved',
            'confirmed_distinct_attempts': None,
            'counts': {'competitions': len(COMPETITIONS), 'units': len(expected_units),
                       'source_objects': len(sources), 'source_positions': len(positions),
                       'transport_rows': transport_rows,
                       'repeated_transport_rows': transport_rows - len(positions),
                       'aggregate_rows': len(aggregate_rows),
                       'empty_units': sum(gap['id'].endswith('-empty') for gap in gaps),
                       'gaps': len(gaps), 'confirmed_distinct_attempts': None},
            'sources': sources, 'positions': positions, 'aggregate_rows': aggregate_rows,
            'gaps': gaps}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    packet = build(args.source_dir)
    raw = (json.dumps(packet, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(raw)
    print(json.dumps({'output': str(args.output), 'sha256': digest(raw), **packet['counts']}, sort_keys=True))


if __name__ == '__main__':
    main()
