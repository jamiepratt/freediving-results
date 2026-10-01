#!/usr/bin/env python3
"""Reconcile every Nordic Cup final-results PDF row to retained timing JSON."""

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path


PDF_SHA256 = 'd151b972d231c3cc40858d0b381db731c83f7b5f11348a08d53e2c32943ad4fa'
PAGES = (
    ('CNF', 'SENM', 'CONSTANT WEIGHT WITHOUT FINS', 'SENIORS MEN', 11),
    ('CNF', 'SENF', 'CONSTANT WEIGHT WITHOUT FINS', 'SENIORS WOMEN', 5),
    ('FIM', 'SENM', 'FREE IMMERSION', 'SENIORS MEN', 16),
    ('FIM', 'SENF', 'FREE IMMERSION', 'SENIORS WOMEN', 6),
    ('CWT', 'SENM', 'CONSTANT WEIGHT WITH FINS', 'SENIORS MEN', 12),
    ('CWT', 'SENF', 'CONSTANT WEIGHT WITH FINS', 'SENIORS WOMEN', 8),
    ('CWT-BF', 'SENM', 'CONSTANT WEIGHT WITH BI-FINS', 'SENIORS MEN', 13),
    ('CWT-BF', 'SENF', 'CONSTANT WEIGHT WITH BI-FINS', 'SENIORS WOMEN', 5),
)
COUNTRY_AND_DEPTH = re.compile(r'(?P<country>CMAS1|[A-Z]{3})\s+(?P<decl>\d+)\b')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def source_line(line, previous='', following=''):
    match = COUNTRY_AND_DEPTH.search(line)
    if not match:
        return None
    prefix = line[:match.start()].strip()
    rank = re.match(r'^(\d+)\s+', prefix)
    name = prefix[rank.end():] if rank else prefix
    if not name:
        raise ValueError('PDF result name missing')
    remainder = line[match.end():]
    numbers = [match.group('decl')] + re.findall(r'\b\d+\b', remainder)
    status = next((token for token in ('DNS', 'DSQ', 'PEN')
                   if re.search(r'\b' + token + r'\b', remainder)), None)
    if status == 'PEN' and len(numbers) == 4:
        declared, result, penalty, final = numbers
    elif status == 'DNS' and len(numbers) == 1:
        declared = numbers[0]
        result = penalty = final = None
    elif status == 'DSQ' and len(numbers) == 2:
        declared, result = numbers
        penalty = final = None
    elif status is None and len(numbers) == 3:
        declared, result, final = numbers
        penalty = None
    else:
        raise ValueError(f'PDF numeric columns ambiguous: {name}: {numbers}/{status}')
    note = None
    if status == 'PEN':
        if previous.strip() != 'EARLY TURN, NO' or 'MARKER' not in remainder:
            raise ValueError(f'PDF penalty note ambiguous: {name}')
        note = 'EARLY TURN, NO MARKER'
    elif status == 'DSQ':
        for value in ('SURFACE BO', 'UW BO'):
            if value in remainder:
                note = value
                break
        if not note:
            raise ValueError(f'PDF DSQ note ambiguous: {name}')
    medal = next((value for value in ('GOLD MEDAL', 'SILVER MEDAL', 'BRONZE MEDAL')
                  if value in line), None)
    medal_line = None
    if not medal:
        medal = next((value for value in ('GOLD MEDAL', 'SILVER MEDAL', 'BRONZE MEDAL')
                      if following.strip() == value), None)
        if medal:
            medal_line = following
    return {
        'rank': rank.group(1) if rank else None, 'name': name,
        'country': match.group('country'), 'declared_depth': declared,
        'raw_result': result, 'penalty': penalty, 'final_result': final,
        'status': status, 'note': note, 'medal': medal,
        'note_line_before': previous if status == 'PEN' else None,
        'medal_line_after': medal_line,
    }


def page_text(pdf, page):
    return subprocess.run(['pdftotext', '-f', str(page), '-l', str(page),
                           '-layout', str(pdf), '-'], check=True, capture_output=True).stdout


