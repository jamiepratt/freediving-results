#!/usr/bin/env python3
"""Build a private, deterministic visual evidence packet for the Barracuda PDF.

Example:
  python3 scripts/barracuda_supplement.py --pdf ORIGINAL.pdf --gap gap.json \
    --pages private-pages.json --card-ledger card-ledger.json --output PRIVATE/packet.json

The page ledger and output remain private. This tool does not import observations
or make owner decisions. Raw fields, including null cells, are preserved.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote


BARRACUDA_SHA256 = "ca3565fe1e52f2047c55d15ddf4739796154c0c9764c5e372586b88d914a1f68"
ROW_COUNTS = (3, 1, 1, 2)
EVENT_ID = 10947
EVENT_DATE = "2026-05-08"


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
            source.get("id") == f"sha256:{expected_sha256}",
            "source gap identity mismatch")
    require(match.get("bytes", len(pdf)) == len(pdf), "source gap byte count mismatch")
    assessment = match.get("assessment", {})
    require(assessment.get("disposition") == "unsupported_image_results",
            "source gap is not image-only results")
    return source, gap_sha, len(pdf), assessment


def checked_card(path, source):
    ledger, digest = read_json(path)
    matches = [card for card in ledger["cards"] if card.get("event_id") == EVENT_ID]
    require(len(matches) == 1, "card ledger needs exactly one event 10947")
    card = matches[0]
    require(unquote(source["final_url"]) in
            {unquote(url) for url in card.get("result_urls", [])},
            "calendar card does not link the source PDF")
    require(card.get("date") == EVENT_DATE,
            "calendar date must be 2026-05-08")
    require(isinstance(card.get("title"), str) and card["title"].strip(),
            "calendar event title is missing")
    return card["title"], digest


def checked_render(render):
    require(isinstance(render, dict) and render.get("dpi") == 220 and
            render.get("rotation_clockwise_degrees") == 270 and
            all(type(render.get(key)) is int and render[key] > 0
                for key in ("pixel_width", "pixel_height")),
            "render metadata must describe upright 270-degree 220 dpi pixels")
    return render


def checked_rows(rows, page, count, render, source_hash, kind):
    require(isinstance(rows, list) and len(rows) == count,
            f"page {page}: expected {count} {kind} rows")
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
        require(bbox[2] <= render["pixel_width"] and
                bbox[3] <= render["pixel_height"],
                f"page {page} row {ordinal}: region exceeds render dimensions")
        fields = row.get("fields")
        require(isinstance(fields, dict) and len(fields) >= 2 and
                all(isinstance(key, str) and key.strip() and
                    (value is None or isinstance(value, str))
                    for key, value in fields.items()),
                f"page {page} row {ordinal}: printed fields must be a raw text map")
        require(all(row.get(key) is None or isinstance(row[key], str)
                    for key in ("status_raw", "notes_raw")) and
                "status_raw" in row and "notes_raw" in row,
                f"page {page} row {ordinal}: raw status and notes required")
        uncertainty = row.get("uncertainties")
        require(isinstance(uncertainty, list) and
                all(isinstance(item, str) and item.strip() for item in uncertainty),
                f"page {page} row {ordinal}: uncertainties must be text list")
        identity = sha256(f"{source_hash}:page:{page}:{kind}:row:{ordinal}".encode("ascii"))
        output.append({"id": f"source-position:{identity}" if kind == "athlete" else
                       f"aggregate-position:{identity}", "row": ordinal,
                       "citation": {"page": page, "region": region}, "fields": fields,
                       "status_raw": row["status_raw"], "notes_raw": row["notes_raw"],
                       "uncertainties": uncertainty, "review_status": "unreviewed"})
    return output


def checked_pages(path, source_hash):
    ledger, ledger_sha = read_json(path)
    require(ledger.get("source_sha256") == source_hash,
            "page ledger source SHA256 differs from verified PDF")
    render = checked_render(ledger.get("render"))
    pages = ledger.get("pages")
    require(isinstance(pages, list) and [page.get("page") for page in pages] ==
            [1, 2, 3, 4, 5], "page ledger must cover pages 1 through 5 in order")
    output = []
    unresolved_positions = 0
    unresolved_fields = 0
    aggregate_count = 0
    for page in pages:
        number = page["page"]
        heading = page.get("heading_raw")
        require(isinstance(heading, str) and heading.strip(),
                f"page {number}: heading_raw required")
        printed_date = page.get("event_date_raw")
        require(printed_date is None or isinstance(printed_date, str),
                f"page {number}: event_date_raw must be string or null")
        page_render = checked_render(page.get("render", render))
        if number < 5:
            require(page.get("disposition") == "result_table",
                    f"page {number}: expected result table")
            require(printed_date is None, f"page {number}: no printed event date expected")
            require(not page.get("aggregate_rows"),
                    f"page {number}: society aggregate belongs on page 5")
            rows = checked_rows(page.get("rows"), number, ROW_COUNTS[number - 1],
                                page_render, source_hash, "athlete")
            aggregate_rows = []
        else:
            require(page.get("disposition") == "society_aggregate" and
                    page.get("rows") == [],
                    "page 5: society aggregate must have no athlete rows")
            require(isinstance(printed_date, str) and printed_date.strip(),
                    "page 5: printed event date line required")
            raw_aggregates = page.get("aggregate_rows")
            require(isinstance(raw_aggregates, list) and raw_aggregates,
                    "page 5: society aggregate rows required")
            aggregate_rows = checked_rows(raw_aggregates, number, len(raw_aggregates),
                                          page_render, source_hash, "society aggregate")
            aggregate_count = len(aggregate_rows)
            rows = []
        for row in rows:
            unresolved_positions += bool(row["uncertainties"])
            unresolved_fields += len(row["uncertainties"])
        page_out = {"page": number, "heading_raw": heading,
                    "event_date_printed": printed_date,
                    "disposition": page["disposition"], "render": page_render,
                    "rows": rows, "aggregate_rows": aggregate_rows}
        for key in ("legend_raw", "transcription_note"):
            if key in page:
                page_out[key] = page[key]
        output.append(page_out)
    return output, ledger_sha, aggregate_count, unresolved_positions, unresolved_fields


def build_supplement(pdf_path, gap_path, pages_path, card_path,
                     expected_sha256=BARRACUDA_SHA256):
    source, gap_sha, source_bytes, assessment = checked_source(
        pdf_path, gap_path, expected_sha256)
    title, card_sha = checked_card(card_path, source)
    pages, pages_sha, aggregate_count, unresolved_positions, unresolved_fields = (
        checked_pages(pages_path, expected_sha256))
    return {
        "schema": "barracuda-visual-evidence/v1", "source": source,
        "source_sha256": expected_sha256, "source_bytes": source_bytes,
        "source_gap_disposition": assessment["disposition"],
        "source_relationship_status": "unresolved",
        "input_sha256": {"gap_supplement": gap_sha, "page_ledger": pages_sha,
                         "card_ledger": card_sha},
        "event_date_calendar": EVENT_DATE, "event_title_calendar": title,
        "event_id_calendar": EVENT_ID,
        "event_date_calendar_provenance": {
            "source": "B45 discovery/card-ledger.json", "sha256": card_sha,
            "event_id": EVENT_ID, "fields": ["date", "title", "event_id"]},
        "owner_review_status": "unreviewed",
        "gap_reconciliation": {
            "status": "visual_positions_reconciled",
            "prior_disposition": assessment["disposition"],
            "accounted_source_positions": sum(ROW_COUNTS),
            "aggregate_rows_excluded": aggregate_count,
            "unresolved_positions": unresolved_positions,
            "unresolved_fields": unresolved_fields,
            "imported_observation_versions": 0,
            "owner_review_status": "unreviewed"},
        "pages": pages,
        "counts": {"source_objects": 1, "source_positions": sum(ROW_COUNTS),
                   "visual_evidence_rows": sum(ROW_COUNTS),
                   "aggregate_rows_excluded": aggregate_count,
                   "imported_observation_versions": 0,
                   "unresolved_positions": unresolved_positions,
                   "unresolved_fields": unresolved_fields,
                   "confirmed_distinct_attempts": None},
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--gap", required=True, type=Path)
    parser.add_argument("--pages", required=True, type=Path)
    parser.add_argument("--card-ledger", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-sha256", default=BARRACUDA_SHA256,
                        help="test fixture override; production default pins Barracuda bytes")
    args = parser.parse_args(argv)
    try:
        destination = args.output.resolve()
        root = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(root):
            ignored = subprocess.run(
                ["git", "-C", str(root), "check-ignore", "-q", str(destination)],
                check=False)
            require(ignored.returncode == 0,
                    "private output inside repository must be Git ignored")
        document = build_supplement(args.pdf, args.gap, args.pages,
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
        print(f"Barracuda supplement rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
