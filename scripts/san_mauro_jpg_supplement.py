#!/usr/bin/env python3
"""Validate and replay six private Apnea Academy San Mauro 2025 JPG ledgers."""

import argparse
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import re
import sys


BASE_URL = "https://apnea.academy/site/assets/files/11984/"
NAMES = (
    "napoli_statica_maschile_2025.jpg",
    "napoli_statica_femminile_2025.jpg",
    "napoli_dinamica_maschile_2025.jpg",
    "napoli_dinamica_femminile_2025.jpg",
    "napoli_combinata_maschile_2025.jpg",
    "napoli_combinata_femminile_2025.jpg",
)
SCHEMA = "san-mauro-jpg-ledger/v1"
PACKET_SCHEMA = "san-mauro-jpg-supplement/v1"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def jpeg_dimensions(data):
    require(data.startswith(b"\xff\xd8"), "source is not JPEG")
    cursor = 2
    while cursor < len(data):
        require(data[cursor] == 0xff, "invalid JPEG marker")
        while cursor < len(data) and data[cursor] == 0xff:
            cursor += 1
        require(cursor < len(data), "truncated JPEG marker")
        marker = data[cursor]
        cursor += 1
        if marker == 0xd9:
            break
        require(marker not in (0x00, 0xd8) and cursor + 2 <= len(data),
                "invalid JPEG segment")
        length = int.from_bytes(data[cursor:cursor + 2], "big")
        require(length >= 2 and cursor + length <= len(data), "truncated JPEG segment")
        if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
                      0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf):
            require(length >= 7, "truncated JPEG dimensions")
            height = int.from_bytes(data[cursor + 3:cursor + 5], "big")
            width = int.from_bytes(data[cursor + 5:cursor + 7], "big")
            require(width > 0 and height > 0, "invalid JPEG dimensions")
            return width, height
        cursor += length
    raise ValueError("JPEG has no frame dimensions")


def bbox(value, width, height, label):
    require(isinstance(value, list) and len(value) == 4
            and all(type(n) is int for n in value), f"{label} bbox must have four integers")
    x0, y0, x1, y1 = value
    require(0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height,
            f"{label} bbox outside image")
    return value


def within(inner, outer):
    return (outer[0] <= inner[0] < inner[2] <= outer[2]
            and outer[1] <= inner[1] < inner[3] <= outer[3])


