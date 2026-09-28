#!/usr/bin/env python3
"""Replay the two archived FFESSM 2025 daily PDFs into a private packet."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path


SCHEMA = "ffessm-2025-daily/v1"
ROW = re.compile(
    r"^\s*(?P<surname>\S+)\s+(?P<given>.*?)\s+"
    r"(?P<sex>Homme|Femme)\s+(?P<nationality>\S+)\s+"
    r"(?P<federation>FFESSM|CMAS)\s+(?P<announced>\d+)\s*m\s+"
    r"(?P<discipline>CWT-MONO|CWT-BI|CNF|FIM)\s+"
    r"(?P<realized>\d+)\s*m\s+(?P<depth_penalty>\d+)\s+"
    r"(?:(?P<plate_penalty>1)\s+)?(?P<points>-?\d+)\s+"
    r"(?P<card>Blanc|Jaune|Rouge)(?:\s+(?P<comments>.*?))?\s*$"
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def parse_daily_text(extracted, day):
    """Parse athlete rows, retaining exact one-based pdftotext -layout citations."""
    require(day in (1, 2), "day must be 1 or 2")
    pages = [page.splitlines() for page in extracted.split("\f") if page.strip()]
    require(pages and any(f"Résultats Eau Libre J{day}" in line for page in pages for line in page),
            f"day {day} result title missing")
    require(any("Championnat de France 2025" in line for page in pages for line in page),
            "event heading missing")
    rows = []
    for page_number, lines in enumerate(pages, 1):
        for line_number, line in enumerate(lines, 1):
            match = ROW.fullmatch(line)
            if not match:
                # A result line with the federation and a discipline must never vanish silently.
                if re.search(r"\b(?:FFESSM|CMAS)\b", line) and re.search(
                        r"\b(?:CWT-MONO|CWT-BI|CNF|FIM)\b", line):
                    raise ValueError(f"unparsed athlete line: page {page_number} line {line_number}")
                continue
            fields = match.groupdict()
            position = len(rows) + 1
            rows.append({
                "position": position,
                "citation": f"page {page_number} line {line_number}",
                "coordinates": {"page": page_number, "line": line_number},
                "raw_line": line,
                "athlete": fields["surname"] + " " + fields["given"].strip(),
                "surname": fields["surname"], "given_name": fields["given"].strip(),
                "sex": fields["sex"], "nationality": fields["nationality"],
                "federation": fields["federation"],
                "announced_depth_m": int(fields["announced"]),
                "discipline": fields["discipline"],
                "realized_depth_m": int(fields["realized"]),
                "depth_penalty": int(fields["depth_penalty"]),
                "plate_fault_penalty": (int(fields["plate_penalty"])
                                        if fields["plate_penalty"] is not None else None),
                "total_points": int(fields["points"]), "card": fields["card"],
                "comments": fields["comments"] or None,
                "status": {"Blanc": "valid", "Jaune": "penalized",
                           "Rouge": "disqualified"}[fields["card"]],
                "category": fields["sex"],
                "unresolved_fields": ["plate_fault_penalty"]
                if fields["plate_penalty"] is None else [],
            })
    require(rows, f"day {day} has no athlete rows")
    return rows


def verified_pdf(path, source):
    require(path.is_file() and not path.is_symlink(), f"missing PDF original: {path}")
    data = path.read_bytes()
    require(data.startswith(b"%PDF-") and len(data) == source["bytes"]
            and sha(data) == source["sha256"], f"PDF hash or byte count mismatch: {path}")
    return data


def build(day1, day2, receipt_manifest):
    manifest_bytes = receipt_manifest.read_bytes()
    manifest = json.loads(manifest_bytes)
    require(manifest.get("schema") == "ffessm-2025-daily-receipts/v1",
            "invalid receipt manifest schema")
    sources = manifest.get("sources")
    require(isinstance(sources, list) and len(sources) == 2
            and [source.get("day") for source in sources] == [1, 2],
            "receipt manifest requires ordered day 1 and day 2 sources")
    source_packets = []
    observations = []
    for source, path in zip(sources, (day1, day2)):
        day = source["day"]
        require(str(path.resolve()) == str(Path(source["source_path"]).resolve()),
                f"day {day} source path differs from manifest")
        verified_pdf(path, source)
        citation = source.get("index_citation") or {}
        receipt = source.get("receipt") or {}
        require(citation.get("href") == receipt.get("discovery_url")
                and citation.get("sha256") == manifest.get("index_sha256")
                and citation.get("line") and receipt.get("final_url")
                and receipt.get("retrieved_at"), f"day {day} provenance incomplete")
        extracted = subprocess.run(["pdftotext", "-layout", str(path), "-"],
                                   capture_output=True, text=True, check=True).stdout
        rows = parse_daily_text(extracted, day)
        require(len(rows) == source.get("expected_positions"),
                f"day {day} position count differs from manifest")
        source_id = "sha256:" + source["sha256"]
        for row in rows:
            row.update({"id": f"{source_id}:position:{row['position']}",
                        "source_id": source_id, "day": day,
                        "event_date": source["event_date"],
                        "session": source["session"],
                        "date_source": "official result index link label",
                        "review_status": "unreviewed"})
        observations.extend(rows)
        source_packets.append({"id": source_id, "source_type": "daily_results",
                               "day": day, "event": source["event"],
                               "event_date": source["event_date"],
                               "session": source["session"],
                               "sha256": source["sha256"], "bytes": source["bytes"],
                               "index_citation": citation, "receipt": receipt,
                               "printed_positions": len(rows),
                               "parser_observations": len(rows),
                               "confirmed_distinct_attempts": None})
    return {"schema": SCHEMA,
            "inputs": {"receipt_manifest_sha256": sha(manifest_bytes),
                       "index_url": manifest["index_url"],
                       "index_sha256": manifest["index_sha256"]},
            "counts": {"source_objects": 2,
                       "printed_positions": len(observations),
                       "parser_observations": len(observations),
                       "confirmed_distinct_attempts": None},
            "sources": source_packets, "observations": observations,
            "limits": ["The PDF titles identify J1 and J2; exact dates come from the cited official index labels.",
                       "A blank plate-fault cell is retained as null, not inferred as zero.",
                       "Daily rows alone do not prove distinct sporting attempts or ranking overlap."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("day1", "day2", "receipt-manifest", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    try:
        packet = build(args.day1, args.day2, args.receipt_manifest)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output.parent,
                                         prefix=".ffessm-daily-", delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(canonical(packet) + "\n")
        os.replace(temporary, args.output)
        os.chmod(args.output, 0o600)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"ffessm daily packet: {error}\n")


if __name__ == "__main__":
    main()
