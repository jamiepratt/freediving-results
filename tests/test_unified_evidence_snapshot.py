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


def test_required_complete_packet_cannot_be_omitted(tmp_path):
    args, baseline, visual, excluded = build(tmp_path)
    complete = write(tmp_path / 'komaros-complete.json', {
        'schema': 'komaros-visual-evidence/v1',
        'pages': [{'page': 1, 'rows': [{'id': 'printed-row-1'}]}],
    })
    digest = hashlib.sha256(complete.read_bytes()).hexdigest()
    args += ['--required-input', f'komaros-visual={digest}']
    missing = subprocess.run(args, capture_output=True, text=True)
    assert missing.returncode != 0
    assert 'required input missing: komaros-visual' in missing.stderr

    args += ['--input', f'komaros-visual={complete}']
    subprocess.run(args, check=True)
    out = tmp_path / 'out'
    manifest = json.loads((out / 'manifest.json').read_text())
    assert manifest['required_inputs'] == {'komaros-visual': digest}
    assert manifest['inputs']['komaros-visual']['status'] == 'included'
    assert manifest['inputs']['komaros']['status'] == 'excluded'
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        assert db.execute('select count(*) from records where source_name="komaros-visual" and collection="pages.rows"').fetchone()[0] == 1

    first = hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest()
    subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)], check=True)
    assert hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest() == first


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


def test_extend_preserves_historical_snapshot_and_requires_roatan_packet(tmp_path):
    args, *_ = build(tmp_path)
    subprocess.run(args, check=True)
    base = tmp_path / 'out'
    old_db = hashlib.sha256((base / 'snapshot.sqlite').read_bytes()).hexdigest()
    packet = write(tmp_path / 'roatan.json', {'schema': 'roatan-2026-cwt-men-private-census/v1',
        'issue_namespace': '#8', 'confirmed_distinct_attempts': None,
        'counts': {'source_objects': 1, 'source_positions': 1, 'observation_versions': 2,
                   'historical_extraction_acceptances': 0, 'confirmed_distinct_attempts': None},
        'positions': [{'unit': 3551, 'json_index_zero_based': 0, 'event_date': '2026-08-17',
                       'discipline': 'CWT', 'category': 'SENM', 'citation': {'row-index-zero-based': 0},
                       'source_id': 'sha256:' + 'a' * 64,
                       'raw_fields': {'ResResult': '65'}, 'parsed_fields': {'achieved-depth-token': '65'}}],
        'observation_versions': [{'unit': 3551, 'parser_version': 'p/1'},
                                 {'unit': 3551, 'parser_version': 'p/2'}]})
    packet_hash = hashlib.sha256(packet.read_bytes()).hexdigest()
    dest = tmp_path / 'extended'
    early = subprocess.run([sys.executable, str(SCRIPT), 'extend', '--base-dir', str(base),
                    '--cutoff', '2026-09-28T11:00:00Z',
                    '--input', f'roatan-issue8={packet}', '--required-input',
                    f'roatan-issue8={packet_hash}', '--output-dir', str(dest)], capture_output=True, text=True)
    assert early.returncode != 0
    assert 'cutoff precedes base snapshot' in early.stderr
    subprocess.run([sys.executable, str(SCRIPT), 'extend', '--base-dir', str(base),
                    '--cutoff', '2026-09-28T13:00:00Z',
                    '--input', f'roatan-issue8={packet}', '--required-input',
                    f'roatan-issue8={packet_hash}', '--output-dir', str(dest)], check=True)
    manifest = json.loads((dest / 'manifest.json').read_text())
    assert manifest['base_snapshot_sha256'] == old_db
    assert manifest['cutoff'] == '2026-09-28T13:00:00Z'
    assert manifest['confirmed_distinct_attempts'] is None
    assert manifest['inputs']['roatan-issue8']['collections'] == {'observation_versions': 2, 'positions': 1}
    with sqlite3.connect(dest / 'snapshot.sqlite') as db:
        assert db.execute('select count(*) from records where source_name="roatan-issue8" and kind="candidate_position"').fetchone()[0] == 3
        assert db.execute('select source_object_id from records where source_name="roatan-issue8" and collection="positions"').fetchone()[0] == 'sha256:' + 'a' * 64
    assert hashlib.sha256((base / 'snapshot.sqlite').read_bytes()).hexdigest() == old_db
    first = hashlib.sha256((dest / 'snapshot.sqlite').read_bytes()).hexdigest()
    subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(dest)], check=True)
    assert hashlib.sha256((dest / 'snapshot.sqlite').read_bytes()).hexdigest() == first


