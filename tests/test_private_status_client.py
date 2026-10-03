import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from private_status_sync import sync_status


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
