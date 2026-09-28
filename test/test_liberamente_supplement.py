"""Public CLI checks for the private Liberamente visual evidence packet."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/liberamente_supplement.py"


def blank_pdf():
    """A local 24-page A4 landscape PDF, without an external PDF package."""
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Count 24 /Kids [" +
               b" ".join(f"{i} 0 R".encode() for i in range(3, 27)) + b"] >>"]
    objects += [b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 841.68 595.2] "
                b"/Resources <<>> /Contents 27 0 R >>" for _ in range(24)]
    objects += [b"<< /Length 0 >>\nstream\n\nendstream"]
    data = b"%PDF-1.4\n"
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f"{index} 0 obj\n".encode() + obj + b"\nendobj\n"
    start = len(data)
    data += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
    data += b"".join(f"{offset:010} 00000 n \n".encode() for offset in offsets[1:])
    data += f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode()
    return data


def save(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


def fixture(tmp_path):
    pdf = tmp_path / "original.pdf"
    pdf.write_bytes(blank_pdf())
    digest = hashlib.sha256(pdf.read_bytes()).hexdigest()
    url = "https://fipsas.it/results/liberamente.pdf"
    gap = save(tmp_path / "gap.json", {"source_gaps": [{
        "verified_sha256": digest, "bytes": pdf.stat().st_size,
        "assessment": {"disposition": "unsupported_image_results"},
        "source": {"id": "sha256:" + digest, "original_sha256": digest,
                   "final_url": url, "event_ids": [], "source_status": "no_imported_extraction"}}]})
    cards = save(tmp_path / "cards.json", {"cards": [{"event_id": 42,
        "date": "2026-07-04", "title": "Liberamente", "result_urls": [url]}]})
    pages = []
    for number in range(1, 25):
        kind = "aggregate" if number <= 2 else "blank" if number == 16 else "athlete"
        rows = [] if kind == "blank" else [{
            "row": 1, "bbox": [10, 20, 90, 35],
            "fields_raw": {"Name": "A. Diver", "Result": "50"},
            "status_raw": None, "penalty_raw": None, "notes_raw": None,
            "uncertainties": []}]
        pages.append({"page": number, "page_kind": kind,
            "render": {"dpi": 130, "pixel_width": 1520, "pixel_height": 1075},
            "heading_raw": None if kind == "blank" else "Results",
            "event_date_raw": None, "category_raw": None,
            "discipline_raw": None, "columns_raw": [] if kind == "blank" else ["Name", "Result"],
            "visual_row_count": len(rows), "rows": rows})
    parts = [save(tmp_path / f"part-{i}.json", {"source_sha256": digest,
        "pages": pages[i:i + 8]}) for i in (0, 8, 16)]
    return pdf, gap, cards, parts, digest


def run(tmp_path, pdf, gap, cards, parts, digest):
    output = tmp_path / "packet.json"
    args = [sys.executable, str(SCRIPT), "--pdf", str(pdf), "--gap", str(gap),
            "--card-ledger", str(cards), "--output", str(output),
            "--expected-sha256", digest]
    for part in parts:
        args += ["--part", str(part)]
    result = subprocess.run(args, text=True, capture_output=True)
    return result, output


def test_exports_complete_unreviewed_packet_deterministically(tmp_path):
    inputs = fixture(tmp_path)
    result, output = run(tmp_path, *inputs)
    assert result.returncode == 0, result.stderr
    raw = output.read_bytes()
    packet = json.loads(raw)
    assert packet["counts"]["athlete_row_appearances"] == 21
    assert packet["counts"]["aggregate_rows_excluded"] == 2
    assert packet["counts"]["nonduplicate_candidate_positions"] == 21
    assert packet["counts"]["confirmed_distinct_attempts"] is None
    assert packet["counts"]["imported_observation_versions"] == 0
    assert packet["source_objects"] == [packet["source"]]
    assert len(packet["athlete_appearances"]) == 21
    assert packet["athlete_appearances"][0]["id"] == packet["pages"][2]["rows"][0]["id"]
    assert packet["observation_versions"] == []
    assert packet["owner_review_status"] == "unreviewed"
    assert packet["pages"][15]["rows"] == []
    assert packet["pages"][2]["rows"][0]["citation"]["region"]["bbox"] == [10, 20, 90, 35]
    result, output = run(tmp_path, *inputs)
    assert result.returncode == 0, result.stderr
    assert output.read_bytes() == raw


def test_rejects_missing_page_and_invalid_citation(tmp_path):
    pdf, gap, cards, parts, digest = fixture(tmp_path)
    part = json.loads(parts[2].read_text())
    part["pages"].pop()
    save(parts[2], part)
    result, output = run(tmp_path, pdf, gap, cards, parts, digest)
    assert result.returncode == 2
    assert "pages 1 through 24" in result.stderr
    assert not output.exists()
    part = json.loads(parts[2].read_text())
    part["pages"].append({**part["pages"][-1], "page": 24,
                          "rows": [{**part["pages"][-1]["rows"][0], "bbox": [10, 20, 9999, 35]}]})
    save(parts[2], part)
    result, output = run(tmp_path, pdf, gap, cards, parts, digest)
    assert result.returncode == 2
    assert "bbox" in result.stderr
    assert not output.exists()


def test_rejects_false_duplicate_and_source_mismatch(tmp_path):
    pdf, gap, cards, parts, digest = fixture(tmp_path)
    part = json.loads(parts[1].read_text())
    part["pages"][0]["rows"][0]["duplicate_of"] = {"page": 3, "row": 1}
    part["pages"][0]["rows"][0]["duplicate_evidence"] = "same printed line"
    part["pages"][0]["rows"][0]["fields_raw"]["Result"] = "51"
    save(parts[1], part)
    result, output = run(tmp_path, pdf, gap, cards, parts, digest)
    assert result.returncode == 2
    assert "duplicate_of" in result.stderr
    assert not output.exists()
    pdf.write_bytes(b"altered")
    result, output = run(tmp_path, pdf, gap, cards, parts, digest)
    assert result.returncode == 2
    assert "SHA256" in result.stderr


def test_rejects_pdf_geometry_even_when_ledger_claims_correct_render(tmp_path):
    pdf, gap, cards, parts, digest = fixture(tmp_path)
    changed = pdf.read_bytes().replace(b"841.68 595.2", b"840.00 595.2", 1)
    pdf.write_bytes(changed)
    changed_digest = hashlib.sha256(changed).hexdigest()
    gap_doc = json.loads(gap.read_text())
    gap_doc["source_gaps"][0]["verified_sha256"] = changed_digest
    gap_doc["source_gaps"][0]["source"]["id"] = "sha256:" + changed_digest
    gap_doc["source_gaps"][0]["source"]["original_sha256"] = changed_digest
    save(gap, gap_doc)
    for part in parts:
        doc = json.loads(part.read_text())
        doc["source_sha256"] = changed_digest
        save(part, doc)
    result, output = run(tmp_path, pdf, gap, cards, parts, changed_digest)
    assert result.returncode == 2
    assert "geometry" in result.stderr
    assert not output.exists()


class LiberamenteSupplementTest(unittest.TestCase):
    def test_export(self):
        with tempfile.TemporaryDirectory() as root:
            test_exports_complete_unreviewed_packet_deterministically(Path(root))

    def test_invalid_page_and_citation(self):
        with tempfile.TemporaryDirectory() as root:
            test_rejects_missing_page_and_invalid_citation(Path(root))

    def test_false_duplicate_and_source_mismatch(self):
        with tempfile.TemporaryDirectory() as root:
            test_rejects_false_duplicate_and_source_mismatch(Path(root))

    def test_pdf_geometry(self):
        with tempfile.TemporaryDirectory() as root:
            test_rejects_pdf_geometry_even_when_ledger_claims_correct_render(Path(root))
