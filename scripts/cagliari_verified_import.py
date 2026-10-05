#!/usr/bin/env python3
"""Compare the two Cagliari scan passes and stage private source observations.

The second pass declares blindness to the first; the declaration alone is not
proof. Only settled athlete readings enter the isolated SQLite store.
"""
import argparse
import json
import math
import os
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import tempfile

from cmas_worldcup_2026_verified_import import bound_json, import_stage, require, sha, SHA

SOURCE_SHA256 = "cc5764833f444121c83557cc66570ccdfbcb3734bc959fdbfbda5953963a712b"
PACKET_SHA256 = "559a209e694dbecc59e56cb6088fa9be160c83411bb916f7364c573c10fa9aed"
PACKET_SCHEMA = "cagliari-visual-evidence/v1"
STAGE_SCHEMA = "cagliari-verified-stage/v1"


def raw_fields(value, expected=None):
    return (isinstance(value, dict) and bool(value)
            and (expected is None or set(value) == set(expected))
            and all(isinstance(k, str) and k and (v is None or isinstance(v, str))
                    for k, v in value.items()))


def second_fields(value, expected):
    return (isinstance(value, dict) and set(value) == set(expected)
            and all(isinstance(k, str) and k
                    and (v is None or isinstance(v, str)
                         or (k == "Posizione" and type(v) is int and v >= 0))
                    for k, v in value.items()))


def same_reading(field, first, second):
    if first == second:
        return True
    if field == "Posizione" and type(second) is int and first == str(second):
        return True
    if "penalit" in field.lower() and first in (None, "") and second in (None, ""):
        return True
    if isinstance(first, str) and isinstance(second, str):
        normalize = lambda value: value.replace("\u2019", "'").replace("\u2014", "-")
        return normalize(first) == normalize(second)
    return False


def read_png_dimensions(raw):
    require(len(raw) >= 24 and raw[:8] == b"\x89PNG\r\n\x1a\n"
            and raw[12:16] == b"IHDR", "render must be PNG")
    return struct.unpack(">II", raw[16:24])


def check_bbox(box, dimensions):
    require(isinstance(box, list) and len(box) == 4
            and all(type(x) is int for x in box)
            and 0 <= box[0] < box[2] <= dimensions[0]
            and 0 <= box[1] < box[3] <= dimensions[1], "invalid image bbox")


def receipt_valid(receipt, pdf, source, path):
    if receipt.get("schema") == "private-source-bundle/v1":
        items = [x for x in receipt.get("sources", []) if x.get("id") == f"sha256:{source}"]
        require(len(items) == 1, "receipt source count mismatch")
        item = items[0]
        require(item.get("sha256") == source and item.get("bytes") == len(pdf)
                and item.get("status") == "included" and item.get("content_type") == "application/pdf"
                and item.get("receipt", {}).get("acquisition_id")
                and item.get("receipt", {}).get("retrieved_at"), "receipt source binding mismatch")
        require(sha((path.parent / item["object"]).read_bytes()) == source,
                "receipt retained object mismatch")
    else:
        require(receipt.get("source_sha256") == source and receipt.get("bytes") == len(pdf),
                "receipt source binding mismatch")


def checked_stage(path, expected):
    stage = bound_json(path, expected, "stage")
    require(stage.get("schema") == STAGE_SCHEMA
            and isinstance(stage.get("source_sha256"), str)
            and SHA.fullmatch(stage["source_sha256"]),
            "stage source/schema mismatch")
    versions = stage.get("observation_versions")
    unresolved = stage.get("unresolved_positions")
    aggregates = stage.get("non_primary_positions")
    counts = stage.get("counts", {})
    require(isinstance(versions, list) and isinstance(unresolved, list)
            and isinstance(aggregates, list) and isinstance(counts, dict),
            "stage accounting missing")
    require(counts.get("candidate_result_positions") == len(versions) + len(unresolved)
            and counts.get("aggregate_rows_excluded") == len(aggregates)
            and counts.get("cited_positions") == len(versions) + len(unresolved) + len(aggregates)
            and all(x.get("source_role") == "athlete_result" for x in versions + unresolved)
            and all(x.get("source_role") == "club_aggregate"
                    and x.get("disposition") == "aggregate" for x in aggregates),
            "stage role accounting mismatch")
    if stage["source_sha256"] == SOURCE_SHA256:
        require(counts["candidate_result_positions"] == 105
                and counts["aggregate_rows_excluded"] == 16,
                "official source position accounting mismatch")
    return stage


