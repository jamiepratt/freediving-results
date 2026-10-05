#!/usr/bin/env python3
"""Replay pinned issue #172 stage bytes into a private isolated SQLite store.

Input spec v1 names every stage, original source and upstream evidence object with
its byte hash. This command never writes to the owner or public databases.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import tempfile


SHA = re.compile(r"[0-9a-f]{64}\Z")
STAGE_SCHEMAS = {"fipsas-image-verified-stage/v1", "cmas-worldcup-2026-verified-stage/v1",
                 "italian-open-2025-verified-stage/v1", "cagliari-verified-stage/v1",
                 "firenze-verified-stage/v1", "asti-blu-verified-stage/v1"}
MANIFEST = "manifest-v1.json"
DATABASE = "combined-isolated-v1.sqlite"
PROOF = "replay-proof-v1.json"


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True,
                       separators=(",", ":")) + "\n").encode()


def checked_object(entry, label):
    require(isinstance(entry, dict) and set(entry) == {"path", "sha256"},
            f"{label}: path and SHA256 required")
    require(isinstance(entry["sha256"], str) and SHA.fullmatch(entry["sha256"]),
            f"{label}: invalid SHA256")
    path = Path(entry["path"])
    require(path.is_absolute() and path.is_file(), f"{label}: file missing")
    raw = path.read_bytes()
    require(digest(raw) == entry["sha256"], f"{label}: SHA256 mismatch")
    return raw


def pinned_hashes(value, prefix=""):
    result = {}
    for key, item in value.items():
        name = f"{prefix}.{key}" if prefix else key
        if isinstance(item, dict):
            result.update(pinned_hashes(item, name))
        elif item is not None:
            result[name] = item
    return result


def check_stage(item):
    name = item.get("name")
    require(isinstance(name, str) and name and re.fullmatch(r"[a-z0-9-]+", name),
            "source name invalid")
    raw = checked_object(item.get("stage"), f"{name} stage")
    stage = json.loads(raw)
    source_raw = checked_object(item.get("source"), f"{name} source")
    source_sha = digest(source_raw)
    require(stage.get("schema") in STAGE_SCHEMAS, f"{name}: unsupported stage schema")
    require(stage.get("source_sha256") == source_sha, f"{name}: source binding mismatch")
    parser = stage.get("parser_version")
    require(isinstance(parser, str) and parser.strip(), f"{name}: parser version missing")
    input_tree = stage.get("input_sha256")
    require(isinstance(input_tree, dict), f"{name}: upstream evidence absent")
    pinned_inputs = pinned_hashes(input_tree)
    inputs = item.get("inputs")
    require(isinstance(pinned_inputs, dict) and pinned_inputs and
            isinstance(inputs, dict) and set(inputs) == set(pinned_inputs),
            f"{name}: upstream evidence set mismatch")
    for key, expected in pinned_inputs.items():
        require(isinstance(expected, str) and SHA.fullmatch(expected),
                f"{name}: upstream SHA256 invalid")
        require(checked_object(inputs[key], f"{name} {key}") is not None and
                inputs[key]["sha256"] == expected, f"{name}: upstream binding mismatch")
    versions = stage.get("observation_versions")
    unresolved = stage.get("unresolved_positions")
    non_primary = stage.get("non_primary_positions", [])
    counts = stage.get("counts")
    require(all(isinstance(x, list) for x in (versions, unresolved, non_primary))
            and isinstance(counts, dict), f"{name}: position lists missing")
    require(counts.get("candidate_result_positions", len(versions) + len(unresolved))
            == len(versions) + len(unresolved)
            and counts.get("verified_staged_versions") == len(versions)
            and counts.get("unresolved_positions") == len(unresolved)
            and counts.get("cited_positions") == len(versions) + len(unresolved) + len(non_primary)
            and counts.get("non_primary_positions", len(non_primary)) == len(non_primary),
            f"{name}: position accounting mismatch")
    positions, ids = set(), set()
    for row in versions:
        position = row.get("source_position", {})
        key = position.get("id")
        version_id = row.get("id")
        require(isinstance(key, str) and key and isinstance(version_id, str) and version_id
                and row.get("source_sha256") == source_sha
                and row.get("parser_version") == parser
                and isinstance(position.get("citation"), dict)
                and isinstance(row.get("raw_fields"), dict)
                and row.get("interpreted_fields") == {},
                f"{name}: invalid observation version")
        require(key not in positions and version_id not in ids,
                f"{name}: duplicate observation position or version")
        positions.add(key)
        ids.add(version_id)
    for row in unresolved + non_primary:
        key = row.get("id")
        require(isinstance(key, str) and key and key not in positions
                and isinstance(row.get("citation"), dict),
                f"{name}: duplicate or uncited position")
        positions.add(key)
    return stage, {"name": name, "schema": stage["schema"],
                   "stage_sha256": item["stage"]["sha256"],
                   "source_sha256": source_sha, "parser_version": parser,
                   "inputs": {key: pinned_inputs[key] for key in sorted(pinned_inputs)},
                   "counts": counts,
                   "first_pass_attestation": stage.get("first_pass_attestation"),
                   "historical_packet": stage.get("historical_packet"),
                   "candidate_result_positions": len(versions) + len(unresolved),
                   "non_primary_positions": len(non_primary)}


def store(stages, destination, old_database=None):
    if old_database:
        with sqlite3.connect(f"file:{old_database}?mode=ro", uri=True) as old:
            with sqlite3.connect(destination) as new:
                old.backup(new)
    db = sqlite3.connect(destination)
    try:
        db.execute("BEGIN IMMEDIATE")
        db.execute("CREATE TABLE IF NOT EXISTS combined_meta(schema TEXT NOT NULL)")
        marker = db.execute("SELECT schema FROM combined_meta").fetchall()
        if marker:
            require(marker == [("issue172-combined-isolated-store/v1",)], "store marker mismatch")
        else:
            db.execute("INSERT INTO combined_meta VALUES (?)", ("issue172-combined-isolated-store/v1",))
        db.execute("""CREATE TABLE IF NOT EXISTS observation_versions (
            id TEXT PRIMARY KEY, source_sha256 TEXT NOT NULL, source_position_id TEXT NOT NULL,
            parser_version TEXT NOT NULL, stage_sha256 TEXT NOT NULL,
            raw_json TEXT NOT NULL, payload_json TEXT NOT NULL,
            UNIQUE(source_sha256, source_position_id, parser_version))""")
        db.execute("""CREATE TABLE IF NOT EXISTS stage_imports (
            stage_sha256 TEXT PRIMARY KEY, source_name TEXT NOT NULL,
            source_sha256 TEXT NOT NULL, parser_version TEXT NOT NULL,
            stage_json TEXT NOT NULL)""")
        for stage, record in stages:
            for row in stage["observation_versions"]:
                payload = encoded(row).decode().strip()
                raw = encoded(row["raw_fields"]).decode().strip()
                key = (record["source_sha256"], row["source_position"]["id"],
                       record["parser_version"])
                previous = db.execute("""SELECT id, raw_json, payload_json FROM observation_versions
                    WHERE source_sha256=? AND source_position_id=? AND parser_version=?""", key).fetchone()
                if previous:
                    require(previous == (row["id"], raw, payload),
                            "conflicting source position/parser version")
                else:
                    db.execute("""INSERT INTO observation_versions VALUES (?, ?, ?, ?, ?, ?, ?)""",
                               (row["id"], *key, record["stage_sha256"], raw, payload))
            stage_json = encoded(stage).decode().strip()
            previous = db.execute("SELECT source_name, stage_json FROM stage_imports WHERE stage_sha256=?",
                                  (record["stage_sha256"],)).fetchone()
            if previous:
                require(previous == (record["name"], stage_json), "conflicting stage import")
            else:
                db.execute("INSERT INTO stage_imports VALUES (?, ?, ?, ?, ?)",
                           (record["stage_sha256"], record["name"], record["source_sha256"],
                            record["parser_version"], stage_json))
        db.commit()
        require(db.execute("PRAGMA integrity_check").fetchone() == ("ok",), "SQLite integrity failed")
        return {"stored_observation_versions": db.execute(
            "SELECT count(*) FROM observation_versions").fetchone()[0],
                "stored_stage_imports": db.execute("SELECT count(*) FROM stage_imports").fetchone()[0]}
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


def replay(spec_path, output_dir, expected_sources):
    spec = json.loads(spec_path.read_bytes())
    entries = spec.get("sources")
    require(spec.get("schema") == "issue172-combined-input/v1" and isinstance(entries, list)
            and len(entries) == expected_sources, "source manifest count/schema mismatch")
    checked = [check_stage(item) for item in entries]
    records = [record for _, record in checked]
    require(len({r["name"] for r in records}) == len(records), "duplicate source name")
    require(len({r["source_sha256"] for r in records}) == len(records), "duplicate source bytes")
    all_ids = [row["id"] for stage, _ in checked for row in stage["observation_versions"]]
    require(len(all_ids) == len(set(all_ids)), "duplicate observation version across sources")
    all_positions = [row["source_position"]["id"] for stage, _ in checked
                     for row in stage["observation_versions"]]
    require(len(all_positions) == len(set(all_positions)),
            "duplicate source position across sources")
    checked.sort(key=lambda pair: pair[1]["name"])
    records.sort(key=lambda record: record["name"])
    counts = {"candidate_result_positions": sum(r["candidate_result_positions"] for r in records),
              "verified_staged_versions": sum(r["counts"]["verified_staged_versions"] for r in records),
              "unresolved_positions": sum(r["counts"]["unresolved_positions"] for r in records),
              "cited_positions": sum(r["counts"]["cited_positions"] for r in records),
              "non_primary_positions": sum(r["non_primary_positions"] for r in records)}
    manifest = {"schema": "issue172-combined-manifest/v1", "sources": records, "counts": counts,
                "confirmed_distinct_attempts": None}
    manifest_raw = encoded(manifest)
    output_dir = output_dir.resolve()
    if output_dir.exists() and (output_dir / MANIFEST).is_file():
        if (output_dir / MANIFEST).read_bytes() == manifest_raw:
            require((output_dir / DATABASE).is_file() and (output_dir / PROOF).is_file(),
                    "existing replay incomplete")
            with sqlite3.connect(f"file:{output_dir / DATABASE}?mode=ro", uri=True) as db:
                require(db.execute("PRAGMA integrity_check").fetchone() == ("ok",),
                        "existing replay integrity failed")
                for stage, record in checked:
                    stored = db.execute("SELECT source_name, stage_json FROM stage_imports WHERE stage_sha256=?",
                                        (record["stage_sha256"],)).fetchone()
                    require(stored == (record["name"], encoded(stage).decode().strip()),
                            "existing stage replay mismatch")
                    for row in stage["observation_versions"]:
                        stored_row = db.execute("""SELECT raw_json, payload_json FROM observation_versions
                            WHERE id=? AND source_sha256=? AND source_position_id=? AND parser_version=?""",
                            (row["id"], record["source_sha256"], row["source_position"]["id"],
                             record["parser_version"])).fetchone()
                        require(stored_row == (encoded(row["raw_fields"]).decode().strip(),
                                               encoded(row).decode().strip()),
                                "existing observation replay mismatch")
                require(db.execute("SELECT count(*) FROM observation_versions").fetchone()[0]
                        >= counts["verified_staged_versions"], "existing replay count mismatch")
            proof = json.loads((output_dir / PROOF).read_bytes())
            require(proof.get("manifest_sha256") == digest(manifest_raw)
                    and proof.get("counts") == counts and proof.get("sqlite_integrity") == "ok",
                    "existing replay proof mismatch")
            return proof
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".issue172-replay-", dir=output_dir.parent) as temp:
        temp_path = Path(temp)
        old_db = output_dir / DATABASE if output_dir.exists() and (output_dir / DATABASE).exists() else None
        stored = store(checked, temp_path / DATABASE, old_db)
        (temp_path / MANIFEST).write_bytes(manifest_raw)
        proof = {"schema": "issue172-combined-replay-proof/v1",
                 "manifest_sha256": digest(manifest_raw), "counts": counts, **stored,
                 "sqlite_integrity": "ok", "identity_or_attempt_approval": False,
                 "live_activation": False}
        (temp_path / PROOF).write_bytes(encoded(proof))
        for path in temp_path.iterdir():
            os.chmod(path, 0o600)
        if output_dir.exists():
            backup = output_dir.with_name(output_dir.name + ".previous")
            require(not backup.exists(), "previous replay backup exists")
            output_dir.rename(backup)
            try:
                temp_path.rename(output_dir)
            except BaseException:
                backup.rename(output_dir)
                raise
            shutil.rmtree(backup)
        else:
            temp_path.rename(output_dir)
        return proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-spec", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--expected-sources", type=int, default=9)
    args = parser.parse_args()
    try:
        require(args.expected_sources > 0, "expected sources must be positive")
        print(json.dumps(replay(args.input_spec, args.output_dir, args.expected_sources),
                         sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
        print(f"Combined replay rejected: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
