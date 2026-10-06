import copy
import hashlib
import json
import sys
import http.client
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from private_presentation_status import PrivatePresentationStatus, StatusConflict


def sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     ensure_ascii=False).encode()).hexdigest()


def fixture(tmp_path):
    snap = 'a' * 64
    active = {'snapshot_sha256': snap, 'bundle_manifest_sha256': 'b' * 64}
    historical = {'schema': 'private-presentation-status/v3', 'revision': 3,
                  'run_id': 'identity-history',
                  'local': {'snapshot_sha256': snap, 'cutoff': '2026-10-01T00:00:00Z', 'gap_count': 62},
                  'remote': {'status': 'active', 'pending': None, 'failed': None, 'active': active},
                  'application': {'snapshot_sha256': snap, 'canonical_revision': 211,
                                  'canonical_readback_sha256': 'c' * 64,
                                  'owner_store_revision': 209, 'pending_proposals': 207,
                                  'unresolved_exclusions': 2, 'provider_calls_recorded': 0,
                                  'publication_status': 'private'}}
    path = tmp_path / 'status.json'
    path.write_text(json.dumps(historical))
    store = PrivatePresentationStatus(path)
    authority = {'owner_store_revision': 227, 'snapshot_sha256': snap,
                 'signed_owner_feed_sha256': 'd' * 64, 'signed_owner_feed_hmac': 'e' * 64,
                 'owner_metrics': {'pending': 207, 'human_approved': 5, 'projection_pending': 0},
                 'canonical': {'identity': {'status': 'verified', 'revision': 211,
                               'owner_event_revision': 0, 'export_sha256': 'f' * 64,
                               'accepted_count': None, 'evidence_sha256': '1' * 64},
                               'same_attempt': {'status': 'verified', 'revision': 7,
                               'owner_event_revision': 227, 'export_sha256': '2' * 64,
                               'accepted_count': 5, 'evidence_sha256': '3' * 64}}}
    command = {'schema': 'private-presentation-refresh/v1', 'expected_revision': 3,
               'expected_authority_sha256': sha(authority), 'run_id': 'normal-attempt-run',
               'local': {'snapshot_sha256': snap, 'cutoff': '2026-10-02T00:00:00Z', 'gap_count': 9},
               'remote': {'status': 'active', 'pending': None, 'failed': None},
               'snapshot_sha256': snap, 'bundle_manifest_sha256': active['bundle_manifest_sha256'],
               'export_sha256': '2' * 64}
    return store, active, historical, authority, command


def test_refresh_preserves_identity_history_and_independent_current_scopes(tmp_path):
    store, active, historical, authority, command = fixture(tmp_path)
    assert store.read(active, owner_revision=227, owner_snapshot=active['snapshot_sha256'])['status'] == 'stale'
    result = store.refresh(command, active, authority)
    assert result['schema'] == 'private-presentation-status/v4'
    assert result['revision'] == 4
    assert result['historical'] == {'receipt': historical, 'sha256': sha(historical)}
    assert result['authority'] == authority
    assert result['authority']['canonical']['identity']['revision'] == 211
    assert result['authority']['canonical']['same_attempt']['revision'] == 7
    assert result['authority']['owner_store_revision'] == 227
    assert result['local']['gap_count'] == 9
    assert store.read(active, authority=authority) == result


def test_refresh_rejects_status_owner_export_races_and_legacy_overwrite(tmp_path):
    store, active, _, authority, command = fixture(tmp_path)
    original = store.path.read_bytes()
    for change in ({'expected_revision': 2}, {'export_sha256': '9' * 64},
                   {'expected_authority_sha256': '9' * 64},
                   {'bundle_manifest_sha256': '9' * 64}):
        with pytest.raises(StatusConflict):
            store.refresh({**command, **change}, active, authority)
        assert store.path.read_bytes() == original
    result = store.refresh(command, active, authority)
    newer = copy.deepcopy(authority)
    newer['canonical']['same_attempt']['revision'] = 8
    assert store.read(active, authority=newer)['status'] == 'stale'
    corrected = copy.deepcopy(authority)
    corrected['owner_store_revision'] = 228
    assert store.read(active, authority=corrected)['status'] == 'stale'
    with pytest.raises(StatusConflict):
        store.refresh({**command, 'expected_revision': 4}, active, corrected)
    legacy = {**result['historical']['receipt'], 'expected_revision': 4, 'revision': 5}
    with pytest.raises(StatusConflict, match='verified refresh'):
        store.update(legacy, active, owner_revision=209, owner_snapshot=active['snapshot_sha256'])


