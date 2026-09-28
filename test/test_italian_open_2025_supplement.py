"""CLI contract for the private Italian Open 2025 visual packet."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zlib


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/italian_open_2025_supplement.py"


def pdf35():
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Count 35 /Kids [" +
               b" ".join(f"{i} 0 R".encode() for i in range(3, 38)) + b"] >>"]
    objects += [b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] "
                b"/Resources <<>> /Contents 38 0 R >>" for _ in range(35)]
    objects += [b"<< /Length 0 >>\nstream\n\nendstream"]
    data = b"%PDF-1.4\n"
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f"{index} 0 obj\n".encode() + obj + b"\nendobj\n"
    start = len(data)
    data += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
    data += b"".join(f"{offset:010} 00000 n \n".encode() for offset in offsets[1:])
    return data + f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode()


def png(width=100, height=100):
    def chunk(tag, data):
        return len(data).to_bytes(4, "big") + tag + data + zlib.crc32(tag + data).to_bytes(4, "big")
    scanline = b"\0" + b"\xff\xff\xff" * width
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", width.to_bytes(4, "big") +
            height.to_bytes(4, "big") + b"\x08\x02\0\0\0") +
            chunk(b"IDAT", zlib.compress(scanline * height)) + chunk(b"IEND", b""))


def fixture(root):
    pdf = root / "source.pdf"
    pdf.write_bytes(pdf35())
    digest = hashlib.sha256(pdf.read_bytes()).hexdigest()
    render = root / "page.png"
    render.write_bytes(png())
    pages = []
    for number in range(1, 36):
        kind = "result_table" if number == 1 else "aggregate" if number == 2 else "blank"
        row = {"row": 1, "bbox": [1, 1, 50, 20],
               "fields_raw": {"Athlete": "A. Diver", "Result": "50 m"},
               "status_raw": None, "penalty_raw": None, "notes_raw": None,
               "uncertainties": [],
               "disposition": "candidate_result" if kind == "result_table" else "aggregate"}
        rows = [row] if kind != "blank" else []
        pages.append({"page": number, "disposition": kind, "render_path": str(render),
                      "heading_raw": None if kind == "blank" else "Results",
                      "event_date_raw": None, "session_raw": None,
                      "category_raw": None, "discipline_raw": None,
                      "columns_raw": ["Athlete", "Result"] if rows else [],
                      "visual_row_count": len(rows), "rows": rows, "uncertainties": []})
    parts = []
    for start in (0, 12, 24):
        path = root / f"part-{start}.json"
        path.write_text(json.dumps({"source_sha256": digest,
                                    "pages": pages[start:start + 12]}))
        parts.append(path)
    return pdf, digest, parts


def run(root, pdf, digest, parts):
    output = root / "packet.json"
    command = [sys.executable, str(SCRIPT), "--pdf", str(pdf), "--output", str(output),
               "--expected-sha256", digest]
    for part in parts:
        command += ["--part", str(part)]
    return subprocess.run(command, capture_output=True, text=True), output


class ItalianOpenPacketTest(unittest.TestCase):
    def test_complete_packet_and_deterministic_replay(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pdf, digest, parts = fixture(root)
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 0, result.stderr)
            raw = output.read_bytes()
            packet = json.loads(raw)
            self.assertEqual(packet["source_sha256"], digest)
            self.assertEqual(len(packet["pages"]), 35)
            self.assertEqual(packet["pages"][0]["render"]["sha256"],
                             hashlib.sha256((root / "page.png").read_bytes()).hexdigest())
            self.assertEqual(packet["pages"][0]["rows"][0]["citation"]["region"]["bbox"],
                             [1, 1, 50, 20])
            self.assertEqual(packet["counts"]["candidate_result_positions"], 1)
            self.assertEqual(packet["counts"]["aggregate_rows_excluded"], 1)
            self.assertIsNone(packet["counts"]["confirmed_distinct_attempts"])
            self.assertEqual(packet["owner_review_status"], "unreviewed")
            self.assertEqual(packet["observation_versions"], [])
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_bytes(), raw)

    def test_missing_page_and_bad_duplicate_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pdf, digest, parts = fixture(root)
            part = json.loads(parts[-1].read_text())
            part["pages"].pop()
            parts[-1].write_text(json.dumps(part))
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 2)
            self.assertIn("pages 1 through 35", result.stderr)
            self.assertFalse(output.exists())
            part["pages"].append({**part["pages"][-1], "page": 35})
            parts[-1].write_text(json.dumps(part))
            first = json.loads(parts[0].read_text())
            first["pages"][1]["rows"][0].update(disposition="duplicate_render",
                duplicate_of={"page": 1, "row": 1}, duplicate_evidence="repeated")
            parts[0].write_text(json.dumps(first))
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 2)
            self.assertIn("duplicate", result.stderr)
            self.assertFalse(output.exists())

    def test_partial_unresolved_page_and_row_stay_explicit(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pdf, digest, parts = fixture(root)
            first = json.loads(parts[0].read_text())
            first["pages"][0]["rows"][0].update(disposition="unresolved", bbox=None,
                fields_raw={"Athlete": None, "Result": None},
                uncertainties=["row print illegible"])
            first["pages"][2].update(disposition="unresolved", visual_row_count=None,
                uncertainties=["page image awaiting visual count"])
            parts[0].write_text(json.dumps(first))
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 0, result.stderr)
            packet = json.loads(output.read_bytes())
            self.assertEqual(packet["gap_reconciliation"]["status"], "visual_census_incomplete")
            self.assertEqual(packet["counts"]["unresolved_pages"], 1)
            self.assertEqual(packet["counts"]["unresolved_positions"], 1)
            self.assertEqual(packet["pages"][0]["rows"][0]["citation"]["region"], None)

    def test_ranking_relationship_is_only_a_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pdf, digest, parts = fixture(root)
            first = json.loads(parts[0].read_text())
            first["pages"][0]["rows"][0].update(
                duplicate_of="page 2 row 1", duplicate_evidence="same printed person, different rank")
            first["pages"][0]["rows"][0].pop("bbox")
            parts[0].write_text(json.dumps(first))
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 0, result.stderr)
            packet = json.loads(output.read_bytes())
            self.assertEqual(packet["counts"]["relationship_candidate_links"], 1)
            self.assertEqual(packet["counts"]["duplicate_rendered_rows"], 0)
            self.assertEqual(packet["gap_reconciliation"]["status"], "visual_census_incomplete")
            self.assertEqual(packet["counts"]["rows_missing_region"], 1)
            self.assertEqual(packet["pages"][0]["rows"][0]["relationship_candidate_of"],
                             {"page": 2, "row": 1})
            self.assertNotIn("duplicate_of", packet["pages"][0]["rows"][0])
            self.assertIn("Exact row region", packet["pages"][0]["rows"][0]["uncertainties"][-1])

    def test_rejects_inferred_attempt_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pdf, digest, parts = fixture(root)
            first = json.loads(parts[0].read_text())
            first["pages"][0]["rows"][0]["attempt_id"] = "assumed"
            parts[0].write_text(json.dumps(first))
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 2)
            self.assertIn("unsupported inferred", result.stderr)
            self.assertFalse(output.exists())

    def test_free_text_relationship_note_is_not_a_row_link(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pdf, digest, parts = fixture(root)
            first = json.loads(parts[0].read_text())
            first["pages"][0]["rows"][0].update(
                duplicate_of="page 2, alternate ranking view; values may differ",
                duplicate_evidence="Same name; no attempt identity established")
            parts[0].write_text(json.dumps(first))
            result, output = run(root, pdf, digest, parts)
            self.assertEqual(result.returncode, 0, result.stderr)
            packet = json.loads(output.read_bytes())
            row = packet["pages"][0]["rows"][0]
            self.assertEqual(row["relationship_candidate_note"],
                             "page 2, alternate ranking view; values may differ")
            self.assertNotIn("relationship_candidate_of", row)
            self.assertEqual(packet["counts"]["relationship_candidate_notes"], 1)
            self.assertEqual(packet["counts"]["relationship_candidate_links"], 0)


if __name__ == "__main__":
    unittest.main()
