#!/usr/bin/env python3
"""Replay three Apnea Academy child files without treating aggregate rows as attempts."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from gia_2025_workbook_census import parse_workbook


TEAM_URL = 'https://apnea.academy/site/assets/files/11984/classifica_generale_gia_2025.xlsx'
INDIVIDUAL_URL = 'https://apnea.academy/site/assets/files/11984/classifica_generale_individuale_gia_2025.xlsx'
SQUADRE_URL = 'https://apnea.academy/site/assets/files/11984/trofeo_san_mauro_2026_squadre.pdf'
TEAM_SHEET = 'Classifiche  società 2024'
TEAM_COLUMNS = 'BCDEFGHIJKLMN'
SQUADRE_LINE = re.compile(
    r'^\s*(\d{1,2})\s+(.+?)\s+(\d+)\s+([\d.]+,\d{2})\s+([\d.]+,\d{2})\s+([\d.]+,\d{2})\s*$')


def verified_source(path, expected_sha, url):
    if not re.fullmatch(r'[0-9a-f]{64}', expected_sha):
        raise ValueError(f'Invalid expected SHA-256 for {url}')
    data = path.read_bytes()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected_sha:
        raise ValueError(f'SHA-256 mismatch for {url}: expected {expected_sha}, got {actual}')
    return {'url': url, 'sha256': actual, 'bytes': len(data)}


def selected(cells, row):
    return {f'{col}{row}': cells[f'{col}{row}'] for col in TEAM_COLUMNS
            if f'{col}{row}' in cells}


def team_census(path, receipt):
    sheets, dimensions = parse_workbook(path)
    if tuple(sheets) != (TEAM_SHEET,):
        raise ValueError(f'Unexpected GIA team sheets: {list(sheets)}')
    rows = sheets[TEAM_SHEET]
    if '2025' not in str(rows.get(1, {}).get('B1', {}).get('value', '')):
        raise ValueError('GIA team B1 title does not identify 2025')
    standings = []
    for number in range(7, 47):
        cells = selected(rows.get(number, {}), number)
        club = cells.get(f'B{number}', {}).get('value')
        if not isinstance(club, str) or not club.strip():
            raise ValueError(f'GIA team row {number} has no printed club')
        standings.append({'row': number, 'citation': f'{TEAM_SHEET}!B{number}:N{number}',
                          'cells': cells, 'club': club,
                          'overall_score': cells.get(f'N{number}', {}).get('value'),
                          'classification': 'club aggregate standings'})
    placeholder_cells = selected(rows.get(47, {}), 47)
    if placeholder_cells.get('N47', {}).get('value') != 0 or 'B47' in placeholder_cells:
        raise ValueError('GIA team row 47 is not the expected zero placeholder')
    return {'source': receipt, 'sheet': TEAM_SHEET, 'declared_dimension': dimensions[TEAM_SHEET],
            'title_cell': rows[1]['B1'],
            'header_cells': {address: cell for number in range(1, 7)
                             for address, cell in rows.get(number, {}).items()},
            'rows': standings,
            'placeholder': {'row': 47, 'citation': f'{TEAM_SHEET}!B47:N47',
                            'cells': placeholder_cells, 'classification': 'zero placeholder, no club'},
            'ambiguities': ['Sheet name says 2024; B1 title says GIA 2025. Preserve both printed labels.',
                            'Event columns and overall score are club aggregates, not athlete attempts.']}


def squadre_census(path, receipt):
    result = subprocess.run(['pdftotext', '-f', '1', '-l', '1', '-layout', str(path), '-'],
                            capture_output=True, text=True, check=True)
    lines = result.stdout.splitlines()
    if not any("1 marzo 2026" in line.lower() for line in lines):
        raise ValueError('San Mauro source does not print 1 marzo 2026')
    rows = []
    for number, line in enumerate(lines, 1):
        match = SQUADRE_LINE.match(line)
        if not match:
            continue
        rank, club, athletes, din, sta, total = match.groups()
        rows.append({'rank': int(rank), 'citation': f'page 1 line {number}',
                     'printed_line': line, 'fields': {'club': club, 'athletes': athletes,
                                                      'din_points': din, 'sta_points': sta,
                                                      'total_points': total},
                     'classification': 'team aggregate ranking'})
    if len(rows) != 10 or [row['rank'] for row in rows] != list(range(1, 11)):
        raise ValueError(f'San Mauro expected 10 ranked teams, got {len(rows)}')
    return {'source': receipt, 'event_date': '2026-03-01', 'date_citation': next(
        f'page 1 line {number}' for number, line in enumerate(lines, 1)
        if '1 marzo 2026' in line.lower()),
            'rows': rows, 'reported_athletes_sum': sum(int(row['fields']['athletes']) for row in rows),
            'limits': ['ATLETI is a printed team count, not individual athlete rows.',
                       'DIN, STA and TOT TAPPA are printed aggregate points; no attempt status is given.']}


def reconcile(args):
    team_receipt = verified_source(args.team_workbook, args.team_sha256, TEAM_URL)
    individual_receipt = verified_source(args.individual_workbook, args.individual_sha256, INDIVIDUAL_URL)
    squadre_receipt = verified_source(args.squadre_pdf, args.squadre_sha256, SQUADRE_URL)
    manifest_receipt = verified_source(args.prior_snapshot_manifest, args.prior_manifest_sha256,
                                       'prior unified evidence snapshot manifest')
    prior = json.loads(args.prior_snapshot_manifest.read_text())
    gia = prior.get('inputs', {}).get('gia', {})
    if prior.get('schema') != 'unified-evidence-snapshot/v1' or \
            gia.get('source_schema') != 'gia-2025-individual-workbook-census/v1' or \
            gia.get('source_sha256') != individual_receipt['sha256'] or \
            gia.get('collections', {}).get('sheets.rows') != 898 or \
            not re.fullmatch(r'[0-9a-f]{64}', gia.get('sha256', '')):
        raise ValueError('Individual original does not match prior GIA census in snapshot manifest')
    team = team_census(args.team_workbook, team_receipt)
    squadre = squadre_census(args.squadre_pdf, squadre_receipt)
    return {'schema': 'apnea-academy-file-reconciliation/v1',
            'scope': 'GIA 2025 club and individual child links; San Mauro 2026 squadre child link',
            'gia_team': team,
            'individual_equivalence': {'source': individual_receipt,
                                       'prior_snapshot_manifest': manifest_receipt,
                                       'prior_packet_sha256': gia['sha256'],
                                       'prior_sheet_rows': gia['collections']['sheets.rows'],
                                       'same_original_sha256': True,
                                       'classification': 'existing individual original; rows already represented by prior packet',
                                       'new_rows_counted': 0},
            'san_mauro': squadre,
            'counts': {'gia_team_standings_rows': len(team['rows']),
                       'gia_team_placeholder_rows': 1,
                       'san_mauro_team_rows': len(squadre['rows']),
                       'confirmed_distinct_attempts': None},
            'limits': ['No owner review, athlete identity merge or same-attempt link is inferred.',
                       'Aggregate team standings do not add to distinct attempt counts.']}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('team-workbook', 'individual-workbook', 'prior-snapshot-manifest', 'squadre-pdf', 'output'):
        parser.add_argument('--' + flag, required=True, type=Path)
    for flag in ('team-sha256', 'individual-sha256', 'prior-manifest-sha256', 'squadre-sha256'):
        parser.add_argument('--' + flag, required=True)
    args = parser.parse_args(argv)
    try:
        packet = reconcile(args)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump(packet, output, ensure_ascii=False, indent=2, sort_keys=True)
            output.write('\n')
        args.output.chmod(0o600)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
