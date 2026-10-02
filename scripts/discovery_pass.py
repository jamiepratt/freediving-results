"""Run one declared, bounded discovery pass over configured dated indexes.

This is a private evidence checkpoint, not a crawler or ingestion-completeness
claim. Every configured route is checked or a gap; every in-scope lead has a
disposition before the discovery pass can be called complete.
"""

import argparse
from hashlib import sha256
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from acquire_source import SourceRejected, _atomic_write, _private_dir, acquire
from discover_results import _candidates, _date, _public_url, _source_config
from discovery_frontier import DiscoveryFrontier
from discovery_refresh import plan_refresh, record_publisher_result
from source_acquisition import AcquisitionClient
from source_inventory import find_reusable_source, identity


SUPPORTED_FAMILIES = {"results", "rankings", "names"}


class _UndatedAnchors(HTMLParser):
    def __init__(self):
        super().__init__()
        self.count = 0

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            values = dict(attrs)
            if values.get("href") and not values.get("data-date"):
                self.count += 1


def _unexamined_links(source, body):
    if source["index_representation"] == "html":
        parser = _UndatedAnchors()
        parser.feed(body.decode("utf-8"))
        return parser.count
    document = json.loads(body)
    return sum(isinstance(item, dict) and item.get("url") and not item.get("date")
               and item.get("representation") == source["candidate_representation"]
               for item in document["results"])


def _save(path, data):
    _atomic_write(path, (json.dumps(data, sort_keys=True, indent=2) + "\n").encode())


def _load(path):
    return json.loads(path.read_text()) if path.exists() else {}


def _check_config(config):
    if config.get("schema") != "scoped-result-discovery/v1":
        raise ValueError("unsupported scoped discovery schema")
    cutoff, as_of = _date(config["cutoff"]), _date(config["as_of"])
    if cutoff > as_of or not isinstance(config.get("sources"), list) or not config["sources"]:
        raise ValueError("invalid discovery window or sources")
    if (not isinstance(config.get("max_links_per_index", 100), int) or
            config.get("max_links_per_index", 100) < 1):
        raise ValueError("max_links_per_index must be positive")
    scope = config["scope"]
    if scope.get("cutoff") != config["as_of"]:
        raise ValueError("scope cutoff must equal as_of")
    ids = set()
    for source in config["sources"]:
        _source_config(source)
        route = source.get("id")
        if not isinstance(route, str) or not route or route in ids or route not in scope["routes"]:
            raise ValueError("source route ID missing, duplicate or outside scope")
        ids.add(route)
        if not isinstance(source.get("year"), int) or isinstance(source["year"], bool):
            raise ValueError("route year must be an integer")
        if source["year"] not in scope["years"] or source.get("source_family") not in scope["source_families"]:
            raise ValueError("route year or family outside declared scope")
    if ids != set(scope["routes"]):
        raise ValueError("every declared route needs a configuration")
    return cutoff, as_of, scope


def _gap_evidence(error):
    evidence = {"reason": error.reason, "gap": Path(error.gap_path).name}
    try:
        receipt = json.loads(Path(error.gap_path).read_text())
        evidence.update({"status": receipt.get("status"), "checked_at": receipt.get("retrieved_at")})
    except (OSError, ValueError):
        pass
    return evidence


def _publisher_state(states, key, receipt, *, requested):
    if not requested or receipt["status"] == "reused":
        return
    record = _load(Path(receipt["provenance_path"]))
    states[key] = record_publisher_result(states.get(key), checked_at=record["retrieved_at"],
                                          status=record.get("status", 200), sha256=receipt["sha256"])


