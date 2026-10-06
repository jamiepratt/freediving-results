"""Package the exact committed read-only canonical verifier and pinned Maven JARs."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def prepare(repo, output, candidate):
    spec = importlib.util.spec_from_file_location('canonical_reader', repo / 'scripts/private_canonical_status.py')
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    git = lambda *args: subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL)
    if git('rev-parse', 'HEAD').decode().strip() != candidate or git('status', '--porcelain').strip():
        raise ValueError('canonical runtime requires the exact clean committed checkout')
    return build_runtime(repo, output, candidate, reader, git)


def build_runtime(repo, output, candidate, reader, git=None):
    """Use git inputs for releases; direct source inputs are for isolated checks only."""
    if output.exists() or output.is_symlink() or output.is_relative_to(repo):
        raise ValueError('canonical runtime must use a new private output outside the checkout')
    classpath = subprocess.check_output(['clojure', '-Spath'], cwd=repo, text=True).strip().split(os.pathsep)
    jars = {Path(name).name: Path(name) for name in classpath if name.endswith('.jar')}
    if not set(reader.JARS) <= set(jars):
        raise ValueError('pinned canonical verifier dependencies unavailable')
    output.mkdir(mode=0o700, parents=True)
    try:
        files = {}
        for relative in sorted(reader.RUNTIME_FILES):
            target = output / relative
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            if relative.startswith('src/'):
                body = git('show', candidate + ':' + relative) if git else (repo / relative).read_bytes()
                target.write_bytes(body)
            else:
                shutil.copyfile(jars[Path(relative).name], target)
            target.chmod(0o600)
            files[relative] = hashlib.sha256(target.read_bytes()).hexdigest()
        manifest = {'candidate': candidate, 'files': files}
        (output / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True) + '\n')
        (output / 'manifest.json').chmod(0o600)
        return hashlib.sha256((output / 'manifest.json').read_bytes()).hexdigest()
    except Exception:
        shutil.rmtree(output)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--candidate', required=True)
    args = parser.parse_args()
    try:
        digest = prepare(args.repo.resolve(), args.output.absolute(), args.candidate)
        print(json.dumps({'runtime_manifest_sha256': digest, 'runtime': str(args.output)}))
    except Exception:
        print('Canonical runtime preparation refused', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
