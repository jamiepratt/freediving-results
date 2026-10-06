"""Package committed comparison contracts and an independently pinned private corpus packet."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def prepare(repo, output, candidate, bundle, cutoff):
    git = lambda *args: subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL)
    if git('rev-parse', 'HEAD').decode().strip() != candidate or git('status', '--porcelain').strip():
        raise ValueError('comparison runtime requires exact clean committed checkout')
    return build_runtime(repo, output, candidate, bundle, cutoff, git=git)


def build_runtime(repo, output, candidate, bundle=None, cutoff=None, *, git=None):
    """Direct files are for isolated synthetic checks; releases require prepare()."""
    spec = importlib.util.spec_from_file_location('comparison_reader', repo / 'scripts/private_attempt_inspector.py')
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    if output.exists() or output.is_symlink() or output.is_relative_to(repo):
        raise ValueError('comparison runtime needs new private output outside checkout')
    classpath = subprocess.check_output(['clojure', '-Spath'], cwd=repo, text=True).strip().split(os.pathsep)
    jars = {Path(name).name: Path(name) for name in classpath if name.endswith('.jar')}
    if not set(reader.JARS) <= set(jars):
        raise ValueError('pinned comparison dependencies unavailable')
    output.mkdir(mode=0o700, parents=True)
    try:
        runtime = output / 'runtime'
        runtime.mkdir(mode=0o700)
        files = {}
        for relative in sorted(reader.RUNTIME_FILES):
            target = runtime / relative
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            if relative.startswith('src/'):
                target.write_bytes(git('show', candidate + ':' + relative) if git else (repo / relative).read_bytes())
            else:
                shutil.copyfile(jars[Path(relative).name], target)
            target.chmod(0o600)
            files[relative] = hashlib.sha256(target.read_bytes()).hexdigest()
        manifest = runtime / 'manifest.json'
        manifest.write_text(json.dumps({'candidate': candidate, 'files': files}, sort_keys=True) + '\n')
        manifest.chmod(0o600)
        result = {'runtime_path': str(runtime),
                  'runtime_manifest_sha256': hashlib.sha256(manifest.read_bytes()).hexdigest()}
        if bundle:
            command = ['/usr/bin/java', '-Xmx256m', '-cp', str(runtime / 'src') + os.pathsep +
                       os.pathsep.join(str(runtime / 'lib' / jar) for jar in reader.JARS),
                       'clojure.main', '-m', 'freediving.private-attempt-inspector',
                       'prepare', str(bundle), cutoff]
            packet_body = subprocess.check_output(command, cwd=repo, stderr=subprocess.PIPE, timeout=60)
            packet = output / 'packet.edn'
            packet.write_bytes(packet_body)
            packet.chmod(0o600)
            result['packet'] = {'path': str(packet), 'sha256': hashlib.sha256(packet_body).hexdigest()}
            result['authority_evidence'] = None
            cmas_sha = 'f403777b250b7ae816adea945db4cd5ddea51be7349c2efa57046a08671a5758'
            cmas_body = (bundle / 'payload/b16/archive/objects' / cmas_sha).read_bytes()
            if hashlib.sha256(cmas_body).hexdigest() != cmas_sha or not cmas_body.startswith(b'%PDF-'):
                raise ValueError('private retained CMAS PDF changed')
            source_dir = output / 'sources'
            source_dir.mkdir(mode=0o700)
            cmas_source = source_dir / (cmas_sha + '.pdf')
            cmas_source.write_bytes(cmas_body)
            cmas_source.chmod(0o600)
            result['source_objects'] = {cmas_sha: {'path': str(cmas_source), 'sha256': cmas_sha,
                                                 'mime_type': 'application/pdf'}}
            config = output / 'config.json'
            config.write_text(json.dumps(result, sort_keys=True) + '\n')
            config.chmod(0o600)
        return result
    except Exception:
        shutil.rmtree(output)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--cutoff', required=True)
    args = parser.parse_args()
    try:
        result = prepare(args.repo.resolve(), args.output.absolute(), args.candidate, args.bundle.resolve(), args.cutoff)
        print(json.dumps(result, sort_keys=True))
    except Exception:
        print('Private comparison runtime preparation refused', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
