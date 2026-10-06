"""Synthetic runtime boundaries; no retained athletes or sporting approvals."""
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import shutil
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from unittest.mock import patch

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
        for inspect in getattr(self, 'readers', []):
            if hasattr(inspect, 'close'):
                inspect.close()
        self.directory.cleanup()

    def inspector(self):
        self.config_path.write_text(json.dumps(self.config))
        self.config_path.chmod(0o600)
        inspect = reader.create_reader({'OWNER_EVIDENCE_COMPARISON_CONFIG': str(self.config_path)},
                                       clock=lambda: getattr(self, 'now', datetime(2026, 10, 6, tzinfo=timezone.utc)))
        self.readers = getattr(self, 'readers', []) + [inspect]
        return inspect

    def test_warm_packaged_reads_finish_inside_browser_remaining_budget(self):
        inspect = self.inspector()
        inspect({}, None)
        for _ in range(3):
            result = inspect({}, None, deadline=time.monotonic() + 0.25)
            self.assertEqual(result['coverage']['withheld'], 2)

    def altered_runtime(self, program):
        """Actual isolated JVM code for slow/malformed subprocess boundaries."""
        runtime = self.path / 'runtime'
        if not runtime.exists():
            shutil.copytree(self.config['runtime_path'], runtime)
        source = runtime / 'src/freediving/private_attempt_inspector.clj'
        source.write_text(source.read_text() + '\n' + program + '\n')
        manifest = runtime / 'manifest.json'
        data = json.loads(manifest.read_text())
        data['files']['src/freediving/private_attempt_inspector.clj'] = hashlib.sha256(source.read_bytes()).hexdigest()
        manifest.write_text(json.dumps(data))
        self.config.update(runtime_path=str(runtime),
                           runtime_manifest_sha256=hashlib.sha256(manifest.read_bytes()).hexdigest())

    def test_coherent_runtime_generation_change_at_same_path_restarts_warm_jvm(self):
        self.altered_runtime('')
        inspect = self.inspector()
        self.assertNotIn('runtime_generation', inspect({}, None))
        self.altered_runtime('(def original-inspect inspect) (defn inspect [packet filters authority] (assoc (original-inspect packet filters authority) :runtime_generation "changed"))')
        self.config_path.write_text(json.dumps(self.config))
        self.assertEqual(inspect({}, None)['runtime_generation'], 'changed')

    def test_slow_actual_jvm_times_out_is_reaped_and_retry_uses_fresh_process(self):
        self.altered_runtime('(def original-main -main) (defn -main [& args] (Thread/sleep 5000) (apply original-main args))')
        inspect = self.inspector()
        processes = []
        launch = subprocess.Popen
        def record(*args, **kwargs):
            process = launch(*args, **kwargs)
            processes.append(process)
            return process
        with patch.object(reader.subprocess, 'Popen', side_effect=record):
            for _ in range(2):
                started = time.monotonic()
                with self.assertRaisesRegex(ValueError, 'deadline'):
                    inspect({}, None, deadline=started + 0.15)
                self.assertLess(time.monotonic() - started, 0.8)
                self.assertTrue(all(p.poll() is not None for p in processes))
        self.assertEqual(len(processes), 2)

    def test_bounded_parallel_reads_reject_overload_and_close_cancels_waiters(self):
        self.altered_runtime('(def original-main -main) (defn -main [& args] (Thread/sleep 5000) (apply original-main args))')
        inspect = self.inspector()
        launched = threading.Event()
        processes = []
        launch = subprocess.Popen
        def record(*args, **kwargs):
            process = launch(*args, **kwargs)
            processes.append(process)
            launched.set()
            return process
        def attempt():
            try:
                inspect({}, None)
            except (ValueError, OSError) as exc:
                return str(exc)
        with patch.object(reader.subprocess, 'Popen', side_effect=record), ThreadPoolExecutor(max_workers=8) as executor:
            first = executor.submit(attempt)
            self.assertTrue(launched.wait(2))
            contenders = [executor.submit(attempt) for _ in range(7)]
            time.sleep(0.1)
            completed = [f.result() for f in contenders if f.done()]
            self.assertGreaterEqual(sum('busy' in result for result in completed), 5)
            inspect.close()
            results = [f.result(timeout=2) for f in [first, *contenders]]
            self.assertTrue(all(results))
        self.assertEqual(len(processes), 1)
        self.assertTrue(all(p.poll() is not None for p in processes))
        outcomes = {event['outcome'] for event in inspect.diagnostics()}
        self.assertTrue({'overload', 'cancelled'} <= outcomes)
        self.assertTrue(any(event['queue_ms'] > 50 for event in inspect.diagnostics()))
        with self.assertRaisesRegex(ValueError, 'closed'):
            inspect({}, None)

    def test_three_admitted_real_jvm_reads_share_startup_and_finish_within_deadline(self):
        inspect = self.inspector()
        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = [executor.submit(inspect, {}, None, deadline=time.monotonic() + 3) for _ in range(3)]
            results = [future.result(timeout=3) for future in futures]
        self.assertTrue(all(result['coverage']['withheld'] == 2 for result in results))
        timings = inspect.diagnostics()
        self.assertEqual(len(timings), 3)
        self.assertTrue(all(event['outcome'] == 'success' for event in timings))
        self.assertTrue(any(event['queue_ms'] > 100 for event in timings))

    def test_malformed_actual_jvm_protocol_is_reaped(self):
        self.altered_runtime('(defn -main [& _] (println "not-json") (flush) (Thread/sleep 5000))')
        inspect = self.inspector()
        with self.assertRaises(ValueError):
            inspect({}, None)

    def test_oversized_actual_jvm_response_is_bounded_and_reaped(self):
        self.altered_runtime('(defn -main [& _] (println (json/write-str {:schema "private-attempt-inspector/v1" :padding (apply str (repeat (* 5 1024 1024) "x"))})) (flush) (Thread/sleep 5000))')
        inspect = self.inspector()
        with self.assertRaisesRegex(ValueError, 'exceeds bound'):
            inspect({}, None)

    def test_publication_check_detects_expiry_without_reusing_authority(self):
        self.evidence()
        inspect = self.inspector()
        initial = inspect.verify_current(self.current)
        self.assertNotEqual(initial, inspect.verify_current({**self.current, 'revision': 2}))
        self.evidence('2026-10-05T00:00:00Z')
        inspect = self.inspector()
        self.assertNotEqual(initial, inspect.verify_current(self.current))

    def test_actual_jvm_read_rejects_packet_change_during_execution(self):
        self.altered_runtime('(def original-main -main) (defn -main [& args] (Thread/sleep 400) (apply original-main args))')
        inspect = self.inspector()
        launched = threading.Event()
        launch = subprocess.Popen
        def record(*args, **kwargs):
            process = launch(*args, **kwargs)
            launched.set()
            return process
        with patch.object(reader.subprocess, 'Popen', side_effect=record), ThreadPoolExecutor(max_workers=1) as executor:
            result = executor.submit(inspect, {}, None)
            self.assertTrue(launched.wait(2))
            Path(self.packet_pin['path']).write_text('{}')
            with self.assertRaisesRegex(ValueError, 'changed'):
                result.result(timeout=3)

    def test_actual_jvm_read_rejects_authority_expiry_during_execution(self):
        self.altered_runtime('(def original-main -main) (defn -main [& args] (Thread/sleep 400) (apply original-main args))')
        self.evidence()
        inspect = self.inspector()
        launched = threading.Event()
        launch = subprocess.Popen
        def record(*args, **kwargs):
            process = launch(*args, **kwargs)
            launched.set()
            return process
        with patch.object(reader.subprocess, 'Popen', side_effect=record), ThreadPoolExecutor(max_workers=1) as executor:
            result = executor.submit(inspect, {}, self.current)
            self.assertTrue(launched.wait(2))
            self.now = datetime(2026, 10, 8, tzinfo=timezone.utc)
            with self.assertRaisesRegex(ValueError, 'changed'):
                result.result(timeout=3)

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