def intersects(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def verified_date(source):
    period = source.get("competition_date")
    require(isinstance(period, dict) and set(period) == {"start", "end"},
            "competition_date must have start and end")
    try:
        start, end = date.fromisoformat(period["start"]), date.fromisoformat(period["end"])
    except (ValueError, TypeError) as exc:
        raise ValueError("invalid competition_date") from exc
    require((start.isoformat(), end.isoformat()) == ("2025-02-01", "2025-02-02"),
            "competition_date must cite 1-2 February 2025")
    evidence = source.get("date_evidence")
    require(isinstance(evidence, dict) and set(evidence) == {"url", "quote"}
            and isinstance(evidence["url"], str)
            and evidence["url"].startswith("https://apnea.academy/")
            and isinstance(evidence["quote"], str) and evidence["quote"].strip(),
            "missing exact date citation")
    # The ledger author still must compare this quote with the original image.
    # This check only blocks a quote unrelated to the fixed San Mauro heading.
    heading = " ".join(evidence["quote"].casefold().split())
    require("2025" in heading and "trofeo san mauro" in heading
            and "casalnuovo di napoli" in heading
            and re.search(r"\b1\s*/\s*2\s+febbraio\b", heading) is not None,
            "date citation does not identify San Mauro 1-2 February 2025")
    return period, evidence


def validate_rows(source, width, height, source_id, filename):
    regions = source.get("regions")
    rows = source.get("rows")
    require(isinstance(regions, list) and regions and isinstance(rows, list),
            "regions and rows required")
    region_by_id = {}
    for region in regions:
        require(isinstance(region, dict) and set(region) == {
            "id", "bbox", "printed_row_count", "rows"}, "unsupported region fields")
        region_id = region["id"]
        require(isinstance(region_id, str) and region_id.strip()
                and region_id not in region_by_id, "duplicate or invalid region id")
        box = bbox(region["bbox"], width, height, f"region {region_id}")
        require(not any(intersects(box, other["bbox"]) for other in region_by_id.values()),
                "overlapping regions")
        n = region["printed_row_count"]
        require(type(n) is int and n >= 0 and isinstance(region["rows"], list)
                and region["rows"] == list(range(1, n + 1)),
                f"region {region_id} printed row accounting mismatch")
        region_by_id[region_id] = region
    assigned = set()
    boxes = {key: [] for key in region_by_id}
    positions, versions = [], []
    for row in rows:
        require(isinstance(row, dict) and set(row) in (
            {"region_id", "printed_row", "bbox", "raw_fields", "uncertain_fields",
             "status", "notes", "disposition"},
            {"region_id", "printed_row", "rank", "bbox", "raw_fields",
             "uncertain_fields", "status", "notes", "disposition"},
            {"region_id", "printed_row", "rank", "bbox", "raw_fields",
             "uncertain_fields", "status", "notes", "disposition", "unresolved_reason"},
            {"region_id", "printed_row", "bbox", "raw_fields", "uncertain_fields",
             "status", "notes", "disposition", "unresolved_reason"}),
            "unsupported or missing row fields")
        region_id, ordinal = row["region_id"], row["printed_row"]
        require(region_id in region_by_id and type(ordinal) is int
                and ordinal in region_by_id[region_id]["rows"],
                "row absent from region printed row accounting")
        key = (region_id, ordinal)
        require(key not in assigned, "duplicate printed position")
        assigned.add(key)
        box = bbox(row["bbox"], width, height, f"row {key}")
        require(within(box, region_by_id[region_id]["bbox"]), "row bbox outside region")
        require(not any(intersects(box, earlier) for earlier in boxes[region_id]),
                "overlapping row bboxes")
        boxes[region_id].append(box)
        rank = row.get("rank")
        require(rank is None or (type(rank) is int and rank > 0), "invalid rank")
        raw = row["raw_fields"]
        require(isinstance(raw, dict) and all(isinstance(k, str) and k.strip()
                and isinstance(v, str) for k, v in raw.items()), "invalid raw fields")
        if "columns" in source:
            require(set(raw) == set(source["columns"]),
                    "raw fields differ from printed columns")
        uncertain = row["uncertain_fields"]
        require(isinstance(uncertain, list) and len(set(uncertain)) == len(uncertain)
                and all(isinstance(field, str) and field in raw for field in uncertain),
                "uncertain fields must cite raw fields")
        require(row["status"] is None or isinstance(row["status"], str), "invalid status")
        require(row["notes"] is None or isinstance(row["notes"], str), "invalid notes")
        disposition = row["disposition"]
        require(disposition in ("transcribed", "unresolved"), "invalid disposition")
        if disposition == "unresolved":
            require(isinstance(row.get("unresolved_reason"), str)
                    and row["unresolved_reason"].strip(), "unresolved row needs reason")
        else:
            require("unresolved_reason" not in row and raw, "transcribed row needs raw fields")
        citation = {"source_sha256": source_id.removeprefix("sha256:"),
                    "region_id": region_id, "printed_row": ordinal, "bbox": box}
        position = {"lead_id": source["lead_id"], "source_id": source_id,
                    "filename": filename, "kind": source["kind"],
                    "competition_date": source["competition_date"], "citation": citation,
                    "rank": rank, "disposition": disposition}
        version = {"lead_id": source["lead_id"], "source_id": source_id,
                   "position": citation, "parser_version": "manual-jpg-census/1",
                   "raw_fields": raw, "uncertain_fields": uncertain,
                   "status": row["status"], "notes": row["notes"],
                   "disposition": disposition, "review_status": "unreviewed"}
        if disposition == "unresolved":
            version["unresolved_reason"] = row["unresolved_reason"]
        positions.append(position)
        versions.append(version)
    expected = {(region_id, n) for region_id, region in region_by_id.items()
                for n in region["rows"]}
    require(assigned == expected, "printed row accounting mismatch")
    return positions, versions


def retained_path(value, ledger_path, raw_dir, expected=None):
    require(isinstance(value, str) and value, "missing retained path")
    path = Path(value)
    if path.is_absolute():
        resolved = path.resolve()
    else:
        candidates = [(ledger_path.parent / path).resolve(), (raw_dir / path).resolve()]
        resolved = next((candidate for candidate in candidates if candidate == expected),
                        candidates[0]) if expected is not None else candidates[0]
    if expected is not None:
        require(resolved == expected.resolve(), "raw_path differs from retained JPG")
    return resolved


def verified_receipt(receipt, ledger_path, raw_dir, byte_count):
    require(isinstance(receipt, dict) and set(receipt) == {
        "status", "redirects", "headers_path", "headers_sha256", "http_version",
        "content_type", "content_length", "last_modified", "response_date"},
        "unsupported or missing receipt fields")
    require(type(receipt["status"]) is int and receipt["status"] == 200,
            "receipt HTTP status must be 200")
    require(isinstance(receipt["redirects"], list)
            and all(isinstance(url, str) for url in receipt["redirects"]),
            "invalid receipt redirects")
    require(receipt["content_type"] == "image/jpeg", "receipt content-type mismatch")
    require(type(receipt["content_length"]) is int
            and receipt["content_length"] == byte_count,
            "receipt content-length differs from JPG bytes")
    header_path = retained_path(receipt["headers_path"], ledger_path, raw_dir)
    header_bytes = header_path.read_bytes()
    require(re.fullmatch(r"[0-9a-f]{64}", receipt["headers_sha256"]) is not None
            and sha256(header_bytes) == receipt["headers_sha256"],
            "headers SHA-256 mismatch")
    blocks = re.split(r"\r?\n\r?\n", header_bytes.decode("iso-8859-1"))
    blocks = [block.splitlines() for block in blocks if block.strip()]
    require(blocks and blocks[-1] and blocks[-1][0].startswith("HTTP/"),
            "missing retained HTTP status line")
    status_line = blocks[-1][0]
    require(status_line.split()[:2] == [receipt["http_version"], "200"],
            "retained HTTP status differs from receipt")
    headers = {}
    for line in blocks[-1][1:]:
        require(":" in line, "invalid retained HTTP header")
        key, value = line.split(":", 1)
        headers.setdefault(key.casefold().strip(), []).append(value.strip())
    for key, expected in (("content-type", "image/jpeg"),
                          ("content-length", str(byte_count)),
                          ("date", receipt["response_date"])):
        require(isinstance(expected, str) and expected
                and headers.get(key) == [expected],
                f"retained HTTP {key} differs from receipt")
    require(receipt["last_modified"] is None
            or headers.get("last-modified") == [receipt["last_modified"]],
            "retained HTTP last-modified differs from receipt")
    return receipt


def candidate_relationships(records, objects):
    require(isinstance(records, list), "relationships must be a list")
    by_lead = {source["lead_id"]: source for source in objects}
    allowed_pairs = {
        frozenset(("san-mauro-2025:45", "san-mauro-2025:49")),
        frozenset(("san-mauro-2025:47", "san-mauro-2025:49")),
        frozenset(("san-mauro-2025:46", "san-mauro-2025:50")),
        frozenset(("san-mauro-2025:48", "san-mauro-2025:50")),
    }
    seen, result = set(), []
    for record in records:
        require(isinstance(record, dict) and set(record) == {
            "left_lead_id", "right_lead_id", "state", "basis"},
            "unsupported relationship fields")
        left_id, right_id = record["left_lead_id"], record["right_lead_id"]
        require(left_id in by_lead and right_id in by_lead and left_id != right_id,
                "relationship must cite two distinct sources")
        pair = frozenset((left_id, right_id))
        require(pair not in seen, "duplicate relationship pair")
        seen.add(pair)
        left, right = by_lead[left_id], by_lead[right_id]
        require(pair in allowed_pairs and {left["kind"], right["kind"]} == {
            "individual", "aggregate"}, "relationship category or kind mismatch")
        require(left["competition_date"] == right["competition_date"],
                "relationship event date range mismatch")
        require(record["state"] == "candidate_event_context"
                and isinstance(record["basis"], str) and record["basis"].strip(),
                "relationship requires candidate event context and basis")
        result.append({"left_lead_id": left_id, "right_lead_id": right_id,
                       "left_source_sha256": left["sha256"],
                       "right_source_sha256": right["sha256"],
                       "state": record["state"], "basis": record["basis"],
                       "evidence": {"left_date_evidence": left["date_evidence"],
                                    "right_date_evidence": right["date_evidence"]}})
    return sorted(result, key=lambda item: (item["left_lead_id"], item["right_lead_id"]))


def build(ledger_path, raw_dir):
    ledger_bytes = ledger_path.read_bytes()
    ledger = json.loads(ledger_bytes)
    require(isinstance(ledger, dict) and {"schema", "sources"} <= set(ledger)
            and set(ledger) <= {"schema", "sources", "relationships"}
            and ledger["schema"] == SCHEMA, "unsupported ledger schema")
    sources = ledger["sources"]
    require(isinstance(sources, list) and len(sources) == 6, "exactly six sources required")
    objects, positions, versions = [], [], []
    seen = set()
    for source in sources:
        required = {
            "lead_id", "url", "sha256", "bytes", "width", "height",
            "competition_date", "date_evidence", "kind", "regions", "rows"}
        require(isinstance(source, dict) and required <= set(source)
                and set(source) <= required | {"columns", "raw_path", "receipt",
                                               "source_role", "source_note"},
            "unsupported or missing source fields")
        if "source_role" in source:
            require(isinstance(source["source_role"], str)
                    and source["source_role"].strip(), "source_role must be nonempty")
        if "source_note" in source:
            require(source["source_note"] is None
                    or (isinstance(source["source_note"], str)
                        and source["source_note"].strip()),
                    "source_note must be null or nonempty")
        lead_id = source["lead_id"]
        require(isinstance(lead_id, str) and lead_id.startswith("san-mauro-2025:")
                and lead_id not in seen, "duplicate or invalid lead ID")
        seen.add(lead_id)
        index = int(lead_id.split(":")[-1]) - 45
        require(0 <= index < 6 and source["url"] == BASE_URL + NAMES[index],
                "lead ID or exact source URL mismatch")
        filename = NAMES[index]
        expected_kind = "aggregate" if index >= 4 else "individual"
        require(source["kind"] == expected_kind, "source kind mismatch")
        path = raw_dir / filename
        if "raw_path" in source:
            retained_path(source["raw_path"], ledger_path, raw_dir, path)
        data = path.read_bytes()
        require(re.fullmatch(r"[0-9a-f]{64}", source["sha256"]) is not None
                and sha256(data) == source["sha256"], f"SHA-256 mismatch: {filename}")
        require(type(source["bytes"]) is int and len(data) == source["bytes"],
                f"byte size mismatch: {filename}")
        width, height = jpeg_dimensions(data)
        require(type(source["width"]) is int and type(source["height"]) is int
                and (width, height) == (source["width"], source["height"]),
                f"dimensions mismatch: {filename}")
        period, evidence = verified_date(source)
        if "columns" in source:
            columns = source["columns"]
            require(isinstance(columns, list) and columns
                    and all(isinstance(label, str) and label.strip() for label in columns)
                    and len(columns) == len(set(columns)),
                    "columns must be nonempty unique printed labels")
        if "receipt" in source:
            verified_receipt(source["receipt"], ledger_path, raw_dir, len(data))
        source_id = "sha256:" + source["sha256"]
        obj = {"lead_id": lead_id, "source_id": source_id, "url": source["url"],
                        "path": str(path.resolve()), "sha256": source["sha256"],
                        "bytes": len(data), "width": width, "height": height,
                        "competition_date": period, "date_evidence": evidence,
                        "kind": source["kind"]}
        for key in ("columns", "raw_path", "receipt", "source_role", "source_note"):
            if key in source:
                obj[key] = source[key]
        objects.append(obj)
        cited, observed = validate_rows(source, width, height, source_id, filename)
        positions.extend(cited)
        versions.extend(observed)
    require(seen == {f"san-mauro-2025:{n}" for n in range(45, 51)},
            "missing expected lead ID")
    objects.sort(key=lambda x: x["lead_id"])
    positions.sort(key=lambda x: (x["lead_id"], x["citation"]["region_id"],
                                  x["citation"]["printed_row"]))
    versions.sort(key=lambda x: (x["lead_id"], x["position"]["region_id"],
                                 x["position"]["printed_row"]))
    individual = sum(p["kind"] == "individual" for p in positions)
    aggregate = len(positions) - individual
    transcribed = sum(p["disposition"] == "transcribed" for p in positions)
    unresolved = len(positions) - transcribed
    relationships = candidate_relationships(ledger.get("relationships", []), objects)
    return {"schema": PACKET_SCHEMA, "ledger_sha256": sha256(ledger_bytes),
            "source_objects": objects, "positions": positions,
            "observation_versions": versions, "relationships": relationships,
            "counts": {"source_objects": len(objects), "individual_positions": individual,
                       "aggregate_positions": aggregate, "source_positions": len(positions),
                       "observation_versions": len(versions),
                       "transcribed_positions": transcribed,
                       "unresolved_positions": unresolved,
                       "manual_observation_versions": len(versions),
                       "imported_observation_versions": 0,
                       "relationship_candidates": len(relationships),
                       "confirmed_distinct_attempts": None},
            "limits": ["Aggregate combined standings are source positions, not individual attempts.",
                       "No athlete identity or cross-image attempt equivalence is inferred."]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--raw-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        packet = build(args.ledger, args.raw_dir)
        args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(packet, output, ensure_ascii=False, indent=2, sort_keys=True)
            output.write("\n")
        args.output.chmod(0o600)
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
