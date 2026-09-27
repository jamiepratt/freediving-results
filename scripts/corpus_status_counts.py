"""Count explicit, bounded source-inventory evidence without inferring attempts.

Input is JSON with schema ``corpus-status-counts/v1``, a human-readable scope,
and groups of link observations. Every group needs a unique group_id, an ISO
8601 cutoff with timezone, and links. Every link needs a link_id, status and
reason. event_ids and source_sha256 are optional and stay unknown when absent.
An optional event_cards array records calendar cards separately from PDF links;
one result link may serve several cards and cards may have no result link.
An optional numeric row_count requires row_count_kind and row_basis_id. Null
or absent row_count means unknown, never zero. A basis names
one disjoint set of cited source positions (or one corpus observation snapshot)
and can repeat across groups only with the same kind and count. The preparer
must establish that different bases do not overlap. Link records are counted
per group; repeating one link in another group records another observation.
An optional corpus_snapshot holds explicitly measured jobs, source_hashes and
observation_versions. No value here estimates unique sporting attempts.
"""

import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re
import sys


STATUSES = {"imported", "already_present", "unsupported", "unresolved", "inaccessible"}
ROW_KINDS = {"printed_positions", "imported_positions", "unresolved_positions",
             "unparsed_positions", "observation_versions"}
HASH = re.compile(r"[0-9a-f]{64}\Z")


