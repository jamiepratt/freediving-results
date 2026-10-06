"""Private historical AIDA staging through its owned command interface."""

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/historical_aida_stage.py'
HEADERS = ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline', 'OT',
           'AP', 'RP', 'Card', 'Points', 'Remarks')


class HistoricalAidaStageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / 'private' / 'stage.json'

    def source(self, day='2024-06-24', selector='day_4', rows=None):
        rows = rows or [('1', 'Ada &amp; Eve', 'GER', 'F', 'DYN', '09:40',
                         '150 m', '0', 'DQsp', '0', '')]
        body = (f'<html><li class="active"><a class="days" id="{selector}">{day}</a></li>'
                '<table id="table_ajax"><thead><tr>'
                + ''.join(f'<th>{name}</th>' for name in HEADERS)
                + '</tr></thead><tbody id="body_ajax">'
                + ''.join('<tr>' + ''.join(f'<td>{value}</td>' for value in row)
                          + '</tr>' for row in rows)
                + '</tbody></table></html>').encode()
        source = self.root / 'raw' / f'{day}.html'
        source.parent.mkdir(exist_ok=True)
        source.write_bytes(body)
        sha = hashlib.sha256(body).hexdigest()
        url = 'https://www.aidainternational.org/EventPage/1234'
        receipt = self.root / f'{day}.receipt.json'
        receipt.write_text(json.dumps({
            'schema': 'aida-selected-html-browser-receipt/v1',
            'requested_url': url, 'final_url': url, 'http_status': 200,
            'content_type': 'text/html; charset=UTF-8',
            'response_time': '2026-10-07T00:00:00Z',
            'selected_view': {'date': day, 'selector': selector},
            'body': {'path': f'raw/{day}.html', 'bytes': len(body), 'sha256': sha},
            'source_citation': {'url': url, 'selected_date': day,
                                'table': 'table_ajax', 'tbody': 'body_ajax'},
        }))
        return source, receipt, sha

    def run_stage(self, source, receipt, sha, output=None):
        return subprocess.run([sys.executable, str(SCRIPT), str(source), str(receipt),
                               str(output or self.output), '--expected-source-sha256', sha],
                              capture_output=True, text=True)

    def test_stages_raw_rows_with_cited_private_unreviewed_versions(self):
        source, receipt, sha = self.source()
        result = self.run_stage(source, receipt, sha)
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads(self.output.read_text())
        self.assertEqual(stage['schema'], 'historical-aida-private-stage/v1')
        self.assertEqual(stage['summary']['source_positions'], 1)
        self.assertEqual(stage['summary']['observation_versions'], 1)
        self.assertIsNone(stage['summary']['distinct_attempts'])
        view = stage['views'][0]
        self.assertEqual(view['source']['sha256'], sha)
        self.assertEqual(view['source_html'], source.read_text())
        self.assertEqual(view['source']['selected_date'], '2024-06-24')
        self.assertEqual(view['source']['selector'], 'day_4')
        self.assertTrue(view['parser_version'])
        self.assertIsNone(view['finality'])
        row = view['positions'][0]
        self.assertEqual(row['review_status'], 'unreviewed')
        self.assertIsNone(row['category'])
        self.assertEqual(row['cells']['RP']['value'], '0')
        self.assertEqual(row['cells']['Card']['value'], 'DQsp')
        self.assertEqual(row['cells']['Points']['value'], '0')
        self.assertEqual(row['cells']['Remarks']['value'], '')
        self.assertIn('Ada &amp; Eve', row['cells']['Diver']['source_html'])
        self.assertTrue(row['observation_version'])
        self.assertEqual(self.output.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o600)

    def test_accumulates_views_and_exact_replay_preserves_stage_bytes(self):
        first = self.source()
        second = self.source('2024-06-25', 'day_5')
        for source in (first, second):
            result = self.run_stage(*source)
            self.assertEqual(result.returncode, 0, result.stderr)
        before = self.output.read_bytes()
        before_stat = self.output.stat()
        stage = json.loads(before)
        self.assertEqual(stage['summary']['source_views'], 2)
        self.assertEqual(stage['summary']['observation_versions'], 2)
        versions = [view['positions'][0]['observation_version'] for view in stage['views']]
        self.assertEqual(len(set(versions)), 2)
        for source in (first, second):
            result = self.run_stage(*source)
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_bytes(), before)
        self.assertEqual(self.output.stat().st_mtime_ns, before_stat.st_mtime_ns)

    def test_rejects_changed_existing_view_even_when_replaying_another_view(self):
        first = self.source()
        second = self.source('2024-06-25', 'day_5')
        for source in (first, second):
            self.assertEqual(self.run_stage(*source).returncode, 0)
        stage = json.loads(self.output.read_text())
        changed = next(view for view in stage['views']
                       if view['source']['selected_date'] == '2024-06-25')
        changed['positions'][0]['cells']['Points']['value'] = '999'
        self.output.write_text(json.dumps(stage))
        tampered = self.output.read_bytes()
        result = self.run_stage(*first)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('stage', result.stderr)
        self.assertEqual(self.output.read_bytes(), tampered)

    def test_python_entrypoint_rejects_public_existing_stage_on_replay(self):
        spec = importlib.util.spec_from_file_location('historical_aida_stage', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        source, receipt, sha = self.source()
        module.stage(source, receipt, self.output, sha)
        self.output.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'private'):
            module.stage(source, receipt, self.output, sha)

    def test_rejects_nonprivate_existing_directory_without_writing(self):
        self.output.parent.mkdir(mode=0o755)
        result = self.run_stage(*self.source())
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('private', result.stderr)
        self.assertFalse(self.output.exists())

    def test_rehashed_staged_payload_still_requires_original_field_replay(self):
        source = self.source()
        self.assertEqual(self.run_stage(*source).returncode, 0)
        stage = json.loads(self.output.read_text())
        stage['views'][0]['positions'][0]['cells']['Card']['value'] = 'WHITE'
        del stage['payload_sha256']
        stage['payload_sha256'] = hashlib.sha256(json.dumps(
            stage, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        self.output.write_text(json.dumps(stage))
        before = self.output.read_bytes()
        result = self.run_stage(*source)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('retained evidence', result.stderr)
        self.assertEqual(self.output.read_bytes(), before)

    def test_rejects_source_hash_change_and_receipt_rebinding_without_overwrite(self):
        source, receipt, sha = self.source()
        self.assertEqual(self.run_stage(source, receipt, sha).returncode, 0)
        before = self.output.read_bytes()
        source.write_bytes(source.read_bytes().replace(b'DQsp', b'WHITE'))
        changed = self.run_stage(source, receipt, sha)
        self.assertEqual(changed.returncode, 2, changed.stderr)
        self.assertIn('receipt', changed.stderr)
        self.assertEqual(self.output.read_bytes(), before)
        source, receipt, sha = self.source()
        changed = self.run_stage(source, receipt, '0' * 64)
        self.assertEqual(changed.returncode, 2, changed.stderr)
        self.assertIn('expected source sha256', changed.stderr)
        evidence = json.loads(receipt.read_text())
        evidence['response_time'] = '2026-10-07T00:00:01Z'
        receipt.write_text(json.dumps(evidence))
        changed = self.run_stage(source, receipt, sha)
        self.assertEqual(changed.returncode, 2, changed.stderr)
        self.assertIn('retained evidence', changed.stderr)
        self.assertEqual(self.output.read_bytes(), before)

    def test_keeps_white_red_zero_blank_and_unresolved_source_positions(self):
        rows = [('1', 'A', 'GER', 'M', 'DYNB', '09:40', '100 m', '101 m', 'WHITE', '101', ''),
                ('2', 'B', '', 'F', 'DYNB', '', '110 m', '0', 'RED', '0', 'Early start'),
                ('3', 'incomplete')]
        source = self.source(rows=rows)
        result = self.run_stage(*source)
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = json.loads(self.output.read_text())
        self.assertEqual(stage['summary']['source_positions'], 3)
        self.assertEqual(stage['summary']['parsed'], 2)
        self.assertEqual(stage['summary']['parse_unresolved'], 1)
        self.assertEqual(stage['summary']['review_unresolved'], 3)
        self.assertEqual(stage['summary']['observation_versions'], 2)
        positions = stage['views'][0]['positions']
        self.assertEqual(positions[0]['cells']['Card']['value'], 'WHITE')
        self.assertEqual(positions[1]['cells']['Card']['value'], 'RED')
        self.assertEqual(positions[1]['cells']['Nationality']['value'], '')
        self.assertEqual(positions[1]['cells']['Points']['value'], '0')
        self.assertEqual(positions[2]['source_cells'], ['<td>3</td>', '<td>incomplete</td>'])
        self.assertEqual(positions[2]['disposition'], 'unresolved')
        self.assertIsNone(positions[2]['observation_version'])

    def test_rejects_repository_output_and_stage_symlink(self):
        source = self.source()
        forbidden = SCRIPT.parent / '.historical-aida-test-forbidden.json'
        result = self.run_stage(*source, output=forbidden)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('outside repository', result.stderr)
        self.assertFalse(forbidden.exists())
        self.output.parent.mkdir(mode=0o700)
        target = self.root / 'target.json'
        target.write_text('preserve')
        self.output.symlink_to(target)
        result = self.run_stage(*source)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('symlink', result.stderr)
        self.assertEqual(target.read_text(), 'preserve')

    def test_rejects_malformed_existing_stage_through_owned_error_interface(self):
        source = self.source()
        self.output.parent.mkdir(mode=0o700)
        self.output.write_text('[]')
        self.output.chmod(0o600)
        result = self.run_stage(*source)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('stage object', result.stderr)
        self.assertEqual(self.output.read_text(), '[]')

    def test_cli_refuses_2023_without_creating_or_changing_private_stage(self):
        older = self.source('2023-06-24', 'day_4')
        rejected = self.run_stage(*older)
        self.assertEqual(rejected.returncode, 2, rejected.stderr)
        self.assertIn('2024 selected date', rejected.stderr)
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.parent.exists())
        current = self.source()
        self.assertEqual(self.run_stage(*current).returncode, 0)
        before = self.output.read_bytes()
        before_stat = self.output.stat()
        rejected = self.run_stage(*older)
        self.assertEqual(rejected.returncode, 2, rejected.stderr)
        self.assertEqual(self.output.read_bytes(), before)
        self.assertEqual(self.output.stat().st_mtime_ns, before_stat.st_mtime_ns)

    def test_python_refuses_2023_without_creating_or_changing_private_stage(self):
        spec = importlib.util.spec_from_file_location('historical_aida_stage', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        source, receipt, sha = self.source('2023-06-24', 'day_4')
        with self.assertRaisesRegex(ValueError, '2024 selected date'):
            module.stage(source, receipt, self.output, sha)
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.parent.exists())
        current = self.source()
        module.stage(current[0], current[1], self.output, current[2])
        before = self.output.read_bytes()
        before_stat = self.output.stat()
        with self.assertRaisesRegex(ValueError, '2024 selected date'):
            module.stage(source, receipt, self.output, sha)
        self.assertEqual(self.output.read_bytes(), before)
        self.assertEqual(self.output.stat().st_mtime_ns, before_stat.st_mtime_ns)


if __name__ == '__main__':
    unittest.main()
