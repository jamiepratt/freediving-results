#!/usr/bin/env python3
"""Create source-bound render and OCR locator evidence for reviewed CMAS scans.

OCR output is never promoted to accepted row data. The input ledger owns any
reviewed transcription and must reconcile every printed position.
"""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path


SCHEMA = "cmas-scan-evidence/v1"
LEDGER_VERSION = "camotes-world-cup-image-row-ledger/1"
SHA = re.compile(r"[0-9a-f]{64}\Z")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def png_dimensions(path):
    with path.open("rb") as stream:
        header = stream.read(24)
    if len(header) != 24 or not header.startswith(PNG_SIGNATURE) or header[12:16] != b"IHDR":
        raise ValueError(f"invalid PNG: {path.name}")
    return list(struct.unpack(">II", header[16:24]))


def run(*args):
    completed = subprocess.run(args, capture_output=True, text=True)
    if completed.returncode:
        raise ValueError(f"{args[0]} failed ({completed.returncode}): {completed.stderr.strip()}")
    return completed


def natural_number(value, label):
    if type(value) is not int or value < 0:
        raise ValueError(f"invalid {label}")
    return value


def validate_ledger(ledger, source_hash):
    if ledger.get("ledger_version") != LEDGER_VERSION:
        raise ValueError("unsupported ledger version")
    if ledger.get("source_sha256") != source_hash or not SHA.fullmatch(source_hash):
        raise ValueError("source SHA-256 mismatch")
    page_count = natural_number(ledger.get("source_page_count"), "source page count")
    if page_count == 0 or not isinstance(ledger.get("pages"), list) or len(ledger["pages"]) != page_count:
        raise ValueError("page count mismatch")
    if not isinstance(ledger.get("positions"), list) or not isinstance(ledger.get("totals"), dict):
        raise ValueError("missing positions or totals")
    totals = ledger["totals"]
    printed = natural_number(totals.get("printed_positions"), "printed total")
    parsed = natural_number(totals.get("parsed"), "parsed total")
    unresolved = natural_number(totals.get("unresolved"), "unresolved total")
    if printed != parsed + unresolved or printed != len(ledger["positions"]):
        raise ValueError("printed positions do not reconcile")
    required_fields = ledger.get("required_fields")
    if parsed:
        if (not isinstance(required_fields, list) or not required_fields
                or any(not isinstance(field, str) or not field for field in required_fields)
                or len(set(required_fields)) != len(required_fields)):
            raise ValueError("parsed rows need declared required fields")
    seen_pages = set()
    page_sizes = {}
    for page in ledger["pages"]:
        number = natural_number(page.get("page"), "page number")
        if number < 1 or number > page_count or number in seen_pages:
            raise ValueError("duplicate or missing page")
        seen_pages.add(number)
        dimensions = page.get("dimensions_px")
        if not isinstance(dimensions, list) or len(dimensions) != 2 or any(type(v) is not int or v <= 0 for v in dimensions):
            raise ValueError("invalid page dimensions")
        page_sizes[number] = dimensions
        natural_number(page.get("visual_printed_row_count"), "page row count")
        if "render_sha256" in page and not SHA.fullmatch(page["render_sha256"]):
            raise ValueError("invalid render SHA-256")
    if seen_pages != set(range(1, page_count + 1)):
        raise ValueError("missing page")
    seen_positions = set()
    counts = {number: 0 for number in seen_pages}
    statuses = {"reviewed": 0, "unresolved": 0}
    for row in ledger["positions"]:
        page = row.get("page")
        block = row.get("block")
        line = row.get("line")
        key = (page, block, line)
        if page not in page_sizes or any(type(v) is not int or v < 1 for v in key) or key in seen_positions:
            raise ValueError("invalid or duplicate row position")
        if row.get("position") != f"p{page}-b{block}-r{line:02d}":
            raise ValueError("row position label mismatch")
        seen_positions.add(key)
        counts[page] += 1
        region = row.get("image_region_px")
        if not isinstance(region, dict) or set(region) != {"x1", "y1", "x2", "y2"}:
            raise ValueError("invalid row region")
        x1, y1, x2, y2 = (region[k] for k in ("x1", "y1", "x2", "y2"))
        width, height = page_sizes[page]
        if any(type(v) is not int for v in (x1, y1, x2, y2)) or not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
            raise ValueError("row region outside page")
        status = row.get("review_status")
        if status == "unresolved":
            if not isinstance(row.get("unresolved_fields"), list) or not row["unresolved_fields"]:
                raise ValueError("unresolved row needs explicit fields")
            statuses["unresolved"] += 1
        elif status == "reviewed":
            if not isinstance(row.get("accepted_fields"), dict) or not row["accepted_fields"] or not row.get("reviewer"):
                raise ValueError("reviewed row needs accepted fields and reviewer")
            if row.get("review_method") != "source-image":
                raise ValueError("reviewed row requires source-image attribution")
            if not set(required_fields).issubset(row["accepted_fields"]):
                raise ValueError("reviewed row missing required fields")
            statuses["reviewed"] += 1
        else:
            raise ValueError("invalid review status")
    if parsed != statuses["reviewed"] or unresolved != statuses["unresolved"]:
        raise ValueError("row review states do not reconcile")
    for page in ledger["pages"]:
        if counts[page["page"]] != page["visual_printed_row_count"]:
            raise ValueError("page printed positions do not reconcile")
    return page_sizes


