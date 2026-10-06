"""Pinned private code/runtime extraction at its public CLI boundary."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('private_archive_extract.py')


class ExtractionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.archive = self.root / 'bundle.tar'
        self.target = self.root / 'staged'

    def package(self, members):
        with tarfile.open(self.archive, 'w') as archive:
            for name, body, kind in members:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.size = len(body) if kind == tarfile.REGTYPE else 0
                member.linkname = 'scripts/a.py' if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE) else ''
                archive.addfile(member, io.BytesIO(body) if member.isfile() else None)
        self.archive.chmod(0o600)
        return hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def run_cli(self, digest):
        return subprocess.run([sys.executable, str(SCRIPT), '--archive', str(self.archive),
                               '--archive-sha256', digest, '--target', str(self.target)],
                              capture_output=True, text=True,
                              preexec_fn=lambda: os.umask(0o077))

    def test_private_umask_preserves_nested_traversal_and_pinned_file_bytes(self):
        bodies = {'resources/workspace.js': b'resource', 'scripts/reader.py': b'reader',
                  'deploy/activate.py': b'activation', 'lib/runtime.jar': b'jar',
                  'src/freediving/status.clj': b'source', 'manifest.json': b'{}'}
        digest = self.package([(name, body, tarfile.REGTYPE) for name, body in bodies.items()])
        cache = self.root / 'derived-cache'
        cache.mkdir(mode=0o700)
        (cache / 'private.json').write_text('private')
        (cache / 'private.json').chmod(0o600)
        result = self.run_cli(digest)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['archive_sha256'], digest)
        for path in (self.target, *(p for p in self.target.rglob('*') if p.is_dir())):
            self.assertEqual(path.stat().st_mode & 0o777, 0o755)
            self.assertEqual(path.stat().st_uid, os.geteuid())
            self.assertEqual(path.stat().st_gid, os.getegid())
        for name, body in bodies.items():
            path = self.target / name
            self.assertEqual(path.read_bytes(), body)
            self.assertEqual(path.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.archive.stat().st_mode & 0o777, 0o600)
        self.assertEqual(cache.stat().st_mode & 0o777, 0o700)
        self.assertEqual((cache / 'private.json').stat().st_mode & 0o777, 0o600)

    def test_unsafe_members_refuse_without_partial_staging(self):
        cases = ([('scripts/a.py', b'ok', tarfile.REGTYPE), ('../escape', b'x', tarfile.REGTYPE)],
                 [('/absolute', b'x', tarfile.REGTYPE)],
                 [('scripts/link', b'', tarfile.SYMTYPE)],
                 [('scripts/link', b'', tarfile.LNKTYPE)],
                 [('scripts', b'', tarfile.DIRTYPE)],
                 [('scripts/a.py', b'first', tarfile.REGTYPE), ('scripts/a.py', b'last', tarfile.REGTYPE)],
                 [('scripts', b'file', tarfile.REGTYPE), ('scripts/a.py', b'x', tarfile.REGTYPE)],
                 [('./scripts/a.py', b'x', tarfile.REGTYPE)])
        for members in cases:
            with self.subTest(members=[name for name, _, _ in members]):
                result = self.run_cli(self.package(members))
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.target.exists())
                self.assertFalse((self.root / 'escape').exists())

    def test_wrong_pin_existing_destination_and_linked_inputs_refuse(self):
        digest = self.package([('scripts/a.py', b'original', tarfile.REGTYPE)])
        self.assertNotEqual(self.run_cli('0' * 64).returncode, 0)
        self.assertFalse(self.target.exists())
        self.target.mkdir(mode=0o700)
        retained = self.target / 'retained'
        retained.write_text('preserve')
        self.assertNotEqual(self.run_cli(digest).returncode, 0)
        self.assertEqual(retained.read_text(), 'preserve')
        retained.unlink()
        self.target.rmdir()
        original = self.archive.with_name('original.tar')
        self.archive.rename(original)
        self.archive.symlink_to(original)
        self.assertNotEqual(self.run_cli(digest).returncode, 0)
        self.assertFalse(self.target.exists())
        self.archive.unlink()
        original.rename(self.archive)
        linked = self.root / 'linked'
        linked.symlink_to(self.root, target_is_directory=True)
        self.target = linked / 'staged'
        self.assertNotEqual(self.run_cli(digest).returncode, 0)
        self.assertFalse(self.target.exists())
