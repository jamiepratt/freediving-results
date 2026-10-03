import http.client
import json
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from owner_evidence_origin import make_server
from private_presentation_status import PrivatePresentationStatus, StatusConflict
from test_unified_evidence_query import snapshot


def test_reconciliation_status_is_hidden_after_owner_correction_or_snapshot_change(tmp_path):
    store = PrivatePresentationStatus(tmp_path / 'status.json')
    snap = 'a' * 64
    active = {'snapshot_sha256': snap, 'bundle_manifest_sha256': 'b' * 64}
    summary = {'snapshot_sha256': snap, 'decision_revision': 3,
               'owner_store_revision': 7,
               'metrics': {'decision_denominator': 4, 'automatic_approved': 2,
                           'unknown': 1, 'error': 1, 'conflict': 0,
                           'pending_review': 2, 'source_gaps': 1,
                           'provider_calls_recorded': 2, 'sampled_error': None,
                           'accepted_athletes': None, 'distinct_attempts': None,
                           'actual_monetary_cost': None}}
    payload = {'schema': 'private-presentation-status/v2', 'run_id': 'run-1',
               'revision': 1, 'expected_revision': 0,
               'local': {'snapshot_sha256': snap, 'cutoff': '2026-10-03T00:00:00Z',
                         'gap_count': 0},
               'remote': {'status': 'active', 'pending': None, 'failed': None,
                          'active': active}, 'reconciliation': summary}
    with __import__('pytest').raises(StatusConflict):
        store.update(payload, active, owner_revision=None, owner_snapshot=None)
    store.update(payload, active, owner_revision=7, owner_snapshot=snap)
    assert store.read(active, owner_revision=7, owner_snapshot=snap)['reconciliation'] == summary
    assert store.read(active, owner_revision=8, owner_snapshot=snap)['status'] == 'stale'
    assert 'revision' not in store.read(active, owner_revision=8, owner_snapshot=snap)
    machine = store.read(active, owner_revision=8, owner_snapshot=snap,
                         include_stale_checkpoint=True)
    assert (machine['revision'], machine['run_id'], machine['cutoff']) == (1, 'run-1', '2026-10-03T00:00:00Z')
    assert 'local' not in machine and 'reconciliation' not in machine
    stale_retry = {**payload, 'revision': 2, 'expected_revision': 1,
                   'reconciliation': {**summary, 'owner_store_revision': 8}}
    with __import__('pytest').raises(StatusConflict):
        store.update(stale_retry, active, owner_revision=8, owner_snapshot=snap)
    newer = {**stale_retry, 'run_id': 'run-2',
             'local': {**payload['local'], 'cutoff': '2026-10-04T00:00:00Z'}}
    legacy_replacement = {key: value for key, value in newer.items() if key != 'reconciliation'}
    legacy_replacement['schema'] = 'private-presentation-status/v1'
    with __import__('pytest').raises(StatusConflict):
        store.update(legacy_replacement, active, owner_revision=8, owner_snapshot=snap)
    assert store.update(newer, active, owner_revision=8, owner_snapshot=snap)['revision'] == 2
    assert store.read({'snapshot_sha256': 'c' * 64, 'bundle_manifest_sha256': 'd' * 64},
                      owner_revision=8, owner_snapshot=snap)['status'] == 'stale'


