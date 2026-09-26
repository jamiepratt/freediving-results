"""Fetch one official source through the shared paced acquisition client.

This records private evidence only. It does not register or ingest a source.
"""

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import parse_qsl, urlsplit
from uuid import uuid4

from source_acquisition import AcquisitionClient, AcquisitionError
from source_inventory import identity, legacy_archive_gaps, verified_candidates


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
    if path.is_symlink():
        raise ValueError("private output directory must not be a symlink")
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


def import_once(run_path, importer):
    """Checkpoint an idempotent archive/extraction/import callable after success.

    A crash after the importer commits but before the checkpoint can invoke it again.
    The importer must therefore use the existing immutable idempotent archive and
    observation APIs. It receives verified source and original provenance paths.
    """
    run_path = Path(run_path)
    if run_path.is_symlink() or not run_path.is_file():
        raise ValueError("source run must be a regular file")
    lock_path = run_path.parent / f".{run_path.name}.lock"
    descriptor = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        run = json.loads(run_path.read_text())
        if run.get("schema") != "source-run/v1" or run.get("source_status") != "complete":
            raise ValueError("source run is incomplete")
        provenance_path = run_path.parent / run["acquisition"]
        provenance = json.loads(provenance_path.read_text())
        if (run.get("identity") != identity(provenance["requested_url"],
                                            provenance["representation"], provenance.get("context", {})) or
                run_path.name != f"run-{run['identity']}.json"):
            raise ValueError("source run identity does not match provenance")
        candidates = verified_candidates([run_path.parent], provenance["requested_url"],
                                         provenance["representation"], provenance.get("context", {}))
        matching = [item for item in candidates if item[2] == provenance_path and
                    item[1].name == run["source"] and item[3]["sha256"] == run["source_sha256"]]
        if not matching:
            raise ValueError("source run evidence is not verified")
        if run.get("import_status") == "complete":
            return run["import_ref"]
        import_ref = importer(matching[0][1], provenance_path)
        if not isinstance(import_ref, str) or not import_ref:
            raise ValueError("importer must return a nonempty immutable job reference")
        run.update(import_status="complete", import_ref=import_ref,
                   imported_at=datetime.now(timezone.utc).isoformat())
        _json_write(run_path, run)
        return import_ref


