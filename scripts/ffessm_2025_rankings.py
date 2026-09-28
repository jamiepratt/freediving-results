#!/usr/bin/env python3
"""Replay three archived FFESSM 2025 ranking sources into a private query packet."""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import tempfile
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin


INDEX_URL = "https://apnee.ffessm.fr/resultats-2025"
BASE = "https://apnee.ffessm.fr"
SOURCES = (
    ("https://apnee.ffessm.fr/uploads/media/default/0001/13/b81f1b021ed2ee18c7b84aa77ed5a9b1dd1f9e81.pdf",
     "Monopalme Femmes", "Résultats Monopalme Femmes", 2),
    ("https://apnee.ffessm.fr/uploads/media/default/0001/13/a1d6b065d17c907e07cb9311010a65c4921bcdb3.pdf",
     "Monopalme Hommes", "Résultats Monopalme Hommes", 8),
    ("https://apnee.ffessm.fr/uploads/media/default/0001/13/fe9c48ac711c081e9f1b098c4564dc8285748163.pdf",
     "Monopalme Hommes Juniors", "Résultats Monopalme JUNIORS", 1),
)
SCHEMA = "ffessm-2025-rankings/v1"
DAILY_LABELS = ("Jour 1 - Vendredi 27 juin 2025", "Jour 2 - Samedi 28 juin 2025")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


