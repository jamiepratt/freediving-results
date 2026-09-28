#!/usr/bin/env python3
"""Validate and export a private, unreviewed visual packet for Cagliari 2026."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote

SOURCE_SHA256 = "cc5764833f444121c83557cc66570ccdfbcb3734bc959fdbfbda5953963a712b"
EVENT_IDS = (9372, 9506)
EVENT_DATE = "2026-01-18"
PAGE_COUNT = 29
RENDER_SIZE = [2572, 1819]


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw), digest(raw)


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n").encode()


def checked_source(pdf_path, gap_path, expected_sha256):
    require(re.fullmatch(r"[0-9a-f]{64}", expected_sha256), "expected SHA256 must be lowercase hexadecimal")
    pdf = Path(pdf_path).read_bytes()
    require(digest(pdf) == expected_sha256, "source SHA256 differs from expected PDF")
    gap, gap_sha = read_json(gap_path)
    matches = [x for x in gap["source_gaps"] if x.get("verified_sha256") == expected_sha256]
    require(len(matches) == 1, "expected one matching source gap")
    entry = matches[0]
    source = entry["source"]
    require(source.get("id") == "sha256:" + expected_sha256 and
            source.get("original_sha256") == expected_sha256 and
            entry.get("bytes") == len(pdf), "source gap identity or byte receipt mismatch")
    require(source.get("final_url"), "source gap final URL required")
    require(entry.get("assessment", {}).get("disposition") == "unsupported_image_results",
            "source gap is not image-only results")
    return source, gap_sha, len(pdf), entry["assessment"]


def checked_cards(card_path, source):
    ledger, ledger_sha = read_json(card_path)
    out = []
    for event_id in EVENT_IDS:
        matches = [x for x in ledger["cards"] if x.get("event_id") == event_id]
        require(len(matches) == 1, f"expected one calendar card {event_id}")
        card = matches[0]
        require(card.get("date") == EVENT_DATE, f"calendar card {event_id} date mismatch")
        require(isinstance(card.get("title"), str) and card["title"].strip(),
                f"calendar card {event_id} title missing")
        require(unquote(source["final_url"]) in
                {unquote(url) for url in card.get("result_urls", [])},
                f"calendar card {event_id} does not link exact source PDF")
        out.append({"event_id": event_id, "title": card["title"], "date": card["date"]})
    return out, ledger_sha


def checked_rows(rows, page, columns, page_kind, source_sha):
    require(isinstance(rows, list), f"page {page}: rows list required")
    require([x.get("row_ordinal") for x in rows] == list(range(1, len(rows) + 1)),
            f"page {page}: row ordinals must be consecutive")
    out = []
    for row in rows:
        ordinal = row["row_ordinal"]
        kind = row.get("kind")
        require(kind == ("aggregate" if page_kind == "club" else "athlete"),
                f"page {page} row {ordinal}: kind inconsistent with page")
        bbox = row.get("bbox")
        require(isinstance(bbox, list) and len(bbox) == 4 and
                all(type(v) is int for v in bbox) and
                0 <= bbox[0] < bbox[2] <= RENDER_SIZE[0] and
                0 <= bbox[1] < bbox[3] <= RENDER_SIZE[1],
                f"page {page} row {ordinal}: bbox must cite a region within render")
        fields = row.get("fields_raw")
        require(isinstance(fields, dict) and set(fields) == set(columns) and
                all(value is None or isinstance(value, str) for value in fields.values()),
                f"page {page} row {ordinal}: fields must match printed columns")
        require(all(key in row and (row[key] is None or isinstance(row[key], str))
                    for key in ("status_raw", "penalty_raw", "notes_raw")),
                f"page {page} row {ordinal}: raw status, penalty and notes required")
        uncertainty = row.get("uncertainties")
        require(isinstance(uncertainty, list) and all(isinstance(x, str) and x.strip()
                for x in uncertainty), f"page {page} row {ordinal}: explicit uncertainties required")
        row_id = digest(f"{source_sha}:page:{page}:{kind}:row:{ordinal}".encode())
        item = {"id": f"{kind}-position:{row_id}", "row": ordinal, "kind": kind,
                "citation": {"page": page, "region": {"units": "upright_png_pixels_220dpi", "bbox": bbox}},
                "fields_raw": fields, "status_raw": row["status_raw"],
                "penalty_raw": row["penalty_raw"], "notes_raw": row["notes_raw"],
                "uncertainties": uncertainty, "review_status": "unreviewed"}
        if "duplicate_of" in row:
            item["duplicate_of"] = row["duplicate_of"]
        if "duplicate_evidence" in row:
            require(isinstance(row["duplicate_evidence"], str) and row["duplicate_evidence"].strip(),
                    f"page {page} row {ordinal}: duplicate_evidence must be text")
            item["duplicate_evidence"] = row["duplicate_evidence"]
        out.append(item)
    return out


def checked_pages(part_paths, source_sha):
    pages = []
    part_hashes = []
    for path in part_paths:
        part, part_sha = read_json(path)
        require(part.get("source_pdf_sha256", part.get("source_sha256")) == source_sha,
                "part source SHA256 differs from verified PDF")
        require(part.get("render_dpi") == 220 and
                part.get("render_size_px", part.get("render_dimensions_px")) == RENDER_SIZE,
                "part render must be upright 2572x1819 at 220 dpi")
        require(isinstance(part.get("pages"), list), "part pages list required")
        pages.extend(part["pages"])
        part_hashes.append(part_sha)
    require([p.get("page") for p in pages] == list(range(1, PAGE_COUNT + 1)),
            "parts must cover pages 1 through 29 in order")
    output = []
    for page in pages:
        number = page["page"]
        kind = page.get("page_kind")
        require(kind == ("club" if number >= 28 else "other" if number == 19 else "result"),
                f"page {number}: page kind inconsistent with document")
        heading = page.get("heading_raw")
        require(heading is None if kind == "other" else
                (isinstance(heading, str) and bool(heading.strip())) or
                (isinstance(heading, list) and bool(heading) and
                 all(isinstance(x, str) and x.strip() for x in heading)),
                f"page {number}: heading_raw invalid")
        require(all(key in page and (page[key] is None or isinstance(page[key], str))
                    for key in ("event_date_raw", "category_raw", "discipline_raw")),
                f"page {number}: date, category and discipline raw fields required")
        columns = page.get("columns_raw")
        require(isinstance(columns, list) and len(columns) == len(set(columns)) and
                all(isinstance(x, str) and x.strip() for x in columns),
                f"page {number}: printed columns required")
        count = page.get("row_count")
        require(type(count) is int and count >= 0 and isinstance(page.get("rows"), list) and
                len(page["rows"]) == count, f"page {number}: visual row count mismatch")
        rows = checked_rows(page["rows"], number, columns, kind, source_sha)
        require(kind != "other" or not rows, "continuation page must have zero rows")
        raw_meta = {key: value for key, value in page.items() if key not in
                    {"page", "rows", "row_count", "columns_raw", "page_kind", "heading_raw",
                     "event_date_raw", "category_raw", "discipline_raw"}}
        output.append({"page": number, "page_kind": kind, "heading_raw": heading,
            "event_date_printed": page["event_date_raw"], "category_raw": page["category_raw"],
            "discipline_raw": page["discipline_raw"], "columns_raw": columns,
            "visual_row_count": count, "rows": rows, "raw_page_metadata": raw_meta})
    by_pos = {(page["page"], row["row"]): row for page in output for row in page["rows"]}
    duplicates = 0
    for page in output:
        for row in page["rows"]:
            reference = row.get("duplicate_of")
            if reference is None:
                continue
            require(isinstance(reference, dict) and set(reference) == {"page", "row"} and
                    type(reference["page"]) is int and type(reference["row"]) is int and
                    1 <= reference["page"] < page["page"] and reference["row"] > 0,
                    f"page {page['page']} row {row['row']}: invalid duplicate_of")
            prior = by_pos.get((reference["page"], reference["row"]))
            require(prior is not None and "duplicate_of" not in prior and
                    row["kind"] == prior["kind"] and
                    all(row[key] == prior[key] for key in
                        ("fields_raw", "status_raw", "penalty_raw", "notes_raw")) and
                    "duplicate_evidence" in row,
                    f"page {page['page']} row {row['row']}: duplicate_of must cite matching earlier row")
            duplicates += row["kind"] == "athlete"
    return output, part_hashes, duplicates


def build_supplement(pdf_path, gap_path, parts, card_path, expected_sha256=SOURCE_SHA256):
    source, gap_sha, source_bytes, assessment = checked_source(pdf_path, gap_path, expected_sha256)
    cards, card_sha = checked_cards(card_path, source)
    pages, part_hashes, athlete_duplicates = checked_pages(parts, expected_sha256)
    athletes = sum(len(page["rows"]) for page in pages if page["page_kind"] == "result")
    aggregates = sum(len(page["rows"]) for page in pages if page["page_kind"] == "club")
    unresolved = sum(bool(row["uncertainties"]) for page in pages for row in page["rows"])
    unresolved_fields = sum(len(row["uncertainties"]) for page in pages for row in page["rows"])
    counts = {"source_objects": 1, "source_positions": athletes,
              "athlete_row_appearances": athletes, "visual_evidence_rows": athletes,
              "aggregate_rows_excluded": aggregates, "aggregate_row_appearances": aggregates,
              "duplicate_rendered_rows": athlete_duplicates,
              "nonduplicate_candidate_positions": athletes - athlete_duplicates,
              "imported_observation_versions": 0, "unresolved_positions": unresolved,
              "unresolved_fields": unresolved_fields, "confirmed_distinct_attempts": None}
    return {"schema": "cagliari-visual-evidence/v1", "source": source,
        "source_sha256": expected_sha256, "source_bytes": source_bytes,
        "source_gap_disposition": assessment["disposition"],
        "source_relationship_status": "unresolved",
        "source_relationship_candidates": [{"event_id": card["event_id"],
            "relationship_type": "calendar_event_link", "basis": "calendar card links exact source URL",
            "state": "candidate", "cross_source_equivalence": "unassessed"} for card in cards],
        "input_sha256": {"gap_supplement": gap_sha, "page_parts": part_hashes,
                         "card_ledger": card_sha},
        "event_date_calendar": EVENT_DATE, "event_cards_calendar": cards,
        "event_date_printed_evidence": [{"page": page["page"], "raw": page["event_date_printed"]}
            for page in pages if page["event_date_printed"] is not None],
        "event_date_calendar_provenance": {"source": "B45 discovery/card-ledger.json",
            "sha256": card_sha, "event_ids": list(EVENT_IDS), "fields": ["date", "title", "event_id"]},
        "owner_review_status": "unreviewed",
        "gap_reconciliation": {"status": "visual_positions_reconciled",
            "prior_disposition": assessment["disposition"],
            "accounted_source_positions": athletes, "aggregate_rows_excluded": aggregates,
            "duplicate_rendered_rows": athlete_duplicates,
            "nonduplicate_candidate_positions": athletes - athlete_duplicates,
            "unresolved_positions": unresolved, "unresolved_fields": unresolved_fields,
            "imported_observation_versions": 0, "owner_review_status": "unreviewed"},
        "transcription_note": "Printed dash glyphs are transcribed as ASCII hyphens; raw values need owner review.",
        "pages": pages, "counts": counts}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("pdf", "gap", "card-ledger", "output"):
        parser.add_argument("--" + flag, required=True, type=Path)
    parser.add_argument("--part", required=True, action="append", type=Path)
    parser.add_argument("--expected-sha256", default=SOURCE_SHA256,
                        help="test fixture override; production default pins original PDF")
    args = parser.parse_args(argv)
    try:
        require(len(args.part) == 3, "expected exactly three page parts")
        destination = args.output.resolve()
        root = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(root):
            ignored = subprocess.run(["git", "-C", str(root), "check-ignore", "-q",
                                      str(destination)], check=False)
            require(ignored.returncode == 0, "private output inside repository must be Git ignored")
        document = build_supplement(args.pdf, args.gap, args.part, args.card_ledger,
                                    args.expected_sha256)
        raw = encoded(document)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
        print(json.dumps({"output": str(destination), "sha256": digest(raw),
            "source_positions": document["counts"]["source_positions"],
            "aggregate_rows_excluded": document["counts"]["aggregate_rows_excluded"]}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Cagliari supplement rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
