#!/usr/bin/env python3
"""Verify two cited Firenze image readings; stage private, immutable observations.

Both passes use the page/row layout of firenze-visual-evidence/v1 and carry
independent original-page-image attestations. This command does not decide
athlete identity, sporting attempts, review, or publication.
"""
import argparse
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

from cmas_worldcup_2026_verified_import import bound_json, import_stage, require, sha, SHA
from fipsas_image_verified_import import receipt_valid


SOURCE_SHA256 = "5418c897ade460098e5df26586a17de1d474d5ad3776cbd20dbdade584dcaf14"
HISTORICAL_PACKET_SHA256 = "a3c94db3598c8653e9e543e8b317f268b192c5385fdb2111d76782bf84ec22f3"
PASS_SCHEMA = "firenze-visual-evidence/v1"
COMPARABLE_SCHEMA = "firenze-comparable-pass/v1"
STAGE_SCHEMA = "firenze-verified-stage/v1"
INSPECTION_SCHEMA = "firenze-image-inspections/v1"
HISTORICAL_FIELDS = {"position_raw": "Posizione", "cognome_raw": "Cognome",
                     "nome_raw": "Nome", "anno_di_nascita_raw": "Anno di nascita",
                     "societa_raw": "Società"}


def fields_valid(fields):
    return (isinstance(fields, dict) and len(fields) >= 1
            and all(isinstance(key, str) and key.strip()
                    and (value is None or isinstance(value, str))
                    for key, value in fields.items()))


def png_size(raw):
    require(len(raw) >= 24 and raw[:8] == b"\x89PNG\r\n\x1a\n"
            and raw[12:16] == b"IHDR", "render must be PNG")
    return struct.unpack(">II", raw[16:24])


def bbox_valid(box, dimensions):
    return (isinstance(box, list) and len(box) == 4
            and all(type(value) is int for value in box)
            and 0 <= box[0] < box[2] <= dimensions[0]
            and 0 <= box[1] < box[3] <= dimensions[1])


def same_reading(first, second):
    return first == second or first in (None, "") and second in (None, "")


def same_field(field, first, second):
    if field != "result_columns_raw":
        return same_reading(first, second)
    def comparable(value):
        if not isinstance(value, str):
            return value
        try:
            tokens = json.loads(value)
            if isinstance(tokens, list) and all(isinstance(item, str) for item in tokens):
                value = " ".join(tokens)
        except ValueError:
            pass
        return re.sub(r"\s+", " ", value).strip()
    return same_reading(comparable(first), comparable(second))


def normalize_pass(document, source):
    """Adapt source-only page ledgers without changing their pinned bytes."""
    if not isinstance(document.get("pages"), list):
        return document
    output = {**document, "pages": []}
    athletes = aggregates = duplicates = 0
    for page in document["pages"]:
        normalized = {**page}
        normalized["event_date_printed"] = page.get("event_date_raw",
                                                     page.get("event_date_printed"))
        if "render" not in normalized and isinstance(page.get("render_dimensions"), list):
            dimensions = page["render_dimensions"]
            if len(dimensions) == 2:
                normalized["render"] = {"pixel_width": dimensions[0],
                                        "pixel_height": dimensions[1]}
        for kind, section in (("source", "rows"), ("aggregate", "aggregate_rows")):
            rows = []
            for item in page.get(section, []):
                row = dict(item)
                if "citation" not in row and "region" in row:
                    row["citation"] = {"page": page.get("page"), "region": row["region"]}
                if "id" not in row and type(page.get("page")) is int and type(row.get("row")) is int:
                    row["id"] = f"{kind}-position:" + sha(
                        f"{source}:page:{page['page']}:{kind}:row:{row['row']}".encode())
                rows.append(row)
                if kind == "aggregate":
                    aggregates += 1
                else:
                    athletes += 1
                    duplicates += row.get("duplicate_of") is not None
            normalized[section] = rows
        output["pages"].append(normalized)
    counts = dict(output.get("counts", {}))
    for name, value in (("source_positions", athletes),
                        ("aggregate_rows_excluded", aggregates),
                        ("duplicate_rendered_rows", duplicates),
                        ("nonduplicate_candidate_positions", athletes - duplicates)):
        counts.setdefault(name, value)
    output["counts"] = counts
    return output


