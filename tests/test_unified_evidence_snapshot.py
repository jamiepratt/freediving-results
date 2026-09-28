import hashlib
import json
import sqlite3
import subprocess
import sys
import unittest
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'unified_evidence_snapshot.py'


def write(path, value):
    path.write_text(json.dumps(value), encoding='utf-8')
    return path


def build(tmp_path):
    baseline = write(tmp_path / 'baseline.json', {
        'schema': 'census-evidence/v1', 'cutoff': '2026-09-28T11:27:24Z',
        'positions': [{'id': 'same-id', 'event_name': 'A', 'event_date': None,
                       'parsed_fields': {'discipline': 'DYN'}, 'raw_fields': {'rank': '1'},
                       'observation_refs': [{'parser_version': 'p/1', 'citation': 'page 1 line 2'}]}],
        'gaps': [{'id': 'g1', 'status': 'unchecked'}],
        'sources': [{'id': 'sha256:abc', 'acquisition_id': 'acq1'}],
        'events': [], 'relationships': [], 'projection_provenance': {'imported_jobs': 1},
        'distinct_attempts': None})
    visual = write(tmp_path / 'visual.json', {
        'schema': 'visual/v1', 'owner_review_status': 'unreviewed',
        'source': {'id': 'same-id', 'acquisition_id': 'acq2'},
        'event_date_calendar': '2026-05-08',
        'pages': [{'page': 1, 'category_raw': 'Women', 'discipline_raw': 'STA',
                   'rows': [{'id': 'same-id', 'fields': {'Nome': 'Ada'}, 'citation': {'page': 1}}],
                   'aggregate_rows': [{'id': 'agg1', 'fields': {'Nome': 'Team'}, 'citation': {'page': 1}}]}],
        'counts': {'confirmed_distinct_attempts': None}})
    excluded = write(tmp_path / 'unfinished.json', {'pages': [{'rows': [1]}]})
    args = [sys.executable, str(SCRIPT), 'build', '--cutoff', '2026-09-28T12:00:00Z',
            '--input', f'baseline={baseline}', '--input', f'visual={visual}',
            '--excluded', f'komaros={excluded}:incomplete-page-ledger',
            '--output-dir', str(tmp_path / 'out')]
    return args, baseline, visual, excluded


def test_snapshot_preserves_rows_provenance_and_exclusions(tmp_path):
    args, baseline, visual, excluded = build(tmp_path)
    subprocess.run(args, check=True)
    out = tmp_path / 'out'
    manifest = json.loads((out / 'manifest.json').read_text())
    assert manifest['schema'] == 'unified-evidence-snapshot/v1'
    assert manifest['confirmed_distinct_attempts'] is None
    assert manifest['inputs']['komaros']['status'] == 'excluded'
    assert manifest['inputs']['komaros']['sha256'] == hashlib.sha256(excluded.read_bytes()).hexdigest()
    assert manifest['inputs']['komaros']['observed_collections'] == {'pages': 1, 'pages.rows': 1}
    assert manifest['inputs']['komaros']['record_count'] == 0
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        assert db.execute('select count(*) from records where source_name="baseline" and collection="positions"').fetchone()[0] == 1
        rows = db.execute('select record_id,kind,raw_json,discipline,event_date,review_status from records where source_name="visual" and collection="pages.rows"').fetchall()
        assert len(rows) == 1
        assert rows[0][1] == 'candidate_position'
        assert json.loads(rows[0][2])['fields']['Nome'] == 'Ada'
        assert rows[0][3:] == ('STA', '2026-05-08', 'unreviewed')
        assert db.execute('select kind from records where source_name="visual" and collection="pages.aggregate_rows"').fetchone()[0] == 'aggregate'
        assert db.execute('select count(distinct record_id) from records').fetchone()[0] == db.execute('select count(*) from records').fetchone()[0]
        assert db.execute('select count(*) from records where source_id="same-id"').fetchone()[0] == 2
    assert manifest['inputs']['visual']['collections']['pages.rows'] == 1
    assert manifest['inputs']['visual']['collections']['pages.aggregate_rows'] == 1


def test_snapshot_replay_is_byte_identical(tmp_path):
    args, *_ = build(tmp_path)
    subprocess.run(args, check=True)
    out = tmp_path / 'out'
    first = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir()}
    subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)], check=True)
    second = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir()}
    assert first == second


def test_corrupt_input_keeps_last_good_snapshot(tmp_path):
    args, baseline, visual, _ = build(tmp_path)
    subprocess.run(args, check=True)
    out = tmp_path / 'out'
    before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir() if p.is_file()}
    visual.write_text('{corrupt', encoding='utf-8')
    assert subprocess.run(args, capture_output=True).returncode != 0
    after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir() if p.is_file()}
    assert before == after


def test_workbook_scores_are_aggregates_and_date_scope_queryable(tmp_path):
    gia = write(tmp_path / 'gia.json', {'schema': 'gia/v1', 'sheets': [
        {'name': 'Napoli', 'role': 'event_table', 'date_scope': ['2025-02-01', '2025-02-02', 'cell G4'],
         'rows': [{'kind': 'combined_score', 'citation': 'Napoli!B1', 'date_scope': ['2025-02-01', '2025-02-02', 'cell G4']},
                  {'kind': 'formula_placeholder', 'citation': 'Napoli!B2'}]}]})
    out = tmp_path / 'out'
    subprocess.run([sys.executable, str(SCRIPT), 'build', '--cutoff', '2026-09-28T12:00:00Z',
                    '--input', f'gia={gia}', '--output-dir', str(out)], check=True)
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        assert db.execute('select kind,date_from,date_to,event_date from records where record_path="sheets[0].rows[0]"').fetchone() == ('aggregate', '2025-02-01', '2025-02-02', None)
        assert db.execute('select kind from records where record_path="sheets[0].rows[1]"').fetchone()[0] == 'other'


class SnapshotContractTest(unittest.TestCase):
    def test_records(self):
        with tempfile.TemporaryDirectory() as d:
            test_snapshot_preserves_rows_provenance_and_exclusions(Path(d))

    def test_corrupt_input(self):
        with tempfile.TemporaryDirectory() as d:
            test_corrupt_input_keeps_last_good_snapshot(Path(d))

    def test_workbook(self):
        with tempfile.TemporaryDirectory() as d:
            test_workbook_scores_are_aggregates_and_date_scope_queryable(Path(d))

    def test_replay(self):
        with tempfile.TemporaryDirectory() as d:
            test_snapshot_replay_is_byte_identical(Path(d))


if __name__ == "__main__":
    unittest.main()
