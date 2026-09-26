"""Private, paced Playwright capture. No observation import or publication."""

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import stat
import sys

from browser_acquisition import BrowserAcquisitionError, _validate_selection, capture_page
from source_acquisition import AcquisitionClient, CMAS_POLICY, Policy


def _write_private(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)


def _json(path, value):
    _write_private(path, (json.dumps(value, sort_keys=True, indent=2) + "\n").encode())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", help="Official HTTP(S) source page")
    parser.add_argument("output", type=Path, help="New private capture directory")
    parser.add_argument("--lease-path", type=Path, help="Shared private SQLite lease path")
    parser.add_argument("--channel", help="Installed Playwright browser channel, such as chrome")
    parser.add_argument("--archive-root", action="append", type=Path, default=[],
                        help="Existing private local or mounted remote acquisition root")
    parser.add_argument("--context-json", default="{}", help="Exact selected date/filter/version JSON object")
    parser.add_argument("--selection-file", type=Path, help="Versioned date/filter selection JSON")
    parser.add_argument("--storage-state", type=Path, help="Private Playwright storage state (0600)")
    parser.add_argument("--refresh", action="store_true", help="Request publisher even when verified bytes exist")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    from urllib.parse import urlsplit
    parts = urlsplit(args.url)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        parser.error("source URL must be HTTP(S) without credentials")
    host = parts.hostname.lower()
    selection = None
    if args.selection_file:
        try:
            selection = json.loads(args.selection_file.read_text())
            _validate_selection(selection)
        except (OSError, ValueError):
            parser.error("invalid selection file")
    if args.storage_state:
        try:
            info = args.storage_state.lstat()
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or
                    info.st_mode & 0o077 or not info.st_mode & stat.S_IRUSR):
                raise ValueError
        except (OSError, ValueError):
            parser.error("storage state must be a private regular file readable only by its owner")
    if args.dry_run:
        policy = CMAS_POLICY if host == "cmas.org" or host.endswith(".cmas.org") else Policy()
        print(json.dumps({"host": host, "policy": asdict(policy),
                          "selected_date": selection["selected_date"] if selection else None}, sort_keys=True))
        return 0
    client = AcquisitionClient(lease_path=args.lease_path)
    try:
        source_context = json.loads(args.context_json)
        if not isinstance(source_context, dict):
            raise ValueError
    except ValueError:
        parser.error("context must be a JSON object")
    if selection:
        if source_context.get("selected_date") not in (None, selection["selected_date"]):
            parser.error("context selected_date conflicts with selection file")
        source_context["selected_date"] = selection["selected_date"]
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        parser.error("Playwright is required for browser capture")
    args.output.mkdir(mode=0o700)
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True, channel=args.channel)
            try:
                context = browser.new_context(accept_downloads=True, service_workers="block",
                                              **({"storage_state": str(args.storage_state)} if args.storage_state else {}))
                page = context.new_page()
                capture = capture_page(page, args.url, client, archive_roots=args.archive_root,
                                       source_context=source_context, refresh=args.refresh,
                                       selection=selection)
            finally:
                browser.close()
        dom_bytes = capture.dom.encode("utf-8")
        dom_hash = sha256(dom_bytes).hexdigest()
        _write_private(args.output / f"dom-{dom_hash}.html", dom_bytes)
        records = []
        for response in capture.responses:
            name = f"response-{response.sha256}.bin"
            target = args.output / name
            if not target.exists():
                _write_private(target, response.body)
            records.append({"host": response.host, "status": response.status,
                            "url": response.url,
                            "content_type": response.content_type, "sha256": response.sha256,
                            "bytes": len(response.body), "file": name})
        _json(args.output / "capture.json", {"schema": "browser-capture/v1", "host": host,
              "entry_url": capture.entry_url,
              "final_url": capture.final_url,
              "retrieved_at": datetime.now(timezone.utc).isoformat(), "dom_sha256": dom_hash,
              "redirects": [{"from": source, "to": target, "status": status}
                            for source, target, status in capture.redirects],
              "context": source_context, "reuses": capture.reuses,
              "selected_state": capture.selected_state,
              "events": capture.events,
              "responses": records})
        print(json.dumps({"status": "captured", "host": host, "responses": len(records),
                          "dom_sha256": dom_hash}, sort_keys=True))
        return 0
    except BrowserAcquisitionError as error:
        _json(args.output / "coverage-gap.json", {"schema": "source-gap/v1", "host": error.host,
              "reason": error.reason, "status": error.status,
              "events": error.events,
              "retrieved_at": datetime.now(timezone.utc).isoformat()})
        print(json.dumps({"status": "coverage_gap", "host": error.host,
                          "reason": error.reason}, sort_keys=True))
        return 2
    except Exception:
        _json(args.output / "coverage-gap.json", {"schema": "source-gap/v1", "host": host,
              "reason": "browser_error", "status": None,
              "retrieved_at": datetime.now(timezone.utc).isoformat()})
        print(json.dumps({"status": "coverage_gap", "host": host,
                          "reason": "browser_error"}, sort_keys=True))
        return 2


if __name__ == "__main__":
    sys.exit(main())
