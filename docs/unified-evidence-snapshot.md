# Dated private evidence snapshot

`scripts/unified_evidence_snapshot.py` builds a local SQLite export for owner review. The snapshot is a partial census, not a claim that all 2025-2026 results or distinct attempts are known. Keep `snapshot.sqlite` and `manifest.json` under ignored `data/`; neither is public site content.

Build with one `--input NAME=PATH` per completed JSON packet, `--excluded NAME=PATH:REASON` per unfinished lead, an explicit UTC `--cutoff`, and `--output-dir data/issue55-unified-snapshot-YYYYMMDD`. Source names must be unique. `verify --output-dir DIR` checks the database hash and collection counts. `replay --output-dir DIR` rereads the exact input paths from the manifest, checks input hashes, rebuilds, and requires the same database hash. Replay needs the original private files; normal read-only queries need only the database.

`records` stores one row per top-level list entry and, for sheet/page containers, one additional row per nested `rows`, `aggregate_rows`, or `athlete_rows` entry. `record_id` hashes the packet namespace and JSON path. `source_id` is the packet's own ID and may repeat across packets or within a packet. `raw_json` preserves the exact parsed JSON object. `raw_fields_json` and `parsed_fields_json` preserve fields separately. `citation_json`, `page`, `date_from`, `date_to`, `date_scope_json`, `parser_version`, `observation_version`, `acquisition_id`, and normalized event/date/session/category/discipline fields support review filters. Unknowns stay null. `source_metadata` preserves every non-list top-level field, including source objects, acquisition details, counts, uncertainty notes, and packet provenance. The manifest supplies input hashes, schema, paths, observed collection counts, exclusion reasons, and snapshot hash.

`kind='candidate_position'` means a printed or candidate source position, including unreviewed rows and GIA workbook event-table rows. It does not mean a confirmed distinct attempt. `kind='aggregate'` includes GIA combined and secondary scores, standings, and club rows. Formula placeholders are `other`. Repeated visual appearances, retained candidate versions, baseline positions, and GIA rows stay in separate packet namespaces. Do not sum them into distinct attempts or infer equivalence. Source relationships remain raw candidate evidence until reviewed. The manifest's `confirmed_distinct_attempts` is null.

Example read-only query:

```sql
SELECT source_name, record_path, event_date, discipline, category,
       citation_json, raw_fields_json, review_status
FROM records
WHERE kind = 'candidate_position' AND event_date = '2026-05-08'
ORDER BY source_name, record_path;
```

The 2026-09-28 export combines B45 imported positions, retained-only and gap reconciliations, the GIA 2025 workbook, and completed Barracuda, Firenze, Asti Blu, Friday Night Dive, Cagliari, and Liberamente visual packets. The two Komaros page ledgers are explicitly excluded pending a completed packet. Their manifest entries inventory 13 pages and 47 visual rows. The export does not include the separate #8 Roatan owner-reviewed evidence.

For workbook date ranges, filter `date_from <= ? AND date_to >= ?`; a multi-day date scope has null `event_date`.
