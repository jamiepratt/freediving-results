import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "scripts/reuse_portable_source.py"
PORTABLE = ROOT / "scripts/portable_corpus.py"


class ReusePortableSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.archive = self.root / "archive"
        self.pdf = b"%PDF-1.4\nsource bytes\n"
        self.sha = hashlib.sha256(self.pdf).hexdigest()
        source = self.root / "source.pdf"
        source.write_bytes(self.pdf)
        self.manifest = ('{:sha256 "' + self.sha + '" :discovery-url "https://www.cmas.org/detail" '
                         ':final-url "https://www.cmas.org/viewdocument/1237.html" '
                         ':acquisition-method "Chrome View download" :retrieved-at "2026-09-25T14:30:42Z" '
                         ':content-type "application/pdf" :publisher "CMAS" :relationship :publisher '
                         ':mirror-of nil :provenance {:publisher-url "https://www.cmas.org/" '
                         ':redirect-chain ["https://www.cmas.org/viewdocument/1237.html"]}}')
        manifest_path = self.root / "manifest.edn"
        manifest_path.write_text(self.manifest)
        result = subprocess.run(["clojure", "-M:archive", "import", str(self.archive), str(source),
                                 str(manifest_path)], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.acquisition = re.search(r':acquisition-id "([0-9a-f]{64})"', result.stdout).group(1)
        # Reviews may be bundled, but the reuse export must never copy them.
        (self.root / "private-review.txt").write_text("private review")
        self.bundle = self.root / "bundle"
        self.export_dir = self.root / "export"

    def make_bundle(self):
        spec = self.root / "spec.json"
        spec.write_text(json.dumps({"schema": "portable-corpus-spec/v1", "corpus": "fixture",
                                    "entries": [{"source": str(self.archive), "path": "archive", "role": "source"},
                                                {"source": str(self.root / "private-review.txt"),
                                                 "path": "review/private-review.txt", "role": "review"}],
                                    "archive_roots": ["archive"]}))
        result = subprocess.run([sys.executable, str(PORTABLE), "export", str(spec), str(self.bundle)],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def run_cli(self, ok=True):
        result = subprocess.run([sys.executable, str(CLI), str(self.bundle), self.sha,
                                 self.acquisition, str(self.export_dir)], capture_output=True, text=True)
        self.assertEqual(0 if ok else 1, result.returncode, result.stderr)
        return result

    def test_exports_only_verified_pdf_and_sanitized_original_provenance(self):
        self.make_bundle()
        self.run_cli()
        self.assertEqual({"source.pdf", "manifest.edn", "provenance.json"},
                         {p.name for p in self.export_dir.iterdir()})
        self.assertEqual(self.pdf, (self.export_dir / "source.pdf").read_bytes())
        provenance = json.loads((self.export_dir / "provenance.json").read_text())
        self.assertEqual(self.sha, provenance["source_sha256"])
        self.assertEqual(self.acquisition, provenance["original_acquisition_id"])
        self.assertEqual("Chrome View download", provenance["original_retrieval"]["method"])
        self.assertEqual("2026-09-25T14:30:42Z", provenance["original_retrieval"]["at"])
        self.assertEqual("https://www.cmas.org/detail", provenance["original_retrieval"]["discovery_url"])
        self.assertEqual(["https://www.cmas.org/viewdocument/1237.html"],
                         provenance["original_retrieval"]["redirect_chain"])
        self.assertFalse(provenance["original_retrieval"]["http_evidence_complete"])
        self.assertEqual("reuse-of-original-source", provenance["transfer_kind"])
        self.assertNotIn("private review", (self.export_dir / "provenance.json").read_text())
        self.assertEqual(0o600, (self.export_dir / "source.pdf").stat().st_mode & 0o777)

    def test_rejects_mismatched_record_and_tampered_bundle_without_export(self):
        self.make_bundle()
        record = self.bundle / "payload/archive/acquisitions" / (self.acquisition + ".edn")
        record.write_text(record.read_text().replace(self.sha, "0" * 64))
        self.run_cli(ok=False)
        self.assertFalse(self.export_dir.exists())

    def test_rejects_wrong_expected_hash_and_acquisition(self):
        self.make_bundle()
        self.sha = "0" * 64
        self.run_cli(ok=False)
        self.assertFalse(self.export_dir.exists())

    def test_rejects_sensitive_values_in_free_text_acquisition_method(self):
        source = self.root / "source.pdf"
        manifest = self.root / "sensitive.edn"
        manifest.write_text(self.manifest.replace("Chrome View download", "Chrome View token=private-value"))
        result = subprocess.run(["clojure", "-M:archive", "import", str(self.archive), str(source),
                                 str(manifest)], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.acquisition = re.search(r':acquisition-id "([0-9a-f]{64})"', result.stdout).group(1)
        self.make_bundle()
        self.run_cli(ok=False)
        self.assertFalse(self.export_dir.exists())


if __name__ == "__main__":
    unittest.main()
