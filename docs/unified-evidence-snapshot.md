# Dated private evidence snapshot

`scripts/unified_evidence_snapshot.py` builds a local SQLite export for owner review. The snapshot is a partial census, not a claim that all 2025-2026 results or distinct attempts are known. Keep `snapshot.sqlite` and `manifest.json` under ignored `data/`; neither is public site content.

Build with one `--input NAME=PATH` per completed JSON packet, `--excluded NAME=PATH:REASON` per unfinished lead, an explicit UTC `--cutoff`, and `--output-dir data/issue55-unified-snapshot-YYYYMMDD`. Add `--required-input NAME=SHA256` for every complete packet that the export must contain. The build fails if a required packet is absent or has different bytes; the manifest retains this contract for replay. Source names must be unique. `verify --output-dir DIR` checks the database hash and collection counts. `replay --output-dir DIR` rereads the exact input paths from the manifest, checks input hashes, rebuilds, and requires the same database hash. Replay needs the original private files; normal read-only queries need only the database.

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

The original 2026-09-28 export combines B45 imported positions, retained-only and gap reconciliations, the GIA 2025 workbook, and completed Barracuda, Firenze, Asti Blu, Friday Night Dive, Cagliari, Liberamente, and Komaros visual packets. Include the completed Komaros packet as `--input komaros-visual=PATH` with `--required-input komaros-visual=4f79e8694131cb4eb707f855360917c9c5d9df81688a9f9fc842ae2f44495081`. The two incomplete Komaros page ledgers remain excluded provenance; their 47 observed rows are not counted again. This original export did not include #8 Roatan.

The Roatan supplement is a later private version. `scripts/roatan_2026_cwt_men_census.py build` reads the corrected packet, isolated stage archive and post-acceptance database dump. It verifies exact source and artifact object hashes, retains acquisition and acceptance file references, exports both parser versions, and records the seven historical extraction acceptances only for parser v2 unit 3551 rows 0-6. Unit 3559's 24 v2 rows and all 31 v1 rows remain unreviewed. The 31 source positions, 62 observation versions and seven decisions are separate counts; confirmed distinct attempts remains null. The packet keeps declared depth, raw depth, publisher final depth, penalty, status and notes separate. Its selected result URLs, source hashes and zero-based row citations support source inspection. Its `version_diffs` compare parser fields without inferring attempt equivalence. No review decision, identity, source finality or publication state is created by this export.

Use `extend --base-dir OLD_SNAPSHOT --input roatan-issue8=PRIVATE_PACKET --required-input roatan-issue8=PACKET_SHA256 --output-dir NEW_SNAPSHOT` to append the bounded #8 namespace to a hash-verified copy of the historical database. Keep the old directory intact. The new manifest records the old database and manifest hashes and the required Roatan packet hash. `verify` checks the new database; `replay` checks the old manifest and required packet, then reproduces the new database. This route works when earlier source packet paths in the old manifest are no longer available. The original input namespaces remain separate and are not counted again.

For workbook date ranges, filter `date_from <= ? AND date_to >= ?`; a multi-day date scope has null `event_date`.

## Read-only owner query layer

`scripts/unified_evidence_query.py` opens the snapshot in SQLite read-only immutable mode. It verifies the database hash against the manifest before opening it. It has no write or review route and creates no database files. Keep this module behind owner authentication if a web handler is added; this slice exposes no HTTP service or public Worker endpoint.

Python API: `with SnapshotQuery(snapshot_dir) as query:` then call `overview()`, `browse(...)`, `detail(record_id)`, `gaps(...)`, `relationships(...)`, `sources()`, or `source(source_name)`. `browse` accepts exact `source_name`, `collection`, `kind`, `event_name`, `session`, `discipline`, and `category` filters. `date_from` and `date_to` are inclusive ISO dates that overlap a stored `date_from`/`date_to` span or an exact `event_date`. A missing date does not match a date filter. `limit` defaults to 50 and is bounded to 1-100; `offset` is bounded to 0-100000. Results include `total`, `limit`, `offset`, and stable rows ordered by source namespace, collection, record path, then record ID. Bad filters raise `ValueError`; absent detail/source IDs return `None`.

`overview` reports collection and kind counts **within each source namespace**, the dated cutoff, snapshot hash, and `confirmed_distinct_attempts: null`. `detail` returns exact stored raw, raw field, parsed field, citation, date scope, normalized locator/review fields, input packet SHA256, original source SHA256 when supplied, parser/observation versions, and snapshot SHA256. `sources` includes manifest dispositions for included and excluded inputs; `source(name)` adds stored source metadata for included inputs. `gaps` and `relationships` are kind-filtered browse calls. These are candidate evidence rows, not accepted cross-source relationships or resolved issues. No federation filter exists in this schema; use only explicitly recorded source metadata or raw fields and do not infer federation from names.

CLI emits one JSON object per invocation:

```sh
python3 scripts/unified_evidence_query.py --snapshot-dir data/issue55-unified-snapshot-20260928 overview
python3 scripts/unified_evidence_query.py --snapshot-dir data/issue55-unified-snapshot-20260928 browse --source-name komaros-visual --kind candidate_position --limit 50
python3 scripts/unified_evidence_query.py --snapshot-dir data/issue55-unified-snapshot-20260928 detail RECORD_ID
python3 scripts/unified_evidence_query.py --snapshot-dir data/issue55-unified-snapshot-20260928 sources
```

The CLI and API may expose private source file paths and raw evidence. Run them only in the private owner environment. The API never computes distinct attempts, merges overlapping namespaces, or promotes uncertain relationships to facts. The original 2026-09-28 snapshot remains partial; the later private extension includes the bounded #8 Roatan evidence as a separate namespace.
