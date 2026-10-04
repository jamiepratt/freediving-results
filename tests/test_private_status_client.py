import json
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from private_status_sync import sync_status, sync_application_status


def test_private_apply_sync_requires_active_binding_and_skips_identical_retry(tmp_path):
    run = tmp_path / 'run'
    run.mkdir()
    snap = 'a' * 64
    (run / 'state.json').write_text(json.dumps({
        'run_id': 'run-1', 'local': {'status': 'complete', 'snapshot_sha256': snap},
        'coverage': {'cutoff': '2026-10-03T00:00:00Z', 'gaps': []}}))
    application = {'snapshot_sha256': snap, 'canonical_revision': 189,
                   'canonical_readback_sha256': 'c' * 64,
                   'owner_store_revision': 12, 'pending_proposals': 207,
                   'unresolved_exclusions': 2, 'provider_calls_recorded': 0,
                   'publication_status': 'private'}
    active = {'snapshot_sha256': snap, 'bundle_manifest_sha256': 'b' * 64}

    class Response:
        status = 200
        def __init__(self, value): self.value = value
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def read(self, *_): return json.dumps(self.value).encode()

    class Opener:
        current = {'status': 'unavailable', 'remote': {'active': active}}
        posts = 0
        def open(self, request, **_):
            if request.get_method() == 'POST':
                self.posts += 1
                self.current = json.loads(request.data)
                self.current.pop('expected_revision')
            return Response(self.current)

    opener = Opener()
    result = sync_application_status(run, application, 'machine.access',
                                     'access-secret', 'token', opener=opener)
    assert result['schema'] == 'private-presentation-status/v3'
    assert result['application'] == application
    assert opener.posts == 1
    assert sync_application_status(run, application, 'machine.access',
                                   'access-secret', 'token', opener=opener) == result
    assert opener.posts == 1
    opener.current = {'status': 'stale', 'revision': 1, 'run_id': 'run-1',
                      'cutoff': '2026-10-03T00:00:00Z', 'remote': {'active': active}}
    import pytest
    with pytest.raises(RuntimeError, match='stale'):
        sync_application_status(run, application, 'machine.access',
                                'access-secret', 'token', opener=opener)
    opener.current = {'status': 'unavailable', 'remote': {'active': {
        'snapshot_sha256': 'd' * 64, 'bundle_manifest_sha256': 'b' * 64}}}
    with pytest.raises(ValueError, match='active snapshot'):
        sync_application_status(run, application, 'machine.access',
                                'access-secret', 'token', opener=opener)


def test_stale_checkpoint_requires_newer_run_before_compare_and_swap(tmp_path):
    run = tmp_path / 'run'
    run.mkdir()
    ledger = run / 'reconciliation' / 'flow.edn'
    ledger.parent.mkdir()
    ledger.write_text('{:events []}')
    ledger_sha = hashlib.sha256(ledger.read_bytes()).hexdigest()
    state = {'run_id': 'run-1', 'local': {'status': 'complete', 'snapshot_sha256': 'a' * 64},
             'coverage': {'cutoff': '2026-10-03T00:00:00Z', 'gaps': []},
             'remote': {'status': 'pending', 'pending': {'snapshot_sha256': 'a' * 64}, 'failed': None},
             'reconciliation': {'status': 'complete', 'decision_revision': 3,
                                'remote_store_revision': 8, 'spec_sha256': 'b' * 64,
                                'ledger_sha256': ledger_sha, 'metrics': {
                                    'schema': 'local-reconciliation-metrics/v1',
                                    'binding': {'run_id': 'run-1', 'snapshot_sha256': 'a' * 64,
                                                'decision_revision': 3, 'stage_checkpoint': 'reconciliation-complete',
                                                'spec_sha256': 'b' * 64, 'ledger_sha256': ledger_sha},
                                    'coverage': {'decision_denominator': 0, 'automatic_approved': 0,
                                                 'unknown': 0, 'error': 0, 'conflict': 0,
                                                 'pending_review': 0},
                                    'provider': {'calls_recorded': 0}, 'source_gaps': 0,
                                    'sampled_error': None}}}
    (run / 'state.json').write_text(json.dumps(state))
    class Response:
        status = 200
        def __init__(self, body): self.body = body
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def read(self, *_): return json.dumps(self.body).encode()
    class Opener:
        posted = None
        def open(self, request, **_):
            if request.get_method() == 'POST':
                self.posted = json.loads(request.data)
                return Response(self.posted)
            return Response({'status': 'stale', 'revision': 4, 'run_id': 'run-1',
                             'cutoff': '2026-10-03T00:00:00Z', 'remote': {'active': None}})
    opener = Opener()
    import pytest
    with pytest.raises(RuntimeError, match='stale'):
        sync_status(run, 'machine.access', 'access-secret', 'token', opener=opener)
    assert opener.posted is None
    state['run_id'] = 'run-2'
    state['coverage']['cutoff'] = '2026-10-04T00:00:00Z'
    state['reconciliation']['metrics']['binding']['run_id'] = 'run-2'
    (run / 'state.json').write_text(json.dumps(state))
    result = sync_status(run, 'machine.access', 'access-secret', 'token', opener=opener)
    assert result['run_id'] == 'run-2'
    assert opener.posted['expected_revision'] == 4
    assert opener.posted['revision'] == 5
    assert opener.posted['schema'] == 'private-presentation-status/v2'


