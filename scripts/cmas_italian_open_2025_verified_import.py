#!/usr/bin/env python3
"""Verify the retained Italian Open v3 scan packet and stage cited observations.

Second pass: italian-open-2025-second-pass/v1, blind_to_first_pass=true,
transcriber, source_sha256, and one {page,row,fields_raw,uncertain_fields}
for every v3 page/row. Inspections: italian-open-2025-image-inspections/v1,
source_sha256 and records with page,row,reason,render_sha256,bbox,
inspector,fields_raw,uncertain_fields. All fields are raw strings or null.
--compare-only reports required inspections before staging. Neither stage nor
isolated SQLite import grants identity, attempt, review, or publication approval.
"""
import argparse
import json
import math
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile

from cmas_worldcup_2026_verified_import import bound_json, import_stage, require, sha, SHA

SOURCE_SHA256 = 'e8ca68825e29d44a1b13127ab2cab55f6db534b15ae26ff005f13a1ca8ec9d44'
PACKET_SCHEMA = 'italian-open-2025-visual-evidence/v3'
STAGE_SCHEMA = 'italian-open-2025-verified-stage/v1'
ROLES = {'candidate_result': 'event_result_table', 'aggregate': 'aggregate',
         'summary': 'summary', 'duplicate_render': 'duplicate_render'}


def fields_valid(fields, expected_keys=None):
    return (isinstance(fields, dict) and bool(fields)
            and (expected_keys is None or set(fields) == set(expected_keys))
            and all(v is None or isinstance(v, str) for v in fields.values()))


def same_reading(first, second):
    return first == second or (first in (None, '') and second in (None, ''))


def check_bbox(box):
    require(isinstance(box, list) and len(box) == 4
            and all(type(v) in (float, int) for v in box)
            and 0 <= box[0] < box[2] <= 1 and 0 <= box[1] < box[3] <= 1,
            'invalid normalized image bbox')


def receipt_valid(receipt, pdf, source_hash, receipt_path):
    if receipt.get('schema') == 'private-source-bundle/v1':
        matches = [x for x in receipt.get('sources', []) if x.get('id') == f'sha256:{source_hash}']
        require(len(matches) == 1, 'receipt must identify one PDF source')
        item = matches[0]
        acquisition = item.get('receipt', {})
        require(item.get('sha256') == source_hash and item.get('bytes') == len(pdf)
                and item.get('status') == 'included' and item.get('content_type') == 'application/pdf'
                and acquisition.get('acquisition_id') and acquisition.get('retrieved_at'),
                'acquisition receipt mismatch')
        path = (receipt_path.parent / item['object']).resolve()
        require(sha(path.read_bytes()) == source_hash, 'receipt retained object SHA256 mismatch')
    else:
        require(receipt.get('source_sha256') == source_hash and receipt.get('bytes') == len(pdf),
                'receipt source binding mismatch')


