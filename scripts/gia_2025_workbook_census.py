#!/usr/bin/env python3
"""Private, source-cited census of the original GIA 2025 individual workbook.

Rows are printed source positions. They are not deduplicated sporting attempts.
The parser uses only Python's standard library and never edits the workbook.
"""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
from xml.etree import ElementTree as ET
from zipfile import ZipFile


NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
      'p': 'http://schemas.openxmlformats.org/package/2006/relationships'}
SHEETS = ('Classifica Generale ', 'NAPOLI 2025', 'PAVIA 2025',
          'ROMA 2025', 'FIRENZE 2025')
DATE = {'NAPOLI 2025': ('2025-02-01', '2025-02-02', 'Classifica Generale !G4; NAPOLI 2025!C1'),
        'PAVIA 2025': ('2025-02-16', '2025-02-16', 'Classifica Generale !H4'),
        'ROMA 2025': ('2025-03-16', '2025-03-16', 'Classifica Generale !I4'),
        'FIRENZE 2025': ('2025-03-30', '2025-03-30', 'Classifica Generale !J4')}


def decode_number(value):
    if value is None or value == '':
        return None
    if re.fullmatch(r'-?\d+', value):
        return int(value)
    return float(value)


def text_content(element):
    return ''.join(node.text or '' for node in element.iterfind('.//m:t', NS))


def parse_workbook(path):
    with ZipFile(path) as archive:
        strings = []
        if 'xl/sharedStrings.xml' in archive.namelist():
            root = ET.fromstring(archive.read('xl/sharedStrings.xml'))
            strings = [text_content(item) for item in root.findall('m:si', NS)]
        book = ET.fromstring(archive.read('xl/workbook.xml'))
        rels = ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))
        targets = {rel.attrib['Id']: rel.attrib['Target'] for rel in rels}
        result = {}
        dimensions = {}
        for sheet in book.findall('.//m:sheets/m:sheet', NS):
            name = sheet.attrib['name']
            target = targets[sheet.attrib[f'{{{NS["r"]}}}id']]
            target = target.lstrip('/') if target.startswith('/') else 'xl/' + target
            root = ET.fromstring(archive.read(target))
            dimension = root.find('m:dimension', NS)
            dimensions[name] = dimension.attrib.get('ref') if dimension is not None else None
            rows = {}
            for row in root.findall('.//m:sheetData/m:row', NS):
                cells = {}
                for cell in row.findall('m:c', NS):
                    address = cell.attrib['r']
                    kind = cell.attrib.get('t')
                    value_node = cell.find('m:v', NS)
                    formula_node = cell.find('m:f', NS)
                    raw = value_node.text if value_node is not None else None
                    if kind == 's':
                        value = strings[int(raw)]
                    elif kind == 'inlineStr':
                        value = text_content(cell.find('m:is', NS))
                    elif kind in ('str', 'e'):
                        value = raw
                    elif kind == 'b':
                        value = raw == '1'
                    else:
                        value = decode_number(raw)
                    formula = '=' + (formula_node.text or '') if formula_node is not None else None
                    if value is None and formula is None:
                        continue
                    cells[address] = {'citation': f'{name}!{address}', 'value': value,
                                      'formula': formula,
                                      'cached_value': value if formula is not None else None,
                                      'cell_type': kind or 'n'}
                if cells:
                    rows[int(row.attrib['r'])] = cells
            result[name] = rows
    return result, dimensions


def value(cells, column, row):
    return cells.get(f'{column}{row}', {}).get('value')


def selected(cells, columns, row):
    return {key: cells[key] for column in columns if (key := f'{column}{row}') in cells}


def record(name, number, kind, cells, fields, units=None, date=None, notes=None):
    item = {'sheet': name, 'row': number, 'kind': kind,
            'citation': f'{name}!{min(cells)}:{max(cells)}' if cells else f'{name}!{number}',
            'cells': cells, 'fields': fields, 'units': units or {},
            'printed_status': None, 'date_scope': date,
            'ambiguities': notes or []}
    return item


