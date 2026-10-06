"""Synthetic runtime boundaries; no retained athletes or sporting approvals."""
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('comparison_reader', ROOT / 'scripts/private_attempt_inspector.py')
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
package_spec = importlib.util.spec_from_file_location('comparison_package', ROOT / 'deploy/comparison_runtime.py')
package = importlib.util.module_from_spec(package_spec)
package_spec.loader.exec_module(package)


class ReaderBoundaryTest(unittest.TestCase):
    def test_absent_configuration_is_disabled(self):
        self.assertIsNone(reader.create_reader({}))

    def test_world_readable_or_symlink_configuration_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            path.write_text('{}')
            path.chmod(0o644)
            with self.assertRaisesRegex(ValueError, 'permissions'):
                reader.create_reader({'OWNER_EVIDENCE_COMPARISON_CONFIG': str(path)})
            path.chmod(0o600)
            link = Path(directory) / 'link'
            link.symlink_to(path)
            with self.assertRaisesRegex(ValueError, 'permissions'):
                reader.create_reader({'OWNER_EVIDENCE_COMPARISON_CONFIG': str(link)})


class PackagedComparisonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name).resolve()
        cls.runtime = package.build_runtime(ROOT, cls.root / 'package', 'isolated-synthetic-test-only')
        expression = ('(require (quote freediving.private-attempt-inspector-test)) '
                      '(prn freediving.private-attempt-inspector-test/packet) '
                      '(prn (:rows (freediving.private-attempt-inspector-test/synthetic-authority)))')
        bodies = subprocess.check_output(['clojure', '-Sdeps', '{:paths ["src" "resources" "test"]}',
                                           '-M', '-e', expression], cwd=ROOT, text=True).splitlines()
        cls.packet = bodies[0].encode()
        cls.claim_rows = bodies[1]

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=self.root)
        self.path = Path(self.directory.name)
        packet = self.path / 'packet.edn'
        packet.write_bytes(self.packet)
        packet.chmod(0o600)
        self.packet_pin = {'path': str(packet), 'sha256': hashlib.sha256(self.packet).hexdigest()}
        self.config = {**self.runtime, 'packet': self.packet_pin, 'authority_evidence': None}
        self.config_path = self.path / 'config.json'
        self.current = {'synthetic': 'isolated current live status stand-in', 'revision': 1}

    def tearDown(self):
        self.directory.cleanup()

    def inspector(self):
        self.config_path.write_text(json.dumps(self.config))
        self.config_path.chmod(0o600)
        return reader.create_reader({'OWNER_EVIDENCE_COMPARISON_CONFIG': str(self.config_path)},
                                    clock=lambda: datetime(2026, 10, 6, tzinfo=timezone.utc))

    def evidence(self, expires='2026-10-07T00:00:00Z'):
        path = self.path / 'authority.json'
        path.write_text(json.dumps({'schema': 'private-comparison-authority/v1',
                         'packet_sha256': self.packet_pin['sha256'],
                         'authority_sha256': reader._digest(self.current), 'valid_until': expires,
                         'rows_edn': self.claim_rows}))
        path.chmod(0o600)
        self.config['authority_evidence'] = {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        return path

    def test_packaged_contracts_withhold_then_rank_then_clear_on_live_authority_change(self):
        inspect = self.inspector()
        self.assertEqual(inspect({}, self.current)['coverage']['withheld'], 2)
        self.evidence()
        inspect = self.inspector()
        accepted = inspect({}, self.current)
        self.assertEqual([row.get('rank') for row in accepted['rows']], [1, 2])
        changed = inspect({}, {**self.current, 'revision': 2})
        self.assertEqual(changed['authority']['status'], 'stale')
        self.assertTrue(all('rank' not in row for row in changed['rows']))
        absent = inspect({}, None)
        self.assertTrue(all('rank' not in row for row in absent['rows']))

    def test_expired_or_tampered_evidence_fails_closed(self):
        evidence = self.evidence('2026-10-05T00:00:00Z')
        inspect = self.inspector()
        self.assertEqual(inspect({}, self.current)['authority']['status'], 'stale')
        evidence.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'changed'):
            inspect({}, self.current)

    def test_packet_tamper_is_rejected_on_next_read(self):
        inspect = self.inspector()
        inspect({}, self.current)
        Path(self.packet_pin['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'changed'):
            inspect({}, self.current)

    def test_pinned_cmas_pdf_exact_row_access_and_aida_original_refusal(self):
        pdf = b'%PDF-1.4\nSynthetic isolated PDF bytes only\n'
        digest = hashlib.sha256(pdf).hexdigest()
        packet_path = Path(self.packet_pin['path'])
        packet_path.write_bytes(self.packet.replace(b'a' * 64, digest.encode()))
        self.config['packet'] = {'path': str(packet_path), 'sha256': hashlib.sha256(packet_path.read_bytes()).hexdigest()}
        source = self.path / 'source.pdf'
        source.write_bytes(pdf)
        source.chmod(0o600)
        self.config['source_objects'] = {digest: {'path': str(source), 'sha256': digest,
                                                'mime_type': 'application/pdf'}}
        inspect = self.inspector()
        rows = inspect({}, None)['rows']
        cmas = next(row for row in rows if row['federation'] == 'CMAS')
        aida = next(row for row in rows if row['federation'] == 'AIDA')
        self.assertEqual(inspect.source_bytes(cmas), pdf)
        with self.assertRaisesRegex(ValueError, 'restricted'):
            inspect.source_bytes(aida)
        with self.assertRaisesRegex(ValueError, 'binding'):
            inspect.source_bytes({**cmas, 'row_coordinate': {'page': 99}})
        source.write_bytes(pdf + b'tampered')
        with self.assertRaisesRegex(ValueError, 'changed'):
            inspect.source_bytes(cmas)


if __name__ == '__main__':
    unittest.main()
