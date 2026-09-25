import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CLI = Path(__file__).resolve().parents[1] / "scripts" / "portable_corpus.py"


def digest(data):
    return hashlib.sha256(data).hexdigest()


class PortableCorpusTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "source.json").write_bytes(b'{"result": 1}\n')
        (self.source / "receipt.json").write_text(json.dumps({"source": {"path": "source.json", "sha256": digest(b'{"result": 1}\n')}}))
        self.spec = self.root / "spec.json"
        self.bundle = self.root / "bundle"
        self.target = self.root / "restored"

    def run_cli(self, *args, ok=True):
        result = subprocess.run([sys.executable, str(CLI), *map(str, args)], capture_output=True, text=True)
        if ok:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
        return result

    def write_spec(self, **extra):
        spec = {"schema": "portable-corpus-spec/v1", "corpus": "test-corpus", "entries": [
            {"source": str(self.source), "path": "evidence", "role": "source-and-review"}],
            "references": [{"path": "evidence/source.json", "sha256": digest(b'{"result": 1}\n')}],
            **extra}
        self.spec.write_text(json.dumps(spec))

    def test_export_verify_restore_preserves_bytes_and_rejects_tampering(self):
        self.write_spec()
        self.run_cli("export", self.spec, self.bundle)
        self.run_cli("verify", self.bundle)
        self.run_cli("restore", self.bundle, self.target)
        self.assertEqual((self.source / "source.json").read_bytes(), (self.target / "evidence/source.json").read_bytes())
        self.assertEqual((self.source / "receipt.json").read_bytes(), (self.target / "evidence/receipt.json").read_bytes())
        (self.bundle / "payload/evidence/source.json").write_bytes(b"tampered")
        self.run_cli("verify", self.bundle, ok=False)

    def test_missing_file_and_reference_fail(self):
        self.write_spec(references=[{"path": "evidence/absent", "sha256": "0" * 64}])
        self.run_cli("export", self.spec, self.bundle, ok=False)
        self.write_spec()
        self.run_cli("export", self.spec, self.bundle)
        (self.bundle / "payload/evidence/source.json").unlink()
        self.run_cli("verify", self.bundle, ok=False)

    def test_rejects_traversal_and_symlinks(self):
        self.write_spec(entries=[{"source": str(self.source / "source.json"), "path": "../outside", "role": "source"}])
        self.run_cli("export", self.spec, self.bundle, ok=False)
        (self.source / "link").symlink_to(self.source / "source.json")
        self.write_spec()
        self.run_cli("export", self.spec, self.bundle, ok=False)

    def test_archive_reference_must_resolve_to_hash_matched_object(self):
        archive = self.root / "archive"
        (archive / "acquisitions").mkdir(parents=True)
        (archive / "objects").mkdir()
        sha = digest(b"source bytes")
        (archive / "acquisitions/a.edn").write_text('{:sha256 "' + sha + '"}')
        self.write_spec(entries=[{"source": str(archive), "path": "archive", "role": "acquisition-archive"}],
                        references=[], archive_roots=["archive"])
        self.run_cli("export", self.spec, self.bundle, ok=False)
        (archive / "objects" / sha).write_bytes(b"source bytes")
        self.run_cli("export", self.spec, self.bundle)
        self.run_cli("verify", self.bundle)

    def test_receipt_references_and_index_paths_are_verified(self):
        self.write_spec(receipt_paths=["evidence/receipt.json"])
        self.run_cli("export", self.spec, self.bundle)
        self.run_cli("verify", self.bundle)
        index_path = self.bundle / "index.json"
        index = json.loads(index_path.read_text())
        index["files"]["../escape"] = index["files"].pop("evidence/source.json")
        index_path.write_text(json.dumps(index))
        self.run_cli("verify", self.bundle, ok=False)

    def test_system_tmp_alias_can_hold_a_relocated_bundle(self):
        if not Path("/tmp").is_symlink():
            self.skipTest("system /tmp alias is macOS-specific")
        self.write_spec()
        with tempfile.TemporaryDirectory(dir="/tmp") as destination:
            bundle = Path(destination) / "bundle"
            self.run_cli("export", self.spec, bundle)
            self.run_cli("verify", bundle)


if __name__ == "__main__":
    unittest.main()
