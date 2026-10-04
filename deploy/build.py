#!/usr/bin/env python3
"""Build an explicit source/resources/JAR artifact; never includes data or secrets."""
import gzip, pathlib, shutil, subprocess, tarfile, tempfile
root = pathlib.Path(__file__).resolve().parents[1]
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
classpath = subprocess.check_output(['clojure', '-Spath'], cwd=root, text=True).strip()
out = root / 'data' / 'deploy'
out.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    staging = pathlib.Path(tmp)
    for name in ['src', 'resources', 'deploy']:
        shutil.copytree(root / name, staging / name)
    (staging / 'lib').mkdir()
    for item in classpath.split(':'):
        if item.endswith('.jar'):
            shutil.copy2(item, staging / 'lib' / pathlib.Path(item).name)
    (staging / 'REVISION').write_text(revision + '\n')
    # The service user must read releases even when the builder uses umask 077
    # or dependency cache files are private. Change only these packaged copies.
    for path in staging.rglob('*'):
        path.chmod(0o755 if path.is_dir() else 0o644)
    artifact = out / 'freediving.tar.gz'
    with artifact.open('wb') as stream, gzip.GzipFile(filename='', mode='wb', mtime=0, fileobj=stream) as compressed, tarfile.open(fileobj=compressed, mode='w') as tar:
        def stable(info):
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            return info
        for child in sorted(staging.iterdir()):
            tar.add(child, arcname=child.name, filter=stable)
print(artifact)
