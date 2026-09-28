#!/usr/bin/env python3
"""Build a private source-bound packet from the 2026 CMAS Depth World Cup scan.

The page TSVs are visual transcriptions. Blank cells remain null. The packet
records printed positions, without importing observations or deciding whether
repeated athletes represent distinct sporting attempts.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys


SOURCE_SHA256 = "1d98a22d67708f4a31ce666b84af1e81aba342c9c52e34a2e2c980cb3f010db5"
COUNTS = (33, 32, 31, 30)
TSV_COLUMNS = ("page", "row", "last_name", "first_name", "country", "discipline",
               "ap_m", "top_time", "dive_time", "rp_m", "card", "record", "points", "note", "uncertain")
RENDER_SHA256 = (
    "67a120d6e0c6757475b5c79c7063bdb9f5c5935d16901546022c615720b5408c",
    "52ddd2c489a960d62478d75f7fde170139322c41ed6eb9af8b52899274963e49",
    "6f282b52cff063a01f63f93599ada6e0038126acae01f5d61ebc939041c3e704",
    "bfd1bab337ce19ecc117ab89b23b41d16283597482f9085fa79e8da4bd1052c8",
)
RAW_COLUMNS = {"last_name": "Last Name", "first_name": "First Name", "country": "Country",
               "discipline": "Discipline", "ap_m": "AP (m)", "top_time": "Top time",
               "dive_time": "Dive Time", "rp_m": "RP (m)", "card": "Card",
               "record": "Record", "points": "Points"}
# Measured from the four official pages rendered independently at 180 dpi.
# Bands enclose the athlete text and may include a few pixels of row borders.
# Source pages have different canvas sizes and were published out of day order.
PAGE_LAYOUT = (
    {"day_raw": "Day 2 : 26/05/2026", "date": "26/05/2026", "iso": "2026-05-26", "size": [2435, 2735], "table": [5, 989, 2429, 2723]},
    {"day_raw": "Day 1 : 24/05/2026", "date": "24/05/2026", "iso": "2026-05-24", "size": [2590, 2545], "table": [13, 998, 2575, 2527]},
    {"day_raw": "Day 3 : 28/05/2026", "date": "28/05/2026", "iso": "2026-05-28", "size": [2415, 2625], "table": [12, 979, 2397, 2607]},
    {"day_raw": "Day 4 : 30/05/2026", "date": "30/05/2026", "iso": "2026-05-30", "size": [2735, 2810], "table": [14, 1062, 2714, 2797]},
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw), sha256(raw)


def source_from_gap(pdf_path, gap_path, source_hash):
    require(re.fullmatch(r"[0-9a-f]{64}", source_hash) is not None,
            "expected SHA256 must be lowercase hexadecimal")
    source_bytes = Path(pdf_path).read_bytes()
    require(sha256(source_bytes) == source_hash, "source SHA256 differs from expected PDF")
    gap, gap_hash = read_json(gap_path)
    matches = [entry for entry in gap["source_gaps"]
               if entry.get("verified_sha256") == source_hash]
    require(len(matches) == 1, "expected one matching source gap")
    entry = matches[0]
    source = entry["source"]
    require(source.get("id") == f"sha256:{source_hash}" and
            source.get("original_sha256") == source_hash, "source gap identity mismatch")
    require(entry.get("bytes", len(source_bytes)) == len(source_bytes),
            "source gap byte count mismatch")
    disposition = entry.get("assessment", {}).get("disposition")
    require(disposition == "unsupported_image_results", "source gap disposition mismatch")
    return source, len(source_bytes), gap_hash, disposition


def read_positions(parts):
    require(len(parts) == 2, "expected exactly two TSV parts")
    positions = []
    hashes = []
    for path in parts:
        data = Path(path).read_bytes()
        hashes.append(sha256(data))
        with Path(path).open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream, delimiter="\t", strict=True)
            require(tuple(reader.fieldnames or ()) == TSV_COLUMNS, "TSV columns mismatch")
            positions.extend(reader)
    return positions, hashes


def checked_renders(paths, expected_hashes):
    require(len(paths) == len(PAGE_LAYOUT) and len(expected_hashes) == len(PAGE_LAYOUT),
            "expected four page renders and SHA256 values")
    renders = []
    for page, (path, expected, layout) in enumerate(zip(paths, expected_hashes, PAGE_LAYOUT), 1):
        require(re.fullmatch(r"[0-9a-f]{64}", expected) is not None,
                f"page {page}: render SHA256 malformed")
        raw = Path(path).read_bytes()
        require(sha256(raw) == expected, f"page {page}: render SHA256 mismatch")
        require(len(raw) >= 24 and raw[:8] == b"\x89PNG\r\n\x1a\n" and raw[12:16] == b"IHDR",
                f"page {page}: render PNG header invalid")
        dimensions = list(struct.unpack(">II", raw[16:24]))
        require(dimensions == layout["size"], f"page {page}: render dimensions mismatch")
        renders.append({"dpi": 180, "pixel_width": dimensions[0],
                        "pixel_height": dimensions[1], "sha256": expected,
                        "format": "PNG", "render_command": "pdftoppm -r 180 -png"})
    return renders


def row_region(page, ordinal, count):
    x1, top, x2, bottom = PAGE_LAYOUT[page - 1]["table"]
    # Broad enough to include glyphs at cell borders, but confined to the row.
    y1 = top + (bottom - top) * (ordinal - 1) // count
    y2 = top + (bottom - top) * ordinal // count
    return {"units": "rendered_px_180dpi", "bbox": [x1, y1, x2, y2]}


def checked_pages(positions, source_hash, renders):
    require(len(positions) == sum(COUNTS), "expected 126 athlete result rows")
    pages = []
    unresolved = 0
    cursor = 0
    for page, count in enumerate(COUNTS, 1):
        rows = positions[cursor:cursor + count]
        cursor += count
        require(all(row.get("page") == str(page) for row in rows),
                f"page {page}: expected {count} consecutive rows")
        require([row.get("row") for row in rows] == [str(i) for i in range(1, count + 1)],
                f"page {page}: row ordinals must be consecutive and unique")
        output_rows = []
        for ordinal, row in enumerate(rows, 1):
            require(set(row) == set(TSV_COLUMNS) and all(value is not None for value in row.values()),
                    f"page {page} row {ordinal}: TSV fields incomplete")
            require(row["last_name"].strip() and row["first_name"].strip(),
                    f"page {page} row {ordinal}: athlete name required")
            fields = {label: row[key] if row[key] != "" else None
                      for key, label in RAW_COLUMNS.items()}
            uncertainty = [row["uncertain"]] if row["uncertain"].strip() else []
            unresolved += bool(uncertainty)
            identity = sha256(f"{source_hash}:page:{page}:row:{ordinal}".encode("ascii"))
            output_rows.append({
                "id": f"source-position:{identity}", "row": ordinal,
                "citation": {"page": page, "region": row_region(page, ordinal, count)},
                "fields": fields, "uncertainties": uncertainty,
                "transcription_notes": [row["note"]] if row["note"].strip() else [],
                "review_status": "unreviewed", "disposition": "visual_source_position",
            })
        layout = PAGE_LAYOUT[page - 1]
        pages.append({"page": page, "heading_raw": "CMAS WORLD CUP DEPTH PHILIPPINES 2026",
                      "day_heading_raw": layout["day_raw"],
                      "event_date_printed": layout["date"],
                      "event_date": layout["iso"],
                      "column_headings_raw": list(RAW_COLUMNS.values()),
                      "render": renders[page - 1],
                      "disposition": "day_result_table", "rows": output_rows})
    return pages, unresolved


def build_supplement(pdf_path, gap_path, parts, renders, expected_sha256=SOURCE_SHA256,
                     expected_render_sha256=RENDER_SHA256):
    source, source_bytes, gap_hash, prior_disposition = source_from_gap(
        pdf_path, gap_path, expected_sha256)
    positions, part_hashes = read_positions(parts)
    verified_renders = checked_renders(renders, expected_render_sha256)
    pages, unresolved = checked_pages(positions, expected_sha256, verified_renders)
    return {
        "schema": "cmas-worldcup-2026-visual-evidence/v1", "source": source,
        "source_sha256": expected_sha256, "source_bytes": source_bytes,
        "input_sha256": {"gap_supplement": gap_hash, "page_parts": part_hashes},
        "source_relationship_status": "unresolved",
        "gap_reconciliation": {"status": "visual_positions_reconciled",
                               "prior_disposition": prior_disposition,
                               "accounted_source_positions": 126,
                               "unresolved_positions": unresolved,
                               "imported_observation_versions": 0,
                               "owner_review_status": "unreviewed"},
        "pages": pages,
        "counts": {"source_objects": 1, "source_positions": 126,
                   "visual_evidence_rows": 126, "imported_observation_versions": 0,
                   "unresolved_positions": unresolved,
                   "confirmed_distinct_attempts": None},
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--gap", required=True, type=Path)
    parser.add_argument("--part", required=True, action="append", type=Path)
    parser.add_argument("--render", required=True, action="append", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-sha256", default=SOURCE_SHA256,
                        help="test fixture override; production default pins official bytes")
    parser.add_argument("--expected-render-sha256", action="append",
                        help="test fixture override; production pins four official render hashes")
    args = parser.parse_args(argv)
    try:
        destination = args.output.resolve()
        repo = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(repo):
            ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "-q", str(destination)],
                                     check=False)
            require(ignored.returncode == 0, "private output inside repository must be Git ignored")
        require(args.expected_sha256 != SOURCE_SHA256 or not args.expected_render_sha256,
                "official source requires pinned render SHA256 values")
        document = build_supplement(args.pdf, args.gap, args.part, args.render,
                                    args.expected_sha256,
                                    args.expected_render_sha256 or RENDER_SHA256)
        raw = (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
        print(json.dumps({"output": str(destination), "sha256": sha256(raw),
                          "source_positions": 126,
                          "unresolved_positions": document["counts"]["unresolved_positions"]},
                         sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError, csv.Error) as error:
        print(f"CMAS World Cup supplement rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
