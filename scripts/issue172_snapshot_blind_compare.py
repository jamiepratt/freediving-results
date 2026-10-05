#!/usr/bin/env python3
"""Read-only comparison of recovered snapshot rows and blind image transcriptions.

The snapshot is a derivative of missing first packets. This tool never verifies
those packet bytes, resolves a disagreement, or imports observations.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import sys


SOURCES = {
    "liberamente": ("dd2589b800ae444c9c505c52f2b3313653ef0e1974663c131b56a721b97a2bc7",
                    "b38c09afce741b34ca18c5dd9e306ef10ae88d7f2a0ceb79867e4b9af64dd6fd",
                    "liberamente-visual-evidence/v1", 24, 59, 13),
    "friday": ("7c791d8426c8ed728bb2247e78f4cfe0a4d383b740e5ec45d6601de44102bfce",
               "2e563d41c5b5524a8c73d423db00f64243f51b0580172a28f60312f09292b10a",
               "friday-night-visual-evidence/v1", 21, 30, 6),
}
ATHLETE_FIELDS = {
    "surname": "Cognome", "given_name": "Nome", "club": "Società",
    "birth_year": "Anno di nascita", "rank": "Posizione", "position": "Posizione",
    "declared_time": "Tempo dichiarato", "declared_distance": "Distanza dichiarata",
    "realized_time": "Tempo realizzato", "actual_time": "Tempo realizzato",
    "realized_distance": "Distanza realizzata", "actual_distance": "Distanza realizzata",
    "approved_time": "Tempo omologato", "certified_time": "Tempo omologato",
    "approved_distance": "Distanza omologata", "certified_distance": "Distanza omologata",
    "time_difference": "Differenza tempo", "score": "Punteggio", "penalty": "Penalità",
}
AGGREGATE_FIELDS = {"club": "Nome", "region": "Regione", "locality": "Località",
                    "score": "Punteggio", "position": "Posizione"}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def bound(path, expected, label):
    raw = path.read_bytes()
    require(re.fullmatch(r"[0-9a-f]{64}", expected) and sha(raw) == expected,
            f"{label} SHA256 mismatch")
    return raw


def parse_position(source_kind, role, row):
    position = row.get("source_position")
    pattern = (r"p(\d+)-r(\d+)" if source_kind == "liberamente" and role == "athlete" else
               r"p(\d+)-club-(\d+)" if source_kind == "liberamente" else
               r"p(\d+):r(\d+)" if role == "athlete" else r"p(\d+):c(\d+)")
    match = re.fullmatch(pattern, position or "")
    require(match is not None and int(match[1]) == row.get("page"), "blind source position invalid")
    return int(match[1]), int(match[2]), role


def compare(args):
    bound(args.snapshot, args.snapshot_sha256, "snapshot")
    blind = json.loads(bound(args.blind, args.blind_sha256, "blind transcript"))
    require(blind.get("source_sha256") == args.expected_source_sha256,
            "source SHA256 mismatch")
    with sqlite3.connect(f"file:{args.snapshot}?mode=ro", uri=True) as db:
        require(db.execute("pragma integrity_check").fetchone()[0] == "ok",
                "snapshot integrity check failed")
        record = db.execute("select metadata_json from source_metadata where source_name=?",
                            (args.source_kind,)).fetchall()
        require(len(record) == 1, "snapshot source metadata count mismatch")
        metadata = json.loads(record[0][0])
        require(metadata.get("source_sha256") == args.expected_source_sha256,
                "source SHA256 mismatch")
        official = SOURCES[args.source_kind]
        if args.expected_source_sha256 == official[0]:
            require(metadata.get("schema") == official[2],
                    "snapshot source schema mismatch")
            require(blind.get("source_pages") == official[3], "blind page count mismatch")
        pages = db.execute("select count(*) from records where source_name=? and collection='pages'",
                           (args.source_kind,)).fetchone()[0]
        if pages:
            require(blind.get("source_pages") == pages, "snapshot page count mismatch")
        first = {}
        query = db.execute("select collection,page,raw_json from records where source_name=? and collection in ('pages.rows','pages.aggregate_rows')",
                           (args.source_kind,))
        for collection, page, raw in query:
            item = json.loads(raw)
            role = ("aggregate" if collection == "pages.aggregate_rows" or item.get("kind") == "aggregate"
                    else "athlete")
            key = (page, item.get("row"), role)
            require(type(page) is int and type(item.get("row")) is int and key not in first,
                    "duplicate source position")
            require(item.get("citation", {}).get("page") == page,
                    "snapshot citation page mismatch")
            fields = item.get("fields_raw", item.get("fields"))
            require(isinstance(fields, dict), "snapshot raw fields missing")
            first[key] = fields
    later = {}
    for role, collection in (("athlete", blind.get("athlete_rows")),
                             ("aggregate", blind.get("aggregate_rows", blind.get("club_aggregate_rows")))):
        require(isinstance(collection, list), "blind position collection missing")
        for row in collection:
            key = parse_position(args.source_kind, role, row)
            require(key not in later, "duplicate blind source position")
            later[key] = row
    require(set(first) == set(later), "source position coverage mismatch")
    athlete = sum(key[2] == "athlete" for key in first)
    aggregate = len(first) - athlete
    if args.expected_source_sha256 == SOURCES[args.source_kind][0]:
        require((athlete, aggregate) == SOURCES[args.source_kind][4:],
                "official source position count mismatch")
    disagreements = []
    unpaired = []
    agreements = []
    compared = 0
    for key in sorted(first):
        blind_row = later[key]
        mapping = ATHLETE_FIELDS if key[2] == "athlete" else AGGREGATE_FIELDS
        different = []
        missing = []
        for blind_field, source_field in mapping.items():
            if blind_field not in blind_row:
                continue
            if source_field not in first[key]:
                missing.append(blind_field)
                continue
            compared += 1
            a, b = first[key][source_field], blind_row[blind_field]
            if a != b and not (a in (None, "") and b in (None, "")):
                different.append(blind_field)
        position = blind_row["source_position"]
        if different:
            disagreements.append({"source_position": position, "fields": sorted(different)})
        if missing:
            unpaired.append({"source_position": position, "fields": sorted(missing)})
        if not different and not missing:
            agreements.append(position)
    sampled = sorted(agreements, key=lambda position: sha(
        f"{args.expected_source_sha256}:{position}".encode()))[:math.ceil(len(agreements) / 10)]
    disagreement_fields = Counter(field for item in disagreements for field in item["fields"])
    unpaired_fields = Counter(field for item in unpaired for field in item["fields"])
    return {"schema": "issue172-snapshot-blind-comparison/v1",
            "source_kind": args.source_kind, "source_sha256": args.expected_source_sha256,
            "snapshot_sha256": args.snapshot_sha256, "blind_sha256": args.blind_sha256,
            "first_packet_verified": False,
            "counts": {"athlete_positions": athlete, "aggregate_positions": aggregate,
                       "compared_fields": compared, "disagreements": len(disagreements),
                       "unpaired_positions": len(unpaired),
                       "sampled_agreements": len(sampled)},
            "disagreement_fields": dict(sorted(disagreement_fields.items())),
            "unpaired_fields": dict(sorted(unpaired_fields.items())),
            "disagreements": disagreements, "unpaired": unpaired,
            "sampled_agreements": sorted(sampled),
            "limitations": ["snapshot is a derivative; original first-packet bytes unavailable",
                            "raw field differences require source-image inspection before import"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-kind", choices=sorted(SOURCES), required=True)
    for name in ("snapshot", "blind"):
        parser.add_argument(f"--{name}", type=Path, required=True)
        parser.add_argument(f"--{name}-sha256", required=True)
    parser.add_argument("--expected-source-sha256", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(compare(args), sort_keys=True))
    except (ValueError, sqlite3.Error, OSError, json.JSONDecodeError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
