# Lessons from 2025-2026 ingestion

Reviewed 2026-10-02 against code and retained reports at commit
`1521ac20f6961a9bae915d1c4f73019d8b6347cc`. This is a historical synthesis, not a
fresh web census or an independent replay of private databases. Source documents
below hold the exact receipts and run cutoffs. Future implementation belongs in
[issue #62](https://github.com/jamiepratt/freediving-results/issues/62);
the dated census remains [#55](https://github.com/jamiepratt/freediving-results/issues/55).

## What the passes established

| Observed pass and evidence | Lesson for ingestion |
| --- | --- |
| September CMAS index sweep: 15 linked 2025 routes and 14 linked 2026 routes. [Inventory](../2025-onward-source-inventory-20260926.md) | Index coverage is a bounded lead count, not event/session completeness. Follow timing shortcuts and child result views. |
| FIPSAS calendar passes: month responses, event pages, unlinked `Classifiche` labels, wrong-year links and image PDFs. [Source guide](../source-acquisition.md) | Calendar discovery and actual result acquisition need separate dispositions. Event date cannot be inferred from page publication or the year of the index. |
| VDST and FEDAS national/cup protocols included mixed sports and repeated standings. [Inventory](../2025-onward-source-inventory-20260926.md) | Classify source sections; a PDF need not be entirely freediving or entirely individual results. Preserve exclusion and repeat evidence. |
| AIDA national/organizer indexes led to selected-day EventPage and legacy EventResults tables. [Census contract](../census-evidence-contract.md) | The URL alone does not identify the result view. Capture selected date, table structure and actual response representation. EventRanking is separate aggregate evidence. |
| FFESSM daily and ranking PDFs used both publisher hosting and official links to Drive. [Census contract](../census-evidence-contract.md) | Follow officially linked hosting routes and retain redirects. Preserve daily and ranking scopes independently, even when fields correspond. |
| Vestico discipline queries and Noxygen's application bootstrap exposed results outside simple PDF links. [Source guide](../source-acquisition.md) | Browser/application investigation can reveal reusable deterministic routes. Query parameters may identify different disciplines rather than duplicates. |
| Camotes scans, Italian Open pages, FIPSAS image sheets and the GIA workbook required visual/cell evidence. [Inventory](../2025-onward-source-inventory-20260926.md) | A downloaded file can remain unsupported. Retain page/region or cell citations; OCR and clipped text cannot supply missing facts. |
| October Microplus acquisition obtained 92 unit responses: 490 transport rows, 279 publisher source positions and 211 identical repeated appearances. [October census](../cmas-microplus-census-20261001.md) | Endpoint row totals overcount publisher positions. Preserve alternate response hashes and JSON pointers, with equality evidence, rather than discarding provenance. |
| Nordic final PDF: 76 rows corresponded to API rows; 16 API daily rows had no PDF counterpart; 43 ranks differed. [October census](../cmas-microplus-census-20261001.md) | One source does not replace another merely because most fields match. Ranking scope is part of the evidence. |

These examples establish usable route families, not federation-wide completeness.
The inspected reports do not establish a systematic census of Asian local-script
name mappings or annual federation/affiliate rankings. Those are explicit parts
of the owner's broader collection scope, with implementation tracked in #62.

## Useful pass boundaries

The history supports distinct kinds of pass within the accepted three stages.
This taxonomy explains the division of work; it does not claim all passes are
implemented or prescribe an execution schedule.

| Stage and pass | Retained output | Why keep it separate |
| --- | --- | --- |
| Discovery: inventory reuse | Existing routes, selected views, source hashes and unresolved leads | Start from retained work; do not rediscover known sources on every run. |
| Discovery: event census | Cited event lists, competition dates and unknown event scope | Events can be known even when results are missing or unpublished. |
| Discovery: result expansion | Child PDFs, API units, selected days, sessions and discipline views | A landing page is rarely the complete result source. |
| Discovery: name evidence | Original spellings, script/language and explicitly published name correspondences | Athlete identity needs more evidence than similar Latin spellings. |
| Discovery: rankings and points | Event/annual scope, category, discipline, printed rank/points and linked rules where available | Aggregate views provide evidence without adding attempts. |
| Acquisition: archive/reuse | Verified bytes, retrieval receipts, view state, reuse decisions and access gaps | Parallel discovery must still share host pacing and avoid redundant transfers. |
| Extraction: supported batch | Parser/version, original fields, exact citations and position accounting | Repeatable computation should run without per-row LLM calls. |
| Extraction: format exceptions | Unsupported/ambiguous scope and tested parser changes or cited unresolved evidence | A few difficult sources should not force LLM processing of the whole corpus. |

Each pass needs a bounded scope and dated accounting. A later pass may discover
new links from acquired documents, so the stages can repeat without losing prior
work. Redundant discovery edges are useful history; identical work is not a new
acquisition or observation merely because another agent found it.

## Existing mechanisms and limits

- `scripts/source_inventory.py` verifies URL, representation, context, complete
  receipts and source hashes before reuse. `acquire_source.py` has explicit
  `--refresh`; reuse is not a fresh publisher check. Local byte integrity and
  publisher freshness are different claims.
- `scripts/source_acquisition.py` provides a shared SQLite host lease for
  pacing across processes. The October Microplus run used five-second pacing;
  this is a dated run setting, not every client's default.
- `scripts/discover_results.py` handles explicitly configured dated HTML/JSON
  indexes. It is not a general web crawler. `issue55_route_roster.py` validates
  2025-2026 routes, including undated leads, but does not fetch or verify originals.
- `src/freediving/extraction.clj` has deterministic PDF dispatch, `supported?`
  predicates and source-hash checks. A heading match can still fail a source guard.
  Recognition and permission to parse must not be conflated.
- AIDA HTML and CMAS JSON have distinct extraction paths. Several image/workbook
  supplements replay retained, manually checked evidence. Deterministic replay
  of such a packet does not demonstrate automatic extraction from a new scan.
- The census snapshot distinguishes retained sources, source positions,
  observation versions, aggregates, gaps and relationships. Successful parsing
  may add evidence positions without importing PostgreSQL observations.

## Failures worth preserving

HTTP 200 was insufficient: some apparent PDF routes returned invalid PDF bodies.
Some early failure receipts omitted response hashes, MIME or bytes, limiting later
diagnosis. A 403 or stale timing shortcut was an access outcome, not evidence that
results did not exist; October timing acquisition recovered previously missing
sources. Access history must remain dated.

Repeated names, repeated pages, national subsets, annual aggregates, re-parsing
and publisher corrections describe different relationships. Count them separately
and retain uncertainty. The archived material already shows raw/final result
differences and daily/final rank differences; field names alone do not settle
semantics. Reconciliation policy remains deferred.

## Evidence quality checks used in the existing work

Hash verification, citation round trips, source-position ledgers, rendered-source
inspection, explicit unresolved rows, unchanged replay, idempotent import and
portable restore were complementary checks. No single successful parser run
established completeness, athlete identity or public approval. See the linked
run reports for which checks actually ran for each source.
