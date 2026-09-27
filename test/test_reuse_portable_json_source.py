import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "scripts/reuse_portable_json_source.py"
PORTABLE = ROOT / "scripts/portable_corpus.py"
DISCOVERY = "https://www.cmas.org/freediving-events/2025-cmas-world-championship-freediving-indoor.html"
VIEW = "https://results.microplustimingservices.com/CMAS/Results/#/2/dynamic-result-json/MAM/011/007/001"
RESPONSE = "https://results.microplustimingservices.com/CMAS/ExportPOST/export/CMAS_2/TFMAM011CLAS07%20001.JSON"


class ReusePortableJsonSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.archive = self.root / "archive"
        self.source_bytes = b'{"CGR1":{"rows":[{"PlaName":"Ada"}]}}'
        self.sha = hashlib.sha256(self.source_bytes).hexdigest()
        self.source = self.root / "source.json"
        self.source.write_bytes(self.source_bytes)
        self.bundle = self.root / "bundle"
        self.export_dir = self.root / "export"

    def make_bundle(self, *, view=VIEW, response=RESPONSE, mime="application/json",
                    method="Browser response archive", archive_path="archive",
                    publisher="CMAS", publisher_url="https://www.cmas.org/"):
        manifest = self.root / "manifest.edn"
        manifest.write_text(
            '{:sha256 "' + self.sha + '" :discovery-url "' + DISCOVERY + '" '
            ':final-url "' + response + '" :acquisition-method "' + method + '" '
            ':retrieved-at "2026-09-25T14:30:42Z" :content-type "' + mime + '" '
            ':publisher "' + publisher + '" :relationship :publisher :mirror-of nil '
            ':provenance {:publisher-url "' + publisher_url + '" :source-page-url "' + view + '" '
            ':redirect-chain ["' + response + '"]}}')
        imported = subprocess.run(["clojure", "-M:archive", "import", str(self.archive),
                                   str(self.source), str(manifest)], cwd=ROOT,
                                  capture_output=True, text=True)
        self.assertEqual(0, imported.returncode, imported.stderr)
        self.acquisition = re.search(r':acquisition-id "([0-9a-f]{64})"', imported.stdout).group(1)
        review = self.root / "private-review.txt"
        review.write_text("private review")
        spec = self.root / "spec.json"
        spec.write_text(json.dumps({"schema": "portable-corpus-spec/v1", "corpus": "fixture",
                                    "entries": [{"source": str(self.archive), "path": archive_path, "role": "source"},
                                                {"source": str(review), "path": "review/private-review.txt",
                                                 "role": "review"}],
                                    "archive_roots": [archive_path]}))
        bundled = subprocess.run([sys.executable, str(PORTABLE), "export", str(spec), str(self.bundle)],
                                 capture_output=True, text=True)
        self.assertEqual(0, bundled.returncode, bundled.stderr)

    def run_cli(self, expected=0):
        result = subprocess.run([sys.executable, str(CLI), str(self.bundle), self.sha,
                                 self.acquisition, str(self.export_dir)], capture_output=True, text=True)
        self.assertEqual(expected, result.returncode, result.stderr)
        return result

    def test_exports_exact_json_and_sanitized_original_acquisition(self):
        self.make_bundle()
        self.run_cli()
        self.assertEqual({"source.json", "manifest.edn", "provenance.json"},
                         {path.name for path in self.export_dir.iterdir()})
        self.assertEqual(self.source_bytes, (self.export_dir / "source.json").read_bytes())
        provenance = json.loads((self.export_dir / "provenance.json").read_text())
        self.assertEqual(self.sha, provenance["source_sha256"])
        self.assertEqual(self.acquisition, provenance["original_acquisition_id"])
        self.assertEqual(DISCOVERY, provenance["original_retrieval"]["discovery_url"])
        self.assertEqual(VIEW, provenance["original_retrieval"]["source_page_url"])
        self.assertEqual(RESPONSE, provenance["original_retrieval"]["final_url"])
        self.assertEqual("reuse-of-original-source", provenance["transfer_kind"])
        self.assertFalse(provenance["original_retrieval"]["http_evidence_complete"])
        self.assertNotIn("private review", (self.export_dir / "provenance.json").read_text())
        self.assertEqual(0o600, (self.export_dir / "source.json").stat().st_mode & 0o777)

    def test_rejects_non_json_and_unrelated_result_page(self):
        self.make_bundle(mime="application/pdf")
        self.run_cli(expected=1)
        self.assertFalse(self.export_dir.exists())

    def test_rejects_mismatched_result_page_and_response_family(self):
        self.make_bundle(view=VIEW.replace("#/2/", "#/1/"))
        self.run_cli(expected=1)
        self.assertFalse(self.export_dir.exists())

    def test_rejects_tampered_bundle(self):
        self.make_bundle()
        record = self.bundle / "payload/archive/acquisitions" / (self.acquisition + ".edn")
        record.write_text(record.read_text().replace(self.sha, "0" * 64))
        self.run_cli(expected=1)
        self.assertFalse(self.export_dir.exists())

    def test_rejects_sensitive_acquisition_method(self):
        self.make_bundle(method="Browser response token=private-value")
        self.run_cli(expected=1)
        self.assertFalse(self.export_dir.exists())

    def test_exports_b32_prefixed_archive_with_cmas_microplus_publisher(self):
        self.make_bundle(archive_path="athens/archive", publisher="CMAS / Microplus",
                         publisher_url=DISCOVERY)
        self.run_cli()
        self.assertEqual(self.source_bytes, (self.export_dir / "source.json").read_bytes())
        provenance = json.loads((self.export_dir / "provenance.json").read_text())
        self.assertEqual("CMAS / Microplus", provenance["original_retrieval"]["publisher"])
        self.assertEqual(DISCOVERY, provenance["original_retrieval"]["publisher_url"])


if __name__ == "__main__":
    unittest.main()
