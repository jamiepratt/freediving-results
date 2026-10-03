#!/usr/bin/env python3
"""Read-only preflight for a frozen private reconciliation corpus.

The output is aggregate evidence accounting. Positions and observation versions
are deliberately not converted into sporting attempts or identity approvals.
"""

import argparse
import csv
import hashlib
import json
import re
import sqlite3
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

from reconciliation_replay_eligibility import replay_blockers

SNAPSHOT_SCHEMA = "unified-evidence-snapshot/v1"
CHECKED_SCHEMA = "affiliate-name-input/v1"
PG_REVISION_FIELDS = ("job_id", "ordinal", "candidate_id", "artifact_sha256",
                      "source_sha256", "parser_version")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def year_bucket(value):
    if value is None or value == "":
        return "unknown"
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise ValueError("candidate position has invalid event_date")
    try:
        date.fromisoformat(value)
    except ValueError as error:
        raise ValueError("candidate position has invalid event_date") from error
    return value[:4] if value[:4] in ("2025", "2026") else "outside_scope"


def build_pg_binding_report(snapshot_dir, pg_binding_csv, expected_manifest_sha256,
                            expected_sqlite_sha256, expected_pg_binding_sha256):
    """Validate structured snapshot refs against one frozen structural PG export.

    This accounts for evidence versions only. It does not approve attempts or people.
    The export contains no athlete values or payloads, only immutable revision keys.
    """
    snapshot_dir = Path(snapshot_dir).resolve()
    manifest_path = snapshot_dir / "manifest.json"
    db_path = snapshot_dir / "snapshot.sqlite"
    csv_path = Path(pg_binding_csv).resolve()
    expected = ((manifest_path, expected_manifest_sha256, "snapshot manifest"),
                (db_path, expected_sqlite_sha256, "snapshot SQLite"),
                (csv_path, expected_pg_binding_sha256, "PostgreSQL binding"))
    for path, digest, label in expected:
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"expected {label} SHA-256 required")
        if sha256(path) != digest:
            raise ValueError(f"{label} hash mismatch")
    manifest = json.loads(manifest_path.read_text())
    if (manifest.get("schema") != SNAPSHOT_SCHEMA
            or manifest.get("snapshot_sha256") != expected_sqlite_sha256):
        raise ValueError("snapshot manifest binding mismatch")

    revisions = {}
    with csv_path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(PG_REVISION_FIELDS):
            raise ValueError("invalid PostgreSQL binding columns")
        for row in reader:
            if None in row or any(not isinstance(row[field], str) or not row[field]
                                  for field in PG_REVISION_FIELDS):
                raise ValueError("invalid PostgreSQL observation")
            if (not re.fullmatch(r"[0-9a-f]{64}", row["job_id"])
                    or not re.fullmatch(r"[0-9a-f]{64}", row["artifact_sha256"])
                    or not re.fullmatch(r"[0-9a-f]{64}", row["source_sha256"])
                    or not row["ordinal"].isdigit()):
                raise ValueError("invalid PostgreSQL observation")
            row["ordinal"] = int(row["ordinal"])
            key = (row["job_id"], row["ordinal"])
            if key in revisions:
                raise ValueError("duplicate PostgreSQL observation")
            revisions[key] = row

    bound = set()
    bound_citations = {}
    repeated_refs = 0
    parser_disagreements = 0
    scalar_versions = set()
    structured_refs = 0
    uri = db_path.as_uri() + "?mode=ro&immutable=1"
    with sqlite3.connect(uri, uri=True) as db:
        db.execute("PRAGMA query_only=ON")
        if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise ValueError("snapshot SQLite integrity check failed")
        try:
            rows = db.execute("SELECT kind,raw_json,observation_version,source_object_id,parser_version,citation_json "
                              "FROM records")
            for kind, raw_json, scalar, source_object, parser, citation_json in rows:
                if scalar:
                    scalar_versions.add(scalar)
                if kind != "candidate_position":
                    continue
                raw = json.loads(raw_json)
                if not isinstance(raw, dict):
                    raise ValueError("malformed snapshot observation references")
                refs = raw.get("observation_refs") or raw.get("imported_observation_refs") or []
                if not isinstance(refs, list):
                    raise ValueError("malformed snapshot observation references")
                for ref in refs:
                    structured_refs += 1
                    if (not isinstance(ref, dict)
                            or any(field not in ref for field in PG_REVISION_FIELDS
                                   if field != "source_sha256")
                            or not isinstance(ref["job_id"], str)
                            or type(ref["ordinal"]) is not int or ref["ordinal"] < 0):
                        raise ValueError("malformed snapshot observation reference")
                    key = (ref["job_id"], ref["ordinal"])
                    revision = revisions.get(key)
                    if revision is None:
                        raise ValueError("missing PostgreSQL observation")
                    if any(ref.get(field) != revision[field] for field in PG_REVISION_FIELDS
                           if field != "source_sha256"):
                        raise ValueError("mismatched PostgreSQL observation")
                    source_hash = (source_object or "").removeprefix("sha256:")
                    if (source_hash != revision["source_sha256"]
                            or (raw.get("source_sha256") and raw["source_sha256"] != source_hash)
                            or (ref.get("source_sha256") and ref["source_sha256"] != source_hash)):
                        raise ValueError("snapshot source version differs from observation")
                    if parser != revision["parser_version"]:
                        parser_disagreements += 1
                    citation = json.loads(citation_json) if citation_json else None
                    if key in bound:
                        if not citation or citation != bound_citations[key]:
                            raise ValueError("ambiguous snapshot observation reference")
                        repeated_refs += 1
                    else:
                        bound_citations[key] = citation
                    bound.add(key)
        except sqlite3.DatabaseError as error:
            raise ValueError("snapshot candidate records unavailable") from error
    candidate_ids = {revision["candidate_id"] for revision in revisions.values()}
    return {"schema": "reconciliation-pg-binding/v1",
            "snapshot": {"manifest_sha256": expected_manifest_sha256,
                         "sqlite_sha256": expected_sqlite_sha256,
                         "cutoff": manifest.get("cutoff")},
            "pg_binding_sha256": expected_pg_binding_sha256,
            "pg_observation_versions": len(revisions),
            "snapshot_structured_pg_refs": structured_refs,
            "bound_pg_observation_versions": len(bound),
            "repeated_pg_refs_same_position": repeated_refs,
            "snapshot_record_parser_disagreements": parser_disagreements,
            "unreferenced_pg_observation_versions": len(revisions) - len(bound),
            "snapshot_observation_version_strings": len(scalar_versions),
            "unmatched_non_pg_strings": len(scalar_versions - candidate_ids),
            "confirmed_distinct_attempts": None}


