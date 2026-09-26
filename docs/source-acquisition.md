# Source acquisition and row lineage

Observed through 25 September 2026. This is a route and evidence guide, not a live availability check or a complete championship inventory. Start at the [CMAS results archive](https://www.cmas.org/freediving/results.html) or the AIDA event page, then retain what the route actually returns. Current source coverage and unresolved work live in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Paced acquisition entry points

Use `scripts/acquire_source.py` for official discovery pages, PDFs, JSON and HTML. Supply the expected representation explicitly. Its private output retains exact successful bytes, hash, MIME, status, timing, redirect chain and host-only pacing events. A restriction, challenge page, wrong MIME or invalid signature produces a host-only coverage gap and no source object. This command does not register or ingest a source.

```sh
python3 scripts/acquire_source.py https://www.cmas.org/ html data/private-acquisition --dry-run
python3 scripts/acquire_source.py OFFICIAL_URL pdf data/private-acquisition
python3 scripts/acquire_source.py OFFICIAL_URL pdf data/private-acquisition --archive-root /private/mounted-archive --context-json '{"event":"exact-event","version":"exact-result-version"}'
python3 scripts/acquire_source.py OFFICIAL_URL pdf data/private-acquisition --archive-root /private/mounted-archive --context-json '{"event":"exact-event","version":"exact-result-version"}' --refresh
```

The HTTP command inventories its output directory and every configured existing acquisition root before a request. A root may be local or a mounted remote private directory. It checks the exact requested URL, representation and supplied JSON date/filter/version context; HTTP 200, MIME, final URL and redirect chain; timestamps with offsets, byte length, SHA-256 and response signature; and the complete `source-run/v1` checkpoint for new receipts. Corrupt, missing or incomplete candidates are ignored. An unavailable or corrupt configured root stops the command. Matching verified bytes are copied atomically into the output directory with unchanged original acquisition provenance and a new run reference. The default receipt says `freshness: not_checked`. `--refresh` makes a new paced publisher request and records `freshness: refresh_requested`; neither label proves the publisher has no later revision. Compare the observed SHA-256 and selected context before asserting any revision relationship. Existing flat legacy acquisition receipts can be reused only when they contain the same complete retrieval evidence.

For a configured Clojure archive with `objects/` and `acquisitions/`, the command runs the versioned `clojure -M:archive inventory-json ARCHIVE` API first. This verifies every manifest identity, object hash and retained browser evidence. Matching records count as `legacy_archive_incomplete` in the receipt because those manifests alone lack observed HTTP status and byte length. They are not reused until paired with a complete acquisition receipt. A corrupt archive fails closed before any publisher request. A mounted remote root must already be accessible; this command does not discover or authenticate to remote services.

Each successful request or reuse writes `run-<identity>.json` with a complete source checkpoint and pending import. The source and provenance files are staged with atomic replacement; a failed or partial HTTP response creates a gap and no complete checkpoint. A downstream importer can use the public `import_once(run_path, importer)` API, which verifies source bytes and provenance again, invokes the importer's existing idempotent archive/extraction/observation APIs, and atomically records its immutable job reference only after success. A crash before the checkpoint retries that idempotent importer on restart. The CLI form executes an exact command once after substitution of `{source}` and `{provenance}` and requires the caller's immutable job reference:

```sh
python3 scripts/acquire_source.py import-once data/private-acquisition/run-IDENTITY.json JOB_ID -- clojure -M:observations import data/private-archive JOB_ID
```

This example assumes the exact verified source and provenance were already registered and extracted in that archive, and the job ID is the deterministic extraction receipt. Supply `FREEDIVING_DATABASE_URL` for the isolated database. Verify the job ID and source hash against the extraction receipt before calling it. This checkpoint prevents repeated successful invocation after restart; it cannot by itself make an external, non-idempotent importer safe after a crash between external commit and checkpoint. The existing archive registration, derivation and observation import APIs provide that idempotence.

Use `scripts/capture_browser.py` for a page that needs browser rendering. It requires Playwright and a Chromium installation; `--channel chrome` uses an installed Chrome channel. The command routes navigation, API calls, direct downloads and page assets through the same per-host lease. It retains result-bearing response bytes, safe request URLs and redirect targets, and the complete rendered DOM under a new private output directory, or a safe coverage gap on a terminal failure. Blocked service workers prevent requests from bypassing the route. Browser capture does not infer selected dates, filters, source identity or publisher authority; record and verify those before archive registration. Browser response byte limits are checked after Playwright receives the response, so use the HTTP command for large or unknown-size downloads.

```sh
python3 scripts/capture_browser.py https://www.aidainternational.org/StartList/4349 data/private-browser --dry-run
python3 scripts/capture_browser.py OFFICIAL_PAGE_URL data/private-browser
```

Both commands use `scripts/source_acquisition.py`. Its SQLite lease under `~/.local/state/freediving-results/acquisition.sqlite3` coordinates workers and restarted processes on the same machine. Configure a single shared `lease_path` in Python callers, or `--lease-path` for browser capture, when using another private state location. Every HTTP retry and redirect host gets a fresh lease. The default policy is two concurrent requests per host, at least one second between starts, a 20-second timeout, three attempts, 30-second maximum retry delay, five redirects and 50 MiB response limit. CMAS hosts default to one concurrent request, three seconds between starts and two attempts. A caller can set stricter host policies. `Retry-After` is honored up to the configured maximum. Events and terminal errors omit URL paths, queries, response bodies and headers. Do not put credentials or session-bearing URLs in command arguments or private provenance.

The historical manual and ad hoc acquisition paths are not automatically guarded by these commands. Future #8/#16 acquisition must use these entry points or the shared client and verify the resulting evidence before archive registration. The [parent pacing prerequisite #22](https://github.com/jamiepratt/freediving-results/issues/22) remains open through archive inventory and full validation. Do not start new #8/#16 ingestion before it closes.

## Find and acquire a source

| Source and discovery | Result route and representation | Observed access and context | Provenance caution |
| --- | --- | --- | --- |
| CMAS 2026 Novi Sad [seniors/juniors](https://www.cmas.org/document/2026,-cmas-world-championship-freediving-indoor/download.html) and [masters](https://www.cmas.org/document/2026,-cmas-world-championship-freediving-indoor-masters/download.html) | Direct `download.html` PDFs | Senior PDF returned 200 `application/pdf`, including anonymous Python HTTPS GET; browser download also worked. Both PDFs were archived with valid `%PDF-` signatures. | A browser success does not establish a login requirement. Check status, MIME, signature, length and hash for each retrieval. |
| CMAS 2025 Mytikas federation [CWT men](https://www.cmas.org/media/com_eventbooking/Result_CWT-MS_M.pdf) and [women](https://www.cmas.org/media/com_eventbooking/Result_CWT-WS_W.pdf) | Direct federation PDFs | Acquired as PDFs; the event archive does not directly expose the men's file. | The federation copies and timing-service CWT copies differ in bytes. No row-level revision relationship follows from the filenames or hashes. |
| CMAS 2025 [depth](https://www.cmas.org/document/2025,-cmas-world-championship-freediving-depth/download.html) and [masters](https://www.cmas.org/document/2025,-cmas-world-championship-freediving-depth-masters/download.html) | Federation redirect to Microplus `#/competition-schedule/3`; [competition 3 API](https://cmas-api.microplustimingservices.com/api/competitions/3), schedule, unit results and documents lead to `RES` PDFs | Twenty result PDFs acquired. The masters redirect to `/event-detail/4` is stale; competition 4 is another event. | Keep federation link and redirect chain. Distinguish `RES` result documents from start lists and medalist files. |
| CMAS 2026 [Roatan depth](https://www.cmas.org/freediving-events/2026-cmas-world-championship-freediving-depth.html) | [competition 30 API](https://cmas-api.microplustimingservices.com/api/competitions/30), unit result/document JSON and timing PDFs | Seven result PDFs acquired. CWT men unit 3551 has no linked PDF; the linked unit 3559 result returned 404. Later direct API and app probes returned 403. | Retain failed response metadata as a gap. Unit JSON rows and a medalists PDF do not fill missing attempt-result PDF coverage. |
| CMAS 2025 Athens [event](https://www.cmas.org/freediving-events/2025-cmas-world-championship-freediving-indoor.html) and indoor archive entries | Archive redirects to legacy selectors `#/2/schedule-bydate` and `#/1/schedule-bydate`. A human [DNF result page](https://results.microplustimingservices.com/CMAS/Results/#/2/dynamic-result-json/MAM/011/007/001) cites a distinct [CGR1 JSON response](https://results.microplustimingservices.com/CMAS/ExportPOST/export/CMAS_2/TFMAM011CLAS07%20001.JSON). | Forty-two Results JSON views acquired across seven disciplines and six category codes. All 30 configured result PDF links returned 404. Later direct federation-page visits returned 403. | Both selectors contain junior, senior and master categories; they separate discipline groups. Keep selector, page fragment, JSON URL and exact JSON bytes. HTML is a citation, not the archived JSON object. |
| AIDA official [Wakayama 4349](https://www.aidainternational.org/StartList/4349), [Limassol 4350](https://www.aidainternational.org/StartList/4350), [Budapest 4852](https://www.aidainternational.org/StartList/4852) | Selected-date HTML attempt tables at a URL that may remain unchanged; a literal `day_index` query can also select a date. | Browser access worked; scripted direct HTTP returned 403 in discovery. Sixteen distinct date views were retained. | Capture selected date, filters, response HTML and complete rendered DOM. Ranking tables are supplemental views, not additional attempts. Browser success does not prove login was required or absent. |
| Secondary publications: [Sportalsub Wakayama DYN export](https://www.sportalsub.net/en/wp-content/uploads/2025/07/DYN-RESULTS.pdf), [Venezuelan federation Athens PDF](https://www.fvas.com.ve/wp-content/uploads/2025/05/Resultados-CMAS-Freediving-Indoor-Atenas-2025.pdf), [Sportalsub World Games resultbook](https://www.sportalsub.net/blog/wp-content/uploads/2025/08/Full-Results-Freediving-WG-2025.pdf) | PDF mirrors | Acquired as supporting copies. The [official World Games PDF](https://www.cmas.org/document/2025,-the-world-games/download.html) was acquired; the [Tissot resultbook landing](https://swog2025.theworldgames.org/nh/en/Pdf/Resultbook) was visible, but its official zip/download returned 403 or no permission in observed clients. | Record publisher and `:mirror-of`; do not claim independent corroboration, equivalent bytes or a verified before/after revision. The Athens PDF materially disagrees with current JSON. |

For Microplus, inspect the schedule, unit results and document API responses before selecting a PDF. Record the competition, discipline, category, round and heat. In Athens, `CGR1` is Results; do not double-count Summary, responsive mobile rows, or start-list exports. The 42 archived views contain 792 original JSON positions. One DNF senior-women response has an invalid UTF-8 byte at position 19: retain its original bytes and quarantine that position, without guessing a name or recoding the file. The B30 parser imported 791 positions, plus one earlier parser version for the same 30-row source. These counts do not establish event-wide completeness or distinct sporting attempts.

## Register evidence, not a filename

Use the [archive manifest contract](../README.md#manifest-contract) and its synthetic fixture. Before registration, retain the HTTP request/discovery URL, each redirect and final URL, retrieval time, status, MIME, byte length and SHA-256. Verify a PDF starts with `%PDF-`; a `download.html` suffix or `.pdf` filename is insufficient. Save original successful PDF, JSON or HTML response bytes in the ignored private archive. Keep failed 403/404 bodies and headers in private acquisition evidence rather than registering them as result sources.

The manifest requires `:sha256`, `:discovery-url`, `:final-url`, `:acquisition-method`, `:retrieved-at`, `:content-type`, `:publisher`, `:relationship` and `:mirror-of`. Use optional `:provenance` for `:publisher-url`, full `:redirect-chain` and browser state. For AIDA, retain the selected date/filter and a separately hashed, complete rendered DOM with `freediving.archive/retain-evidence!`; compare it with result-bearing response cells. The earlier truncated DOM captures were hash-valid but incomplete, so later complete captures remain separate acquisition versions. For Microplus, keep meaningful URL fragments and supported query context; do not strip them to satisfy URL validation. Never save credentials, cookies or session-bearing URLs.

`clojure -M:archive import ARCHIVE SOURCE MANIFEST.edn` returns a content SHA-256 and acquisition ID. `clojure -M:archive inspect ARCHIVE SOURCE_SHA256` verifies the object and returns its private `:artifact-path` and every acquisition manifest for that content. A source hash identifies bytes; an acquisition ID identifies a particular retrieval and context. Repeated identical imports skip; distinct timestamps, redirects or selected views may create distinct acquisitions for the same bytes. Neither changed hashes, `-v2` filenames, parser versions nor retrieval order alone prove a publisher revision or a distinct sporting attempt. Preserve possible and confirmed relationships separately under [revision evidence rules](revision-relationships.md).

## Trace one observation back to its source

Select the **isolated** database and its matching private archive first. The database contains immutable extraction artifact bytes and candidate payloads, but a dump alone does not contain the original source object archive. With an exact `JOB_ID` and zero-based observation `ORDINAL`, set `psql` variables `job_id` and `ordinal` using `-v`, then use a read-only SQL session:

```sql
BEGIN READ ONLY;
SELECT o.job_id, o.ordinal, o.candidate_id, o.kind, o.payload_edn,
       e.source_sha256, e.artifact_sha256, e.parser_version,
       e.schema_version, e.artifact_bytes
FROM freediving.observations AS o
JOIN freediving.extractions AS e USING (job_id)
WHERE o.job_id = :'job_id' AND o.ordinal = :ordinal;
ROLLBACK;
```

The public project API `(freediving.observations/inspect jdbc-url job-id)` returns the stored artifact and ordered observations; `clojure -M:observations inspect JOB_ID` is the CLI form. Match the exact ordinal and candidate ID, then verify the stored artifact bytes against `artifact_sha256` and its `source-sha256` against `source_sha256`. The archive's `derivations/JOB_ID.edn` receipt points to the immutable derived artifact. In the matching archive, run `clojure -M:archive inspect ARCHIVE SOURCE_SHA256`; inspect each returned acquisition manifest for discovery/final URLs, redirects, publisher, selected context and acquisition ID. Match the artifact's retained acquisition snapshot to those IDs, since the archive can hold later acquisitions of the same bytes. Verify the returned `:artifact-path` bytes against `SOURCE_SHA256`. Database `inspect` does not itself replay the source parser; the importer's unchanged retry does. See [local PostgreSQL APIs](../README.md#local-postgresql) and [private source inspection](local-source-inspection.md) for authenticated row viewing and replay checks.

Read `:coordinates` from the exact candidate in `payload_edn` or the inspected artifact. Schema 5 JSON uses `:row-index-zero-based` in the original `CGR1` array, not a visible page number. PDF schemas 1-3 use 1-based `:page` and `:line` in extracted text; retain rendered page evidence when line order is ambiguous. Schema 4 AIDA HTML uses 1-based `:table` and `:row`, paired with selected date/filter and retained table cells. `ordinal` is the database candidate order and is not interchangeable with a JSON array index. Cite the human HTML page for JSON plus its exact response URL and position; cite PDF page/line or AIDA date/table/row for those sources.

| Isolated corpus at the 25 September snapshot | Registered/imported source membership | Row/version meaning and private navigation |
| --- | --- | --- |
| B11 `championship_b11`; B16 `championship_b16` restore of B11 | B11: 24 AIDA acquisition versions, 20 CMAS 2025 depth timing PDFs, two 2025 federation CWT PDFs, seven 2026 Roatan depth PDFs and two 2026 Novi Sad indoor PDFs. B16 adds two parser-2 jobs over those same indoor PDFs. | B11 has 55 extraction versions and 4,736 observations; B16 has 57 and 5,384. These are versioned rows, not unique attempts. See [corpus audit](championship-corpus-20260925.md) and private `data/b11-evidence/OPERATIONS.md`, `import-manifest.json`, `review-index.jsonl`, and B16 `data/b16-evidence/OPERATIONS.md`, `review-index.jsonl`. |
| B30 `championship_b30_4x50`, separate Athens corpus | Forty-two distinct official `CGR1` response hashes, 792 source positions: 791 candidates and one byte-quarantined position. | Forty-three extraction versions and 821 versioned observations include a second parser version of one 30-row source. See [JSON ingestion audit](cmas-2025-json-ingestion.md) and ignored `data/b30-2025-4x50/` archive, ledgers and backup. Athens JSON is absent from B11/B16 and production. |
| Croatian Open 2026, separate private held-out candidate corpus | One official CMAS-hosted PDF, SHA-256 `e57141f79dfe38d12aaab20d04ad44d506a60ba0e1209968beb6f5037d0bf615`. The Chrome `View` route supplied the PDF; the retained macOS download provenance names that route and its CMAS detail page. | Parser `cmas-croatia-open-2026/1` preserves 246 result rows across eight pages with exact source fields and page/line evidence. All 246 imported as one unreviewed extraction version; unchanged extraction and import retries skipped. The ignored `data/croatia-2026-source/` archive and database and `data/croatia-2026-audit/` ledger are local private evidence. This corpus has no owner labels, selected identity cases or publication approval. |

Private paths above identify packet locations in their retained batch checkouts; they are not Git files or portable paths. B32 Roatan CWT-men evidence is a separate **source-gap audit** of unit JSON and 403s, not an imported replacement corpus; see [source gaps](championship-source-gaps-20260925.md#roatan-cwt-men-coverage). No listed isolated corpus is production; acquisition and import do not grant review, identity, selection or publication authority.

## Portable private corpus bundles

`python3 scripts/portable_corpus.py export SPEC.json BUNDLE` copies an explicit file map into `BUNDLE/payload/` and writes `BUNDLE/index.json`. Keep the spec and bundle in a private destination outside managed worktrees. The index records portable paths, role, byte count and SHA-256 for every file, plus corpus metadata. It does not retain original absolute source paths. A directory entry includes every regular file recursively, including PDFs, JSON, HTML, rendered DOM evidence, acquisition manifests, derivations, row ledgers, review records and a custom PostgreSQL backup when those are supplied. Empty directories are retained. Symlinks, path traversal, duplicate paths, missing files and changed bytes are rejected. The exporter never removes or changes the source files.

Example private spec shape (replace paths, hashes and metadata with the exact corpus being exported):

```json
{
  "schema": "portable-corpus-spec/v1",
  "corpus": "isolated-example",
  "entries": [
    {"source": "/absolute/private/archive", "path": "archive", "role": "acquisition-and-extraction-archive"},
    {"source": "/absolute/private/review", "path": "review", "role": "append-only-review-evidence"},
    {"source": "/absolute/private/database.dump", "path": "database/database.dump", "role": "postgres-custom-backup"}
  ],
  "archive_roots": ["archive"],
  "receipt_paths": ["review/owner-decision-receipt.json"],
  "references": [{"path": "review/source.json", "sha256": "replace-with-64-hex-source-hash"}],
  "metadata": {
    "scope": "exact isolated corpus, event, session and acquisition coverage",
    "database_format": "pg_dump custom",
    "restore_command": "createdb NEW_ISOLATED_DB && pg_restore --no-owner --no-acl --dbname=NEW_ISOLATED_DB database/database.dump",
    "import_notes": "Record parser version, source hashes and job IDs; replay only into an isolated database"
  }
}
```

`archive_roots` checks that every acquisition manifest names a bundled source object under `objects/<sha256>` and every derivation receipt names a bundled artifact under `derived-objects/<artifact-sha256>`. `receipt_paths` checks a JSON owner receipt's `source` and `result_files` references against bundled bytes. `references` asserts other exact path/hash relationships, including source and artifact hashes cited by row evidence. These checks run again on `verify` and before `restore`. Keep a separate trusted copy of the printed `index_sha256` so a changed index is detectable; SHA-256 is an integrity check, not an author signature.

Run `python3 scripts/portable_corpus.py verify BUNDLE` after copying the bundle. Run `python3 scripts/portable_corpus.py restore BUNDLE FRESH_TARGET` to copy verified payload bytes to a new, nonexistent directory. The restore checks the copied bytes before reporting success and sets private directory/file modes to `0700`/`0600`. The restored `archive/` is then usable by the existing archive and observation CLIs. Restore the database dump with the recorded command into a **new isolated** PostgreSQL database, then compare database extraction jobs, observation counts and review decisions with the retained ledgers. Neither a successful bundle restore nor a database restore approves identity, result selection or publication. Do not include a live PostgreSQL data directory, credentials, cookies, session URLs or raw sources in Git.

## Detailed evidence

[Dated championship inventory](championship-inventory-20260925.md), [CMAS timing acquisition](cmas-timing-acquisition-20260925.md), [Athens JSON ingestion](cmas-2025-json-ingestion.md), [AIDA HTML ingestion](aida-html-ingestion.md), [source gaps](championship-source-gaps-20260925.md), and [Athens mirror audit](athens-mirror-audit-20260925.md) contain the full reconciliations and known limitations. Keep private names, raw bytes, manifests and row ledgers under ignored `data/`; record future acquisition and coverage work in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8) or [issue #10](https://github.com/jamiepratt/freediving-results/issues/10), not a repo-local plan file.