def check_pass(document, source, byte_count, sizes, label):
    require(document.get("schema") in (PASS_SCHEMA, COMPARABLE_SCHEMA)
            and document.get("source_sha256") == source
            and document.get("source_bytes") == byte_count,
            f"{label}: source binding mismatch")
    attestation = document.get("attestation", {})
    require(attestation.get("method") == "original_page_images"
            and isinstance(attestation.get("transcriber"), str)
            and attestation["transcriber"].strip()
            and attestation.get("blind_to_other_pass") is True
            and attestation.get("blind_to_derivative_snapshot") is True,
            f"{label}: independent image attestation missing")
    pages = document.get("pages")
    require(isinstance(pages, list) and len(pages) == len(sizes) == 25,
            f"{label}: page coverage mismatch")
    positions = {}
    identifiers = set()
    athletes = aggregates = duplicates = 0
    for number, page in enumerate(pages, 1):
        require(page.get("page") == number, f"{label}: page order mismatch")
        render = page.get("render", {})
        require(render.get("pixel_width") == sizes[number][0]
                and render.get("pixel_height") == sizes[number][1],
                f"{label}: render dimensions mismatch")
        rows, club = page.get("rows"), page.get("aggregate_rows")
        require(isinstance(rows, list) and isinstance(club, list),
                f"{label}: row lists missing")
        counts = page.get("visual_row_count", {})
        require(counts.get("athlete") == len(rows)
                and counts.get("aggregate") == len(club),
                f"{label}: page row accounting mismatch")
        for kind, group in (("source", rows), ("aggregate", club)):
            require([row.get("row") for row in group] == list(range(1, len(group) + 1)),
                    f"{label}: row ordinals invalid")
            for row in group:
                key = (number, kind, row["row"])
                expected = f"{kind}-position:" + sha(
                    f"{source}:page:{number}:{kind}:row:{row['row']}".encode())
                require(row.get("id") == expected and expected not in identifiers,
                        f"{label}: position identity invalid")
                identifiers.add(expected)
                citation = row.get("citation", {})
                region = citation.get("region", {})
                require(citation.get("page") == number
                        and region.get("units") == "upright_png_pixels_220dpi"
                        and bbox_valid(region.get("bbox"), sizes[number]),
                        f"{label}: image citation invalid")
                require(fields_valid(row.get("fields"))
                        and all(name in row and (row[name] is None or isinstance(row[name], str))
                                for name in ("status_raw", "penalty_raw", "notes_raw")),
                        f"{label}: raw fields invalid")
                uncertain = row.get("uncertainties")
                require(isinstance(uncertain, list)
                        and all(isinstance(item, str) and item.strip() for item in uncertain),
                        f"{label}: uncertainty invalid")
                positions[key] = (row, page)
                if kind == "aggregate":
                    aggregates += 1
                else:
                    athletes += 1
                    duplicates += row.get("duplicate_of") is not None
    for (page, kind, row_number), (row, _) in positions.items():
        if kind != "source" or row.get("duplicate_of") is None:
            continue
        prior = row["duplicate_of"]
        require(isinstance(prior, dict) and set(prior) == {"page", "row"}
                and type(prior["page"]) is int and type(prior["row"]) is int
                and prior["page"] < page
                and (prior["page"], "source", prior["row"]) in positions,
                f"{label}: duplicate citation invalid")
        original = positions[(prior["page"], "source", prior["row"])][0]
        require(original.get("duplicate_of") is None
                and isinstance(row.get("duplicate_evidence"), str)
                and row["duplicate_evidence"].strip(),
                f"{label}: duplicate source evidence mismatch")
    counts = document.get("counts", {})
    require(counts.get("source_positions") == athletes
            and counts.get("aggregate_rows_excluded") == aggregates
            and counts.get("duplicate_rendered_rows") == duplicates
            and counts.get("nonduplicate_candidate_positions") == athletes - duplicates,
            f"{label}: position accounting mismatch")
    if source == SOURCE_SHA256:
        require((athletes, aggregates, duplicates) == (155, 28, 7)
                and sum(len(p["rows"]) for p in pages[:2]) == 0
                and sum(len(p["aggregate_rows"]) for p in pages[2:]) == 0
                and pages[17]["rows"] == []
                and len(pages[24]["rows"]) == 7
                and all(row.get("duplicate_of") is not None and row["duplicate_of"]["page"] == 24
                        for row in pages[24]["rows"]),
                f"{label}: official Firenze role accounting mismatch")
    return positions, attestation