def build(pdf, ledger_path, output):
    pdf, ledger_path, output = map(Path, (pdf, ledger_path, output))
    if not pdf.is_file() or not ledger_path.is_file() or pdf.is_symlink() or ledger_path.is_symlink():
        raise ValueError("PDF and ledger must be regular files")
    if output.exists() or output.is_symlink():
        raise ValueError("output already exists")
    source_hash = digest(pdf)
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    sizes = validate_ledger(ledger, source_hash)
    page_ledger = {page["page"]: page for page in ledger["pages"]}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".cmas-scan-", dir=output.parent) as temporary:
        work = Path(temporary)
        rendered = work / "rendered"
        ocr = work / "ocr"
        rendered.mkdir()
        ocr.mkdir()
        render_args = ["pdftoppm", "-png", "-r", "300", str(pdf), str(rendered / "page")]
        run(*render_args)
        images = sorted(rendered.glob("page-*.png"), key=lambda p: int(p.stem.split("-")[-1]))
        if len(images) != len(sizes):
            raise ValueError("rendered page count mismatch")
        pages = []
        for number, image in enumerate(images, 1):
            if image.name != f"page-{number}.png" or png_dimensions(image) != sizes[number]:
                raise ValueError("rendered page identity or dimensions mismatch")
            image_hash = digest(image)
            expected_hash = page_ledger[number].get("render_sha256")
            if expected_hash and image_hash != expected_hash:
                raise ValueError("rendered image SHA-256 mismatch")
            tsv = ocr / f"page-{number}.tsv"
            run("tesseract", str(image), str(tsv.with_suffix("")), "--psm", "6", "tsv")
            if not tsv.is_file():
                raise ValueError("missing OCR locator TSV")
            pages.append({"page": number, "dimensions_px": sizes[number], "image": f"rendered/{image.name}",
                          "image_sha256": image_hash, "ocr_locator": f"ocr/{tsv.name}",
                          "ocr_sha256": digest(tsv), "printed_positions": page_ledger[number]["visual_printed_row_count"]})
        evidence = {"schema": SCHEMA, "source_sha256": source_hash, "ledger_sha256": digest(ledger_path),
                    "render": {"arguments": ["pdftoppm", "-png", "-r", "300"],
                               "version": run("pdftoppm", "-v").stderr.strip()},
                    "ocr": {"arguments": ["tesseract", "IMAGE", "OUTPUT", "--psm", "6", "tsv"],
                            "version": run("tesseract", "--version").stdout.splitlines()[0]},
                    "pages": pages, "totals": ledger["totals"],
                    "import_ready": ledger["totals"]["unresolved"] == 0,
                    "ocr_policy": "locator-only; accepted row values must come from reviewed ledger"}
        (work / "evidence.json").write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        work.rename(output)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdf")
    parser.add_argument("ledger")
    parser.add_argument("output")
    args = parser.parse_args()
    try:
        evidence = build(args.pdf, args.ledger, args.output)
    except (ValueError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        parser.exit(1, f"scan evidence: {error}\n")
    print(json.dumps({"output": args.output, "totals": evidence["totals"], "import_ready": evidence["import_ready"]}))


if __name__ == "__main__":
    main()
