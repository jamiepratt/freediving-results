"""Verify private acquisition evidence before a new publisher request."""

from datetime import datetime
from hashlib import sha256
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import parse_qsl, urlsplit


_SENSITIVE = re.compile(r"(?:token|key|secret|password|session|auth|credential|signature|jwt)", re.I)


def _safe_url(url):
    if not isinstance(url, str):
        return False
    parts = urlsplit(url)
    return (parts.scheme in ("http", "https") and bool(parts.hostname) and
            not parts.username and not parts.password and
            not any(_SENSITIVE.search(key) for key, _ in parse_qsl(parts.query, keep_blank_values=True)) and
            not _SENSITIVE.search(parts.fragment))


def identity(url, representation, context):
    return sha256(json.dumps([url, representation, context], sort_keys=True).encode()).hexdigest()


def _regular(path):
    return path.is_file() and not path.is_symlink()


def verified_candidates(roots, url, representation, context):
    """Yield only exact, complete acquisition receipts with matching source bytes."""
    candidates = []
    for root in roots:
        root = Path(root)
        if not root.is_dir() or root.is_symlink():
            raise ValueError("configured archive root is unavailable or unsafe")
        for manifest in root.glob("acquisition-*.json"):
            if not _regular(manifest):
                continue
            try:
                record = json.loads(manifest.read_text())
                if record.get("requested_url") != url or record.get("representation") != representation:
                    continue
                if record.get("context", {}) != context:
                    continue
                if (record.get("status") != 200 or not record.get("content_type") or
                        not record.get("final_url") or not record.get("started_at") or
                        not record.get("retrieved_at") or not isinstance(record.get("byte_length"), int) or
                        record["byte_length"] < 1 or not isinstance(record.get("attempts"), int) or
                        record["attempts"] < 1):
                    continue
                started = datetime.fromisoformat(record["started_at"])
                retrieved = datetime.fromisoformat(record["retrieved_at"])
                if started.tzinfo is None or retrieved.tzinfo is None:
                    continue
                chain = record.get("redirect_chain")
                if (not isinstance(chain, list) or not chain or chain[0] != url or
                        chain[-1] != record["final_url"] or not all(_safe_url(item) for item in chain)):
                    continue
                digest = record.get("sha256", "")
                if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                    continue
                if record.get("schema") == "source-acquisition/v1":
                    run = root / f"run-{identity(url, representation, context)}.json"
                    if not _regular(run):
                        continue
                    checkpoint = json.loads(run.read_text())
                    if (checkpoint.get("schema") != "source-run/v1" or
                            checkpoint.get("source_status") != "complete" or
                            checkpoint.get("source_sha256") != digest or
                            checkpoint.get("acquisition") != manifest.name):
                        continue
                source = root / f"{digest}.{representation}"
                if not _regular(source):
                    continue
                body = source.read_bytes()
                if len(body) != record["byte_length"] or sha256(body).hexdigest() != digest:
                    continue
                candidates.append((record["retrieved_at"], source, manifest, record, body))
            except (OSError, ValueError, TypeError, KeyError, UnicodeError):
                continue
    return sorted(candidates, key=lambda candidate: candidate[0], reverse=True)


def legacy_archive_gaps(roots, url, representation, context):
    """Inventory verified Clojure archives; report records missing HTTP receipt facts."""
    count = 0
    for root in map(Path, roots):
        if not (root / "objects").is_dir() or not (root / "acquisitions").is_dir():
            continue
        command = ["clojure", "-M:archive", "inventory-json", str(root)]
        try:
            result = subprocess.run(command, cwd=Path(__file__).resolve().parents[1],
                                    capture_output=True, text=True, check=True)
            records = json.loads(result.stdout)
        except (OSError, subprocess.CalledProcessError, ValueError):
            raise ValueError("private archive inventory could not be verified") from None
        for record in records:
            if url not in (record["discovery-url"], record["final-url"]):
                continue
            mime = record["content-type"].split(";", 1)[0].lower()
            if representation == "pdf" and mime not in ("application/pdf", "application/octet-stream"):
                continue
            if representation == "json" and mime != "application/json" and not mime.endswith("+json"):
                continue
            if representation == "html" and mime not in ("text/html", "application/xhtml+xml"):
                continue
            selected = context.get("selected_date") or context.get("selected-date")
            if selected and record.get("selected-date") != selected:
                continue
            # The Clojure manifest has no observed HTTP status or byte length.
            # Its verified object is evidence, but cannot alone authorize reuse.
            count += 1
    return count


def find_reusable_source(roots, url, representation, context=None):
    """Return verified result bytes and original receipt for HTTP or browser routes.

    The caller must still decide whether the selected browser state matches the
    declared context and must report that publisher freshness was not checked.
    """
    context = context or {}
    for _, source, provenance, record, body in verified_candidates(roots, url, representation, context):
        mime = record["content_type"].split(";", 1)[0].lower()
        if representation == "pdf" and not (body.startswith(b"%PDF-") and mime in ("application/pdf", "application/octet-stream")):
            continue
        if representation == "json":
            if mime != "application/json" and not mime.endswith("+json"):
                continue
            try:
                json.loads(body)
            except (ValueError, UnicodeDecodeError):
                continue
        if representation == "html" and (mime not in ("text/html", "application/xhtml+xml") or
                                           b"<html" not in body[:65536].lower() and
                                           b"<!doctype html" not in body[:65536].lower()):
            continue
        return {"body": body, "source_path": source, "provenance_path": provenance,
                "record": record, "freshness": "not_checked"}
    return None