def build(args):
    pdf = args.pdf.read_bytes()
    source = sha(pdf)
    require(source == args.expected_source_sha256 and SHA.fullmatch(source), "PDF source SHA256 mismatch")
    packet = bound_json(args.packet, args.packet_sha256, "first packet")
    second = bound_json(args.second_pass, args.second_pass_sha256, "second pass")
    receipt = bound_json(args.receipt, args.receipt_sha256, "receipt")
    require(packet.get("schema") == PACKET_SCHEMA and packet.get("source_sha256") == source
            and packet.get("source_bytes") == len(pdf)
            and packet.get("source", {}).get("id") == f"sha256:{source}"
            and packet["source"].get("original_sha256") == source,
            "first packet source binding mismatch")
    if source == SOURCE_SHA256:
        require(args.packet_sha256 == PACKET_SHA256, "official first packet SHA256 mismatch")
    receipt_valid(receipt, pdf, source, args.receipt)
    require(second.get("schema") == "cagliari-second-pass/v1"
            and second.get("source_sha256") == source
            and second.get("blind_to_first_pass") is True
            and isinstance(second.get("transcriber"), str) and second["transcriber"].strip(),
            "second pass source or blindness attestation missing")
    blind_artifacts = second.get("blind_artifacts", {})
    require(isinstance(blind_artifacts, dict)
            and all(isinstance(name, str) and isinstance(value, str) and SHA.fullmatch(value)
                    for name, value in blind_artifacts.items()),
            "blind artifact provenance invalid")
    normalization = second.get("presentation_normalization", {})
    require(isinstance(normalization, dict), "presentation normalization provenance invalid")
    require(isinstance(args.parser_version, str) and args.parser_version.strip(), "parser version required")
    pages = packet.get("pages")
    require(isinstance(pages, list) and len(pages) == len(args.render),
            "render/page coverage mismatch")
    if source == SOURCE_SHA256:
        require(len(pages) == 29, "official page count mismatch")
    first = {}
    render_hashes = {}
    ids = set()
    for number, (page, render_path) in enumerate(zip(pages, args.render), 1):
        require(page.get("page") == number, "packet pages must be consecutive")
        render = render_path.read_bytes()
        dimensions = read_png_dimensions(render)
        render_hashes[number] = sha(render)
        rows = page.get("rows")
        require(isinstance(rows, list)
                and [x.get("row") for x in rows] == list(range(1, len(rows) + 1))
                and page.get("visual_row_count") == len(rows), "row positions not consecutive")
        for row in rows:
            kind = row.get("kind")
            require(kind in ("athlete", "aggregate"), "source role invalid")
            key = (number, row["row"])
            identity = f"{source}:page:{number}:{kind}:row:{row['row']}"
            require(key not in first and row.get("id") == f"{kind}-position:{sha(identity.encode())}"
                    and row["id"] not in ids, "source position identity mismatch")
            ids.add(row["id"])
            citation = row.get("citation", {})
            region = citation.get("region", {})
            require(citation.get("page") == number
                    and region.get("units") == "upright_png_pixels_220dpi",
                    "source image citation invalid")
            check_bbox(region.get("bbox"), dimensions)
            require(raw_fields(row.get("fields_raw"))
                    and isinstance(row.get("uncertainties"), list)
                    and all(isinstance(x, str) and x.strip() for x in row["uncertainties"])
                    and all(row.get(k) is None or isinstance(row.get(k), str)
                            for k in ("status_raw", "penalty_raw", "notes_raw")),
                    "first pass raw fields invalid")
            first[key] = (row, page)
    athlete_count = sum(row["kind"] == "athlete" for row, _ in first.values())
    aggregate_count = len(first) - athlete_count
    counts = packet.get("counts", {})
    require(counts.get("source_positions") == athlete_count
            and counts.get("athlete_row_appearances") == athlete_count
            and counts.get("aggregate_row_appearances") == aggregate_count
            and counts.get("aggregate_rows_excluded") == aggregate_count
            and counts.get("nonduplicate_candidate_positions") == athlete_count,
            "packet source-position accounting mismatch")
    if source == SOURCE_SHA256:
        require((athlete_count, aggregate_count) == (105, 16),
                "official athlete/aggregate position count mismatch")
    require(isinstance(second.get("rows"), list), "second pass rows missing")
    later = {}
    for row in second["rows"]:
        key = (row.get("page"), row.get("row"))
        require(key in first and key not in later and row.get("kind") == first[key][0]["kind"],
                "second pass coverage or duplicate mismatch")
        require(second_fields(row.get("fields_raw"), first[key][0]["fields_raw"]),
                "second pass raw fields invalid")
        uncertain = row.get("uncertain_fields")
        require(isinstance(uncertain, list) and len(uncertain) == len(set(uncertain))
                and set(uncertain) <= set(row["fields_raw"]),
                "second pass uncertainty invalid")
        later[key] = row
    require(set(later) == set(first), "second pass coverage mismatch")
    presentation = {key: sorted(k for k in row["fields_raw"]
                                if row["fields_raw"][k] != later[key]["fields_raw"][k]
                                and same_reading(k, row["fields_raw"][k], later[key]["fields_raw"][k]))
                    for key, (row, _) in first.items()}
    presentation = {key: fields for key, fields in presentation.items() if fields}
    differences = {key: sorted(k for k in row["fields_raw"]
                               if not same_reading(k, row["fields_raw"][k], later[key]["fields_raw"][k]))
                   for key, (row, _) in first.items()}
    differences = {key: fields for key, fields in differences.items() if fields}
    agreements = set(first) - set(differences)
    sample_size = math.ceil(len(agreements) / 10)
    sample = set(sorted(agreements, key=lambda k: (sha(f"{source}:page:{k[0]}:row:{k[1]}".encode()), k))[:sample_size])
    comparison = {"schema": "cagliari-comparison/v1", "source_sha256": source,
                  "first_packet_sha256": args.packet_sha256, "second_pass_sha256": args.second_pass_sha256,
                  "second_pass_derivation": {"blind_artifacts": blind_artifacts,
                                             "presentation_normalization": normalization},
                  "render_sha256": [render_hashes[i] for i in sorted(render_hashes)],
                  "disagreements": [{"page": k[0], "row": k[1], "fields": differences[k]}
                                    for k in sorted(differences)],
                  "presentation_differences": [[k[0], k[1], presentation[k]] for k in sorted(presentation)],
                  "sampled_agreements": [list(k) for k in sorted(sample)],
                  "sampling": {"method": "SHA256(source:page:N:row:M) ascending", "size": sample_size},
                  "counts": {"cited_positions": len(first), "candidate_result_positions": athlete_count,
                             "aggregate_rows_excluded": aggregate_count,
                             "disagreements": len(differences), "sampled_agreements": sample_size}}
    if args.compare_only:
        return comparison
    require(args.inspections is not None and args.inspections_sha256 is not None,
            "pinned inspections required")
    inspections = bound_json(args.inspections, args.inspections_sha256, "inspections")
    require(inspections.get("schema") == "cagliari-image-inspections/v1"
            and inspections.get("source_sha256") == source
            and isinstance(inspections.get("records"), list), "inspection artifact invalid")
    checked = {}
    for item in inspections["records"]:
        key = (item.get("page"), item.get("row"))
        require(key in first and key not in checked, "inspection position invalid or duplicate")
        expected_reason = "disagreement" if key in differences else "agreement_sample"
        require(item.get("reason") == expected_reason
                and item.get("render_sha256") == render_hashes[key[0]]
                and item.get("bbox") == first[key][0]["citation"]["region"]["bbox"],
                "inspection citation or reason mismatch")
        require(isinstance(item.get("inspector"), str) and item["inspector"].strip()
                and item["inspector"] != second["transcriber"],
                "independent image inspector missing")
        require(raw_fields(item.get("fields_raw"), first[key][0]["fields_raw"]),
                "inspection raw fields invalid")
        uncertain = item.get("uncertain_fields")
        require(isinstance(uncertain, list) and len(uncertain) == len(set(uncertain))
                and set(uncertain) <= set(item["fields_raw"]), "inspection uncertainty invalid")
        if key not in differences:
            require(item["fields_raw"] == first[key][0]["fields_raw"],
                    "agreement inspection contradicts both passes")
        checked[key] = item
    require(set(differences) | sample <= set(checked), "missing cited source inspection")
    versions, unresolved, aggregates = [], [], []
    for key in sorted(first):
        row, page = first[key]
        inspection = checked.get(key)
        raw = inspection["fields_raw"] if inspection else row["fields_raw"]
        position = {"id": row["id"], "page": key[0], "row": key[1], "citation": row["citation"]}
        provenance = {"first_pass_raw_fields": row["fields_raw"],
                      "second_pass_raw_fields": later[key]["fields_raw"],
                      "first_pass_uncertainties": row["uncertainties"],
                      "second_pass_uncertain_fields": later[key]["uncertain_fields"],
                      "inspection": inspection,
                      "page_context_raw": {name: page.get(name) for name in
                                           ("heading_raw", "category_raw", "discipline_raw", "event_date_printed")},
                      "status_raw": row.get("status_raw"), "penalty_raw": row.get("penalty_raw"),
                      "notes_raw": row.get("notes_raw")}
        if row["kind"] == "aggregate":
            aggregates.append({**position, "source_role": "club_aggregate", "disposition": "aggregate",
                               "raw_fields": raw, **provenance})
            continue
        if row["uncertainties"] or later[key]["uncertain_fields"] or (inspection and inspection["uncertain_fields"]):
            unresolved.append({**position, "source_role": "athlete_result", "reason": "uncertain raw reading",
                               "raw_fields": raw, **provenance})
            continue
        identity = [source, row["id"], args.parser_version, args.packet_sha256,
                    args.second_pass_sha256, args.inspections_sha256, raw, provenance]
        versions.append({"id": "observation-version:" + sha(json.dumps(identity, ensure_ascii=False,
                         sort_keys=True, separators=(",", ":")).encode()),
                         "source_sha256": source, "source_position": position,
                         "source_role": "athlete_result", "parser_version": args.parser_version,
                         "raw_fields": raw, "interpreted_fields": {}, **provenance,
                         "verification": "source_inspected" if inspection else "agreed_uninspected"})
    return {"schema": STAGE_SCHEMA, "source_sha256": source,
            "parser_version": args.parser_version,
            "input_sha256": {"first_packet": args.packet_sha256, "second_pass": args.second_pass_sha256,
                             "receipt": args.receipt_sha256, "inspections": args.inspections_sha256},
            "render_sha256": [render_hashes[i] for i in sorted(render_hashes)],
            "second_pass_attestation": {"transcriber": second["transcriber"], "blind_to_first_pass": True},
            "second_pass_derivation": {"blind_artifacts": blind_artifacts,
                                       "presentation_normalization": normalization},
            "verification": {"disagreements": [list(k) for k in sorted(differences)],
                             "sampled_agreements": [list(k) for k in sorted(sample)],
                             "presentation_differences": [[k[0], k[1], presentation[k]]
                                                          for k in sorted(presentation)]},
            "observation_versions": versions, "unresolved_positions": unresolved,
            "non_primary_positions": aggregates,
            "counts": {"cited_positions": len(first), "candidate_result_positions": athlete_count,
                       "verified_staged_versions": len(versions), "unresolved_positions": len(unresolved),
                       "non_primary_positions": len(aggregates), "aggregate_rows_excluded": len(aggregates),
                       "confirmed_distinct_attempts": None}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("pdf", "packet", "second-pass", "receipt", "output", "inspections",
                 "import-stage", "sqlite-store"):
        parser.add_argument(f"--{name}", type=Path)
    for name in ("packet-sha256", "second-pass-sha256", "receipt-sha256",
                 "inspections-sha256", "parser-version", "stage-sha256"):
        parser.add_argument(f"--{name}")
    parser.add_argument("--render", action="append", type=Path)
    parser.add_argument("--expected-source-sha256", default=SOURCE_SHA256)
    parser.add_argument("--compare-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.import_stage:
            require(args.stage_sha256 and args.sqlite_store, "stage import requires SHA256 and store")
            destination = args.sqlite_store.resolve()
        else:
            require(all(getattr(args, name) is not None for name in
                        ("pdf", "packet", "second_pass", "receipt", "output", "packet_sha256",
                         "second_pass_sha256", "receipt_sha256", "parser_version", "render")),
                    "verification input missing")
            destination = args.output.resolve()
        repo = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(repo):
            ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "-q", str(destination)],
                                     check=False)
            require(ignored.returncode == 0, "private output inside repository must be Git ignored")
        if args.import_stage:
            checked_stage(args.import_stage, args.stage_sha256)
            result = import_stage(args.import_stage, args.stage_sha256, destination,
                                  stage_schema=STAGE_SCHEMA,
                                  store_schema="cagliari-isolated-store/v1",
                                  marker_table="cagliari_stage_meta")
            print(json.dumps(result, sort_keys=True))
            return 0
        document = build(args)
        raw = (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
        if destination.exists():
            require(destination.read_bytes() == raw, "output exists with different version")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".cagliari-stage-", delete=False) as stream:
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
    except (OSError, ValueError, KeyError, TypeError, AttributeError, sqlite3.Error) as error:
        print(f"Cagliari verified import rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
