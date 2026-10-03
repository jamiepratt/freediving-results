import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from private_status_sync import sync_status


def test_reconciliation_receipt_requires_exact_binding_and_sends_only_summary(tmp_path):
    run = tmp_path / 'run'
    run.mkdir()
    snapshot = 'a' * 64
    state = {'run_id': 'run-1', 'local': {'status': 'complete', 'snapshot_sha256': snapshot},
             'coverage': {'cutoff': '2026-10-03T00:00:00Z', 'gaps': []},
             'remote': {'status': 'pending', 'pending': {'snapshot_sha256': snapshot}, 'failed': None},
             'reconciliation': {'status': 'complete', 'decision_revision': 3,
                                'remote_store_revision': 7, 'metrics': {
                                    'schema': 'local-reconciliation-metrics/v1',
                                    'binding': {'run_id': 'run-1', 'snapshot_sha256': snapshot,
                                                'decision_revision': 3},
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
    result = sync_status(run, 'jwt', 'token', opener=opener)
    assert result['reconciliation']['owner_store_revision'] == 7
    assert result['reconciliation']['metrics']['sampled_error']['denominator'] == 2
    assert 'PRIVATE' not in json.dumps(opener.posted)
    assert result['reconciliation']['metrics']['accepted_athletes'] is None
    state['reconciliation']['metrics']['binding']['decision_revision'] = 2
    (run / 'state.json').write_text(json.dumps(state))
    import pytest
    with pytest.raises(ValueError, match='metrics checkpoint binding'):
        sync_status(run, 'jwt', 'token', opener=opener)


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
    result = sync_status(run, 'machine-jwt', 'private-status-token', opener=opener)
    assert result['revision'] == 1
    assert result['remote']['active']['snapshot_sha256'] == 'c' * 64
    assert len(opener.calls) == 2
    payload = opener.calls[-1].data.decode()
    assert '/secret/path' not in payload
    assert 'secret athlete' not in payload
    assert 'private/path' not in payload
    assert result['local']['gap_count'] == 1
    sync_status(run, 'machine-jwt', 'private-status-token', opener=opener)
    assert len(opener.calls) == 3
