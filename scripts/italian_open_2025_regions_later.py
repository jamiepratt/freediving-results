#!/usr/bin/env python3
"""Add checked page 19-35 regions to the private Italian Open evidence packet."""

import argparse
from copy import deepcopy
import json
from pathlib import Path
import sys

from italian_open_2025_regions import (SOURCE_SHA256, SOURCE_BYTES, REGION_NOTE,
                                       apply_corrections, collides, digest, encoded,
                                       read, recount, region, require)


PRIOR_SHA256 = "54e003b0b5ebac55864a84740bb69b6255287fc8793dcd163fe0ffb0fc97bb98"
FIRST_PAGE, LAST_PAGE, EXPECTED_LATER_ROWS, EXPECTED_TOTAL_ROWS = 19, 35, 104, 242
LINK_KEYS = ("duplicate_of", "duplicate_evidence", "relationship_candidate_of",
             "relationship_evidence", "relationship_candidate_note")


def validate_existing_regions(prior):
    for page in prior["pages"][:FIRST_PAGE - 1]:
        boxes = []
        for row in page["rows"]:
            citation = row["citation"]
            require(citation["page"] == page["page"] and
                    citation["source_sha256"] == prior["source_sha256"],
                    f"page {page['page']} row {row['row']}: citation differs")
            checked = region({"page": page["page"], "row": row["row"],
                              "bbox": citation["region"]["bbox"],
                              "render_sha256": citation["region"]["render_sha256"]},
                             page["render"]["sha256"])
            require(citation["region"] == checked and
                    not any(collides(checked["bbox"], box) for box in boxes),
                    f"page {page['page']} row {row['row']}: prior region invalid")
            boxes.append(checked["bbox"])


def validate_repeats(packet):
    page34, page35 = packet["pages"][33:35]
    require(len(page34["rows"]) == len(page35["rows"]) == 6,
            "pages 34-35 must each have six repeat positions")
    for earlier, repeat in zip(page34["rows"], page35["rows"]):
        require(repeat["disposition"] == "duplicate_render" and
                repeat.get("duplicate_of") == {"page": 34, "row": earlier["row"]} and
                repeat["row"] == earlier["row"] and
                all(repeat[key] == earlier[key] for key in
                    ("fields_raw", "status_raw", "penalty_raw", "notes_raw")) and
                isinstance(repeat.get("duplicate_evidence"), str) and
                bool(repeat["duplicate_evidence"].strip()),
                f"page 35 row {repeat['row']}: exact repeat link differs from page 34")


