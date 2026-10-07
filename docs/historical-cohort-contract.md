# Dated historical cohort contract

[`scripts/historical_cohort.py`](../scripts/historical_cohort.py) validates and queries a private `historical-cohort/v1` inventory for [issue #195](https://github.com/jamiepratt/freediving-results/issues/195). The shipped contract covers 2024 discovery records and guards the ordinary 2023 private staging entrypoint. A valid manifest records bounded evidence; it does not establish archive-wide or worldwide completeness.

The manifest has these required fields:

| Field | Contract |
| --- | --- |
| `schema`, `cohort_year` | `historical-cohort/v1`, integer `2024` |
| `cutoff` | ISO timestamp ending in UTC `Z` |
| `scope` | `from: 2024-01-01`, `to: 2024-12-31`, nonempty `description` |
| `routes` | Nonempty array of discovered archive, calendar, national or organizer routes |
| `leads` | Array of concrete event leads discovered through those routes |
| `census` | A valid existing `census-evidence/v1` projection with the same cutoff |

Each route requires a unique `id`, `federation` (`AIDA`, `CMAS` or `unknown`), `kind` (`archive`, `calendar`, `national` or `organizer`), exact HTTP `url`, nonempty `selector`, explicit nullable `query`, `checked_at`, `status`, `disposition` and an `evidence` note. `pagination` records a nonempty `method`, the exact `checked_pages` strings and explicit nullable `remaining` scope. Attempted routes require a UTC check time no later than the cutoff. A resolved route must be checked, have at least one exact checked page and have no remaining page scope. A page check can still have an unresolved disposition.

Each lead requires a unique `id`, valid nonempty `route_ids`, `event_name`, explicit nullable `event_date`, `discipline`, `category` and `url`, `unknown_fields`, `source_state`, `disposition`, `evidence` and valid `source_ids`. `unknown_fields` must exactly enumerate the null fields among those four nullable fields. Printed or calendar dates must be in 2024; unknown dates remain null. Evidence notes retain the citation for event context, dates, classifications or unresolved scope. Source links identify retained objects only when present. Route/lead IDs cannot overlap or repeat.

Route `status` and lead `source_state` accept `checked`, `unchecked`, `unavailable`, `unsupported` or `unresolved`. Dispositions accept `resolved`, `unchecked`, `unavailable`, `unsupported` or `unresolved`. `resolved` requires checked evidence and a typed lead `resolution`: `{kind, evidence}`. `kind` is `attempt-results`, `not-attempt-source` or `no-competition-attempts`; `evidence` is a nonempty cited basis. Attempt-result resolution requires retained source IDs and, for each source, cited positions with parsed nonempty raw fields, observation-version references and no unresolved reason. Freeform resolution cannot substitute for this row accounting. A cited nonattempt or cancelled-event resolution can leave attempt classifications unknown. Source objects, raw source positions, parser states, citations and exact observation-version references remain under the existing [census contract](census-evidence-contract.md). The nested census must use 2024 event dates and acquisition times at or before the cutoff. Source, position and version counts remain separate; distinct sporting attempts stay unknown.

```sh
python3 scripts/historical_cohort.py validate PRIVATE_MANIFEST.json
python3 scripts/historical_cohort.py query PRIVATE_MANIFEST.json --route ROUTE_ID
python3 scripts/historical_cohort.py query PRIVATE_MANIFEST.json --lead LEAD_ID
python3 scripts/historical_cohort.py query PRIVATE_MANIFEST.json --status unchecked
python3 scripts/historical_cohort.py gate PRIVATE_MANIFEST.json --year 2023
python3 scripts/historical_cohort.py ingest PRIVATE_MANIFEST.json PRIVATE_2023_CENSUS.json PRIVATE_STAGE.json --year 2023
```

`query` preserves matching leads and their retained source positions/version citations. Route and lead filters must name existing records. A status query selects matching route/lead states and keeps the discovery route context for matching leads. It is a query result, not a completeness assessment.

`gate` exits 3 when closed and 0 when open; invalid evidence exits 2. Every nonresolved route/lead, unknown field on an attempt-result lead, census gap, source provenance gap, and unparsed, quarantined or unresolved position closes ordinary 2023 staging. A census gap marked `checked` remains a gap: checking its search does not resolve a missing result. Missing manifests, missing dispositions, empty route inventories and malformed references are rejected. The implementation provides no human exception or override flag.

`ingest` calls the same gate before opening the candidate census. When open, it validates that every candidate event belongs to 2023 and writes a private stage outside the repository using 0700 directories and a 0600 file. An existing output directory must already be private. It never writes a live source, canonical or public database. The public Python `ordinary_ingest` entrypoint applies the same gate. Its stage is not extraction approval, athlete identity approval or publication authorization. This entrypoint stages private evidence only; existing legacy importers are not universally intercepted. Existing raw parsers remain callable for explicit bounded historical format prototypes; those parser calls do not import an ordinary year cohort.

The contract tests use synthetic URLs, fields and events. They verify fail-closed behavior and query/stage semantics, not retained source accuracy or genuine owner acceptance. Retained sample verification is separate evidence.

## Bounded 2024 selected-view staging

`historical_aida_stage.py` stages an explicitly selected 2024 AIDA HTML view:

```sh
python3 scripts/historical_aida_stage.py PRIVATE_SOURCE.html PRIVATE_RECEIPT.json PRIVATE_STAGE.json --expected-source-sha256 EXPECTED_SHA256
```

It replays `issue55_aida_selected_html.build`, verifies the expected original hash and active selected date, and retains every row, raw cell, original HTML and exact receipt. `historical-aida-selected-html/v1` binds private observation versions to original hash, parser version and row position. Parsed positions receive versions; malformed positions remain unresolved without versions. These are private JSON versions, with zero database imports. Category, finality and distinct attempts stay unknown; all rows stay unreviewed.

The bounded selected-view adapter also accepts observed numeric modern
`/StartList/[0-9]+` Results URLs with query/fragment context retained. Its
single empty-ID `table.table__data[id=""]` must retain `tbody#body_ajax`,
recognized 11/12-column headers and the active selected date. URL recognition
does not establish Results semantics for every StartList. Exact source/receipt,
hash, table ambiguity, date and raw-cell guards still apply. Existing
EventPage/EventResults outputs and parser version remain stable; the
[7 October modern-source report](historical-six-midaugust-events-20261007.md)
records actual acquisitions, source-specific private versions and limitations.

Every stored view is reverified against its retained original and receipt before addition or replay. Exact replay leaves stage bytes and mtime unchanged. Altered source bytes, receipt bindings or staged fields fail closed, including an altered payload whose internal digest was recomputed. Output must be outside the repository, with private directories/files; stage symlinks are rejected. Serialize calls for a given output. Receipt/path bindings are immutable, so reacquisition requires a separate stage. The private external index/checkpoint binds the stage file itself; its internal digest alone is not an external trust anchor.

This adapter accepts 2024 selected dates only. The ordinary 2023 `ingest` entrypoint remains gated above. Existing legacy raw parsers remain independently callable for explicit bounded format prototypes. Neither path supplies owner review, public eligibility or a human exception. [The dated Kaunas report](historical-kaunas-2024-views-20261007.md) separates raw positions, private staged versions and database imports.
