#!/usr/bin/env python3
"""Fail-closed private accounting of nine independently verified scan stages.

Relationship assertions are accepted only from separately pinned, cited evidence.
This command never infers a sporting attempt or athlete identity from row values.
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import unicodedata
from collections import Counter
from pathlib import Path


SOURCES = ("worldcup", "italian_open", "barracuda", "komaros", "cagliari",
           "liberamente", "friday", "firenze", "asti")
IMPORT_NAMES = {"italian_open": "italian-open", "friday": "friday-night", "asti": "asti-blu"}
RELATIONS = ("exact_supported", "likely_overlap", "conflicting_revision", "ambiguous_candidate")
EVENT_DAYS = {"barracuda": "2026-05-08", "komaros": "2026-04-12",
              "cagliari": "2026-01-18", "liberamente": "2026-07-11",
              "friday": "2026-05-29", "firenze": "2026-03-29", "asti": "2026-04-19"}
WORLDCUP_DAYS = {1: "2026-05-26", 2: "2026-05-24", 3: "2026-05-28", 4: "2026-05-30"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def read_pinned(spec):
    name, path, expected = spec.split("=", 2)
    raw = Path(path).read_bytes()
    actual = digest(raw)
    if actual != expected:
        raise ValueError(f"{name}: SHA-256 mismatch: {actual}")
    return name, Path(path), actual, json.loads(raw)


def read_pinned_binary(spec):
    name, path, expected = spec.split("=", 2)
    raw = Path(path).read_bytes()
    actual = digest(raw)
    if actual != expected:
        raise ValueError(f"{name}: SHA-256 mismatch: {actual}")
    return name, Path(path), actual, raw


def verify_provenance(stages, roots):
    wanted = {}
    for name, (_, _, stage) in stages.items():
        wanted.setdefault(stage["source_sha256"], []).append((name, "source"))
        for field, value in stage.get("input_sha256", {}).items():
            if isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value):
                wanted.setdefault(value, []).append((name, field))
    found = {}
    for root in roots:
        for path in sorted(Path(root).rglob("*")):
            if not path.is_file() or (path.suffix.lower() not in (".json", ".pdf") and path.name not in wanted):
                continue
            if path.stat().st_size > 100_000_000:
                continue
            sha = digest(path.read_bytes())
            if sha in wanted and sha not in found:
                found[sha] = str(path)
    missing = sorted(f"{name}:{field}" for sha, tags in wanted.items() if sha not in found for name, field in tags)
    return {"verified": {f"{name}:{field}": {"sha256": sha, "path": found[sha]}
                         for sha, tags in wanted.items() if sha in found for name, field in tags},
            "missing": missing}


def position_id(item):
    pos = item.get("source_position") or item
    return pos.get("id") or item.get("id")


def normalized(value):
    return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", str(value or ""))
                  .encode("ascii", "ignore").decode().lower())


def names(raw):
    found = set()
    for first, last in (("Nome", "Cognome"), ("given_name", "surname"),
                        ("First Name", "Last Name"), ("first_name", "last_name"),
                        ("name", "surname"), ("given-name", "surname")):
        if raw.get(first) and raw.get(last):
            found.update((normalized(str(raw[first]) + str(raw[last])),
                          normalized(str(raw[last]) + str(raw[first]))))
    for field in ("full_name", "source-name"):
        if raw.get(field):
            found.add(normalized(raw[field]))
    return {value for value in found if len(value) >= 5}


def event_day(name, item):
    if name == "worldcup":
        return WORLDCUP_DAYS.get((item.get("source_position") or item).get("page"))
    return EVENT_DAYS.get(name)


def compatible_date(source, source_day, other_day, other_source=None):
    source_year = "2025" if source == "italian_open" else source_day[:4] if source_day else None
    other_year = "2025" if other_source == "italian_open" else other_day[:4] if other_day else None
    if source_year and other_year and source_year != other_year:
        return False
    return not (source_day and other_day and source_day != other_day)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", action="append", required=True, metavar="SOURCE=PATH=SHA256")
    parser.add_argument("--relationship-evidence", metavar="PATH=SHA256",
                        help="Cited, independently reviewed relationship assertions")
    parser.add_argument("--corpus-sha256", help="Pinned retained/imported corpus digest for comparison scope")
    parser.add_argument("--snapshot", metavar="PATH=SHA256", help="Read-only retained corpus snapshot")
    parser.add_argument("--evidence-root", action="append", default=[],
                        help="Private source, receipt, and pass files to hash-check")
    parser.add_argument("--require-complete-provenance", action="store_true")
    parser.add_argument("--import-manifest", metavar="PATH=SHA256", help="Pinned combined isolated import manifest")
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args(argv)
    try:
        stages = dict()
        for spec in args.stage:
            name, path, sha, data = read_pinned(spec)
            if name in stages:
                raise ValueError(f"duplicate stage: {name}")
            stages[name] = (path, sha, data)
        if set(stages) != set(SOURCES):
            raise ValueError(f"nine named stages required; missing={sorted(set(SOURCES)-set(stages))}; extra={sorted(set(stages)-set(SOURCES))}")
        import_manifest_sha = None
        if args.import_manifest:
            _, _, import_manifest_sha, import_manifest = read_pinned("manifest=" + args.import_manifest)
            if import_manifest.get("schema") != "issue172-combined-manifest/v1":
                raise ValueError("combined import manifest schema mismatch")
            imported = {next((name for name in SOURCES if IMPORT_NAMES.get(name, name) == item["name"]), item["name"]): item
                        for item in import_manifest["sources"]}
            if set(imported) != set(SOURCES):
                raise ValueError("combined import manifest source mismatch")
            for name in SOURCES:
                if imported[name]["stage_sha256"] != stages[name][1]:
                    raise ValueError(f"{name}: combined import stage mismatch")
        provenance = verify_provenance(stages, args.evidence_root) if args.evidence_root else None
        if args.require_complete_provenance and (provenance is None or provenance["missing"]):
            raise ValueError("missing pinned provenance: " + ", ".join(provenance["missing"] if provenance else ["all"]))
        all_positions = {}
        candidate_rows = []
        source_reports = {}
        queue = []
        for name in SOURCES:
            path, sha, stage = stages[name]
            observations = stage["observation_versions"]
            unresolved = stage["unresolved_positions"]
            non_primary = stage.get("non_primary_positions", [])
            counts = stage["counts"]
            candidate = counts.get("candidate_result_positions", counts["cited_positions"])
            if len(observations) != counts["verified_staged_versions"] or len(unresolved) != counts["unresolved_positions"]:
                raise ValueError(f"{name}: stage count mismatch")
            if candidate != len(observations) + len(unresolved):
                raise ValueError(f"{name}: candidate result accounting mismatch")
            if counts["cited_positions"] != candidate + len(non_primary):
                raise ValueError(f"{name}: cited position accounting mismatch")
            seen = set()
            for item in observations + unresolved + non_primary:
                pid = position_id(item)
                if not pid or pid in seen:
                    raise ValueError(f"{name}: missing or duplicate source position")
                seen.add(pid)
            for item in observations:
                pid = position_id(item)
                if item.get("source_sha256") != stage["source_sha256"] or item.get("parser_version") != stage["parser_version"]:
                    raise ValueError(f"{name}: observation provenance mismatch")
                if pid in all_positions:
                    raise ValueError(f"cross-source position ID collision: {pid}")
                all_positions[pid] = name
                candidate_rows.append((name, item, stage["source_sha256"], sha))
            for item in unresolved:
                pid = position_id(item)
                if pid in all_positions:
                    raise ValueError(f"cross-source position ID collision: {pid}")
                all_positions[pid] = name
                candidate_rows.append((name, item, stage["source_sha256"], sha))
                queue.append({"id": "issue172:" + digest(encoded(["field", stage["source_sha256"], pid]))[:24],
                              "kind": "unresolved_field", "source_key": name,
                              "source_sha256": stage["source_sha256"], "source_position": pid,
                              "citation": item.get("citation") or item.get("source_position", {}).get("citation"),
                              "evidence_version": sha, "reason": item.get("reason", "unresolved reading"),
                              "related_positions": [], "status": "pending"})
            source_reports[name] = {"stage_path": str(path), "stage_sha256": sha,
                "source_sha256": stage["source_sha256"], "parser_version": stage["parser_version"],
                "input_sha256": stage.get("input_sha256", {}),
                "historical_packet_sha256": stage.get("historical_packet_sha256"),
                "historical_packet": stage.get("historical_packet"),
                "first_pass_attestation": stage.get("first_pass_attestation"),
                "cited_positions": counts["cited_positions"], "candidate_result_positions": candidate,
                "known_repeats": counts.get("duplicate_rendered_rows", counts.get("repeated_athlete_rows", 0)),
                "non_primary_positions": len(non_primary), "verified_staged_versions": len(observations),
                "unresolved_positions": len(unresolved), "exact_supported": 0, "likely_overlap": 0,
                "conflicting_revision": 0, "ambiguous_candidate": 0,
                "no_known_counterpart": None, "not_assessed": candidate,
                "confirmed_distinct_attempts": None}
        snapshot_sha = None
        cross_pair_leads = {f"{left}:{right}": 0 for i, left in enumerate(SOURCES)
                            for right in SOURCES[i + 1:]}
        seen_cross_pairs = set()
        if args.snapshot:
            _, snapshot_path, snapshot_sha, _ = read_pinned_binary("snapshot=" + args.snapshot)
            with sqlite3.connect(f"file:{snapshot_path}?mode=ro", uri=True) as db:
                if db.execute("pragma integrity_check").fetchone()[0] != "ok":
                    raise ValueError("snapshot integrity check failed")
                retained = db.execute("select record_id,source_id,source_object_id,event_date,discipline,"
                                      "citation_json,raw_fields_json,parsed_fields_json from records "
                                      "where kind in ('candidate_position','observation','source_position')").fetchall()
            index = {}
            for record in retained:
                raw = json.loads(record[6])
                parsed = json.loads(record[7])
                for key in names(raw) | names(parsed):
                    index.setdefault(key, {})[record[0]] = record
            cross_index = {}
            for source, item, source_sha, stage_sha in candidate_rows:
                for key in names(item.get("raw_fields", {})):
                    cross_index.setdefault(key, []).append((source, item, source_sha))
            for source, item, source_sha, stage_sha in candidate_rows:
                pid = position_id(item)
                day = event_day(source, item)
                matches = {}
                for key in names(item.get("raw_fields", {})):
                    for record in index.get(key, {}).values():
                        if record[2] in (source_sha, "sha256:" + source_sha):
                            continue
                        if compatible_date(source, day, record[3]):
                            matches["snapshot:" + record[0]] = {
                                "position": record[1] or record[0], "citation": json.loads(record[5] or "null"),
                                "source_sha256": record[2], "event_day": record[3]}
                    for other_source, other, other_sha in cross_index.get(key, []):
                        if other_source == source:
                            continue
                        other_day = event_day(other_source, other)
                        if compatible_date(source, day, other_day, other_source):
                            pair = tuple(sorted((pid, position_id(other))))
                            if pair not in seen_cross_pairs:
                                seen_cross_pairs.add(pair)
                                source_pair = ":".join(sorted((source, other_source), key=SOURCES.index))
                                cross_pair_leads[source_pair] += 1
                            matches["stage:" + position_id(other)] = {
                                "position": position_id(other),
                                "citation": (other.get("source_position") or other).get("citation"),
                                "source_sha256": other_sha, "event_day": other_day}
                report = source_reports[source]
                report["not_assessed"] -= 1
                if matches:
                    report["ambiguous_candidate"] += 1
                    queue.append({"id": "issue172:" + digest(encoded(["overlap", source_sha, pid]))[:24],
                                  "kind": "relationship_candidate", "source_key": source,
                                  "source_sha256": source_sha, "source_position": pid,
                                  "citation": (item.get("source_position") or item).get("citation"),
                                  "evidence_version": snapshot_sha,
                                  "reason": "name lead with compatible or unknown date; attempt relationship unproven",
                                  "related_positions": sorted({m["position"] for m in matches.values() if m["position"]}),
                                  "related_evidence": [matches[key] for key in sorted(matches)],
                                  "status": "pending"})
                else:
                    report["no_known_counterpart"] = (report["no_known_counterpart"] or 0) + 1
            for report in source_reports.values():
                if report["no_known_counterpart"] is None:
                    report["no_known_counterpart"] = 0
        relationship_sha = None
        if args.relationship_evidence:
            _, _, relationship_sha, evidence = read_pinned("relationships=" + args.relationship_evidence)
            if evidence.get("schema") != "issue172-cited-relationships-v1":
                raise ValueError("relationship evidence schema mismatch")
            if evidence.get("corpus_sha256") != args.corpus_sha256:
                raise ValueError("relationship corpus digest mismatch")
            assigned = set()
            for rel in evidence["relationships"]:
                source_position = rel["source_position"]
                kind = rel["classification"]
                if kind not in RELATIONS or source_position not in all_positions or source_position in assigned:
                    raise ValueError("invalid or duplicate relationship classification")
                if not rel.get("citation") or not rel.get("target_citation") or not rel.get("target_source_sha256"):
                    raise ValueError("relationship requires both source citations and target source hash")
                assigned.add(source_position)
                source = all_positions[source_position]
                source_reports[source][kind] += 1
                source_reports[source]["not_assessed"] -= 1
                if kind != "exact_supported":
                    queue.append({"id": "issue172:" + digest(encoded(["relationship", source_position, rel.get("target_position")]))[:24],
                                  "kind": "relationship_candidate", "source_key": source,
                                  "source_sha256": source_reports[source]["source_sha256"],
                                  "source_position": source_position, "citation": rel["citation"],
                                  "evidence_version": relationship_sha,
                                  "reason": kind, "related_positions": [rel.get("target_position")], "status": "pending"})
            if evidence.get("comparison_complete"):
                for name, report in source_reports.items():
                    report["no_known_counterpart"] = report["not_assessed"]
                    report["not_assessed"] = 0
        queue.sort(key=lambda x: x["id"])
        if len({x["id"] for x in queue}) != len(queue):
            raise ValueError("queue ID collision")
        totals = {key: sum(report[key] for report in source_reports.values()) for key in
                  ("cited_positions", "candidate_result_positions", "known_repeats", "non_primary_positions",
                   "verified_staged_versions", "unresolved_positions", "exact_supported", "likely_overlap",
                   "conflicting_revision", "ambiguous_candidate", "not_assessed")}
        totals["no_known_counterpart"] = (sum(r["no_known_counterpart"] for r in source_reports.values())
                                          if all(r["no_known_counterpart"] is not None for r in source_reports.values()) else None)
        totals["confirmed_distinct_attempts"] = None
        audit = {"schema": "issue172-consolidated-audit-v1", "sources": source_reports,
                 "totals": totals, "corpus_sha256": args.corpus_sha256 or snapshot_sha,
                 "snapshot_sha256": snapshot_sha,
                 "combined_import_manifest_sha256": import_manifest_sha,
                 "cross_stage_pair_leads": cross_pair_leads,
                 "provenance": provenance,
                 "relationship_evidence_sha256": relationship_sha,
                 "limitations": ["Overlap is a bounded name and compatible-date lead search over the pinned corpus and nine stages.",
                                 "A name lead does not establish a shared sporting attempt or athlete identity.",
                                 "No known counterpart means no lead in this bounded search, not proof of a distinct attempt.",
                                 "Exact, likely, and conflicting classifications require separate cited relationship evidence."],
                 "attempt_count_policy": "unknown_without_source_backed_distinct-attempt evidence"}
        queue_doc = {"schema": "issue172-owner-queue-v1", "audit_sha256": digest(encoded(audit)), "entries": queue}
        output = Path(args.output_dir)
        output.mkdir(parents=True, exist_ok=True)
        for name, value in (("audit-v1.json", audit), ("owner-queue-v1.json", queue_doc)):
            destination = output / name
            temp = output / (name + ".tmp")
            temp.write_bytes(encoded(value))
            temp.chmod(0o600)
            os.replace(temp, destination)
        print(json.dumps({"audit_sha256": digest(encoded(audit)), "queue_sha256": digest(encoded(queue_doc)),
                          "totals": totals}, sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, str(error) + "\n")


if __name__ == "__main__":
    main()
