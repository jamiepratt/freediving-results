#!/usr/bin/env python3
"""Add checked page 1-18 visual regions to a private Italian Open evidence packet."""

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys


SOURCE_SHA256 = "e8ca68825e29d44a1b13127ab2cab55f6db534b15ae26ff005f13a1ca8ec9d44"
SOURCE_BYTES = 13755700
REGION_NOTE = "Exact row region not bounded in page render"
FIRST_PAGE, LAST_PAGE, EXPECTED_ROWS = 1, 18, 138
DISPOSITIONS = {"candidate_result", "aggregate", "summary", "duplicate_render", "unresolved"}
CORRECTION_KEYS = {"fields_raw", "disposition", "uncertainties"}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def read(path):
    raw = path.read_bytes()
    return json.loads(raw), digest(raw)


def region(entry, expected_render_hash):
    bbox = entry.get("bbox")
    require(isinstance(bbox, list) and len(bbox) == 4 and
            all(type(number) in (int, float) and 0 <= number <= 1 for number in bbox) and
            bbox[0] < bbox[2] and bbox[1] < bbox[3],
            f"page {entry.get('page')} row {entry.get('row')}: invalid normalized bbox")
    supplied_hash = entry.get("render_sha256")
    require(supplied_hash is None or supplied_hash == expected_render_hash,
            f"page {entry['page']}: entry render SHA256 differs from original")
    return {"units": "normalized_image", "bbox": bbox,
            "render_sha256": expected_render_hash}


def collides(a, b):
    return max(a[0], b[0]) < min(a[2], b[2]) and max(a[1], b[1]) < min(a[3], b[3])


def apply_corrections(row, entry):
    corrections = entry.get("corrections", {})
    require(isinstance(corrections, dict) and set(corrections) <= CORRECTION_KEYS,
            f"page {entry['page']} row {entry['row']}: invalid corrections")
    if not corrections:
        return
    evidence = entry.get("evidence")
    require(isinstance(evidence, str) and evidence.strip(),
            f"page {entry['page']} row {entry['row']}: correction needs visual evidence")
    if "fields_raw" in corrections:
        fields = corrections["fields_raw"]
        require(isinstance(fields, dict) and set(fields) == set(row["fields_raw"]) and
                all(value is None or isinstance(value, str) for value in fields.values()),
                f"page {entry['page']} row {entry['row']}: invalid raw field correction")
        row["fields_raw"] = fields
    if "disposition" in corrections:
        require(corrections["disposition"] in DISPOSITIONS,
                f"page {entry['page']} row {entry['row']}: invalid disposition correction")
        row["disposition"] = corrections["disposition"]
    if "uncertainties" in corrections:
        notes = corrections["uncertainties"]
        require(isinstance(notes, list) and
                all(isinstance(note, str) and note.strip() for note in notes) and
                REGION_NOTE not in notes,
                f"page {entry['page']} row {entry['row']}: invalid uncertainty correction")
        row["uncertainties"] = notes


def recount(packet):
    pages = packet["pages"]
    rows = [row for page in pages for row in page["rows"]]
    counts = packet["counts"]
    counts.update({
        "candidate_result_positions": sum(row["disposition"] == "candidate_result" for row in rows),
        "aggregate_rows_excluded": sum(row["disposition"] == "aggregate" for row in rows),
        "summary_rows_excluded": sum(row["disposition"] == "summary" for row in rows),
        "duplicate_rendered_rows": sum(row["disposition"] == "duplicate_render" for row in rows),
        "unresolved_positions": sum(row["disposition"] == "unresolved" for row in rows),
        "rows_with_uncertainties": sum(bool(row["uncertainties"]) for row in rows),
        "rows_with_field_uncertainties": sum(
            any(note != REGION_NOTE for note in row["uncertainties"]) for row in rows),
        "unresolved_field_notes": sum(
            note != REGION_NOTE for row in rows for note in row["uncertainties"]),
        "rows_missing_region": sum(row["citation"]["region"] is None for row in rows),
        "relationship_candidate_links": sum("relationship_candidate_of" in row for row in rows),
        "relationship_candidate_notes": sum("relationship_candidate_note" in row for row in rows),
        "unresolved_pages": sum(page["disposition"] == "unresolved" for page in pages),
        "confirmed_distinct_attempts": None,
        "imported_observation_versions": 0,
    })
    packet["candidate_result_appearances"] = [row for row in rows
                                               if row["disposition"] == "candidate_result"]
    packet["gap_reconciliation"]["status"] = (
        "visual_census_incomplete" if counts["unresolved_pages"] or
        counts["unresolved_positions"] or counts["rows_missing_region"] or
        any(page["visual_row_count"] is None for page in pages)
        else "visual_positions_reconciled")