class Links(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.current = None
        self.links = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self.current = [dict(attrs).get("href"), [], self.getpos()[0]]

    def handle_data(self, data):
        if self.current is not None:
            self.current[1].append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.current is not None:
            self.links.append((urljoin(BASE, self.current[0] or ""),
                               " ".join("".join(self.current[1]).split()),
                               self.current[2]))
            self.current = None


def index_links(index_bytes):
    parser = Links()
    parser.feed(index_bytes.decode("utf-8"))
    for url, label, _, _ in SOURCES:
        found = [item for item in parser.links if item[0] == url]
        require(len(found) == 1, f"index link missing or duplicated: {url}")
        require(found[0][1] == label, f"index label mismatch: {url}")
    for label in DAILY_LABELS:
        require(sum(item[1] == label for item in parser.links) == 1,
                f"index daily link missing or duplicated: {label}")
    return parser.links


def archived_sources(inventory):
    found = {}
    for item in inventory.get("sources", []):
        receipt = item.get("receipt") or {}
        url = receipt.get("final_url")
        if url not in {spec[0] for spec in SOURCES}:
            continue
        require(url not in found, f"duplicate inventory source: {url}")
        require(item.get("classification") == "eligible" and
                item.get("content_type", "").split(";")[0].lower() == "application/pdf",
                f"invalid PDF source: {url}")
        require(receipt.get("discovery_url") == INDEX_URL and receipt.get("retrieved_at"),
                f"missing provenance receipt: {url}")
        source_path = Path(item["source_path"])
        require(source_path.is_file() and not source_path.is_symlink(),
                f"missing original source: {url}")
        content = source_path.read_bytes()
        require(content.startswith(b"%PDF-") and len(content) == item.get("bytes")
                and sha(content) == item.get("sha256")
                and item.get("id") == "sha256:" + sha(content),
                f"source hash, bytes or PDF signature mismatch: {url}")
        extracted = subprocess.run(["pdftotext", "-layout", str(source_path), "-"],
                                   capture_output=True, text=True, check=True).stdout
        found[url] = (item, extracted)
    require(len(found) == len(SOURCES), "three inventory ranking sources required")
    return found


def heading(extracted, expected):
    lines = [" ".join(line.split()) for line in extracted.splitlines()]
    require(any("Championnat de France Eau Libre 2025" in line for line in lines)
            and any(expected in line for line in lines)
            and any("Villefranche-sur-Mer" in line for line in lines),
            f"PDF event/year/title heading mismatch: {expected}")
    return expected


def positions(db, source, extracted):
    pages = [page.splitlines() for page in extracted.split("\f")]
    rows = db.execute("select record_path,citation_json,raw_fields_json,raw_json,"
                      "category,discipline,review_status "
                      "from records where source_object_id=? and kind='candidate_position' "
                      "order by record_path", (source["id"],)).fetchall()
    require(rows, f"snapshot candidate positions missing: {source['id']}")
    result = []
    seen = set()
    for record_path, citation_json, raw_fields_json, raw_json, category, discipline, review_status in rows:
        raw = json.loads(raw_json)
        citation = json.loads(citation_json)
        fields = json.loads(raw_fields_json)
        coords = raw.get("coordinates") or {}
        refs = raw.get("observation_refs") or []
        require(isinstance(citation, str) and citation == raw.get("locator")
                and re.fullmatch(r"page [1-9][0-9]* line [1-9][0-9]* column start [1-9][0-9]* column end [1-9][0-9]*", citation)
                and all(type(coords.get(key)) is int and coords[key] > 0
                        for key in ("page", "line", "column-start", "column-end"))
                and coords["column-start"] <= coords["column-end"],
                f"invalid exact position citation: {record_path}")
        require(isinstance(fields, dict) and fields
                and fields == (raw.get("raw_fields") or (raw.get("raw_evidence") or {}).get("fields"))
                and isinstance(raw.get("id"), str) and raw["id"] not in seen,
                f"conflicting position fields or ID: {record_path}")
        require(isinstance(refs, list) and refs and
                all(isinstance(ref, dict) and ref.get("job_id") is not None
                    and type(ref.get("ordinal")) is int for ref in refs),
                f"observation references missing: {record_path}")
        require(all(ref.get("citation") == citation for ref in refs),
                f"observation citation differs from position: {record_path}")
        require(coords["page"] <= len(pages) and coords["line"] <= len(pages[coords["page"] - 1]),
                f"printed line missing from original PDF: {record_path}")
        printed = pages[coords["page"] - 1][coords["line"] - 1]
        raw_line = (raw.get("raw_evidence") or {}).get("line")
        require(isinstance(raw_line, str) and raw_line == printed
                and coords["column-end"] <= len(printed) + 1,
                f"printed line differs from original PDF: {record_path}")
        source_lines = raw.get("source_lines")
        require(isinstance(source_lines, list) and
                {"page": coords["page"], "line": coords["line"], "text": raw_line} in source_lines,
                f"source lines differ from original PDF: {record_path}")
        seen.add(raw["id"])
        result.append({"source_object_id": source["id"], "source_url": source["receipt"]["final_url"],
                       "record_path": record_path, "position_id": raw["id"],
                       "citation": citation, "coordinates": coords,
                       "printed_line": printed, "source_lines": source_lines,
                       "raw_fields": fields, "observation_refs": refs,
                       "event_date": None, "date_from": None, "date_to": None,
                       "session": None, "category": category, "discipline": discipline,
                       "status": raw.get("status"),
                       "unresolved_reason": raw.get("unresolved_reason"),
                       "review_status": review_status})
    return result


def build(index_path, inventory_path, snapshot_path):
    index_bytes = index_path.read_bytes()
    links = index_links(index_bytes)
    index_citations = {url: {"url": INDEX_URL, "sha256": sha(index_bytes),
                             "line": next(line for found_url, _, line in links if found_url == url),
                             "href": url, "label": label}
                       for url, label, _, _ in SOURCES}
    daily_context = [{"url": url, "label": label, "index_line": line}
                     for url, label, line in links
                     if label in DAILY_LABELS]
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    archive = archived_sources(inventory)
    sources = []
    all_positions = []
    relationships = []
    with sqlite3.connect(f"file:{snapshot_path}?mode=ro", uri=True) as db:
        for url, label, title, expected_positions in SOURCES:
            source, extracted = archive[url]
            cited = positions(db, source, extracted)
            require(len(cited) == expected_positions,
                    f"snapshot position count differs from printed ranking: {url}")
            categories = {row["category"] for row in cited}
            disciplines = {row["discipline"] for row in cited}
            require(len(categories) == 1 and None not in categories
                    and disciplines == {"CWT-MONO"},
                    f"snapshot category or discipline conflict: {url}")
            sources.append({"id": source["id"], "url": url, "sha256": source["sha256"],
                            "bytes": source["bytes"], "receipt": source["receipt"],
                            "source_type": "aggregate_ranking",
                            "index_label": label, "heading": heading(extracted, title),
                            "index_citation": index_citations[url],
                            "category": next(iter(categories)),
                            "discipline": "CWT-MONO",
                            "event_year": 2025, "event_date": None,
                            "date_from": None, "date_to": None, "session": None,
                            "http_status": None, "response_headers": None,
                            "provenance_gaps": (source.get("metadata") or {}).get("provenance_gaps", [])
                            + ["Original HTTP status and response headers absent from retained receipt"],
                            "source_positions": len(cited)})
            all_positions.extend(cited)
            relationships.extend({"kind": "ranking_to_daily_publication_candidate",
                                  "ranking_source_object_id": source["id"],
                                  "ranking_url": url, "daily_url": daily["url"],
                                  "daily_index_label": daily["label"],
                                  "daily_index_line": daily["index_line"],
                                  "status": "unknown", "same_attempt": None,
                                  "source_equivalence": None}
                                 for daily in daily_context)
    return {"schema": SCHEMA,
            "inputs": {"index_url": INDEX_URL, "index_sha256": sha(index_bytes),
                       "inventory_sha256": sha(inventory_path.read_bytes()),
                       "snapshot_sha256": sha(snapshot_path.read_bytes())},
            "counts": {"source_objects": len(sources), "source_positions": len(all_positions),
                       "existing_observation_versions": sum(len(p["observation_refs"]) for p in all_positions),
                       "confirmed_distinct_attempts": None},
            "sources": sources, "positions": all_positions,
            "relationship_candidates": relationships,
            "date_evidence": {"event_year": 2025, "source": "PDF title and 2025 official result index",
                              "ranking_row_date": None,
                              "related_daily_links": daily_context},
            "limits": ["Category ranking rows do not print a result day.",
                       "Snapshot observations are existing versions, not new imports.",
                       "Source positions do not establish distinct sporting attempts or overlap with daily results."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("index", "inventory", "snapshot", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    try:
        packet = build(args.index, args.inventory, args.snapshot)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output.parent,
                                         prefix=".ffessm-rankings-", delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(canonical(packet) + "\n")
        os.replace(temporary, args.output)
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error,
            subprocess.CalledProcessError) as exc:
        parser.exit(1, f"ffessm ranking packet: {exc}\n")


if __name__ == "__main__":
    main()
