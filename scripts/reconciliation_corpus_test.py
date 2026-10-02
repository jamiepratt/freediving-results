#!/usr/bin/env python3
"""Read-only preflight for a frozen private reconciliation corpus.

The output is aggregate evidence accounting. Positions and observation versions
are deliberately not converted into sporting attempts or identity approvals.
"""

import argparse
import hashlib
import json
import re
import sqlite3
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

SNAPSHOT_SCHEMA = "unified-evidence-snapshot/v1"
CHECKED_SCHEMA = "affiliate-name-input/v1"


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

    buckets = Counter({key: 0 for key in ("2025", "2026", "unknown", "outside_scope")})
    by_source = defaultdict(lambda: defaultdict(lambda: {"candidate_positions": 0,
                                                         "source_object_refs": set(),
                                                         "observation_version_refs": set()}))
    unknown_by_source = Counter()
    source_records = 0
    obs_refs = set()
    input_counts = Counter()
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
    return {"schema": "reconciliation-corpus-preflight/v1",
            "snapshot": {"manifest_sha256": manifest_hash, "sqlite_sha256": db_hash,
                         "cutoff": manifest["cutoff"]},
            "checked_name_input": {"sha256": checked_hash,
                                   "assertion_count": len(checked.get("assertions", [])),
                                   "gap_count": len(checked.get("gaps", []))},
            "source_records": source_records,
            "candidate_positions": sum(buckets.values()),
            "candidate_positions_by_year": dict(buckets),
            "observation_version_refs": len(obs_refs),
            "unknown_date_by_source": dict(sorted(unknown_by_source.items())),
            "by_source_year": rows,
            "confirmed_distinct_attempts": None,
            "identity_approvals": None,
            "decision_ledger_revision": None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot-dir", required=True, type=Path)
    parser.add_argument("--checked-input", required=True, type=Path)
    parser.add_argument("--checked-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path, help="Private aggregate JSON report path")
    args = parser.parse_args()
    report = build_report(args.snapshot_dir, args.checked_input, args.checked_sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output.resolve()), "candidate_positions": report["candidate_positions"],
                      "unknown_dates": report["candidate_positions_by_year"]["unknown"]}, sort_keys=True))


if __name__ == "__main__":
    main()
