"""Synthetic public CLI checks for bounded Italian Open scan verification."""
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'cmas_italian_open_2025_verified_import.py'
WORLD_CUP = Path(__file__).resolve().parents[1] / 'scripts' / 'cmas_worldcup_2026_verified_import.py'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


class ItalianOpenVerifiedImportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.pdf = self.write('source.pdf', b'%PDF-1.4\nsynthetic scan\n')
        self.source = digest(self.pdf.read_bytes())
        self.render = self.write('page.jpg', b'\xff\xd8\xff\xe0synthetic jpg\xff\xd9')
        fields = {'Cognome': 'Example', 'Nome': 'Ada', 'Profondit\u00e0 dichiarata': '30',
                  'Profondit\u00e0 realizzata': '29', 'Profondit\u00e0 omologata': '29',
                  'Tempo dichiarato': '1:20.00', 'Tempo realizzato': '1:19.00',
                  'Tempo omologato': '1:19.00', 'Penalit\u00e0': 'PG', 'Posizione': '1'}
        self.fields = fields
        self.rows = []
        for index, disposition in enumerate(('candidate_result', 'aggregate', 'summary', 'duplicate_render'), 1):
            row = {'row': index, 'id': f'source-position:{index}', 'disposition': disposition,
                   'citation': {'source_sha256': self.source, 'page': 1,
                                'region': {'units': 'normalized_image',
                                           'render_sha256': digest(self.render.read_bytes()),
                                           'bbox': [0.1, 0.1 * index, 0.9, 0.1 * index + 0.05]}},
                   'fields_raw': fields.copy(), 'penalty_raw': 'PG', 'status_raw': None,
                   'notes_raw': None, 'uncertainties': []}
            if disposition == 'duplicate_render':
                row['duplicate_of'] = {'page': 1, 'row': 3}
                row['duplicate_evidence'] = 'Repeated summary row'
            self.rows.append(row)
        self.packet = self.json('packet.json', {
            'schema': 'italian-open-2025-visual-evidence/v3', 'source_sha256': self.source,
            'source_bytes': self.pdf.stat().st_size,
            'pages': [{'page': 1, 'disposition': 'result_table', 'visual_row_count': 4,
                       'render': {'format': 'jpg', 'sha256': digest(self.render.read_bytes()),
                                  'pixel_width': 10, 'pixel_height': 10}, 'rows': self.rows}],
            'counts': {'candidate_result_positions': 1, 'aggregate_rows_excluded': 1,
                       'summary_rows_excluded': 1, 'duplicate_rendered_rows': 1}})
        self.second = self.json('second.json', {'schema': 'italian-open-2025-second-pass/v1',
            'source_sha256': self.source, 'blind_to_first_pass': True,
            'transcriber': 'blind-reader',
            'rows': [{'page': 1, 'row': i, 'fields_raw': self.fields.copy(),
                      'uncertain_fields': []} for i in range(1, 5)]})
        self.receipt = self.json('receipt.json', {'source_sha256': self.source,
                                                 'bytes': self.pdf.stat().st_size})
        self.inspections = self.json('inspections.json', {
            'schema': 'italian-open-2025-image-inspections/v1',
            'source_sha256': self.source,
            'records': [{'page': 1, 'row': 1, 'reason': 'agreement_sample',
                         'render_sha256': digest(self.render.read_bytes()),
                         'bbox': self.rows[0]['citation']['region']['bbox'],
                         'inspector': 'independent-inspector', 'fields_raw': self.fields.copy(),
                         'uncertain_fields': []}]})
        self.stage = self.root / 'stage.json'
        self.store = self.root / 'stage.sqlite'

    def write(self, name, raw):
        path = self.root / name
        path.write_bytes(raw)
        return path

    def json(self, name, value):
        return self.write(name, (json.dumps(value, sort_keys=True) + '\n').encode())

    def run_cli(self, *extra):
        args = [sys.executable, str(SCRIPT), '--pdf', str(self.pdf), '--packet', str(self.packet),
                '--packet-sha256', digest(self.packet.read_bytes()), '--second-pass', str(self.second),
                '--second-pass-sha256', digest(self.second.read_bytes()), '--receipt', str(self.receipt),
                '--receipt-sha256', digest(self.receipt.read_bytes()), '--expected-source-sha256', self.source,
                '--render', str(self.render), '--parser-version', 'italian-open-test/v1',
                '--output', str(self.stage), *extra]
        return subprocess.run(args, capture_output=True, text=True)

    def test_roles_are_accounted_without_duplicate_import_and_replay_is_idempotent(self):
        first = self.run_cli('--inspections', str(self.inspections),
                             '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(first.returncode, 0, first.stderr)
        stage = json.loads(self.stage.read_text())
        self.assertEqual(stage['counts']['cited_positions'], 4)
        self.assertEqual(stage['counts']['verified_staged_versions'], 1)
        self.assertEqual(stage['counts']['non_primary_positions'], 3)
        self.assertEqual(len(stage['observation_versions']), 1)
        self.assertEqual(stage['observation_versions'][0]['raw_fields']['Penalit\u00e0'], 'PG')
        self.assertEqual(self.run_cli('--inspections', str(self.inspections),
                             '--inspections-sha256', digest(self.inspections.read_bytes())).returncode, 0)
        args = [sys.executable, str(SCRIPT), '--import-stage', str(self.stage),
                '--stage-sha256', digest(self.stage.read_bytes()), '--sqlite-store', str(self.store)]
        self.assertEqual(subprocess.run(args, capture_output=True).returncode, 0)
        self.assertEqual(subprocess.run(args, capture_output=True).returncode, 0)
        with sqlite3.connect(self.store) as db:
            self.assertEqual(db.execute('select count(*) from observation_versions').fetchone()[0], 1)
            self.assertEqual(db.execute('select count(*) from non_primary_positions').fetchone()[0], 3)

    def test_mismatched_source_hash_rejects_before_output(self):
        result = self.run_cli('--expected-source-sha256', '0' * 64)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.stage.exists())

    def test_missing_inspection_and_changed_render_reject_without_output(self):
        missing = self.run_cli('--inspections', str(self.inspections),
                               '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(missing.returncode, 0, missing.stderr)
        self.stage.unlink()
        inspection = json.loads(self.inspections.read_text())
        inspection['records'] = []
        self.json('inspections.json', inspection)
        result = self.run_cli('--inspections', str(self.inspections),
                              '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.stage.exists())
        self.render.write_bytes(b'changed')
        result = self.run_cli('--compare-only')
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.stage.exists())

    def test_changed_parser_version_and_failed_import_keep_store_consistent(self):
        result = self.run_cli('--inspections', str(self.inspections),
                              '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        original = json.loads(self.stage.read_text())
        args = [sys.executable, str(SCRIPT), '--import-stage', str(self.stage),
                '--stage-sha256', digest(self.stage.read_bytes()), '--sqlite-store', str(self.store)]
        self.assertEqual(subprocess.run(args, capture_output=True).returncode, 0)
        changed_stage = self.root / 'changed-stage.json'
        changed = json.loads(self.stage.read_text())
        changed['parser_version'] = 'italian-open-test/v2'
        changed['observation_versions'][0]['parser_version'] = 'italian-open-test/v2'
        changed['observation_versions'][0]['id'] += '-v2'
        changed_stage.write_text(json.dumps(changed))
        changed_args = [sys.executable, str(SCRIPT), '--import-stage', str(changed_stage),
                        '--stage-sha256', digest(changed_stage.read_bytes()),
                        '--sqlite-store', str(self.store)]
        self.assertEqual(subprocess.run(changed_args, capture_output=True).returncode, 0)
        with sqlite3.connect(self.store) as db:
            self.assertEqual(db.execute('select count(*) from observation_versions').fetchone()[0], 2)
            self.assertEqual(db.execute('select count(distinct parser_version) from observation_versions').fetchone()[0], 2)
        # A conflicting version for an existing source/parser must roll back the whole import.
        changed['parser_version'] = original['parser_version']
        changed['observation_versions'][0]['parser_version'] = original['parser_version']
        changed_stage.write_text(json.dumps(changed))
        changed_args[5] = digest(changed_stage.read_bytes())
        self.assertEqual(subprocess.run(changed_args, capture_output=True).returncode, 2)
        with sqlite3.connect(self.store) as db:
            self.assertEqual(db.execute('select count(*) from observation_versions').fetchone()[0], 2)

    def test_disagreement_needs_cited_inspection_and_uncertainty_is_retained(self):
        second = json.loads(self.second.read_text())
        second['rows'][0]['fields_raw']['Profondit\u00e0 realizzata'] = '28'
        self.json('second.json', second)
        comparison = self.run_cli('--compare-only')
        self.assertEqual(comparison.returncode, 0, comparison.stderr)
        report = json.loads(self.stage.read_text())
        self.assertEqual(report['disagreements'][0]['fields'], ['Profondit\u00e0 realizzata'])
        self.stage.unlink()
        rejected = self.run_cli('--inspections', str(self.inspections),
                                '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(rejected.returncode, 2)
        inspection = json.loads(self.inspections.read_text())
        inspection['records'][0]['reason'] = 'disagreement'
        inspection['records'][0]['uncertain_fields'] = ['Profondit\u00e0 realizzata']
        self.json('inspections.json', inspection)
        accepted = self.run_cli('--inspections', str(self.inspections),
                                '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        stage = json.loads(self.stage.read_text())
        self.assertEqual(stage['counts']['verified_staged_versions'], 0)
        self.assertEqual(stage['counts']['unresolved_positions'], 1)

    def test_existing_world_cup_isolated_import_still_replays(self):
        stage = {'schema': 'cmas-worldcup-2026-verified-stage/v1',
                 'source_sha256': self.source, 'parser_version': 'worldcup-test/v1',
                 'counts': {'cited_positions': 1, 'verified_staged_versions': 1,
                            'unresolved_positions': 0}, 'unresolved_positions': [],
                 'observation_versions': [{'id': 'observation-version:synthetic',
                    'source_sha256': self.source, 'parser_version': 'worldcup-test/v1',
                    'source_position': {'id': 'source-position:synthetic'},
                    'raw_fields': {'AP (m)': '30'}, 'interpreted_fields': {}}]}
        path = self.json('worldcup-stage.json', stage)
        store = self.root / 'worldcup.sqlite'
        cmd = [sys.executable, str(WORLD_CUP), '--import-stage', str(path),
               '--stage-sha256', digest(path.read_bytes()), '--sqlite-store', str(store)]
        self.assertEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
        self.assertEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
        with sqlite3.connect(store) as db:
            self.assertEqual(db.execute('select count(*) from observation_versions').fetchone()[0], 1)

    def test_cross_page_duplicate_uses_row_evidence_when_render_hashes_differ(self):
        other_render = self.write('second-page.jpg', b'\xff\xd8\xff\xe0different synthetic jpg\xff\xd9')
        packet = json.loads(self.packet.read_text())
        duplicate = packet['pages'][0]['rows'].pop()
        packet['pages'][0]['visual_row_count'] = 3
        duplicate['row'] = 1
        duplicate['citation']['page'] = 2
        duplicate['citation']['region']['render_sha256'] = digest(other_render.read_bytes())
        duplicate['duplicate_evidence'] = 'Repeated summary row on another page'
        packet['pages'].append({'page': 2, 'disposition': 'summary', 'visual_row_count': 1,
            'render': {'format': 'jpg', 'sha256': digest(other_render.read_bytes()),
                       'pixel_width': 10, 'pixel_height': 10}, 'rows': [duplicate]})
        self.json('packet.json', packet)
        second = json.loads(self.second.read_text())
        second['rows'][-1]['page'] = 2
        second['rows'][-1]['row'] = 1
        self.json('second.json', second)
        result = self.run_cli('--render', str(other_render), '--inspections', str(self.inspections),
                              '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads(self.stage.read_text())
        self.assertEqual(stage['counts']['duplicate_rendered_rows'], 1)
        self.assertEqual(stage['counts']['verified_staged_versions'], 1)

    def test_blank_none_and_empty_text_agree_without_changing_raw_values(self):
        packet = json.loads(self.packet.read_text())
        packet['pages'][0]['rows'][0]['fields_raw']['Punteggio'] = None
        self.json('packet.json', packet)
        second = json.loads(self.second.read_text())
        second['rows'][0]['fields_raw']['Punteggio'] = ''
        self.json('second.json', second)
        inspection = json.loads(self.inspections.read_text())
        inspection['records'][0]['fields_raw']['Punteggio'] = None
        self.json('inspections.json', inspection)
        comparison = self.run_cli('--compare-only')
        self.assertEqual(comparison.returncode, 0, comparison.stderr)
        self.assertEqual(json.loads(self.stage.read_text())['disagreements'], [])
        self.stage.unlink()
        result = self.run_cli('--inspections', str(self.inspections),
                              '--inspections-sha256', digest(self.inspections.read_bytes()))
        self.assertEqual(result.returncode, 0, result.stderr)
        row = json.loads(self.stage.read_text())['observation_versions'][0]
        self.assertIsNone(row['raw_fields']['Punteggio'])
        self.assertIsNone(row['first_pass_raw_fields']['Punteggio'])
        self.assertEqual(row['second_pass_raw_fields']['Punteggio'], '')

    def test_nonblank_changes_remain_disagreements(self):
        packet = json.loads(self.packet.read_text())
        row = packet['pages'][0]['rows'][0]['fields_raw']
        row['Punteggio'] = None
        row['Profondit\u00e0 realizzata'] = '29 [crossed out]'
        self.json('packet.json', packet)
        second = json.loads(self.second.read_text())
        raw = second['rows'][0]['fields_raw']
        raw['Punteggio'] = '--'
        raw['Profondit\u00e0 realizzata'] = '29'
        raw['Nome'] = 'Ada.'
        self.json('second.json', second)
        result = self.run_cli('--compare-only')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.stage.read_text())['disagreements'][0]['fields'],
                         ['Nome', 'Profondit\u00e0 realizzata', 'Punteggio'])
