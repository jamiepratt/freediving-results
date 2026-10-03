"""Deterministic review of two retained, source-bound scan transcriptions.

Provenance attestations are recorded assertions, not proof of independence.
This module reads only supplied artifacts and never calls a model or network.
"""

from copy import deepcopy
import hashlib
import json
import re
from pathlib import Path


_SHA = re.compile(r"[0-9a-f]{64}\Z")
_DECISIONS = {"confirmed", "resolved", "unresolved"}
TRUSTED_REVIEW_SHA256 = "bda8637049bee3a8b99578c8927875c286c353680eade0f08c864b803f28b84b"


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _citation(entry, source_sha):
    _require(isinstance(entry, dict), "position must be an object")
    _require(isinstance(entry.get("position"), str) and entry["position"], "missing position")
    _require(type(entry.get("page")) is int and entry["page"] > 0, "invalid page")
    region = entry.get("region_px")
    _require(isinstance(region, dict) and set(region) == {"x1", "y1", "x2", "y2"}, "invalid region")
    _require(all(type(v) is int and v >= 0 for v in region.values())
             and region["x1"] < region["x2"] and region["y1"] < region["y2"], "invalid region")
    _require(isinstance(entry.get("citation"), str) and entry["citation"], "missing citation")
    if "source_sha256" in entry:
        _require(entry["source_sha256"] == source_sha, "citation source mismatch")
    return entry["page"], region, entry["citation"]


def _same_source_position(left, right, source_sha):
    """Require one page and at least 50% intersection over union of pixel boxes."""
    left_page, left_region, _ = _citation(left, source_sha)
    right_page, right_region, _ = _citation(right, source_sha)
    if left_page != right_page:
        return False
    width = max(0, min(left_region["x2"], right_region["x2"])
                - max(left_region["x1"], right_region["x1"]))
    height = max(0, min(left_region["y2"], right_region["y2"])
                 - max(left_region["y1"], right_region["y1"]))
    intersection = width * height
    left_area = (left_region["x2"] - left_region["x1"]) * (left_region["y2"] - left_region["y1"])
    right_area = (right_region["x2"] - right_region["x1"]) * (right_region["y2"] - right_region["y1"])
    return 2 * intersection >= left_area + right_area - intersection


def _validate_pass(artifact):
    _require(isinstance(artifact, dict) and artifact.get("schema") == "scan-transcription/v1",
             "unsupported transcription schema")
    source = artifact.get("source_sha256")
    _require(isinstance(source, str) and _SHA.fullmatch(source), "invalid source SHA-256")
    for field in ("pass_id", "parser_version"):
        _require(isinstance(artifact.get(field), str) and artifact[field], f"missing {field}")
    provenance = artifact.get("provenance")
    _require(isinstance(provenance, dict)
             and isinstance(provenance.get("actor"), str) and provenance["actor"]
             and provenance.get("source_only") is True
             and provenance.get("first_pass_visible") is False
             and isinstance(provenance.get("attestation"), str) and provenance["attestation"],
             "missing independent-pass provenance attestation")
    entries = artifact.get("entries")
    _require(isinstance(entries, list) and entries, "missing transcription entries")
    by_position = {}
    for entry in entries:
        _citation(entry, source)
        _require("reading" in entry and (entry["reading"] is None
                 or isinstance(entry["reading"], str) and bool(entry["reading"])),
                 "reading must be nonempty text or explicit null")
        position = entry["position"]
        _require(position not in by_position, "duplicate transcription position")
        by_position[position] = entry
    return by_position


def _sample(source_sha, positions, sample_size):
    _require(type(sample_size) is int and 0 <= sample_size <= len(positions), "invalid agreement sample size")
    _require(not positions or sample_size > 0, "agreement sample must include a position")
    ranked = sorted(positions, key=lambda position: (
        hashlib.sha256(f"{source_sha}:{position}".encode()).hexdigest(), position))
    return sorted(ranked[:sample_size])