def check_historical(document, source, byte_count, sizes):
    require(document.get("schema") == PASS_SCHEMA
            and document.get("source_sha256") == source
            and document.get("source_bytes") == byte_count,
            "historical packet source binding mismatch")
    pages = document.get("pages")
    require(isinstance(pages, list) and len(pages) == 25,
            "historical packet page coverage mismatch")
    positions = {}
    for number, page in enumerate(pages, 1):
        require(page.get("page") == number, "historical packet page order mismatch")
        for kind, section in (("source", "rows"), ("aggregate", "aggregate_rows")):
            rows = page.get(section)
            require(isinstance(rows, list), "historical packet rows missing")
            for ordinal, row in enumerate(rows, 1):
                key = (number, kind, ordinal)
                expected_id = f"{kind}-position:" + sha(
                    f"{source}:page:{number}:{kind}:row:{ordinal}".encode())
                citation = row.get("citation", {})
                region = citation.get("region", {})
                require(row.get("row") == ordinal and row.get("id") == expected_id
                        and citation.get("page") == number
                        and region.get("units") == "upright_png_pixels_220dpi"
                        and bbox_valid(region.get("bbox"), sizes[number])
                        and fields_valid(row.get("fields")),
                        "historical packet row identity or citation mismatch")
                positions[key] = row
    counts = document.get("counts", {})
    require(len(positions) == 183 and counts.get("source_positions") == 155
            and counts.get("aggregate_rows_excluded") == 28
            and counts.get("duplicate_rendered_rows") == 7,
            "historical packet position accounting mismatch")
    return positions


def checked_stage(path, expected):
    stage = bound_json(path, expected, "stage")
    require(stage.get("schema") == STAGE_SCHEMA, "stage schema mismatch")
    versions = stage.get("observation_versions", [])
    unresolved = stage.get("unresolved_positions", [])
    non_primary = stage.get("non_primary_positions", [])
    counts = stage.get("counts", {})
    require(counts.get("candidate_result_positions") == len(versions) + len(unresolved)
            and counts.get("aggregate_rows_excluded") == sum(
                item.get("disposition") == "aggregate" for item in non_primary)
            and counts.get("duplicate_rendered_rows") == sum(
                item.get("disposition") == "duplicate_render" for item in non_primary)
            and all(item.get("source_role") == "athlete_result" for item in versions + unresolved)
            and all(item.get("source_role") in ("aggregate", "duplicate_render")
                    for item in non_primary), "stage role accounting mismatch")


