import json
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_private_evidence_transfer import prepared
from test_private_evidence_ssh import ssh_standin
from evidence_presentation import present
from private_evidence_remote import PrivateEvidenceRemote


class RemoteAdapterTests(unittest.TestCase):
    def test_present_activates_bound_stage_and_reads_authenticated_source(self):
        with TemporaryDirectory() as root:
            run_dir, destination = prepared(Path(root))
            source = next(x for x in json.loads((run_dir / 'bundle/manifest.json').read_text())['sources'] if x['id'] == 'source.pdf')
            commands = []
            responses = {
                '/api/overview': {'snapshot_sha256': json.loads((run_dir / 'state.json').read_text())['local']['snapshot_sha256'], 'bundle_manifest_sha256': json.loads((run_dir / 'state.json').read_text())['local']['bundle_manifest_sha256']},
                '/api/source-view/' + 'a' * 64: {'snapshot_sha256': json.loads((run_dir / 'state.json').read_text())['local']['snapshot_sha256'], 'source_sha256': source['sha256'], 'source_bytes': source['bytes'], 'format': 'pdf', 'page': 1, 'citation': {'page': 1}},
            }
            def command(argv):
                commands.append(argv)
                return 'activated'
            def http(path, headers, timeout):
                self.assertEqual(headers, {'Cookie': 'CF_Authorization=aaa.bbb.ccc'})
                if path.endswith('/page/1'):
                    return 200, 'image/png', b'\x89PNG\r\n\x1a\n' + b'page'
                return 200, 'application/json', responses[path]
            remote = PrivateEvidenceRemote('safe-host', destination, Path(__file__).resolve().parents[1] / 'scripts/private_evidence_transfer.py', ssh_standin(Path(root)), Path('/opt/code'), Path('/opt/code/deploy/owner_evidence_activate.py'), 'https://poc.alphacompose.com/owner-evidence', 'aaa.bbb.ccc', 'a' * 64, source['sha256'], command=command, http=http)
            result = present(run_dir, remote, publisher_requests_stopped=True, vps_reachable=lambda: True)
            self.assertEqual(result['status'], 'active')
            self.assertEqual(len(commands), 1)
            self.assertIn('--expected-sha256', commands[0][-1])
            self.assertIn('--expected-source-manifest-sha256', commands[0][-1])
            self.assertIn('--bundle-dir', commands[0][-1])
            self.assertIn('/opt/code', commands[0][-1])
            fresh = PrivateEvidenceRemote('safe-host', destination, Path(__file__).resolve().parents[1] / 'scripts/private_evidence_transfer.py', ssh_standin(Path(root)), Path('/opt/code'), Path('/opt/code/deploy/owner_evidence_activate.py'), 'https://poc.alphacompose.com/owner-evidence', 'aaa.bbb.ccc', 'a' * 64, source['sha256'], command=command, http=http)
            self.assertEqual(present(run_dir, fresh, publisher_requests_stopped=True, vps_reachable=lambda: True)['status'], 'active')
            self.assertEqual(len(commands), 1)

    def test_denied_or_mismatched_owner_readback_rolls_back_and_retains_retry(self):
        for fault in ('expired', 'missing-auth', 'stale-snapshot', 'source-denied', 'source-mismatch', 'page-denied', 'page-type', 'page-mismatch', 'timeout'):
            with self.subTest(fault=fault), TemporaryDirectory() as root:
                run_dir, destination = prepared(Path(root))
                local = json.loads((run_dir / 'state.json').read_text())['local']
                source = next(x for x in json.loads((run_dir / 'bundle/manifest.json').read_text())['sources'] if x['id'] == 'source.pdf')
                commands = []
                def command(argv):
                    commands.append(argv[-1])
                    return 'rolled back' if '--rollback-candidate-sha256' in argv[-1] else 'activated'
                def http(path, headers, timeout):
                    if fault == 'timeout':
                        raise TimeoutError('stand-in timeout')
                    if fault == 'missing-auth':
                        self.assertTrue(headers['Cookie'])
                        return 403, 'application/json', {}
                    if fault == 'expired':
                        return 302, 'application/json', {}
                    if path.endswith('/page/1'):
                        return (403, 'application/json', {}) if fault == 'page-denied' else (200, 'application/octet-stream' if fault == 'page-type' else 'image/png', b'bad' if fault == 'page-mismatch' else b'\x89PNG\r\n\x1a\n' + b'page')
                    if path == '/api/overview':
                        return 200, 'application/json', {'snapshot_sha256': '0' * 64 if fault == 'stale-snapshot' else local['snapshot_sha256'], 'bundle_manifest_sha256': local['bundle_manifest_sha256']}
                    if fault == 'source-denied':
                        return 403, 'application/json', {}
                    return 200, 'application/json', {'snapshot_sha256': local['snapshot_sha256'], 'source_sha256': '0' * 64 if fault == 'source-mismatch' else source['sha256'], 'source_bytes': source['bytes'], 'format': 'pdf', 'page': 1, 'citation': {'page': 1}}
                remote = PrivateEvidenceRemote('safe-host', destination, Path(__file__).resolve().parents[1] / 'scripts/private_evidence_transfer.py', ssh_standin(Path(root)), Path('/opt/code'), Path('/opt/code/deploy/owner_evidence_activate.py'), 'https://poc.alphacompose.com/owner-evidence', 'aaa.bbb.ccc', 'a' * 64, source['sha256'], command=command, http=http)
                with self.assertRaises((ValueError, RuntimeError)):
                    present(run_dir, remote, publisher_requests_stopped=True, vps_reachable=lambda: True)
                self.assertEqual(len(commands), 2)
                self.assertIn('--rollback-candidate-sha256', commands[-1])
                state = json.loads((run_dir / 'state.json').read_text())
                self.assertEqual(state['remote']['status'], 'failed')
                self.assertEqual(state['remote']['pending'], state['remote']['failed'])
                self.assertIsNone(state['remote']['active'])
                self.assertTrue((run_dir / 'bundle/manifest.json').is_file())

    def test_rollback_failure_keeps_unverified_active_unknown(self):
        with TemporaryDirectory() as root:
            run_dir, destination = prepared(Path(root))
            source = next(x for x in json.loads((run_dir / 'bundle/manifest.json').read_text())['sources'] if x['id'] == 'source.pdf')
            def command(argv):
                if '--rollback-candidate-sha256' in argv[-1]:
                    raise RuntimeError('stand-in rollback failure')
                return 'activated'
            remote = PrivateEvidenceRemote('safe-host', destination, Path(__file__).resolve().parents[1] / 'scripts/private_evidence_transfer.py', ssh_standin(Path(root)), Path('/opt/code'), Path('/opt/code/deploy/owner_evidence_activate.py'), 'https://poc.alphacompose.com/owner-evidence', 'aaa.bbb.ccc', 'a' * 64, source['sha256'], command=command, http=lambda path, headers, timeout: (403, 'application/json', {}))
            with self.assertRaisesRegex(RuntimeError, 'remote rollback failed'):
                present(run_dir, remote, publisher_requests_stopped=True, vps_reachable=lambda: True)
            state = json.loads((run_dir / 'state.json').read_text())['remote']
            self.assertIsNone(state['active'])
            self.assertIn('rollback', state['rollback_error'])

    def test_interrupted_activation_retries_staged_bytes(self):
        with TemporaryDirectory() as root:
            run_dir, destination = prepared(Path(root))
            local = json.loads((run_dir / 'state.json').read_text())['local']
            source = next(x for x in json.loads((run_dir / 'bundle/manifest.json').read_text())['sources'] if x['id'] == 'source.pdf')
            interrupted = [True]
            def command(argv):
                if interrupted[0]:
                    interrupted[0] = False
                    raise KeyboardInterrupt()
                return 'activated'
            def http(path, headers, timeout):
                return (200, 'application/json', {'snapshot_sha256': local['snapshot_sha256'], 'bundle_manifest_sha256': local['bundle_manifest_sha256']}) if path == '/api/overview' else (200, 'image/png', b'\x89PNG\r\n\x1a\npage') if path.endswith('/page/1') else (200, 'application/json', {'snapshot_sha256': local['snapshot_sha256'], 'source_sha256': source['sha256'], 'source_bytes': source['bytes'], 'format': 'pdf', 'page': 1, 'citation': {'page': 1}})
            remote = PrivateEvidenceRemote('safe-host', destination, Path(__file__).resolve().parents[1] / 'scripts/private_evidence_transfer.py', ssh_standin(Path(root)), Path('/opt/code'), Path('/opt/code/deploy/owner_evidence_activate.py'), 'https://poc.alphacompose.com/owner-evidence', 'aaa.bbb.ccc', 'a' * 64, source['sha256'], command=command, http=http)
            with self.assertRaises(KeyboardInterrupt):
                present(run_dir, remote, publisher_requests_stopped=True, vps_reachable=lambda: True)
            self.assertEqual(json.loads((run_dir / 'state.json').read_text())['remote']['status'], 'pending')
            self.assertEqual(present(run_dir, remote, publisher_requests_stopped=True, vps_reachable=lambda: True)['status'], 'active')

    def test_missing_owner_identity_is_rejected_before_network(self):
        with self.assertRaisesRegex(ValueError, 'owner Access identity session required'):
            PrivateEvidenceRemote('safe-host', Path('/private'), Path('/remote/ssh.py'), Path('/usr/bin/ssh'), Path('/opt/code'), Path('/opt/code/deploy/owner_evidence_activate.py'), 'https://poc.alphacompose.com/owner-evidence', '', 'a' * 64, 'b' * 64)