def test_extend_adds_two_visual_packets_without_promoting_non_results(tmp_path):
    args, *_ = build(tmp_path)
    subprocess.run(args, check=True)
    base = tmp_path / 'out'
    original_sha = hashlib.sha256((base / 'snapshot.sqlite').read_bytes()).hexdigest()
    source_hash = 'b' * 64
    world = write(tmp_path / 'world.json', {
        'schema': 'cmas-worldcup-2026-visual-evidence/v1',
        'source': {'id': 'sha256:' + source_hash, 'sha256': source_hash},
        'source_sha256': source_hash,
        'pages': [{'page': 2, 'event_date': '2026-05-26', 'discipline_raw': 'CWT',
                   'rows': [{'id': 'world-row', 'disposition': 'visual_source_position',
                             'fields': {'Last Name': 'Bai'},
                             'citation': {'page': 2, 'region': {'bbox': [1, 2, 3, 4]}}}]}]})
    italian_hash = 'c' * 64
    italian_rows = [
        {'id': name, 'disposition': disposition,
         'citation': {'page': 1, 'source_sha256': italian_hash, 'region': {'bbox': [i, 2, i + 1, 4]}},
         'fields_raw': {'Nome': name}}
        for i, (name, disposition) in enumerate([
            ('result', 'candidate_result'), ('club', 'aggregate'),
            ('total', 'summary'), ('repeat', 'duplicate_render')])]
    italian = write(tmp_path / 'italian.json', {
        'schema': 'italian-open-2025-visual-evidence/v3',
        'source_sha256': italian_hash,
        'candidate_result_appearances': [dict(italian_rows[0])],
        'pages': [{'page': 1, 'rows': italian_rows}]})
    out = tmp_path / 'extended'
    command = [sys.executable, str(SCRIPT), 'extend', '--base-dir', str(base),
               '--cutoff', '2026-09-28T13:00:00Z',
               '--input', f'worldcup={world}', '--input', f'italian={italian}',
               '--required-input', f'worldcup={hashlib.sha256(world.read_bytes()).hexdigest()}',
               '--required-input', f'italian={hashlib.sha256(italian.read_bytes()).hexdigest()}',
               '--output-dir', str(out)]
    subprocess.run(command, check=True)
    manifest = json.loads((out / 'manifest.json').read_text())
    assert set(manifest['required_inputs']) == {'worldcup', 'italian'}
    assert manifest['base_snapshot_sha256'] == original_sha
    assert set(manifest['extension_namespaces']) == {'worldcup', 'italian'}
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        rows = db.execute('select source_id,kind,source_object_id,citation_json,page,event_date '
                          'from records where source_name="italian" and collection="pages.rows" '
                          'order by record_path').fetchall()
        assert [(r[0], r[1]) for r in rows] == [
            ('result', 'candidate_position'), ('club', 'aggregate'),
            ('total', 'summary'), ('repeat', 'repeated_position')]
        assert all(r[2] == 'sha256:' + italian_hash for r in rows)
        assert all(json.loads(r[3])['region']['bbox'] for r in rows)
        assert db.execute('select source_object_id,event_date,page from records '
                          'where source_name="worldcup" and collection="pages.rows"').fetchone() == (
                              'sha256:' + source_hash, '2026-05-26', 2)
        assert db.execute('select kind from records where source_name="italian" '
                          'and collection="candidate_result_appearances"').fetchone()[0] == 'relationship'
        assert db.execute('select count(*) from records where source_name="baseline"').fetchone()[0] == 3
    before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir()}
    subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)], check=True)
    after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir()}
    assert before == after
    assert hashlib.sha256((base / 'snapshot.sqlite').read_bytes()).hexdigest() == original_sha


