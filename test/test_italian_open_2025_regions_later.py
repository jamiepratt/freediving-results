"""CLI contract for the second Italian Open region evidence version."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_italian_open_2025_regions import encoded, fixture, run as run_first, REGION_NOTE


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/italian_open_2025_regions_later.py"
ROW_COUNTS = [2, 9, 7, 6, 9, 4, 16, 1, 6, 5, 13, 1, 4, 7, 2, 6, 6]


def later_fixture(root):
    pdf, original, renders, first, second, _ = fixture(root)
    result, prior_path, _ = run_first(root, pdf, original, renders, first, second)
    assert result.returncode == 0, result.stderr
    prior = json.loads(prior_path.read_bytes())
    image = (renders / "page-01.png").read_bytes()
    image_hash = hashlib.sha256(image).hexdigest()
    entries = []
    for page_number, count in enumerate(ROW_COUNTS, 19):
        page = prior["pages"][page_number - 1]
        (renders / f"page-{page_number:02}.png").write_bytes(image)
        page["visual_row_count"] = count
        page["disposition"] = "summary" if page_number in (19, 20, 32, 33, 34, 35) else "result_table"
        page["rows"] = []
        for ordinal in range(1, count + 1):
            ident = f"source-position:{page_number}-{ordinal}"
            row = {"id": ident, "row": ordinal, "disposition": "candidate_result",
                   "citation": {"source_sha256": prior["source_sha256"],
                                "page": page_number, "region": None},
                   "fields_raw": {"Name": f"Diver {ident}"}, "status_raw": None,
                   "penalty_raw": None, "notes_raw": None,
                   "uncertainties": [REGION_NOTE], "review_status": "unreviewed"}
            if page_number == 35:
                row["fields_raw"] = {"Name": f"Diver source-position:34-{ordinal}"}
                row["disposition"] = "duplicate_render"
                row["duplicate_of"] = {"page": 34, "row": ordinal}
                row["duplicate_evidence"] = "Same printed row as page 34"
            page["rows"].append(row)
            entries.append({"id": ident, "page": page_number, "row": ordinal,
                            "bbox": [0.1, round(ordinal / 20, 3), 0.9,
                                     round(ordinal / 20 + 0.025, 3)],
                            "render_sha256": image_hash})
    prior["candidate_result_appearances"] = [row for page in prior["pages"]
                                             for row in page["rows"]
                                             if row["disposition"] == "candidate_result"]
    prior["counts"]["rows_missing_region"] = 104
    prior["counts"]["confirmed_distinct_attempts"] = None
    prior_path.write_bytes(encoded(prior))
    first_map, second_map = root / "later-first.json", root / "later-second.json"
    first_map.write_bytes(encoded([e for e in entries if e["page"] <= 26]))
    second_map.write_bytes(encoded([e for e in entries if e["page"] >= 27]))
    return pdf, prior_path, renders, first_map, second_map, entries


def run(root, args):
    pdf, prior, renders, first, second = args[:5]
    output, diff = root / "packet-v3.json", root / "diff-v3.json"
    command = [sys.executable, str(SCRIPT), "--prior", str(prior),
               "--pdf", str(pdf), "--renders", str(renders),
               "--first-map", str(first), "--second-map", str(second),
               "--output", str(output), "--diff", str(diff),
               "--expected-sha256", hashlib.sha256(pdf.read_bytes()).hexdigest(),
               "--expected-prior-sha256", hashlib.sha256(prior.read_bytes()).hexdigest()]
    return subprocess.run(command, capture_output=True, text=True), output, diff


class ItalianOpenLaterRegionsTest(unittest.TestCase):
    def test_complete_overlay_preserves_earlier_pages_and_repeat_links(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = later_fixture(root)
            result, output, diff = run(root, args)
            self.assertEqual(result.returncode, 0, result.stderr)
            packet = json.loads(output.read_bytes())
            prior = json.loads(args[1].read_bytes())
            changes = json.loads(diff.read_bytes())
            self.assertEqual(packet["schema"], "italian-open-2025-visual-evidence/v3")
            self.assertEqual(packet["pages"][:18], prior["pages"][:18])
            self.assertEqual(sum(len(p["rows"]) for p in packet["pages"]), 242)
            self.assertEqual(packet["counts"]["rows_missing_region"], 0)
            self.assertEqual(len(changes["rows"]), 104)
            self.assertEqual(packet["pages"][34]["rows"][0]["duplicate_of"],
                             {"page": 34, "row": 1})
            self.assertEqual(packet["input_sha256"]["prior_packet"],
                             hashlib.sha256(args[1].read_bytes()).hexdigest())
            self.assertIsNone(packet["counts"]["confirmed_distinct_attempts"])
            before = output.read_bytes(), diff.read_bytes()
            result, _, _ = run(root, args)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((output.read_bytes(), diff.read_bytes()), before)

    def test_missing_and_overlapping_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = later_fixture(root)
            second = json.loads(args[4].read_bytes())
            args[4].write_bytes(encoded(second[:-1]))
            result, output, diff = run(root, args)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(output.exists())
            self.assertFalse(diff.exists())
            second[1]["bbox"] = second[0]["bbox"]
            args[4].write_bytes(encoded(second))
            result, output, diff = run(root, args)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(output.exists())
            self.assertFalse(diff.exists())

    def test_unlocatable_region_requires_reason_and_keeps_uncertainty(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = later_fixture(root)
            first = json.loads(args[3].read_bytes())
            first[0]["bbox"] = None
            args[3].write_bytes(encoded(first))
            result, output, _ = run(root, args)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(output.exists())
            first[0]["unlocatable_reason"] = "row boundary obscured by a page fold"
            args[3].write_bytes(encoded(first))
            result, output, _ = run(root, args)
            self.assertEqual(result.returncode, 0, result.stderr)
            row = json.loads(output.read_bytes())["pages"][18]["rows"][0]
            self.assertIsNone(row["citation"]["region"])
            self.assertIn("row boundary obscured", row["uncertainties"][0])

    def test_field_correction_requires_evidence_and_diffs_exact_row(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = later_fixture(root)
            first = json.loads(args[3].read_bytes())
            first[0]["corrections"] = {"fields_raw": {"Name": "Verified Diver"},
                                       "uncertainties": []}
            args[3].write_bytes(encoded(first))
            result, output, _ = run(root, args)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(output.exists())
            first[0]["evidence"] = "Page 19 row 1 image shows the complete name."
            args[3].write_bytes(encoded(first))
            result, output, diff = run(root, args)
            self.assertEqual(result.returncode, 0, result.stderr)
            row = json.loads(output.read_bytes())["pages"][18]["rows"][0]
            self.assertEqual(row["fields_raw"]["Name"], "Verified Diver")
            self.assertEqual(json.loads(diff.read_bytes())["rows"][0]["before"]["fields_raw"]["Name"],
                             "Diver source-position:19-1")

    def test_prior_hash_and_repeat_evidence_are_enforced(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            args = later_fixture(root)
            prior = json.loads(args[1].read_bytes())
            expected_hash = hashlib.sha256(args[1].read_bytes()).hexdigest()
            prior["pages"][34]["rows"][0]["fields_raw"]["Name"] = "Different diver"
            args[1].write_bytes(encoded(prior))
            result, output, diff = run(root, args)
            self.assertEqual(result.returncode, 2)
            self.assertIn("exact repeat", result.stderr)
            self.assertFalse(output.exists())
            self.assertFalse(diff.exists())
            command = [sys.executable, str(SCRIPT), "--prior", str(args[1]),
                       "--pdf", str(args[0]), "--renders", str(args[2]),
                       "--first-map", str(args[3]), "--second-map", str(args[4]),
                       "--output", str(output), "--diff", str(diff),
                       "--expected-sha256", hashlib.sha256(args[0].read_bytes()).hexdigest(),
                       "--expected-prior-sha256", expected_hash]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("prior packet SHA256", result.stderr)


if __name__ == "__main__":
    unittest.main()
