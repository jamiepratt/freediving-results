import json
import os
import subprocess
import sys
from pathlib import Path

from test_private_evidence_transfer import prepared

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'private_evidence_transfer.py'


def ssh_standin(tmp_path):
    wrapper = tmp_path / 'ssh-standin'
    wrapper.write_text('''#!/usr/bin/env python3
import os, subprocess, sys
args = sys.argv[1:]
assert args[:2] == ['-T', '--']
assert args[2] == 'safe-host'
result = subprocess.run(args[3], shell=True, stdin=sys.stdin.buffer, stdout=sys.stdout.buffer, stderr=sys.stderr.buffer)
raise SystemExit(result.returncode)
''')
    wrapper.chmod(0o700)
    return wrapper


def transfer(source, destination, ssh):
    return subprocess.run([sys.executable, str(SCRIPT), 'ssh-stage', '--run-dir', str(source),
                           '--host', 'safe-host', '--remote-root', str(destination),
                           '--remote-script', str(SCRIPT), '--ssh', str(ssh)],
                          capture_output=True, text=True)


def test_ssh_stage_verifies_receipt_and_keeps_active(tmp_path):
    source, destination = prepared(tmp_path)
    active = destination / 'active'
    active.write_text('previous')
    result = transfer(source, destination, ssh_standin(tmp_path))
    assert result.returncode == 0, result.stderr
    receipt = json.loads(result.stdout)
    stage = destination / receipt['staging_path']
    assert json.loads((stage / 'transfer.json').read_text()) == {k: v for k, v in receipt.items() if k != 'bytes_copied'}
    assert active.read_text() == 'previous'
    for path in stage.rglob('*'):
        assert (path.stat().st_mode & 0o777) == (0o700 if path.is_dir() else 0o600)
    again = transfer(source, destination, ssh_standin(tmp_path))
    assert again.returncode == 0, again.stderr
    assert json.loads(again.stdout)['bytes_copied'] == 0


def test_ssh_stage_rejects_corrupt_remote_without_touching_active(tmp_path):
    source, destination = prepared(tmp_path)
    ssh = ssh_standin(tmp_path)
    first = transfer(source, destination, ssh)
    assert first.returncode == 0, first.stderr
    stage = destination / json.loads(first.stdout)['staging_path']
    (stage / 'snapshot' / 'snapshot.sqlite').write_bytes(b'corrupt')
    active = destination / 'active'
    active.write_text('previous')
    failed = transfer(source, destination, ssh)
    assert failed.returncode != 0
    assert 'mismatch' in failed.stderr
    assert str(source) not in failed.stderr
    assert str(destination) not in failed.stderr
    assert active.read_text() == 'previous'


def test_ssh_stage_resumes_verified_partial(tmp_path):
    source, destination = prepared(tmp_path)
    ssh = ssh_standin(tmp_path)
    first = transfer(source, destination, ssh)
    assert first.returncode == 0, first.stderr
    stage = destination / json.loads(first.stdout)['staging_path']
    full = stage / 'snapshot' / 'snapshot.sqlite'
    content = full.read_bytes()
    full.unlink()
    part = full.with_name(full.name + '.part')
    part.write_bytes(content[:len(content) // 2])
    part.chmod(0o600)
    resumed = transfer(source, destination, ssh)
    assert resumed.returncode == 0, resumed.stderr
    assert full.read_bytes() == content
    assert not part.exists()
    full.unlink()
    part.write_bytes(b'bad')
    part.chmod(0o600)
    failed = transfer(source, destination, ssh)
    assert failed.returncode != 0
    assert 'partial mismatch' in failed.stderr


def test_ssh_stage_quotes_remote_paths_and_hides_remote_stderr(tmp_path):
    source, _ = prepared(tmp_path)
    destination = tmp_path / 'remote $(echo injected)'
    destination.mkdir(mode=0o700)
    ssh = ssh_standin(tmp_path)
    result = transfer(source, destination, ssh)
    assert result.returncode == 0, result.stderr
    assert not (tmp_path / 'remote injected').exists()
    reject = tmp_path / 'reject-ssh'
    reject.write_text('#!/bin/sh\necho "private /source/path token" >&2\nexit 1\n')
    reject.chmod(0o700)
    failed = transfer(source, destination, reject)
    assert failed.returncode != 0
    assert 'private /source/path token' not in failed.stderr
    assert str(source) not in failed.stderr
    assert str(destination) not in failed.stderr


def test_ssh_stage_fails_closed_on_remote_protocol_drift(tmp_path):
    source, destination = prepared(tmp_path)
    standin = tmp_path / 'drifted-ssh'
    standin.write_text("#!/bin/sh\necho '{\"protocol\":\"private-evidence-ssh/v0\"}'\n")
    standin.chmod(0o700)
    result = transfer(source, destination, standin)
    assert result.returncode != 0
    assert 'protocol mismatch' in result.stderr
    assert list(destination.iterdir()) == []