def sheet_census(name, rows, declared_dimension):
    output = []
    header_rows = {'Classifica Generale ': (1, 3, 4), 'NAPOLI 2025': (1, 2),
                   'PAVIA 2025': (1,), 'ROMA 2025': (1,), 'FIRENZE 2025': (1,)}
    for n, all_cells in rows.items():
        if name == 'Classifica Generale ' and n >= 6:
            if value(all_cells, 'D', n) is not None:
                cells = selected(all_cells, 'BCDEFGHIJKLMNP', n)
                output.append(record(name, n, 'standings', cells,
                                     {'rank_or_serial': value(cells, 'B', n),
                                      'club': value(cells, 'C', n), 'athlete': value(cells, 'D', n),
                                      'sex': value(cells, 'E', n),
                                      'event_scores': {col: value(cells, col, n) for col in 'FGHIJKLMN'
                                                       if value(cells, col, n) is not None},
                                      'overall_score': value(cells, 'P', n)},
                                     notes=['Aggregate standings; event columns are scores, not individual attempts.']))
            elif value(all_cells, 'B', n) is not None or f'P{n}' in all_cells:
                cells = selected(all_cells, 'BCDEFGHIJKLMNP', n)
                output.append(record(name, n, 'formula_placeholder', cells,
                                     {'rank_or_serial': value(cells, 'B', n)},
                                     notes=['No printed athlete; exclude from athlete counts.']))
        elif name == 'NAPOLI 2025' and n >= 3 and value(all_cells, 'D', n) is not None:
            cells = selected(all_cells, 'BCDEG', n)
            output.append(record(name, n, 'combined_score', cells,
                                 {'serial': value(cells, 'B', n), 'club': value(cells, 'C', n),
                                  'athlete': value(cells, 'D', n), 'sex': value(cells, 'E', n),
                                  'combined_score': value(cells, 'G', n)},
                                 {'combined_score': 'GIA points'}, DATE[name],
                                 ['Combined STA + DIN score; component performances and statuses absent.']))
        elif name == 'PAVIA 2025' and n >= 2 and value(all_cells, 'C', n) is not None:
            cells = selected(all_cells, 'ABCD', n)
            output.append(record(name, n, 'distance_result', cells,
                                 {'serial': value(cells, 'A', n), 'club': value(cells, 'B', n),
                                  'athlete': value(cells, 'C', n), 'distance': value(cells, 'D', n)},
                                 {'distance': 'not printed (likely metres)'}, DATE[name],
                                 ['Discipline DIN appears in overall standings H3, not this sheet; status absent.']))
        elif name == 'ROMA 2025' and n >= 2 and value(all_cells, 'B', n) is not None:
            cells = selected(all_cells, 'ABCDEFG', n)
            output.append(record(name, n, 'discipline_result', cells,
                                 {'serial': value(cells, 'A', n), 'athlete': value(cells, 'B', n),
                                  'club': value(cells, 'C', n), 'discipline': value(cells, 'D', n),
                                  'category': value(cells, 'E', n), 'printed_time': value(cells, 'F', n),
                                  'distance': value(cells, 'G', n)},
                                 {'printed_time': 'mm:ss.xx', 'distance': 'not printed (likely metres)'}, DATE[name],
                                 ['No separate result status column; preserve zero times as printed.']))
        elif name == 'FIRENZE 2025' and n >= 2:
            common = {'surname': value(all_cells, 'B', n), 'given_name': value(all_cells, 'C', n),
                      'club': value(all_cells, 'D', n), 'printed_full_name': value(all_cells, 'E', n)}
            if value(all_cells, 'F', n) is not None:
                cells = selected(all_cells, 'ABCDEFGHI', n)
                output.append(record(name, n, 'dynamic_result', cells,
                                     dict(common, serial=value(cells, 'A', n),
                                          discipline=value(cells, 'F', n),
                                          category=value(cells, 'G', n),
                                          printed_time=value(cells, 'H', n),
                                          distance=value(cells, 'I', n)),
                                     {'printed_time': 'mm:ss.xx', 'distance': 'not printed (likely metres)'}, DATE[name],
                                     ['No separate result status column.']))
            if value(all_cells, 'K', n) is not None:
                cells = selected(all_cells, 'BCDEKL', n)
                output.append(record(name, n, 'static_result', cells,
                                     dict(common, printed_time=value(cells, 'K', n),
                                          gia_points=value(cells, 'L', n)),
                                     {'printed_time': 'numeric; minute.second inferred from adjacent L formula',
                                      'gia_points': 'GIA points'}, DATE[name],
                                     [f'L{n} formula treats K{n} as minutes and seconds; K is a numeric source cell, not an Excel time serial.']))
            if value(all_cells, 'O', n) is not None:
                cells = selected(all_cells, 'NOP', n)
                output.append(record(name, n, 'secondary_score', cells,
                                     {'club': value(cells, 'N', n), 'athlete': value(cells, 'O', n),
                                      'score': value(cells, 'P', n)},
                                     {'score': 'GIA points or distance-derived score'}, DATE[name],
                                     ['Secondary name and score listing; not an additional attempt.']))
    counts = Counter(item['kind'] + '_rows' for item in output)
    info = {'name': name, 'role': 'aggregate_standings' if name == 'Classifica Generale ' else 'event_table',
            'date_scope': DATE.get(name),
            'header_cells': {address: cell for n in header_rows[name]
                             for address, cell in rows.get(n, {}).items()},
            'inventory': {'declared_dimension': declared_dimension,
                          'nonblank_cells': sum(len(cells) for cells in rows.values()),
                          'last_nonblank_row': max(rows)},
            'counts': dict(sorted(counts.items())),
            'rows': output}
    return info


