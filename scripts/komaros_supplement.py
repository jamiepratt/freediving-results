#!/usr/bin/env python3
"""Build a private, replayable visual evidence supplement for the Komaros PDF.

Example:
  python3 scripts/komaros_supplement.py --pdf ORIGINAL.pdf --gap gap-supplement.json \
    --part pages-01-07.json --part pages-08-13.json --card-ledger card-ledger.json \
    --output PRIVATE/supplement.json

The source PDF, page transcriptions, and output remain private. This tool neither
imports observations nor records owner decisions. Null raw cells stay null.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote


KOMAROS_SHA256 = "2a556ebf4edf4a00f67b91c2a023c9e84c12520008fb7af952885343fcb10ce3"
ROW_COUNTS = (1, 2, 4, 1, 1, 3, 3, 6, 5, 10, 1, 4, 6)
RAW_FIELDS = {
    "position", "surname", "name", "club", "birth_year", "declared_time",
    "declared_distance", "realized_time", "realized_distance", "penalty",
    "homologated_time", "homologated_distance", "time_difference", "points",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def encoded(document):
    return (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def read_json(path):
    data = Path(path).read_bytes()
    return json.loads(data), sha256(data)


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
    require(match.get("bytes", len(pdf)) == len(pdf), "source gap byte count mismatch")
    require(match.get("assessment", {}).get("disposition") == "unsupported_image_results",
            "source gap is not image-only results")
    return source, gap_sha, len(pdf), match.get("assessment", {})


def calendar_date(card_ledger_path, source):
    if card_ledger_path is None:
        return None, None
    ledger, ledger_sha = read_json(card_ledger_path)
    matches = [card for card in ledger["cards"] if card.get("event_id") == 9678]
    require(len(matches) == 1, "card ledger needs exactly one event 9678")
    card = matches[0]
    require(unquote(source["final_url"]) in
            {unquote(url) for url in card.get("result_urls", [])},
            "calendar card does not link the source PDF")
    date = card.get("date")
    require(isinstance(date, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", date),
            "calendar event date is missing or malformed")
    return date, {"source": "B45 discovery/card-ledger.json", "sha256": ledger_sha,
                  "event_id": 9678, "field": "date"}


def checked_pages(parts, source_hash):
    pages = []
    part_hashes = []
    for path in parts:
        part, digest = read_json(path)
        require(isinstance(part, dict) and isinstance(part.get("pages"), list),
                "part must contain pages list")
        pages.extend(part["pages"])
        part_hashes.append(digest)
    require([page.get("page") for page in pages] == list(range(1, 14)),
            "parts must cover pages 1 through 13 in order")
    output = []
    unresolved_positions = 0
    unresolved_fields = 0
    for page, count in zip(pages, ROW_COUNTS):
        number = page["page"]
        heading = page.get("heading_raw")
        require(isinstance(heading, str) and heading.strip(), f"page {number}: heading_raw required")
        printed_date = page.get("event_date_raw")
        require(printed_date is None or isinstance(printed_date, str),
                f"page {number}: event_date_raw must be string or null")
        rows = page.get("rows")
        require(isinstance(rows, list) and len(rows) == count,
                f"page {number}: expected {count} athlete result rows")
        require([row.get("row") for row in rows] == list(range(1, count + 1)),
                f"page {number}: row ordinals must be consecutive and unique")
        match = re.search(r"\bClassifica\s+(.+?)\s+-\s+(\S+)\b", heading, re.IGNORECASE)
        result_rows = []
        for row in rows:
            ordinal = row["row"]
            region = row.get("region")
            require(isinstance(region, dict) and
                    region.get("units") in {"rendered_px_220dpi", "rendered_png_pixels_220_dpi"} and
                    isinstance(region.get("bbox"), list) and len(region["bbox"]) == 4 and
                    all(type(value) is int and value >= 0 for value in region["bbox"]) and
                    region["bbox"][0] < region["bbox"][2] and
                    region["bbox"][1] < region["bbox"][3],
                    f"page {number} row {ordinal}: region citation required")
            fields = row.get("fields")
            require(isinstance(fields, dict) and RAW_FIELDS <= fields.keys(),
                    f"page {number} row {ordinal}: raw fields incomplete")
            require(all(value is None or isinstance(value, str) for value in fields.values()),
                    f"page {number} row {ordinal}: raw fields must be strings or null")
            uncertainty = row.get("uncertainties")
            require(isinstance(uncertainty, list) and all(isinstance(item, str) and item.strip()
                                                         for item in uncertainty),
                    f"page {number} row {ordinal}: uncertainties must be text list")
            unresolved_positions += bool(uncertainty)
            unresolved_fields += len(uncertainty)
            identity = sha256(f"{source_hash}:page:{number}:row:{ordinal}".encode("ascii"))
            result_rows.append({"id": f"source-position:{identity}", "row": ordinal,
                                "citation": {"page": number, "region": region}, "fields": fields,
                                "uncertainties": uncertainty, "review_status": "unreviewed"})
        page_out = {"page": number, "heading_raw": heading,
                       "category_raw": match.group(1) if match else None,
                       "discipline_raw": match.group(2) if match else None,
                       "event_date_printed": printed_date,
                       "disposition": "result_table", "rows": result_rows}
        for key in ("legend_raw", "transcription_note", "render_metadata"):
            if key in page:
                page_out[key] = page[key]
        output.append(page_out)
    return output, part_hashes, unresolved_positions, unresolved_fields


def build_supplement(pdf_path, gap_path, parts, card_ledger_path=None,
                     expected_sha256=KOMAROS_SHA256):
    source, gap_sha, source_bytes, assessment = checked_source(
        pdf_path, gap_path, expected_sha256)
    pages, part_hashes, unresolved_positions, unresolved_fields = checked_pages(
        parts, expected_sha256)
    date, date_provenance = calendar_date(card_ledger_path, source)
    return {
        "schema": "komaros-visual-evidence/v1",
        "source": source,
        "source_sha256": expected_sha256,
        "source_bytes": source_bytes,
        "source_gap_disposition": assessment.get("disposition"),
        "source_relationship_status": "unresolved",
        "input_sha256": {"gap_supplement": gap_sha, "page_parts": part_hashes},
        "event_date_calendar": date,
        "event_date_calendar_provenance": date_provenance,
        "pages": pages,
        "counts": {
            "source_objects": 1, "source_positions": sum(ROW_COUNTS),
            "visual_evidence_rows": sum(ROW_COUNTS),
            "imported_observation_versions": 0,
            "unresolved_positions": unresolved_positions,
            "unresolved_fields": unresolved_fields,
            "confirmed_distinct_attempts": None,
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--gap", required=True, type=Path)
    parser.add_argument("--part", required=True, action="append", type=Path)
    parser.add_argument("--card-ledger", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-sha256", default=KOMAROS_SHA256,
                        help="test fixture override; production default pins Komaros bytes")
    args = parser.parse_args(argv)
    try:
        require(len(args.part) == 2, "expected exactly two page parts")
        destination = args.output.resolve()
        root = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(root):
            ignored = subprocess.run(["git", "-C", str(root), "check-ignore", "-q", str(destination)],
                                     check=False)
            require(ignored.returncode == 0, "private output inside repository must be Git ignored")
        document = build_supplement(args.pdf, args.gap, args.part, args.card_ledger,
                                    args.expected_sha256)
        output_bytes = encoded(document)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(output_bytes)
        print(json.dumps({"output": str(destination), "sha256": sha256(output_bytes),
                          "source_positions": document["counts"]["source_positions"],
                          "unresolved_positions": document["counts"]["unresolved_positions"]},
                         sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Komaros supplement rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