def test_extend_keeps_aida_positions_noxy_views_and_route_leads_separate(tmp_path):
    args, *_ = build(tmp_path)
    subprocess.run(args, check=True)
    base = tmp_path / 'out'
    aida = write(tmp_path / 'aida.json', {
        'schema': 'aida-selected-html-packet/v1',
        'source': {'sha256': 'a' * 64, 'selected_date': '2025-08-30',
                   'selector': 'day_1', 'url': 'https://example.test/results'},
        'summary': {'source_positions': 1, 'confirmed_distinct_attempts': None},
        'positions': [{'position': {'date': '2025-08-30', 'row': 2, 'tbody_row': 1},
                       'cells': {'Discipline': {'value': 'CWTB'}, 'Diver': {'value': 'Ada'}},
                       'source_html': '<tr><td>Ada</td></tr>', 'review_status': 'unreviewed'}]})
    noxy = write(tmp_path / 'noxy.json', {
        'schema': 'eindhoven-2026-noxy-private-accounting/v1',
        'confirmed_distinct_attempts': None,
        'counts': {'result_rows': 1, 'overall_rows': 1, 'endpoint_records': 2,
                   'linked_result_endpoint_records': 1, 'endpoint_only_records': 1,
                   'confirmed_distinct_attempts': None},
        'result_rows': [{'attempt_id': 10, 'event_date': '2026-05-09', 'discipline': 'DYN',
                         'session': {'discipline': 'DYN', 'raw_fields': {'session_id': 1}},
                         'citation': {'source_sha256': 'b' * 64, 'json_pointer': '/rows/1'},
                         'raw_fields': {'athlete': 'Ada'}}],
        'overall_rows': [{'event_date': '2026-05-09', 'discipline': 'OVERALL',
                          'disposition': 'aggregate_not_attempt',
                          'citation': {'source_sha256': 'b' * 64, 'json_pointer': '/rows/0'}}],
        'endpoint_records': [{'attempt_id': 10, 'disposition': 'linked_by_attempt_id',
                              'citation': {'source_sha256': 'c' * 64, 'json_pointer': '/0'}},
                             {'attempt_id': 11, 'disposition': 'endpoint_only',
                              'citation': {'source_sha256': 'c' * 64, 'json_pointer': '/1'}}],
        'relationships': [{'attempt_id': 10, 'result_pointer': '/rows/1', 'endpoint_pointer': '/0'}]})
    roster = write(tmp_path / 'roster.json', {
        'schema': 'issue55-route-roster/v3', 'cutoff': '2026-09-28T18:30:05Z',
        'summary': {'route_count': 1, 'lead_count': 2, 'confirmed_distinct_attempts': None},
        'routes': [{'id': 'aida', 'status': 'checked'}],
        'leads': [{'id': 'aida:1', 'status': 'checked'},
                  {'id': 'aida:2', 'status': 'unchecked'}]})
    inputs = {'aida-day': aida, 'eindhoven': noxy, 'route-roster': roster}
    out = tmp_path / 'extended'
    command = [sys.executable, str(SCRIPT), 'extend', '--base-dir', str(base),
               '--cutoff', '2026-09-28T19:00:00Z']
    for name, path in inputs.items():
        command += ['--input', f'{name}={path}', '--required-input',
                    f'{name}={hashlib.sha256(path.read_bytes()).hexdigest()}']
    command += ['--output-dir', str(out)]
    subprocess.run(command, check=True)
    manifest = json.loads((out / 'manifest.json').read_text())
    assert manifest['confirmed_distinct_attempts'] is None
    assert manifest['inputs']['aida-day']['collections'] == {'positions': 1}
    assert manifest['inputs']['eindhoven']['collections']['result_rows'] == 1
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        assert db.execute('select kind,event_date,discipline,source_object_id,citation_json from records '
                          'where source_name="aida-day"').fetchone() == (
                              'candidate_position', '2025-08-30', 'CWTB', 'sha256:' + 'a' * 64,
                              json.dumps({'date': '2025-08-30', 'row': 2, 'tbody_row': 1}, sort_keys=True,
                                         separators=(',', ':')))
        assert db.execute('select collection,kind from records where source_name="eindhoven" '
                          'order by collection').fetchall() == [
                              ('endpoint_records', 'endpoint_source_record'),
                              ('endpoint_records', 'endpoint_source_record'),
                              ('overall_rows', 'aggregate'),
                              ('relationships', 'relationship'),
                              ('result_rows', 'candidate_position')]
        assert db.execute('select session from records where source_name="eindhoven" '
                          'and collection="result_rows"').fetchone()[0] == (
                              '{"discipline":"DYN","raw_fields":{"session_id":1}}')
        assert db.execute('select count(*) from records where source_name="eindhoven" '
                          'and kind="observation_version"').fetchone()[0] == 0
        assert db.execute('select collection,kind from records where source_name="route-roster" '
                          'order by collection').fetchall() == [
                              ('leads', 'discovery_lead'), ('leads', 'discovery_lead'),
                              ('routes', 'discovery_route')]
    first = hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest()
    subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)], check=True)
    assert hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest() == first


