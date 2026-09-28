#!/usr/bin/env python3
"""Validate 24 visually audited Liberamente pages and export a private packet."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
from urllib.parse import unquote


SOURCE_SHA256 = "dd2589b800ae444c9c505c52f2b3313653ef0e1974663c131b56a721b97a2bc7"
SOURCE_BYTES = 5340196
EVENT_ID = 11493
EVENT_DATE = "2026-07-11"
PAGE_COUNT = 24
RENDER_WIDTH = 1520
RENDER_HEIGHT = 1075


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw), sha256(raw)


def encoded(document):
    return (json.dumps(document, sort_keys=True, ensure_ascii=False, indent=2) + "\n").encode()


def checked_source(pdf_path, gap_path, expected_sha256):
    require(re.fullmatch(r"[0-9a-f]{64}", expected_sha256),
            "expected SHA256 must be lowercase hexadecimal")
    pdf = Path(pdf_path).read_bytes()
    require(sha256(pdf) == expected_sha256, "source SHA256 differs from expected PDF")
    if expected_sha256 == SOURCE_SHA256:
        require(len(pdf) == SOURCE_BYTES, "pinned source byte count mismatch")
    checked_pdf_geometry(pdf_path)
    gap, gap_hash = read_json(gap_path)
    matches = [entry for entry in gap["source_gaps"]
               if entry.get("verified_sha256") == expected_sha256]
    require(len(matches) == 1, "expected one matching source gap")
    entry = matches[0]
    source = entry["source"]
    require(source.get("id") == "sha256:" + expected_sha256 and
            source.get("original_sha256") == expected_sha256 and
            entry.get("bytes") == len(pdf), "source gap identity or byte receipt mismatch")
    require(isinstance(source.get("final_url"), str) and source["final_url"],
            "source gap final URL required")
    require(entry.get("assessment", {}).get("disposition") == "unsupported_image_results",
            "source gap is not image-only results")
    return source, gap_hash, len(pdf)


def checked_pdf_geometry(pdf_path):
    info = subprocess.run(["pdfinfo", "-f", "1", "-l", str(PAGE_COUNT),
                           str(pdf_path)], capture_output=True, text=True, check=False)
    require(info.returncode == 0, "pdfinfo could not read source PDF")
    match = re.search(r"^Pages:\s+(\d+)\s*$", info.stdout, re.MULTILINE)
    require(match is not None and int(match.group(1)) == PAGE_COUNT,
            "source PDF must contain exactly 24 pages")
    sizes = re.findall(r"^Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+) pts", info.stdout,
                       re.MULTILINE)
    rotations = re.findall(r"^Page\s+(\d+) rot:\s+(\d+)", info.stdout,
                           re.MULTILINE)
    require(len(sizes) == PAGE_COUNT and len(rotations) == PAGE_COUNT and
            [int(row[0]) for row in sizes] == list(range(1, PAGE_COUNT + 1)) and
            [int(row[0]) for row in rotations] == list(range(1, PAGE_COUNT + 1)) and
            all(float(width) == 841.68 and float(height) == 595.2
                for _, width, height in sizes) and
            all(int(rotation) == 0 for _, rotation in rotations),
            "source PDF pages must share the audited upright A4 landscape geometry")
    with tempfile.TemporaryDirectory() as root:
        prefix = Path(root) / "page-1"
        render = subprocess.run(["pdftoppm", "-f", "1", "-l", "1", "-r", "130",
                                 "-png", "-singlefile", str(pdf_path), str(prefix)],
                                capture_output=True, check=False)
        require(render.returncode == 0, "could not render source PDF at 130dpi")
        header = prefix.with_suffix(".png").read_bytes()[:24]
        require(header[:8] == b"\x89PNG\r\n\x1a\n" and len(header) == 24 and
                struct.unpack(">II", header[16:24]) == (RENDER_WIDTH, RENDER_HEIGHT),
                "source PDF 130dpi render dimensions differ from 1520x1075")


def checked_card(card_path, source, production):
    ledger, ledger_hash = read_json(card_path)
    matching = [card for card in ledger["cards"] if
                unquote(source["final_url"]) in
                {unquote(url) for url in card.get("result_urls", [])}]
    require(len(matching) <= 1, "multiple calendar cards link exact source URL")
    if production:
        require(len(matching) == 1 and matching[0].get("event_id") == EVENT_ID and
                matching[0].get("date") == EVENT_DATE,
                "calendar card 11493 date/source URL mismatch")
    if matching:
        card = matching[0]
        require(isinstance(card.get("title"), str) and card["title"].strip() and
                isinstance(card.get("date"), str) and card["date"].strip(),
                "calendar card title/date missing")
        return {"event_id": card["event_id"], "title": card["title"],
                "date": card["date"]}, ledger_hash
    return None, ledger_hash


def checked_rows(page, source_sha):
    number = page["page"]
    rows = page.get("rows")
    count = page.get("visual_row_count")
    require(type(count) is int and count >= 0 and isinstance(rows, list) and len(rows) == count,
            f"page {number}: visual row count mismatch")
    require([row.get("row") for row in rows] == list(range(1, count + 1)),
            f"page {number}: row ordinals must be consecutive")
    columns = page["columns_raw"]
    render = page["render"]
    kind = page["page_kind"]
    output = []
    for row in rows:
        ordinal = row["row"]
        bbox = row.get("bbox")
        require(isinstance(bbox, list) and len(bbox) == 4 and
                all(type(value) is int for value in bbox) and
                0 <= bbox[0] < bbox[2] <= render["pixel_width"] and
                0 <= bbox[1] < bbox[3] <= render["pixel_height"],
                f"page {number} row {ordinal}: bbox exceeds render or is invalid")
        fields = row.get("fields_raw")
        require(isinstance(fields, dict) and set(fields) == set(columns) and
                all(value is None or isinstance(value, str) for value in fields.values()),
                f"page {number} row {ordinal}: fields_raw must cover printed columns")
        require(all(key in row and (row[key] is None or isinstance(row[key], str))
                    for key in ("status_raw", "penalty_raw", "notes_raw")),
                f"page {number} row {ordinal}: raw status, penalty and notes required")
        uncertainties = row.get("uncertainties")
        require(isinstance(uncertainties, list) and
                all(isinstance(item, str) and item.strip() for item in uncertainties),
                f"page {number} row {ordinal}: explicit uncertainties required")
        identity = sha256(f"{source_sha}:page:{number}:{kind}:row:{ordinal}".encode())
        item = {"id": f"{kind}-position:{identity}", "row": ordinal, "kind": kind,
                "citation": {"page": number, "region": {
                    "units": "upright_png_pixels_130dpi", "bbox": bbox}},
                "fields_raw": fields, "status_raw": row["status_raw"],
                "penalty_raw": row["penalty_raw"], "notes_raw": row["notes_raw"],
                "uncertainties": uncertainties, "review_status": "unreviewed"}
        for key in ("duplicate_of", "duplicate_evidence", "annotations_raw"):
            if key in row:
                item[key] = row[key]
        output.append(item)
    return output


def checked_pages(part_paths, source_sha):
    pages = []
    hashes = []
    for path in part_paths:
        part, part_hash = read_json(path)
        require(part.get("source_sha256") == source_sha,
                "part source SHA256 differs from verified PDF")
        require(isinstance(part.get("pages"), list), "part pages list required")
        pages.extend(part["pages"])
        hashes.append(part_hash)
    require([page.get("page") for page in pages] == list(range(1, PAGE_COUNT + 1)),
            "parts must cover pages 1 through 24 in order")
    output = []
    for page in pages:
        number = page["page"]
        expected_kind = "aggregate" if number <= 2 else "blank" if number == 16 else "athlete"
        require(page.get("page_kind") == expected_kind,
                f"page {number}: page kind differs from visual census")
        render = page.get("render")
        require(isinstance(render, dict) and render.get("dpi") == 130 and
                render.get("pixel_width") == RENDER_WIDTH and
                render.get("pixel_height") == RENDER_HEIGHT,
                f"page {number}: 130dpi render dimensions must be 1520x1075")
        heading = page.get("heading_raw")
        require(heading is None if expected_kind == "blank" else
                isinstance(heading, str) and bool(heading.strip()),
                f"page {number}: heading_raw invalid")
        for key in ("event_date_raw", "category_raw", "discipline_raw"):
            require(key in page and (page[key] is None or isinstance(page[key], str)),
                    f"page {number}: {key} required as raw text or null")
        columns = page.get("columns_raw")
        require(isinstance(columns, list) and len(columns) == len(set(columns)) and
                all(isinstance(item, str) and item.strip() for item in columns) and
                (not columns if expected_kind == "blank" else bool(columns)),
                f"page {number}: printed columns invalid")
        rows = checked_rows(page, source_sha)
        require(expected_kind != "blank" or not rows,
                "blank page must have zero rows")
        extra = {key: value for key, value in page.items() if key not in {
            "page", "page_kind", "render", "heading_raw", "event_date_raw",
            "category_raw", "discipline_raw", "columns_raw", "visual_row_count", "rows"}}
        output.append({"page": number, "page_kind": expected_kind,
            "render": render, "heading_raw": heading,
            "event_date_printed": page["event_date_raw"],
            "category_raw": page["category_raw"],
            "discipline_raw": page["discipline_raw"], "columns_raw": columns,
            "visual_row_count": page["visual_row_count"], "rows": rows,
            "raw_page_metadata": extra})
    by_position = {(page["page"], row["row"]): row
                   for page in output for row in page["rows"]}
    duplicates = {"athlete": 0, "aggregate": 0}
    for page in output:
        for row in page["rows"]:
            reference = row.get("duplicate_of")
            if reference is None:
                continue
            require(isinstance(reference, dict) and set(reference) == {"page", "row"} and
                    type(reference["page"]) is int and type(reference["row"]) is int and
                    reference["page"] < page["page"] and reference["row"] > 0,
                    f"page {page['page']} row {row['row']}: invalid duplicate_of")
            previous = by_position.get((reference["page"], reference["row"]))
            require(previous is not None and "duplicate_of" not in previous and
                    row["kind"] == previous["kind"] and
                    all(row[key] == previous[key] for key in
                        ("fields_raw", "status_raw", "penalty_raw", "notes_raw")) and
                    isinstance(row.get("duplicate_evidence"), str) and
                    bool(row["duplicate_evidence"].strip()),
                    f"page {page['page']} row {row['row']}: duplicate_of must cite matching earlier row")
            duplicates[row["kind"]] += 1
    return output, hashes, duplicates


def build_supplement(pdf_path, gap_path, parts, card_path, expected_sha256=SOURCE_SHA256):
    source, gap_hash, source_bytes = checked_source(pdf_path, gap_path, expected_sha256)
    card, card_hash = checked_card(card_path, source, expected_sha256 == SOURCE_SHA256)
    pages, part_hashes, duplicates = checked_pages(parts, expected_sha256)
    athletes = sum(len(page["rows"]) for page in pages if page["page_kind"] == "athlete")
    aggregates = sum(len(page["rows"]) for page in pages if page["page_kind"] == "aggregate")
    unresolved = sum(bool(row["uncertainties"]) for page in pages for row in page["rows"])
    unresolved_fields = sum(len(row["uncertainties"]) for page in pages for row in page["rows"])
    counts = {"source_objects": 1, "source_positions": athletes,
              "athlete_row_appearances": athletes, "visual_evidence_rows": athletes,
              "aggregate_rows_excluded": aggregates, "aggregate_row_appearances": aggregates,
              "duplicate_aggregate_rows": duplicates["aggregate"],
              "duplicate_rendered_rows": duplicates["athlete"],
              "nonduplicate_candidate_positions": athletes - duplicates["athlete"],
              "imported_observation_versions": 0, "unresolved_positions": unresolved,
              "unresolved_fields": unresolved_fields, "confirmed_distinct_attempts": None}
    appearances = [{**row, "page": page["page"]}
                   for page in pages if page["page_kind"] == "athlete"
                   for row in page["rows"]]
    relationship = ([{"event_id": card["event_id"],
        "relationship_type": "calendar_event_link",
        "basis": "calendar card links exact source URL", "state": "candidate",
        "cross_source_equivalence": "unassessed"}] if card else [])
    return {"schema": "liberamente-visual-evidence/v1", "source": source,
        "source_objects": [source],
        "source_sha256": expected_sha256, "source_bytes": source_bytes,
        "source_gap_disposition": "unsupported_image_results",
        "source_relationship_status": "unresolved",
        "source_relationship_candidates": relationship,
        "source_relationship_gaps": source.get("provenance_gaps", []),
        "input_sha256": {"gap_supplement": gap_hash, "page_parts": part_hashes,
                         "card_ledger": card_hash},
        "event_card_calendar": card,
        "event_date_calendar": card["date"] if card else None,
        "event_date_printed_evidence": [
            {"page": page["page"], "raw": page["event_date_printed"]}
            for page in pages if page["event_date_printed"] is not None],
        "owner_review_status": "unreviewed",
        "gap_reconciliation": {"status": "visual_positions_reconciled",
            "prior_disposition": "unsupported_image_results",
            "accounted_source_positions": athletes,
            "aggregate_rows_excluded": aggregates,
            "duplicate_rendered_rows": duplicates["athlete"],
            "nonduplicate_candidate_positions": athletes - duplicates["athlete"],
            "unresolved_positions": unresolved, "unresolved_fields": unresolved_fields,
            "imported_observation_versions": 0, "owner_review_status": "unreviewed"},
        "pages": pages, "athlete_appearances": appearances,
        "nonduplicate_candidate_appearances": [row["id"] for row in appearances
                                               if "duplicate_of" not in row],
        "observation_versions": [], "counts": counts}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("pdf", "gap", "card-ledger", "output"):
        parser.add_argument("--" + flag, required=True, type=Path)
    parser.add_argument("--part", required=True, action="append", type=Path)
    parser.add_argument("--expected-sha256", default=SOURCE_SHA256,
                        help="fixture override; production default pins original PDF")
    args = parser.parse_args(argv)
    try:
        require(len(args.part) == 3, "expected exactly three page parts")
        destination = args.output.resolve()
        root = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(root):
            ignored = subprocess.run(["git", "-C", str(root), "check-ignore", "-q",
                                      str(destination)], check=False)
            require(ignored.returncode == 0,
                    "private output inside repository must be Git ignored")
        document = build_supplement(args.pdf, args.gap, args.part,
                                    args.card_ledger, args.expected_sha256)
        raw = encoded(document)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
        print(json.dumps({"output": str(destination), "sha256": sha256(raw),
            "source_positions": document["counts"]["source_positions"],
            "aggregate_rows_excluded": document["counts"]["aggregate_rows_excluded"]},
            sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Liberamente supplement rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