def build(args):
    pdf = args.pdf.read_bytes()
    source_hash = sha(pdf)
    require(source_hash == args.expected_source_sha256, 'PDF source SHA256 mismatch')
    packet = bound_json(args.packet, args.packet_sha256, 'first packet')
    second = bound_json(args.second_pass, args.second_pass_sha256, 'second pass')
    receipt = bound_json(args.receipt, args.receipt_sha256, 'receipt')
    require(packet.get('schema') == PACKET_SCHEMA
            and packet.get('source_sha256') == source_hash
            and packet.get('source_bytes') == len(pdf), 'first packet source binding mismatch')
    receipt_valid(receipt, pdf, source_hash, args.receipt)
    require(second.get('schema') == 'italian-open-2025-second-pass/v1'
            and second.get('source_sha256') == source_hash
            and second.get('blind_to_first_pass') is True
            and isinstance(second.get('transcriber'), str) and second['transcriber'].strip(),
            'second pass source or independence attestation missing')
    require(isinstance(args.parser_version, str) and args.parser_version.strip(),
            'parser version required')
    pages = packet.get('pages')
    require(isinstance(pages, list) and pages and len(pages) == len(args.render),
            'render/page coverage mismatch')
    first = {}
    render_hashes = {}
    ids = set()
    for index, (page, path) in enumerate(zip(pages, args.render), 1):
        number = page.get('page')
        require(number == index, 'packet pages must be consecutive')
        render = page.get('render', {})
        require(render.get('format') == 'jpg' and sha(path.read_bytes()) == render.get('sha256'),
                f'page {number}: render SHA256 mismatch')
        require(type(render.get('pixel_width')) is int and render['pixel_width'] > 0
                and type(render.get('pixel_height')) is int and render['pixel_height'] > 0,
                'render dimensions absent')
        render_hashes[number] = render['sha256']
        rows = page.get('rows')
        require(isinstance(rows, list) and rows
                and page.get('visual_row_count') == len(rows)
                and [x.get('row') for x in rows] == list(range(1, len(rows) + 1)),
                f'page {number}: row accounting mismatch')
        for row in rows:
            key = (number, row['row'])
            cite = row.get('citation', {})
            region = cite.get('region') or {}
            require(cite.get('source_sha256') == source_hash and cite.get('page') == number
                    and region.get('units') == 'normalized_image'
                    and region.get('render_sha256') == render['sha256'],
                    f'page {number} row {row["row"]}: source citation mismatch')
            check_bbox(region.get('bbox'))
            require(row.get('disposition') in ROLES and row.get('id', '').startswith('source-position:')
                    and row['id'] not in ids, 'duplicate or invalid source position')
            ids.add(row['id'])
            require(fields_valid(row.get('fields_raw'))
                    and isinstance(row.get('uncertainties'), list), 'first pass raw fields invalid')
            require(all(row.get(name) is None or isinstance(row[name], str)
                        for name in ('penalty_raw', 'status_raw', 'notes_raw')),
                    'raw penalty/status/note invalid')
            first[key] = (row, page)
    counts = packet.get('counts', {})
    expected = {'candidate_result': 'candidate_result_positions',
                'aggregate': 'aggregate_rows_excluded',
                'summary': 'summary_rows_excluded',
                'duplicate_render': 'duplicate_rendered_rows'}
    for role, label in expected.items():
        require(counts.get(label) == sum(x[0]['disposition'] == role for x in first.values()),
                f'{role} source-position accounting mismatch')
    if source_hash == SOURCE_SHA256:
        require(len(first) == 242 and
                [counts[label] for label in expected.values()] == [186, 22, 28, 6],
                'official Italian Open row accounting mismatch')
    for key, (row, _) in first.items():
        if row['disposition'] == 'duplicate_render':
            target = row.get('duplicate_of', {})
            other = (target.get('page'), target.get('row'))
            require(other in first and other != key and first[other][0]['disposition'] != 'duplicate_render'
                    and row['fields_raw'] == first[other][0]['fields_raw']
                    and isinstance(row.get('duplicate_evidence'), str)
                    and row['duplicate_evidence'].strip(),
                    'duplicate render target invalid')
    require(isinstance(second.get('rows'), list), 'second pass rows missing')
    later = {}
    for row in second['rows']:
        key = (row.get('page'), row.get('row'))
        require(key in first and key not in later, 'second pass coverage or duplicate mismatch')
        raw = row.get('fields_raw')
        require(fields_valid(raw, first[key][0]['fields_raw']), 'second pass raw fields invalid')
        uncertain = row.get('uncertain_fields')
        require(isinstance(uncertain, list) and len(set(uncertain)) == len(uncertain)
                and set(uncertain) <= set(raw), 'second pass uncertain fields invalid')
        later[key] = row
    require(set(later) == set(first), 'second pass row coverage mismatch')
    differences = {}
    for key, (row, _) in first.items():
        changed = [field for field in row['fields_raw']
                   if not same_reading(row['fields_raw'][field], later[key]['fields_raw'][field])]
        if changed:
            differences[key] = changed
    agreements = [key for key in first
                  if first[key][0]['disposition'] == 'candidate_result' and key not in differences]
    sample_size = math.ceil(len(agreements) / 10)
    sample = set(sorted(agreements, key=lambda key: (sha(f'{source_hash}:page:{key[0]}:row:{key[1]}'.encode()), key))[:sample_size])
    comparison = {'schema': 'italian-open-2025-comparison/v1',
                  'source_sha256': source_hash, 'first_packet_sha256': args.packet_sha256,
                  'second_pass_sha256': args.second_pass_sha256,
                  'disagreements': [{'page': k[0], 'row': k[1], 'fields': differences[k]}
                                    for k in sorted(differences)],
                  'sampled_agreements': [list(k) for k in sorted(sample)],
                  'sampling': {'method': 'SHA256(source:page:N:row:M) ascending', 'size': sample_size},
                  'counts': {'cited_positions': len(first), 'candidate_result_positions': len(agreements) +
                             sum(first[k][0]['disposition'] == 'candidate_result' for k in differences),
                             'disagreements': len(differences), 'sampled_agreements': sample_size}}
    if args.compare_only:
        return comparison
    require(args.inspections is not None and args.inspections_sha256 is not None,
            'pinned --inspections required for staging')
    inspections = bound_json(args.inspections, args.inspections_sha256, 'inspections')
    require(inspections.get('schema') == 'italian-open-2025-image-inspections/v1'
            and inspections.get('source_sha256') == source_hash
            and isinstance(inspections.get('records'), list), 'inspection artifact invalid')
    checked = {}
    for item in inspections['records']:
        key = (item.get('page'), item.get('row'))
        require(key in first and key not in checked, 'inspection position invalid or duplicate')
        row = first[key][0]
        require(item.get('reason') == ('disagreement' if key in differences else 'agreement_sample')
                and item.get('render_sha256') == render_hashes[key[0]]
                and item.get('bbox') == row['citation']['region']['bbox'],
                'inspection citation or reason mismatch')
        require(isinstance(item.get('inspector'), str) and item['inspector'].strip()
                and item['inspector'] != second['transcriber'], 'independent inspector missing')
        raw = item.get('fields_raw')
        require(fields_valid(raw, row['fields_raw']), 'inspection raw fields invalid')
        uncertain = item.get('uncertain_fields')
        require(isinstance(uncertain, list) and len(set(uncertain)) == len(uncertain)
                and set(uncertain) <= set(raw), 'inspection uncertain fields invalid')
        if key not in differences:
            require(raw == row['fields_raw'], 'agreement inspection contradicts both passes')
        checked[key] = item
    require(set(differences) | sample <= set(checked), 'missing cited source inspection')
    versions, unresolved, non_primary = [], [], []
    for key in sorted(first):
        row, page = first[key]
        inspection = checked.get(key)
        raw = inspection['fields_raw'] if inspection else row['fields_raw']
        position = {'id': row['id'], 'page': key[0], 'row': key[1], 'citation': row['citation']}
        role = ROLES[row['disposition']]
        provenance = {'source_role': role, 'source_position': position,
                      'page_context_raw': {k: page.get(k) for k in
                                           ('event_raw', 'event_date_raw', 'discipline_raw',
                                            'category_raw', 'session_raw', 'heading_raw')},
                      'first_pass_raw_fields': row['fields_raw'],
                      'second_pass_raw_fields': later[key]['fields_raw'],
                      'penalty_raw': row.get('penalty_raw'), 'status_raw': row.get('status_raw'),
                      'notes_raw': row.get('notes_raw'),
                      'first_pass_uncertainties': row['uncertainties'],
                      'second_pass_uncertain_fields': later[key]['uncertain_fields'],
                      'inspection': inspection,
                      'relationship_candidate_of': row.get('relationship_candidate_of'),
                      'relationship_candidate_note': row.get('relationship_candidate_note')}
        if row['disposition'] != 'candidate_result':
            non_primary.append(dict(position, source_role=role, disposition=row['disposition'],
                                    raw_fields=raw, **{k: v for k, v in provenance.items()
                                                       if k not in ('source_role', 'source_position')}))
            continue
        if row['uncertainties'] or later[key]['uncertain_fields'] or (inspection and inspection['uncertain_fields']):
            unresolved.append(dict(position, source_role=role, reason='uncertain raw reading',
                                   raw_fields=raw, **{k: v for k, v in provenance.items()
                                                      if k not in ('source_role', 'source_position')}))
            continue
        identity = [source_hash, row['id'], args.parser_version, args.packet_sha256,
                    args.second_pass_sha256, args.inspections_sha256, raw, provenance]
        versions.append({'id': 'observation-version:' + sha(json.dumps(identity, ensure_ascii=False,
                         sort_keys=True, separators=(',', ':')).encode()),
                         'source_sha256': source_hash, 'source_position': position,
                         'source_role': role, 'parser_version': args.parser_version,
                         'raw_fields': raw, 'interpreted_fields': {}, **{k: v for k, v in provenance.items()
                                                                       if k not in ('source_role', 'source_position')},
                         'verification': 'source_inspected' if inspection else 'agreed_uninspected'})
    require(len(versions) + len(unresolved) + len(non_primary) == len(first),
            'source-position accounting failure')
    return {'schema': STAGE_SCHEMA, 'source_sha256': source_hash,
            'parser_version': args.parser_version,
            'input_sha256': {'first_packet': args.packet_sha256, 'second_pass': args.second_pass_sha256,
                             'receipt': args.receipt_sha256, 'inspections': args.inspections_sha256},
            'render_sha256': [render_hashes[i] for i in sorted(render_hashes)],
            'second_pass_attestation': {'transcriber': second['transcriber'], 'blind_to_first_pass': True},
            'verification': {'disagreements': [list(k) for k in sorted(differences)],
                             'sampled_agreements': [list(k) for k in sorted(sample)]},
            'observation_versions': versions, 'unresolved_positions': unresolved,
            'non_primary_positions': non_primary,
            'counts': {'cited_positions': len(first), 'candidate_result_positions': counts['candidate_result_positions'],
                       'verified_staged_versions': len(versions), 'unresolved_positions': len(unresolved),
                       'non_primary_positions': len(non_primary),
                       'aggregate_rows_excluded': counts['aggregate_rows_excluded'],
                       'summary_rows_excluded': counts['summary_rows_excluded'],
                       'duplicate_rendered_rows': counts['duplicate_rendered_rows'],
                       'confirmed_distinct_attempts': None}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('pdf', 'packet', 'second-pass', 'receipt', 'output', 'inspections',
                 'import-stage', 'sqlite-store'):
        parser.add_argument(f'--{name}', type=Path)
    for name in ('packet-sha256', 'second-pass-sha256', 'receipt-sha256',
                 'inspections-sha256', 'parser-version', 'stage-sha256'):
        parser.add_argument(f'--{name}')
    parser.add_argument('--render', action='append', type=Path)
    parser.add_argument('--expected-source-sha256', default=SOURCE_SHA256)
    parser.add_argument('--compare-only', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.import_stage:
            require(args.stage_sha256 and args.sqlite_store, 'stage import requires SHA256 and store')
            destination = args.sqlite_store.resolve()
        else:
            require(all(getattr(args, name) is not None for name in
                        ('pdf', 'packet', 'second_pass', 'receipt', 'output', 'packet_sha256',
                         'second_pass_sha256', 'receipt_sha256', 'parser_version', 'render')),
                    'verification input missing')
            destination = args.output.resolve()
        repo = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(repo):
            ignored = subprocess.run(['git', '-C', str(repo), 'check-ignore', '-q', str(destination)],
                                     check=False)
            require(ignored.returncode == 0, 'private output inside repository must be Git ignored')
        if args.import_stage:
            print(json.dumps(import_stage(args.import_stage, args.stage_sha256, destination,
                stage_schema=STAGE_SCHEMA, store_schema='italian-open-2025-isolated-store/v1',
                marker_table='italian_open_stage_meta'), sort_keys=True))
            return 0
        document = build(args)
        raw = (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()
        if destination.exists():
            require(destination.read_bytes() == raw, 'staged output exists with different version; use a new path')
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.italian-open-stage-',
                                             delete=False) as stream:
                temporary = Path(stream.name)
                try:
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
                except BaseException:
                    temporary.unlink(missing_ok=True)
                    raise
            try:
                os.replace(temporary, destination)
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise
        print(json.dumps({'output': str(destination), 'sha256': sha(raw),
                          'counts': document['counts']}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError, AttributeError, sqlite3.Error) as error:
        print(f'Italian Open verified stage rejected: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
