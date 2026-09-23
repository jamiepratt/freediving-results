# Local anonymous corrections

Visitors can request a correction from a visible result page without an account. A suggested change, explanation and evidence citation/reference are required. The receipt identifies a private request pending owner review. No request, duplicate, triage event or proposal changes published values. This synthetic local workflow supports [issue #1](https://github.com/jamiepratt/freediving-results/issues/1); it does not approve the real pilot or deploy a site.

## Run both interfaces

Create a new synthetic public demo with the current setup script. Existing demo directories are never reset. Choose unused ports:

```sh
export PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH
scripts/setup-public-demo.sh data/corrections-demo 55488
export FREEDIVING_PUBLIC_DATABASE_URL='jdbc:postgresql://127.0.0.1:55488/public_demo?user=reviews_public'
export FREEDIVING_SUBMIT_DATABASE_URL='jdbc:postgresql://127.0.0.1:55488/public_demo?user=corrections_submit'
clojure -J-Dsun.net.httpserver.maxReqTime=10 -J-Dsun.net.httpserver.maxRspTime=10 -M:public 8786 --synthetic-demo
```

Open `http://127.0.0.1:8786`. In a second terminal, start the private synthetic owner interface against the same database:

```sh
export FREEDIVING_OWNER_DATABASE_URL='jdbc:postgresql://127.0.0.1:55488/public_demo?user=reviews_owner'
clojure -M:owner 8787 "$(pwd -P)/data/corrections-demo/owner-capability"
```

Open `http://127.0.0.1:8787`; copy the private capability file locally into its password field as described in [owner access](local-owner-review.md). Never put it in a URL or log. The public process has only the restricted reader and separate submission capability; it never receives reviewer credentials. Omitting `FREEDIVING_SUBMIT_DATABASE_URL` retains a read-only public interface without a form.

Stop both foreground processes with Ctrl-C, then run `scripts/local-postgres.sh stop data/corrections-demo/postgres17`. Restart the retained cluster with `scripts/local-postgres.sh start data/corrections-demo/postgres17 55488` before restarting the servers. Do not repeat setup over retained data. Current setup includes additive, checksummed migration 5; existing older demos need an explicit database-owner migration through `freediving.corrections/migrate!` and a new restricted `corrections_submit` role before enabling forms or the owner queue.

## Visitor and owner flow

The form preserves Unicode text and treats markup and references as untrusted text. Suggested changes are limited to 1,000 UTF-16 code units; reasons and citations to 2,000 each. Evidence may be a concrete document/page/row reference or an HTTP(S) URL with reference text. URLs are inert: neither server fetches them. URL credentials, query strings, unsafe schemes and fragments other than `#page=N` are rejected. There are no uploads, file-path reads, email fields or visitor accounts. Free text is not a credential scanner; the form asks visitors to omit secrets and contact details.

Requests bind the displayed immutable result and current validation version. Hidden, absent, revoked and stale targets share an unavailable response. Submission locks the relations that govern visibility before checking the current view; a concurrent revocation either follows an already accepted request or prevents acceptance. Later revocation does not delete a private request. Values and version tokens come from one database snapshot. Pages already displayed require reload to see later changes.

A retry with the same ID and fields returns its receipt. Changed contents cannot reuse an ID. Identical content from the same socket client and result version returns the original receipt even with a new ID. An uncertain browser retry preserves its ID/body; editing fields creates a new ID. Once the target is no longer visible, even an old retry returns unavailable. Receipts contain no submitted text, and submission uses POST bodies rather than URL query parameters. There is no public request lookup or queue.

The capability-authenticated owner sees a paginated private queue with original claims, inert references and append-only triage history. Dismiss requires an owner reason. To pursue a request, inspect its observation, prepare a normal proposal using registered source coordinates and an independently written reason, then link that proposal from the queue. A visitor URL is never automatically registered evidence. Linking checks that the proposal addresses the same immutable observation. Approval or rejection is a separate existing review action with revision checks. An approved correction still requires explicit extraction revalidation and trusted projection refresh before public visibility returns; the owner UI does not refresh projections. See [publication policy](publication-policy.md).

## Authority and abuse bounds

The public reader retains SELECT on the sanitized public view only. Startup rejects private table/column grants, owning/elevated roles and executable application SECURITY DEFINER functions. The separate submission role has no table reads or writes and can execute only the restricted submission/version functions. Their fixed search paths and visibility checks confine writes to immutable request records and bounded rate buckets. Public and submission URLs must address the same loopback database. The reviewer can read requests and append triage, but cannot change request originals. Database owners remain trusted; reviewer SQL is an administrative capability.

All HTTP POSTs require exact same-origin Origin, JSON content type and a custom request header. There is no permissive CORS. JSON must be a flat object with exactly the allowed string fields; duplicate keys, nested containers, trailing data, invalid UTF-8 and bodies above 16 KiB are rejected. Responses are no-store with restrictive CSP. Text is rendered without HTML interpretation.

Persistent PostgreSQL limits permit five new requests per socket client per hour and 100 globally per hour. Rate-limited valid attempts count toward the saturated global counter; exact retries and duplicate content are exempt. Invalid or unavailable requests do not create records or rate buckets. There are at most 1,000 client buckets plus one global bucket, reclaimed after expiry, 10,000 lifetime requests and 100 triage events per request. Reaching storage capacity refuses new requests and requires operator planning under issue #1; no history is automatically deleted. Rate state survives HTTP/database restarts.

Client identity is a SHA-256 digest of the socket IP, retained privately with requests. It is guessable and is not anonymous against the local operator. Forwarded and X-Forwarded-For headers are ignored. Loopback visitors share one client allowance. No trusted proxy mode exists: do not proxy or expose this pilot. A public deployment needs authenticated database access, reviewed proxy/IP handling, edge abuse limits, retention policy and owner authentication at a separate checkpoint in issue #1.

Public HTTP uses four workers, a queue of 32 and backlog 16. The shown JVM flags bound request/response time to approximately ten seconds using JDK process-wide settings. Set them before any server initializes if embedding this server in another JVM; `start!` also sets them before creating its server. Correction database transactions have a two-second lock timeout and five-second statement timeout. A blocked attempt returns service unavailable without creating a request; retry the same request after the lock clears. Submission uses table locks and a serialized counter for this small pilot. These controls bound ordinary local resource use, not internet-scale denial of service. Local PostgreSQL trust authentication allows other local processes to impersonate roles; keep both database and HTTP on loopback.

## Verification

```sh
clojure -M:test-public-ui
clojure -M:test-owner-ui
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-corrections
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-correction-http
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-owner
```

Tests use isolated synthetic PostgreSQL databases and HTTP listeners. They cover visibility/revocation races, role privileges, retry/concurrency, storage/rate limits, malformed inputs, inert evidence, private triage, existing proposal authority and immutable source records. Synthetic approvals do not count toward genuine pilot review.
