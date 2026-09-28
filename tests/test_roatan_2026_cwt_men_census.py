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
                   'ResNotePenality': 'EARLY TURN, NO MARKER'},
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
    out = tmp_path / 'out.json'
    subprocess.run([sys.executable, str(SCRIPT), 'build', '--corrected-packet', str(packet_path),
                    '--stage', str(stage), '--legacy-observations', str(legacy_path),
                    '--owner-receipt', str(receipt_path), '--output', str(out)], check=True)
    result = json.loads(out.read_text())
    assert result['counts'] == {'source_objects': 1, 'source_positions': 1,
                                'observation_versions': 2, 'historical_extraction_acceptances': 1,
                                'confirmed_distinct_attempts': None}
    assert len(result['observation_versions']) == 2
    assert result['positions'][0]['depths'] == {'declared': '95', 'raw': '65',
                                                'publisher_final': '34'}
    assert result['positions'][0]['penalty'] == '31'
    assert result['positions'][0]['notes'] == 'EARLY TURN, NO MARKER'
    assert result['observation_versions'][0]['review_status'] == 'unreviewed'
    assert result['observation_versions'][1]['review_status'] == 'extraction_accepted'
    assert result['confirmed_distinct_attempts'] is None


class RoatanCensusTest(unittest.TestCase):
    def test_versions_and_scoped_decisions(self):
        with tempfile.TemporaryDirectory() as directory:
            test_roatan_packet_keeps_versions_and_scoped_decisions(Path(directory))