def test_extend_accounts_for_ffessm_sources_positions_and_correspondences(tmp_path):
    args, *_ = build(tmp_path)
    subprocess.run(args, check=True)
    ranking = write(tmp_path / 'ranking.json', {
        'schema': 'ffessm-2025-rankings/v1',
        'counts': {'source_objects': 1, 'source_positions': 1,
                   'existing_observation_versions': 1, 'confirmed_distinct_attempts': None},
        'sources': [{'id': 'sha256:' + 'a' * 64, 'sha256': 'a' * 64, 'source_positions': 1}],
        'limits': ['source date is not exact'],
        'positions': [{'position_id': 'ranking-1', 'source_object_id': 'sha256:' + 'a' * 64,
                       'event_date': '2025-07-01', 'date_from': '2025-07-01', 'date_to': '2025-07-01',
                       'discipline': 'CWT', 'citation': 'page 2 line 3', 'coordinates': {'page': 2, 'line': 3},
                       'raw_fields': {'rank': '1'}, 'observation_refs': [{'parser_version': 'prior/1'}]}],
        'relationship_candidates': [{'kind': 'same-source-candidate', 'same_attempt': None}]})
    daily = write(tmp_path / 'daily.json', {
        'schema': 'ffessm-2025-daily/v1',
        'counts': {'source_objects': 1, 'printed_positions': 1, 'parser_observations': 1,
                   'confirmed_distinct_attempts': None},
        'sources': [{'id': 'sha256:' + 'b' * 64, 'sha256': 'b' * 64, 'printed_positions': 1}],
        'observations': [{'id': 'daily-1', 'source_id': 'sha256:' + 'b' * 64,
                          'event_date': '2025-07-01', 'discipline': 'CWT',
                          'citation': 'page 1 line 4', 'coordinates': {'page': 1, 'line': 4},
                          'raw_line': 'rank athlete depth'}]})
    links = write(tmp_path / 'links.json', {
        'schema': 'ffessm-2025-daily-relationships/v1',
        'counts': {'daily_positions': 1, 'ranking_positions': 1,
                   'shared_printed_field_correspondences': 1, 'unmatched_daily_positions': 0,
                   'confirmed_distinct_attempts': None},
        'relationships': [{'daily_position_id': 'daily-1', 'ranking_position_id': 'ranking-1',
                           'same_attempt': None, 'basis': 'printed fields'}],
        'unmatched_daily': []})
    inputs = {'ranking': ranking, 'daily': daily, 'links': links}
    out = tmp_path / 'extended'
    command = [sys.executable, str(SCRIPT), 'extend', '--base-dir', str(tmp_path / 'out'),
               '--cutoff', '2026-09-28T13:00:00Z']
    for name, path in inputs.items():
        command += ['--input', f'{name}={path}', '--required-input',
                    f'{name}={hashlib.sha256(path.read_bytes()).hexdigest()}']
    subprocess.run(command + ['--output-dir', str(out)], check=True)
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        assert db.execute('select kind,source_object_id,date_from,date_to,citation_json,page '
                          'from records where source_name="ranking" and collection="positions"').fetchone() == (
                              'candidate_position', 'sha256:' + 'a' * 64, '2025-07-01', '2025-07-01',
                              '"page 2 line 3"', 2)
        assert db.execute('select kind,source_object_id from records where source_name="daily" '
                          'and collection="observations"').fetchone() == ('candidate_position', 'sha256:' + 'b' * 64)
        assert db.execute('select source_object_id from records where source_name="ranking" '
                          'and collection="sources"').fetchone()[0] == 'sha256:' + 'a' * 64
        assert db.execute('select count(*) from records where source_name="ranking" '
                          'and collection="limits"').fetchone()[0] == 0
        assert db.execute('select kind from records where source_name="links" '
                          'and collection="relationships"').fetchone()[0] == 'relationship'
        assert db.execute("select count(*) from records where kind='observation_version'").fetchone()[0] == 0
    subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)], check=True)