def test_reconciliation_receipt_requires_exact_binding_and_sends_only_summary(tmp_path):
    run = tmp_path / 'run'
    run.mkdir()
    ledger = run / 'reconciliation' / 'flow.edn'
    ledger.parent.mkdir()
    ledger.write_text('{:events []}')
    ledger_sha = hashlib.sha256(ledger.read_bytes()).hexdigest()
    spec_sha = 'b' * 64
    snapshot = 'a' * 64
    state = {'run_id': 'run-1', 'local': {'status': 'complete', 'snapshot_sha256': snapshot},
             'coverage': {'cutoff': '2026-10-03T00:00:00Z', 'gaps': []},
             'remote': {'status': 'pending', 'pending': {'snapshot_sha256': snapshot}, 'failed': None},
             'reconciliation': {'status': 'complete', 'decision_revision': 3,
                                'spec_sha256': spec_sha, 'ledger_sha256': ledger_sha,
                                'remote_store_revision': 7, 'metrics': {
                                    'schema': 'local-reconciliation-metrics/v1',
                                    'binding': {'run_id': 'run-1', 'snapshot_sha256': snapshot,
                                                'decision_revision': 3,
                                                'stage_checkpoint': 'reconciliation-complete',
                                                'spec_sha256': spec_sha,
                                                'ledger_sha256': ledger_sha},
                                    'coverage': {'decision_denominator': 4, 'automatic_approved': 2,
                                                 'unknown': 1, 'error': 1, 'conflict': 0,
                                                 'pending_review': 2},
                                    'provider': {'calls_recorded': 2, 'actual_monetary_cost': None,
                                                 'secret_answer': 'PRIVATE'},
                                    'source_gaps': 1, 'sampled_error': {
                                        'sampling_frame': 'owner selected approvals', 'numerator': 1,
                                        'denominator': 2, 'selection': 'convenience',
                                        'selection_bias': 'biased selection', 'rate': .5},
                                    'accepted_athletes': None, 'distinct_attempts': None}}}
    (run / 'state.json').write_text(json.dumps(state))

    class Response:
        status = 200
        def __init__(self, data): self.data = data
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def read(self, *_): return json.dumps(self.data).encode()
    class Opener:
        posted = None
        def open(self, request, **_):
            if request.get_method() == 'POST':
                self.posted = json.loads(request.data)
                return Response(self.posted)
            return Response({'status': 'unavailable', 'remote': {'active': None}})
    opener = Opener()
    result = sync_status(run, 'machine.access', 'access-secret', 'token', opener=opener)
    assert result['reconciliation']['owner_store_revision'] == 7
    assert result['reconciliation']['metrics']['sampled_error']['denominator'] == 2
    assert 'PRIVATE' not in json.dumps(opener.posted)
    assert result['reconciliation']['metrics']['accepted_athletes'] is None
    state['reconciliation']['metrics']['binding']['decision_revision'] = 2
    (run / 'state.json').write_text(json.dumps(state))
    import pytest
    with pytest.raises(ValueError, match='metrics checkpoint binding'):
        sync_status(run, 'machine.access', 'access-secret', 'token', opener=opener)
    state['reconciliation']['metrics']['binding']['decision_revision'] = 3
    (run / 'state.json').write_text(json.dumps(state))
    ledger.write_text('{:events [{:changed true}]}')
    with pytest.raises(ValueError, match='metrics checkpoint binding'):
        sync_status(run, 'machine.access', 'access-secret', 'token', opener=opener)
    ledger.write_text('{:events []}')
    state['reconciliation']['metrics']['binding']['stage_checkpoint'] = 'running'
    (run / 'state.json').write_text(json.dumps(state))
    with pytest.raises(ValueError, match='metrics checkpoint binding'):
        sync_status(run, 'machine.access', 'access-secret', 'token', opener=opener)
    state['reconciliation']['metrics']['binding']['stage_checkpoint'] = 'reconciliation-complete'
    state['reconciliation']['remote_store_revision'] = None
    (run / 'state.json').write_text(json.dumps(state))
    with pytest.raises(ValueError, match='authoritative owner decision revision unavailable'):
        sync_status(run, 'machine.access', 'access-secret', 'token', opener=opener)


