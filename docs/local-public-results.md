# Local public results

The public interface reads only sanitized, eligible projections through a restricted PostgreSQL reader. It provides source-name search, visible-data filters, pagination, persistent result pages and histories containing only active approved identity links. This is local readiness for [issue #1](https://github.com/jamiepratt/freediving-results/issues/1). No real pilot observation is approved by creating the synthetic demo.

The [live public site](https://poc.alphacompose.com) showed 81 records on 28 September 2026. This partial pilot includes four separately validated 2026 CMAS Novi Sad DYN-BF junior-men rows from the official PDF's page 10, lines 9-12. The other 77 existing result IDs and both approved history links were retained. The four new rows have unresolved athlete identities; distinct attempts, other sessions, publisher revisions and possible overlap remain unverified. The visible count comes from the API and does not establish a complete ranking. Remaining championship coverage is tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Run the synthetic demo

Requires Java 17+, Clojure CLI and PostgreSQL 17 tools on PATH. From the repository:

```sh
export PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH
scripts/setup-public-demo.sh data/public-demo 55487
export FREEDIVING_PUBLIC_DATABASE_URL='jdbc:postgresql://127.0.0.1:55487/public_demo?user=reviews_public'
clojure -M:public 8785 --synthetic-demo
```

Open `http://127.0.0.1:8785`. Setup requires a new private directory and an empty dedicated database. It creates invented source documents, ingests immutable observations, records explicit synthetic review and extraction-validation decisions, then refreshes public projections. These synthetic decisions do not count as real owner-reviewed cases. Preserve the private setup receipt when inspecting or revoking demo decisions. Never run this seed against the real pilot.

Stop the foreground HTTP process with Ctrl-C, then stop only its cluster:

```sh
scripts/local-postgres.sh stop data/public-demo/postgres17
```

Restart the retained cluster with `scripts/local-postgres.sh start data/public-demo/postgres17 55487`, then run the HTTP command again. Do not rerun setup against retained data.

## Reading results

Search matches original and effective source-name text, including validated results whose identity remains unresolved. Filters use available public federation, discipline, category and event-date values. Missing metadata is shown explicitly. An event representation code is not a citizenship claim. Coverage counts only currently visible records and never establishes complete event or athlete coverage.

Result pages retain source tokens, original extracted fields and effective approved values separately. Approved corrections and reversals include reasons, timestamps and public result evidence. PDF citations use page/line; HTML citations use event header, selected date, table/row, publisher URL and source hash. The HTML document title is labelled separately from the event name. The server does not redistribute source documents or raw HTML. Athlete history uses source names, without inventing a canonical name, and includes only current approved links among eligible records. A validated source name with unresolved identity is not an approved athlete history. The [anonymous correction workflow](local-corrections.md) adds a form when a separate restricted submission capability is configured.

## Public comparison availability

`/?comparison=2026-pool-dnf-women` and the matching `/api/results` query show comparison availability using the same current eligible public projection. Requests without this optional parameter retain their existing API response shape. The closed target excludes malformed dates, junior categories and unknown category evidence. CMAS senior-women source labels remain source labels; the public AIDA projection does not expose the gender and comparable category proof needed to enter this target.

The panel separates all published pilot source records from target source records and eligible comparison peers. National, Continental and International ranks are withheld, sporting peer denominators and distinct sporting attempts are unknown, and links lead to published target source records rather than ranked peer lists. Search filters narrow displayed source records; counts describe the fixed target. Projection readback time is separate from the unknown publisher evidence coverage cutoff. Zero eligible peers does not establish zero dives or absent competitions. No private packets or sporting authority are consumed by this route. Full comparison acceptance remains tracked in [issue #194](https://github.com/jamiepratt/freediving-results/issues/194).

Result summaries show the original publisher placing first, labelled as a source field. Sporting finality is not inferred; effective approved corrections remain visible separately.

## Read boundary and invalidation

The read-only configuration receives only `FREEDIVING_PUBLIC_DATABASE_URL`; optional anonymous submissions use a separate restricted capability as documented in the [correction guide](local-corrections.md). Do not supply ingestion or reviewer credentials to that process. Startup rejects elevated, owning or broadly privileged database roles. HTTP binds only to IPv4 loopback, checks Host and Origin, accepts read-only routes plus explicitly configured correction submissions, bounds and validates queries, serves fixed assets, and returns generic errors with restrictive security headers. Pages load no external scripts, fonts or images. Public text renders as text rather than HTML.

Each data request reads the restricted public view. There is no application response cache; responses use `no-store`. Changes to review or publication decisions invalidate the whole projection snapshot immediately at the database boundary. Ineligible, revoked and absent records share the same unavailable response. A trusted reviewer must revalidate affected rows when needed and refresh projections separately. See [publication policy](publication-policy.md). A page already displayed is not a live subscription; navigation and reload read current eligibility.

The bounded pilot is read into memory for search and filtering. Stable result IDs identify exact immutable observations, not a guarantee of permanent visibility. Source evidence coordinates refer to extracted text lines, not PDF bounding boxes. Local PostgreSQL trust authentication allows other local processes to impersonate roles; this synthetic setup is for a trusted development machine. Keep both local listeners on loopback and do not tunnel them. Production hosting uses separate deployment configuration; remaining corpus review and coverage are tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Verification

```sh
clojure -M:test-public-ui
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-public-server
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-public-demo
```

The UI contract tests require Node.js. HTTP and demo tests use isolated disposable PostgreSQL clusters. Full database regression runs through `scripts/test-postgres.sh`; standalone archive/extraction regression remains `clojure -M:test`.

## Public sporting comparison

`/comparison` and `/api/comparison` expose the bounded 2026 pool DNF women comparison. They read the separate `public_sporting_comparison` projection through the restricted public database role. Installing migration 22 creates an empty sporting authority ledger and one scoring-policy event. It publishes no sporting attempts or owner decisions. The source-record API remains independent.

Trusted database-owner preparation rejects unknown fields and binds each cited fact to the exact public result, source and extraction artifact digests, hashed observation version and ordinal. Selected source view and its attempt/result kind, finality, source authority, final post-penalty value, scoring policy, same-attempt relationship, comparable category, source gender, represented country, listing and sanction are separate facts. The public adapter uses hashed aliases for the internally exact source reference; original job and candidate identifiers are withheld. The first eligible cohort must contain both CMAS and AIDA before a user narrows federation or representation. Equal values receive competition ranks. Official event placing remains a separate publisher field; DQ achieved-value hypothetical scores never become achieved ranks.

Source publication does not supply sporting authority. Genuine owner/source decisions and a current withdrawal bridge into the independent public fact ledger remain required for real publication under [issue #194](https://github.com/jamiepratt/freediving-results/issues/194). Snapshot checks on the public database's local canonical tables do not establish the current state of the separate private canonical database. Actual sporting authority is therefore initially unavailable, with zero eligible peers and no ranks.

List filters are `comparison`, `federation`, `representation`, `sanction_scope` and `listing_filter`. They are independent of source-name, date and source-category search filters. Detail URLs carry an authority token; peer URLs carry a token binding the full filters, policy, authority revision and exact ordered peer descriptor. Current policy, publication/review corrections or reversals, selection/relationship drift, sporting withdrawal, changed source bodies or changed local derived state invalidate those URLs. Every HTTP response has `Cache-Control: no-store`. Coverage cutoff and projection read time are separate fields; source positions, extraction versions, explicitly distinct attempts and eligible peers have separate denominators.

The isolated positive HTTP/browser fixture uses invented CMAS/AIDA sources and the actual restricted SQL view:

```sh
FREEDIVING_TEST_SERVE=1 scripts/test-postgres.sh test-public-sporting
```

The process prints its loopback comparison URL. Interrupt it to stop the HTTP server and remove only its disposable PostgreSQL cluster. This fixture is not a production authority import path.
