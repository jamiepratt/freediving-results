import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/roatan_2026_cwt_men_census.py'


def test_roatan_packet_keeps_versions_and_scoped_decisions(tmp_path):
    source = b'[{"ResResult":"65"}]'
    digest = hashlib.sha256(source).hexdigest()
    stage = tmp_path / 'stage'
    objects = stage / 'archive' / 'objects'
    objects.mkdir(parents=True)
    (objects / digest).write_bytes(source)
    artifacts = stage / 'archive' / 'derived-objects'
    artifacts.mkdir(parents=True)
    v2_artifact = hashlib.sha256(b'a').hexdigest()
    v1_artifact = hashlib.sha256(b'b').hexdigest()
    (artifacts / v2_artifact).write_bytes(b'a')
    (artifacts / v1_artifact).write_bytes(b'b')
    row = {'json_index_zero_based': 0, 'citation': {'source-sha256': digest,
            'row-index-zero-based': 0, 'view-url': 'https://example.test/result'},
           'raw': {'ParPrintName': 'LU San-Jen', 'DECLLEN_STR': '95', 'ResResult': '65',
                   'ResResultFinal': '34', 'ResPenality': '31', 'ResReasonCode': 'PEN',
                   'ResNotePenality': 'EARLY TURN, NO MARKER', 'ResNote': 'other note',
                   'ResStartNote': 'start note', 'ResRecord': 'record token'},
           'parsed': {'category': 'SENM', 'declared-depth-token': '95', 'achieved-depth-token': '65',
                      'publisher-final-depth-token': '34', 'penalty-token': '31'},
           'visible_display': {'declared_depth': '95', 'depth': '65', 'final_depth': '34',
                               'penalties': '31', 'status': 'PEN', 'notes': 'EARLY TURN, NO MARKER'}}
    packet = {'schema': 'corrected/v1', 'units': [{'unit': 3551, 'session_date': '2026-08-17',
             'source_sha256': digest, 'parser_version': 'cmas-2026-roatan-json/2',
             'job_id': 'v2', 'artifact_sha256': v2_artifact, 'view_url': 'https://example.test/result',
             'api_url': 'https://example.test/api', 'rows': [row]}]}
    packet_path = tmp_path / 'corrected.json'
    packet_path.write_text(json.dumps(packet))
    legacy = [{'job_id': 'v1', 'ordinal': 0, 'candidate_id': 'candidate',
               'source_sha256': digest, 'artifact_sha256': v1_artifact,
               'parser_version': 'cmas-2026-roatan-json/1',
               'payload': {'raw': row['raw'], 'parsed': {'declared-depth-token': '95'},
                           'citation': row['citation']}}]
    legacy_path = tmp_path / 'legacy.json'
    legacy_path.write_text(json.dumps(legacy))
    receipt = {'rows': [{'json_index_zero_based': 0, 'candidate_id': 'candidate',
                         'event_id': 'accept-0'}], 'version': {'job_id': 'v2',
                         'source_sha256': digest, 'parser_version': 'cmas-2026-roatan-json/2'}}
    receipt_path = tmp_path / 'receipt.json'
    receipt_path.write_text(json.dumps(receipt))
    reviews = [{'id': 'accept-0', 'job_id': 'v2', 'ordinal': 0, 'revision': 1,
                'action': 'accept', 'db_role': 'reviews_owner', 'recorded_at': '2026-09-28 12:56:34+02',
                'body_edn': '{:action :accept}', 'row_sha256': hashlib.sha256(b'review-row').hexdigest(),
                'body': {'id': 'accept-0', 'job-id': 'v2', 'ordinal': 0, 'action': 'accept',
                         'evidence': {'job-id': 'v2', 'candidate-id': 'candidate', 'source-sha256': digest,
                                      'artifact-sha256': v2_artifact,
                                      'parser-version': 'cmas-2026-roatan-json/2',
                                      'row-index-zero-based': 0}}}]
    reviews_path = tmp_path / 'reviews.json'
    reviews_path.write_text(json.dumps(reviews))
    out = tmp_path / 'out.json'
    command = [sys.executable, str(SCRIPT), 'build', '--corrected-packet', str(packet_path),
               '--stage', str(stage), '--legacy-observations', str(legacy_path),
               '--owner-receipt', str(receipt_path), '--review-rows', str(reviews_path),
               '--output', str(out)]
    subprocess.run(command, check=True)
    result = json.loads(out.read_text())
    assert result['counts'] == {'source_objects': 1, 'source_positions': 1,
                                'observation_versions': 2, 'historical_extraction_acceptances': 1,
                                'database_extraction_review_rows': 1,
                                'confirmed_distinct_attempts': None}
    assert len(result['observation_versions']) == 2
    assert result['positions'][0]['depths'] == {'declared': '95', 'raw': '65',
                                                'publisher_final': '34'}
    assert result['positions'][0]['penalty'] == '31'
    assert result['positions'][0]['notes'] == 'EARLY TURN, NO MARKER'
    assert result['positions'][0]['source_notes'] == {
        'result_note': 'other note', 'penalty_note': 'EARLY TURN, NO MARKER',
        'start_note': 'start note', 'record_token': 'record token'}
    assert result['positions'][0]['visible_status'] == 'PEN'
    assert result['positions'][0]['visible_notes'] == 'EARLY TURN, NO MARKER'
    assert result['positions'][0]['source_id'] == f'sha256:{digest}'
    assert result['positions'][0]['parser_version'] == 'cmas-2026-roatan-json/2'
    assert all(v['source_id'] == f'sha256:{digest}' for v in result['observation_versions'])
    assert len(result['database_extraction_reviews']) == 1
    assert result['database_extraction_reviews'][0]['row_sha256'] == reviews[0]['row_sha256']
    assert result['database_extraction_reviews'][0]['id'] == 'accept-0'
    assert result['observation_versions'][0]['review_status'] == 'unreviewed'
    assert result['observation_versions'][1]['review_status'] == 'extraction_accepted'
    assert result['confirmed_distinct_attempts'] is None
    reviews[0]['action'] = 'reject'
    reviews_path.write_text(json.dumps(reviews))
    bad = subprocess.run(command, capture_output=True, text=True)
    assert bad.returncode != 0
    assert 'database review mismatch' in bad.stderr


class RoatanCensusTest(unittest.TestCase):
    def test_versions_and_scoped_decisions(self):
        with tempfile.TemporaryDirectory() as directory:
            test_roatan_packet_keeps_versions_and_scoped_decisions(Path(directory))
