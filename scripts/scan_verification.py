"""Deterministic review of two retained, source-bound scan transcriptions.

Provenance attestations are recorded assertions, not proof of independence.
This module reads only supplied artifacts and never calls a model or network.
"""

from copy import deepcopy
import hashlib
import json
import re


_SHA = re.compile(r"[0-9a-f]{64}\Z")
_DECISIONS = {"confirmed", "resolved", "unresolved"}


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
    ranked = sorted(positions, key=lambda position: (
        hashlib.sha256(f"{source_sha}:{position}".encode()).hexdigest(), position))
    return sorted(ranked[:sample_size])


def verify_transcriptions(first, second, inspections, *, sample_size):
    """Return cited review results from retained artifacts, or reject incomplete review.

    Every disagreement needs a source inspection. A deterministic sample of
    agreements also needs inspection. Null readings remain unresolved unless
    an inspection explicitly resolves them. No attempt or identity is imported.
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
        _require(_citation(first_entries[position], source) == _citation(second_entries[position], source),
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
        _require(_citation(inspection, source) == _citation(first_entries[position], source),
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
