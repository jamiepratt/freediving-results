"""Package the exact committed read-only sporting proof verifier and pinned Maven JARs."""
import argparse
import hashlib
import importlib.util
import json
import os
import pwd
import re
from pathlib import Path
import shutil
import subprocess
import sys


def prepare(repo, output, candidate):
    git = lambda *args: subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL)
    if git('rev-parse', 'HEAD').decode().strip() != candidate or git('status', '--porcelain').strip():
        raise ValueError('sporting proof runtime requires the exact clean committed checkout')
    spec = importlib.util.spec_from_file_location('sporting_proof_reader', repo / 'scripts/private_sporting_proofs.py')
    reader = importlib.util.module_from_spec(spec)
    previous = sys.dont_write_bytecode
    previous_path = list(sys.path)
    try:
        sys.dont_write_bytecode = True
        sys.path.insert(0, str((repo / 'scripts').resolve()))
        spec.loader.exec_module(reader)
    finally:
        sys.dont_write_bytecode = previous
        sys.path[:] = previous_path
    return build_runtime(repo, output, candidate, reader, git)


def build_runtime(repo, output, candidate, reader, git=None):
    """Use git inputs for releases; direct source inputs are for isolated checks only."""
    if output.exists() or output.is_symlink() or output.is_relative_to(repo):
        raise ValueError('sporting proof runtime must use a new private output outside the checkout')
    classpath = subprocess.check_output(['clojure', '-Spath'], cwd=repo, text=True).strip().split(os.pathsep)
    jars = {Path(name).name: Path(name) for name in classpath if name.endswith('.jar')}
    if not set(reader.JARS) <= set(jars):
        raise ValueError('pinned sporting proof verifier dependencies unavailable')
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


def stage(source, manifest_sha256, state, uid, gid):
    """Stage independently hash-checked code/JARs without attaching a capability."""
    from owner_evidence_activate import _stage_directory
    from provision_sporting_proof_reader import digest, unlinked
    unlinked(source)
    manifest=source/'manifest.json'
    if not re.fullmatch('[0-9a-f]{64}',manifest_sha256) or digest(manifest)!=manifest_sha256:
        raise ValueError('sporting proof stage manifest changed')
    value=json.loads(manifest.read_text())
    if not re.fullmatch('[0-9a-f]{40}',value['candidate']):raise ValueError('sporting proof stage candidate invalid')
    files=[]
    for relative,pin in value['files'].items():
        if (Path(relative).is_absolute() or '..' in Path(relative).parts
                or not relative.startswith(('src/','lib/')) or digest(source/relative)!=pin):
            raise ValueError('sporting proof stage file changed')
        files.append((relative,source/relative))
    if any(p.is_symlink() for p in source.rglob('*')):raise ValueError('linked sporting proof stage input')
    actual={str(p.relative_to(source)) for p in source.rglob('*') if p.is_file()}
    if actual!=set(value['files'])|{'manifest.json'}:raise ValueError('unexpected sporting proof stage input')
    parent=state/'sporting-proof-payloads';unlinked(parent)
    parent.mkdir(mode=0o750,exist_ok=True);parent.chmod(0o750);os.chown(parent,uid,gid)
    unlinked(parent/'runtimes'/manifest_sha256)
    destination=_stage_directory(parent/'runtimes',manifest_sha256,
                                 [('manifest.json',manifest),*files],uid,gid,0o644)
    if any(p.is_symlink() for p in destination.rglob('*')):raise ValueError('linked sporting proof staged output')
    installed={str(p.relative_to(destination)) for p in destination.rglob('*') if p.is_file()}
    if installed!=actual:raise ValueError('unexpected sporting proof staged output')
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', type=Path)
    parser.add_argument('--stage', action='store_true')
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--runtime-manifest-sha256')
    parser.add_argument('--candidate')
    args = parser.parse_args()
    try:
        if args.stage:
            if os.geteuid()!=0 or not args.runtime or not args.runtime_manifest_sha256:
                raise ValueError('root host proof stage and exact inputs required')
            account=pwd.getpwnam('freediving-evidence')
            output=stage(args.runtime,args.runtime_manifest_sha256,Path('/var/lib/freediving-owner-evidence'),0,account.pw_gid)
            digest=args.runtime_manifest_sha256
        else:
            if not args.output or not args.candidate:raise ValueError('proof package output and candidate required')
            digest = prepare(args.repo.resolve(), args.output.absolute(), args.candidate)
            output=args.output
        print(json.dumps({'runtime_manifest_sha256': digest, 'runtime': str(output)}))
    except Exception:
        print('Sporting proof runtime preparation refused', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
