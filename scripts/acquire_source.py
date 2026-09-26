"""Fetch one official source through the shared paced acquisition client.

This records private evidence only. It does not register or ingest a source.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.parse import parse_qsl, urlsplit
from uuid import uuid4

from source_acquisition import AcquisitionClient, AcquisitionError


_REPRESENTATIONS = {"pdf", "json", "html"}
_CHALLENGE = re.compile(
    rb"(?:captcha|cloudflare|verify you are human|checking your browser|"
    rb"access denied|bot detection|unusual traffic|attention required|"
    rb"security challenge|enable javascript and cookies)", re.I)
_SENSITIVE = re.compile(r"(?:token|key|secret|password|session|auth|credential|signature|jwt)", re.I)


class SourceRejected(Exception):
    def __init__(self, reason, gap_path):
        self.reason = reason
        self.gap_path = str(gap_path)
        super().__init__(f"source rejected: {reason}; gap: {gap_path}")


def _safe_url(url):
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        raise ValueError("source URL must be HTTP(S) without credentials")
    if any(_SENSITIVE.search(key) for key, _ in parse_qsl(parts.query, keep_blank_values=True)):
        raise ValueError("source URL has a sensitive query parameter")
    if _SENSITIVE.search(parts.fragment):
        raise ValueError("source URL has a sensitive fragment")
    return parts.hostname.lower()


def _private_dir(path):
    path = Path(path)
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path, 0o700)
    return path


def _atomic_write(path, data):
    descriptor, temporary = tempfile.mkstemp(prefix=".pending-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _json_write(path, value):
    _atomic_write(path, (json.dumps(value, sort_keys=True, indent=2) + "\n").encode())


def _validate(result, representation):
    body = result.body
    if _CHALLENGE.search(body[:65536]):
        return "access_challenge"
    mime = (result.content_type or "").split(";", 1)[0].strip().lower()
    if representation == "pdf":
        if not body.startswith(b"%PDF-"):
            return "invalid_pdf_signature"
        if mime not in ("application/pdf", "application/octet-stream"):
            return "wrong_content_type"
    elif representation == "json":
        if mime != "application/json" and not mime.endswith("+json"):
            return "wrong_content_type"
        try:
            json.loads(body)
        except (ValueError, UnicodeDecodeError):
            return "invalid_json"
    elif representation == "html":
        if mime not in ("text/html", "application/xhtml+xml"):
            return "wrong_content_type"
        if not re.search(rb"<(?:!doctype\s+html|html|head|body)\b", body[:65536], re.I):
            return "invalid_html"
    return None


def acquire(url, representation, output_dir, *, client=None, dry_run=False):
    """Return a private receipt or dry-run policy; raise SourceRejected with gap path."""
    if representation not in _REPRESENTATIONS:
        raise ValueError("representation must be pdf, json or html")
    host = _safe_url(url)
    client = client or AcquisitionClient()
    policy = client.policy_for(host)
    if dry_run:
        return {"host": host, "representation": representation, "policy": vars(policy), "dry_run": True}

    directory = _private_dir(output_dir)
    started_at = datetime.now(timezone.utc).isoformat()
    started_clock = time.monotonic()
    try:
        result = client.fetch(url, url_validator=_safe_url)
        # Redirects are checked before retaining them or source bytes.
        for target in result.redirects:
            _safe_url(target)
        _safe_url(result.final_url)
        reason = _validate(result, representation)
        if reason:
            raise SourceRejected(reason, "")
    except (AcquisitionError, SourceRejected, ValueError) as error:
        retrieved_at = datetime.now(timezone.utc).isoformat()
        reason = error.reason if isinstance(error, (AcquisitionError, SourceRejected)) else "unsafe_redirect"
        status = error.status if isinstance(error, AcquisitionError) else (result.status if "result" in locals() else None)
        gap = directory / f"gap-{uuid4().hex}.json"
        _json_write(gap, {"host": host, "representation": representation, "started_at": started_at,
                          "retrieved_at": retrieved_at, "elapsed_seconds": time.monotonic() - started_clock,
                          "status": status, "reason": reason})
        raise SourceRejected(reason, gap) from None

    retrieved_at = datetime.now(timezone.utc).isoformat()
    elapsed_seconds = time.monotonic() - started_clock
    digest = hashlib.sha256(result.body).hexdigest()
    source_path = directory / f"{digest}.{representation}"
    if source_path.exists():
        if hashlib.sha256(source_path.read_bytes()).hexdigest() != digest:
            raise RuntimeError("existing source object differs from its hash")
    else:
        _atomic_write(source_path, result.body)
    provenance_path = directory / f"acquisition-{uuid4().hex}.json"
    _json_write(provenance_path, {
        "requested_url": url, "final_url": result.final_url,
        "redirect_chain": [url, *result.redirects], "started_at": started_at,
        "retrieved_at": retrieved_at, "elapsed_seconds": elapsed_seconds,
        "status": result.status, "content_type": result.content_type,
        "byte_length": len(result.body), "sha256": digest,
        "representation": representation, "attempts": result.attempts,
        "events": [vars(event) for event in result.events],
    })
    return {"source_path": str(source_path), "provenance_path": str(provenance_path),
            "sha256": digest, "status": result.status}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("representation", choices=sorted(_REPRESENTATIONS))
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        receipt = acquire(args.url, args.representation, args.output_dir, dry_run=args.dry_run)
    except SourceRejected as error:
        print(json.dumps({"status": "gap", "reason": error.reason, "gap_path": error.gap_path}))
        return 2
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
