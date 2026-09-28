#!/usr/bin/env python3
"""Build a private visual evidence packet for the 2026 2nd Friday Night Dive PDF.

Example: python3 scripts/friday_night_supplement.py --pdf ORIGINAL.pdf --gap GAP.json \
  --part PAGES-01-07.json --part PAGES-08-14.json --part PAGES-15-21.json --card-ledger CARDS.json \
  --output PRIVATE/packet.json

The page ledgers and packet stay private. Raw cells remain raw; no observation is
imported and no distinct sporting attempt or owner decision is asserted.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote


FRIDAY_NIGHT_SHA256 = "7c791d8426c8ed728bb2247e78f4cfe0a4d383b740e5ec45d6601de44102bfce"
EVENT_ID = 11005
EVENT_DATE = "2026-05-29"
PAGE_COUNT = 21
RENDER_WIDTH = 2572
RENDER_HEIGHT = 1819


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    data = Path(path).read_bytes()
    return json.loads(data), sha256(data)


def encoded(document):
    return (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def checked_source(pdf_path, gap_path, expected_sha256):
    require(re.fullmatch(r"[0-9a-f]{64}", expected_sha256) is not None,
            "expected SHA256 must be lowercase hexadecimal")
    pdf = Path(pdf_path).read_bytes()
    require(sha256(pdf) == expected_sha256, "source SHA256 differs from expected PDF")
    gap, gap_sha = read_json(gap_path)
    matches = [item for item in gap["source_gaps"]
               if item.get("verified_sha256") == expected_sha256]
    require(len(matches) == 1, "expected one matching source gap")
    match = matches[0]
    source = match["source"]
    require(source.get("original_sha256") == expected_sha256 and
            source.get("id") == f"sha256:{expected_sha256}", "source gap identity mismatch")
    require(match.get("bytes") == len(pdf), "source gap byte count mismatch")
    assessment = match.get("assessment", {})
    require(assessment.get("disposition") == "unsupported_image_results",
            "source gap is not image-only results")
    return source, gap_sha, len(pdf), assessment


def checked_card(path, source):
    ledger, digest = read_json(path)
    matches = [card for card in ledger["cards"] if card.get("event_id") == EVENT_ID]
    require(len(matches) == 1, "card ledger needs exactly one event 11005")
    card = matches[0]
    require(unquote(source["final_url"]) in
            {unquote(url) for url in card.get("result_urls", [])},
            "calendar card does not link the source PDF")
    require(card.get("date") == EVENT_DATE, "calendar date must be 2026-05-29")
    require(isinstance(card.get("title"), str) and card["title"].strip(),
            "calendar event title is missing")
    return card["title"], digest


def checked_render(render, page):
    require(isinstance(render, dict) and render.get("dpi") == 220 and
            render.get("rotation_clockwise_degrees") == 270 and
            render.get("pixel_width") == RENDER_WIDTH and
            render.get("pixel_height") == RENDER_HEIGHT,
            f"page {page}: render dimensions must be {RENDER_WIDTH}x{RENDER_HEIGHT} upright 270-degree 220 dpi pixels")
    return render


def checked_rows(rows, page, count, render, source_hash, kind, columns):
    require(isinstance(rows, list) and len(rows) == count,
            f"page {page}: visual row count differs from {kind} rows")
    require([row.get("row") for row in rows] == list(range(1, count + 1)),
            f"page {page}: {kind} row ordinals must be consecutive and unique")
    output = []
    for row in rows:
        ordinal = row["row"]
        region = row.get("region")
        require(isinstance(region, dict) and
                region.get("units") == "upright_png_pixels_220dpi" and
                isinstance(region.get("bbox"), list) and len(region["bbox"]) == 4 and
                all(type(value) is int and value >= 0 for value in region["bbox"]) and
                region["bbox"][0] < region["bbox"][2] and
                region["bbox"][1] < region["bbox"][3],
                f"page {page} row {ordinal}: region citation required")
        bbox = region["bbox"]
        require(bbox[2] <= render["pixel_width"] and bbox[3] <= render["pixel_height"],
                f"page {page} row {ordinal}: region exceeds render dimensions")
        fields = row.get("fields")
        require(isinstance(fields, dict) and len(fields) >= 2 and
                all(isinstance(key, str) and key.strip() and
                    (value is None or isinstance(value, str))
                    for key, value in fields.items()),
                f"page {page} row {ordinal}: printed fields must be a raw text map")
        require(set(fields) == set(columns),
                f"page {page} row {ordinal}: fields must match printed columns")
        require(all(key in row and (row[key] is None or isinstance(row[key], str))
                    for key in ("status_raw", "penalty_raw", "notes_raw")),
                f"page {page} row {ordinal}: raw status, penalty and notes required")
        uncertainty = row.get("uncertainties")
        require(isinstance(uncertainty, list) and
                all(isinstance(item, str) and item.strip() for item in uncertainty),
                f"page {page} row {ordinal}: uncertainties must be text list")
        identity = sha256(f"{source_hash}:page:{page}:{kind}:row:{ordinal}".encode("ascii"))
        output.append({"id": f"{kind}-position:{identity}", "row": ordinal,
                       "citation": {"page": page, "region": region},
                       "fields": fields, "status_raw": row["status_raw"],
                       "penalty_raw": row["penalty_raw"], "notes_raw": row["notes_raw"],
                       "uncertainties": uncertainty, "review_status": "unreviewed"})
        if "kind" in row:
            require(isinstance(row["kind"], str) and row["kind"].strip(),
                    f"page {page} row {ordinal}: kind must be text")
            output[-1]["ledger_kind"] = row["kind"]
        if "duplicate_of" in row:
            require("duplicate_evidence" in row,
                    f"page {page} row {ordinal}: duplicate_evidence required")
            output[-1]["duplicate_of"] = row["duplicate_of"]
        if "duplicate_evidence" in row:
            require(isinstance(row["duplicate_evidence"], str) and
                    row["duplicate_evidence"].strip(),
                    f"page {page} row {ordinal}: duplicate_evidence must be text")
            output[-1]["duplicate_evidence"] = row["duplicate_evidence"]
        if "annotations_raw" in row:
            annotations = row["annotations_raw"]
            require(isinstance(annotations, list) and
                    all(isinstance(item, str) and item.strip() for item in annotations),
                    f"page {page} row {ordinal}: annotations_raw must be text list")
            output[-1]["annotations_raw"] = annotations
    return output


def checked_pages(parts, source_hash):
    pages = []
    part_hashes = []
    for path in parts:
        part, digest = read_json(path)
        require(isinstance(part, dict) and isinstance(part.get("pages"), list),
                "part must contain pages list")
        require(part.get("source_sha256") == source_hash,
                "part source SHA256 differs from verified PDF")
        render = part.get("render")
        require(isinstance(render, dict) and render.get("dpi") == 220 and
                render.get("rotation_clockwise_degrees") == 270,
                "part render metadata must declare upright 270-degree 220 dpi")
        pages.extend(part["pages"])
        part_hashes.append(digest)
    require([page.get("page") for page in pages] == list(range(1, PAGE_COUNT + 1)),
            "parts must cover pages 1 through 21 in order")
    aggregate_pages = [page["page"] for page in pages
                       if page.get("disposition") == "club_aggregate"]
    require(aggregate_pages == [1, 2],
            "club aggregate pages must be 1 and 2")
    output = []
    athlete_count = aggregate_count = unresolved_positions = unresolved_fields = 0
    for page in pages:
        number = page["page"]
        heading = page.get("heading_raw")
        require(isinstance(heading, str) and heading.strip(),
                f"page {number}: heading_raw required")
        for key in ("event_date_raw", "category_raw", "discipline_raw"):
            require(key in page and (page[key] is None or isinstance(page[key], str)),
                    f"page {number}: {key} must be string or null")
        columns = page.get("columns_raw")
        require(isinstance(columns, list) and
                all(isinstance(item, str) and item.strip() for item in columns),
                f"page {number}: columns_raw must be printed header list")
        disposition = page.get("disposition")
        require(disposition in {"club_aggregate", "result_table"},
                f"page {number}: disposition invalid")
        render = checked_render(page.get("render"), number)
        counts = page.get("visual_row_count")
        require(isinstance(counts, dict) and
                all(type(counts.get(key)) is int and counts[key] >= 0
                    for key in ("athlete", "aggregate")),
                f"page {number}: visual row count required")
        athlete_rows = checked_rows(page.get("rows"), number, counts["athlete"],
                                    render, source_hash, "source", columns)
        aggregate_rows = checked_rows(page.get("aggregate_rows"), number,
                                      counts["aggregate"], render, source_hash,
                                      "aggregate", columns)
        require(not athlete_rows or disposition == "result_table",
                f"page {number}: club aggregate cannot contain athlete rows")
        require(not aggregate_rows or disposition == "club_aggregate",
                f"page {number}: aggregate rows require club aggregate disposition")
        for row in athlete_rows:
            unresolved_positions += bool(row["uncertainties"])
            unresolved_fields += len(row["uncertainties"])
        athlete_count += len(athlete_rows)
        aggregate_count += len(aggregate_rows)
        page_out = {"page": number, "heading_raw": heading,
                    "event_date_printed": page["event_date_raw"],
                    "category_raw": page["category_raw"],
                    "discipline_raw": page["discipline_raw"],
                    "disposition": disposition, "columns_raw": columns,
                    "visual_row_count": counts, "render": render,
                    "rows": athlete_rows, "aggregate_rows": aggregate_rows}
        for key in ("legend_raw", "transcription_note"):
            if key in page:
                require(isinstance(page[key], str), f"page {number}: {key} must be text")
                page_out[key] = page[key]
        if "notes" in page:
            require(isinstance(page["notes"], list) and
                    all(isinstance(note, str) and note.strip() for note in page["notes"]),
                    f"page {number}: notes must be text list")
            page_out["notes"] = page["notes"]
        output.append(page_out)
    def validate_duplicates(kind):
        by_position = {(page["page"], row["row"]): row
                       for page in output for row in page[kind]}
        count = 0
        for page in output:
            for row in page[kind]:
                reference = row.get("duplicate_of")
                if reference is None:
                    continue
                require(isinstance(reference, dict) and
                        set(reference) == {"page", "row"} and
                        type(reference["page"]) is int and type(reference["row"]) is int and
                        reference["page"] < page["page"] and reference["row"] > 0,
                        f"page {page['page']} row {row['row']}: invalid duplicate_of")
                prior = by_position.get((reference["page"], reference["row"]))
                require(prior is not None and "duplicate_of" not in prior and
                        all(row[key] == prior[key] for key in
                            ("fields", "status_raw", "penalty_raw", "notes_raw")),
                        f"page {page['page']} row {row['row']}: duplicate_of must cite matching earlier row")
                count += 1
        return count

    athlete_duplicates = validate_duplicates("rows")
    aggregate_duplicates = validate_duplicates("aggregate_rows")
    return (output, part_hashes, athlete_count, aggregate_count, athlete_duplicates,
            aggregate_duplicates, unresolved_positions, unresolved_fields)


def build_supplement(pdf_path, gap_path, parts, card_path,
                     expected_sha256=FRIDAY_NIGHT_SHA256):
    source, gap_sha, source_bytes, assessment = checked_source(
        pdf_path, gap_path, expected_sha256)
    title, card_sha = checked_card(card_path, source)
    pages, part_hashes, positions, aggregates, duplicates, aggregate_duplicates, unresolved_positions, unresolved_fields = (
        checked_pages(parts, expected_sha256))
    counts = {"source_objects": 1, "source_positions": positions,
              "athlete_row_appearances": positions,
              "visual_evidence_rows": positions, "aggregate_rows_excluded": aggregates,
              "aggregate_row_appearances": aggregates,
              "duplicate_aggregate_rows": aggregate_duplicates,
              "nonduplicate_aggregate_rows": aggregates - aggregate_duplicates,
              "duplicate_rendered_rows": duplicates,
              "nonduplicate_candidate_positions": positions - duplicates,
              "imported_observation_versions": 0,
              "unresolved_positions": unresolved_positions,
              "unresolved_fields": unresolved_fields,
              "confirmed_distinct_attempts": None}
    return {
        "schema": "friday-night-visual-evidence/v1", "source": source,
        "source_sha256": expected_sha256, "source_bytes": source_bytes,
        "source_gap_disposition": assessment["disposition"],
        "source_relationship_status": "unresolved",
        "source_relationship_candidates": [{"event_id": EVENT_ID,
            "relationship_type": "calendar_event_link",
            "basis": "calendar card links exact source URL", "state": "candidate",
            "cross_source_equivalence": "unassessed"}],
        "input_sha256": {"gap_supplement": gap_sha, "page_parts": part_hashes,
                         "card_ledger": card_sha},
        "event_date_calendar": EVENT_DATE, "event_title_calendar": title,
        "event_id_calendar": EVENT_ID,
        "event_date_calendar_provenance": {
            "source": "B45 discovery/card-ledger.json", "sha256": card_sha,
            "event_id": EVENT_ID, "fields": ["date", "title", "event_id"]},
        "event_date_printed_evidence": [
            {"page": page["page"], "raw": page["event_date_printed"]}
            for page in pages if page["event_date_printed"] is not None],
        "owner_review_status": "unreviewed",
        "gap_reconciliation": {"status": "visual_positions_reconciled",
            "prior_disposition": assessment["disposition"],
            "accounted_source_positions": positions,
            "aggregate_rows_excluded": aggregates,
            "duplicate_aggregate_rows": aggregate_duplicates,
            "nonduplicate_aggregate_rows": aggregates - aggregate_duplicates,
            "duplicate_rendered_rows": duplicates,
            "nonduplicate_candidate_positions": positions - duplicates,
            "unresolved_positions": unresolved_positions,
            "unresolved_fields": unresolved_fields,
            "imported_observation_versions": 0,
            "owner_review_status": "unreviewed"},
        "pages": pages, "counts": counts,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--gap", required=True, type=Path)
    parser.add_argument("--part", required=True, action="append", type=Path)
    parser.add_argument("--card-ledger", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-sha256", default=FRIDAY_NIGHT_SHA256,
                        help="test fixture override; production default pins Friday Night Dive bytes")
    args = parser.parse_args(argv)
    try:
        require(len(args.part) == 3, "expected exactly three page parts")
        destination = args.output.resolve()
        root = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(root):
            ignored = subprocess.run(
                ["git", "-C", str(root), "check-ignore", "-q", str(destination)],
                check=False)
            require(ignored.returncode == 0,
                    "private output inside repository must be Git ignored")
        document = build_supplement(args.pdf, args.gap, args.part,
                                    args.card_ledger, args.expected_sha256)
        output_bytes = encoded(document)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(output_bytes)
        print(json.dumps({"output": str(destination), "sha256": sha256(output_bytes),
                          "source_positions": document["counts"]["source_positions"],
                          "aggregate_rows_excluded":
                          document["counts"]["aggregate_rows_excluded"]}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Friday Night Dive supplement rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
