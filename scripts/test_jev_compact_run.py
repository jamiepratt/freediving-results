import importlib.util
import json
import os
import hashlib
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('runner', Path(__file__).with_name('jev_compact_run.py'))

class DurableLaunch(unittest.TestCase):
    def test_missing_credential_can_launch_after_correction(self):
        runner = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(runner)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            manifest = {'root': str(root), 'cwd': str(root), 'packet': str(root), 'original_packet': str(root), 'expected_run_id': '0' * 64, 'max_http': 11}
            (root / 'manifest.json').write_text(json.dumps(manifest))
            with patch.object(runner, 'validate', return_value=manifest), patch.object(runner, 'run_clojure'), patch.object(runner, 'credential', side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    runner.main(['live', '--manifest', str(root / 'manifest.json')])
            self.assertFalse((root / 'dispatcher-started.json').exists())
            self.assertFalse((root / 'store').exists())
            with patch.object(runner, 'validate', return_value=manifest), patch.object(runner, 'credential', return_value='corrected-key'), patch.object(runner, 'run_clojure', return_value=0) as clj:
                self.assertEqual(0, runner.main(['live', '--manifest', str(root / 'manifest.json')]))
                self.assertTrue((root / 'dispatcher-started.json').is_file())
                clj.assert_any_call(manifest, 'live', 'corrected-key')
            with patch.object(runner, 'validate', return_value=manifest), patch.object(runner, 'credential') as cred, patch.object(runner, 'run_clojure') as clj:
                with self.assertRaisesRegex(RuntimeError, 'never relaunch'):
                    runner.main(['live', '--manifest', str(root / 'manifest.json')])
                cred.assert_not_called()
                clj.assert_not_called()

class FrozenPacketCheck(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('JEV_COMPACT_TEST_PACKET'), 'private frozen packet unavailable')
    def test_complete_clojure_entrypoint_checks_without_creating_store(self):
        runner = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(runner)
        with tempfile.TemporaryDirectory() as tmp:
            m = {'root': tmp, 'cwd': str(Path(__file__).resolve().parent.parent), 'packet': os.environ['JEV_COMPACT_TEST_PACKET'], 'original_packet': os.environ['JEV_ORIGINAL_TEST_PACKET'], 'expected_run_id': os.environ['JEV_COMPACT_TEST_RUN_ID']}
            self.assertEqual(0, runner.run_clojure(m, 'check'))
            self.assertEqual([], list(Path(tmp).iterdir()))

    @unittest.skipUnless(os.environ.get('JEV_COMPACT_TEST_PACKET'), 'private frozen packet unavailable')
    def test_full_frozen_live_replay_and_terminal_arm_stop_with_fake_provider(self):
        for terminal, expected_calls in ((False, 11), (True, 1)):
            with self.subTest(terminal=terminal), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp).resolve()
                root.chmod(0o700)
                (root / 'dispatcher-started.json').write_text('{}')
                packet = os.environ['JEV_COMPACT_TEST_PACKET']
                form = r'''(require '[jev-compact-runner :as r] '[jev-frozen-runner :as original] '[freediving.evaluation-providers :as p] '[clojure.java.io :as io])
(def calls (atom 0))
(with-redefs [p/execute! (fn [request _]
                         (let [n (swap! calls inc)]
                           (if (and TERMINAL (= n 1))
                             {:outcome :error :error :invalid-response :retryable? false :external-outcome :known :cost {:status :unknown}}
                             {:outcome :match :retryable? false :external-outcome :known :cost {:status :unknown}
                              :answers (into {} (map (fn [q] [q {:outcome :match :confidence 0.8}]) (:question-ids request)))})))]
  (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "fake-token\n"))]
    (r/-main "live" PACKET ROOT ORIGINAL RUNID)))
(assert (= EXPECTED @calls))
(defn inventory [] (into {} (for [f (file-seq (io/file ROOT "store")) :when (.isFile f)] [(.getPath f) (original/sha (slurp f))])))
(def before (inventory))
(with-redefs [p/execute! (fn [& _] (throw (Exception. "Forbidden replay dispatch")))] (r/-main "replay" PACKET ROOT ORIGINAL RUNID))
(assert (= before (inventory)))
(assert (= EXPECTED @calls))
'''.replace('TERMINAL', str(terminal).lower()).replace('EXPECTED', str(expected_calls)).replace('PACKET', json.dumps(packet)).replace('ROOT', json.dumps(str(root))).replace('ORIGINAL', json.dumps(os.environ['JEV_ORIGINAL_TEST_PACKET'])).replace('RUNID', json.dumps(os.environ['JEV_COMPACT_TEST_RUN_ID']))
                env = {**os.environ, 'JAVA_HOME': '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'}
                result = subprocess.run(['clojure', '-Sdeps', '{:paths ["src" "resources" "scripts"]}', '-M', '-e', form], cwd=Path(__file__).resolve().parent.parent, env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                analysis = json.loads((root / 'analysis-input.json').read_text())
                self.assertEqual(81, len(analysis['labels']))
                self.assertEqual({'C8'}, set(analysis['providers']))

class PublicCLI(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('JEV_COMPACT_TEST_PACKET'), 'private frozen packet unavailable')
    def test_full_cli_one_attempt_and_credential_free_replay(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp).resolve()
            root = base / 'output'
            root.mkdir()
            bindir = base / 'bin'
            bindir.mkdir()
            fake = r"""#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
if name == 'security': print('test-service-secret')
elif name == 'op':
    print(json.dumps([{'id':'test','title':'typesafe'}] if sys.argv[2] == 'list' else {'fields':[{'value':'test-provider-secret','type':'CONCEALED'}]}))
else:
    mode, packet, output, original, runid = sys.argv[-5:]
    root = pathlib.Path(output)
    if mode == 'live':
        assert sys.stdin.read() == 'test-provider-secret\n'
        (root / 'dispatch.txt').write_text('one')
        if os.environ.get('FAIL_AFTER_DISPATCH'): sys.exit(9)
        (root / 'result-live.edn').write_text('{}')
    elif mode == 'replay': assert sys.stdin.read() == ''
"""
            for binary in ('clojure', 'security', 'op'):
                path = bindir / binary
                path.write_text(fake)
                path.chmod(0o700)
            env = {**os.environ, 'PATH': str(bindir) + os.pathsep + os.environ['PATH'], 'PYTHONDONTWRITEBYTECODE': '1'}
            cwd = Path(__file__).resolve().parent.parent
            packet = Path(os.environ['JEV_COMPACT_TEST_PACKET'])
            files = [cwd / 'scripts/jev_compact_run.py', cwd / 'scripts/jev_compact_runner.clj', cwd / 'scripts/jev_frozen_runner.clj', cwd / 'scripts/jev_frozen_run.py', cwd / 'deps.edn', Path(sys.executable).resolve(), Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java').resolve()]
            files += [p.resolve() for name in ('src', 'resources') for p in (cwd / name).rglob('*') if p.is_file()]
            files += list(bindir.iterdir())
            files += [packet / p for p in ('candidate-frozen.edn', 'requests.json')]
            original = Path(os.environ['JEV_ORIGINAL_TEST_PACKET'])
            files += [original / p for p in ('REPORT.md', 'candidate-frozen.edn', 'requests.json')]
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'root': str(root), 'cwd': str(cwd), 'packet': str(packet), 'original_packet': str(original), 'expected_run_id': os.environ['JEV_COMPACT_TEST_RUN_ID'], 'max_http': 11, 'files': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}))
            def cli(mode):
                return subprocess.run([sys.executable, str(cwd / 'scripts/jev_compact_run.py'), mode, '--manifest', str(manifest)], env=env, capture_output=True, text=True)
            first = cli('live')
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual('one', (root / 'dispatch.txt').read_text())
            self.assertNotEqual(0, cli('live').returncode)
            self.assertEqual(0, cli('replay').returncode)
            self.assertTrue(json.loads((root / 'secret-scan.json').read_text())['passed'])
            second_root = base / 'interrupted'
            second_root.mkdir()
            content = json.loads(manifest.read_text())
            content['root'] = str(second_root)
            manifest = second_root / 'manifest.json'
            manifest.write_text(json.dumps(content))
            env['FAIL_AFTER_DISPATCH'] = '1'
            self.assertNotEqual(0, cli('live').returncode)
            self.assertTrue((second_root / 'dispatcher-started.json').exists())
            self.assertFalse((second_root / 'dispatcher-completed.json').exists())
            self.assertNotEqual(0, cli('live').returncode)
            self.assertEqual('one', (second_root / 'dispatch.txt').read_text())
            self.assertNotEqual(0, cli('replay').returncode)
            (bindir / 'op').write_text('tampered')
            self.assertNotEqual(0, cli('check').returncode)

if __name__ == '__main__':
    unittest.main()