def build(original, original_hash, pdf, renders, first, second, expected):
    require(original.get("schema") == "italian-open-2025-visual-evidence/v1",
            "original packet schema invalid")
    require(original.get("source_sha256") == expected and digest(pdf.read_bytes()) == expected,
            "PDF SHA256 differs from original or expected source")
    require(original.get("source_bytes") == pdf.stat().st_size and
            (expected != SOURCE_SHA256 or pdf.stat().st_size == SOURCE_BYTES),
            "PDF byte count differs from original or pinned source")
    require(len(original.get("pages", [])) == 35 and
            [p.get("page") for p in original["pages"]] == list(range(1, 36)),
            "original packet must have pages 1-35")
    require(original.get("owner_review_status") == "unreviewed" and
            original.get("observation_versions") == [] and
            original.get("counts", {}).get("confirmed_distinct_attempts") is None,
            "original packet already has review, import, or confirmed attempts")
    entries = first + second
    require(len(entries) == EXPECTED_ROWS and all(isinstance(e, dict) for e in entries),
            "maps must contain exactly 138 objects")
    by_position = {(p["page"], row["row"]): row
                   for p in original["pages"][:LAST_PAGE] for row in p["rows"]}
    require(len(by_position) == EXPECTED_ROWS, "original pages 1-18 must have exactly 138 positions")
    entry_by_position = {}
    for entry in entries:
        page, ordinal = entry.get("page"), entry.get("row")
        require(type(page) is int and type(ordinal) is int and
                FIRST_PAGE <= page <= LAST_PAGE and (page, ordinal) in by_position,
                "map entry does not identify a page 1-18 source position")
        require(entry.get("id") == by_position[(page, ordinal)]["id"],
                f"page {page} row {ordinal}: source position ID mismatch")
        require((page, ordinal) not in entry_by_position,
                f"page {page} row {ordinal}: duplicate region entry")
        entry_by_position[(page, ordinal)] = entry
    require(set(entry_by_position) == set(by_position),
            "maps must match every original page 1-18 position once")
    output = deepcopy(original)
    changes = []
    for page in output["pages"][:LAST_PAGE]:
        number = page["page"]
        expected_render = page["render"]["sha256"]
        image = renders / f"page-{number:02}.{page['render']['format']}"
        require(digest(image.read_bytes()) == expected_render,
                f"page {number}: render bytes differ from original packet")
        boxes = []
        for row in page["rows"]:
            key = (number, row["row"])
            entry = entry_by_position[key]
            require(row["citation"]["region"] is None and REGION_NOTE in row["uncertainties"],
                    f"page {number} row {row['row']}: original region state differs")
            before = deepcopy(row)
            new_region = region(entry, expected_render)
            require(not any(collides(new_region["bbox"], prior) for prior in boxes),
                    f"page {number} row {row['row']}: region overlaps another row")
            boxes.append(new_region["bbox"])
            row["citation"]["region"] = new_region
            row["uncertainties"].remove(REGION_NOTE)
            apply_corrections(row, entry)
            require(all(row.get(key) == before.get(key) for key in
                        ("duplicate_of", "duplicate_evidence", "relationship_candidate_of",
                         "relationship_evidence", "relationship_candidate_note")),
                    f"page {number} row {row['row']}: relationship link changed")
            changes.append({"id": row["id"], "page": number, "row": row["row"],
                            "before": before, "after": deepcopy(row),
                            "evidence": entry.get("evidence"),
                            "uncertainty_decision": entry.get("uncertainty_decision")})
    recount(output)
    require(output["pages"][LAST_PAGE:] == original["pages"][LAST_PAGE:],
            "pages 19-35 changed")
    require(output["owner_review_status"] == "unreviewed" and
            output["observation_versions"] == [] and
            output["counts"]["confirmed_distinct_attempts"] is None,
            "review, import, or confirmed attempts changed")
    output["schema"] = "italian-open-2025-visual-evidence/v2"
    output["input_sha256"]["original_packet"] = original_hash
    diff = {"schema": "italian-open-2025-region-diff/v1",
            "original_packet_sha256": original_hash,
            "source_sha256": expected, "rows": changes,
            "counts_before": original["counts"], "counts_after": output["counts"]}
    return output, diff


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("original", "pdf", "renders", "first-map", "second-map", "output", "diff"):
        parser.add_argument("--" + flag, required=True, type=Path)
    parser.add_argument("--expected-sha256", default=SOURCE_SHA256,
                        help="fixture override; production default pins the official PDF")
    args = parser.parse_args(argv)
    try:
        paths = [args.original.resolve(), args.output.resolve(), args.diff.resolve()]
        require(len(set(paths)) == len(paths), "original, output, and diff paths must differ")
        original, original_hash = read(args.original)
        first, first_hash = read(args.first_map)
        second, second_hash = read(args.second_map)
        require(isinstance(first, list) and isinstance(second, list),
                "region maps must be JSON lists")
        output, diff = build(original, original_hash, args.pdf, args.renders,
                             first, second, args.expected_sha256)
        output["input_sha256"]["region_maps"] = [first_hash, second_hash]
        raw, diff_raw = encoded(output), encoded(diff)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.diff.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(raw)
        args.diff.write_bytes(diff_raw)
        print(json.dumps({"packet": str(args.output), "sha256": digest(raw),
                          "diff": str(args.diff), "diff_sha256": digest(diff_raw),
                          "counts": output["counts"]}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Italian Open 2025 region overlay rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