def acquire(url, representation, output_dir, *, client=None, dry_run=False,
            archive_roots=(), context=None, refresh=False):
    """Return a private receipt or dry-run policy; raise SourceRejected with gap path."""
    if representation not in _REPRESENTATIONS:
        raise ValueError("representation must be pdf, json or html")
    host = _safe_url(url)
    context = context or {}
    if not isinstance(context, dict) or any(not isinstance(key, str) for key in context):
        raise ValueError("context must be a JSON object with string keys")
    context_key = identity(url, representation, context)
    client = client or AcquisitionClient()
    policy = client.policy_for(host)
    if dry_run:
        return {"host": host, "representation": representation, "policy": vars(policy), "dry_run": True}

    directory = _private_dir(output_dir)
    roots = [directory, *archive_roots]
    legacy_gaps = legacy_archive_gaps(roots, url, representation, context)
    if not refresh:
        for _, source, provenance, record, body in verified_candidates(roots, url, representation, context):
            # Archive bytes can be hash-valid while representing an HTML challenge or wrong MIME.
            from source_acquisition import Result
            result = Result(body, 200, record["final_url"], (), record["content_type"],
                            record["attempts"], ())
            if _validate(result, representation):
                continue
            source_path = directory / source.name
            provenance_path = directory / provenance.name
            if source_path.exists() and (source_path.is_symlink() or
                                         hashlib.sha256(source_path.read_bytes()).hexdigest() != record["sha256"]):
                raise RuntimeError("local source object differs from verified archive bytes")
            if not source_path.exists():
                _atomic_write(source_path, body)
            if provenance_path.exists() and (provenance_path.is_symlink() or
                                             provenance_path.read_bytes() != provenance.read_bytes()):
                raise RuntimeError("local provenance conflicts with verified archive")
            if not provenance_path.exists():
                _atomic_write(provenance_path, provenance.read_bytes())
            run_path = directory / f"run-{context_key}.json"
            run = {"schema": "source-run/v1", "identity": context_key, "source_sha256": record["sha256"],
                   "acquisition": provenance_path.name, "source": source_path.name,
                   "source_status": "complete", "import_status": "pending",
                   "freshness": "not_checked", "reused_from": str(provenance)}
            if run_path.exists():
                existing = json.loads(run_path.read_text())
                if existing.get("source_sha256") == record["sha256"] and existing.get("acquisition") == provenance_path.name:
                    run = existing
            _json_write(run_path, run)
            return {"source_path": str(source_path), "provenance_path": str(provenance_path),
                    "run_path": str(run_path), "sha256": record["sha256"],
                    "status": "reused", "freshness": run["freshness"],
                    "legacy_archive_incomplete": legacy_gaps}
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
        events = [vars(event) for event in error.events] if isinstance(error, AcquisitionError) else []
        gap = directory / f"gap-{uuid4().hex}.json"
        _json_write(gap, {"host": host, "representation": representation,
                          "source_identity": context_key, "started_at": started_at,
                          "retrieved_at": retrieved_at, "elapsed_seconds": time.monotonic() - started_clock,
                          "status": status, "reason": reason, "events": events,
                          "legacy_archive_incomplete": legacy_gaps})
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
        "schema": "source-acquisition/v1", "context": context,
        "requested_url": url, "final_url": result.final_url,
        "redirect_chain": [url, *result.redirects], "started_at": started_at,
        "retrieved_at": retrieved_at, "elapsed_seconds": elapsed_seconds,
        "status": result.status, "content_type": result.content_type,
        "byte_length": len(result.body), "sha256": digest,
        "representation": representation, "attempts": result.attempts,
        "events": [vars(event) for event in result.events],
    })
    run_path = directory / f"run-{context_key}.json"
    _json_write(run_path, {"schema": "source-run/v1", "identity": context_key,
                           "source_sha256": digest, "acquisition": provenance_path.name,
                           "source": source_path.name, "source_status": "complete",
                           "import_status": "pending",
                           "freshness": "refresh_requested" if refresh else "not_checked"})
    return {"source_path": str(source_path), "provenance_path": str(provenance_path),
            "run_path": str(run_path), "sha256": digest, "status": "acquired",
            "freshness": "refresh_requested" if refresh else "not_checked",
            "legacy_archive_incomplete": legacy_gaps}


def main(argv=None):
    argv = list(argv) if argv is not None else __import__("sys").argv[1:]
    if argv and argv[0] == "import-once":
        command_parser = argparse.ArgumentParser(description="Run an idempotent importer and checkpoint success")
        command_parser.add_argument("run_path", type=Path)
        command_parser.add_argument("job_ref")
        command_parser.add_argument("command", nargs=argparse.REMAINDER)
        command_args = command_parser.parse_args(argv[1:])
        command = command_args.command
        if command and command[0] == "--":
            command = command[1:]
        if not command:
            command_parser.error("an import command is required after --")

        def invoke(source, provenance):
            expanded = [part.replace("{source}", str(source)).replace("{provenance}", str(provenance))
                        for part in command]
            subprocess.run(expanded, check=True)
            return command_args.job_ref

        job_ref = import_once(command_args.run_path, invoke)
        print(json.dumps({"status": "import_complete", "job_ref": job_ref}, sort_keys=True))
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("representation", choices=sorted(_REPRESENTATIONS))
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--lease-path", type=Path, help="Shared private SQLite lease path")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--archive-root", action="append", type=Path, default=[],
                        help="Existing private local or mounted remote acquisition root")
    parser.add_argument("--context-json", default="{}", help="Exact selected date/filter/version JSON object")
    parser.add_argument("--refresh", action="store_true", help="Request publisher even when verified bytes exist")
    args = parser.parse_args(argv)
    try:
        client = AcquisitionClient(lease_path=args.lease_path) if args.lease_path and not args.dry_run else None
        receipt = acquire(args.url, args.representation, args.output_dir, dry_run=args.dry_run,
                          client=client,
                          archive_roots=args.archive_root, context=json.loads(args.context_json),
                          refresh=args.refresh)
    except SourceRejected as error:
        print(json.dumps({"status": "gap", "reason": error.reason, "gap_path": error.gap_path}))
        return 2
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
