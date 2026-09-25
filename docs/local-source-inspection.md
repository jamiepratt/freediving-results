# Private source inspection

The local owner interface can inspect an explicitly configured real corpus without recording decisions. PDF pages and HTML rows, original tokens, parsed values and effective reviewed values remain private. This supports [issue #1](https://github.com/jamiepratt/freediving-results/issues/1); opening source evidence is not an owner review or publication approval.

## Prepare an isolated corpus

Requires Java 17+, Clojure CLI, PostgreSQL 17 tools, Python 3 and Poppler `pdfinfo` / `pdftoppm`. The renderer uses `/usr/bin/python3` and fixed tool locations `/opt/homebrew/bin` or `/usr/bin`; it does not execute an arbitrary PATH-supplied renderer. Extraction and setup tools still need their documented PATH configuration. Use a trusted local machine. The PostgreSQL helper uses loopback trust authentication, so other local processes can impersonate database roles.

Choose a new private directory and unused ports. Preserve the existing archive and its prior extraction versions. Copy the complete registered archive, including acquisition records, retained evidence, derivation receipts and derived objects. Do not reconstruct provenance from filenames or import arbitrary PDFs through the web interface.

```sh
umask 077
export PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH
mkdir data/source-inspection
cp -pR /absolute/registered/archive data/source-inspection/archive
scripts/local-postgres.sh start data/source-inspection/postgres17 55489
psql -h 127.0.0.1 -p 55489 -d postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE observations_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE reviews_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE reviews_public LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE corrections_submit LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE source_inspector LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE DATABASE inspection_pilot;'
export FREEDIVING_DATABASE_URL="jdbc:postgresql://127.0.0.1:55489/inspection_pilot?user=$(id -un)"
clojure -M:observations migrate observations_app
clojure -M:reviews migrate observations_app reviews_owner
clojure -M:publication migrate reviews_owner
clojure -M:public-results migrate reviews_owner reviews_public
clojure -M -e "(require '[freediving.corrections :as c]) (c/migrate! (System/getenv \"FREEDIVING_DATABASE_URL\") \"reviews_owner\" \"corrections_submit\")"
export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:55489/inspection_pilot?user=observations_app'
clojure -M:observations import data/source-inspection/archive EXACT_EXTRACTION_JOB_HASH
```

Repeat the last command for each selected completed extraction version. Use job hashes from verified archive receipts. Imports verify source hashes, artifact hashes and observation provenance and are idempotent. Do not run synthetic demo setup or seed commands against this database. Setup never requires the prior pilot database to run.

Grant only inspection access in the new database:

```sh
psql -h 127.0.0.1 -p 55489 -d inspection_pilot -v ON_ERROR_STOP=1 \
  -c 'REVOKE CREATE ON DATABASE inspection_pilot FROM PUBLIC;' \
  -c 'GRANT USAGE ON SCHEMA freediving TO source_inspector;' \
  -c 'GRANT SELECT ON freediving.extractions,freediving.observations,freediving.review_proposals,freediving.review_decisions,freediving.publication_decisions,freediving.publication_policy_events,freediving.correction_requests,freediving.correction_triage TO source_inspector;' \
  -c 'ALTER ROLE source_inspector SET default_transaction_read_only=on;'
mkdir data/source-inspection/render-cache
```

## Start and inspect

Write a private, single-form EDN configuration file under the new directory. Replace paths with absolute paths without symlinks; archive, cache and capability directories must be private. The capability file must not already exist.

```clojure
{:mode :real-inspection
 :port 8788
 :capability-file "/absolute/project/data/source-inspection/owner-capability"
 :archive-root "/absolute/project/data/source-inspection/archive"
 :cache-root "/absolute/project/data/source-inspection/render-cache"}
```

```sh
export FREEDIVING_OWNER_DATABASE_URL='jdbc:postgresql://127.0.0.1:55489/inspection_pilot?user=source_inspector'
clojure -J-Xmx512m -M:owner --config data/source-inspection/owner-config.edn
```

Open `http://127.0.0.1:8788` in the local browser. Copy the capability locally into the password field, for example `pbcopy < data/source-inspection/owner-capability` on macOS, then clear the clipboard. Never put its value in a URL, log, screenshot, issue or message. The capability file is mode 0600; sign-out invalidates the session, including subsequent image requests. Use one owner service per browser session: cookies share a host across ports, so signing into a second local owner service requires reauthentication when returning to the first.

Read-only mode hides decision controls and rejects proposal, review, publication and triage POSTs even with a valid session and CSRF token. Startup rejects an elevated role or a read-only configuration supplied with write authority. No review, validation, projection refresh or public release occurs during inspection.

Filter cases and open a comparison. The page viewer provides page navigation and zoom, while the evidence panel retains the selected observation's exact text coordinates and provenance. Moving to another page changes the displayed image context; it does not move the observation. Text line numbers and text-extraction column offsets are not PDF geometry. No row bounding boxes are inferred. Compare rendered glyphs with the exact extracted strings; discrepancies and shared/ambiguous rows remain unresolved.

Schema-4 HTML observations open a separate text inspection panel with the retained event context, selected date, table/row, headers, decoded cells and exact row markup. Markup is displayed as text; publisher scripts, images and other assets never execute or load. This is inspection of the retained evidence, not a reconstruction of the publisher's visual page. The original document title remains distinct from the event header. Missing or ambiguous context cannot be supplied by a URL or inferred from a generic title.

HTML evidence uses 1-based table/row coordinates. Review and validation forms retain those coordinates without substituting page/line numbers. The source endpoint accepts only the exact registered job and ordinal; it rechecks source bytes, artifact identity and parser replay. Loading the exact row in the current authenticated session is required before an accuracy attestation, and the source is checked again on validation. Neither loading the row nor replaying it creates a reviewer decision.

## Deliberate review enablement

When the owner chooses to conduct actual review, stop the inspection server, use the separate restricted `reviews_owner` database role and explicitly add `:review-enabled? true` to the real configuration. A read-only inspector cannot enable review. This changes authority and presents the existing proposal and separate decision/validation controls. Capability login and CSRF checks still apply. Do not enable it merely to inspect pages.

The [review rubric](review-rubric.md) and [publication policy](publication-policy.md) still govern evidence and attestations. A successfully loaded source image does not establish accuracy, identity, citizenship or publication eligibility. Genuine decisions must be supplied by the owner. Synthetic test decisions never count toward the pilot's required reviews. The owner interface does not refresh public projections.

## Stop and resume

Stop the HTTP process with Ctrl-C; its shutdown hook deletes the capability. Stop only this cluster:

```sh
scripts/local-postgres.sh stop data/source-inspection/postgres17
```

Resume with `scripts/local-postgres.sh start data/source-inspection/postgres17 55489`, then the same inspection command. Do not repeat setup over retained data. After an ungraceful server exit, confirm it is stopped before removing a stale capability file. Preserve the original archive and database; derived rendering caches are separate private outputs.

## Trust boundary

Only the owner server serves PNG pages and private HTML row JSON; the public server has no archive or inspection route and refuses inspection/reviewer database roles. Requests identify a registered observation and, for PDFs, a bounded page number, never a path or URL. Source bytes and extraction binding are verified before rendering and before serving cached output. Rendering identity includes the source, installed rendering tool and configuration. Corrupt or mismatched cached artifacts are rejected. No remote fetching or external browser assets are used.

This is a trusted-local pilot with private filesystem storage and point-in-time database authority checks, not an internet-facing service or an operating-system sandbox against a malicious local user. Keep both listeners on loopback. Production authentication, expanded corpus coverage, genuine reviews and evaluation remain in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Rendering bounds and cache maintenance

Rendering uses one process slot, a 1,800-pixel maximum edge, a 15-second wall deadline per tool, ten CPU seconds per process and a 12 MiB file/output limit. Source and extraction files are capped at 100 MiB before reading. On macOS, a monitor checks renderer RSS every 50 ms and kills it above 512 MiB; this is a sampled limit, not a hard address-space ceiling. Linux additionally applies a 1 GiB address-space limit. The owner HTTP executor has four workers, 16 queued requests and request/response deadlines of 15/45 seconds. Run with the shown JVM heap limit. These are bounded local processing controls, not a hardened untrusted-PDF sandbox.

The dedicated cache rejects a new render when its existing contents exceed 96 MiB. One in-flight source snapshot, fonts and bounded tool outputs add temporary overhead; 96 MiB is an admission threshold, not a hard total disk quota. A full or corrupt cache requires stopping the owner server and choosing a fresh private cache directory or removing only that dedicated derived cache. Never remove the source archive to clear rendered pages.

Cache identity includes source/page, renderer profile, fixed arguments, Poppler versions, resolved executable hashes and a controlled fallback-font hash. Embedded PDF fonts are retained; missing fonts use the fingerprinted Arial (macOS) or DejaVu Sans (Linux) fallback. The renderer clears inherited environment settings. Dynamic libraries and the OS rasterization stack are outside this fingerprint: use a fresh cache after library-only or operating-system rendering upgrades. Source hashes, extraction binding and cached bytes are reverified on every page request.

When a viewer is configured, extraction validation also requires this authenticated session to have received the exact observation's page image. Metadata alone, another page or a previous session is insufficient. The server rechecks source/render integrity before validation. That prerequisite never replaces the two explicit accuracy attestations and does not record an approval merely by loading the page.

```sh
clojure -M:test
clojure -M:test-owner-ui
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-owner
```

Synthetic regression checks cover authenticated images, read-only mutation rejection, extra database privileges, source/cache corruption, page/path rejection, cache invalidation after a same-version executable change, rendering timeout/concurrency recovery, and explicit source-page/accuracy prerequisites. Browser verification additionally compares real rendered glyphs and ambiguous rows without recording real decisions.
