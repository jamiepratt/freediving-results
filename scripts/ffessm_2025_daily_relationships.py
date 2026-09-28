#!/usr/bin/env python3
"""Cite shared printed fields across FFESSM 2025 daily and ranking packets."""

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path


SCHEMA = "ffessm-2025-daily-relationships/v1"
FIELDS = (("surname", "family-name"), ("given_name", "given-name"),
          ("announced_depth_m", "announced-depth"),
          ("realized_depth_m", "realized-depth"),
          ("depth_penalty", "depth-penalty"),
          ("total_points", "final-points"), ("card", "card"))
NUMERIC = {"announced_depth_m", "realized_depth_m", "depth_penalty", "total_points"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def number(raw, field):
    match = re.fullmatch(r"\s*(-?\d+)\s*(?:m)?\s*", str(raw))
    require(match is not None, f"invalid ranking {field}: {raw!r}")
    return int(match.group(1))


def profile(row, ranking=False):
    fields = row["raw_fields"] if ranking else row
    value = {}
    for normalized, printed in FIELDS:
        raw = fields[printed if ranking else normalized]
        value[normalized] = number(raw, printed) if normalized in NUMERIC else raw.strip()
    value["discipline"] = row["discipline"]
    return value


def ref(row, ranking=False):
    return {"id": row["position_id"] if ranking else row["id"],
            "source_id": row["source_object_id"] if ranking else row["source_id"],
            "citation": row["citation"]}


def reconcile(daily, rankings):
    require(daily.get("schema") == "ffessm-2025-daily/v1"
            and rankings.get("schema") == "ffessm-2025-rankings/v1",
            "input packet schema mismatch")
    daily_rows = daily["observations"]
    ranking_rows = rankings["positions"]
    daily_sources = {source["id"] for source in daily["sources"]}
    ranking_sources = {source["id"] for source in rankings["sources"]}
    require(len({row["id"] for row in daily_rows}) == len(daily_rows)
            and len({row["position_id"] for row in ranking_rows}) == len(ranking_rows),
            "duplicate position id")
    require(all(row["source_id"] in daily_sources for row in daily_rows)
            and all(row["source_object_id"] in ranking_sources for row in ranking_rows),
            "position source id absent from source packet")
    indexed = {}
    for row in daily_rows:
        key = tuple(profile(row).items())
        indexed.setdefault(key, []).append(row)
    relationships = []
    used_daily = set()
    for ranking in ranking_rows:
        key = tuple(profile(ranking, True).items())
        candidates = indexed.get(key, [])
        require(candidates, f"missing daily match: {ranking['position_id']}")
        require(len(candidates) == 1, f"ambiguous daily match: {ranking['position_id']}")
        row = candidates[0]
        require(row["id"] not in used_daily,
                f"duplicate ranking match for daily row: {row['id']}")
        used_daily.add(row["id"])
        rank_plate = number(ranking["raw_fields"]["plate-penalty"], "plate-penalty")
        daily_plate = row["plate_fault_penalty"]
        require(daily_plate == rank_plate or (daily_plate is None and rank_plate == 0),
                f"plate penalty conflict: {ranking['position_id']}")
        plate_evidence = ("daily_blank_ranking_zero" if daily_plate is None
                          else "same_printed_plate_value")
        correspondences = {}
        for normalized, printed in FIELDS:
            correspondences[normalized] = {
                "value": profile(row)[normalized],
                "daily_printed": row[normalized],
                "ranking_printed": ranking["raw_fields"][printed],
                "daily_citation": row["citation"],
                "ranking_citation": ranking["citation"]}
        correspondences["discipline"] = {
            "value": row["discipline"], "daily_printed": row["discipline"],
            "ranking_printed": ranking["discipline"],
            "daily_citation": row["citation"], "ranking_citation": ranking["citation"]}
        relationships.append({"kind": "shared_printed_fields",
                              "ranking": ref(ranking, True), "daily": ref(row),
                              "field_correspondences": correspondences,
                              "plate_evidence": plate_evidence,
                              "daily_plate_fault_penalty": daily_plate,
                              "ranking_plate_penalty": rank_plate,
                              "same_attempt": None,
                              "ranking_row_date": None,
                              "matched_daily_date": row.get("event_date")})
    unmatched = []
    for row in daily_rows:
        if row["id"] in used_daily:
            continue
        candidates = [ranking for ranking in ranking_rows
                      if ranking["raw_fields"]["family-name"] == row["surname"]
                      and ranking["raw_fields"]["given-name"] == row["given_name"]
                      and ranking["discipline"] == row["discipline"]]
        unmatched.append({**ref(row), "day": row.get("day"),
                          "event_date": row.get("event_date"),
                          "federation": row.get("federation"),
                          "status": "unmatched",
                          "same_athlete_discipline_ranking_candidates":
                          [ref(candidate, True) for candidate in candidates],
                          "same_attempt": None})
    return {"schema": SCHEMA,
            "counts": {"ranking_positions": len(ranking_rows),
                       "daily_positions": len(daily_rows),
                       "shared_printed_field_correspondences": len(relationships),
                       "unmatched_daily_positions": len(unmatched),
                       "confirmed_distinct_attempts": None},
            "relationships": relationships, "unmatched_daily": unmatched,
            "limits": ["A unique shared printed-field profile does not by itself prove the same sporting attempt.",
                       "Daily blank plate cells remain null; ranking zeros are separately cited.",
                       "Ranking rows do not print a day, so their result dates remain unknown."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--daily", required=True, type=Path)
    parser.add_argument("--rankings", required=True, type=Path)
    parser.add_argument("--expected-daily-sha")
    parser.add_argument("--expected-ranking-sha")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        daily_bytes, ranking_bytes = args.daily.read_bytes(), args.rankings.read_bytes()
        for label, content, expected in (("daily", daily_bytes, args.expected_daily_sha),
                                         ("ranking", ranking_bytes, args.expected_ranking_sha)):
            if expected:
                require(sha(content) == expected, f"{label} packet SHA-256 mismatch")
        packet = reconcile(json.loads(daily_bytes), json.loads(ranking_bytes))
        packet["inputs"] = {"daily_sha256": sha(daily_bytes),
                            "ranking_sha256": sha(ranking_bytes)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output.parent,
                                         prefix=".ffessm-daily-relationships-",
                                         delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(canonical(packet) + "\n")
        os.replace(temporary, args.output)
        os.chmod(args.output, 0o600)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"ffessm daily relationships: {error}\n")


if __name__ == "__main__":
    main()
