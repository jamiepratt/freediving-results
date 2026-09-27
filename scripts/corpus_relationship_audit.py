#!/usr/bin/env python3
"""Validate a private, read-only corpus relationship projection.

Input is JSON: ``baseline`` is the JSON form of source_relationship_ledger's
output; ``b44`` is its independent FIPSAS source comparison; optional
``archive_inventory`` and ``prior_ledgers`` carry acquisition and historical
aggregate evidence. This command never opens a database or source URL.
Output is a deterministic private ledger. It does not count unique attempts.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{64}\Z")
EXACT_KINDS = {"same-attempt", "source-duplicate", "parser-revision"}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(value):
    return isinstance(value, str) and SHA.fullmatch(value) is not None


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def reference(value):
    require(isinstance(value, dict) and isinstance(value.get("job-id"), str)
            and value["job-id"] and type(value.get("ordinal")) is int
            and value["ordinal"] >= 0, "invalid observation reference")
    return value["job-id"], value["ordinal"]


def cited(observation):
    positions = observation.get("position")
    return (sha(observation.get("source-sha256")) and isinstance(positions, list)
            and bool(positions) and all(isinstance(p, dict)
                and type(p.get("page")) is int and p["page"] > 0
                and type(p.get("line")) is int and p["line"] > 0 for p in positions)
            and isinstance(observation.get("source-text"), str)
            and bool(observation["source-text"].strip()))


def validate_observation_edge(edge, indexed):
    left = indexed.get(reference(edge.get("left")))
    right = indexed.get(reference(edge.get("right")))
    require(left is not None and right is not None and left is not right,
            "observation edge has missing or identical endpoints")
    kind = edge.get("kind")
    require(kind in EXACT_KINDS | {"unknown"}, "invalid observation edge kind")
    if kind == "unknown":
        return
    require(cited(left) and cited(right), "exact edge needs cited source rows")
    basis = edge.get("basis") or {}
    if kind in {"source-duplicate", "parser-revision"}:
        require(left["source-sha256"] == right["source-sha256"]
                == basis.get("source-sha256"), "same-source edge hash mismatch")
        require(left["position"] == right["position"] == basis.get("position"),
                "same-source edge position mismatch")
        require(left["source-text"] == right["source-text"]
                == basis.get("left-text") == basis.get("right-text"),
                "same-source edge citation mismatch")
        require(left.get("event-key") == right.get("event-key")
                and left.get("discipline") == right.get("discipline")
                and left.get("day") == right.get("day"), "same-source scope mismatch")
        if kind == "source-duplicate":
            require(left.get("parsed") == right.get("parsed"),
                    "source-duplicate parsed rows differ")
        else:
            require(left.get("parser-version") != right.get("parser-version"),
                    "parser-revision requires different parser versions")
    else:
        require(left["position"] == basis.get("left-position")
                and right["position"] == basis.get("right-position")
                and left["source-text"] == basis.get("left-text")
                and right["source-text"] == basis.get("right-text"),
                "same-attempt cited positions differ")
        require(left.get("event-key") and left.get("event-key") == right.get("event-key")
                and left.get("discipline") and left.get("discipline") == right.get("discipline"),
                "same-attempt event or discipline scope mismatch")
        # An unknown date is permitted only within the exact same source.
        require((left.get("day") is not None and left.get("day") == right.get("day"))
                or left["source-sha256"] == right["source-sha256"],
                "unknown dates cannot prove cross-source equality")
        fields = basis.get("match-fields")
        require(isinstance(fields, list) and len(fields) >= 3
                and len(set(fields)) == len(fields), "same-attempt needs match fields")
        require(all(left.get("parsed", {}).get(field) is not None
                    and left["parsed"][field] == right.get("parsed", {}).get(field)
                    for field in fields), "same-attempt printed values differ")


def validate_baseline(baseline):
    require(isinstance(baseline, dict), "baseline ledger required")
    observations = baseline.get("observations")
    require(isinstance(observations, list), "baseline observations required")
    indexed = {}
    for observation in observations:
        key = reference(observation.get("ref"))
        require(key not in indexed, "duplicate observation reference")
        indexed[key] = observation
    routes = baseline.get("routes", [])
    require(isinstance(routes, list), "routes must be a list")
    route_index = {}
    for route in routes:
        route_id = route.get("route-id")
        require(isinstance(route_id, str) and route_id and route_id not in route_index,
                "duplicate or invalid route")
        require(route.get("source-sha256") is None or sha(route["source-sha256"]),
                "invalid route source hash")
        route_index[route_id] = route
    edges = baseline.get("edges", [])
    source_edges = baseline.get("source-edges", [])
    candidates = baseline.get("source-candidates", [])
    require(all(isinstance(x, list) for x in (edges, source_edges, candidates)),
            "edge collections must be lists")
    seen_pairs = set()
    for edge in edges:
        require(edge.get("scope") == "observation", "invalid observation edge scope")
        validate_observation_edge(edge, indexed)
        pair = tuple(sorted((reference(edge["left"]), reference(edge["right"]))))
        require(pair not in seen_pairs, "duplicate observation edge")
        seen_pairs.add(pair)
    for edge in source_edges + candidates:
        require(edge.get("scope") == "source-route", "invalid source route edge scope")
        a, b = (edge.get(side, {}).get("route-id") for side in ("left", "right"))
        require(a in route_index and b in route_index and a != b,
                "source route edge needs two known routes")
        kind = edge.get("kind")
        require(kind in {"source-duplicate", "unknown"}, "invalid source route kind")
        if kind == "source-duplicate":
            require(route_index[a]["source-sha256"] is not None
                    and route_index[a]["source-sha256"] == route_index[b]["source-sha256"]
                    == (edge.get("basis") or {}).get("source-sha256"),
                    "source duplicate requires exact byte hash")
    recorded = baseline.get("counts-by-scope")
    if recorded is not None:
        for scope, items in (("observation", edges),
                             ("source-route", source_edges + candidates)):
            for kind, count in recorded.get(scope, {}).items():
                require(count == Counter(e["kind"] for e in items)[kind],
                        "baseline count mismatch")
    return indexed, route_index


def b44_relationships(b44):
    if b44 is None:
        return []
    sources = b44.get("sources", {})
    require(all(sha(sources.get(k)) for k in ("b29_cmas", "b44_open", "b44_italian")),
            "b44 requires three exact source hashes")
    open_audit = b44.get("open", {})
    italian = b44.get("italian", {})
    require(type(open_audit.get("full_row_match_count")) is int
            and open_audit["full_row_match_count"] > 0
            and type(open_audit.get("normalized_page_text_matches_b29")) is int
            and open_audit["normalized_page_text_matches_b29"] > 0
            and open_audit.get("all_page_text_equal_after_normalization") is True,
            "b44 open requires independent page and row comparison")
    rows = italian.get("matched_row_evidence")
    require(isinstance(rows, list) and rows
            and len(rows) == italian.get("national_position_match_count"),
            "b44 national cited count mismatch")
    seen = set()
    for row in rows:
        require(type(row.get("page")) is int and row["page"] > 0
                and type(row.get("line")) is int and row["line"] > 0
                and isinstance(row.get("heading"), str) and row["heading"].strip()
                and sha(row.get("canonical_row_sha256")),
                "b44 national position lacks a citation")
        position = (row["page"], row["line"], row["heading"], row["canonical_row_sha256"])
        require(position not in seen, "b44 national position repeated")
        seen.add(position)
    return [
        {"kind": "cross-publication-same-result", "scope": "source-position",
         "left_source_sha256": sources["b29_cmas"],
         "right_source_sha256": sources["b44_open"],
         "matched_positions": open_audit["full_row_match_count"],
         "proof": {"normalized_matching_pages": open_audit["normalized_page_text_matches_b29"],
                   "all_page_text_equal_after_normalization": True,
                   "position_evidence": "aggregate-only"}},
        {"kind": "supporting-national-standings", "scope": "source-position",
         "left_source_sha256": sources["b44_open"],
         "right_source_sha256": sources["b44_italian"],
         "matched_positions": len(rows), "proof": {"matched_row_evidence": rows}},
    ]


def archive_relationships(inventory):
    if inventory is None:
        return [], []
    acquisitions = inventory.get("acquisitions", [])
    require(isinstance(acquisitions, list), "archive acquisitions must be a list")
    by_hash, by_route, ids = defaultdict(list), defaultdict(list), set()
    for item in acquisitions:
        acquisition_id, route_id, source_hash = (item.get(k) for k in
            ("acquisition_id", "route_id", "source_sha256"))
        require(isinstance(acquisition_id, str) and acquisition_id and acquisition_id not in ids
                and isinstance(route_id, str) and route_id and sha(source_hash),
                "invalid archive acquisition")
        ids.add(acquisition_id)
        by_hash[source_hash].append(acquisition_id)
        by_route[route_id].append(source_hash)
    for field, actual in (("expected_acquisitions", len(acquisitions)),
                          ("expected_distinct_hashes", len(by_hash))):
        if field in inventory:
            require(inventory[field] == actual, "archive inventory count mismatch")
    duplicates = [{"scope": "archive-acquisition", "kind": "source-duplicate",
                   "source_sha256": source_hash, "acquisition_ids": sorted(group)}
                  for source_hash, group in by_hash.items() if len(group) > 1]
    unknown = [{"scope": "archive-route", "kind": "unknown",
                "reason": "changed-bytes-same-route", "route_id": route,
                "source_hashes": sorted(set(group))}
               for route, group in by_route.items() if len(set(group)) > 1]
    return sorted(duplicates, key=canonical), sorted(unknown, key=canonical)


def prior_relationships(prior):
    require(isinstance(prior, list), "prior_ledgers must be a list")
    result = []
    for item in prior:
        require(isinstance(item.get("id"), str) and item["id"]
                and sha(item.get("ledger_sha256"))
                and item.get("kind") == "supporting-printed-value-links"
                and type(item.get("matched_positions")) is int
                and item["matched_positions"] >= 0,
                "invalid prior ledger summary")
        breakdown = item.get("breakdown", {})
        require(isinstance(breakdown, dict)
                and all(isinstance(k, str) and type(v) is int and v >= 0
                        for k, v in breakdown.items())
                and (not breakdown or sum(breakdown.values()) == item["matched_positions"]),
                "prior ledger breakdown mismatch")
        result.append({"id": item["id"], "scope": "source-position",
                       "kind": item["kind"], "ledger_sha256": item["ledger_sha256"],
                       "matched_positions": item["matched_positions"],
                       "breakdown": breakdown})
    require(len({x["id"] for x in result}) == len(result), "duplicate prior ledger ID")
    return sorted(result, key=lambda x: x["id"])


def audit(data, expected_observations=None, expected_jobs=None):
    baseline = data.get("baseline")
    indexed, routes = validate_baseline(baseline)
    jobs = {job for job, _ in indexed}
    if expected_observations is not None:
        require(len(indexed) == expected_observations, "observation count mismatch")
    if expected_jobs is not None:
        require(len(jobs) == expected_jobs, "extraction job count mismatch")
    source_positions = b44_relationships(data.get("b44"))
    archive_duplicates, archive_unknown = archive_relationships(data.get("archive_inventory"))
    prior = prior_relationships(data.get("prior_ledgers", []))
    exact = sorted([e for e in baseline["edges"] if e["kind"] in EXACT_KINDS], key=canonical)
    unknown = sorted([e for e in baseline["edges"] if e["kind"] == "unknown"]
                     + baseline.get("source-candidates", []) + archive_unknown, key=canonical)
    output = {
        "schema": "corpus-relationship-audit/v1",
        "counts": {"observation_versions": len(indexed), "extraction_jobs": len(jobs),
                   "distinct_observation_source_hashes": len({o.get("source-sha256")
                                                                for o in indexed.values()}),
                   "source_routes": len(routes), "exact_observation_edges": len(exact),
                   "exact_source_route_duplicates": len(baseline.get("source-edges", [])),
                   "archive_byte_duplicate_groups": len(archive_duplicates),
                   "unknown_candidate_groups": len(unknown)},
        "observation_edge_counts": dict(sorted(Counter(e["kind"] for e in baseline["edges"]).items())),
        "exact_observation_edges": exact,
        "exact_source_route_duplicates": sorted(baseline.get("source-edges", []), key=canonical),
        "archive_byte_duplicate_groups": archive_duplicates,
        "source_position_relationships": source_positions,
        "prior_supporting_ledgers": prior,
        "unknown_candidates": unknown,
        "limits": ["Observation versions are not unique sporting attempts.",
                   "Aggregate source-position evidence does not create observation pairs.",
                   "Changed bytes and unknown dates do not prove a revision or same attempt."],
    }
    output["ledger_sha256"] = hashlib.sha256(canonical(output).encode()).hexdigest()
    return output


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_json", type=Path)
    parser.add_argument("output_json", type=Path)
    parser.add_argument("--expected-observations", type=int)
    parser.add_argument("--expected-jobs", type=int)
    args = parser.parse_args(argv)
    try:
        data = json.loads(args.input_json.read_text())
        result = audit(data, args.expected_observations, args.expected_jobs)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output_json.parent,
                                         prefix=".relationship-audit-", delete=False) as handle:
            temporary = Path(handle.name)
            try:
                os.chmod(temporary, 0o600)
                handle.write(canonical(result) + "\n")
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise
        os.replace(temporary, args.output_json)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"relationship audit: {error}\n")


if __name__ == "__main__":
    main()