def build(prior, prior_hash, pdf, renders, first, second, expected,
          expected_prior):
    require(prior_hash == expected_prior,
            "prior packet SHA256 differs from pinned version")
    require(prior.get("schema") == "italian-open-2025-visual-evidence/v2",
            "prior packet schema invalid")
    require(prior.get("source_sha256") == expected and
            digest(pdf.read_bytes()) == expected and
            prior.get("source_bytes") == pdf.stat().st_size and
            (expected != SOURCE_SHA256 or pdf.stat().st_size == SOURCE_BYTES),
            "PDF hash or byte count differs from pinned source")
    pages = prior.get("pages", [])
    require(len(pages) == 35 and
            [page.get("page") for page in pages] == list(range(1, 36)),
            "prior packet must have pages 1-35")
    require(sum(len(page["rows"]) for page in pages) == EXPECTED_TOTAL_ROWS,
            "prior packet must have exactly 242 source positions")
    require(prior.get("owner_review_status") == "unreviewed" and
            prior.get("observation_versions") == [] and
            prior.get("counts", {}).get("confirmed_distinct_attempts") is None,
            "prior packet has review, import, or confirmed attempts")
    validate_existing_regions(prior)
    validate_repeats(prior)
    by_position = {(page["page"], row["row"]): row
                   for page in pages[FIRST_PAGE - 1:] for row in page["rows"]}
    require(len(by_position) == EXPECTED_LATER_ROWS and
            len(first) + len(second) == EXPECTED_LATER_ROWS,
            "pages 19-35 and maps must have exactly 104 positions")
    entries = {}
    for part, allowed in ((first, range(19, 27)), (second, range(27, 36))):
        for entry in part:
            require(isinstance(entry, dict), "region map entry must be an object")
            number, ordinal = entry.get("page"), entry.get("row")
            require(type(number) is int and type(ordinal) is int and
                    number in allowed and (number, ordinal) in by_position,
                    "map entry does not identify a source position in its page range")
            require(entry.get("id") == by_position[(number, ordinal)]["id"],
                    f"page {number} row {ordinal}: source position ID mismatch")
            require((number, ordinal) not in entries,
                    f"page {number} row {ordinal}: duplicate map entry")
            entries[(number, ordinal)] = entry
    require(set(entries) == set(by_position),
            "maps must match every page 19-35 position once")
    output, changes = deepcopy(prior), []
    for page in output["pages"][FIRST_PAGE - 1:]:
        number = page["page"]
        expected_render = page["render"]["sha256"]
        image = renders / f"page-{number:02}.{page['render']['format']}"
        require(digest(image.read_bytes()) == expected_render,
                f"page {number}: render bytes differ from prior packet")
        boxes = []
        for row in page["rows"]:
            entry = entries[(number, row["row"])]
            require(row["citation"]["source_sha256"] == expected and
                    row["citation"]["page"] == number and
                    row["citation"]["region"] is None and
                    REGION_NOTE in row["uncertainties"],
                    f"page {number} row {row['row']}: prior region state differs")
            before = deepcopy(row)
            if entry.get("bbox") is None:
                reason = entry.get("unlocatable_reason")
                require(isinstance(reason, str) and reason.strip(),
                        f"page {number} row {row['row']}: unlocatable reason required")
                row["uncertainties"].remove(REGION_NOTE)
                row["uncertainties"].append("Exact row region unlocatable: " + reason.strip())
            else:
                require("unlocatable_reason" not in entry,
                        f"page {number} row {row['row']}: contradictory region evidence")
                checked = region(entry, expected_render)
                require(not any(collides(checked["bbox"], box) for box in boxes),
                        f"page {number} row {row['row']}: region overlaps another row")
                boxes.append(checked["bbox"])
                row["citation"]["region"] = checked
                row["uncertainties"].remove(REGION_NOTE)
            apply_corrections(row, entry)
            require(all(row.get(key) == before.get(key) for key in LINK_KEYS) and
                    row["id"] == before["id"] and row["row"] == before["row"] and
                    row["review_status"] == before["review_status"],
                    f"page {number} row {row['row']}: row identity or relationship changed")
            if row["citation"]["region"] is None:
                require(any(note.startswith("Exact row region unlocatable: ")
                            for note in row["uncertainties"]),
                        f"page {number} row {row['row']}: missing unlocatable reason")
            changes.append({"id": row["id"], "page": number, "row": row["row"],
                            "before": before, "after": deepcopy(row),
                            "evidence": entry.get("evidence"),
                            "uncertainty_decision": entry.get("uncertainty_decision")})
    validate_repeats(output)
    recount(output)
    def field_notes(row):
        return [note for note in row["uncertainties"]
                if note != REGION_NOTE and
                not note.startswith("Exact row region unlocatable: ")]
    output_rows = [row for page in output["pages"] for row in page["rows"]]
    output["counts"]["rows_with_field_uncertainties"] = sum(
        bool(field_notes(row)) for row in output_rows)
    output["counts"]["unresolved_field_notes"] = sum(
        len(field_notes(row)) for row in output_rows)
    require(output["pages"][:FIRST_PAGE - 1] == prior["pages"][:FIRST_PAGE - 1] and
            output["owner_review_status"] == "unreviewed" and
            output["observation_versions"] == [] and
            output["counts"]["confirmed_distinct_attempts"] is None,
            "earlier pages, review, import, or confirmed attempts changed")
    output["schema"] = "italian-open-2025-visual-evidence/v3"
    output["input_sha256"]["prior_packet"] = prior_hash
    diff = {"schema": "italian-open-2025-region-diff/v2",
            "prior_packet_sha256": prior_hash,
            "original_packet_sha256": prior["input_sha256"]["original_packet"],
            "source_sha256": expected, "rows": changes,
            "counts_before": prior["counts"], "counts_after": output["counts"]}
    return output, diff


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("prior", "pdf", "renders", "first-map", "second-map", "output", "diff"):
        parser.add_argument("--" + flag, required=True, type=Path)
    parser.add_argument("--expected-sha256", default=SOURCE_SHA256)
    parser.add_argument("--expected-prior-sha256", default=PRIOR_SHA256)
    args = parser.parse_args(argv)
    try:
        inputs = [args.prior, args.pdf, args.first_map, args.second_map]
        outputs = [args.output, args.diff]
        require(len({path.resolve() for path in inputs + outputs}) == 6,
                "input and output paths must differ")
        prior, prior_hash = read(args.prior)
        first, first_hash = read(args.first_map)
        second, second_hash = read(args.second_map)
        require(isinstance(first, list) and isinstance(second, list),
                "region maps must be JSON lists")
        output, diff = build(prior, prior_hash, args.pdf, args.renders,
                             first, second, args.expected_sha256,
                             args.expected_prior_sha256)
        output["input_sha256"]["region_maps"] += [first_hash, second_hash]
        raw, diff_raw = encoded(output), encoded(diff)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.diff.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(raw)
        args.diff.write_bytes(diff_raw)
        print(json.dumps({"packet": str(args.output), "sha256": digest(raw),
                          "diff": str(args.diff), "diff_sha256": digest(diff_raw),
                          "counts": output["counts"]}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Italian Open 2025 later region overlay rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