def test_owner_api_rejects_reconciliation_without_authoritative_decision_store(tmp_path):
    snap = snapshot(tmp_path)
    served = json.loads((snap / 'manifest.json').read_text())['snapshot_sha256']
    env = {'OWNER_EVIDENCE_GATEWAY_SECRET': 'gateway-secret-long-enough',
           'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
           'OWNER_EVIDENCE_SNAPSHOT_SHA256': served,
           'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
           'OWNER_EVIDENCE_STATUS_FILE': str(tmp_path / 'status.json'),
           'OWNER_EVIDENCE_STATUS_TOKEN': 'status-token-long-enough-private',
           'OWNER_EVIDENCE_STATUS_CLIENT_ID': 'status-client.access'}
    server = make_server(snap, env)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        payload = {'schema': 'private-presentation-status/v2', 'run_id': 'run-1',
                   'revision': 1, 'expected_revision': 0,
                   'local': {'snapshot_sha256': served, 'cutoff': '2026-10-03T00:00:00Z', 'gap_count': 0},
                   'remote': {'status': 'pending', 'pending': served, 'failed': None, 'active': None},
                   'reconciliation': {'snapshot_sha256': served, 'decision_revision': 1,
                                      'owner_store_revision': 1, 'metrics': {
                                          'decision_denominator': 0, 'automatic_approved': 0,
                                          'unknown': 0, 'error': 0, 'conflict': 0,
                                          'pending_review': 0, 'source_gaps': 0,
                                          'provider_calls_recorded': 0, 'sampled_error': None,
                                          'accepted_athletes': None, 'distinct_attempts': None,
                                          'actual_monetary_cost': None}}}
        conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        conn.request('POST', '/owner-evidence/api/presentation-status', json.dumps(payload),
                     {'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
                      'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                      'X-Freediving-Owner-Machine': env['OWNER_EVIDENCE_STATUS_CLIENT_ID'],
                      'X-Freediving-Status-Token': env['OWNER_EVIDENCE_STATUS_TOKEN'],
                      'Content-Type': 'application/json'})
        response = conn.getresponse()
        assert response.status == 409
        response.read(); conn.close()
    finally:
        server.shutdown(); thread.join(timeout=2); server.server_close()
    env['OWNER_EVIDENCE_DECISION_DB'] = str(tmp_path / 'decisions.sqlite')
    server = make_server(snap, env)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        payload['reconciliation']['owner_store_revision'] = server.decisions.revision
        headers = {'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
                   'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                   'X-Freediving-Owner-Machine': env['OWNER_EVIDENCE_STATUS_CLIENT_ID'],
                   'X-Freediving-Status-Token': env['OWNER_EVIDENCE_STATUS_TOKEN'],
                   'Content-Type': 'application/json'}
        conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        conn.request('POST', '/owner-evidence/api/presentation-status', json.dumps(payload), headers)
        response = conn.getresponse()
        assert response.status == 200
        assert json.loads(response.read())['reconciliation']['decision_revision'] == 1
        conn.close()
        conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        conn.request('GET', '/owner-evidence/api/presentation-status', headers={
            'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
            'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
            'X-Freediving-Owner-Email': env['OWNER_EVIDENCE_EMAILS']})
        response = conn.getresponse()
        assert response.status == 200
        assert json.loads(response.read())['reconciliation']['metrics']['decision_denominator'] == 0
        conn.close()
        server.decisions.bind_verified_snapshot(
            snap, expected_revision=server.decisions.revision,
            idempotency_key='synthetic-new-owner-binding')
        conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        conn.request('GET', '/owner-evidence/api/presentation-status', headers={
            'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
            'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
            'X-Freediving-Owner-Email': env['OWNER_EVIDENCE_EMAILS']})
        owner_stale = json.loads(conn.getresponse().read())
        assert owner_stale['status'] == 'stale'
        assert 'revision' not in owner_stale and 'local' not in owner_stale
        conn.close()
        conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        conn.request('GET', '/owner-evidence/api/presentation-status', headers=headers)
        machine_stale = json.loads(conn.getresponse().read())
        assert (machine_stale['revision'], machine_stale['run_id']) == (1, 'run-1')
        assert 'reconciliation' not in machine_stale
        conn.close()
        payload.update({'revision': 2, 'expected_revision': machine_stale['revision']})
        payload['reconciliation']['owner_store_revision'] = server.decisions.revision
        conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        conn.request('POST', '/owner-evidence/api/presentation-status', json.dumps(payload), headers)
        assert conn.getresponse().status == 409
        conn.close()
        payload['run_id'] = 'run-2'
        payload['local']['cutoff'] = '2026-10-04T00:00:00Z'
        conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        conn.request('POST', '/owner-evidence/api/presentation-status', json.dumps(payload), headers)
        response = conn.getresponse()
        assert response.status == 200
        assert json.loads(response.read())['revision'] == 2
        conn.close()
    finally:
        server.shutdown(); thread.join(timeout=2); server.server_close()