def source_receipt(path, sha):
    receipt = path.parent / 'receipt.json'
    if not receipt.exists():
        return None
    for item in json.loads(receipt.read_text()):
        if item.get('sha256') == sha:
            return item
    return None


def excluded_team_source(path):
    sha = '408dc419f22536d319976077851563cbbe316891bab8b328d348df275b8b987f'
    related = path.parent / (sha + '.xlsx')
    if not related.exists():
        return None
    actual = hashlib.sha256(related.read_bytes()).hexdigest()
    if actual != sha:
        raise ValueError(f'Related team workbook SHA-256 mismatch: {actual}')
    sheets, _ = parse_workbook(related)
    if tuple(sheets) != ('Classifiche  società 2024',):
        raise ValueError('Unexpected related team workbook sheets')
    title = sheets['Classifiche  società 2024'][1]['B1']
    return {'sha256': sha, 'acquisition': source_receipt(related, sha),
            'sheet_name': 'Classifiche  società 2024', 'title_cell': title,
            'classification': 'club aggregate standings only',
            'exclusion_reason': 'No individual athlete result rows. Sheet name says 2024 while B1 title says GIA 2025.'}


def census(path, expected_sha):
    data = path.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    if sha != expected_sha:
        raise ValueError(f'SHA-256 mismatch: expected {expected_sha}, got {sha}')
    sheets, dimensions = parse_workbook(path)
    if tuple(sheets) != SHEETS:
        raise ValueError(f'Unexpected sheets: {list(sheets)}')
    reports = [sheet_census(name, sheets[name], dimensions[name]) for name in SHEETS]
    return {'schema': 'gia-2025-individual-workbook-census/v1',
            'source': {'sha256': sha, 'bytes': len(data), 'acquisition': source_receipt(path, sha),
                       'workbook_name': path.name},
            'scope': 'Giro d\'Italia in Apnea 2025 individual workbook source positions',
            'sheets': reports, 'excluded_related_source': excluded_team_source(path),
            'confirmed_distinct_attempts': None,
            'limits': ['Dates are supported by overall standings row 4 and the Napoli title; year by the workbook and sheet titles.',
                       'Source rows and scores are not confirmed distinct attempts.',
                       'No row-level status field is printed; zero values do not establish status.',
                       'Firenze N:P repeats a name-score view and is not counted as a new result.',
                       'Names are preserved as printed; no cross-source identity merge.']}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workbook', required=True, type=Path)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha256):
            raise ValueError('Expected SHA-256 must be lowercase hex')
        packet = census(args.workbook, args.expected_sha256)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(packet, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
    except (OSError, ValueError, KeyError) as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