def build(args):
    pdf = args.pdf.read_bytes()
    source = sha(pdf)
    require(source == args.expected_source_sha256 and SHA.fullmatch(source),
            "PDF source SHA256 mismatch")
    first = normalize_pass(bound_json(args.packet, args.packet_sha256, "first packet"), source)
    second = normalize_pass(bound_json(args.second_pass, args.second_pass_sha256, "second pass"), source)
    basis = {}
    for name, document, path, expected in (
            ("first", first, args.first_sealed, args.first_sealed_sha256),
            ("second", second, args.second_sealed, args.second_sealed_sha256)):
        if document.get("schema") == COMPARABLE_SCHEMA:
            require(path is not None and expected is not None,
                    f"{name}: pinned sealed source pass required")
            sealed = bound_json(path, expected, f"{name} sealed pass")
            require(sealed.get("source_sha256") == source
                    and document.get("basis_sha256") == expected,
                    f"{name}: comparable derivative basis mismatch")
            basis[name] = expected
    parent_derivative_hash = None
    if first.get("normalization_parent_sha256") is not None:
        require(args.first_parent_derivative is not None
                and args.first_parent_derivative_sha256 is not None,
                "pinned first normalization parent required")
        parent = bound_json(args.first_parent_derivative,
                            args.first_parent_derivative_sha256,
                            "first normalization parent")
        require(first["normalization_parent_sha256"] == args.first_parent_derivative_sha256
                and parent.get("source_sha256") == source
                and parent.get("basis_sha256") == basis.get("first"),
                "first normalization parent binding mismatch")
        parent_derivative_hash = args.first_parent_derivative_sha256
    addendum_hash = None
    if args.first_addendum is not None or args.first_addendum_sha256 is not None:
        require(args.first_addendum is not None and args.first_addendum_sha256 is not None,
                "first source-image addendum requires pinned bytes")
        addendum = bound_json(args.first_addendum, args.first_addendum_sha256,
                              "first source-image addendum")
        require(addendum.get("original_pdf_sha256") == source
                and addendum.get("base_sha256") == basis.get("first", args.packet_sha256),
                "first source-image addendum source or basis mismatch")
        addendum_hash = args.first_addendum_sha256
    receipt = bound_json(args.receipt, args.receipt_sha256, "receipt")
    receipt_valid(receipt, pdf, source, args.receipt)
    historical_hash = None
    historical_positions = None
    if source == SOURCE_SHA256:
        require(args.historical_packet is not None and args.historical_packet_sha256 is not None,
                "official Firenze import needs recovered historical packet")
    if args.historical_packet is not None or args.historical_packet_sha256 is not None:
        require(args.historical_packet is not None and args.historical_packet_sha256 is not None,
                "historical packet requires pinned bytes")
        historical = bound_json(args.historical_packet, args.historical_packet_sha256,
                                "historical packet")
        require(historical.get("schema") == PASS_SCHEMA
                and historical.get("source_sha256") == source
                and historical.get("source_bytes") == len(pdf),
                "historical packet source binding mismatch")
        if source == SOURCE_SHA256:
            require(args.historical_packet_sha256 == HISTORICAL_PACKET_SHA256,
                    "historical Firenze packet SHA256 mismatch")
        historical_hash = args.historical_packet_sha256
    require(isinstance(args.parser_version, str) and args.parser_version.strip(),
            "parser version required")
    require(len(args.render) == 25, "25 source page renders required")
    renders = {}
    sizes = {}
    for number, path in enumerate(args.render, 1):
        raw = path.read_bytes()
        sizes[number] = png_size(raw)
        renders[number] = sha(raw)
    positions, first_attestation = check_pass(first, source, len(pdf), sizes, "first pass")
    later, second_attestation = check_pass(second, source, len(pdf), sizes, "second pass")
    if historical_hash is not None:
        historical_positions = check_historical(historical, source, len(pdf), sizes)
        require(set(historical_positions) == set(positions),
                "historical packet source-position coverage mismatch")
    require(first_attestation["transcriber"] != second_attestation["transcriber"],
            "independent transcribers required")
    require(set(positions) == set(later), "independent pass position coverage mismatch")
    differences = {}
    unpaired_fields = {}
    historical_conflicts = {}
    for key in sorted(positions):
        row = positions[key][0]
        other = later[key][0]
        require(row.get("duplicate_of") == other.get("duplicate_of"),
                "duplicate classification disagreement requires correction")
        unpaired = sorted(set(row["fields"]) ^ set(other["fields"]))
        require(not unpaired or key[1] == "aggregate" or row.get("duplicate_of") is not None,
                "primary athlete raw fields cannot be unpaired")
        changed = sorted(field for field in set(row["fields"]) & set(other["fields"])
                         if not same_field(field, row["fields"][field], other["fields"][field]))
        if unpaired:
            unpaired_fields[key] = unpaired
        for field in ("status_raw", "penalty_raw", "notes_raw"):
            if not same_reading(row[field], other[field]):
                changed.append(field)
        if changed:
            differences[key] = changed
        if historical_positions is not None and key[1] == "source":
            historical_row = historical_positions[key]
            prior = historical_row["fields"]
            def normalized_text(value):
                return re.sub(r"\s+", " ", value or "").strip().casefold()
            conflict = sorted(field for field, label in HISTORICAL_FIELDS.items()
                if field in row["fields"] and field in other["fields"] and label in prior
                and normalized_text(row["fields"][field]) == normalized_text(other["fields"][field])
                and normalized_text(row["fields"][field]) != normalized_text(prior[label]))
            conflict.extend(field for field in ("status_raw", "penalty_raw", "notes_raw")
                if normalized_text(row[field]) == normalized_text(other[field])
                and normalized_text(row[field]) != normalized_text(historical_row.get(field)))
            if conflict:
                historical_conflicts[key] = sorted(conflict)
    agreements = sorted(key for key in positions
                        if key[1] == "source" and positions[key][0].get("duplicate_of") is None
                        and key not in differences)
    sample_count = math.ceil(len(agreements) / 10)
    sample = set(sorted(agreements, key=lambda key: sha(
        f"{source}:page:{key[0]}:role:{key[1]}:row:{key[2]}".encode()))[:sample_count])
    candidate_count = sum(key[1] == "source" and
                          positions[key][0].get("duplicate_of") is None for key in positions)
    counts = {"cited_positions": len(positions),
              "athlete_appearances": sum(key[1] == "source" for key in positions),
              "candidate_result_positions": candidate_count,
              "aggregate_rows_excluded": sum(key[1] == "aggregate" for key in positions),
              "duplicate_rendered_rows": sum(
                  key[1] == "source" and positions[key][0].get("duplicate_of") is not None
                  for key in positions),
              "disagreements": len(differences), "unpaired_positions": len(unpaired_fields),
              "historical_conflicts": len(historical_conflicts),
              "sampled_agreements": sample_count,
              "confirmed_distinct_attempts": None}
    comparison = {"schema": "firenze-image-comparison/v1", "source_sha256": source,
        "first_packet_sha256": args.packet_sha256, "second_pass_sha256": args.second_pass_sha256,
        "sealed_basis_sha256": basis, "first_addendum_sha256": addendum_hash,
        "historical_packet_sha256": historical_hash,
        "first_normalization_parent_sha256": parent_derivative_hash,
        "render_sha256": [renders[n] for n in sorted(renders)],
        "disagreements": [{"page": key[0], "row": key[2], "role": key[1],
                           "fields": differences[key]} for key in sorted(differences)],
        "unpaired_fields": [{"page": key[0], "row": key[2], "role": key[1],
                              "fields": unpaired_fields[key]} for key in sorted(unpaired_fields)],
        "historical_conflicts": [{"page": key[0], "row": key[2], "role": key[1],
                                   "fields": historical_conflicts[key]}
                                  for key in sorted(historical_conflicts)],
        "sampled_agreements": [[key[0], key[1], key[2]] for key in sorted(sample)],
        "sampling": {"method": "SHA256(source:page:N:role:R:row:M) ascending",
                     "size": sample_count}, "counts": counts}
    if args.compare_only:
        return comparison
    require(args.inspections is not None and args.inspections_sha256 is not None,
            "pinned image inspections required")
    inspections = bound_json(args.inspections, args.inspections_sha256, "inspections")
    require(inspections.get("schema") == INSPECTION_SCHEMA
            and inspections.get("source_sha256") == source
            and isinstance(inspections.get("records"), list),
            "inspection artifact invalid")
    if inspections.get("comparison_sha256") is not None:
        require(args.comparison is not None and args.comparison_sha256 is not None,
                "pinned comparison required by inspections")
        pinned_comparison = bound_json(args.comparison, args.comparison_sha256,
                                       "comparison")
        require(inspections["comparison_sha256"] == args.comparison_sha256
                and pinned_comparison == comparison,
                "inspection comparison binding mismatch")
    if inspections.get("historical_packet_sha256") is not None:
        require(inspections["historical_packet_sha256"] == historical_hash,
                "inspection historical packet binding mismatch")
    if inspections.get("parent_sha256") is not None:
        require(args.inspection_parent is not None
                and args.inspection_parent_sha256 is not None,
                "pinned inspection parent required")
        parent_inspections = bound_json(args.inspection_parent,
                                        args.inspection_parent_sha256,
                                        "inspection parent")
        require(inspections["parent_sha256"] == args.inspection_parent_sha256
                and parent_inspections.get("source_sha256") == source,
                "inspection parent binding mismatch")
    checked = {}
    for item in inspections["records"]:
        key = (item.get("page"), item.get("role"), item.get("row"))
        require(key in positions and key not in checked, "inspection position invalid")
        row = positions[key][0]
        reason = item.get("reason")
        allowed_reasons = ({"disagreement", "shared_error"} if key in differences else
                           {"historical_conflict", "shared_error"}
                           if key in historical_conflicts else
                           {"unpaired_aggregate"} if key in unpaired_fields else
                           {"agreement_sample", "shared_error"})
        require(reason in allowed_reasons
                and item.get("render_sha256") == renders[key[0]]
                and item.get("bbox") == row["citation"]["region"]["bbox"]
                and isinstance(item.get("inspector"), str)
                and item["inspector"].strip()
                and item["inspector"] not in
                {first_attestation["transcriber"], second_attestation["transcriber"]},
                "inspection source citation or independent inspector invalid")
        fields = item.get("fields_raw")
        uncertain = item.get("uncertain_fields")
        require(fields_valid(fields) and set(fields) == set(row["fields"])
                and isinstance(uncertain, list)
                and len(uncertain) == len(set(uncertain))
                and set(uncertain) <= set(fields),
                "inspection raw reading invalid")
        for name in ("status_raw", "penalty_raw", "notes_raw"):
            if name in differences.get(key, []) or name in historical_conflicts.get(key, []):
                require(name in item and (item[name] is None or isinstance(item[name], str)),
                        "inspection must resolve changed raw status, penalty, or note")
        if reason == "agreement_sample":
            require(all(same_reading(fields[field], row["fields"][field])
                        for field in fields), "inspection contradicts agreed raw reading")
        if reason in ("shared_error", "unpaired_aggregate", "historical_conflict"):
            require(isinstance(item.get("source_image_correction"), str)
                    and item["source_image_correction"].strip(),
                    "source image correction note required")
        checked[key] = item
    require(set(differences) | set(historical_conflicts) | sample <= set(checked),
            "disagreements, historical conflicts and sampled agreements need image inspections")
    versions, unresolved, non_primary = [], [], []
    for key in sorted(positions):
        row, page = positions[key]
        other = later[key][0]
        historical_row = historical_positions[key] if historical_positions else None
        inspection = checked.get(key)
        raw = inspection["fields_raw"] if inspection else row["fields"]
        retained_raw = historical_row["fields"] if historical_row else raw
        position = {"id": row["id"], "page": key[0], "row": key[2],
                    "citation": row["citation"]}
        selected_status = {name: inspection[name] if inspection and name in inspection
                           else row[name] for name in ("status_raw", "penalty_raw", "notes_raw")}
        provenance = {"first_pass_raw_fields": row["fields"],
            "second_pass_raw_fields": other["fields"], "first_pass_uncertainties": row["uncertainties"],
            "second_pass_uncertainties": other["uncertainties"], "inspection": inspection,
            "unpaired_fields": unpaired_fields.get(key, []),
            "historical_conflict_fields": historical_conflicts.get(key, []),
            "historical_fields_raw": historical_row["fields"] if historical_row else None,
            "historical_citation": historical_row["citation"] if historical_row else None,
            "raw_fields_origin": "historical_visual_packet" if historical_row else "source_image_pass",
            "historical_field_verification_scope": (
                "source-position/citation verified; full printed cell map retained; "
                "blind OCR and cited inspections are separate evidence"
                if historical_row else None),
            "source_inspection_fields_raw": raw if inspection else None,
            "status_raw": row["status_raw"], "penalty_raw": row["penalty_raw"],
            "notes_raw": row["notes_raw"], "second_pass_status_raw": other["status_raw"],
            "second_pass_penalty_raw": other["penalty_raw"],
            "second_pass_notes_raw": other["notes_raw"],
            "selected_status_raw": selected_status["status_raw"],
            "selected_penalty_raw": selected_status["penalty_raw"],
            "selected_notes_raw": selected_status["notes_raw"],
            "page_context_raw": {name: page.get(name) for name in
                ("heading_raw", "event_date_printed", "category_raw", "discipline_raw")}}
        if key[1] == "aggregate" or row.get("duplicate_of") is not None:
            disposition = "aggregate" if key[1] == "aggregate" else "duplicate_render"
            non_primary.append({**position, "source_role": disposition,
                "disposition": disposition, "raw_fields": retained_raw,
                "duplicate_of": row.get("duplicate_of"), **provenance})
            continue
        if row["uncertainties"] or other["uncertainties"] or (inspection and inspection["uncertain_fields"]):
            unresolved.append({**position, "source_role": "athlete_result",
                "reason": "uncertain raw reading", "raw_fields": retained_raw, **provenance})
            continue
        identity = [source, row["id"], args.parser_version, args.packet_sha256,
                    args.second_pass_sha256, args.inspections_sha256, retained_raw, provenance]
        versions.append({"id": "observation-version:" + sha(json.dumps(identity,
            ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()),
            "source_sha256": source, "source_position": position,
            "source_role": "athlete_result", "parser_version": args.parser_version,
            "raw_fields": retained_raw, "interpreted_fields": {}, **provenance,
            "verification": "source_inspected" if inspection else "agreed_uninspected"})
    return {"schema": STAGE_SCHEMA, "source_sha256": source,
        "parser_version": args.parser_version,
        "input_sha256": {"first_packet": args.packet_sha256, "second_pass": args.second_pass_sha256,
                         "receipt": args.receipt_sha256, "inspections": args.inspections_sha256,
                         "comparison": args.comparison_sha256,
                         "inspection_parent": args.inspection_parent_sha256,
                         "sealed_basis": basis, "first_addendum": addendum_hash},
        "historical_packet_sha256": historical_hash,
        "first_normalization_parent_sha256": parent_derivative_hash,
        "render_sha256": [renders[n] for n in sorted(renders)],
        "first_pass_attestation": first_attestation,
        "second_pass_attestation": second_attestation,
        "verification": {"disagreements": comparison["disagreements"],
                         "historical_conflicts": comparison["historical_conflicts"],
                         "sampled_agreements": comparison["sampled_agreements"]},
        "observation_versions": versions, "unresolved_positions": unresolved,
        "non_primary_positions": non_primary,
        "counts": {**counts, "verified_staged_versions": len(versions),
                   "unresolved_positions": len(unresolved),
                   "non_primary_positions": len(non_primary)}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("pdf", "packet", "second-pass", "receipt", "output",
                 "inspections", "import-stage", "sqlite-store", "first-sealed",
                 "second-sealed", "first-addendum", "comparison", "inspection-parent"):
        parser.add_argument(f"--{name}", type=Path)
    for name in ("expected-source-sha256", "packet-sha256", "second-pass-sha256",
                 "receipt-sha256", "inspections-sha256", "parser-version", "stage-sha256"):
        parser.add_argument(f"--{name}")
    parser.add_argument("--historical-packet", type=Path)
    parser.add_argument("--first-parent-derivative", type=Path)
    for name in ("first-sealed-sha256", "second-sealed-sha256", "first-addendum-sha256",
                 "historical-packet-sha256", "first-parent-derivative-sha256",
                 "comparison-sha256", "inspection-parent-sha256"):
        parser.add_argument(f"--{name}")
    parser.add_argument("--render", action="append", type=Path)
    parser.add_argument("--compare-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.import_stage:
            require(args.stage_sha256 and args.sqlite_store,
                    "stage import requires SHA256 and isolated store")
            destination = args.sqlite_store.resolve()
        else:
            require(all(getattr(args, key) is not None for key in
                ("pdf", "packet", "second_pass", "receipt", "output", "packet_sha256",
                 "second_pass_sha256", "receipt_sha256", "parser_version", "render")),
                "verification input missing")
            args.expected_source_sha256 = args.expected_source_sha256 or SOURCE_SHA256
            destination = args.output.resolve()
        repo = Path(__file__).resolve().parents[1]
        if destination.is_relative_to(repo):
            ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "-q",
                                      str(destination)], check=False)
            require(ignored.returncode == 0, "private output inside repository must be Git ignored")
        if args.import_stage:
            checked_stage(args.import_stage, args.stage_sha256)
            result = import_stage(args.import_stage, args.stage_sha256, destination,
                                  stage_schema=STAGE_SCHEMA,
                                  store_schema="firenze-isolated-store/v1",
                                  marker_table="firenze_stage_meta")
            print(json.dumps(result, sort_keys=True))
            return 0
        document = build(args)
        raw = (json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
        if destination.exists():
            require(destination.read_bytes() == raw, "output exists with different version")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".firenze-stage-",
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
        print(f"Firenze import rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
