import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from xml.sax.saxutils import escape


CLI = Path(__file__).resolve().parents[1] / 'scripts' / 'gia_2025_workbook_census.py'
SOURCE = Path('/Users/jamiep/.codex/worktrees/e1fc/freediving-results/data/issue55-gap-reconcile-20260928/spreadsheets/352ebb0c4119f35cb254d1a4b999e89ee86ed09c5ca3c81be7d60c33102f187b.xlsx')
SHA = '352ebb0c4119f35cb254d1a4b999e89ee86ed09c5ca3c81be7d60c33102f187b'


def tiny_workbook(path):
    names = ('Classifica Generale ', 'NAPOLI 2025', 'PAVIA 2025', 'ROMA 2025', 'FIRENZE 2025')
    def string(address, text):
        return f'<c r="{address}" t="inlineStr"><is><t>{escape(text)}</t></is></c>'
    def number(address, value):
        return f'<c r="{address}"><v>{value}</v></c>'
    row_data = (
        {3: string('G3', 'INDOOR (DIN)'),
         4: string('G4', 'CASALNUOVO di NAPOLI 1-2 FEBBRAIO'),
         6: number('B6', 1) + string('D6', 'Athlete A') + number('G6', 42) +
            '<c r="P6"><f>SUM(G6:N6)</f><v>42</v></c>'},
        {2: string('G2', 'PUNTEGGIO COMBINATA'),
         3: number('B3', 1) + string('D3', 'Athlete A') + number('G3', 42)},
        {1: string('D1', 'Distanza realizzata'),
         2: number('A2', 1) + string('C2', 'Athlete B') + number('D2', 75)},
        {1: string('D1', 'Specialità'),
         2: number('A2', 1) + string('B2', 'Athlete C') + string('D2', 'DNF') +
            string('E2', '2° M') + string('F2', '1:20.00') + number('G2', 75)},
        {1: string('K1', 'STATICA Tempo realizzato'),
         2: number('A2', 1) + string('B2', 'One') + string('C2', 'Diver') +
            string('F2', 'DYN') + number('I2', 100) +
            string('N2', 'Club') + string('O2', 'One Diver') + number('P2', 100),
         3: string('B3', 'Two') + string('C3', 'Diver') + number('K3', 4.42) +
            '<c r="L3"><f>(4*60+42)*0.35</f><v>98.7</v></c>' +
            string('N3', 'Club') + string('O3', 'Two Diver') + number('P3', 98.7)})
    uri = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
    rel_uri = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    with ZipFile(path, 'w') as archive:
        sheets = ''.join(f'<sheet name="{escape(name)}" sheetId="{i}" r:id="rId{i}"/>'
                         for i, name in enumerate(names, 1))
        archive.writestr('xl/workbook.xml',
                         f'<workbook xmlns="{uri}" xmlns:r="{rel_uri}"><sheets>{sheets}</sheets></workbook>')
        rels = ''.join(f'<Relationship Id="rId{i}" Target="worksheets/sheet{i}.xml"/>'
                       for i in range(1, 6))
        archive.writestr('xl/_rels/workbook.xml.rels',
                         f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{rels}</Relationships>')
        for i, rows in enumerate(row_data, 1):
            xml_rows = ''.join(f'<row r="{n}">{cells}</row>' for n, cells in rows.items())
            archive.writestr(f'xl/worksheets/sheet{i}.xml',
                             f'<worksheet xmlns="{uri}"><dimension ref="A1:P{max(rows)}"/>'
                             f'<sheetData>{xml_rows}</sheetData></worksheet>')


class GiaWorkbookCensusTest(unittest.TestCase):
    def run_cli(self, source, expected_sha, output):
        return subprocess.run([sys.executable, str(CLI), '--workbook', str(source),
                               '--expected-sha256', expected_sha, '--output', str(output)],
                              capture_output=True, text=True)

    def test_rejects_source_hash_mismatch_before_writing_packet(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'bad.xlsx'
            output = Path(directory) / 'packet.json'
            source.write_bytes(b'not a workbook')
            result = self.run_cli(source, '0' * 64, output)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('SHA-256', result.stderr)
            self.assertFalse(output.exists())

    def test_explicit_acquisition_manifest_is_bound_to_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'tiny.xlsx'
            output = root / 'packet.json'
            manifest = root / 'acquisition.json'
            tiny_workbook(source)
            sha = hashlib.sha256(source.read_bytes()).hexdigest()
            receipt = {'sha256': sha, 'bytes': source.stat().st_size,
                       'final_url': 'https://example.test/original.xlsx', 'http_status': 200}
            manifest.write_text(json.dumps({'sources': [receipt]}))
            result = subprocess.run([sys.executable, str(CLI), '--workbook', str(source),
                                     '--expected-sha256', sha, '--receipt-manifest', str(manifest),
                                     '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(receipt, json.loads(output.read_text())['source']['acquisition'])
            manifest.write_text(json.dumps({'sources': [{**receipt, 'bytes': receipt['bytes'] - 1}]}))
            output.unlink()
            result = subprocess.run([sys.executable, str(CLI), '--workbook', str(source),
                                     '--expected-sha256', sha, '--receipt-manifest', str(manifest),
                                     '--output', str(output)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists())

    def test_portable_workbook_preserves_formula_cache_and_distinct_row_roles(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'tiny.xlsx'
            output = Path(directory) / 'packet.json'
            tiny_workbook(source)
            sha = hashlib.sha256(source.read_bytes()).hexdigest()
            result = self.run_cli(source, sha, output)
            self.assertEqual(0, result.returncode, result.stderr)
            packet = json.loads(output.read_text())
            sheets = {sheet['name']: sheet for sheet in packet['sheets']}
            self.assertEqual(['standings'], [row['kind'] for row in sheets['Classifica Generale ']['rows']])
            self.assertEqual(42, sheets['Classifica Generale ']['rows'][0]['cells']['P6']['cached_value'])
            self.assertEqual('=SUM(G6:N6)', sheets['Classifica Generale ']['rows'][0]['cells']['P6']['formula'])
            self.assertEqual(['dynamic_result', 'secondary_score', 'static_result', 'secondary_score'],
                             [row['kind'] for row in sheets['FIRENZE 2025']['rows']])
            self.assertEqual(4.42, sheets['FIRENZE 2025']['rows'][2]['fields']['printed_time'])
            self.assertIsNone(packet['excluded_related_source'])

    @unittest.skipUnless(SOURCE.exists(), 'official acquired workbook unavailable')
    def test_official_workbook_preserves_scored_and_result_rows_with_citations(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'packet.json'
            result = self.run_cli(SOURCE, SHA, output)
            self.assertEqual(0, result.returncode, result.stderr)
            packet = json.loads(output.read_text())
            self.assertEqual('gia-2025-individual-workbook-census/v1', packet['schema'])
            self.assertEqual(SHA, packet['source']['sha256'])
            self.assertIsNone(packet['confirmed_distinct_attempts'])
            sheets = {sheet['name']: sheet for sheet in packet['sheets']}
            self.assertEqual({'Classifica Generale ', 'NAPOLI 2025', 'PAVIA 2025',
                              'ROMA 2025', 'FIRENZE 2025'}, set(sheets))
            self.assertEqual(366, sheets['Classifica Generale ']['counts']['standings_rows'])
            self.assertEqual('A1:Q709', sheets['Classifica Generale ']['inventory']['declared_dimension'])
            self.assertEqual(380, sheets['Classifica Generale ']['inventory']['last_nonblank_row'])
            self.assertEqual(9, sheets['Classifica Generale ']['counts']['formula_placeholder_rows'])
            self.assertEqual(96, sheets['NAPOLI 2025']['counts']['combined_score_rows'])
            self.assertEqual(143, sheets['PAVIA 2025']['counts']['distance_result_rows'])
            self.assertEqual(140, sheets['ROMA 2025']['counts']['discipline_result_rows'])
            self.assertEqual(65, sheets['FIRENZE 2025']['counts']['dynamic_result_rows'])
            self.assertEqual(7, sheets['FIRENZE 2025']['counts']['static_result_rows'])
            self.assertEqual(72, sheets['FIRENZE 2025']['counts']['secondary_score_rows'])
            self.assertEqual('PUNTEGGIO COMBINATA',
                             sheets['NAPOLI 2025']['header_cells']['G2']['value'])
            self.assertIn('16  MARZO',
                          sheets['Classifica Generale ']['header_cells']['I4']['value'])
            self.assertEqual(0, next(row for row in sheets['NAPOLI 2025']['rows']
                                     if row['row'] == 13)['fields']['combined_score'])
            self.assertEqual('Classifica Generale !P6',
                             sheets['Classifica Generale ']['rows'][0]['cells']['P6']['citation'])
            self.assertTrue(sheets['Classifica Generale ']['rows'][0]['cells']['P6']['formula'].startswith('=IF('))
            self.assertAlmostEqual(420.3, sheets['Classifica Generale ']['rows'][0]['cells']['P6']['cached_value'])
            self.assertEqual('DYN', sheets['ROMA 2025']['rows'][0]['fields']['discipline'])
            self.assertEqual('not printed (likely metres)',
                             sheets['ROMA 2025']['rows'][0]['units']['distance'])
            self.assertEqual('not printed (likely metres)',
                             sheets['PAVIA 2025']['rows'][0]['units']['distance'])
            self.assertEqual('not printed (likely metres)',
                             next(row for row in sheets['FIRENZE 2025']['rows']
                                  if row['kind'] == 'dynamic_result')['units']['distance'])
            self.assertEqual(4.42, next(row for row in sheets['FIRENZE 2025']['rows']
                                        if row['kind'] == 'static_result' and row['row'] == 5)['fields']['printed_time'])
            self.assertTrue(next(row for row in sheets['FIRENZE 2025']['rows']
                                 if row['kind'] == 'static_result' and row['row'] == 5)['cells']['L5']['formula'].startswith('='))
            self.assertIn('L5 formula', next(row for row in sheets['FIRENZE 2025']['rows']
                                            if row['kind'] == 'static_result' and row['row'] == 5)['ambiguities'][0])
            self.assertEqual('408dc419f22536d319976077851563cbbe316891bab8b328d348df275b8b987f',
                             packet['excluded_related_source']['sha256'])
            self.assertEqual('Classifiche  società 2024',
                             packet['excluded_related_source']['sheet_name'])
            self.assertIn('2025', packet['excluded_related_source']['title_cell']['value'])
            first = output.read_bytes()
            self.assertEqual(0, self.run_cli(SOURCE, SHA, output).returncode)
            self.assertEqual(first, output.read_bytes())


if __name__ == '__main__':
    unittest.main()