def test_refresh_is_idempotent_and_rollback_keeps_current_authority_and_history(tmp_path):
    store, active, historical, authority, command = fixture(tmp_path)
    newer_bundle = {**active, 'bundle_manifest_sha256': '4' * 64}
    command['bundle_manifest_sha256'] = newer_bundle['bundle_manifest_sha256']
    result = store.refresh(command, newer_bundle, authority)
    replay = {**command, 'expected_revision': 4}
    saved = store.path.read_bytes()
    assert store.refresh(replay, newer_bundle, authority) == result
    assert store.path.read_bytes() == saved
    assert store.read(active, authority=authority)['status'] == 'stale'
    rollback = store.refresh({**replay, 'bundle_manifest_sha256': active['bundle_manifest_sha256']}, active, authority)
    assert rollback['revision'] == 5
    assert rollback['authority'] == authority
    assert rollback['historical']['receipt'] == historical
    reloaded = PrivatePresentationStatus(store.path)
    assert reloaded.read(active, authority=authority) == rollback


def test_unknown_identity_does_not_borrow_attempt_revision_or_counts(tmp_path):
    store, active, _, authority, command = fixture(tmp_path)
    authority['canonical']['identity'] = dict.fromkeys(('revision', 'owner_event_revision',
        'export_sha256', 'accepted_count', 'evidence_sha256')) | {'status': 'unknown'}
    command['expected_authority_sha256'] = sha(authority)
    result = store.refresh(command, active, authority)
    assert result['authority']['canonical']['identity']['status'] == 'unknown'
    assert result['authority']['canonical']['identity']['accepted_count'] is None
    assert result['historical']['receipt']['application']['canonical_revision'] == 211


def test_refresh_refuses_regressed_scope_and_newer_durable_status(tmp_path):
    store, active, _, authority, command = fixture(tmp_path)
    result = store.refresh(command, active, authority)
    lower = copy.deepcopy(authority)
    lower['canonical']['same_attempt']['revision'] = 6
    replay = {**command, 'expected_revision': 4, 'expected_authority_sha256': sha(lower)}
    with pytest.raises(StatusConflict, match='canonical revision'):
        store.refresh(replay, active, lower)
    newer = {**result, 'revision': 5}
    store.path.write_text(json.dumps(newer))
    saved = store.path.read_bytes()
    with pytest.raises(StatusConflict, match='durable status'):
        store.refresh({**command, 'expected_revision': 4}, active, authority)
    assert store.path.read_bytes() == saved


def test_refresh_cannot_regress_historical_authority(tmp_path):
    store, active, _, authority, command = fixture(tmp_path)
    authority['owner_store_revision'] = 208
    with pytest.raises(StatusConflict, match='owner revision'):
        store.refresh({**command, 'expected_authority_sha256': sha(authority)}, active, authority)