def run_pass(config_path, output_dir, *, request_budget=None, client=None):
    """Resume or start a configured pass; budget counts logical publisher fetches.

    A repeated call with the same scope resumes its checkpoint. Change the dated
    scope cutoff for a new pass; the cross-run refresh ledger remains in output.
    AcquisitionClient shares its SQLite lease across workers using this output.
    """
    config_bytes = Path(config_path).read_bytes()
    config = json.loads(config_bytes)
    cutoff, as_of, scope = _check_config(config)
    output = _private_dir(output_dir)
    archive = _private_dir(output / "archive")
    client = client or AcquisitionClient(lease_path=output / "leases.sqlite3")
    config_digest = sha256(config_bytes).hexdigest()
    frontier = DiscoveryFrontier.load(output / f"frontier-{config_digest}.json", scope, request_budget)
    state_path = output / "refresh-state.json"
    states = _load(state_path)
    policy = config.get("refresh_policy")
    if policy is not None:
        plan_refresh("index", as_of, policy=policy)  # validate before requests

    index_cache = {}
    for source in config["sources"]:
        route = source["id"]
        if frontier.routes[route]["status"] != "unchecked":
            continue
        if source["source_family"] not in SUPPORTED_FAMILIES:
            frontier.mark_route(route, status="gap", evidence={
                "reason": "unsupported_source_family", "family": source["source_family"]})
            continue
        url = source["index_url"]
        context = {**source["context"], "discovery_role": "index", "as_of": config["as_of"]}
        index_key = "index:" + identity(url, source["index_representation"], context)
        if index_key not in index_cache:
            prior_hashes = {item.get("evidence", {}).get("sha256")
                            for item in frontier.routes.values()
                            if item.get("evidence", {}).get("index_identity") == index_key}
            if prior_hashes:
                reusable_index = find_reusable_source(
                    [archive], url, source["index_representation"], context)
                if reusable_index and reusable_index["record"]["sha256"] in prior_hashes:
                    index_cache[index_key] = {
                        "source_path": str(reusable_index["source_path"]),
                        "provenance_path": str(reusable_index["provenance_path"]),
                        "sha256": reusable_index["record"]["sha256"], "status": "reused"}
        if index_key not in index_cache and not frontier.spend_request():
            break
        try:
            if index_key in index_cache:
                receipt = index_cache[index_key]
            else:
                receipt = acquire(url, source["index_representation"], archive, client=client,
                                  context=context, refresh=True)
                index_cache[index_key] = receipt
                _publisher_state(states, index_key, receipt, requested=True)
                _save(state_path, states)
            index_record = _load(Path(receipt["provenance_path"]))
            body = Path(receipt["source_path"]).read_bytes()
            links = _candidates(source, body)
            unexamined = _unexamined_links(source, body)
            if len(links) > config.get("max_links_per_index", 100):
                raise ValueError("index exceeds configured candidate bound")
            for link in links:
                selected = _date(link["date"])
                candidate_url = urljoin(index_record["final_url"], link["url"])
                if (not _public_url(candidate_url) or
                        urlsplit(candidate_url).hostname.lower() != source["allowed_host"].lower()):
                    raise ValueError("candidate URL is unsafe or outside publisher host")
                rules_url = link.get("rules_url")
                if rules_url is not None and not _public_url(urljoin(index_record["final_url"], rules_url)):
                    raise ValueError("unsafe rules reference")
                if not isinstance(link.get("provisional", False), bool):
                    raise ValueError("provisional flag must be Boolean")
                evidence = {"index_url": url, "index_sha256": receipt["sha256"],
                            "selected_date": selected.isoformat(), "locator": link.get("locator", "dated link")}
                metadata = {"representation": source["candidate_representation"],
                            "publisher": source["publisher"], "context": {
                                **source["context"], "selected_date": selected.isoformat()},
                            "publication_date": link.get("publication_date"),
                            "provisional": link.get("provisional", False),
                            "period": link.get("period"), "category": link.get("category"),
                            "rules_url": (urljoin(index_record["final_url"], rules_url)
                                          if rules_url is not None else None), "script": link.get("script")}
                key = frontier.add_lead(candidate_url, route=route, year=selected.year,
                                        source_family=source["source_family"],
                                        evidence=evidence, metadata=metadata)
                if not cutoff <= selected <= as_of or selected.year != source["year"]:
                    frontier.record_disposition(key, "out_of_scope", evidence={
                        "reason": "date outside configured route window", **evidence})
            route_evidence = {"index_url": url, "index_identity": index_key,
                              "sha256": receipt["sha256"],
                              "provenance": Path(receipt["provenance_path"]).name}
            if unexamined:
                frontier.mark_route(route, status="gap", evidence={
                    **route_evidence, "reason": "undated_links_not_evaluated",
                    "unexamined_links": unexamined})
            else:
                frontier.mark_route(route, evidence=route_evidence)
        except SourceRejected as error:
            gap = _gap_evidence(error)
            if isinstance(gap.get("status"), int) and gap["status"] != 304 and gap.get("checked_at"):
                states[index_key] = record_publisher_result(states.get(index_key),
                    checked_at=gap["checked_at"], status=gap["status"], gap_reason=gap["reason"])
                _save(state_path, states)
            frontier.mark_route(route, status="gap", evidence=gap)
        except (ValueError, UnicodeError, OSError):
            frontier.mark_route(route, status="gap", evidence={
                "reason": "index_parse_or_link_error", "index_url": url})

    for key, lead in list(frontier.leads.items()):
        if lead["disposition"] is not None:
            continue
        metadata = lead["metadata"]
        state_key = "source:" + identity(lead["url"], metadata["representation"], metadata["context"])
        previous = states.get(state_key)
        try:
            decision = plan_refresh("result", as_of,
                                    publication_date=metadata.get("publication_date"),
                                    provisional=metadata.get("provisional", False),
                                    last_publisher_check=(previous or {}).get("last_publisher_check"),
                                    policy=policy)
        except ValueError:
            frontier.record_disposition(key, "gap", evidence={"reason": "invalid_refresh_evidence"})
            continue
        if previous and previous.get("gap") and not decision["due"]:
            frontier.record_disposition(key, "gap", evidence={
                "reason": "prior_publisher_gap_not_due", "prior_gap": previous["gap"],
                "refresh_reason": decision["reason"]})
            continue
        reusable = None if decision["due"] else find_reusable_source(
            [archive], lead["url"], metadata["representation"], metadata["context"])
        publisher_request = decision["due"] or reusable is None
        if publisher_request and not frontier.spend_request():
            break
        try:
            receipt = acquire(lead["url"], metadata["representation"], archive,
                              client=client, context=metadata["context"],
                              refresh=decision["refresh"])
            _publisher_state(states, state_key, receipt, requested=publisher_request)
            _save(state_path, states)
            frontier.record_disposition(key, "acquired", evidence={
                "sha256": receipt["sha256"], "provenance": Path(receipt["provenance_path"]).name,
                "request": receipt["status"] != "reused", "refresh_reason": decision["reason"]})
        except SourceRejected as error:
            gap = _gap_evidence(error)
            if isinstance(gap.get("status"), int) and gap["status"] != 304 and gap.get("checked_at"):
                states[state_key] = record_publisher_result(states.get(state_key),
                    checked_at=gap["checked_at"], status=gap["status"], gap_reason=gap["reason"])
                _save(state_path, states)
            frontier.record_disposition(key, "gap", evidence=gap)

    result = frontier.summary()
    result.update({"schema": "scoped-result-discovery-summary/v1",
                   "publisher_checks": sum(bool(item.get("last_publisher_check")) for item in states.values()),
                   "source_versions": sum(len(item.get("versions", [])) for key, item in states.items()
                                          if key.startswith("source:")),
                   "cache_reuse": sum(item["disposition"]["evidence"].get("request") is False
                                      for item in frontier.leads.values()
                                      if item["disposition"] and item["disposition"]["status"] == "acquired")})
    _save(output / "summary.json", result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("private_output", type=Path)
    parser.add_argument("--request-budget", type=int,
                        help="Maximum logical publisher fetches in this invocation")
    args = parser.parse_args(argv)
    result = run_pass(args.config, args.private_output, request_budget=args.request_budget)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
