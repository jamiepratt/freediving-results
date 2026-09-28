import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'private_source_bundle.py'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *map(str, args)], capture_output=True, text=True)


def entry(path, data, kind='eligible', **extra):
    return {'id': path.name, 'sha256': sha(data), 'bytes': len(data),
            'content_type': 'application/octet-stream', 'source_path': str(path),
            'receipt': {'origin': 'synthetic test', 'retrieved_at': '2026-09-28'},
            'classification': kind, 'metadata': {}, **extra}


def inventory(tmp_path, entries):
    p = tmp_path / 'inventory.json'
    p.write_text(json.dumps({'sources': entries}))
    return p


def test_build_verify_and_restore_independent_bundle(tmp_path):
    source = tmp_path / 'original.pdf'
    snapshot = tmp_path / 'snapshot.sqlite'
    snapshot_manifest = tmp_path / 'snapshot-manifest.json'
    for path, data in [(source, b'%PDF-1.4\nsource'), (snapshot, b'SQLite format 3\0'),
                       (snapshot_manifest, b'{"schema":"snapshot"}')]:
        path.write_bytes(data)
    entries = [entry(p, p.read_bytes()) for p in (source, snapshot, snapshot_manifest)]
    spec = inventory(tmp_path, entries)
    bundle = tmp_path / 'bundle'
    built = run('build', '--inventory', spec, '--bundle-dir', bundle)
    assert built.returncode == 0, built.stderr
    manifest_bytes = (bundle / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    assert manifest['schema'] == 'private-source-bundle/v1'
    assert [x['id'] for x in manifest['sources']] == sorted(p.name for p in (source, snapshot, snapshot_manifest))
    assert all(x['status'] == 'included' for x in manifest['sources'])
    assert all('source_path' not in x for x in manifest['sources'])
    assert os.stat(bundle).st_mode & 0o777 == 0o700
    for item in manifest['sources']:
        obj = bundle / item['object']
        assert obj.read_bytes() == next(p.read_bytes() for p in (source, snapshot, snapshot_manifest) if p.name == item['id'])
        assert os.stat(obj).st_mode & 0o777 == 0o600
    assert run('verify', '--bundle-dir', bundle).returncode == 0
    for p in (source, snapshot, snapshot_manifest):
        p.unlink()
    restored = tmp_path / 'restored'
    result = run('restore', '--bundle-dir', bundle, '--output-dir', restored)
    assert result.returncode == 0, result.stderr
    assert (restored / 'manifest.json').read_bytes() == manifest_bytes
    assert sorted(p.name for p in (restored / 'objects').iterdir()) == sorted(p.name for p in (bundle / 'objects').iterdir())
    assert run('verify', '--bundle-dir', restored).returncode == 0


def test_replay_is_deterministic_and_records_gaps(tmp_path):
    safe = tmp_path / 'safe.pdf'
    safe.write_bytes(b'pdf')
    restricted = tmp_path / 'discovery.html'
    restricted.write_text('<html>session=PRIVATE</html>')
    entries = [entry(safe, safe.read_bytes()),
               entry(restricted, restricted.read_bytes(), 'restricted', content_type='text/html',
                     reason='ephemeral session material', derivative='safe.json'),
               entry(tmp_path / 'gone.pdf', b'missing', 'missing', reason='original unavailable'),
               entry(tmp_path / 'review.pdf', b'review', 'excluded', reason='outside scope')]
    spec = inventory(tmp_path, entries)
    a, b = tmp_path / 'a', tmp_path / 'b'
    for bundle in (a, b):
        result = run('build', '--inventory', spec, '--bundle-dir', bundle)
        assert result.returncode == 0, result.stderr
    assert (a / 'manifest.json').read_bytes() == (b / 'manifest.json').read_bytes()
    assert len(list((a / 'objects').iterdir())) == 1
    content = (a / 'manifest.json').read_text()
    assert 'PRIVATE' not in content
    statuses = {x['id']: x['status'] for x in json.loads(content)['sources']}
    assert statuses == {'safe.pdf': 'included', 'discovery.html': 'restricted',
                        'gone.pdf': 'missing', 'review.pdf': 'excluded'}
    restricted_entry = next(x for x in json.loads(content)['sources'] if x['id'] == 'discovery.html')
    assert restricted_entry['derivative'] == 'safe.json'


def test_bad_original_and_bad_restore_fail(tmp_path):
    source = tmp_path / 'source.pdf'
    source.write_bytes(b'wrong')
    spec = inventory(tmp_path, [entry(source, b'expected')])
    bundle = tmp_path / 'bundle'
    assert run('build', '--inventory', spec, '--bundle-dir', bundle).returncode != 0
    assert not bundle.exists()
    source.write_bytes(b'expected')
    assert run('build', '--inventory', spec, '--bundle-dir', bundle).returncode == 0
    (bundle / 'objects' / sha(b'expected')).write_bytes(b'tampered')
    assert run('verify', '--bundle-dir', bundle).returncode != 0
    restored = tmp_path / 'restored'
    assert run('restore', '--bundle-dir', bundle, '--output-dir', restored).returncode != 0
    assert not restored.exists()


def test_html_and_secret_receipts_cannot_enter_bundle(tmp_path):
    html = tmp_path / 'page.html'
    html.write_text('<html>token=SECRET</html>')
    spec = inventory(tmp_path, [entry(html, html.read_bytes(), content_type='text/html')])
    assert run('build', '--inventory', spec, '--bundle-dir', tmp_path / 'bad-html').returncode != 0
    pdf = tmp_path / 'source.pdf'
    pdf.write_bytes(b'pdf')
    secret = entry(pdf, pdf.read_bytes(), receipt={'url': 'https://example.test/file?token=SECRET'})
    spec = inventory(tmp_path, [secret])
    result = run('build', '--inventory', spec, '--bundle-dir', tmp_path / 'safe')
    assert result.returncode == 0, result.stderr
    assert 'SECRET' not in (tmp_path / 'safe' / 'manifest.json').read_text()


def test_duplicate_namespace_refs_share_one_object_and_unknown_missing_size(tmp_path):
    source = tmp_path / 'source.pdf'
    source.write_bytes(b'one source')
    first = entry(source, source.read_bytes(), id='baseline:sha256')
    second = entry(source, source.read_bytes(), id='supplement:sha256')
    missing = entry(tmp_path / 'absent.pdf', b'absent', 'missing', id='baseline:absent',
                    reason='original not found', bytes=None, source_path=None)
    spec = inventory(tmp_path, [second, missing, first])
    bundle = tmp_path / 'bundle'
    result = run('build', '--inventory', spec, '--bundle-dir', bundle)
    assert result.returncode == 0, result.stderr
    assert len(list((bundle / 'objects').iterdir())) == 1
    entries = json.loads((bundle / 'manifest.json').read_text())['sources']
    assert len(entries) == 3
    assert entries[0]['bytes'] is None
    assert entries[1]['object'] == entries[2]['object']


def test_eligible_json_secret_and_object_symlink_are_rejected(tmp_path):
    secret_json = tmp_path / 'source.json'
    secret_json.write_text('{"api_key":"PRIVATE"}')
    spec = inventory(tmp_path, [entry(secret_json, secret_json.read_bytes(), content_type='application/json')])
    bad = run('build', '--inventory', spec, '--bundle-dir', tmp_path / 'secret-bundle')
    assert bad.returncode != 0
    assert not (tmp_path / 'secret-bundle').exists()

    safe_json = tmp_path / 'safe.json'
    safe_json.write_text('{"results":[]}', encoding='utf-8')
    spec = inventory(tmp_path, [entry(safe_json, safe_json.read_bytes(), content_type='application/json')])
    bundle = tmp_path / 'safe-bundle'
    assert run('build', '--inventory', spec, '--bundle-dir', bundle).returncode == 0
    obj = bundle / 'objects' / sha(safe_json.read_bytes())
    obj.unlink()
    obj.symlink_to(safe_json)
    assert run('verify', '--bundle-dir', bundle).returncode != 0


def test_claimed_content_type_must_match_eligible_bytes(tmp_path):
    claims = (
        ('fake.pdf', b'plain text', 'application/pdf'),
        ('fake.json', b'{bad json', 'application/json'),
        ('fake.sqlite', b'not sqlite', 'application/vnd.sqlite3'),
        ('fake.xlsx', b'not a zip', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
    )
    for name, data, content_type in claims:
        source = tmp_path / name
        source.write_bytes(data)
        spec = inventory(tmp_path, [entry(source, data, content_type=content_type)])
        bundle = tmp_path / (name + '-bundle')
        result = run('build', '--inventory', spec, '--bundle-dir', bundle)
        assert result.returncode != 0, (name, result.stderr)
        assert not bundle.exists()


def test_verify_requires_private_modes_and_real_objects_directory(tmp_path):
    source = tmp_path / 'source.pdf'
    source.write_bytes(b'%PDF-1.4\nsource')
    spec = inventory(tmp_path, [entry(source, source.read_bytes(), content_type='application/pdf')])
    bundle = tmp_path / 'bundle'
    assert run('build', '--inventory', spec, '--bundle-dir', bundle).returncode == 0
    assert run('verify', '--bundle-dir', bundle).returncode == 0
    objects = bundle / 'objects'
    os.chmod(objects, 0o755)
    assert run('verify', '--bundle-dir', bundle).returncode != 0
    os.chmod(objects, 0o700)
    os.chmod(bundle, 0o755)
    assert run('verify', '--bundle-dir', bundle).returncode != 0
    os.chmod(bundle, 0o700)
    os.chmod(bundle / 'manifest.json', 0o644)
    assert run('verify', '--bundle-dir', bundle).returncode != 0
    os.chmod(bundle / 'manifest.json', 0o600)
    object_path = objects / sha(source.read_bytes())
    os.chmod(object_path, 0o644)
    assert run('verify', '--bundle-dir', bundle).returncode != 0
    os.chmod(object_path, 0o600)
    object_path.unlink()
    objects.rmdir()
    objects.symlink_to(tmp_path, target_is_directory=True)
    assert run('verify', '--bundle-dir', bundle).returncode != 0
