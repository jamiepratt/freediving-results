"""Release artifacts remain readable by the service under a private build umask."""
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


class BuildPermissionsTest(unittest.TestCase):
    def test_private_inputs_produce_service_readable_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for name in ['src', 'resources', 'deploy', 'data', 'bin']:
                (root / name).mkdir(mode=0o700)
            shutil.copyfile(pathlib.Path(__file__).with_name('build.py'), root / 'deploy/build.py')
            source = root / 'src/example.clj'
            source.write_text('(ns example)\n')
            source.chmod(0o600)
            (root / 'resources/example.edn').write_text('{}\n')
            (root / 'data/private.pdf').write_bytes(b'private archive')
            (root / '.env').write_text('PRIVATE=not-for-release\n')
            jar = root / 'private-dependency.jar'
            jar.write_bytes(b'fixture dependency')
            jar.chmod(0o600)
            for command, output in [('git', 'a' * 40), ('clojure', str(jar))]:
                tool = root / 'bin' / command
                tool.write_text('#!' + sys.executable + '\nprint(' + repr(output) + ')\n')
                tool.chmod(0o700)
            subprocess.run([sys.executable, str(root / 'deploy/build.py')],
                           env={**os.environ, 'PATH': str(root / 'bin') + os.pathsep + os.environ['PATH']},
                           umask=0o077, check=True, capture_output=True, text=True)
            with tarfile.open(root / 'data/deploy/freediving.tar.gz') as artifact:
                members = artifact.getmembers()
                self.assertEqual({'src', 'resources', 'deploy', 'lib', 'REVISION'},
                                 {member.name.split('/')[0] for member in members})
                self.assertEqual(b'fixture dependency', artifact.extractfile('lib/private-dependency.jar').read())
                for member in members:
                    with self.subTest(member=member.name):
                        self.assertEqual(0o755 if member.isdir() else 0o644, member.mode)
            self.assertEqual(0o600, source.stat().st_mode & 0o777)
            self.assertEqual(0o600, jar.stat().st_mode & 0o777)


if __name__ == '__main__':
    unittest.main()
