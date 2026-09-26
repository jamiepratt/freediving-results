"""Bounded, date-filtered discovery from explicitly configured publisher indexes.

Only links carrying an explicit ISO date are candidates. This inventories the
configured pages, not all competitions worldwide. Output is private evidence.
"""

import argparse
from datetime import date
from hashlib import sha256
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import parse_qsl, urljoin, urlsplit

from acquire_source import SourceRejected, _atomic_write, _private_dir, acquire
from source_acquisition import AcquisitionClient
from source_inventory import _safe_url


_CREDENTIAL_KEY = re.compile(
    r"bearer|cookie|token|password|secret|credential|authorization|api[_-]?key|session[_-]?(?:id|key)", re.I)
_CREDENTIAL_VALUE = re.compile(
    r"bearer|cookie|token|password|secret|credential|authorization|api[_-]?key|session\s*[:=]", re.I)


def _public_url(url):
    if not _safe_url(url):
        return False
    return not any(_CREDENTIAL_VALUE.search(value) for _, value in
                   parse_qsl(urlsplit(url).query, keep_blank_values=True))


def _public_context(value):
    if isinstance(value, dict):
        return all(isinstance(key, str) and not _CREDENTIAL_KEY.search(key) and _public_context(item)
                   for key, item in value.items())
    if isinstance(value, list):
        return all(_public_context(item) for item in value)
    if isinstance(value, str):
        return not _CREDENTIAL_VALUE.search(value)
    return value is None or isinstance(value, (bool, int, float))


class _DatedLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            values = dict(attrs)
            if values.get("href") and values.get("data-date"):
                self.links.append({"url": values["href"], "date": values["data-date"]})


def _date(value):
    if not isinstance(value, str):
        raise ValueError("dates must be ISO strings")
    parsed = date.fromisoformat(value)
    if parsed.isoformat() != value:
        raise ValueError("dates must be YYYY-MM-DD")
    return parsed


def _json(path, value):
    _atomic_write(path, (json.dumps(value, sort_keys=True, indent=2) + "\n").encode())


def _source_config(source):
    if not isinstance(source, dict):
        raise ValueError("source must be an object")
    required = {"publisher", "index_url", "index_representation", "candidate_representation", "allowed_host", "context"}
    if not required <= source.keys() or not isinstance(source["context"], dict):
        raise ValueError("source is missing required fields")
    if source["index_representation"] not in ("html", "json") or source["candidate_representation"] not in ("pdf", "html", "json"):
        raise ValueError("unsupported representation")
    if not _public_context(source["context"]):
        raise ValueError("source context may contain credentials")
    if not _public_url(source["index_url"]):
        raise ValueError("unsafe index URL")
    if urlsplit(source["index_url"]).hostname.lower() != source["allowed_host"].lower():
        raise ValueError("index host differs from allowed host")
    if not isinstance(source["publisher"], str) or not source["publisher"]:
        raise ValueError("publisher is required")


def _candidates(source, body):
    if source["index_representation"] == "html":
        parser = _DatedLinks()
        parser.feed(body.decode("utf-8"))
        return parser.links
    document = json.loads(body)
    if not isinstance(document, dict) or not isinstance(document.get("results"), list):
        raise ValueError("JSON index needs a results array")
    return [{"url": item["url"], "date": item["date"]} for item in document["results"]
            if isinstance(item, dict) and item.get("representation") == source["candidate_representation"]
            and "url" in item and "date" in item]


