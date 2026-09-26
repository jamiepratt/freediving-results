import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from acquire_source import SourceRejected, acquire, import_once
from source_acquisition import AcquisitionClient, Policy
from source_inventory import find_reusable_source
from test_acquire_source import Publisher


class SourceInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.client = AcquisitionClient(default_policy=Policy(min_interval=0, max_attempts=1),
                                        lease_path=self.root / "leases.sqlite3")

    def publisher(self, body=b"%PDF-1.7\noriginal"):
        publisher = Publisher(lambda path: (200, {"Content-Type": "application/pdf"}, body))
        self.addCleanup(publisher.close)
        return publisher

    def test_verified_local_and_remote_evidence_prevents_a_new_request(self):
        publisher = self.publisher()
        remote = self.root / "remote"
        original = acquire(publisher.url, "pdf", remote, client=self.client,
                           context={"event": "synthetic", "version": "results-1"})
        local = self.root / "local"
        reused = acquire(publisher.url, "pdf", local, client=self.client,
                         archive_roots=[remote], context={"event": "synthetic", "version": "results-1"})
        again = acquire(publisher.url, "pdf", local, client=self.client,
                        archive_roots=[remote], context={"event": "synthetic", "version": "results-1"})
        self.assertEqual(1, len(publisher.requests))
        self.assertEqual(original["sha256"], reused["sha256"])
        self.assertEqual("reused", reused["status"])
        self.assertEqual("not_checked", reused["freshness"])
        self.assertEqual(reused["provenance_path"], again["provenance_path"])
        self.assertNotEqual(original["run_path"], reused["run_path"])
        self.assertEqual(Path(original["provenance_path"]).read_bytes(),
                         Path(reused["provenance_path"]).read_bytes())
        self.assertEqual(hashlib.sha256(Path(reused["source_path"]).read_bytes()).hexdigest(), reused["sha256"])
        browser_candidate = find_reusable_source([remote], publisher.url, "pdf",
                                                 {"event": "synthetic", "version": "results-1"})
        self.assertEqual(Path(original["source_path"]).read_bytes(), browser_candidate["body"])
        self.assertIsNone(find_reusable_source([remote], publisher.url, "pdf", {"version": "other"}))

    def test_corrupt_or_incomplete_evidence_is_not_reused(self):
        publisher = self.publisher()
        remote = self.root / "remote"
        original = acquire(publisher.url, "pdf", remote, client=self.client)
        Path(original["source_path"]).write_bytes(b"corrupt")
        acquired = acquire(publisher.url, "pdf", self.root / "local", client=self.client,
                           archive_roots=[remote])
        self.assertEqual("acquired", acquired["status"])
        self.assertEqual(2, len(publisher.requests))

    def test_explicit_refresh_requests_and_retains_separate_acquisition(self):
        publisher = self.publisher()
        first = acquire(publisher.url, "pdf", self.root / "local", client=self.client)
        second = acquire(publisher.url, "pdf", self.root / "local", client=self.client, refresh=True)
        self.assertEqual(2, len(publisher.requests))
        self.assertEqual("refresh_requested", second["freshness"])
        self.assertNotEqual(first["provenance_path"], second["provenance_path"])

    def test_missing_checkpoint_and_interrupted_response_cannot_be_reused(self):
        publisher = self.publisher()
        remote = self.root / "remote"
        original = acquire(publisher.url, "pdf", remote, client=self.client)
        Path(original["run_path"]).unlink()
        acquired = acquire(publisher.url, "pdf", self.root / "local", client=self.client,
                           archive_roots=[remote])
        self.assertEqual("acquired", acquired["status"])
        self.assertEqual(2, len(publisher.requests))

    def test_missing_retrieval_evidence_and_changed_context_force_request(self):
        publisher = self.publisher()
        remote = self.root / "remote"
        original = acquire(publisher.url, "pdf", remote, client=self.client,
                           context={"selected_date": "2026-01-01"})
        record = json.loads(Path(original["provenance_path"]).read_text())
        record.pop("content_type")
        Path(original["provenance_path"]).write_text(json.dumps(record))
        acquire(publisher.url, "pdf", self.root / "local", client=self.client,
                archive_roots=[remote], context={"selected_date": "2026-01-01"})
        acquire(publisher.url, "pdf", self.root / "other", client=self.client,
                archive_roots=[remote], context={"selected_date": "2026-01-02"})
        self.assertEqual(3, len(publisher.requests))

    def test_wrong_signature_or_naive_timestamp_is_not_reused(self):
        publisher = self.publisher()
        remote = self.root / "remote"
        original = acquire(publisher.url, "pdf", remote, client=self.client)
        bad_body = b"not-a-pdf"
        bad_hash = hashlib.sha256(bad_body).hexdigest()
        Path(original["source_path"]).unlink()
        (remote / f"{bad_hash}.pdf").write_bytes(bad_body)
        record = json.loads(Path(original["provenance_path"]).read_text())
        record.update(sha256=bad_hash, byte_length=len(bad_body))
        Path(original["provenance_path"]).write_text(json.dumps(record))
        run = json.loads(Path(original["run_path"]).read_text())
        run.update(source_sha256=bad_hash, source=f"{bad_hash}.pdf")
        Path(original["run_path"]).write_text(json.dumps(run))
        acquired = acquire(publisher.url, "pdf", self.root / "local", client=self.client,
                           archive_roots=[remote])
        self.assertEqual("acquired", acquired["status"])
        self.assertEqual(2, len(publisher.requests))

        valid_record = json.loads(Path(acquired["provenance_path"]).read_text())
        valid_record["retrieved_at"] = "2026-09-26T08:00:00"
        Path(acquired["provenance_path"]).write_text(json.dumps(valid_record))
        acquired = acquire(publisher.url, "pdf", self.root / "other", client=self.client,
                           archive_roots=[self.root / "local"])
        self.assertEqual("acquired", acquired["status"])
        self.assertEqual(3, len(publisher.requests))

    def test_interrupted_download_creates_gap_without_complete_run(self):
        body = b"%PDF-1.7\nshort"
        publisher = Publisher(lambda path: (200, {"Content-Type": "application/pdf",
                                                 "Content-Length": str(len(body) + 10)}, body))
        self.addCleanup(publisher.close)
        with self.assertRaises(SourceRejected) as caught:
            acquire(publisher.url, "pdf", self.root / "local", client=self.client)
        self.assertEqual("attempts_exhausted", caught.exception.reason)
        self.assertEqual([], list((self.root / "local").glob("run-*.json")))
        self.assertEqual([], list((self.root / "local").glob("*.pdf")))
        self.assertTrue(json.loads(Path(caught.exception.gap_path).read_text())["events"])

    def test_import_checkpoint_skips_completed_import_and_replays_after_interruption(self):
        publisher = self.publisher()
        receipt = acquire(publisher.url, "pdf", self.root / "local", client=self.client)
        calls = []

        def interrupted(source, provenance):
            calls.append((source, provenance))
            raise RuntimeError("interrupted")

        with self.assertRaises(RuntimeError):
            import_once(receipt["run_path"], interrupted)
        self.assertEqual("pending", json.loads(Path(receipt["run_path"]).read_text())["import_status"])

        def importer(source, provenance):
            calls.append((source, provenance))
            return "job-1"

        self.assertEqual("job-1", import_once(receipt["run_path"], importer))
        self.assertEqual("job-1", import_once(receipt["run_path"], importer))
        self.assertEqual(2, len(calls))
        self.assertEqual("complete", json.loads(Path(receipt["run_path"]).read_text())["import_status"])

    def test_import_once_cli_runs_exactly_one_successful_import(self):
        publisher = self.publisher()
        receipt = acquire(publisher.url, "pdf", self.root / "local", client=self.client)
        marker = self.root / "import-count.txt"
        command = [sys.executable, str(ROOT / "scripts" / "acquire_source.py"),
                   "import-once", receipt["run_path"], "immutable-job-1", "--",
                   sys.executable, "-c", "import pathlib,sys; pathlib.Path(sys.argv[1]).open('a').write('1')",
                   str(marker)]
        self.assertEqual(0, subprocess.run(command, capture_output=True).returncode)
        self.assertEqual(0, subprocess.run(command, capture_output=True).returncode)
        self.assertEqual("1", marker.read_text())

    def test_existing_clojure_archive_is_inventoried_but_lacks_http_receipt(self):
        publisher = self.publisher()
        body = b"%PDF-1.7\noriginal"
        source = self.root / "source.pdf"
        source.write_bytes(body)
        manifest = self.root / "manifest.edn"
        manifest.write_text("{:sha256 \"" + hashlib.sha256(body).hexdigest() +
                            "\" :discovery-url \"" + publisher.url +
                            "\" :final-url \"" + publisher.url +
                            "\" :acquisition-method \"synthetic\" :retrieved-at \"2026-09-26T00:00:00Z\" "
                            ":content-type \"application/pdf\" :publisher \"Synthetic\" "
                            ":relationship :publisher :mirror-of nil}")
        archive = self.root / "archive"
        subprocess.run(["clojure", "-M:archive", "import", str(archive), str(source), str(manifest)],
                       cwd=ROOT, capture_output=True, check=True)
        receipt = acquire(publisher.url, "pdf", self.root / "local", client=self.client,
                          archive_roots=[archive])
        self.assertEqual(1, receipt["legacy_archive_incomplete"])
        self.assertEqual("acquired", receipt["status"])
        self.assertEqual(1, len(publisher.requests))

    def test_import_checkpoint_wraps_idempotent_archive_cli(self):
        publisher = self.publisher()
        receipt = acquire(publisher.url, "pdf", self.root / "local", client=self.client)
        manifest = self.root / "manifest.edn"
        manifest.write_text("{:sha256 \"" + receipt["sha256"] +
                            "\" :discovery-url \"" + publisher.url +
                            "\" :final-url \"" + publisher.url +
                            "\" :acquisition-method \"synthetic\" :retrieved-at \"2026-09-26T00:00:00Z\" "
                            ":content-type \"application/pdf\" :publisher \"Synthetic\" "
                            ":relationship :publisher :mirror-of nil}")
        archive = self.root / "archive"
        command = [sys.executable, str(ROOT / "scripts" / "acquire_source.py"),
                   "import-once", receipt["run_path"], "synthetic-acquisition", "--",
                   "clojure", "-M:archive", "import", str(archive), "{source}", str(manifest)]
        for _ in range(2):
            self.assertEqual(0, subprocess.run(command, cwd=ROOT, capture_output=True).returncode)
        inspected = subprocess.run(["clojure", "-M:archive", "inspect", str(archive), receipt["sha256"]],
                                   cwd=ROOT, capture_output=True, text=True, check=True)
        self.assertIn(receipt["sha256"], inspected.stdout)
        self.assertEqual("complete", json.loads(Path(receipt["run_path"]).read_text())["import_status"])


if __name__ == "__main__":
    unittest.main()