def verify_transcriptions(first, second, inspections, *, sample_size):
    """Return cited review results from retained artifacts, or reject incomplete review.

    Every disagreement needs a source inspection. A deterministic sample of
    agreements also needs inspection. Null readings remain unresolved unless
    an inspection explicitly resolves them. Pass and inspection boxes must
    overlap each other by at least 50% intersection over union on one page.
    No attempt or identity is imported.
    """
    first_entries = _validate_pass(first)
    second_entries = _validate_pass(second)
    source = first["source_sha256"]
    _require(second["source_sha256"] == source, "transcription source mismatch")
    _require(first["pass_id"] != second["pass_id"], "duplicate pass ID")
    _require(first["provenance"]["actor"] != second["provenance"]["actor"],
             "passes need distinct attested actors")
    _require(set(first_entries) == set(second_entries), "pass position coverage differs")
    positions = sorted(first_entries)
    for position in positions:
        _require(_same_source_position(first_entries[position], second_entries[position], source),
                 f"citation mismatch at {position}")

    agreements = [position for position in positions
                  if first_entries[position]["reading"] == second_entries[position]["reading"]
                  and first_entries[position]["reading"] is not None]
    disagreements = [position for position in positions if position not in agreements]
    sampled = _sample(source, agreements, sample_size)
    required = set(disagreements) | set(sampled)
    _require(isinstance(inspections, list), "inspections must be a list")
    by_position = {}
    for inspection in inspections:
        _require(isinstance(inspection, dict) and inspection.get("position") in required,
                 "inspection outside required source positions")
        position = inspection["position"]
        _require(position not in by_position, "duplicate inspection")
        _require(inspection.get("source_sha256") == source, "inspection source mismatch")
        _require(_same_source_position(inspection, first_entries[position], source)
                 and _same_source_position(inspection, second_entries[position], source),
                 "inspection citation mismatch")
        _require(isinstance(inspection.get("inspected_by"), str) and inspection["inspected_by"]
                 and isinstance(inspection.get("note"), str) and inspection["note"],
                 "missing source inspection proof")
        decision = inspection.get("decision")
        _require(decision in _DECISIONS, "invalid inspection decision")
        reading = inspection.get("reading")
        if decision == "unresolved":
            _require(reading is None, "unresolved inspection cannot accept a reading")
        else:
            _require(isinstance(reading, str) and reading, "inspection needs a source reading")
            if decision == "confirmed":
                _require(position in sampled and reading == first_entries[position]["reading"],
                         "confirmation must match sampled agreement")
            else:
                _require(position in disagreements, "resolution requires disagreement")
        by_position[position] = inspection
    _require(set(by_position) == required, "missing cited source inspection")

    rows = []
    for position in positions:
        inspection = by_position.get(position)
        accepted = (inspection.get("reading") if inspection and inspection["decision"] != "unresolved"
                    else first_entries[position]["reading"] if position in agreements and not inspection
                    else None)
        status = ("unresolved" if accepted is None else "source_inspected" if inspection
                  else "agreed_uninspected")
        rows.append({"position": position, "status": status, "accepted_reading": accepted,
                     "first_pass": deepcopy(first_entries[position]),
                     "second_pass": deepcopy(second_entries[position]),
                     "inspection": deepcopy(inspection)})
    return {"schema": "scan-verification/v1", "source_sha256": source,
            "passes": [{"pass_id": artifact["pass_id"], "parser_version": artifact["parser_version"],
                        "provenance": deepcopy(artifact["provenance"]),
                        "artifact_sha256": hashlib.sha256(json.dumps(artifact, sort_keys=True,
                            ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()}
                       for artifact in (first, second)],
            "sampling": {"method": "sha256(source_sha256:position) ascending", "size": sample_size},
            "sampled_agreements": sampled, "disagreements": disagreements, "positions": rows}


def _read_bound(path, expected, label):
    data = Path(path).read_bytes()
    _require(hashlib.sha256(data).hexdigest() == expected, f"{label} digest mismatch")
    return json.loads(data)


def replay_scan_source(manifest):
    """Rebuild a bounded source-position entry from retained private scan evidence.

    The manifest supplies paths and an independently pinned summary digest. A
    successful return is evidence routing input, never identity or review approval.
    """
    root = Path(manifest["bundle_root"])
    _require(manifest["summary_sha256"] == TRUSTED_REVIEW_SHA256,
             "untrusted review digest")
    source_name = manifest["source"]
    _require(re.fullmatch(r"napoli_(?:statica|dinamica|combinata)_(?:maschile|femminile)_2025\.jpg",
                          source_name) is not None, "unsupported scan family")
    stem = source_name[:-4]
    summary = _read_bound(root / "review" / "summary.json", manifest["summary_sha256"], "summary")
    _require(summary.get("schema") == "clear62-blind-scan-review/v1", "unsupported summary schema")
    sources = [item for item in summary.get("sources", []) if item.get("source") == source_name]
    _require(len(sources) == 1, "source absent or duplicate in summary")
    selected = sources[0]
    receipt = _read_bound(manifest["receipt_path"], summary["source_receipt_sha256"], "receipt")
    _require(receipt.get("schema") == "issue55-san-mauro-jpg-receipts/v1", "unsupported receipt schema")
    receipts = [item for item in receipt.get("sources", [])
                if Path(item.get("path", "")).name == source_name]
    _require(len(receipts) == 1, "source absent or duplicate in receipt")
    acquired = receipts[0]
    _require(acquired.get("sha256") == selected["source_sha256"]
             and acquired.get("http_status") == 200
             and acquired.get("content_type") == "image/jpeg"
             and isinstance(acquired.get("retrieved_at"), str), "source acquisition mismatch")
    source_path = Path(acquired["path"])
    source_bytes = source_path.read_bytes()
    _require(hashlib.sha256(source_bytes).hexdigest() == selected["source_sha256"]
             and len(source_bytes) == acquired["bytes"], "source bytes mismatch")
    if "headers_path" in acquired:
        _require(hashlib.sha256(Path(acquired["headers_path"]).read_bytes()).hexdigest()
                 == acquired["headers_sha256"], "headers digest mismatch")
    first = _read_bound(root / "blind-a" / (stem + ".json"),
                        selected["first_pass_sha256"], "first pass")
    second = _read_bound(root / "blind-b" / (stem + ".json"),
                         selected["second_pass_sha256"], "second pass")
    review = _read_bound(root / "review" / (stem + ".verification.json"),
                         selected["verification_sha256"], "review")
    _require(review.get("schema") == "scan-verification/v1", "unsupported review schema")
    inspections = [row["inspection"] for row in review["positions"] if row["inspection"]]
    rebuilt = verify_transcriptions(first, second, inspections,
                                    sample_size=review["sampling"]["size"])
    _require(rebuilt == review, "stale or forged review")
    coverages = []
    for directory, artifact in (("blind-a", first), ("blind-b", second)):
        coverage = json.loads((root / directory / (stem + ".coverage.json")).read_text())
        _require(coverage.get("schema") == "scan-transcription-coverage/v1"
                 and coverage.get("source_sha256") == selected["source_sha256"]
                 and coverage.get("pass_id") == artifact["pass_id"], "coverage provenance mismatch")
        coverages.append(coverage)
    sections = coverages[0]["sections"]
    _require(all(isinstance(item.get("rows_attempted"), int)
                 and item["rows_attempted"] > 0
                 and isinstance(item.get("rows_unexamined"), int)
                 and item["rows_unexamined"] >= 0 for coverage in coverages
                 for item in coverage["sections"]), "invalid section lengths")
    _require(len(sections) == len(coverages[1]["sections"]) == selected["sections"]
             and sum(item["rows_attempted"] for item in sections) == selected["positions"]
             and [item["rows_attempted"] for item in sections]
             == [item["rows_attempted"] for item in coverages[1]["sections"]]
             and len(review["positions"]) == selected["positions"], "section coverage mismatch")
    ambiguous = sum(row["status"] == "unresolved" for row in review["positions"])
    family = stem.split("_")[1]
    role = "aggregate" if family == "combinata" else "individual-result"
    for section in sections:
        label = section["label"].upper()
        _require((family == "combinata" and "COMBINATA" in label)
                 or (family == "statica" and "STATICA" in label)
                 or (family == "dinamica" and "CLASSIFICA" in label),
                 "section family mismatch")
        _require(section["rows_unexamined"] == 0, "unexamined scan section")
    _require(all(section["rows_unexamined"] == 0 for section in coverages[1]["sections"]),
             "unexamined scan section")
    positions = []
    candidates = []
    section_index = 0
    section_end = sections[0]["rows_attempted"]
    for index, row in enumerate(review["positions"]):
        _require(row["position"] == f"row:{index + 1:03d}",
                 "source row order differs from section coverage")
        while index >= section_end:
            section_index += 1
            section_end += sections[section_index]["rows_attempted"]
        source_citations = {"first_pass": row["first_pass"]["citation"],
                            "second_pass": row["second_pass"]["citation"],
                            "inspection": (row["inspection"] or {}).get("citation")}
        position_id = row["position"]
        citation = f"sha256:{selected['source_sha256']}#{position_id}"
        coordinates = {"page": row["first_pass"]["page"],
                       "region_px": row["first_pass"]["region_px"],
                       "section": sections[section_index]["label"],
                       "evidence_role": role}
        positions.append({"id": position_id, "citation": citation,
                          "coordinates": coordinates, "ambiguous": row["status"] == "unresolved"})
        if row["status"] != "unresolved":
            candidates.append({"id": position_id, "citation": citation,
                               "coordinates": coordinates, "evidence_role": role,
                               "parsed": {"source_reading": row["accepted_reading"]},
                               "source_citations": source_citations,
                               "verification_status": row["status"]})
    _require(len(candidates) + ambiguous == len(positions), "position accounting mismatch")
    return {"document": {"source_sha256": selected["source_sha256"], "format": "image",
                         "positions": positions},
            "candidates": candidates,
            "source_path": str(source_path),
            "parser_version": "scan-review/1",
            "verification": {"source_sha256": selected["source_sha256"],
                             "summary_sha256": manifest["summary_sha256"],
                             "review_sha256": selected["verification_sha256"],
                             "first_pass_sha256": selected["first_pass_sha256"],
                             "second_pass_sha256": selected["second_pass_sha256"]}}


if __name__ == "__main__":
    import sys
    _require(len(sys.argv) == 3 and sys.argv[1] == "replay-source", "usage: replay-source manifest.json")
    with open(sys.argv[2], encoding="utf-8") as input_file:
        print(json.dumps(replay_scan_source(json.load(input_file)), ensure_ascii=False))