def test_private_status_is_persistent_ordered_and_owner_read_only():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        snap = snapshot(root)
        served = json.loads((snap / 'manifest.json').read_text())['snapshot_sha256']
        env = {'OWNER_EVIDENCE_GATEWAY_SECRET': 'gateway-secret-long-enough',
               'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
               'OWNER_EVIDENCE_SNAPSHOT_SHA256': served,
               'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
               'OWNER_EVIDENCE_STATUS_FILE': str(root / 'status.json'),
               'OWNER_EVIDENCE_STATUS_TOKEN': 'status-token-long-enough-private',
               'OWNER_EVIDENCE_STATUS_CLIENT_ID': 'status-client.access'}

        def request(server, method, body=None, machine=False, token=True):
            conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
            headers = {'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
                       'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET']}
            if machine:
                headers['X-Freediving-Owner-Machine'] = env['OWNER_EVIDENCE_STATUS_CLIENT_ID']
                if token:
                    headers['X-Freediving-Status-Token'] = env['OWNER_EVIDENCE_STATUS_TOKEN']
            else:
                headers['X-Freediving-Owner-Email'] = env['OWNER_EVIDENCE_EMAILS']
            if body is not None:
                headers['Content-Type'] = 'application/json'
                body = json.dumps(body).encode()
                headers['Content-Length'] = str(len(body))
            conn.request(method, '/owner-evidence/api/presentation-status', body=body, headers=headers)
            response = conn.getresponse()
            result = response.status, response.read()
            conn.close()
            return result

        def start():
            server = make_server(snap, env)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            return server, thread

        def stop(server, thread):
            server.shutdown(); thread.join(timeout=2); server.server_close()

        server, thread = start()
        try:
            assert json.loads(request(server, 'GET')[1])['status'] == 'unavailable'
            payload = {'schema': 'private-presentation-status/v1', 'run_id': 'run-1',
                       'revision': 1, 'expected_revision': 0,
                       'local': {'snapshot_sha256': 'a' * 64, 'cutoff': '2026-10-03T00:00:00Z', 'gap_count': 2},
                       'remote': {'status': 'pending', 'pending': 'a' * 64,
                                  'failed': None, 'active': None}}
            assert request(server, 'POST', payload, machine=True)[0] == 200
            assert json.loads(request(server, 'GET', machine=True)[1])['revision'] == 1
            assert request(server, 'POST', payload, machine=True)[0] == 200
            assert request(server, 'POST', payload)[0] == 403
            assert request(server, 'POST', payload, machine=True, token=False)[0] == 403
            assert request(server, 'POST', {**payload, 'revision': 2, 'expected_revision': 0}, machine=True)[0] == 409
            assert request(server, 'POST', {**payload, 'revision': 2, 'expected_revision': 1,
                                            'local': {**payload['local'], 'snapshot_sha256': 'b' * 64}}, machine=True)[0] == 409
            assert request(server, 'POST', {**payload, 'revision': 2, 'expected_revision': 1,
                                            'remote': {**payload['remote'], 'status': 'active'}}, machine=True)[0] == 409
            assert request(server, 'POST', {**payload, 'secret_path': '/private/file'}, machine=True)[0] == 400
            visible = json.loads(request(server, 'GET')[1])
            assert visible['revision'] == 1
            assert visible['local']['gap_count'] == 2
            assert visible['remote']['active']['snapshot_sha256'] == served
        finally:
            stop(server, thread)
        server, thread = start()
        try:
            assert json.loads(request(server, 'GET')[1])['revision'] == 1
            failed = {**payload, 'revision': 2, 'expected_revision': 1,
                      'remote': {'status': 'failed', 'pending': 'a' * 64,
                                 'failed': 'a' * 64, 'active': None}}
            assert request(server, 'POST', failed, machine=True)[0] == 200
            stale = {**failed, 'run_id': 'older-run', 'revision': 3, 'expected_revision': 2,
                     'local': {**failed['local'], 'cutoff': '2026-10-02T00:00:00Z'}}
            assert request(server, 'POST', stale, machine=True)[0] == 409
            result = json.loads(request(server, 'GET')[1])
            assert result['remote']['status'] == 'failed'
            assert result['remote']['active']['snapshot_sha256'] == served
        finally:
            stop(server, thread)
