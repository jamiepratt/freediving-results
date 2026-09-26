# Local synthetic owner review

This interface exercises the existing review and extraction-validation APIs for [issue #1](https://github.com/jamiepratt/freediving-results/issues/1). The demo configuration accepts explicitly synthetic observations only. The five invented rows are not pilot owner reviews. A separate [real-source inspection configuration](local-source-inspection.md) supports private PDF pages and defaults to read-only access. The separate [public interface](local-public-results.md) reads eligible sanitized projections.

## Create a demo

Requires Java 17+, Clojure CLI and PostgreSQL 17 tools on PATH. On this Mac:

```sh
export PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH
scripts/setup-owner-demo.sh data/owner-demo 55485
```

Choose an unused PostgreSQL port and a new directory. Setup creates an isolated loopback cluster, restricted ingestion/reviewer/public roles, database `owner_demo`, the existing migrations and two synthetic text sources containing five rows. It refuses existing directories and databases; it never resets a prior pilot or demo. No observations are approved, and no public projection is prepared. A setup failure may leave the private cluster running; the stop command below applies to that directory only.

## Start and sign in

```sh
export FREEDIVING_OWNER_DATABASE_URL='jdbc:postgresql://127.0.0.1:55485/owner_demo?user=reviews_owner'
clojure -M:owner 8784 "$(pwd -P)/data/owner-demo/owner-capability"
```

Open `http://127.0.0.1:8784` in the local browser. The capability file is created with mode 0600 beneath the mode-0700 demo directory. Read it locally and paste its contents into the password field. On macOS, copying it without terminal output is possible with `pbcopy < data/owner-demo/owner-capability`. Clear the clipboard afterward. Do not put the capability in a URL, message, screenshot, log or Git. An actor label is audit metadata, not authentication.

Startup refuses elevated/owning database roles, original-record write privileges, missing review capabilities, non-synthetic observations, unsafe capability paths and existing capability files. Use the exact loopback URL: alternate hostnames and remote origins are rejected. Each login replaces the previous session. Sessions expire after eight hours; signing out invalidates the session. Restarting generates a new capability. After an ungraceful process exit, confirm the server is stopped before removing its stale capability file.

## Review flow

Browse or filter cases, then compare source tokens, parsed values and current approved values. The original values never change. Candidate signals are suggestions, not proof of identity or independent corroboration. Event representation is not citizenship. Evidence references show exact PDF page/line or HTML table/row coordinates and source/version identifiers. The text demo does not contain PDFs. The separate source viewer renders registered PDF pages or shows verified HTML context and cells as inert text; extraction line coordinates still do not provide PDF geometry.

Prepare a reasoned proposal using a supported scalar field or explicit identity outcome. An identity match references a selected registered observation and its evidence. Unknown and no-match remain distinct. Needs-more-evidence leaves a case unreviewed, or can be the reason for an explicit rejection. A proposal changes nothing until approved. Approve, reject and reverse are separate actions retained in the private audit.

When a versioned Jev run is configured with `:jev-run-root` and `:jev-provider-id`, identity review shows the complete stored probability distribution, both original source records, uncertainty, source dependence and exact score identity. Inspect both registered source versions and acknowledge that inspection before proposing a match, no-match or unknown outcome. The proposal binds the two citations and exact run, case and result hash. Approval checks the stored score and current source versions again; stale or conflicting links require a fresh inspection. A score never approves an identity. The synthetic text demo has no Jev run, so its identity form has no selectable score. Owner decisions retain before and after values, evidence, actor, reason, revision and database time. Reversal restores the prior effective link and public history after separate revalidation and projection refresh.

Extraction validation is a separate form. Both visual-accuracy and absence-of-substantive-error attestations start unchecked. Supply them only after inspecting the source and context. Parser success and identity approval cannot supply those attestations. The demo's text evidence is synthetic; it does not validate a real PDF. An unreadable row demonstrates blocked validation. Revocation retains earlier events and removes current eligibility.

Schema-5 CMAS CGR1 JSON observations also support a private, extraction-only acceptance through `freediving.reviews/accept-extraction!` or `clojure -M:reviews accept-extraction REQUEST.edn`. The request cites the retained HTML Results page and original zero-based JSON row position, plus exact job, observation ordinal, candidate, source hash, artifact hash, and parser version. The reviewer role records an append-only event with the owner receipt hash and response reference. `extraction-effective` and `extraction-history` read this separate audit; `revoke-extraction` appends a reversal. The source bytes retained in the artifact are checked against the source hash and the cited row. Rows without retained valid JSON cannot use this path. These events do not approve identity, source relationships, completeness, public selection, or publication. The publication validator still requires its separate source-accuracy and substantive-error attestations under an active policy.

Revision conflicts display an error and reload control. Reload current state before making a new decision. A retry after uncertain network failure reuses the exact request ID and contents. Changing a request requires a new ID. Review decisions invalidate earlier extraction validation until explicitly revalidated. The UI does not prepare or serve public projections.

## Visitor requests

The private correction queue retains original visitor claims and append-only triage. Dismiss with a reason, or inspect the observation and create a normal registered-evidence proposal before linking it. Approval remains a separate explicit action. See [anonymous correction setup and limits](local-corrections.md).

## Stop and restart

Stop the foreground HTTP server with Ctrl-C. Its shutdown hook removes the capability file. Then:

```sh
scripts/local-postgres.sh stop data/owner-demo/postgres17
```

To resume, preserve the database and restart it, then run the same HTTP command:

```sh
scripts/local-postgres.sh start data/owner-demo/postgres17 55485
```

Do not rerun setup against an existing directory. To start a fresh demo, choose a new directory and unused port.

## Trust boundary

The server binds only to IPv4 loopback. It checks an exact Host and Origin allowlist, authenticates a random local capability, uses an HttpOnly SameSite session cookie and per-session CSRF token, accepts mutations only as same-origin JSON POSTs, limits request bodies to 64 KiB, and serves only fixed assets and database-scoped evidence. No permissive CORS, external resources or general filesystem routes are provided. Private responses use no-store and a restrictive content security policy. Untrusted values render as text.

This is trusted-local development authentication, not production owner authentication. Another local process running as the same OS user can read the capability, and the development PostgreSQL helper uses loopback trust authentication. Do not tunnel, reverse-proxy or expose either listener. Production authentication, TLS and multi-user operation need separate work under issue #1.

## Verification

```sh
clojure -M:test-owner-ui
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-owner
```

The UI request-contract test additionally requires Node.js on PATH (verified with 22.15.1). It runs several JavaScript assertions inside one Clojure test assertion. The HTTP tests use a disposable isolated PostgreSQL cluster and synthetic fixtures. They exercise authority, origin/CSRF, request limits, immutable evidence, explicit review/validation decisions and conflict/retry behavior. Existing regression tests remain available through `clojure -M:test` and `scripts/test-postgres.sh`.
