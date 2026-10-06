"""A release packages only clean committed source and reports immutable byte pins."""
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
import sporting_proof_runtime


class SportingProofRuntimeTests(unittest.TestCase):
    def test_real_reader_cli_packages_without_pythonpath_or_import_artifacts(self):
        source=Path(os.environ.get('SPORTING_PROOF_TEST_SOURCE',str(Path(__file__).resolve().parents[1])))
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();repo=root/'repo';repo.mkdir()
            shutil.copytree(source/'src',repo/'src')
            shutil.copytree(source/'scripts',repo/'scripts',ignore=shutil.ignore_patterns('__pycache__'))
            shutil.copyfile(source/'deps.edn',repo/'deps.edn')
            for args in (('init','-q'),('config','user.name','Test'),('config','user.email','test@example.invalid'),
                         ('add','.'),('commit','-qm','real proof module package fixture')):
                subprocess.run(['git','-C',str(repo),*args],check=True,capture_output=True)
            candidate=subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()
            output=root/'runtime'
            env={k:v for k,v in os.environ.items() if k!='PYTHONPATH'}
            env['PYTHONDONTWRITEBYTECODE']='1'
            result=subprocess.run([sys.executable,'-I',str(Path(sporting_proof_runtime.__file__)),
                '--repo',str(repo),'--candidate',candidate,'--output',str(output)],
                cwd=root,env=env,text=True,capture_output=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stderr)
            manifest=json.loads((output/'manifest.json').read_text())
            self.assertEqual(len(manifest['files']),21)
            self.assertIn('src/freediving/private_sporting_proofs.clj',manifest['files'])
            self.assertIn('lib/jsoup-1.21.2.jar',manifest['files'])
            self.assertEqual(subprocess.check_output(['git','-C',str(repo),'status','--porcelain']).strip(),b'')
            self.assertEqual(list(repo.rglob('__pycache__')),[])

    def test_host_stage_verifies_hashes_and_is_idempotent_without_live_config_changes(self):
        import os
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();source=root/'source';source.mkdir()
            file=source/'src/freediving/proof.clj';file.parent.mkdir(parents=True);file.write_text('(ns freediving.proof)')
            manifest=source/'manifest.json';manifest.write_text(json.dumps({'candidate':'a'*40,'files':{'src/freediving/proof.clj':hashlib.sha256(file.read_bytes()).hexdigest()}}))
            digest=hashlib.sha256(manifest.read_bytes()).hexdigest()
            state=root/'state';state.mkdir()
            installed=sporting_proof_runtime.stage(source,digest,state,os.getuid(),os.getgid())
            self.assertEqual(installed.name,digest)
            self.assertEqual((installed/'src/freediving/proof.clj').read_bytes(),file.read_bytes())
            self.assertEqual(installed.stat().st_mode&0o777,0o755)
            self.assertEqual((installed/'manifest.json').stat().st_mode&0o777,0o644)
            self.assertEqual(sporting_proof_runtime.stage(source,digest,state,os.getuid(),os.getgid()),installed)
            file.write_text('tampered')
            with self.assertRaises(ValueError):sporting_proof_runtime.stage(source,digest,state,os.getuid(),os.getgid())
            self.assertFalse((state/'active.env').exists())

    def test_release_pins_committed_code_and_refuses_dirty_or_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root/'repo';repo.mkdir()
            for args in (('init','-q'),('config','user.name','Test'),('config','user.email','test@example.invalid')):
                subprocess.run(['git','-C',str(repo),*args],check=True,capture_output=True)
            source=repo/'src/freediving/proof.clj';source.parent.mkdir(parents=True);source.write_text('(ns freediving.proof)\n')
            reader=repo/'scripts/private_sporting_proofs.py';reader.parent.mkdir()
            reader.write_text("JARS=();RUNTIME_FILES=frozenset(['src/freediving/proof.clj'])\n")
            subprocess.run(['git','-C',str(repo),'add','.'],check=True,capture_output=True)
            subprocess.run(['git','-C',str(repo),'commit','-qm','synthetic proof reader'],check=True,capture_output=True)
            candidate=subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()
            output=root/'runtime'
            digest=sporting_proof_runtime.prepare(repo,output,candidate)
            self.assertEqual(digest,hashlib.sha256((output/'manifest.json').read_bytes()).hexdigest())
            self.assertEqual((output/'src/freediving/proof.clj').read_bytes(),source.read_bytes())
            self.assertEqual(json.loads((output/'manifest.json').read_text())['candidate'],candidate)
            self.assertEqual((output/'manifest.json').stat().st_mode&0o777,0o600)
            with self.assertRaises(ValueError):sporting_proof_runtime.prepare(repo,output,candidate)
            source.write_text('(ns changed)\n')
            with self.assertRaisesRegex(ValueError,'exact clean committed'):
                sporting_proof_runtime.prepare(repo,root/'other-runtime',candidate)
            self.assertFalse((root/'other-runtime').exists())


if __name__=='__main__':unittest.main()
