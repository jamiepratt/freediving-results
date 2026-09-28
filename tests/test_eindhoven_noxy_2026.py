import hashlib
import json
import subprocess
import sys
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/eindhoven_noxy_2026.py'


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def corpus(tmp_path):
    raw = tmp_path / 'raw'
    raw.mkdir(parents=True)
    sources = {
        'noxy5-competition': {'competition_id': 5, 'start_date': '2026-05-09', 'end_date': '2026-05-09', 'organization': 'AIDA'},
        'noxy5-sessions': [{'session_id': 22, 'competition_id': 5, 'discipline': 'DYN', 'official_start_time': '2026-05-09 16:15:00'}],
        'noxy5-results-snapshot': {'snapshot': {'snapshot_id': 3, 'competition_id': 5, 'comp_start_date': '2026-05-09'},
            'rows': [
                {'row_id': 10, 'snapshot_id': 3, 'row_type': 'RESULT', 'attempt_id': 70, 'session_id': 22,
                 'discipline': 'DYN', 'entry_id': 4, 'entry_sess_id': 8, 'result_val': '100.000',
                 'result_final_val': '50.000', 'card_status': 'WHITE', 'penalties_json': '[]', 'rank_open': 1},
                {'row_id': 11, 'snapshot_id': 3, 'row_type': 'OVERALL', 'attempt_id': None,
                 'session_id': None, 'discipline': 'OVERALL', 'entry_id': 4, 'rank_open': 1}]},
        'noxy5-attempts': [
            {'attempt_id': 70, 'session_id': 22, 'discipline': 'DYN', 'entry_id': 4, 'entry_sess_id': 8,
             'result_val': '100.000', 'result_final_val': '50.000', 'card_status': 'WHITE',
             'penalties_json': '[]', 'judge_notes': 'ok'},
            {'attempt_id': 71, 'session_id': 22, 'discipline': 'DYN', 'entry_id': 5, 'entry_sess_id': 9,
             'result_val': None, 'result_final_val': None, 'card_status': None,
             'penalties_json': None, 'judge_notes': None}],
    }
    fetches = []
    for key, value in sources.items():
        body = canonical(value)
        headers = b'HTTP/2 200\r\ncontent-type: application/json; charset=utf-8\r\n'
        (raw / f'{key}.body').write_bytes(body)
        (raw / f'{key}.headers').write_bytes(headers)
        fetches.append({'key': key, 'body': {'path': f'raw/{key}.body', 'bytes': len(body),
                         'sha256': hashlib.sha256(body).hexdigest()},
                        'headers': {'path': f'raw/{key}.headers', 'bytes': len(headers),
                         'sha256': hashlib.sha256(headers).hexdigest()},
                        'content_type': 'application/json; charset=utf-8', 'http_status': 200,
                        'requested_url': 'https://noxyapp.com/api/competitions/5/results-snapshot' if key == 'noxy5-results-snapshot' else f'https://noxyapp.com/api/competitions/5/{key}',
                        'final_url': 'https://noxyapp.com/api/competitions/5/results-snapshot' if key == 'noxy5-results-snapshot' else f'https://noxyapp.com/api/competitions/5/{key}',
                        'observed_at': '2026-09-28T16:27:24Z'})
    (tmp_path / 'manifest.json').write_bytes(canonical({'fetches': fetches}))
    roster = {'leads': [{'id': 'eindhoven-2026:noxy-snapshot', 'citation': {
        'url': 'https://noxyapp.com/api/competitions/5/results-snapshot',
        'sha256': fetches[2]['body']['sha256'], 'locator': 'snapshot.rows'},
        'competition_date': '2026-05-09'}]}
    (tmp_path / 'roster.json').write_bytes(canonical(roster))
    return tmp_path


def run(source, output):
    return subprocess.run([sys.executable, str(SCRIPT), 'build', '--corpus', str(source),
                           '--output', str(output)], capture_output=True, text=True)


def test_packet_separates_positions_aggregates_and_endpoint_records(tmp_path):
    source = corpus(tmp_path / 'source')
    out = tmp_path / 'packet.json'
    result = run(source, out)
    assert result.returncode == 0, result.stderr
    packet = json.loads(out.read_text())
    assert packet['counts'] == {'result_rows': 1, 'overall_rows': 1, 'endpoint_records': 2,
                                'linked_result_endpoint_records': 1, 'endpoint_only_records': 1,
                                'confirmed_distinct_attempts': None}
    row = packet['result_rows'][0]
    assert row['citation']['json_pointer'] == '/rows/0'
    assert row['citation']['source_sha256'] == hashlib.sha256((source / 'raw/noxy5-results-snapshot.body').read_bytes()).hexdigest()
    assert row['raw_fields']['rank_open'] == 1
    assert row['session']['discipline'] == 'DYN'
    assert row['event_date'] == '2026-05-09'
    assert packet['overall_rows'][0]['citation']['json_pointer'] == '/rows/1'
    assert packet['endpoint_records'][0]['citation']['json_pointer'] == '/0'
    assert packet['endpoint_records'][0]['raw_fields']['judge_notes'] == 'ok'
    assert packet['endpoint_records'][1]['disposition'] == 'endpoint_only'
    assert packet['relationships'] == [{'basis': 'equal attempt_id and exact common fields',
        'attempt_id': 70, 'result_pointer': '/rows/0', 'endpoint_pointer': '/0'}]
    assert packet['confirmed_distinct_attempts'] is None
    first = out.read_bytes()
    assert run(source, out).returncode == 0
    assert out.read_bytes() == first


def test_source_hash_mismatch_fails_closed(tmp_path):
    source = corpus(tmp_path / 'source')
    (source / 'raw/noxy5-attempts.body').write_bytes(b'[]')
    result = run(source, tmp_path / 'packet.json')
    assert result.returncode != 0
    assert 'source hash mismatch' in result.stderr


def test_shared_id_with_conflicting_fields_is_not_linked(tmp_path):
    source = corpus(tmp_path / 'source')
    body = source / 'raw/noxy5-attempts.body'
    attempts = json.loads(body.read_text())
    attempts[0]['result_final_val'] = '51.000'
    data = canonical(attempts)
    body.write_bytes(data)
    manifest = json.loads((source / 'manifest.json').read_text())
    manifest['fetches'][3]['body']['sha256'] = hashlib.sha256(data).hexdigest()
    manifest['fetches'][3]['body']['bytes'] = len(data)
    (source / 'manifest.json').write_bytes(canonical(manifest))
    result = run(source, tmp_path / 'packet.json')
    assert result.returncode == 0, result.stderr
    packet = json.loads((tmp_path / 'packet.json').read_text())
    assert packet['relationships'] == []
    assert packet['result_rows'][0]['disposition'] == 'shared_id_conflicting_fields'
    assert packet['endpoint_records'][0]['disposition'] == 'shared_id_conflicting_fields'
    assert packet['uncertainties']
