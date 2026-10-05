#!/usr/bin/env python3
"""Compare independent FIPSAS image transcriptions and import private observations.

The second pass is an attested source-only transcription, not proof of blindness.
Only cited athlete rows with settled raw readings become observation versions.
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


SOURCE_HASHES = {
    "barracuda": "ca3565fe1e52f2047c55d15ddf4739796154c0c9764c5e372586b88d914a1f68",
    "komaros": "2a556ebf4edf4a00f67b91c2a023c9e84c12520008fb7af952885343fcb10ce3",
}
ROW_COUNTS = {"barracuda": [3, 1, 1, 2, 0],
              "komaros": [1, 2, 4, 1, 1, 3, 3, 6, 5, 10, 1, 4, 6]}
SCHEMAS = {"barracuda": "barracuda-visual-evidence/v1",
           "komaros": "komaros-visual-evidence/v1"}
STAGE_SCHEMA = "fipsas-image-verified-stage/v1"
KOMAROS_FIELD_MAP = {"given_name": "name", "approved_time": "homologated_time",
                     "approved_distance": "homologated_distance",
                     "penalty_or_status": "penalty"}


def raw_fields(fields, keys=None):
    return (isinstance(fields, dict) and bool(fields)
            and (keys is None or set(fields) == set(keys))
            and all(isinstance(k, str) and k and (v is None or isinstance(v, str))
                    for k, v in fields.items()))


def second_fields(raw, kind, first):
    require(raw_fields(raw), "second pass raw fields invalid")
    if kind == "komaros":
        mapped = {KOMAROS_FIELD_MAP.get(k, k): v for k, v in raw.items() if k != "note"}
    else:
        mapped = raw
    require(raw_fields(mapped, first), "second pass mapped fields invalid")
    return mapped


def same_reading(field, first, second, second_row, kind):
    if first == second or (first in (None, "") and second in (None, "")):
        return True
    if kind == "komaros" and field == "penalty":
        note = second_row["fields_raw"].get("note")
        return isinstance(first, str) and isinstance(second, str) and isinstance(note, str) \
            and first == second + "\n" + note
    return False


def png_dimensions(raw):
    require(len(raw) >= 24 and raw[:8] == b"\x89PNG\r\n\x1a\n"
            and raw[12:16] == b"IHDR", "render must be PNG")
    return struct.unpack(">II", raw[16:24])


def bbox_valid(box, dimensions):
    return (isinstance(box, list) and len(box) == 4
            and all(type(v) is int for v in box)
            and 0 <= box[0] < box[2] <= dimensions[0]
            and 0 <= box[1] < box[3] <= dimensions[1])


def receipt_valid(receipt, pdf, source, path):
    if receipt.get("schema") == "private-source-bundle/v1":
        matches = [x for x in receipt.get("sources", []) if x.get("id") == f"sha256:{source}"]
        require(len(matches) == 1, "receipt source count mismatch")
        item = matches[0]
        require(item.get("sha256") == source and item.get("bytes") == len(pdf)
                and item.get("status") == "included" and item.get("content_type") == "application/pdf"
                and item.get("receipt", {}).get("acquisition_id")
                and item.get("receipt", {}).get("retrieved_at"), "receipt source binding mismatch")
        require(sha((path.parent / item["object"]).read_bytes()) == source,
                "retained receipt object mismatch")
    else:
        require(receipt.get("source_sha256") == source and receipt.get("bytes") == len(pdf),
                "receipt source binding mismatch")


def checked_import_stage(path, expected_sha):
    stage = bound_json(path, expected_sha, "stage")
    require(stage.get("schema") == STAGE_SCHEMA, "stage schema mismatch")
    versions = stage.get("observation_versions")
    non_primary = stage.get("non_primary_positions")
    unresolved = stage.get("unresolved_positions")
    counts = stage.get("counts", {})
    require(isinstance(versions, list) and isinstance(non_primary, list)
            and isinstance(unresolved, list) and isinstance(counts, dict),
            "stage positions missing")
    require(all(row.get("source_role") == "athlete_result" for row in versions + unresolved)
            and all(row.get("source_role") == "society_aggregate"
                    and row.get("disposition") == "aggregate" for row in non_primary),
            "stage role invalid")
    require(counts.get("candidate_result_positions") == len(versions) + len(unresolved)
            and counts.get("aggregate_rows_excluded") == len(non_primary)
            and counts.get("cited_positions") == len(versions) + len(unresolved) + len(non_primary),
            "stage role accounting mismatch")


def build(args):
    pdf = args.pdf.read_bytes()
    source = sha(pdf)
    require(source == args.expected_source_sha256 and SHA.fullmatch(source), "source SHA256 mismatch")
    packet = bound_json(args.packet, args.packet_sha256, "first packet")
    second = bound_json(args.second_pass, args.second_pass_sha256, "second pass")
    receipt = bound_json(args.receipt, args.receipt_sha256, "receipt")
    kind = args.source_kind
    require(packet.get("schema") == SCHEMAS[kind] and packet.get("source_sha256") == source
            and packet.get("source_bytes") == len(pdf)
            and packet.get("source", {}).get("id") == f"sha256:{source}"
            and packet["source"].get("original_sha256") == source,
            "first packet source binding mismatch")
    receipt_valid(receipt, pdf, source, args.receipt)
    require(second.get("schema") == "fipsas-image-second-pass/v1"
            and second.get("source_sha256") == source
            and second.get("blind_to_first_pass") is True
            and isinstance(second.get("transcriber"), str) and second["transcriber"].strip(),
            "second pass source or independence attestation missing")
    require(isinstance(args.parser_version, str) and args.parser_version.strip(),
            "parser version required")
    pages = packet.get("pages")
    require(isinstance(pages, list) and len(pages) == len(args.render),
            "render/page coverage mismatch")
    if source == SOURCE_HASHES[kind]:
        require(len(pages) == len(ROW_COUNTS[kind]), "official page count mismatch")
    first, render_hashes, aggregate = {}, {}, {}
    ids = set()
    for index, (page, render_path) in enumerate(zip(pages, args.render), 1):
        require(page.get("page") == index, "packet pages must be consecutive")
        render_raw = render_path.read_bytes()
        dimensions = png_dimensions(render_raw)
        render_hashes[index] = sha(render_raw)
        metadata = page.get("render") or page.get("render_metadata") or {}
        if metadata:
            require(metadata.get("pixel_width", dimensions[0]) == dimensions[0]
                    and metadata.get("pixel_height", dimensions[1]) == dimensions[1],
                    "render dimensions mismatch")
        rows = page.get("rows")
        aggregates = page.get("aggregate_rows", [])
        require(isinstance(rows, list) and isinstance(aggregates, list)
                and [x.get("row") for x in rows] == list(range(1, len(rows) + 1))
                and [x.get("row") for x in aggregates] == list(range(1, len(aggregates) + 1)),
                "row positions not consecutive")
        if source == SOURCE_HASHES[kind]:
            require(len(rows) == ROW_COUNTS[kind][index - 1], "official row count mismatch")
            if kind == "barracuda":
                require(len(aggregates) == (1 if index == 5 else 0),
                        "Barracuda aggregate accounting mismatch")
        for role, positions in (("athlete", rows), ("aggregate", aggregates)):
            for row in positions:
                key = (index, row["row"])
                target = first if role == "athlete" else aggregate
                require(key not in target and row.get("id") not in ids
                        and isinstance(row.get("id"), str), "duplicate source position")
                ids.add(row["id"])
                if kind == "komaros":
                    identity_text = f"{source}:page:{index}:row:{row['row']}"
                else:
                    identity_kind = "athlete" if role == "athlete" else "society aggregate"
                    identity_text = f"{source}:page:{index}:{identity_kind}:row:{row['row']}"
                expected_id = sha(identity_text.encode())
                require(row["id"] == ("source-position:" if role == "athlete" else "aggregate-position:") + expected_id,
                        "source position identity mismatch")
                citation = row.get("citation", {})
                region = citation.get("region", {})
                units = ({"upright_png_pixels_220dpi"} if kind == "barracuda" else
                         {"rendered_px_220dpi", "rendered_png_pixels_220_dpi"})
                require(citation.get("page") == index and region.get("units") in units
                        and bbox_valid(region.get("bbox"), dimensions), "source image citation invalid")
                require(raw_fields(row.get("fields")) and isinstance(row.get("uncertainties"), list),
                        "first pass raw fields invalid")
                require(all(isinstance(x, str) and x.strip() for x in row["uncertainties"]),
                        "first pass uncertainties invalid")
                if kind == "barracuda":
                    require("status_raw" in row and "notes_raw" in row
                            and all(row[x] is None or isinstance(row[x], str)
                                    for x in ("status_raw", "notes_raw")),
                            "raw status or notes invalid")
                target[key] = (row, page)
    counts = packet.get("counts", {})
    require(counts.get("source_positions") == len(first)
            and counts.get("aggregate_rows_excluded", 0) == len(aggregate),
            "packet source-position accounting mismatch")
    require(isinstance(second.get("rows"), list), "second pass rows missing")
    later = {}
    second_rows = list(second["rows"])
    if kind == "barracuda" and second.get("aggregate") is not None:
        second_rows.append(second["aggregate"])
    for row in second_rows:
        key = (row.get("page"), row.get("row"))
        role = first.get(key) or aggregate.get(key)
        require(role is not None and key not in later, "second pass coverage or duplicate mismatch")
        fields = second_fields(row.get("fields_raw"), kind, role[0]["fields"])
        uncertain = row.get("uncertain_fields")
        require(isinstance(uncertain, list) and len(uncertain) == len(set(uncertain))
                and set(uncertain) <= set(row["fields_raw"]), "second pass uncertain fields invalid")
        later[key] = {**row, "mapped_fields": fields,
                      "mapped_uncertain_fields": [KOMAROS_FIELD_MAP.get(x, x) for x in uncertain]}
    require(set(later) == set(first) | set(aggregate), "second pass coverage mismatch")
    differences = {}
    for key, (row, _) in {**first, **aggregate}.items():
        changed = [field for field in row["fields"] if not same_reading(
            field, row["fields"][field], later[key]["mapped_fields"][field],
            later[key], kind)]
        if changed:
            differences[key] = changed
    agreements = sorted(set(first) - set(differences))
    sample_size = math.ceil(len(agreements) / 10)
    sample = set(sorted(agreements, key=lambda k: (sha(f"{source}:page:{k[0]}:row:{k[1]}".encode()), k))[:sample_size])
    comparison = {"schema": "fipsas-image-comparison/v1", "source_sha256": source,
                  "first_packet_sha256": args.packet_sha256,
                  "second_pass_sha256": args.second_pass_sha256,
                  "render_sha256": [render_hashes[i] for i in sorted(render_hashes)],
                  "disagreements": [{"page": k[0], "row": k[1], "fields": differences[k]}
                                    for k in sorted(differences)],
                  "sampled_agreements": [list(k) for k in sorted(sample)],
                  "sampling": {"method": "SHA256(source:page:N:row:M) ascending", "size": sample_size},
                  "counts": {"cited_positions": len(first) + len(aggregate),
                             "candidate_result_positions": len(first),
                             "aggregate_rows_excluded": len(aggregate),
                             "disagreements": len(differences), "sampled_agreements": sample_size}}
    if args.compare_only:
        return comparison
    require(args.inspections is not None and args.inspections_sha256 is not None,
            "pinned inspections required")
    inspections = bound_json(args.inspections, args.inspections_sha256, "inspections")
    require(inspections.get("schema") == "fipsas-image-inspections/v1"
            and inspections.get("source_sha256") == source
            and isinstance(inspections.get("records"), list), "inspection artifact invalid")
    checked = {}
    for item in inspections["records"]:
        key = (item.get("page"), item.get("row"))
        role = first.get(key) or aggregate.get(key)
        require(role is not None and key not in checked, "inspection position invalid or duplicate")
        expected_reason = "disagreement" if key in differences else "agreement_sample"
        require(item.get("reason") == expected_reason
                and item.get("render_sha256") == render_hashes[key[0]]
                and item.get("bbox") == role[0]["citation"]["region"]["bbox"],
                "inspection citation or reason mismatch")
        require(isinstance(item.get("inspector"), str) and item["inspector"].strip()
                and item["inspector"] != second["transcriber"],
                "independent image inspector missing")
        fields = item.get("fields_raw")
        require(raw_fields(fields, role[0]["fields"]), "inspection raw fields invalid")
        uncertain = item.get("uncertain_fields")
        require(isinstance(uncertain, list) and len(uncertain) == len(set(uncertain))
                and set(uncertain) <= set(fields), "inspection uncertainty invalid")
        if key not in differences:
            require(fields == role[0]["fields"], "agreement inspection contradicts both passes")
        checked[key] = item
    require(set(differences) | sample <= set(checked), "missing cited source inspection")
    versions, unresolved, non_primary = [], [], []
    for key in sorted(set(first) | set(aggregate)):
        row, page = (first.get(key) or aggregate[key]) if key not in first else first[key]
        inspection = checked.get(key)
        raw = inspection["fields_raw"] if inspection else row["fields"]
        position = {"id": row["id"], "page": key[0], "row": key[1], "citation": row["citation"]}
        provenance = {"first_pass_raw_fields": row["fields"],
                      "second_pass_raw_fields": later[key]["fields_raw"],
                      "second_pass_raw_row": {k: v for k, v in later[key].items()
                                              if k not in ("mapped_fields", "mapped_uncertain_fields")},
                      "second_pass_mapped_fields": later[key]["mapped_fields"],
                      "first_pass_uncertainties": row["uncertainties"],
                      "second_pass_uncertain_fields": later[key]["uncertain_fields"],
                      "second_pass_mapped_uncertain_fields": later[key]["mapped_uncertain_fields"],
                      "inspection": inspection, "page_context_raw": {k: page.get(k) for k in
                      ("heading_raw", "category_raw", "discipline_raw", "event_date_printed")},
                      "status_raw": row.get("status_raw"), "notes_raw": row.get("notes_raw")}
        if key in aggregate:
            non_primary.append({**position, "source_role": "society_aggregate",
                                "disposition": "aggregate", "raw_fields": raw, **provenance})
            continue
        if row["uncertainties"] or later[key]["uncertain_fields"] or (inspection and inspection["uncertain_fields"]):
            unresolved.append({**position, "source_role": "athlete_result",
                               "reason": "uncertain raw reading", "raw_fields": raw, **provenance})
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
            "input_sha256": {"first_packet": args.packet_sha256,
                             "second_pass": args.second_pass_sha256,
                             "receipt": args.receipt_sha256,
                             "inspections": args.inspections_sha256},
            "render_sha256": [render_hashes[i] for i in sorted(render_hashes)],
            "second_pass_attestation": {"transcriber": second["transcriber"],
                                        "blind_to_first_pass": True},
            "verification": {"disagreements": [list(k) for k in sorted(differences)],
                             "sampled_agreements": [list(k) for k in sorted(sample)]},
            "observation_versions": versions, "unresolved_positions": unresolved,
            "non_primary_positions": non_primary,
            "counts": {"cited_positions": len(first) + len(aggregate),
                       "candidate_result_positions": len(first),
                       "verified_staged_versions": len(versions),
                       "unresolved_positions": len(unresolved),
                       "non_primary_positions": len(non_primary),
                       "aggregate_rows_excluded": len(aggregate),
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
    parser.add_argument("--source-kind", choices=sorted(SCHEMAS), default="barracuda")
    parser.add_argument("--expected-source-sha256")
    parser.add_argument("--compare-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.import_stage:
            require(args.stage_sha256 and args.sqlite_store, "stage import requires SHA256 and store")
            destination = args.sqlite_store.resolve()
        else:
            require(all(getattr(args, name) is not None for name in
                        ("pdf", "packet", "second_pass", "receipt", "output",
                         "packet_sha256", "second_pass_sha256", "receipt_sha256",
                         "parser_version", "render")), "verification input missing")
            args.expected_source_sha256 = args.expected_source_sha256 or SOURCE_HASHES[args.source_kind]
            destination = args.output.resolve()
        repo = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(repo):
            ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "-q", str(destination)],
                                     check=False)
            require(ignored.returncode == 0, "private output inside repository must be Git ignored")
        if args.import_stage:
            checked_import_stage(args.import_stage, args.stage_sha256)
            result = import_stage(args.import_stage, args.stage_sha256, destination,
                                  stage_schema=STAGE_SCHEMA,
                                  store_schema="fipsas-image-isolated-store/v1",
                                  marker_table="fipsas_image_stage_meta")
            print(json.dumps(result, sort_keys=True))
            return 0
        document = build(args)
        raw = (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
        if destination.exists():
            require(destination.read_bytes() == raw, "output exists with different version")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".fipsas-stage-",
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
    except (OSError, ValueError, KeyError, TypeError, AttributeError, sqlite3.Error) as error:
        print(f"FIPSAS image import rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
