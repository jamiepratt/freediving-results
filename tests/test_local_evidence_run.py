import hashlib
import json
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'local_evidence_run.py'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(plan, directory):
    return subprocess.run([sys.executable, str(SCRIPT), 'run', '--plan', str(plan), '--run-dir', str(directory)], capture_output=True, text=True)


def fixture(tmp_path):
    packet = tmp_path / 'packet.json'
    source = tmp_path / 'source.pdf'
    source.write_bytes(b'%PDF-1.4\nsynthetic')
    generated = json.dumps({'schema': 'synthetic/v1', 'positions': [{'id': 'row-1', 'citation': {'page': 1}}]})
    counter = tmp_path / 'counter.txt'
    producer = tmp_path / 'producer.py'
    producer.write_text('import pathlib,sys\npacket,counter,content=map(pathlib.Path,sys.argv[1:])\nif content.with_suffix(".fail").exists(): raise SystemExit(8)\ncounter.write_text(counter.read_text()+"x" if counter.exists() else "x")\npacket.write_text(content.read_text())\n')
    content = tmp_path / 'content.txt'
    content.write_text(generated)
    inventory = tmp_path / 'inventory.json'
    inventory.write_text(json.dumps({'sources': [{'id': 'source.pdf', 'sha256': sha(source), 'bytes': source.stat().st_size, 'content_type': 'application/pdf', 'source_path': str(source), 'receipt': {'origin': 'synthetic'}, 'classification': 'eligible', 'metadata': {}}]}))
    plan = tmp_path / 'plan.json'
    plan.write_text(json.dumps({'schema': 'local-evidence-run-plan/v1', 'cutoff': '2026-10-03T00:00:00Z', 'stages': [{'name': 'parse', 'command': [sys.executable, str(producer), str(packet), str(counter), str(content)], 'outputs': [str(packet)]}], 'inputs': [{'name': 'packet', 'path': str(packet)}], 'excluded': [], 'source_inventory': str(inventory)}))
    return plan, packet, counter, inventory


def test_run_resumes_completed_stage_and_binds_snapshot_bundle(tmp_path):
    plan, packet, counter, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    first = run(plan, target)
    assert first.returncode == 0, first.stderr
    assert counter.read_text() == 'x'
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['remote']['status'] == 'pending'
    assert state['remote']['active'] is None
    assert state['remote']['failed'] is None
    assert state['coverage']['status'] == 'verified_partial'
    assert state['coverage']['gaps'] == []
    snapshot = target / 'snapshot'
    bundle = target / 'bundle'
    snapshot_manifest = json.loads((snapshot / 'manifest.json').read_text())
    bundle_manifest = json.loads((bundle / 'manifest.json').read_text())
    for identifier, path in [('snapshot.sqlite', snapshot / 'snapshot.sqlite'), ('snapshot-manifest.json', snapshot / 'manifest.json')]:
        entry = next(item for item in bundle_manifest['sources'] if item['id'] == identifier)
        assert entry['sha256'] == sha(path)
        assert (bundle / entry['object']).read_bytes() == path.read_bytes()
    assert state['local']['snapshot_sha256'] == snapshot_manifest['snapshot_sha256']
    assert state['local']['bundle_manifest_sha256'] == sha(bundle / 'manifest.json')
    second = run(plan, target)
    assert second.returncode == 0, second.stderr
    assert counter.read_text() == 'x'
    assert sha(snapshot / 'manifest.json') == state['local']['snapshot_manifest_sha256']


def test_interruption_resumes_and_keeps_prior_completed_run(tmp_path):
    plan, packet, counter, _ = fixture(tmp_path)
    prior = tmp_path / 'prior'
    assert run(plan, prior).returncode == 0
    previous = sha(prior / 'snapshot' / 'manifest.json')
    (tmp_path / 'content.fail').write_text('fail')
    pending = tmp_path / 'pending'
    failed = run(plan, pending)
    assert failed.returncode != 0
    state = json.loads((pending / 'state.json').read_text())
    assert state['local']['status'] == 'failed'
    assert state['remote']['active'] is None
    assert sha(prior / 'snapshot' / 'manifest.json') == previous
    (tmp_path / 'content.fail').unlink()
    resumed = run(plan, pending)
    assert resumed.returncode == 0, resumed.stderr
    assert json.loads((pending / 'state.json').read_text())['local']['status'] == 'complete'


def test_failed_snapshot_build_resumes_with_explicit_gap(tmp_path):
    plan, packet, counter, _ = fixture(tmp_path)
    missing = tmp_path / 'missing.json'
    gap = tmp_path / 'gap.json'
    gap.write_text(json.dumps({'gaps': [{'id': 'g1'}]}))
    spec = json.loads(plan.read_text())
    spec['inputs'].append({'name': 'later', 'path': str(missing)})
    spec['excluded'].append({'name': 'unresolved', 'path': str(gap), 'reason': 'publisher unavailable'})
    plan.write_text(json.dumps(spec))
    target = tmp_path / 'run'
    assert run(plan, target).returncode != 0
    assert json.loads((target / 'state.json').read_text())['stages']['parse']['status'] == 'complete'
    missing.write_text(json.dumps({'positions': [{'id': 'later-row'}]}))
    resumed = run(plan, target)
    assert resumed.returncode == 0, resumed.stderr
    assert counter.read_text() == 'x'
    state = json.loads((target / 'state.json').read_text())
    assert state['coverage']['gaps'] == [{'name': 'unresolved', 'reason': 'publisher unavailable'}]
    manifest = json.loads((target / 'snapshot' / 'manifest.json').read_text())
    assert manifest['inputs']['unresolved']['status'] == 'excluded'
    assert manifest['inputs']['later']['status'] == 'included'


def test_completed_run_rejects_corrupt_bound_bundle(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    assert run(plan, target).returncode == 0
    bundle_manifest = json.loads((target / 'bundle' / 'manifest.json').read_text())
    entry = next(item for item in bundle_manifest['sources'] if item['id'] == 'snapshot-manifest.json')
    (target / 'bundle' / entry['object']).write_bytes(b'corrupt')
    assert run(plan, target).returncode != 0
    assert json.loads((target / 'state.json').read_text())['remote']['status'] == 'pending'
