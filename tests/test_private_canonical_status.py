import hashlib
import json
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))


def test_reader_rechecks_frozen_export_before_calling_readonly_verifier(tmp_path):
    from private_canonical_status import create_reader, RUNTIME_FILES

    export = tmp_path / 'export.json'
    export.write_text(json.dumps({'snapshot_sha256': 'a' * 64, 'proposals': []}))
    config = tmp_path / 'reader.json'
    runtime = tmp_path / 'runtime'
    runtime.mkdir()
    files = {}
    for name in RUNTIME_FILES:
        target = runtime / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('runtime')
        files[name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (runtime / 'manifest.json').write_text(json.dumps({'files': files}))
    config.write_text(json.dumps({'jdbc_url': 'jdbc:postgresql://127.0.0.1:5432/private?user=reader',
                                 'database': 'private', 'snapshot_sha256': 'a' * 64,
                                 'runtime_path': str(runtime),
                                 'runtime_manifest_sha256': hashlib.sha256((runtime / 'manifest.json').read_bytes()).hexdigest(),
                                 'exports': {'same_attempt': {'path': str(export),
                                     'sha256': hashlib.sha256(export.read_bytes()).hexdigest()}}}))
    config.chmod(0o600)
    result = {'schema': 'private-canonical-status-readback/v1',
              'snapshot_sha256': 'a' * 64, 'scopes': {}}
    class Response:
        returncode = 0
        stdout = json.dumps(result)
    with patch('private_canonical_status.subprocess.run', return_value=Response()) as run:
        reader = create_reader({'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG': str(config)}, runtime=runtime)
        assert reader() == result
        assert config.read_text() not in ' '.join(run.call_args.args[0])
        assert 'jdbc:postgresql' not in ' '.join(run.call_args.args[0])
        export.write_text('{}')
        with pytest.raises(ValueError, match='export changed'):
            reader()


def test_reader_configuration_is_optional_and_refuses_exposed_credentials(tmp_path):
    from private_canonical_status import create_reader
    assert create_reader({}) is None
    config = tmp_path / 'reader.json'
    config.write_text('{}')
    config.chmod(0o644)
    with pytest.raises(ValueError, match='private canonical configuration'):
        create_reader({'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG': str(config)})
