#!/usr/bin/env python3
"""Prepare a code-only private owner archive from one exact committed revision.

This local checkpoint does not inspect or change a host, database, Access, VPN,
Worker, or evidence snapshot. Those gates require separate operator validation.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile


# Keep in sync with deploy/owner_evidence_activate.py FILES and SSH stage imports.
OWNER_FILES = (
    'scripts/owner_evidence_origin.py', 'scripts/private_presentation_status.py',
    'scripts/private_canonical_status.py', 'scripts/private_sporting_proofs.py', 'scripts/private_sporting_relationships.py', 'scripts/sporting_authority.py', 'scripts/sporting_authority_http.py', 'scripts/retained_aida_diff.py',
    'scripts/private_attempt_inspector.py',
    'scripts/owner_decision_store.py', 'scripts/aida_snapshot_observations.py',
    'scripts/issue55_aida_selected_html.py',
    'scripts/cmas_microplus_snapshot_observations.py',
    'scripts/cmas_microplus_ingest.py', 'scripts/cmas_microplus_finalize.py',
    'scripts/unified_evidence_query.py',
    'scripts/route_roster_query.py', 'scripts/owner_source_view.py',
    'scripts/private_source_bundle.py', 'scripts/vestico_safe_derivative.py',
    'resources/evidence_workspace.html', 'resources/evidence_workspace.js',
    'resources/evidence_workspace.css',
)
HOST_FILES = (
    'deploy/retained_source_import_guard.py',
    'deploy/owner_evidence_activate.py', 'deploy/freediving-owner-evidence.service',
    'deploy/canonical_status_runtime.py', 'deploy/sporting_proof_runtime.py',
    'deploy/provision_sporting_proof_reader.py', 'deploy/provision_source_review.py',
    'deploy/comparison_runtime.py', 'deploy/comparison_activate.py',
    'deploy/provision_canonical_status_reader.py', 'deploy/provision_sporting_authority.py', 'deploy/private_archive_extract.py',
    'scripts/private_evidence_transfer.py', 'scripts/private_evidence_ssh.py',
    'scripts/unified_evidence_snapshot.py',
    'scripts/local_evidence_run.py', 'scripts/evidence_presentation.py',
    'scripts/private_evidence_remote.py', 'scripts/private_status_sync.py',
    'scripts/macos_nordvpn.py', 'scripts/affiliate_name_query.py',
    'scripts/owner_decision_export_adapter.py', 'scripts/owner_snapshot_binding.py',
    'scripts/reconciliation_flow_host_proof.py',
)
FILES = OWNER_FILES + HOST_FILES


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL)


def inputs(repo, candidate):
    if not re.fullmatch(r'[0-9a-f]{40}', candidate):
        raise ValueError('candidate must be an exact 40-character commit SHA')
    if git(repo, 'rev-parse', 'HEAD').decode().strip() != candidate:
        raise ValueError('candidate differs from checkout HEAD')
    if git(repo, 'status', '--porcelain', '--untracked-files=all').strip():
        raise ValueError('checkout is not clean')
    archive = {}
    for name in FILES:
        entry = git(repo, 'ls-tree', candidate, '--', name).decode().strip()
        if not entry or not entry.startswith('100644 blob ') and not entry.startswith('100755 blob '):
            raise ValueError('missing tracked private owner input: ' + name)
        data = git(repo, 'show', candidate + ':' + name)
        if len(data) > 10 * 1024 * 1024:
            raise ValueError('oversized private owner input: ' + name)
        archive[name] = data
    return archive


def prepare(output, candidate, archive):
    output = output.absolute()
    parent = output.parent
    if (parent.is_symlink() or not parent.is_dir() or
            parent.stat().st_uid != os.geteuid() or parent.stat().st_mode & 0o077):
        raise ValueError('output parent must be an existing owner-only directory')
    if output.exists() or output.is_symlink():
        raise ValueError('refusing to overwrite archive')
    manifest = {'candidate': candidate, 'files': {
        name: hashlib.sha256(data).hexdigest() for name, data in archive.items()}}
    fd, temporary = tempfile.mkstemp(prefix='.private-owner-', suffix='.tar', dir=parent)
    try:
        with os.fdopen(fd, 'wb') as stream, tarfile.open(fileobj=stream, mode='w') as tar:
            for name, data in archive.items():
                info = tarfile.TarInfo(name)
                info.mode = 0o600
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
            data = (json.dumps(manifest, sort_keys=True) + '\n').encode()
            info = tarfile.TarInfo('private-owner-manifest.json')
            info.mode = 0o600
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        os.chmod(temporary, 0o600)
        try:
            os.link(temporary, output)
        except FileExistsError as error:
            raise ValueError('refusing to overwrite archive') from error
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--confirm', help='Repeat the exact candidate SHA for archive creation')
    parser.add_argument('--output', type=Path, help='New tar path in an existing owner-only directory')
    args = parser.parse_args()
    try:
        archive = inputs(args.repo, args.candidate)
        if args.prepare:
            if args.confirm != args.candidate or args.output is None:
                raise ValueError('prepare requires matching --confirm and --output')
            if args.output.absolute().is_relative_to(args.repo.resolve()):
                raise ValueError('archive output must be outside the repository')
            prepare(args.output, args.candidate, archive)
            print(json.dumps({'candidate': args.candidate, 'archive': str(args.output.absolute()),
                              'files': len(archive), 'host_ready': False}))
        else:
            if args.confirm or args.output:
                raise ValueError('--confirm and --output require --prepare')
            print(json.dumps({'candidate': args.candidate, 'files': len(archive),
                              'host_ready': False, 'mode': 'dry-run'}))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        message = str(error) if isinstance(error, ValueError) else 'repository or output check failed'
        print('Private owner preflight refused: ' + message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
