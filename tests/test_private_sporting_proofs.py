"""Transport binds exact rows, narrow paired configs and immutable runtime inventory."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from private_sporting_proofs import create_reader, RUNTIME_FILES
from sporting_authority import canonical


def pinned(tmp_path):
    root = tmp_path / 'runtime'; root.mkdir()
    files = {}
    for relative in RUNTIME_FILES:
        p = root / relative; p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b'isolated transport stand-in member')
        files[relative] = hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = root / 'manifest.json'; manifest.write_bytes(canonical({'files': files}))
    data = {'jdbc_url': 'jdbc:postgresql://127.0.0.1:5432/isolated_source?user=narrow&password=synthetic-secret',
            'database': 'isolated_source', 'canonical_jdbc_url': 'jdbc:postgresql://127.0.0.1:5432/isolated_canonical?user=narrow',
            'canonical_database': 'isolated_canonical', 'runtime_path': str(root),
            'runtime_manifest_sha256': hashlib.sha256(manifest.read_bytes()).hexdigest()}
    config = tmp_path / 'config.json'; config.write_bytes(canonical(data)); config.chmod(0o600)
    return config, data


def row():
    return {'reference': {'job-id': 'a' * 64, 'ordinal': 1, 'candidate-id': 'isolated',
                         'source-sha256': 'b' * 64, 'artifact-sha256': 'c' * 64,
                         'parser-version': 'isolated/1'}, 'coordinates': {'table': 1, 'row': 1}}


def test_paired_reader_binds_source_and_canonical_pins_without_credentials_in_process_args(tmp_path, monkeypatch):
    config, data = pinned(tmp_path)
    calls = []
    def exchange(self, command, request, deadline, generation):
        calls.append((command, copy.deepcopy(request)))
        rows = [{**r, 'upstream': {'review': {'value': 'verified', 'event_sha256': 'd' * 64}} if request['mode'] == 'source' else {},
                 'diagnostics': {'state': 'current'}} for r in request['rows']]
        return {'schema': 'private-sporting-proofs/v1', 'database': request['database'],
                'binding_sha256': ('d' if request['mode'] == 'source' else 'e') * 64, 'rows': rows}
    monkeypatch.setattr('private_sporting_proofs._Runtime.exchange', exchange)
    reader = create_reader({'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG': str(config)})
    try:
        proof = reader([row()])
        assert proof['rows'][0]['upstream']['review']['value'] == 'verified'
        assert proof['scope_bindings'] == {'source': 'd' * 64, 'relationships': 'e' * 64}
        assert proof['binding_sha256'] == reader([])['binding_sha256']
        assert all('synthetic-secret' not in ' '.join(command) for command, _ in calls)
        assert [request['mode'] for _, request in calls[:2]] == ['source', 'relationships']
        member = next(iter(RUNTIME_FILES)); (Path(data['runtime_path']) / member).write_text('tamper')
        with pytest.raises(ValueError, match='member changed'):
            reader([row()])
    finally:
        reader.close()


def test_reader_rejects_wrong_row_and_permission_exposure(tmp_path, monkeypatch):
    config, _ = pinned(tmp_path)
    def exchange(self, command, request, deadline, generation):
        bad = row(); bad['reference']['ordinal'] = 2
        return {'schema': 'private-sporting-proofs/v1', 'database': request['database'],
                'binding_sha256': 'd' * 64, 'rows': [{**bad, 'upstream': {}, 'diagnostics': {}}]}
    monkeypatch.setattr('private_sporting_proofs._Runtime.exchange', exchange)
    reader = create_reader({'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG': str(config)})
    try:
        with pytest.raises(ValueError, match='exact row changed'):
            reader([row()])
    finally:
        reader.close()
    config.chmod(0o644)
    with pytest.raises(ValueError, match='permissions'):
        create_reader({'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG': str(config)})


def review_config(tmp_path, data):
    path = tmp_path / 'review-config.json'
    path.write_bytes(canonical({key: data[key] for key in ('jdbc_url', 'database', 'runtime_path', 'runtime_manifest_sha256')}))
    path.chmod(0o600)
    return path


def test_source_review_uses_same_engine_and_exact_pinned_runtime(tmp_path, monkeypatch):
    config, data = pinned(tmp_path); reviewer = review_config(tmp_path, data)
    engines, requests = [], []
    def exchange(self, command, request, deadline, generation):
        engines.append(self); requests.append(request)
        if request.get('op') == 'source-review':
            return {'schema': 'private-sporting-proofs/v1', 'database': data['database'],
                    'binding_sha256': 'a' * 64, 'receipt': {'revision': 1}, 'replayed': bool(request.get('replay_only'))}
        return {'schema': 'private-sporting-proofs/v1', 'database': request['database'],
                'binding_sha256': 'b' * 64, 'rows': []}
    monkeypatch.setattr('private_sporting_proofs._Runtime.exchange', exchange)
    reader = create_reader({'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG': str(config)})
    try:
        reader([])
        result = reader.review({'id': 'isolated-review'}, reviewer)
        assert result['receipt']['revision'] == 1
        assert len({id(engine) for engine in engines}) == 1
        assert requests[-1] == {'op': 'source-review', 'jdbc_url': data['jdbc_url'], 'database': data['database'], 'request': {'id': 'isolated-review'}}
        reader.review({'id': 'isolated-review'}, reviewer, replay_only=True)
        assert requests[-1]['replay_only'] is True
        assert len({id(engine) for engine in engines}) == 1
        reviewer.chmod(0o644)
        with pytest.raises(ValueError, match='permissions'):
            reader.review({}, reviewer)
        reviewer.chmod(0o600)
        changed = json.loads(reviewer.read_bytes()); changed['runtime_manifest_sha256'] = 'e' * 64
        reviewer.write_bytes(canonical(changed))
        with pytest.raises(ValueError, match='runtime'):
            reader.review({}, reviewer)
    finally:
        reader.close()


@pytest.mark.parametrize('status,exception', [(409, 'ConflictError'), (400, 'ValueError'), (503, 'OSError')])
def test_source_review_maps_safe_domain_errors(tmp_path, monkeypatch, status, exception):
    from sporting_authority import ConflictError
    config, data = pinned(tmp_path); reviewer = review_config(tmp_path, data)
    monkeypatch.setattr('private_sporting_proofs._Runtime.exchange', lambda *args: {'schema': 'private-sporting-proofs/v1', 'error': 'Source accuracy review unavailable', 'status': status})
    reader = create_reader({'OWNER_EVIDENCE_SPORTING_PROOF_CONFIG': str(config)})
    try:
        with pytest.raises({'ConflictError': ConflictError, 'ValueError': ValueError, 'OSError': OSError}[exception]):
            reader.review({'id': 'isolated-review'}, reviewer)
    finally:
        reader.close()
