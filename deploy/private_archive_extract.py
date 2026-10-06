#!/usr/bin/env python3
"""Extract one pinned code/runtime archive into a new service-traversable directory."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import tarfile


def extract(archive, target, expected_sha256):
    """Only new code/runtime paths are widened; archive and parent stay private."""
    archive, target = Path(archive).absolute(), Path(target).absolute()
    if (not re.fullmatch(r'[0-9a-f]{64}', expected_sha256)
            or any(p.is_symlink() for p in (archive, *archive.parents, target, *target.parents))
            or not archive.is_file() or not target.parent.is_dir()):
        raise ValueError('unsafe private extraction input')
    if target.exists():
        raise ValueError('staged path already exists')
    # Hash and extract the same open file, so replacing the archive cannot change
    # the bytes consumed after its pin was verified.
    with archive.open('rb') as stream:
        content_hash = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            content_hash.update(block)
        digest = content_hash.hexdigest()
        if digest != expected_sha256:
            raise ValueError('private archive pin changed')
        stream.seek(0)
        with tarfile.open(fileobj=stream) as source:
            members = source.getmembers()
            names = set()
            for member in members:
                parts = member.name.split('/')
                if (not member.isfile() or any(p in ('', '.', '..') for p in parts)
                        or '\\' in member.name or member.name in names):
                    raise ValueError('unsafe private archive')
                names.add(member.name)
            if any('/'.join(name.split('/')[:i]) in names
                   for name in names for i in range(1, len(name.split('/')))):
                raise ValueError('conflicting private archive members')
            target.mkdir(mode=0o700)
            try:
                for member in members:
                    dest = target / member.name
                    dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    with source.extractfile(member) as body, dest.open('xb') as output:
                        shutil.copyfileobj(body, output)
                    dest.chmod(0o644)
                for directory in (target, *(p for p in target.rglob('*') if p.is_dir())):
                    directory.chmod(0o755)
            except Exception:
                shutil.rmtree(target)
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--archive-sha256', required=True)
    parser.add_argument('--target', required=True, type=Path)
    args = parser.parse_args()
    try:
        extract(args.archive, args.target, args.archive_sha256)
        print(json.dumps({'archive_sha256': args.archive_sha256, 'target': str(args.target)}))
    except Exception:
        print('Private archive extraction refused', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