def build(pdf, api_packet, receipt, page_texts=None):
    raw_pdf = Path(pdf).read_bytes()
    if not raw_pdf.startswith(b'%PDF-') or sha(raw_pdf) != PDF_SHA256:
        raise ValueError('Nordic PDF source hash mismatch')
    source_receipt = json.loads(Path(receipt).read_bytes())
    if source_receipt['sha256'] != PDF_SHA256 or source_receipt['bytes'] != len(raw_pdf):
        raise ValueError('Nordic PDF receipt mismatch')
    packet = json.loads(Path(api_packet).read_bytes())
    if packet.get('schema') != 'cmas-microplus-private-census/v1':
        raise ValueError('expected retained API census v1')
    api_positions = {row['raw_fields']['ResID']: row for row in packet['positions']
                     if row['raw_fields']['DCCmpID'] == 33}
    if len(api_positions) != 92:
        raise ValueError('Nordic API source-position count mismatch')
    positions = []
    relationships = []
    pages = []
    matched = set()
    rank_differences = 0
    for page, (discipline, category, heading, age_heading, expected) in enumerate(PAGES, 1):
        text = page_texts[page - 1] if page_texts is not None else page_text(pdf, page)
        lines = text.decode('utf-8').splitlines()
        if ('2026 Nordic Cup Freediving Depth' not in text.decode('utf-8')
                or 'FINAL RESULTS' not in text.decode('utf-8')
                or heading not in text.decode('utf-8')
                or age_heading not in text.decode('utf-8')
                or f'Page {page} of 8' not in text.decode('utf-8')):
            raise ValueError(f'PDF page {page} heading or footer mismatch')
        start = next(i for i, line in enumerate(lines) if age_heading in line) + 1
        end = next(i for i, line in enumerate(lines) if 'Report Created' in line)
        page_count = 0
        for index in range(start, end):
            line = lines[index]
            parsed = source_line(line, lines[index - 1] if index else '',
                                 lines[index + 1] if index + 1 < len(lines) else '')
            if parsed is None:
                continue
            page_count += 1
            candidates = [row for row in api_positions.values()
                          if row['raw_fields']['EvShortDescr'] == discipline
                          and row['raw_fields']['AGCodeDescr'] == category
                          and row['raw_fields']['ParPrintName'] == parsed['name']
                          and row['raw_fields']['ParOrgCode'] == parsed['country']
                          and row['raw_fields']['DECLLEN_STR'] == parsed['declared_depth']
                          and row['raw_fields']['ResResult'] == parsed['raw_result']
                          and row['raw_fields']['ResPenality'] == parsed['penalty']
                          and row['raw_fields']['ResResultFinal'] == parsed['final_result']
                          and row['raw_fields']['ResReasonCode'] == parsed['status']
                          and row['raw_fields']['ResNotePenality'] == parsed['note']]
            if len(candidates) != 1:
                raise ValueError(f'PDF/API match not unique: page {page} line {index + 1}')
            api_row = candidates[0]
            res_id = api_row['raw_fields']['ResID']
            if res_id in matched:
                raise ValueError(f'API row matched twice: {res_id}')
            matched.add(res_id)
            rank_differences += parsed['rank'] != (
                str(api_row['raw_fields']['ResRnk']) if api_row['raw_fields']['ResRnk'] is not None else None)
            pdf_id = f'nordic-final:page:{page}:line:{index + 1}'
            citation = {'url': source_receipt['final_url'], 'source_sha256': PDF_SHA256,
                        'page': page, 'line': index + 1, 'text': line}
            positions.append({
                'id': pdf_id, 'source_object_id': f'sha256:{PDF_SHA256}',
                'event_name': '2026 Nordic Cup Freediving Depth',
                'date_from': '2026-08-16', 'date_to': '2026-08-21',
                'discipline': discipline, 'category': category,
                'review_status': 'unreviewed', 'disposition': 'final_pdf_source_position',
                'raw_fields': {key: value for key, value in parsed.items()
                               if key not in ('note_line_before', 'medal_line_after')},
                'citation': citation,
                'adjacent_lines': [value for value in
                                   (parsed['note_line_before'], parsed['medal_line_after']) if value],
            })
            relationships.append({
                'id': f'nordic-final-api:{page}:{index + 1}:{res_id}',
                'relationship_type': 'same_result', 'evidence_state': 'exact_printed_field_match',
                'review_status': 'unreviewed',
                'pdf_position_id': pdf_id, 'api_position_id': api_row['id'],
                'api_res_id': res_id, 'citation': citation,
                'api_citation': api_row['citation'],
                'basis': 'Name, country, discipline, category, declared depth, raw result, penalty, final result, status and note match exactly; sporting-attempt identity remains unreviewed.',
            })
        if page_count != expected:
            raise ValueError(f'PDF page {page} expected {expected} rows, got {page_count}')
        pages.append({'page': page, 'discipline': discipline, 'category': category,
                      'printed_rows': page_count, 'pdftotext_sha256': sha(text)})
    if len(positions) != 76 or len(matched) != 76:
        raise ValueError('Nordic PDF accounting mismatch')
    api_only = [row for res_id, row in sorted(api_positions.items()) if res_id not in matched]
    if len(api_only) != 16:
        raise ValueError('Nordic API-only accounting mismatch')
    return {
        'schema': 'nordic-final-pdf-reconciliation/v1',
        'source': {'id': f'sha256:{PDF_SHA256}', 'sha256': PDF_SHA256,
                   'bytes': len(raw_pdf), 'url': source_receipt['final_url']},
        'source_sha256': PDF_SHA256, 'confirmed_distinct_attempts': None,
        'counts': {'pdf_pages': 8, 'pdf_positions': 76, 'matched_api_positions': 76,
                   'api_only_positions': 16, 'api_positions': 92,
                   'different_printed_vs_unit_ranks': rank_differences,
                   'confirmed_distinct_attempts': None},
        'pages': pages, 'positions': positions, 'relationships': relationships,
        'api_only': [{'id': row['id'], 'source_object_id': row['source_object_id'],
                      'citation': row['citation'], 'res_id': row['raw_fields']['ResID']}
                     for row in api_only],
    }


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pdf', required=True, type=Path)
    parser.add_argument('--api-packet', required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = build(args.pdf, args.api_packet, args.receipt)
    raw = (json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()
    args.output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    args.output.write_bytes(raw)
    args.output.chmod(0o600)
    print(json.dumps({'output': str(args.output), 'sha256': sha(raw), **result['counts']}, sort_keys=True))


if __name__ == '__main__':
    main()