def _object(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def _string(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")
    return value


def _cutoff(value, label):
    _string(value, label)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError(f"{label} must be ISO 8601") from None
    if parsed.tzinfo is None:
        raise ValueError(f"{label} needs a timezone")
    return value


def _count(value, label):
    if type(value) is not int or value < 0:
        raise ValueError(f"{label} must be a nonnegative integer")
    return value


def _summarize(links, cards):
    statuses = Counter(link["status"] for link in links)
    events = {event_id for link in links for event_id in link.get("event_ids", [])}
    events.update(card["event_id"] for card in cards)
    events_by_status = {}
    for status in sorted(statuses):
        events_by_status[status] = len({event_id for link in links if link["status"] == status
                                        for event_id in link.get("event_ids", [])})
    card_statuses = Counter(card["status"] for card in cards)
    hashes = {link["source_sha256"] for link in links if link.get("source_sha256")}
    bases = {}
    for link in links:
        if link.get("row_count") is None:
            continue
        key = (link["row_count_kind"], link["row_basis_id"])
        count = link["row_count"]
        if key in bases and bases[key] != count:
            raise ValueError("conflicting row basis counts")
        bases[key] = count
    rows = Counter()
    for (kind, _), count in bases.items():
        rows[kind] += count
    return {
        "link_count": len(links),
        "distinct_link_id_count": len({link["link_id"] for link in links}),
        "links_by_status": dict(sorted(statuses.items())),
        "known_event_count": len(events),
        "unknown_event_link_count": sum(not link.get("event_ids") for link in links),
        "events_by_link_status": events_by_status,
        "distinct_event_card_count": len({card["event_id"] for card in cards}),
        "event_cards_by_status": dict(sorted(card_statuses.items())),
        "distinct_source_hash_count": len(hashes),
        "unknown_source_hash_link_count": sum(not link.get("source_sha256") for link in links),
        "unknown_row_count_link_count": sum(link.get("row_count") is None for link in links),
        "rows_by_kind": dict(sorted(rows.items())),
    }


def report(document):
    document = _object(document, "input")
    if document.get("schema") != "corpus-status-counts/v1":
        raise ValueError("unsupported schema")
    scope = _string(document.get("scope"), "scope")
    groups = document.get("groups")
    if not isinstance(groups, list) or not groups:
        raise ValueError("groups must be a nonempty array")
    seen_groups = set()
    all_links = []
    all_cards = []
    group_reports = []
    for index, item in enumerate(groups):
        group = _object(item, f"group {index}")
        group_id = _string(group.get("group_id"), "group_id")
        if group_id in seen_groups:
            raise ValueError("duplicate group_id")
        seen_groups.add(group_id)
        cutoff = _cutoff(group.get("cutoff"), "group cutoff")
        links = group.get("links")
        if not isinstance(links, list):
            raise ValueError("links must be an array")
        cards = group.get("event_cards", [])
        if not isinstance(cards, list):
            raise ValueError("event_cards must be an array")
        group_cards = []
        seen_cards = set()
        for position, item in enumerate(cards):
            card = _object(item, f"event card {position}")
            card_id = _string(card.get("event_id"), "card event_id")
            if card_id in seen_cards:
                raise ValueError("duplicate event card within group")
            seen_cards.add(card_id)
            if card.get("status") not in ("linked", "unlinked"):
                raise ValueError("event card status must be linked or unlinked")
            _string(card.get("reason"), "card reason")
            group_cards.append(card)
        group_links = []
        seen_links = set()
        for position, item in enumerate(links):
            link = _object(item, f"link {position}")
            link_id = _string(link.get("link_id"), "link_id")
            if link_id in seen_links:
                raise ValueError("duplicate link_id within group")
            seen_links.add(link_id)
            status = _string(link.get("status"), "status")
            if status not in STATUSES:
                raise ValueError(f"unsupported status: {status}")
            _string(link.get("reason"), "reason")
            if "event_id" in link:
                raise ValueError("use event_ids array, not event_id")
            if "event_ids" in link:
                event_ids = link["event_ids"]
                if not isinstance(event_ids, list):
                    raise ValueError("event_ids must be an array")
                for event_id in event_ids:
                    _string(event_id, "event_ids item")
                if len(set(event_ids)) != len(event_ids):
                    raise ValueError("duplicate event_ids within link")
            for optional in ("event_id", "source_url"):
                if optional in link and link[optional] is not None:
                    _string(link[optional], optional)
            if "source_sha256" in link and link["source_sha256"] is not None:
                if not isinstance(link["source_sha256"], str) or not HASH.fullmatch(link["source_sha256"]):
                    raise ValueError("source_sha256 must be a lowercase SHA-256 hex digest")
            row_fields = ("row_count", "row_count_kind", "row_basis_id")
            if any(field in link for field in row_fields):
                if link.get("row_count") is None:
                    if link.get("row_count_kind") is not None or link.get("row_basis_id") is not None:
                        raise ValueError("row_count requires row_count_kind and row_basis_id")
                else:
                    if not all(field in link and link[field] is not None for field in row_fields):
                        raise ValueError("row_count requires row_count_kind and row_basis_id")
                    _count(link["row_count"], "row_count")
                    if link["row_count_kind"] not in ROW_KINDS:
                        raise ValueError("unsupported row_count_kind")
                    _string(link["row_basis_id"], "row_basis_id")
            group_links.append(link)
        group_reports.append({"group_id": group_id, "cutoff": cutoff,
                              **_summarize(group_links, group_cards)})
        all_links.extend(group_links)
        all_cards.extend(group_cards)
    result = {"schema": "corpus-status-counts-report/v1", "scope": scope,
              **_summarize(all_links, all_cards), "groups": group_reports,
              "limits": ["link counts are observations within the supplied groups",
                         "unknown event and source identities remain unknown",
                         "row totals require nonoverlapping row_basis_id evidence",
                         "no unique sporting-attempt count is inferred"]}
    if "corpus_snapshot" in document:
        snapshot = _object(document["corpus_snapshot"], "corpus_snapshot")
        result["corpus_snapshot"] = {"cutoff": _cutoff(snapshot.get("cutoff"), "snapshot cutoff")}
        for field in ("jobs", "source_hashes", "observation_versions"):
            result["corpus_snapshot"][field] = _count(snapshot.get(field), field)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path, help="normalized bounded inventory JSON")
    args = parser.parse_args(argv)
    try:
        result = report(json.loads(args.inventory.read_text()))
    except (OSError, ValueError, UnicodeError) as error:
        print(f"inventory rejected: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