def build_report(snapshot_dir, checked_input, expected_checked_sha256):
    snapshot_dir, checked_input = Path(snapshot_dir).resolve(), Path(checked_input).resolve()
    manifest_path, db_path = snapshot_dir / "manifest.json", snapshot_dir / "snapshot.sqlite"
    if not re.fullmatch(r"[0-9a-f]{64}", expected_checked_sha256):
        raise ValueError("expected checked input SHA-256 required")
    checked_hash = sha256(checked_input)
    if checked_hash != expected_checked_sha256:
        raise ValueError("checked input hash mismatch")
    manifest_hash, db_hash = sha256(manifest_path), sha256(db_path)
    manifest, checked = json.loads(manifest_path.read_text()), json.loads(checked_input.read_text())
    if manifest.get("schema") != SNAPSHOT_SCHEMA or checked.get("schema") != CHECKED_SCHEMA:
        raise ValueError("unsupported frozen input schema")
    if manifest.get("snapshot_sha256") != db_hash:
        raise ValueError("snapshot hash mismatch")
    binding = checked.get("snapshot") or {}
    if (binding.get("manifest_sha256") != manifest_hash or binding.get("sqlite_sha256") != db_hash
            or binding.get("cutoff") != manifest.get("cutoff")
            or Path(binding.get("path", "")).resolve() != snapshot_dir):
        raise ValueError("manifest binding mismatch")
    if manifest.get("confirmed_distinct_attempts") is not None:
        raise ValueError("snapshot claims confirmed attempts without decision ledger")

    manifest_inputs = {"declared": 0, "verified": 0, "missing": 0, "unverified": 0}
    for item in manifest.get("inputs", {}).values():
        manifest_inputs["declared"] += 1
        path, expected = item.get("path"), item.get("sha256")
        if not isinstance(path, str) or not path or not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            manifest_inputs["unverified"] += 1
            continue
        input_path = Path(path)
        if not input_path.is_absolute():
            input_path = snapshot_dir / input_path
        if not input_path.exists():
            manifest_inputs["missing"] += 1
            continue
        if not input_path.is_file():
            raise ValueError("manifest input is not a file")
        if sha256(input_path) != expected:
            raise ValueError("manifest input hash mismatch")
        manifest_inputs["verified"] += 1

    buckets = Counter({key: 0 for key in ("2025", "2026", "unknown", "outside_scope")})
    by_source = defaultdict(lambda: defaultdict(lambda: {"candidate_positions": 0,
                                                         "source_object_refs": set(),
                                                         "observation_version_refs": set()}))
    unknown_by_source = Counter()
    source_records = 0
    source_object_refs = set()
    obs_refs = set()
    input_counts = Counter()
    kind_counts = Counter()
    uri = db_path.as_uri() + "?mode=ro&immutable=1"
    with sqlite3.connect(uri, uri=True) as db:
        db.execute("PRAGMA query_only=ON")
        integrity = db.execute("PRAGMA quick_check").fetchone()[0]
        if integrity != "ok":
            raise ValueError("snapshot SQLite integrity check failed")
        try:
            rows = db.execute("SELECT source_name, kind, source_object_id, observation_version, event_date FROM records")
            for source, kind, source_object, observation, event_date in rows:
                input_counts[source] += 1
                kind_counts[kind] += 1
                if kind == "source":
                    source_records += 1
                if kind != "candidate_position":
                    continue
                bucket = year_bucket(event_date)
                buckets[bucket] += 1
                group = by_source[source][bucket]
                group["candidate_positions"] += 1
                if source_object:
                    group["source_object_refs"].add(source_object)
                    source_object_refs.add(source_object)
                if observation:
                    group["observation_version_refs"].add(observation)
                    obs_refs.add(observation)
                if bucket == "unknown":
                    unknown_by_source[source] += 1
        except sqlite3.DatabaseError as error:
            raise ValueError("snapshot records table unavailable") from error
    expected_counts = {name: item["record_count"] for name, item in manifest.get("inputs", {}).items()
                       if item.get("status") == "included"}
    if dict(input_counts) != {name: count for name, count in expected_counts.items() if count}:
        raise ValueError("snapshot record counts disagree with manifest")
    rows = []
    for source in sorted(by_source):
        for bucket in ("2025", "2026", "unknown", "outside_scope"):
            group = by_source[source].get(bucket)
            if group:
                rows.append({"source": source, "year": bucket,
                             "candidate_positions": group["candidate_positions"],
                             "source_object_refs": len(group["source_object_refs"]),
                             "observation_version_refs": len(group["observation_version_refs"])})
    snapshot = {"manifest_sha256": manifest_hash, "sqlite_sha256": db_hash,
                "cutoff": manifest["cutoff"]}
    return {"schema": "reconciliation-corpus-preflight/v1",
            "snapshot": snapshot,
            "checked_name_input": {"sha256": checked_hash,
                                   "assertion_count": len(checked.get("assertions", [])),
                                   "gap_count": len(checked.get("gaps", []))},
            "source_records": source_records,
            "source_object_refs": len(source_object_refs),
            "record_kinds": dict(sorted(kind_counts.items())),
            "manifest_inputs": manifest_inputs,
            "candidate_positions": sum(buckets.values()),
            "candidate_positions_by_year": dict(buckets),
            "observation_version_refs": len(obs_refs),
            "unknown_date_by_source": dict(sorted(unknown_by_source.items())),
            "by_source_year": rows,
            "confirmed_distinct_attempts": None,
            "identity_approvals": None,
            "decision_ledger_revision": None,
            "replay_blockers": replay_blockers(snapshot, None)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot-dir", required=True, type=Path)
    parser.add_argument("--checked-input", type=Path)
    parser.add_argument("--checked-sha256")
    parser.add_argument("--pg-binding-csv", type=Path)
    parser.add_argument("--pg-binding-sha256")
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--sqlite-sha256")
    parser.add_argument("--output", required=True, type=Path, help="Private aggregate JSON report path")
    args = parser.parse_args()
    if args.pg_binding_csv:
        if not all((args.pg_binding_sha256, args.manifest_sha256, args.sqlite_sha256)):
            parser.error("PG binding requires CSV, binding, manifest and SQLite SHA-256 values")
        report = build_pg_binding_report(args.snapshot_dir, args.pg_binding_csv,
                                         args.manifest_sha256, args.sqlite_sha256,
                                         args.pg_binding_sha256)
    else:
        if not args.checked_input or not args.checked_sha256:
            parser.error("preflight requires checked input and its SHA-256")
        report = build_report(args.snapshot_dir, args.checked_input, args.checked_sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    summary = {"output": str(args.output.resolve())}
    if args.pg_binding_csv:
        summary.update({"pg_observation_versions": report["pg_observation_versions"],
                        "bound_pg_observation_versions": report["bound_pg_observation_versions"]})
    else:
        summary.update({"candidate_positions": report["candidate_positions"],
                        "unknown_dates": report["candidate_positions_by_year"]["unknown"]})
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