def test_checkpoint_sync_sends_only_safe_fields_and_skips_identical_retry(tmp_path):
    run = tmp_path / 'run'
    run.mkdir()
    (run / 'state.json').write_text(json.dumps({
        'plan_sha256': 'run-1', 'local': {'status': 'complete', 'snapshot_sha256': 'a' * 64,
                                          'bundle_manifest_sha256': 'b' * 64},
        'coverage': {'cutoff': '2026-10-03T00:00:00Z',
                     'gaps': [{'name': 'private/path', 'reason': 'secret athlete detail'}]},
        'remote': {'status': 'failed', 'pending': {'snapshot_sha256': 'a' * 64},
                   'failed': {'snapshot_sha256': 'a' * 64},
                   'active': {'snapshot_sha256': 'c' * 64, 'bundle_manifest_sha256': 'd' * 64},
                   'error': '/secret/path'},
    }))

    class Response:
        status = 200
        def __init__(self, data): self.data = data
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def read(self, *_): return json.dumps(self.data).encode()

    class Opener:
        calls = []
        current = {'status': 'unavailable', 'remote': {'active': {'snapshot_sha256': 'c' * 64,
                                                                 'bundle_manifest_sha256': 'd' * 64}}}
        def open(self, request, **_):
            self.calls.append(request)
            if request.get_method() == 'POST':
                self.current = json.loads(request.data)
                self.current.pop('expected_revision')
            return Response(self.current)

    opener = Opener()
    result = sync_status(run, 'machine.access', 'access-secret', 'private-status-token', opener=opener)
    assert result['revision'] == 1
    assert result['remote']['active']['snapshot_sha256'] == 'c' * 64
    assert len(opener.calls) == 2
    assert [request.get_header('User-agent') for request in opener.calls] == [
        'freediving-status-sync/1.0', 'freediving-status-sync/1.0']
    assert [request.get_header('Cf-access-client-id') for request in opener.calls] == [
        'machine.access', 'machine.access']
    assert [request.get_header('Cf-access-client-secret') for request in opener.calls] == [
        'access-secret', 'access-secret']
    assert all(request.get_header('Cf-access-jwt-assertion') is None for request in opener.calls)
    payload = opener.calls[-1].data.decode()
    assert '/secret/path' not in payload
    assert 'secret athlete' not in payload
    assert 'private/path' not in payload
    assert result['local']['gap_count'] == 1
    sync_status(run, 'machine.access', 'access-secret', 'private-status-token', opener=opener)
    assert len(opener.calls) == 3