def test_extend_accounts_for_apnea_aggregates_and_san_mauro_versions(tmp_path):
    args, *_ = build(tmp_path)
    subprocess.run(args, check=True)
    apnea = write(tmp_path / 'apnea.json', {
        'schema': 'apnea-academy-file-reconciliation/v1',
        'counts': {'gia_team_standings_rows': 1, 'gia_team_placeholder_rows': 1,
                   'san_mauro_team_rows': 1, 'confirmed_distinct_attempts': None},
        'gia_team': {'source': {'sha256': 'c' * 64}, 'sheet': 'Clubs',
                     'rows': [{'classification': 'club_standing', 'citation': 'Clubs!B6',
                               'cells': {'B6': 'Club'}}],
                     'placeholder': {'classification': 'formula_placeholder', 'citation': 'Clubs!B7'}},
        'individual_equivalence': {'same_original_sha256': True, 'new_rows_counted': 0,
                                   'prior_sheet_rows': 898, 'source': {'sha256': 'd' * 64}},
        'san_mauro': {'source': {'sha256': 'e' * 64}, 'event_date': '2026-03-01',
                      'rows': [{'classification': 'team_aggregate', 'citation': 'page 1 line 2',
                                'fields': {'team': 'X'}}]}})
    jpg = write(tmp_path / 'jpg.json', {
        'schema': 'san-mauro-jpg-supplement/v1',
        'counts': {'source_objects': 1, 'source_positions': 2, 'transport_rows': 2,
                   'repeated_transport_rows': 0, 'individual_positions': 1,
                   'aggregate_positions': 1, 'observation_versions': 2,
                   'manual_observation_versions': 2, 'relationship_candidates': 1,
                   'confirmed_distinct_attempts': None},
        'source_objects': [{'source_id': 'sha256:' + 'f' * 64, 'sha256': 'f' * 64,
                            'kind': 'aggregate'}],
        'positions': [{'source_id': 'sha256:' + 'f' * 64, 'kind': 'individual',
                       'citation': {'page': 1, 'row': 2}},
                      {'source_id': 'sha256:' + 'f' * 64, 'kind': 'aggregate',
                       'citation': {'page': 1, 'row': 3}}],
        'observation_versions': [{'source_id': 'sha256:' + 'f' * 64,
                                  'position': {'row': 2}, 'parser_version': 'manual/1'},
                                 {'source_id': 'sha256:' + 'f' * 64,
                                  'position': {'row': 3}, 'parser_version': 'manual/1'}],
        'relationships': [{'left_source_sha256': 'f' * 64, 'right_source_sha256': 'f' * 64,
                           'state': 'candidate'}]})
    out = tmp_path / 'extended'
    command = [sys.executable, str(SCRIPT), 'extend', '--base-dir', str(tmp_path / 'out'),
               '--cutoff', '2026-09-28T13:00:00Z']
    for name, path in {'apnea': apnea, 'jpg': jpg}.items():
        command += ['--input', f'{name}={path}', '--required-input',
                    f'{name}={hashlib.sha256(path.read_bytes()).hexdigest()}']
    subprocess.run(command + ['--output-dir', str(out)], check=True)
    with sqlite3.connect(out / 'snapshot.sqlite') as db:
        assert db.execute('select kind,source_object_id from records where source_name="apnea" '
                          'and collection="gia_team.rows"').fetchone() == ('aggregate', 'sha256:' + 'c' * 64)
        assert db.execute('select kind,event_date from records where source_name="apnea" '
                          'and collection="san_mauro.rows"').fetchone() == ('aggregate', '2026-03-01')
        assert db.execute('select count(*) from records where source_name="apnea" '
                          'and collection="individual_equivalence.rows"').fetchone()[0] == 0
        assert db.execute('select kind from records where source_name="jpg" and collection="positions" '
                          'order by record_path').fetchall() == [('candidate_position',), ('aggregate',)]
        assert db.execute("select count(*) from records where source_name='jpg' "
                          "and kind='observation_version'").fetchone()[0] == 2
        assert db.execute("select count(*) from records where source_name='jpg' "
                          "and kind='observation_version' and source_object_id=?",
                          ('sha256:' + 'f' * 64,)).fetchone()[0] == 2
        assert db.execute("select kind from records where source_name='jpg' "
                          "and collection='source_objects'").fetchone()[0] == 'source'
    subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)], check=True)


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

    def test_required_complete_packet(self):
        with tempfile.TemporaryDirectory() as d:
            test_required_complete_packet_cannot_be_omitted(Path(d))

    def test_extend(self):
        with tempfile.TemporaryDirectory() as d:
            test_extend_preserves_historical_snapshot_and_requires_roatan_packet(Path(d))

    def test_extend_two_packets(self):
        with tempfile.TemporaryDirectory() as d:
            test_extend_adds_two_visual_packets_without_promoting_non_results(Path(d))


if __name__ == "__main__":
    unittest.main()
