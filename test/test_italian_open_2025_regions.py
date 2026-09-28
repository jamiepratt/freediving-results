"""CLI contract for private page-region evidence versions."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_italian_open_2025_supplement import png


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/italian_open_2025_regions.py"
REGION_NOTE = "Exact row region not bounded in page render"


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def fixture(root):
    pdf = root / "source.pdf"
    pdf.write_bytes(b"fixture source")
    source_hash = hashlib.sha256(pdf.read_bytes()).hexdigest()
    renders = root / "renders"
    renders.mkdir()
    image = png(100, 100)
    image_hash = hashlib.sha256(image).hexdigest()
    pages, entries = [], []
    for page_number in range(1, 36):
        if page_number <= 18:
            (renders / f"page-{page_number:02}.png").write_bytes(image)
        row_count = 8 if page_number <= 17 else 2 if page_number == 18 else 0
        rows = []
        for ordinal in range(1, row_count + 1):
            ident = f"source-position:{page_number}-{ordinal}"
            row = {"id": ident, "row": ordinal, "disposition": "candidate_result",
                   "citation": {"source_sha256": source_hash, "page": page_number,
                                "region": None},
                   "fields_raw": {"Name": f"Diver {ident}"}, "status_raw": None,
                   "penalty_raw": None, "notes_raw": None,
                   "uncertainties": [REGION_NOTE], "review_status": "unreviewed"}
            rows.append(row)
            entries.append({"id": ident, "page": page_number, "row": ordinal,
                            "bbox": [0.1, ordinal / 10, 0.9, round(ordinal / 10 + 0.05, 2)],
                            "render_sha256": image_hash})
        pages.append({"page": page_number, "disposition": "result_table" if rows else "blank",
                      "render": {"sha256": image_hash, "pixel_width": 100,
                                 "pixel_height": 100, "format": "png"},
                      "visual_row_count": len(rows), "rows": rows})
    packet = {"schema": "italian-open-2025-visual-evidence/v1",
              "source_sha256": source_hash, "source_bytes": len(pdf.read_bytes()),
              "input_sha256": {"page_parts": []},
              "owner_review_status": "unreviewed", "observation_versions": [],
              "source_relationship_status": "unresolved",
              "gap_reconciliation": {"status": "visual_census_incomplete",
                                     "owner_review_status": "unreviewed"},
              "pages": pages, "candidate_result_appearances":
              [row for page in pages for row in page["rows"]],
              "counts": {"candidate_result_positions": 138,
                         "rows_missing_region": 138, "rows_with_uncertainties": 138,
                         "rows_with_field_uncertainties": 0,
                         "unresolved_field_notes": 0,
                         "confirmed_distinct_attempts": None,
                         "imported_observation_versions": 0}}
    original = root / "packet.json"
    original.write_bytes(encoded(packet))
    first = root / "first.json"
    second = root / "second.json"
    first.write_bytes(encoded(entries[:72]))
    second.write_bytes(encoded(entries[72:]))
    return pdf, original, renders, first, second, entries


def run(root, pdf, original, renders, first, second):
    output, diff = root / "packet-regions.json", root / "diff.json"
    command = [sys.executable, str(SCRIPT), "--original", str(original),
               "--pdf", str(pdf), "--renders", str(renders),
               "--first-map", str(first), "--second-map", str(second),
               "--output", str(output), "--diff", str(diff),
               "--expected-sha256", hashlib.sha256(pdf.read_bytes()).hexdigest()]
    return subprocess.run(command, text=True, capture_output=True), output, diff


class ItalianOpenRegionsTest(unittest.TestCase):
    def test_complete_overlay_is_deterministic_and_keeps_later_pages(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = fixture(root)
            result, output, diff = run(root, *args[:5])
            self.assertEqual(result.returncode, 0, result.stderr)
            packet = json.loads(output.read_bytes())
            changes = json.loads(diff.read_bytes())
            self.assertEqual(packet["pages"][0]["rows"][0]["citation"]["region"],
                             {"units": "normalized_image", "bbox": [0.1, 0.1, 0.9, 0.15],
                              "render_sha256": args[5][0]["render_sha256"]})
            self.assertEqual(packet["counts"]["rows_missing_region"], 0)
            self.assertEqual(packet["counts"]["rows_with_uncertainties"], 0)
            self.assertEqual(len(changes["rows"]), 138)
            self.assertEqual(packet["pages"][18:], json.loads(args[1].read_bytes())["pages"][18:])
            self.assertIsNone(packet["counts"]["confirmed_distinct_attempts"])
            output_hash, diff_hash = output.read_bytes(), diff.read_bytes()
            result, _, _ = run(root, *args[:5])
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((output.read_bytes(), diff.read_bytes()), (output_hash, diff_hash))

    def test_missing_or_overlapping_regions_are_rejected_without_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = fixture(root)
            second = json.loads(args[4].read_bytes())
            args[4].write_bytes(encoded(second[:-1]))
            result, output, diff = run(root, *args[:5])
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            self.assertFalse(diff.exists())
            args[4].write_bytes(encoded(second))
            first = json.loads(args[3].read_bytes())
            first[1]["bbox"] = first[0]["bbox"]
            args[3].write_bytes(encoded(first))
            result, output, diff = run(root, *args[:5])
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            self.assertFalse(diff.exists())

    def test_correction_requires_evidence_and_records_row_diff(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = fixture(root)
            first = json.loads(args[3].read_bytes())
            first[0]["corrections"] = {"fields_raw": {"Name": "Corrected Diver"},
                                       "uncertainties": ["Printed surname remains unclear"]}
            args[3].write_bytes(encoded(first))
            result, output, diff = run(root, *args[:5])
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            first[0]["evidence"] = "Page 1 row 1 enlarged image shows this lettering."
            args[3].write_bytes(encoded(first))
            result, output, diff = run(root, *args[:5])
            self.assertEqual(result.returncode, 0, result.stderr)
            packet = json.loads(output.read_bytes())
            self.assertEqual(packet["pages"][0]["rows"][0]["fields_raw"]["Name"],
                             "Corrected Diver")
            self.assertEqual(packet["counts"]["rows_with_field_uncertainties"], 1)
            change = json.loads(diff.read_bytes())["rows"][0]
            self.assertEqual(change["before"]["fields_raw"]["Name"], "Diver source-position:1-1")
            self.assertEqual(change["after"]["fields_raw"]["Name"], "Corrected Diver")
            self.assertIn("enlarged image", change["evidence"])

    def test_changed_page_render_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = fixture(root)
            (args[2] / "page-01.png").write_bytes(png(101, 100))
            result, output, diff = run(root, *args[:5])
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("render bytes differ", result.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
