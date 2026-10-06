import hashlib
import json
import sys
import time
import importlib.util
from pathlib import Path
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))


@pytest.fixture
def pinned_runtime(tmp_path):
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location('canonical_package', root / 'deploy/canonical_status_runtime.py')
    package = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(package)
    import private_canonical_status
    runtime = tmp_path / 'runtime'
    return runtime, package.build_runtime(root, runtime, 'isolated-synthetic-only', private_canonical_status)


def test_warm_current_canonical_reads_fit_remaining_request_budget(tmp_path, pinned_runtime):
    from private_canonical_status import create_reader
    config = tmp_path / 'config.json'
    runtime, digest = pinned_runtime
    config.write_text(json.dumps({'jdbc_url': 'jdbc:postgresql://127.0.0.1:5432/private?user=reader',
                                 'database': 'private', 'snapshot_sha256': 'a' * 64,
                                 'runtime_path': str(runtime), 'runtime_manifest_sha256': digest,
                                 'exports': {}}))
    config.chmod(0o600)
    read = create_reader({'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG': str(config)})
    try:
        initial = read()
        assert initial['schema'] == 'private-canonical-status-readback/v1'
        for _ in range(3):
            assert read(deadline=time.monotonic() + 0.25) == initial
    finally:
        if hasattr(read, 'close'):
            read.close()


def test_reader_rechecks_frozen_export_before_calling_readonly_verifier(tmp_path, pinned_runtime):
    from private_canonical_status import create_reader

    export = tmp_path / 'export.json'
    export.write_text(json.dumps({'snapshot_sha256': 'a' * 64, 'proposals': []}))
    config = tmp_path / 'reader.json'
    runtime, manifest_digest = pinned_runtime
    config.write_text(json.dumps({'jdbc_url': 'jdbc:postgresql://127.0.0.1:5432/private?user=reader',
                                 'database': 'private', 'snapshot_sha256': 'a' * 64,
                                 'runtime_path': str(runtime),
                                 'runtime_manifest_sha256': manifest_digest,
                                 'exports': {'same_attempt': {'path': str(export),
                                     'sha256': hashlib.sha256(export.read_bytes()).hexdigest()}}}))
    config.chmod(0o600)
    result = {'schema': 'private-canonical-status-readback/v1',
              'snapshot_sha256': 'a' * 64, 'scopes': {}}
    import subprocess
    launch = subprocess.Popen
    commands = []
    def record(command, **kwargs):
        commands.append(command)
        return launch(command, **kwargs)
    with patch('private_attempt_inspector.subprocess.Popen', side_effect=record):
        reader = create_reader({'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG': str(config)}, runtime=runtime)
        try:
            assert reader() == result
            assert config.read_text() not in ' '.join(commands[0])
            assert 'jdbc:postgresql' not in ' '.join(commands[0])
            export.write_text('{}')
            with pytest.raises(ValueError, match='export changed'):
                reader()
        finally:
            reader.close()


def test_reader_configuration_is_optional_and_refuses_exposed_credentials(tmp_path):
    from private_canonical_status import create_reader
    assert create_reader({}) is None
    config = tmp_path / 'reader.json'
    config.write_text('{}')
    config.chmod(0o644)
    with pytest.raises(ValueError, match='private canonical configuration'):
        create_reader({'OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG': str(config)})