def test_normal_status_client_refreshes_origin_with_truthful_pending_and_failed_processing(tmp_path):
    from tests.test_local_evidence_run import microplus_normal_fixture, run
    from owner_evidence_origin import make_server
    from private_status_sync import sync_status

    plan, _ = microplus_normal_fixture(tmp_path)
    target = tmp_path / 'run'
    prepared = run(plan, target)
    assert prepared.returncode == 0, prepared.stderr
    state_path = target / 'state.json'
    state = json.loads(state_path.read_text())
    snap = state['local']['snapshot_sha256']
    active = {'snapshot_sha256': snap, 'bundle_manifest_sha256': state['local']['bundle_manifest_sha256']}
    _, _, historical, _, _ = fixture(tmp_path)
    historical['local']['snapshot_sha256'] = snap
    historical['local']['cutoff'] = '2026-10-01T00:00:00Z'
    historical['remote']['active'] = active
    historical['application']['snapshot_sha256'] = snap
    historical['application']['owner_store_revision'] = 0
    (tmp_path / 'status.json').write_text(json.dumps(historical))
    scope = {'revision': 7, 'owner_event_revision': 0,
             'export_sha256': state['reconciliation']['export_sha256'],
             'accepted_count': 0, 'evidence_sha256': '3' * 64}
    env = {'OWNER_EVIDENCE_GATEWAY_SECRET': 'gateway-secret-long-enough',
           'OWNER_EVIDENCE_EMAILS': 'owner@example.com',
           'OWNER_EVIDENCE_SNAPSHOT_SHA256': snap,
           'OWNER_EVIDENCE_ORIGIN_HOST': 'owner-private.alphacompose.com',
           'OWNER_EVIDENCE_STATUS_FILE': str(tmp_path / 'status.json'),
           'OWNER_EVIDENCE_STATUS_TOKEN': 'status-token-long-enough-private',
           'OWNER_EVIDENCE_STATUS_CLIENT_ID': 'status-client.access',
           'OWNER_EVIDENCE_IMPORT_TOKEN': 'import-token-long-enough-private',
           'OWNER_EVIDENCE_DECISION_DB': str(target / 'reconciliation' / 'owner.sqlite'),
           'OWNER_EVIDENCE_SOURCE_BUNDLE_DIR': str(target / 'bundle'),
           'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256': active['bundle_manifest_sha256']}
    server = make_server(target / 'snapshot', env, canonical_reader=lambda: {
        'schema': 'private-canonical-status-readback/v1', 'snapshot_sha256': snap,
        'scopes': {'same_attempt': copy.deepcopy(scope)}})
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    class Response:
        def __init__(self, code, body):
            self.status, self.body = code, body
        def __enter__(self):
            return self
        def __exit__(self, *_):
            pass
        def read(self, _):
            return self.body

    class Transport:
        def open(self, request, timeout):
            headers = {'Host': env['OWNER_EVIDENCE_ORIGIN_HOST'],
                       'X-Freediving-Owner-Gateway': env['OWNER_EVIDENCE_GATEWAY_SECRET'],
                       'X-Freediving-Owner-Machine': env['OWNER_EVIDENCE_STATUS_CLIENT_ID'],
                       'X-Freediving-Status-Token': env['OWNER_EVIDENCE_STATUS_TOKEN']}
            if request.data:
                headers['Content-Type'] = 'application/json'
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=10)
            connection.request(request.method, '/owner-evidence/api/presentation-status', request.data, headers)
            response = connection.getresponse()
            body, code = response.read(), response.status
            connection.close()
            assert len(body) <= 4096
            assert code == 200, (code, body)
            return Response(code, body)

    transport = Transport()
    try:
        pending = sync_status(target, 'id', 'secret', 'token', opener=transport, authoritative_refresh=True)
        assert pending['schema'] == 'private-presentation-status/v4'
        assert pending['remote']['status'] == 'pending'
        assert pending['remote']['active'] == active
        assert pending['historical']['receipt']['application']['owner_store_revision'] == 0
        assert pending['authority']['owner_metrics'] == {'pending': 1}
        assert pending['authority']['canonical']['identity']['status'] == 'unknown'
        assert pending['authority']['canonical']['same_attempt']['revision'] == 7
        state['remote'].update(status='failed', pending=active, failed=active)
        state_path.write_text(json.dumps(state))
        failed = sync_status(target, 'id', 'secret', 'token', opener=transport, authoritative_refresh=True)
        assert failed['remote'] == {'status': 'failed', 'active': active, 'pending': snap, 'failed': snap}
        assert sync_status(target, 'id', 'secret', 'token', opener=transport,
                           authoritative_refresh=True)['revision'] == failed['revision']
        scope['revision'] = 8
        assert server.presentation_status.read(active, authority=server.status_authority())['status'] == 'stale'
    finally:
        server.shutdown(); thread.join(timeout=3); server.server_close()
