#!/usr/bin/env python3
"""Compare archived FFESSM 2025 daily and category observations without DB writes.

Input supplies cited observations and source context independently checked against
the archived official index and original PDF headings. Category dates are unknown.
This ledger links printed results, retaining every original observation version.
"""

import argparse
import hashlib
import json
import os
import re
import tempfile
from collections import Counter, defaultdict
from pathlib import Path


SHA = re.compile(r"[0-9a-f]{64}\Z")
PERFORMANCE = ("announced-depth", "realized-depth", "depth-penalty",
               "final-points", "card", "unit")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def identity(row):
    parsed = row["parsed"]
    fields = ("source-name", "gender", "discipline")
    values = tuple(parsed.get(field) for field in fields)
    return values if all(isinstance(v, str) and v.strip() for v in values) else None


def profile_match(daily, category):
    a, b = daily["parsed"], category["parsed"]
    return all(isinstance(a.get(field), str) and a[field].strip()
               and a[field] == b.get(field)
               for field in ("federation", "nationality"))


def citation(row):
    return {"ref": row["ref"], "source-sha256": row["source-sha256"],
            "position": row["position"], "source-text": row["source-text"]}


def validate(data):
    sources = data.get("sources")
    observations = data.get("observations")
    require(isinstance(sources, list) and isinstance(observations, list),
            "sources and observations lists required")
    indexed = {}
    for source in sources:
        source_hash = source.get("sha256")
        require(isinstance(source_hash, str) and SHA.fullmatch(source_hash)
                and source_hash not in indexed, "duplicate or invalid source hash")
        require(source.get("role") in {"daily", "category"}, "invalid source role")
        require(isinstance(source.get("title"), str) and source["title"].strip(),
                "source title required")
        indexed[source_hash] = source
    seen = set()
    for row in observations:
        source_hash = row.get("source-sha256")
        require(source_hash in indexed, "observation source absent from context")
        ref = row.get("ref")
        require(isinstance(ref, dict) and isinstance(ref.get("job-id"), str)
                and type(ref.get("ordinal")) is int and ref["ordinal"] >= 0,
                "invalid observation reference")
        key = ref["job-id"], ref["ordinal"]
        require(key not in seen, "duplicate observation reference")
        seen.add(key)
        position = row.get("position")
        require(isinstance(position, list) and position
                and all(type(p.get("page")) is int and p["page"] > 0
                        and type(p.get("line")) is int and p["line"] > 0
                        for p in position), "observation citation position required")
        require(isinstance(row.get("source-text"), str)
                and row["source-text"].strip(), "observation citation text required")
        require(isinstance(row.get("parsed"), dict), "parsed observation required")
        source = indexed[source_hash]
        if source["role"] == "daily":
            require(source.get("day") in {"2025-06-27", "2025-06-28"}
                    and source["day"] == row.get("day"), "daily date mismatch")
        else:
            require(source.get("day") is None and row.get("day") is None,
                    "category date must remain unprinted")
    return indexed


def context_compatible(daily, category):
    a, b = daily, category
    session = "J1" if a.get("day") == "2025-06-27" else "J2"
    return (a.get("event") and a.get("event") == b.get("event")
            and "Championnat de France" in a["title"]
            and "Championnat de France" in b["title"]
            and "2025" in a["title"] and "2025" in b["title"]
            and "Villefranche" in a["title"] and "Villefranche" in b["title"]
            and session in a["title"])


def performance_match(daily, category):
    a, b = daily["parsed"], category["parsed"]
    if not all(a.get(field) is not None and a.get(field) == b.get(field)
               for field in PERFORMANCE):
        return False
    # The daily heading prints a blank plate cell; category parsers encode
    # that same zero as 0. Never normalize a printed nonzero penalty.
    plate_a, plate_b = a.get("plate-penalty"), b.get("plate-penalty")
    return plate_a == plate_b or (plate_a is None and plate_b == 0)