def discover(config_path, output_dir, *, client=None, retry_gaps=False):
    config_bytes = Path(config_path).read_bytes()
    config = json.loads(config_bytes)
    if config.get("schema") != "result-discovery-config/v1" or not isinstance(config.get("sources"), list):
        raise ValueError("invalid discovery config")
    cutoff, as_of = _date(config["cutoff"]), _date(config["as_of"])
    if cutoff > as_of or not config["sources"]:
        raise ValueError("invalid discovery window or empty sources")
    for source in config["sources"]:
        _source_config(source)

    output = _private_dir(output_dir)
    archive = _private_dir(output / "archive")
    client = client or AcquisitionClient(lease_path=output / "leases.sqlite3")
    inventory_path = output / "inventory.json"
    digest = sha256(config_bytes).hexdigest()
    previous = json.loads(inventory_path.read_text()) if inventory_path.exists() else {}
    old_gaps = {item["key"]: item for item in previous.get("candidates", [])
                if previous.get("config_sha256") == digest and item.get("status") == "gap"}
    old_index_gaps = {(item["url"], item["publisher"]): item for item in previous.get("indexes", [])
                      if previous.get("config_sha256") == digest and item.get("status") == "gap"}
    inventory = {"schema": "result-discovery-inventory/v1", "config_sha256": digest,
                 "cutoff": cutoff.isoformat(), "as_of": as_of.isoformat(), "indexes": [], "candidates": []}
    seen = set()

    for source in config["sources"]:
        index_url = source["index_url"]
        old_index = old_index_gaps.get((index_url, source["publisher"]))
        if old_index and not retry_gaps:
            inventory["indexes"].append(old_index)
            _json(inventory_path, inventory)
            continue
        index_context = {**source["context"], "discovery_role": "index", "as_of": as_of.isoformat()}
        try:
            index = acquire(index_url, source["index_representation"], archive,
                            client=client, context=index_context)
        except SourceRejected as error:
            inventory["indexes"].append({"url": index_url, "publisher": source["publisher"],
                                         "status": "gap", "reason": error.reason,
                                         "gap": Path(error.gap_path).name})
            _json(inventory_path, inventory)
            continue
        index_record = json.loads(Path(index["provenance_path"]).read_text())
        inventory["indexes"].append({"url": index_url, "publisher": source["publisher"],
                                     "status": "complete", "sha256": index["sha256"],
                                     "final_url": index_record["final_url"],
                                     "redirect_chain": index_record["redirect_chain"],
                                     "provenance": Path(index["provenance_path"]).name})
        links = _candidates(source, Path(index["source_path"]).read_bytes())
        if len(links) > config.get("max_links_per_index", 100):
            raise ValueError("index exceeds configured candidate bound")
        for link in links:
            selected = _date(link["date"])
            if not cutoff <= selected <= as_of:
                continue
            url = urljoin(index_record["final_url"], link["url"])
            if not _public_url(url) or urlsplit(url).hostname.lower() != source["allowed_host"].lower():
                raise ValueError("candidate URL is unsafe or outside publisher host")
            representation = source["candidate_representation"]
            context = {**source["context"], "selected_date": selected.isoformat(),
                       "discovery_page": index_url, "discovery_sha256": index["sha256"]}
            key = sha256(json.dumps([url, representation, context], sort_keys=True).encode()).hexdigest()
            if key in seen:
                continue
            seen.add(key)
            if key in old_gaps and not retry_gaps:
                inventory["candidates"].append(old_gaps[key])
                continue
            item = {"key": key, "publisher": source["publisher"], "index_url": index_url,
                    "url": url, "selected_date": selected.isoformat(), "representation": representation,
                    "context": context}
            try:
                receipt = acquire(url, representation, archive, client=client, context=context)
                record = json.loads(Path(receipt["provenance_path"]).read_text())
                item.update(status="complete", sha256=receipt["sha256"],
                            final_url=record["final_url"], redirect_chain=record["redirect_chain"],
                            provenance=Path(receipt["provenance_path"]).name,
                            run=Path(receipt["run_path"]).name)
            except SourceRejected as error:
                item.update(status="gap", reason=error.reason, gap=Path(error.gap_path).name)
            inventory["candidates"].append(item)
            _json(inventory_path, inventory)
    _json(inventory_path, inventory)
    return inventory


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("private_output", type=Path)
    parser.add_argument("--retry-gaps", action="store_true",
                        help="Retry checkpointed publisher failures explicitly")
    args = parser.parse_args(argv)
    result = discover(args.config, args.private_output, retry_gaps=args.retry_gaps)
    print(json.dumps({"indexes": len(result["indexes"]), "candidates": len(result["candidates"]),
                      "gaps": sum(item["status"] == "gap" for item in result["candidates"])}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
