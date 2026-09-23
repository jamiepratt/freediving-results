# Local public results

The public interface reads only sanitized, eligible projections through a restricted PostgreSQL reader. It provides source-name search, visible-data filters, pagination, persistent result pages and histories containing only active approved identity links. This is local readiness for [issue #1](https://github.com/jamiepratt/freediving-results/issues/1). No real pilot observation is approved by creating the synthetic demo.

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

Result pages retain source tokens, original extracted fields and effective approved values separately. Approved corrections and reversals include reasons, timestamps and public result/page/line evidence. Publisher citations describe acquisition provenance; the server does not redistribute archived PDFs. Athlete history uses source names, without inventing a canonical name, and includes only current approved links among eligible records. The [anonymous correction workflow](local-corrections.md) adds a form when a separate restricted submission capability is configured.

## Read boundary and invalidation

The read-only configuration receives only `FREEDIVING_PUBLIC_DATABASE_URL`; optional anonymous submissions use a separate restricted capability as documented in the [correction guide](local-corrections.md). Do not supply ingestion or reviewer credentials to that process. Startup rejects elevated, owning or broadly privileged database roles. HTTP binds only to IPv4 loopback, checks Host and Origin, accepts read-only routes plus explicitly configured correction submissions, bounds and validates queries, serves fixed assets, and returns generic errors with restrictive security headers. Pages load no external scripts, fonts or images. Public text renders as text rather than HTML.

Each data request reads the restricted public view. There is no application response cache; responses use `no-store`. Changes to review or publication decisions invalidate the whole projection snapshot immediately at the database boundary. Ineligible, revoked and absent records share the same unavailable response. A trusted reviewer must revalidate affected rows when needed and refresh projections separately. See [publication policy](publication-policy.md). A page already displayed is not a live subscription; navigation and reload read current eligibility.

The bounded pilot is read into memory for search and filtering. Stable result IDs identify exact immutable observations, not a guarantee of permanent visibility. Source evidence coordinates refer to extracted text lines, not PDF bounding boxes. Local PostgreSQL trust authentication allows other local processes to impersonate roles; this setup is for a trusted development machine. Keep both listeners on loopback and do not tunnel them. Production authentication, hosting and genuine corpus approval remain separate checkpoints in issue #1.

## Verification

```sh
clojure -M:test-public-ui
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-public-server
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-public-demo
```

The UI contract tests require Node.js. HTTP and demo tests use isolated disposable PostgreSQL clusters. Full database regression runs through `scripts/test-postgres.sh`; standalone archive/extraction regression remains `clojure -M:test`.