def category_compatible(daily, category):
    name = category["parsed"].get("category") or ""
    return "Juniors" not in name or daily["parsed"].get("category") == name


def category_heading_compatible(source, row):
    terms = {"FIM": "Immersion Libre", "CNF": "Sans Palmes",
             "CWT-BI": "Bipalme", "CWT-MONO": "Monopalme"}
    title = source["title"]
    term = terms.get(row["discipline"])
    category = row["parsed"].get("category") or ""
    gender_term = "Femmes" if row["parsed"].get("gender") == "F" else "Hommes"
    return (term is not None and term in title
            and ("Juniors" in category or gender_term in title))


def classify(data):
    sources = validate(data)
    daily = [r for r in data["observations"]
             if sources[r["source-sha256"]]["role"] == "daily"]
    categories = [r for r in data["observations"]
                  if sources[r["source-sha256"]]["role"] == "category"]
    by_identity = defaultdict(list)
    for row in daily:
        if identity(row):
            by_identity[identity(row)].append(row)
    edges, unmatched = [], []
    for category in categories:
        candidates = by_identity.get(identity(category), []) if identity(category) else []
        category_source = sources[category["source-sha256"]]
        exact = [r for r in candidates if context_compatible(sources[r["source-sha256"]],
                                                               category_source)
                 and category_heading_compatible(category_source, category)
                 and category_compatible(r, category)
                 and profile_match(r, category)
                 and performance_match(r, category)]
        if not candidates:
            unmatched.append({"category": citation(category),
                              "reason": "no-daily-identity-candidate"})
            continue
        for row in candidates:
            context = context_compatible(sources[row["source-sha256"]],
                                         category_source)
            matching = performance_match(row, category)
            if not context:
                kind, reason = "unknown", "missing-or-conflicting-event-context"
            elif not category_heading_compatible(category_source, category):
                kind, reason = "unknown", "category-heading-conflict"
            elif not category_compatible(row, category):
                kind, reason = "unknown", "junior-category-unverified-in-daily-row"
            elif not profile_match(row, category):
                kind, reason = "unknown", "federation-or-nationality-conflict"
            elif matching and len(exact) == 1:
                kind, reason = "same-result", "unique-exact-daily-row-in-two-day-scope"
            elif matching:
                kind, reason = "unknown", "identical-result-on-multiple-daily-rows"
            elif len(exact) == 1 and row["day"] != exact[0]["day"]:
                kind, reason = "distinct-session", "different-dated-daily-performance"
            else:
                kind, reason = "unknown", "different-performance-without-unique-exact-match"
            edges.append({"kind": kind, "reason": reason,
                          "daily": citation(row), "category": citation(category),
                          "daily-day": row["day"], "discipline": category["discipline"],
                          "source-name": category["parsed"].get("source-name")})
    edges.sort(key=canonical)
    unmatched.sort(key=canonical)
    counts = Counter(edge["kind"] for edge in edges)
    output = {"schema": "ffessm-2025-cross-publication/v1",
              "counts": {"daily-positions": len(daily), "category-positions": len(categories),
                         "same-result": counts["same-result"],
                         "distinct-session": counts["distinct-session"],
                         "unknown": counts["unknown"], "unmatched-category": len(unmatched)},
              "edges": edges, "unmatched-category": unmatched,
              "limits": ["Category dates are unprinted; matched dates come from unique cited daily rows.",
                         "Links are printed-result relationships, not owner identities or a global unique-attempt count.",
                         "Every original observation remains immutable."]}
    output["ledger_sha256"] = hashlib.sha256(canonical(output).encode()).hexdigest()
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_json", type=Path)
    parser.add_argument("output_json", type=Path)
    args = parser.parse_args()
    try:
        result = classify(json.loads(args.input_json.read_text()))
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output_json.parent,
                                         prefix=".ffessm-ledger-", delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(canonical(result) + "\n")
        os.replace(temporary, args.output_json)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"ffessm relationship audit: {error}\n")


if __name__ == "__main__":
    main()
