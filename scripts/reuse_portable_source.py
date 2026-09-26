#!/usr/bin/env python3
"""Export one verified original PDF and acquisition provenance from a portable corpus.

Usage: reuse_portable_source.py BUNDLE SOURCE_SHA256 ACQUISITION_ID PRIVATE_EXPORT_DIR
"""

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import portable_corpus


STRING = r'"(?:\\.|[^"\\])*"'
SENSITIVE_VALUE = re.compile(r"(?i)\b(?:token|password|secret|cookie|authorization|api[-_]?key|session[-_]?id)\s*[:=]|\bbearer\s+\S+")


def field(manifest, key):
    matches = re.findall(r":" + re.escape(key) + r"\s+(" + STRING + r")", manifest)
    if len(matches) != 1:
        raise ValueError("missing or ambiguous acquisition field: " + key)
    return json.loads(matches[0])


def redirect_chain(manifest):
    matches = re.findall(r":redirect-chain\s+\[([^\]]*)\]", manifest, re.DOTALL)
    if len(matches) != 1:
        raise ValueError("missing or ambiguous redirect chain")
    values = re.findall(STRING, matches[0])
    if not values or re.sub(STRING, "", matches[0]).strip():
        raise ValueError("invalid redirect chain")
    return [json.loads(value) for value in values]


def export(bundle, sha, acquisition_id, destination):
    if not portable_corpus.SHA.fullmatch(sha) or not portable_corpus.SHA.fullmatch(acquisition_id):
        raise ValueError("invalid source or acquisition ID")
    bundle = portable_corpus.safe_existing(bundle)
    index = portable_corpus.verify(bundle)
    archive = portable_corpus.safe_existing(bundle / "payload/archive")
    object_name = "archive/objects/" + sha
    record_name = "archive/acquisitions/" + acquisition_id + ".edn"
    if object_name not in index["files"] or record_name not in index["files"]:
        raise ValueError("source object or acquisition record absent from bundle")
    source = portable_corpus.safe_existing(bundle / "payload" / object_name)
    record = portable_corpus.safe_existing(bundle / "payload" / record_name)
    with source.open("rb") as stream:
        signature = stream.read(5)
    if signature != b"%PDF-":
        raise ValueError("source is not a PDF")

    # Archive inventory validates canonical acquisition IDs, manifest schema,
    # acquisition-object relationships, and every acquisition in this archive.
    command = subprocess.run(["clojure", "-M:archive", "inventory-json", str(archive)],
                             cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True)
    if command.returncode:
        raise ValueError("archive acquisition validation failed")
    acquisitions = json.loads(command.stdout)
    selected = [a for a in acquisitions if a["acquisition-id"] == acquisition_id and a["sha256"] == sha]
    if len(selected) != 1:
        raise ValueError("acquisition does not identify expected source")

    manifest = record.read_text()
    if SENSITIVE_VALUE.search(manifest):
        raise ValueError("acquisition record contains sensitive value")
    if field(manifest, "sha256") != sha or field(manifest, "content-type") != "application/pdf":
        raise ValueError("acquisition source hash or MIME mismatch")
    method = field(manifest, "acquisition-method")
    retrieved_at = field(manifest, "retrieved-at")
    discovery_url = field(manifest, "discovery-url")
    final_url = field(manifest, "final-url")
    publisher = field(manifest, "publisher")
    publisher_url = field(manifest, "publisher-url")
    redirects = redirect_chain(manifest)
    if redirects[-1] != final_url:
        raise ValueError("redirect chain does not end at final URL")
    # These values must match the separately validated archive inventory.
    if any(selected[0][key] != value for key, value in
           (("retrieved-at", retrieved_at), ("discovery-url", discovery_url),
            ("final-url", final_url), ("publisher", publisher))):
        raise ValueError("acquisition metadata mismatch")

    destination = Path(destination).absolute()
    portable_corpus.safe_existing(destination.parent)
    if destination.exists() or destination.is_symlink():
        raise ValueError("export destination already exists")
    provenance = {
        "schema": "portable-source-reuse/v1",
        "transfer_kind": "reuse-of-original-source",
        "source_sha256": sha,
        "source_bytes": index["files"][object_name]["bytes"],
        "original_acquisition_id": acquisition_id,
        "bundle_index_sha256": portable_corpus.hash_file(bundle / "index.json")[0],
        "original_retrieval": {
            "method": method, "at": retrieved_at, "discovery_url": discovery_url,
            "final_url": final_url, "publisher": publisher,
            "publisher_url": publisher_url, "redirect_chain": redirects,
            "http_evidence_complete": False,
            "http_status": None,
        },
    }
    try:
        destination.mkdir(mode=0o700)
        shutil.copyfile(source, destination / "source.pdf")
        (destination / "source.pdf").chmod(0o600)
        shutil.copyfile(record, destination / "manifest.edn")
        (destination / "manifest.edn").chmod(0o600)
        portable_corpus.write_json(destination / "provenance.json", provenance)
        if portable_corpus.hash_file(destination / "source.pdf")[0] != sha:
            raise ValueError("export copy mismatch")
    except Exception:
        shutil.rmtree(destination)
        raise
    return provenance


def main():
    if len(sys.argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        provenance = export(*sys.argv[1:])
        print(json.dumps({"source_sha256": provenance["source_sha256"],
                          "original_acquisition_id": provenance["original_acquisition_id"],
                          "bundle_index_sha256": provenance["bundle_index_sha256"]}))
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print("portable source reuse: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
