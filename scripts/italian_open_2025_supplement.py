#!/usr/bin/env python3
"""Validate a 35-page visual census and write a private, unreviewed evidence packet.

Page ledgers are private JSON. Printed cells remain raw. A source row is not a
confirmed sporting attempt, and this tool does not import observations.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


SOURCE_SHA256 = "e8ca68825e29d44a1b13127ab2cab55f6db534b15ae26ff005f13a1ca8ec9d44"
SOURCE_BYTES = 13755700
SOURCE_URL = "https://www.cmas.org/document/2025,-italian-open-outdoor-freediving-championship/download.html"
REGION_UNCERTAINTY = "Exact row region not bounded in page render"
PAGE_COUNT = 35
PAGE_DISPOSITIONS = {"result_table", "aggregate", "summary", "blank", "unresolved"}
ROW_DISPOSITIONS = {"candidate_result", "aggregate", "summary", "duplicate_render", "unresolved"}
FORBIDDEN_INPUT_KEYS = {"attempt_id", "confirmed_distinct_attempts", "normalized_result",
                        "observation_versions", "owner_review_status", "review_status"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw), sha256(raw)


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def raw_or_null(value):
    return value is None or isinstance(value, str)


def no_inferences(value):
    if isinstance(value, dict):
        require(not FORBIDDEN_INPUT_KEYS.intersection(value),
                "manifest contains unsupported inferred or review fields")
        for child in value.values():
            no_inferences(child)
    elif isinstance(value, list):
        for child in value:
            no_inferences(child)


def checked_pdf(path, expected):
    require(re.fullmatch(r"[0-9a-f]{64}", expected) is not None,
            "expected SHA256 must be lowercase hexadecimal")
    raw = Path(path).read_bytes()
    require(sha256(raw) == expected, "source SHA256 differs from expected PDF")
    if expected == SOURCE_SHA256:
        require(len(raw) == SOURCE_BYTES, "pinned source byte count mismatch")
    info = subprocess.run(["pdfinfo", str(path)], text=True, capture_output=True,
                          check=False)
    require(info.returncode == 0, "pdfinfo could not read source PDF")
    match = re.search(r"^Pages:\s+(\d+)\s*$", info.stdout, re.MULTILINE)
    require(match is not None and int(match.group(1)) == PAGE_COUNT,
            "source PDF must contain exactly 35 pages")
    return len(raw)


def checked_render(path, number):
    image = Path(path)
    raw = image.read_bytes()
    dims = subprocess.run(["identify", "-format", "%w %h", str(image)],
                          text=True, capture_output=True, check=False)
    require(dims.returncode == 0, f"page {number}: render is not a readable image")
    match = re.fullmatch(r"(\d+) (\d+)", dims.stdout)
    require(match is not None, f"page {number}: render dimensions invalid")
    width, height = map(int, match.groups())
    require(width > 0 and height > 0, f"page {number}: render dimensions invalid")
    return {"sha256": sha256(raw), "pixel_width": width, "pixel_height": height,
            "format": image.suffix.lower().lstrip(".")}


def checked_rows(page, render, source_hash):
    number = page["page"]
    rows = page.get("rows")
    count = page.get("visual_row_count")
    require(isinstance(rows, list), f"page {number}: rows list required")
    require((type(count) is int and count >= 0 and len(rows) == count) or
            (count is None and page["disposition"] == "unresolved"),
            f"page {number}: visual row count mismatch")
    require([row.get("row") for row in rows] == list(range(1, len(rows) + 1)),
            f"page {number}: row ordinals must be consecutive and unique")
    output = []
    for row in rows:
        ordinal = row["row"]
        disposition = row.get("disposition")
        require(disposition in ROW_DISPOSITIONS,
                f"page {number} row {ordinal}: row disposition invalid")
        uncertainties = row.get("uncertainties")
        require(isinstance(uncertainties, list) and
                all(isinstance(item, str) and item.strip() for item in uncertainties),
                f"page {number} row {ordinal}: uncertainties required")
        bbox = row.get("bbox")
        if bbox is None:
            uncertainties = uncertainties + [REGION_UNCERTAINTY]
            region = None
        else:
            require(isinstance(bbox, list) and len(bbox) == 4 and
                    all(type(value) is int for value in bbox) and
                    0 <= bbox[0] < bbox[2] <= render["pixel_width"] and
                    0 <= bbox[1] < bbox[3] <= render["pixel_height"],
                    f"page {number} row {ordinal}: bbox invalid")
            region = {"units": "render_pixels", "bbox": bbox}
        fields = row.get("fields_raw")
        columns = page["columns_raw"]
        require(isinstance(fields, dict) and len(fields) == len(columns) and
                all(isinstance(key, str) and key.strip() for key in fields) and
                all(raw_or_null(item) for item in fields.values()),
                f"page {number} row {ordinal}: fields_raw must align with printed columns")
        require(any(value is not None for value in fields.values()) or uncertainties,
                f"page {number} row {ordinal}: blank raw fields need uncertainty")
        for key in ("status_raw", "penalty_raw", "notes_raw"):
            require(key in row and raw_or_null(row[key]),
                    f"page {number} row {ordinal}: {key} required as raw text or null")
        identity = sha256(f"{source_hash}:page:{number}:row:{ordinal}".encode())
        item = {"id": f"source-position:{identity}", "row": ordinal,
                "disposition": disposition,
                "citation": {"source_sha256": source_hash, "page": number,
                             "region": region},
                "fields_raw": fields, "status_raw": row["status_raw"],
                "penalty_raw": row["penalty_raw"], "notes_raw": row["notes_raw"],
                "uncertainties": uncertainties, "review_status": "unreviewed"}
        for key in ("duplicate_of", "duplicate_evidence", "relationship_candidate_of",
                    "relationship_evidence"):
            if key in row:
                item[key] = row[key]
        output.append(item)
    return output


def checked_pages(paths, source_hash):
    pages, part_hashes, global_uncertainties = [], [], []
    for path in paths:
        part, digest = read_json(path)
        no_inferences(part)
        require(isinstance(part, dict) and part.get("source_sha256") == source_hash and
                isinstance(part.get("pages"), list),
                "part source SHA256 or pages list invalid")
        notes = part.get("global_uncertainties", [])
        require(isinstance(notes, list) and
                all(isinstance(note, str) and note.strip() for note in notes),
                "part global_uncertainties must be a text list")
        pages.extend(part["pages"])
        part_hashes.append(digest)
        global_uncertainties.extend(notes)
    require(all(isinstance(page, dict) for page in pages) and
            [page.get("page") for page in pages] == list(range(1, PAGE_COUNT + 1)),
            "parts must cover pages 1 through 35 in order")
    output = []
    for page in pages:
        number = page["page"]
        disposition = page.get("disposition")
        require(disposition in PAGE_DISPOSITIONS,
                f"page {number}: page disposition invalid")
        heading = page.get("heading_raw")
        require(raw_or_null(heading), f"page {number}: heading_raw invalid")
        for key in ("event_date_raw", "session_raw", "category_raw", "discipline_raw"):
            require(key in page and raw_or_null(page[key]),
                    f"page {number}: {key} required as raw text or null")
        columns = page.get("columns_raw")
        require(isinstance(columns, list) and len(columns) == len(set(columns)) and
                all(isinstance(item, str) and item.strip() for item in columns),
                f"page {number}: columns_raw invalid")
        uncertainties = page.get("uncertainties")
        require(isinstance(uncertainties, list) and
                all(isinstance(item, str) and item.strip() for item in uncertainties) and
                (disposition != "unresolved" or bool(uncertainties)),
                f"page {number}: unresolved page needs explicit uncertainty")
        render = checked_render(page.get("render_path", page.get("page_render_path")), number)
        rows = checked_rows(page, render, source_hash)
        field_key_order = list(page["rows"][0]["fields_raw"]) if rows else []
        require(all(list(raw["fields_raw"]) == field_key_order for raw in page["rows"]),
                f"page {number}: fields_raw key order must align across rows")
        require(disposition != "blank" or not rows,
                f"page {number}: blank page cannot contain rows")
        require(all(row["disposition"] in ({"candidate_result", "duplicate_render", "unresolved"}
                if disposition == "result_table" else
                {"aggregate", "duplicate_render", "unresolved"} if disposition == "aggregate" else
                {"summary", "duplicate_render", "unresolved"} if disposition == "summary" else
                {"unresolved"}) for row in rows),
                f"page {number}: row disposition conflicts with page")
        output.append({"page": number, "disposition": disposition, "render": render,
                       "heading_raw": heading, "event_raw": page.get("event_raw"),
                       "event_date_raw": page["event_date_raw"],
                       "session_raw": page["session_raw"],
                       "category_raw": page["category_raw"],
                       "discipline_raw": page["discipline_raw"],
                       "columns_raw": columns, "field_key_order": field_key_order,
                       "visual_row_count": page["visual_row_count"],
                       "rows": rows, "uncertainties": uncertainties})
    by_position = {(page["page"], row["row"]): (page, row)
                   for page in output for row in page["rows"]}
    for page in output:
        for row in page["rows"]:
            reference = row.get("duplicate_of")
            if row["disposition"] != "duplicate_render":
                candidate = row.get("relationship_candidate_of", reference)
                evidence = row.get("relationship_evidence", row.get("duplicate_evidence"))
                if candidate is not None:
                    if isinstance(candidate, str):
                        match = re.fullmatch(r"page (\d+) row (\d+)", candidate)
                        if match:
                            candidate = {"page": int(match.group(1)),
                                         "row": int(match.group(2))}
                        else:
                            require(candidate.strip() and isinstance(evidence, str) and
                                    evidence.strip(),
                                    f"page {page['page']} row {row['row']}: relationship note invalid")
                            row["relationship_candidate_note"] = candidate
                            candidate = None
                    if candidate is not None:
                        require(isinstance(candidate, dict) and
                                set(candidate) == {"page", "row"} and
                                all(type(candidate[key]) is int and candidate[key] > 0
                                    for key in ("page", "row")) and
                                (candidate["page"], candidate["row"]) in by_position and
                                (candidate["page"], candidate["row"]) !=
                                (page["page"], row["row"]) and
                                isinstance(evidence, str) and bool(evidence.strip()),
                                f"page {page['page']} row {row['row']}: relationship candidate invalid")
                        row["relationship_candidate_of"] = candidate
                    row["relationship_evidence"] = evidence
                    row.pop("duplicate_of", None)
                    row.pop("duplicate_evidence", None)
                else:
                    require("duplicate_evidence" not in row and
                            "relationship_evidence" not in row,
                            f"page {page['page']} row {row['row']}: unexpected relationship evidence")
                continue
            require("relationship_candidate_of" not in row and
                    "relationship_evidence" not in row,
                    f"page {page['page']} row {row['row']}: exact duplicate cannot be candidate")
            require(isinstance(reference, dict) and set(reference) == {"page", "row"} and
                    type(reference["page"]) is int and type(reference["row"]) is int and
                    reference["page"] < page["page"] and reference["row"] > 0,
                    f"page {page['page']} row {row['row']}: invalid duplicate_of")
            prior_pair = by_position.get((reference["page"], reference["row"]))
            prior_page, prior = prior_pair if prior_pair else (None, None)
            require(prior is not None and prior_page["disposition"] == page["disposition"] and
                    prior_page["render"]["sha256"] == page["render"]["sha256"] and
                    prior["disposition"] not in
                    {"duplicate_render", "unresolved"} and
                    all(row[key] == prior[key] for key in
                        ("fields_raw", "status_raw", "penalty_raw", "notes_raw")) and
                    isinstance(row.get("duplicate_evidence"), str) and
                    bool(row["duplicate_evidence"].strip()),
                    f"page {page['page']} row {row['row']}: duplicate_of must cite matching earlier row")
    return output, part_hashes, global_uncertainties


def build(pdf, paths, expected):
    source_bytes = checked_pdf(pdf, expected)
    pages, part_hashes, global_uncertainties = checked_pages(paths, expected)
    rows = [row for page in pages for row in page["rows"]]
    counts = {"source_objects": 1,
              "candidate_result_positions": sum(r["disposition"] == "candidate_result" for r in rows),
              "aggregate_rows_excluded": sum(r["disposition"] == "aggregate" for r in rows),
              "summary_rows_excluded": sum(r["disposition"] == "summary" for r in rows),
              "duplicate_rendered_rows": sum(r["disposition"] == "duplicate_render" for r in rows),
              "relationship_candidate_links": sum("relationship_candidate_of" in r for r in rows),
              "relationship_candidate_notes": sum("relationship_candidate_note" in r for r in rows),
              "unresolved_positions": sum(r["disposition"] == "unresolved" for r in rows),
              "unresolved_pages": sum(p["disposition"] == "unresolved" for p in pages),
              "rows_with_uncertainties": sum(bool(r["uncertainties"]) for r in rows),
              "rows_with_field_uncertainties": sum(
                  any(note != REGION_UNCERTAINTY for note in r["uncertainties"])
                  for r in rows),
              "unresolved_field_notes": sum(
                  note != REGION_UNCERTAINTY for r in rows for note in r["uncertainties"]),
              "rows_missing_region": sum(r["citation"]["region"] is None for r in rows),
              "imported_observation_versions": 0,
              "confirmed_distinct_attempts": None}
    incomplete = (counts["unresolved_pages"] or counts["unresolved_positions"] or
                  counts["rows_missing_region"] or
                  any(page["visual_row_count"] is None for page in pages))
    return {"schema": "italian-open-2025-visual-evidence/v1",
            "source_sha256": expected, "source_bytes": source_bytes,
            "source_url": SOURCE_URL,
            "retrieval_metadata": {"basis": "reused_verified_B07_bytes",
                                   "refetched_in_this_batch": False,
                                   "current_publisher_revision": "unknown"},
            "event_date": None,
            "event_date_printed_evidence": [
                {"page": page["page"], "raw": page["event_date_raw"]}
                for page in pages if page["event_date_raw"] is not None],
            "input_sha256": {"page_parts": part_hashes},
            "global_uncertainties": global_uncertainties,
            "owner_review_status": "unreviewed",
            "source_relationship_status": "unresolved",
            "gap_reconciliation": {"status": "visual_census_incomplete" if incomplete else
                                   "visual_positions_reconciled",
                                   "owner_review_status": "unreviewed"},
            "pages": pages,
            "candidate_result_appearances": [r for r in rows if
                                             r["disposition"] == "candidate_result"],
            "observation_versions": [], "counts": counts}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--part", required=True, action="append", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-sha256", default=SOURCE_SHA256,
                        help="test fixture override; production default pins the original PDF")
    args = parser.parse_args(argv)
    try:
        destination = args.output.resolve()
        root = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(root):
            ignored = subprocess.run(["git", "-C", str(root), "check-ignore", "-q",
                                      str(destination)], check=False)
            require(ignored.returncode == 0,
                    "private output inside repository must be Git ignored")
        document = build(args.pdf, args.part, args.expected_sha256)
        raw = encoded(document)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
        print(json.dumps({"output": str(destination), "sha256": sha256(raw),
                          "counts": document["counts"]}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Italian Open 2025 supplement rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
