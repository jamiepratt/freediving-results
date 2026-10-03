import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

from test_local_evidence_run import fixture, run

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'private_evidence_transfer.py'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def transfer(source, destination):
    return subprocess.run([sys.executable, str(SCRIPT), 'stage', '--run-dir', str(source),
                           '--destination', str(destination)], capture_output=True, text=True)


def prepared(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    source = tmp_path / 'run'
    result = run(plan, source)
    assert result.returncode == 0, result.stderr
    destination = tmp_path / 'remote'
    destination.mkdir(mode=0o700)
    return source, destination


def test_verified_run_stages_bound_snapshot_and_bundle_without_activation(tmp_path):
    source, destination = prepared(tmp_path)
    active = destination / 'active'
    active.write_text('prior presentation')
    result = transfer(source, destination)
    assert result.returncode == 0, result.stderr
    receipt = json.loads(result.stdout)
    stage = destination / receipt['staging_path']
    state = json.loads((source / 'state.json').read_text())
    assert receipt['schema'] == 'private-evidence-transfer/v1'
    assert receipt['snapshot_sha256'] == state['local']['snapshot_sha256']
    assert receipt['bundle_manifest_sha256'] == state['local']['bundle_manifest_sha256']
    assert (stage / 'snapshot' / 'snapshot.sqlite').read_bytes() == (source / 'snapshot' / 'snapshot.sqlite').read_bytes()
    assert (stage / 'snapshot' / 'manifest.json').read_bytes() == (source / 'snapshot' / 'manifest.json').read_bytes()
    assert (stage / 'source-bundle' / 'manifest.json').read_bytes() == (source / 'bundle' / 'manifest.json').read_bytes()
    assert {p.name for p in (stage / 'source-bundle' / 'objects').iterdir()} == {p.name for p in (source / 'bundle' / 'objects').iterdir()}
    assert active.read_text() == 'prior presentation'


def test_resume_skips_verified_objects_and_completes_partial_copy(tmp_path):
    source, destination = prepared(tmp_path)
    first = transfer(source, destination)
    assert first.returncode == 0, first.stderr
    receipt = json.loads(first.stdout)
    stage = destination / receipt['staging_path']
    full = stage / 'snapshot' / 'snapshot.sqlite'
    untouched = stage / 'snapshot' / 'manifest.json'
    previous_mtime = untouched.stat().st_mtime_ns
    content = full.read_bytes()
    full.unlink()
    partial = full.with_name(full.name + '.part')
    partial.write_bytes(content[:len(content) // 2])
    os.chmod(partial, 0o600)
    second = transfer(source, destination)
    assert second.returncode == 0, second.stderr
    assert full.read_bytes() == content
    assert not partial.exists()
    assert untouched.stat().st_mtime_ns == previous_mtime
    assert json.loads(second.stdout)['bytes_copied'] < len(content)


def test_corrupt_or_mismatched_staged_object_rejected_and_active_preserved(tmp_path):
    source, destination = prepared(tmp_path)
    first = transfer(source, destination)
    assert first.returncode == 0, first.stderr
    stage = destination / json.loads(first.stdout)['staging_path']
    active = destination / 'active'
    active.write_text('prior presentation')
    (stage / 'snapshot' / 'snapshot.sqlite').write_bytes(b'corrupt')
    failed = transfer(source, destination)
    assert failed.returncode != 0
    assert 'mismatch' in failed.stderr
    assert active.read_text() == 'prior presentation'


def test_changed_local_binding_is_rejected_before_staging(tmp_path):
    source, destination = prepared(tmp_path)
    state_path = source / 'state.json'
    state = json.loads(state_path.read_text())
    state['local']['bundle_manifest_sha256'] = '0' * 64
    state_path.write_text(json.dumps(state))
    failed = transfer(source, destination)
    assert failed.returncode != 0
    assert list(destination.iterdir()) == []
