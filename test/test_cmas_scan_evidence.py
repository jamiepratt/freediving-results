import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CLI = Path(__file__).resolve().parents[1] / "scripts" / "cmas_scan_evidence.py"


def small_pdf():
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>",
        b"<< /Length 0 >>\nstream\n\nendstream",
    ]
    data = b"%PDF-1.4\n"
    offsets = [0]
    for number, body in enumerate(objects, 1):
        offsets.append(len(data))
        data += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(data)
    data += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
    data += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets[1:])
    data += f"trailer\n<< /Root 1 0 R /Size {len(offsets)} >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return data


class ScanEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pdf = self.root / "source.pdf"
        self.pdf.write_bytes(small_pdf())
        self.ledger = self.root / "ledger.json"
        self.out = self.root / "evidence"

    def write_ledger(self, **changes):
        ledger = {
            "ledger_version": "camotes-world-cup-image-row-ledger/1",
            "source_sha256": hashlib.sha256(self.pdf.read_bytes()).hexdigest(),
            "source_page_count": 1,
            "pages": [{"page": 1, "visual_printed_row_count": 1, "dimensions_px": [417, 417]}],
            "totals": {"printed_positions": 1, "parsed": 0, "unresolved": 1},
            "positions": [{"page": 1, "block": 1, "line": 1, "position": "p1-b1-r01",
                           "image_region_px": {"x1": 1, "y1": 1, "x2": 416, "y2": 416},
                           "review_status": "unresolved", "unresolved_fields": ["card"]}],
        }
        ledger.update(changes)
        self.ledger.write_text(json.dumps(ledger))

    def run_cli(self, ok=True):
        result = subprocess.run([sys.executable, str(CLI), str(self.pdf), str(self.ledger), str(self.out)],
                                capture_output=True, text=True)
        if ok:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
        return result

    def test_unresolved_row_has_render_and_ocr_evidence_but_cannot_be_import_ready(self):
        self.write_ledger()
        self.run_cli()
        evidence = json.loads((self.out / "evidence.json").read_text())
        self.assertEqual("cmas-scan-evidence/v1", evidence["schema"])
        self.assertEqual({"printed_positions": 1, "parsed": 0, "unresolved": 1}, evidence["totals"])
        self.assertFalse(evidence["import_ready"])
        self.assertEqual(1, len(evidence["pages"]))
        self.assertEqual([417, 417], evidence["pages"][0]["dimensions_px"])
        self.assertTrue((self.out / "rendered" / "page-1.png").exists())
        self.assertTrue((self.out / "ocr" / "page-1.tsv").exists())
        self.assertEqual(hashlib.sha256((self.out / "rendered" / "page-1.png").read_bytes()).hexdigest(),
                         evidence["pages"][0]["image_sha256"])
        self.assertIn("tesseract", evidence["ocr"]["version"].lower())

    def test_reviewed_row_requires_manual_image_attribution(self):
        self.write_ledger(totals={"printed_positions": 1, "parsed": 1, "unresolved": 0},
                          required_fields=["card"],
                          positions=[{"page": 1, "block": 1, "line": 1, "position": "p1-b1-r01",
                                      "image_region_px": {"x1": 1, "y1": 1, "x2": 416, "y2": 416},
                                      "review_status": "reviewed", "reviewer": "human",
                                      "accepted_fields": {"card": "W"}}])
        self.assertIn("source-image", self.run_cli(ok=False).stderr)
        self.assertFalse(self.out.exists())

    def test_fully_reviewed_row_can_be_marked_ready_without_using_ocr(self):
        self.write_ledger(totals={"printed_positions": 1, "parsed": 1, "unresolved": 0},
                          required_fields=["card"],
                          positions=[{"page": 1, "block": 1, "line": 1, "position": "p1-b1-r01",
                                      "image_region_px": {"x1": 1, "y1": 1, "x2": 416, "y2": 416},
                                      "review_status": "reviewed", "reviewer": "human",
                                      "review_method": "source-image", "accepted_fields": {"card": "W"}}])
        self.run_cli()
        evidence = json.loads((self.out / "evidence.json").read_text())
        self.assertTrue(evidence["import_ready"])
        self.assertNotIn("accepted_fields", evidence)

    def test_reviewed_row_without_declared_field_scope_is_not_ready(self):
        self.write_ledger(totals={"printed_positions": 1, "parsed": 1, "unresolved": 0},
                          positions=[{"page": 1, "block": 1, "line": 1, "position": "p1-b1-r01",
                                      "image_region_px": {"x1": 1, "y1": 1, "x2": 416, "y2": 416},
                                      "review_status": "reviewed", "reviewer": "human",
                                      "review_method": "source-image", "accepted_fields": {"card": "W"}}])
        self.assertIn("required fields", self.run_cli(ok=False).stderr)

    def test_rejects_source_mismatch_duplicate_position_and_bad_reconciliation(self):
        self.write_ledger(source_sha256="0" * 64)
        self.assertIn("source SHA-256 mismatch", self.run_cli(ok=False).stderr)
        self.write_ledger(totals={"printed_positions": 2, "parsed": 0, "unresolved": 1})
        self.assertIn("do not reconcile", self.run_cli(ok=False).stderr)
        row = {"page": 1, "block": 1, "line": 1, "position": "p1-b1-r01",
               "image_region_px": {"x1": 1, "y1": 1, "x2": 416, "y2": 416},
               "review_status": "unresolved", "unresolved_fields": ["card"]}
        self.write_ledger(totals={"printed_positions": 2, "parsed": 0, "unresolved": 2},
                          pages=[{"page": 1, "visual_printed_row_count": 2, "dimensions_px": [417, 417]}],
                          positions=[row, row])
        self.assertIn("duplicate row position", self.run_cli(ok=False).stderr)
        self.assertFalse(self.out.exists())

    def test_rejects_position_outside_render(self):
        self.write_ledger(positions=[{"page": 1, "block": 1, "line": 1, "position": "p1-b1-r01",
                                      "image_region_px": {"x1": 1, "y1": 1, "x2": 418, "y2": 416},
                                      "review_status": "unresolved", "unresolved_fields": ["card"]}])
        self.assertIn("outside page", self.run_cli(ok=False).stderr)
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
