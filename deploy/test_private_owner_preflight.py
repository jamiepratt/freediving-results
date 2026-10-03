import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('private_owner_preflight.py')
MATCHING_PRIVATE_HELPERS = {
    'scripts/local_evidence_run.py',
    'scripts/evidence_presentation.py',
    'scripts/private_evidence_remote.py',
    'scripts/private_status_sync.py',
    'scripts/macos_nordvpn.py',
    'scripts/affiliate_name_query.py',
    'scripts/owner_decision_export_adapter.py',
    'scripts/owner_snapshot_binding.py',
}


class PrivateOwnerPreflightCLI(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True).strip()

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), '--repo', str(self.root), *args],
                              text=True, capture_output=True)

    def test_dry_run_fails_closed_when_private_host_code_is_missing(self):
        (self.root / 'README').write_text('test')
        self.git('add', '.')
        self.git('commit', '-qm', 'initial')
        result = self.run_cli('--candidate', self.git('rev-parse', 'HEAD'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('missing tracked private owner input', result.stderr)
        self.assertEqual(list(self.root.glob('*.tar')), [])

    def test_exact_commit_and_confirmation_prepare_code_only_archive(self):
        spec = importlib.util.spec_from_file_location('preflight', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for name in module.FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('code for ' + name)
        self.git('add', '.')
        self.git('commit', '-qm', 'candidate')
        candidate = self.git('rev-parse', 'HEAD')
        dry = self.run_cli('--candidate', candidate)
        self.assertEqual(dry.returncode, 0, dry.stderr)
        self.assertFalse(json.loads(dry.stdout)['host_ready'])
        private = Path(self.temp.name) / 'private'
        private.mkdir(mode=0o700)
        output = private / 'owner-code.tar'
        refused = self.run_cli('--candidate', candidate, '--prepare', '--output', str(output))
        self.assertNotEqual(refused.returncode, 0)
        self.assertFalse(output.exists())
        ready = self.run_cli('--candidate', candidate, '--prepare', '--confirm', candidate,
                             '--output', str(output))
        self.assertEqual(ready.returncode, 0, ready.stderr)
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        with tarfile.open(output) as tar:
            self.assertTrue(MATCHING_PRIVATE_HELPERS <= set(tar.getnames()))
            self.assertEqual(set(tar.getnames()), set(module.FILES) | {'private-owner-manifest.json'})
            manifest = json.load(tar.extractfile('private-owner-manifest.json'))
            self.assertEqual(manifest['candidate'], candidate)
            self.assertEqual(set(manifest['files']), set(module.FILES))
        self.assertFalse(json.loads(ready.stdout)['host_ready'])

    def test_dirty_checkout_refuses_even_when_candidate_is_committed(self):
        (self.root / 'README').write_text('first')
        self.git('add', '.')
        self.git('commit', '-qm', 'candidate')
        candidate = self.git('rev-parse', 'HEAD')
        (self.root / 'README').write_text('changed')
        result = self.run_cli('--candidate', candidate)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('checkout is not clean', result.stderr)


if __name__ == '__main__':
    unittest.main()
