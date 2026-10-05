#!/usr/bin/env python3
"""Verify a retained World Cup visual packet and stage private observation versions.

The second pass is a source-only transcription. Its blind attestation is retained
as an assertion, not treated as proof. Image inspections are independent input.
Only an explicitly selected isolated SQLite store can change. Identity, attempt
and publication state remain untouched.
"""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import struct
import subprocess
import sys
import tempfile


SOURCE_SHA256 = "1d98a22d67708f4a31ce666b84af1e81aba342c9c52e34a2e2c980cb3f010db5"
FIELDS = ("Last Name", "First Name", "Country", "Discipline", "AP (m)",
          "Top time", "Dive Time", "RP (m)", "Card", "Record", "Points")
SHA = re.compile(r"[0-9a-f]{64}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def bound_json(path, expected, label):
    require(isinstance(expected, str) and SHA.fullmatch(expected), f"{label} SHA256 invalid")
    raw = path.read_bytes()
    require(sha(raw) == expected, f"{label} SHA256 mismatch")
    return json.loads(raw)


def check_fields(fields, label):
    require(isinstance(fields, dict) and set(fields) == set(FIELDS),
            f"{label}: raw fields incomplete")
    require(all(value is None or isinstance(value, str) for value in fields.values()),
            f"{label}: raw field must be text or null")


def check_bbox(box, width, height):
    require(isinstance(box, list) and len(box) == 4
            and all(type(n) is int for n in box)
            and 0 <= box[0] < box[2] <= width and 0 <= box[1] < box[3] <= height,
            "invalid cited image bbox")


def import_stage(stage_path, stage_sha256, store_path, *,
                 stage_schema="cmas-worldcup-2026-verified-stage/v1",
                 store_schema="cmas-worldcup-2026-isolated-store/v1",
                 marker_table="worldcup_stage_meta"):
    """Insert a checked JSON stage into an isolated SQLite store in one transaction."""
    stage = bound_json(stage_path, stage_sha256, "stage")
    require(stage.get("schema") == stage_schema,
            "unsupported stage schema")
    source, parser = stage.get("source_sha256"), stage.get("parser_version")
    require(isinstance(source, str) and SHA.fullmatch(source)
            and isinstance(parser, str) and parser.strip(), "stage source/parser invalid")
    versions, unresolved, counts = (stage.get("observation_versions"),
                                    stage.get("unresolved_positions"), stage.get("counts"))
    non_primary = stage.get("non_primary_positions", [])
    require(isinstance(versions, list) and isinstance(unresolved, list)
            and isinstance(non_primary, list)
            and isinstance(counts, dict), "stage accounting absent")
    require(counts.get("cited_positions") == len(versions) + len(unresolved) + len(non_primary)
            and counts.get("verified_staged_versions") == len(versions)
            and counts.get("unresolved_positions") == len(unresolved)
            and (not non_primary or counts.get("non_primary_positions") == len(non_primary)),
            "stage source-position accounting mismatch")
    seen_positions, seen_versions = set(), set()
    for item in versions:
        position = item.get("source_position", {}).get("id")
        require(item.get("source_sha256") == source
                and item.get("parser_version") == parser
                and isinstance(position, str) and position
                and isinstance(item.get("id"), str) and item["id"].startswith("observation-version:")
                and isinstance(item.get("raw_fields"), dict)
                and item.get("interpreted_fields") == {}, "invalid staged observation")
        if stage_schema == "italian-open-2025-verified-stage/v1":
            require(item.get("source_role") == "event_result_table",
                    "non-result observation cannot be imported")
        require(position not in seen_positions and item["id"] not in seen_versions,
                "duplicate staged observation position or version")
        seen_positions.add(position)
        seen_versions.add(item["id"])
    for item in unresolved:
        position = item.get("id")
        require(isinstance(position, str) and position and position not in seen_positions,
                "duplicate or invalid unresolved position")
        seen_positions.add(position)
        if stage_schema == "italian-open-2025-verified-stage/v1":
            require(item.get("source_role") == "event_result_table",
                    "unresolved position role invalid")
    for item in non_primary:
        position = item.get("id")
        require(isinstance(position, str) and position and position not in seen_positions,
                "duplicate or invalid non-primary position")
        seen_positions.add(position)
        if stage_schema == "italian-open-2025-verified-stage/v1":
            require(item.get("disposition") in ("aggregate", "summary", "duplicate_render")
                    and item.get("source_role") in ("aggregate", "summary", "duplicate_render"),
                    "non-primary position role invalid")
    require(len(seen_positions) == counts["cited_positions"], "stage position accounting mismatch")
    if stage_schema == "italian-open-2025-verified-stage/v1":
        require(counts.get("candidate_result_positions") == len(versions) + len(unresolved)
                and counts.get("aggregate_rows_excluded") == sum(
                    x["disposition"] == "aggregate" for x in non_primary)
                and counts.get("summary_rows_excluded") == sum(
                    x["disposition"] == "summary" for x in non_primary)
                and counts.get("duplicate_rendered_rows") == sum(
                    x["disposition"] == "duplicate_render" for x in non_primary),
                "Italian Open stage role accounting mismatch")
    store_path = store_path.resolve()
    new_store = not store_path.exists()
    if not new_store:
        with sqlite3.connect(f"file:{store_path}?mode=ro", uri=True) as check:
            marker = check.execute("select name from sqlite_master where type='table' and name=?",
                                   (marker_table,)).fetchone()
            require(marker is not None, "existing SQLite file is not an isolated stage store")
    store_path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(store_path)
    if new_store:
        os.chmod(store_path, 0o600)
    try:
        db.execute("BEGIN IMMEDIATE")
        db.execute(f"CREATE TABLE IF NOT EXISTS {marker_table} (schema TEXT NOT NULL)")
        marker = db.execute(f"SELECT schema FROM {marker_table}").fetchall()
        if not marker:
            db.execute(f"INSERT INTO {marker_table} VALUES (?)", (store_schema,))
        else:
            require(marker == [(store_schema,)], "store marker mismatch")
        db.execute("""CREATE TABLE IF NOT EXISTS observation_versions (
            id TEXT PRIMARY KEY, source_sha256 TEXT NOT NULL,
            source_position_id TEXT NOT NULL, parser_version TEXT NOT NULL,
            stage_sha256 TEXT NOT NULL, payload_json TEXT NOT NULL,
            UNIQUE(source_sha256, source_position_id, parser_version))""")
        db.execute("""CREATE TABLE IF NOT EXISTS stage_imports (
            stage_sha256 TEXT PRIMARY KEY, source_sha256 TEXT NOT NULL,
            parser_version TEXT NOT NULL, cited_positions INTEGER NOT NULL,
            staged_versions INTEGER NOT NULL, unresolved_positions INTEGER NOT NULL,
            unresolved_json TEXT NOT NULL)""")
        if "non_primary_positions" in stage:
            db.execute("""CREATE TABLE IF NOT EXISTS non_primary_positions (
                stage_sha256 TEXT NOT NULL, source_position_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                PRIMARY KEY(stage_sha256, source_position_id))""")
            for item in non_primary:
                db.execute("""INSERT OR IGNORE INTO non_primary_positions
                    (stage_sha256, source_position_id, payload_json) VALUES (?, ?, ?)""",
                    (stage_sha256, item["id"], json.dumps(item, ensure_ascii=False,
                     sort_keys=True, separators=(",", ":"))))
        for item in versions:
            payload = json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            position = item["source_position"]["id"]
            previous = db.execute("""SELECT id, payload_json FROM observation_versions
                WHERE source_sha256=? AND source_position_id=? AND parser_version=?""",
                (source, position, parser)).fetchone()
            if previous:
                require(previous == (item["id"], payload),
                        "conflicting staged observation for source position/parser")
                continue
            db.execute("""INSERT INTO observation_versions
                (id, source_sha256, source_position_id, parser_version, stage_sha256, payload_json)
                VALUES (?, ?, ?, ?, ?, ?)""",
                (item["id"], source, position, parser, stage_sha256, payload))
        db.execute("""INSERT OR IGNORE INTO stage_imports
            (stage_sha256, source_sha256, parser_version, cited_positions,
             staged_versions, unresolved_positions, unresolved_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (stage_sha256, source, parser, counts["cited_positions"], len(versions),
             len(unresolved), json.dumps(unresolved, ensure_ascii=False,
                                         sort_keys=True, separators=(",", ":"))))
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()
    return {"store": str(store_path), "stage_sha256": stage_sha256,
            "source_sha256": source, "parser_version": parser,
            "stored_observation_versions": len(versions),
            "unresolved_positions": len(unresolved)}


def build(args):
    pdf_raw = args.pdf.read_bytes()
    source_hash = sha(pdf_raw)
    require(source_hash == args.expected_source_sha256, "PDF source SHA256 mismatch")
    packet = bound_json(args.packet, args.packet_sha256, "first packet")
    second = bound_json(args.second_pass, args.second_pass_sha256, "second pass")
    receipt = bound_json(args.receipt, args.receipt_sha256, "receipt")
    require(packet.get("schema") == "cmas-worldcup-2026-visual-evidence/v1", "first packet schema mismatch")
    require(packet.get("source_sha256") == source_hash
            and packet.get("source_bytes") == len(pdf_raw)
            and packet.get("source", {}).get("id") == f"sha256:{source_hash}"
            and packet["source"].get("original_sha256") == source_hash,
            "first packet source binding mismatch")
    if receipt.get("schema") == "private-source-bundle/v1":
        candidates = [item for item in receipt.get("sources", [])
                      if item.get("id") == f"sha256:{source_hash}"]
        require(len(candidates) == 1, "receipt must identify one PDF source")
        acquired = candidates[0]
        acquisition = acquired.get("receipt", {})
        require(acquired.get("sha256") == source_hash
                and acquired.get("bytes") == len(pdf_raw)
                and acquired.get("content_type") == "application/pdf"
                and acquired.get("status") == "included"
                and isinstance(acquisition.get("acquisition_id"), str)
                and acquisition["acquisition_id"]
                and isinstance(acquisition.get("retrieved_at"), str)
                and acquisition["retrieved_at"], "acquisition receipt mismatch")
        retained_object = (args.receipt.parent / acquired["object"]).resolve()
        require(sha(retained_object.read_bytes()) == source_hash,
                "receipt retained object SHA256 mismatch")
    else:
        require(receipt.get("source_sha256") == source_hash
                and receipt.get("bytes") == len(pdf_raw), "receipt source binding mismatch")
    require(second.get("schema") == "cmas-worldcup-2026-second-pass/v1"
            and second.get("source_sha256") == source_hash
            and second.get("blind_to_first_pass") is True
            and isinstance(second.get("transcriber"), str) and second["transcriber"].strip(),
            "second pass source or independence attestation missing")
    require(isinstance(args.parser_version, str) and args.parser_version.strip(),
            "parser version required")
    pages = packet.get("pages")
    require(isinstance(pages, list) and pages and len(pages) == len(args.render),
            "render/page coverage mismatch")
    first_rows = {}
    renders = {}
    seen_ids = set()
    for page_packet, render_path in zip(pages, args.render):
        page = page_packet.get("page")
        require(type(page) is int and page > 0 and page not in renders,
                "duplicate or invalid packet page")
        raw = render_path.read_bytes()
        expected = page_packet.get("render", {})
        require(sha(raw) == expected.get("sha256"), f"page {page}: render SHA256 mismatch")
        require(len(raw) >= 24 and raw[:8] == b"\x89PNG\r\n\x1a\n"
                and raw[12:16] == b"IHDR", f"page {page}: render PNG header invalid")
        width, height = struct.unpack(">II", raw[16:24])
        require([width, height] == [expected.get("pixel_width"), expected.get("pixel_height")],
                f"page {page}: render dimensions mismatch")
        renders[page] = {"sha256": expected["sha256"], "width": width, "height": height}
        rows = page_packet.get("rows")
        require(isinstance(rows, list) and rows, f"page {page}: rows absent")
        require([row.get("row") for row in rows] == list(range(1, len(rows) + 1)),
                f"page {page}: row positions not consecutive")
        for row in rows:
            key = (page, row["row"])
            require(key not in first_rows and isinstance(row.get("id"), str)
                    and row["id"] not in seen_ids, "duplicate source position")
            seen_ids.add(row["id"])
            citation = row.get("citation", {})
            require(citation.get("page") == page
                    and citation.get("region", {}).get("units") == "rendered_px_180dpi",
                    "source citation mismatch")
            check_bbox(citation["region"].get("bbox"), width, height)
            check_fields(row.get("fields"), f"page {page} row {row['row']}")
            require(isinstance(row.get("uncertainties"), list), "packet uncertainty list missing")
            require(isinstance(row.get("transcription_notes", []), list),
                    "packet transcription notes invalid")
            first_rows[key] = row
    require(packet.get("counts", {}).get("source_positions") == len(first_rows),
            "packet source-position accounting mismatch")
    if source_hash == SOURCE_SHA256:
        require(len(first_rows) == 126 and [len(page["rows"]) for page in pages] == [33, 32, 31, 30],
                "official World Cup row accounting mismatch")
    second_rows = {}
    require(isinstance(second.get("rows"), list), "second pass rows missing")
    for row in second["rows"]:
        key = (row.get("page"), row.get("row"))
        require(key in first_rows and key not in second_rows, "second pass row coverage or duplicate mismatch")
        check_fields(row.get("fields"), f"second pass {key}")
        uncertain = row.get("uncertain_fields")
        require(isinstance(uncertain, list) and set(uncertain) <= set(FIELDS)
                and len(set(uncertain)) == len(uncertain), "second pass uncertain fields invalid")
        second_rows[key] = row
    require(set(second_rows) == set(first_rows), "second pass row coverage mismatch")
    differences = {}
    for key, first in first_rows.items():
        later = second_rows[key]
        changed = [field for field in FIELDS if first["fields"][field] != later["fields"][field]]
        if first["uncertainties"] or later["uncertain_fields"]:
            changed.append("uncertain_fields")
        if changed:
            differences[key] = changed
    disagreements = set(differences)
    agreements = set(first_rows) - disagreements
    # Match scan_verification's source-bound SHA ranking, adapted to whole rows.
    sample_size = math.ceil(len(agreements) / 10)
    sample = set(sorted(agreements, key=lambda key: (sha(f"{source_hash}:page:{key[0]}:row:{key[1]}".encode()), key))[:sample_size])
    comparison = {"schema": "cmas-worldcup-2026-comparison/v1",
                  "source_sha256": source_hash,
                  "first_packet_sha256": args.packet_sha256,
                  "second_pass_sha256": args.second_pass_sha256,
                  "disagreements": [{"page": key[0], "row": key[1], "fields": differences[key]}
                                    for key in sorted(disagreements)],
                  "sampled_agreements": [list(key) for key in sorted(sample)],
                  "sampling": {"method": "SHA256(source:page:N:row:M) ascending",
                               "size": sample_size},
                  "counts": {"cited_positions": len(first_rows),
                             "disagreements": len(disagreements),
                             "sampled_agreements": sample_size}}
    if args.compare_only:
        return comparison
    require(args.inspections is not None and args.inspections_sha256 is not None,
            "pinned --inspections required for staging")
    inspections = bound_json(args.inspections, args.inspections_sha256, "inspections")
    require(inspections.get("schema") == "cmas-worldcup-2026-image-inspections/v1"
            and inspections.get("source_sha256") == source_hash
            and isinstance(inspections.get("records"), list), "inspection artifact invalid")
    checked = {}
    for item in inspections["records"]:
        key = (item.get("page"), item.get("row"))
        require(key in first_rows and key not in checked, "inspection position invalid or duplicate")
        require(item.get("reason") == ("disagreement" if key in disagreements else "agreement_sample"),
                "inspection reason mismatch")
        require(item.get("render_sha256") == renders[key[0]]["sha256"],
                "inspection render binding mismatch")
        check_bbox(item.get("bbox"), renders[key[0]]["width"], renders[key[0]]["height"])
        require(item["bbox"] == first_rows[key]["citation"]["region"]["bbox"],
                "inspection citation mismatch")
        require(isinstance(item.get("inspector"), str) and item["inspector"].strip()
                and item["inspector"] != second["transcriber"],
                "independent image inspector missing")
        check_fields(item.get("fields"), f"inspection {key}")
        uncertain = item.get("uncertain_fields")
        require(isinstance(uncertain, list) and set(uncertain) <= set(FIELDS),
                "inspection uncertainty invalid")
        if key in agreements:
            require(item["fields"] == first_rows[key]["fields"],
                    "agreement inspection contradicts both passes")
        checked[key] = item
    require(disagreements | sample <= set(checked), "missing cited source inspection")
    versions = []
    unresolved = []
    for key in sorted(first_rows):
        first, later = first_rows[key], second_rows[key]
        inspection = checked.get(key)
        uncertainty = bool(first["uncertainties"] or later["uncertain_fields"]
                           or (inspection and inspection["uncertain_fields"]))
        raw = inspection["fields"] if inspection else first["fields"]
        position = {"id": first["id"], "page": key[0], "row": key[1],
                    "citation": first["citation"]}
        provenance = {"first_pass_raw_fields": first["fields"],
                      "second_pass_raw_fields": later["fields"],
                      "first_pass_uncertainties": first["uncertainties"],
                      "second_pass_uncertain_fields": later["uncertain_fields"],
                      "transcription_notes": first.get("transcription_notes", []),
                      "inspection": inspection}
        if uncertainty:
            unresolved.append(dict(position, source_role="day_primary_result",
                                   reason="uncertain raw reading", **provenance))
            continue
        version_key = json.dumps([source_hash, first["id"], args.parser_version,
                                  args.packet_sha256, args.second_pass_sha256,
                                  args.inspections_sha256, raw, provenance],
                                 ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        versions.append({"id": "observation-version:" + sha(version_key.encode()),
                         "source_sha256": source_hash, "source_position": position,
                         "source_role": "day_primary_result",
                         "parser_version": args.parser_version,
                         "raw_fields": raw, "interpreted_fields": {},
                         **provenance,
                         "verification": "source_inspected" if inspection else "agreed_uninspected"})
    require(len(versions) + len(unresolved) == len(first_rows), "position accounting failure")
    return {"schema": "cmas-worldcup-2026-verified-stage/v1",
            "source_sha256": source_hash, "parser_version": args.parser_version,
            "input_sha256": {"first_packet": args.packet_sha256,
                             "second_pass": args.second_pass_sha256,
                             "receipt": args.receipt_sha256,
                             "inspections": args.inspections_sha256},
            "render_sha256": [renders[page["page"]]["sha256"] for page in pages],
            "second_pass_attestation": {"transcriber": second["transcriber"],
                                        "blind_to_first_pass": True},
            "verification": {"disagreements": [list(key) for key in sorted(disagreements)],
                             "sampled_agreements": [list(key) for key in sorted(sample)]},
            "observation_versions": versions, "unresolved_positions": unresolved,
            "counts": {"cited_positions": len(first_rows),
                       "verified_staged_versions": len(versions),
                       "unresolved_positions": len(unresolved),
                       "confirmed_distinct_attempts": None}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("pdf", "packet", "second-pass", "receipt", "output"):
        parser.add_argument(f"--{name}", type=Path)
    parser.add_argument("--inspections", type=Path)
    parser.add_argument("--inspections-sha256")
    for name in ("packet-sha256", "second-pass-sha256", "receipt-sha256", "parser-version"):
        parser.add_argument(f"--{name}")
    parser.add_argument("--render", action="append", type=Path)
    parser.add_argument("--expected-source-sha256", default=SOURCE_SHA256)
    parser.add_argument("--compare-only", action="store_true",
                        help="write required inspection positions without staging observations")
    parser.add_argument("--import-stage", type=Path,
                        help="import a verified JSON stage into an isolated SQLite store")
    parser.add_argument("--stage-sha256")
    parser.add_argument("--sqlite-store", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.import_stage is not None:
            require(args.stage_sha256 is not None and args.sqlite_store is not None,
                    "stage import requires --stage-sha256 and --sqlite-store")
            destination = args.sqlite_store.resolve()
        else:
            require(all(getattr(args, name) is not None for name in
                        ("pdf", "packet", "second_pass", "receipt", "output",
                         "packet_sha256", "second_pass_sha256", "receipt_sha256",
                         "parser_version", "render")), "verification input missing")
            destination = args.output.resolve()
        repo = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(repo):
            ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "-q", str(destination)],
                                     check=False)
            require(ignored.returncode == 0, "private output inside repository must be Git ignored")
        if args.import_stage is not None:
            print(json.dumps(import_stage(args.import_stage, args.stage_sha256, destination),
                             sort_keys=True))
            return 0
        document = build(args)
        raw = (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
        if destination.exists():
            require(destination.read_bytes() == raw, "staged output exists with different version; use a new path")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".worldcup-stage-",
                                             delete=False) as stream:
                temporary = Path(stream.name)
                try:
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
                except BaseException:
                    temporary.unlink(missing_ok=True)
                    raise
            try:
                os.replace(temporary, destination)
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise
        print(json.dumps({"output": str(destination), "sha256": sha(raw),
                          "counts": document["counts"]}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error, json.JSONDecodeError) as error:
        print(f"World Cup verified stage rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
