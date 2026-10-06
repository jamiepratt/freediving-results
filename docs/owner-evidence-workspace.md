# Local owner evidence workspace

The runbook below describes the owner evidence workspace and its guarded
decision API. [ADR 0003](adr/0003-automatic-evidence-reconciliation.md)
accepts automatic reconciliation with later review and rollback.
[Issue #72](https://github.com/jamiepratt/freediving-results/issues/72) records
the private review implementation and validation.

The decision API has a separate append-only SQLite store, verified snapshot
binding, authenticated write routes, and review controls. It is opt-in for a
fresh private origin. On 4 October 2026, the existing custom-domain owner service
was active with 207 pending, scoreless AIDA proposals at decision revision 209,
bound to snapshot `278daebb34b6770cf52baf1cc412148f3384435c09fd113e5ab6267815bf6a71`.
No live owner approval, correction, rejection, or reversal had been submitted.
A trusted local delivery command can send
human events through the signed Access feed to a durable flow ledger and canonical
PostgreSQL callbacks, with a checkpoint for each destination. Source-derived
owner decisions show delivery pending until both destinations acknowledge the
exact event; decisions without a verified route show projection unavailable.
Local overlay counts do not attest that PostgreSQL has caught up. Automatic decisions shown here are never
human review attestations or public publication approval.

## Private attempt comparison

The authenticated `api/attempt-inspector` caller reads an independently pinned
retained packet and JVM runtime. Each request binds exact references and row
coordinates to current sporting authority, packet digest and expiry. Missing,
withdrawn or stale authority clears ranks. The current retained scope is 2026
pool DNF source-labelled women; unsupported depth or discipline filters return
no eligible projection. Source positions, retained versions and unknown distinct
sporting attempts keep separate denominators.

A row detail puts cited official event placing before National, Continental and
International lists. Each list states its eligible-peer denominator, versioned
common-score policy and exact ordered peer IDs. Sport, source category,
representation and review/publication filters narrow the peer population before
ranking. Listing provenance and sanction filters are independent; verified
CMAS/AIDA international sanction remains the default. Represented country comes
from the source row. Unmapped geography has no national or continental rank.
AIDA's source age category remains unknown; the optional `age_class` filter
requires cited verified equivalence and never derives age from gender. Para
classes remain outside the supported broad women cohort.

Exact peer links reuse this endpoint with `peer_anchor`, `geography`, `peer_token`
and all selected filters. The digest binds current authority, sporting values,
cutoff, comparison policy and exact membership. Reopening a changed or tampered
link returns a stale peer view, no rows and zero visible ranks. Pagination retains
the exact link scope; applying new filters exits the peer view. Refresh withdraws
old rows while checking current evidence.

Conflicting source claims can rank provisionally only with a cited exact source
choice. Source authority has priority. Equal-authority administrative choice
supports `exact-reference-lexical-v1`, ordering source hash, source-position ordinal and candidate ID,
then job and artifact hash as administrative fallbacks; competing supplied references remain withheld. This
ordering selects evidence and never breaks a sporting-value tie. Affected peer
lists disclose provisional status and show the selection basis.

Disqualified rows remain outside main ranks and denominators. A separate cited
hypothetical requires exact source-supported achieved performance, precision and
conversion. Its position is computed against eligible valid peers only, under
the same category, sanction, listing and geography gates. It has no position when
no eligible peers exist. It neither asserts a valid post-penalty result nor alters
main ranks. The retained real rows receive no hypothetical without genuine exact
current evidence. Public eligibility and the full first-peer acceptance remain
tracked in [issue #194](https://github.com/jamiepratt/freediving-results/issues/194).

Current human review filters include decisions awaiting canonical delivery;
Human approved and Human corrected exclude approvals invalidated by stale evidence
or inactive prerequisites. Canonical delivery pending remains a separate filter.

To reverse a current approval, choose **Human approved**, click **Inspect** on
the decision, then **Preview Reverse** and **Confirm reverse**. These controls
also work while canonical delivery is pending. A preview writes no event; its
**Review after** shows the saved review change and **After** retains
`projection_pending` until both canonical delivery destinations acknowledge the
new event. Reversal preserves the approval and reversal in history.

To restore an original approval, choose **Reversed**, click **Inspect**, then
**Preview approve** and **Confirm approve**. Restoration requires current cited
evidence and active accepted prerequisites. Historical human corrections cannot
be overwritten by ordinary approval, including after reversal; reversed
corrections currently have no restoration control. A stale snapshot, missing
evidence or inactive prerequisite removes the affected action controls. A
concurrent review requires reloading and previewing again. Neither reversal nor
restoration completes canonical delivery or grants publication approval.

For a trusted local import, run
`python3 scripts/owner_decision_export_adapter.py deliver --decision-db DB --config CONFIG`.
`CONFIG` is a private, owner-owned EDN file with mode 0600 containing the pinned
flow path, exported decisions and exact canonical bindings, Access service
identity, import token and PostgreSQL reviewer connection. The command returns
per-target checkpoints. A retry-required result exits with code 2; rerun with
the same inputs after repairing its reported failed target. Keep this config,
the flow ledger and decision database outside Git. The command does not enable
the production decision API or establish retained-corpus authority.
Each callback compares the complete outbox event with the signed feed or durable
flow event before its target checkpoint advances. A verified reconciliation
export includes the owner store revision observed when it was prepared; registration
rejects an export if a later human action or binding changed that revision.
Callers of `freediving.owner-decision-export/export-proposals` supply that
observed revision as `:store-revision` alongside `:binding-revision`; the export
emits `store_revision` for the private registration adapter. Read the current
owner store revision when preparing the export, including intervening owner
actions, rather than deriving it from the snapshot binding revision.

Run `python3 scripts/owner_evidence_web.py --snapshot-dir PATH` with a verified private unified snapshot directory. Enter a local password at the terminal prompt, then open the printed loopback login URL. The process binds only to `127.0.0.1` on an ephemeral port. Stop it with Ctrl-C.

The workspace reads the immutable SQLite snapshot through `SnapshotQuery`. It provides source counts and dispositions, filters for source, collection, kind, event, printed date span, session, discipline, category and cited federation, plus paged candidate, gap and relationship views. The federation control offers CMAS and explicit unknown; it does not infer federation for other source objects. The overview reports mapped and unknown evidence records and candidate positions separately from accepted distinct attempts. Older snapshots without a federation map remain browsable with all federation values unknown. Versions and candidate versions do not add positions; unresolved overlap or publisher revision direction leaves the accepted count unknown. Source row counts are not attempt totals. Detail keeps the source-object federation claim, publisher authority, source role and mapping citation separate from the record citation, retained packet raw and parsed fields, and source hashes. Pass `--source-bundle-dir` and `--source-bundle-sha256` together to enable record-bound original and safe-derivative inspection from a verified [private source bundle](private-source-bundle.md). Without those arguments, source viewing stays unavailable. Owner review, mutation and publication remain outside this process.

For the 2026-09-28 v7 snapshot, the embedded, hash-verified route roster supplies 15 routes and 83 leads without a separate roster directory. The UI shows 34 checked, 29 acquired and 20 unchecked leads, plus one inaccessible route, as dated coverage states. The six selected-date AIDA packets expose 281 exact positions with date, table and row locators. Their original HTML remains restricted; the local viewer checks and displays only a cited safe packet derivative. The Eindhoven nOxy source has 92 RESULT rows, 58 OVERALL aggregates, 94 endpoint records, 92 exact ID links and two endpoint-only records; included original JSON rows are checked against their exact pointers and raw fields. All these are evidence records, not confirmed distinct attempts. The v7 snapshot and bundle hashes are recorded in [unified-evidence-snapshot.md](unified-evidence-snapshot.md) and [private-source-bundle.md](private-source-bundle.md).

The later 2026-09-28 v8 snapshot has 15,730 evidence records at cutoff `2026-09-28T20:22:00Z`, SQLite SHA-256 `40997d52fc409647f4ab3cbacb8e926abcd5920401aa224f5c73195a1ff89fb3`. Its embedded roster still has 15 routes and 83 leads, now 49 acquired, 34 checked and zero unchecked; one CMAS route is inaccessible. Zero unchecked known leads does not establish global completeness. Eleven additional AIDA selected dates add 116 Mabini and 63 Adriatic source positions. Their original HTML remains restricted and cited safe packets remain derivatives. Nine FFESSM ranking PDFs have 56 cited positions with unknown printed row dates; two daily PDFs have 71 positions. Fifty-six printed-field correspondences are candidate links between daily and ranking rows, with 15 unmatched daily positions. A ranking row is supplemental evidence, not an independent attempt. The Apnea Academy additions are 40 GIA club standings, one placeholder and ten San Mauro 2026 team rows, all excluded from individual attempt counts. Six San Mauro 2025 JPGs add 148 individual positions, 90 combined aggregates, 238 manual observation versions and five positions with clipped fields. The v8 bundle manifest SHA-256 is `ec7ce579e525a54f4920f5e4c615848c4562099266f15191f1b5c5f58df07431`; see [private-source-bundle.md](private-source-bundle.md). No owner review, import, source/attempt/identity approval or publication follows from these records.

The 2026-09-28 Roatan extension is a dated partial census, not a new set of confirmed sporting attempts. It retains two exact timing JSON source objects, 31 CWT-men source positions, 31 observations from parser `/1` and 31 from parser `/2`. The read-only `roatan` list and `roatan/UNIT/INDEX` detail routes work under both `/api/` locally and `/owner-evidence/api/` at the private origin. Detail compares both parser versions and shows the original row citation; five explicit Roatan uncertainties enter the exception queue. The seven historical extraction acceptances apply only to unit 3551 parser `/2` rows at zero-based indexes 0-6. They do not approve parser `/1`, the remaining rows, identity, same-attempt relationships, or publication. The source values keep declared depth, raw `ResResult` (publisher `DEPTH`), `ResResultFinal` (publisher `FINAL DEPTH`), penalty, status, and notes distinct. LU San-Jen is a regression case: declared 95, raw depth 65, final depth 34, penalty 31, and penalty note `EARLY TURN, NO MARKER`. Earlier snapshots remain separately addressable by their original manifests and hashes.

The read-only `/api/queue` view groups explicit gaps, unresolved source revision or same-result relationships, candidate-row uncertainties, aggregate limitations and excluded sources into six evidence categories. Each item has a stable ID, source/row citation, trigger, recorded support and contrary evidence, and explicit unknowns. Calendar-to-source link candidates remain in relationship browse, outside the exception queue: their recorded basis links an event card to a source URL and says cross-source equivalence is unassessed. That evidence alone does not raise a publication or result-revision decision. Filters are `group`, `source_name`, `limit` (1-100), and `offset` (0-100000). Group counts are item counts across the snapshot; candidate-position and confirmed-distinct-attempt denominators are separate, with the latter unknown in the current partial snapshot. Empty groups mean no explicit item was recorded, not that the question is settled. The private origin exposes the same route at `/owner-evidence/api/queue`. Queue rows show source, row, record ID, source object ID, parser and observation versions, and source and input hashes, then open the existing record detail and original-source view when an exact cited original is available. No queue endpoint records a decision.

The read-only comparison list accepts only explicit snapshot relationship record IDs. In the corrected 2026-09-28 snapshot it has 136 retained artifact to imported position links and six calendar-to-source candidates. It offers no arbitrary pair selection. A retained link displays the two independently cited records, source and input hashes, acquisition receipt, parser and observation versions, retained raw and parsed fields, and raw and parsed field differences. The example Belgrade link uses parser versions `/1` and `/2` for one source hash, with no parsed field differences. This is a version comparison, not a confirmed attempt, same-attempt, or identity assertion. Calendar candidates lack two cited row records and explicitly show comparison unavailable. The `comparisons` list has `limit` (1-100) and `offset` (0-100000); `comparison/RECORD_ID` accepts a listed record ID. The private origin serves the same routes under `/owner-evidence/api/`. Original availability is labelled from the verified bundle. For a retained candidate with an exact PDF source-object binding and a matching page/line/column citation, the original viewer opens the cited PDF page. It does not independently replay the extracted line or retained extraction artifact. Missing and restricted originals retain their exact bundle reason, with safe derivatives shown only where supplied.

On 5 October 2026, a separately retained private archive of all 17 AIDA selected-date HTML originals, receipts and safe packet derivatives passed independent restore and offline extraction: 17 of 17 selected views and 460 of 460 cited positions reproduced their pinned packets. Restricted originals remain outside the portable bundle and owner web route. The exact historical Belgrade `/1` extraction artifact, derivation receipt and parser source were recovered from retained archives into a new private bundle. An independent restore replayed the original PDF through both historical `/1` and current `/2` parsers. All 57 positions per version match their cited snapshot records; raw and parsed values agree, while one adjacent-line context field differs. Both versions remain unreviewed. See [private-source-bundle.md](private-source-bundle.md) for hashes and restore paths. No replay creates an observation, sporting attempt, relationship or review decision.

Authentication protects the workspace assets and all private API responses. The login uses an exact loopback Host and Origin, a random HTTP-only session cookie, and a terminal-entered password. Responses use `no-store` and restrictive security headers. Login is limited to five attempts per minute and all requests to 120 per minute. Request URLs, bodies and filters have size limits. The server suppresses access logs to avoid recording tokens or source data. Use only on a trusted local machine; HTTP loopback traffic is not encrypted.

## Production gateway boundary

Status note, 2 October 2026: the [1 October activation report in #54](https://github.com/jamiepratt/freediving-results/issues/54)
records successful authenticated HTML/API checks of the v8 snapshot at
`https://poc.alphacompose.com/owner-evidence`, following merged PRs #58-#60.
The original implementation descriptions below predate that activation. This is
a dated operational report, not a fresh availability check; owner visual review
remained open. New ingestion runs are intended to prepare evidence locally and
present verified snapshots here, with the repeatable handoff tracked in
[issue #64](https://github.com/jamiepratt/freediving-results/issues/64).

The Worker has a separate, default-closed gateway at `https://poc.alphacompose.com/owner-evidence` and its child paths. The gateway is a routing and authentication boundary; the private origin below serves the workspace. The local `scripts/owner_evidence_web.py` server is a loopback demonstration and must not be connected to this gateway or exposed through a tunnel.

The private gateway accepts `GET` and `HEAD` on safe paths beneath that prefix. It checks the `Cf-Access-Jwt-Assertion` header using the Cloudflare Access team JWKS over HTTPS with RS256, then checks exact issuer, configured application audience, expiry, not-before and issued-at times, token type `app`, and an exact owner email allowlist. Cloudflare's [JWT validation guide](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/) requires signature verification in Workers; the [application token reference](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/application-token/) documents the identity email and claims. A service token has no identity email and cannot pass this gate. Requests never use an unsigned identity header or the browser cookie as proof. Invalid or missing configuration returns 503; unauthorized requests return 403. All responses use `no-store`, and private upstream redirects are rejected.

The Worker sends only generated `X-Freediving-Owner-Gateway` and verified `X-Freediving-Owner-Email` headers to a separate HTTPS origin under `alphacompose.com`. It does not forward the client Access assertion, cookies, authorization, public gateway secret, or client-supplied owner headers. The upstream must independently require the private gateway secret and must deny direct access without it. The public origin and existing public routes retain their separate gateway secret.

Activation requires these manual Cloudflare and origin settings, with real values supplied by the owner:

1. Create a self-hosted Cloudflare Access application for **both** `poc.alphacompose.com/owner-evidence` and `poc.alphacompose.com/owner-evidence/*`, with an Include policy limited to the intended owner identity and no bypass or service-token policy. Cloudflare [path rules](https://developers.cloudflare.com/cloudflare-one/access-controls/policies/app-paths/) say a wildcard child path does not cover its parent path, and more specific rules take precedence. Confirm both paths challenge unauthorized browsers before enabling the private origin.
2. Set Worker bindings `ACCESS_ISSUER` to the exact team URL `https://<team>.cloudflareaccess.com`, `ACCESS_AUDIENCE` to that application's AUD tag, `OWNER_EVIDENCE_EMAILS` to a comma-separated lowercase allowlist, `OWNER_EVIDENCE_UPSTREAM` to the exact HTTPS origin of a dedicated private host under `alphacompose.com`, and secret `OWNER_EVIDENCE_GATEWAY_SECRET` to a unique random value of at least 16 characters. No owner email or private host has been selected in this repository. The Worker rejects the public origin and main site as private upstreams.
3. Provision a hardened production owner service at that dedicated origin. It must reject requests lacking its private gateway secret, avoid public caching and sensitive logging, and serve only approved owner data. Test direct origin denial, Access policy coverage, expired and foreign identity tokens, and private response headers on the custom domain before loading data.

`deploy/publish-edge.py` currently installs only the public `GATEWAY_SECRET`. It does not set any private bindings or create Access policy or origin resources. A normal Worker release can therefore install the code while this path stays closed with 503. No deployment or private data publication is part of this gateway change.

### Candidate private origin service

`scripts/owner_evidence_origin.py` is a separate read-only service for a dedicated private HTTPS origin. It is not the local password/session demo above. It binds only `127.0.0.1`, defaults to port 8081, and must sit behind an HTTPS reverse proxy for the exact configured private host. The proxy must restrict its upstream to the loopback listener, preserve the Worker-generated owner gateway and email headers unchanged, reject client-supplied copies, disable caching and access logs containing URLs or headers, and never expose the loopback listener directly. Network controls must allow only the trusted Worker path to the proxy. Run it with a private read-only mounted snapshot directory:

```
OWNER_EVIDENCE_ORIGIN_HOST=<dedicated-private-host>.alphacompose.com \
OWNER_EVIDENCE_EMAILS=<lowercase-owner-address> \
OWNER_EVIDENCE_GATEWAY_SECRET=<unique-random-secret> \
OWNER_EVIDENCE_SNAPSHOT_SHA256=<verified-64-digit-hex-sha256> \
python3 scripts/owner_evidence_origin.py --snapshot-dir <private-snapshot-dir> --port 8081
```

The gateway secret must match the Worker's `OWNER_EVIDENCE_GATEWAY_SECRET`; the host must match `OWNER_EVIDENCE_UPSTREAM`. The pinned SHA-256 must match both the manifest and snapshot bytes. Missing or invalid configuration, manifest or snapshot prevents startup. The service verifies the snapshot hash, then queries SQLite with `mode=ro&immutable=1`. Keep the snapshot mount read-only and replace it only by stopping the process and starting it with a newly verified digest. The service requires an exact private Host, constant-time gateway secret comparison, and an exact allowlisted owner email on every request. It rejects duplicate security headers, request bodies, other methods, foreign Origin, arbitrary paths, login/session/write routes, and requests without the Worker-generated headers. Responses carry `no-store`, a restrictive CSP and other browser security headers; details and filters have bounded routes and responses. It suppresses access logs. The UI is served under `/owner-evidence`, with fixed asset and API paths under the same prefix.

For the cited original viewer, set `OWNER_EVIDENCE_SOURCE_BUNDLE_DIR` to a verified private source bundle and `OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256` to the SHA-256 of its `manifest.json`. Both must be set together. Startup verifies the manifest, all bundle objects, and that the bundle contains the pinned snapshot SQLite bytes. The `source-view/RECORD_ID` endpoint accepts only a snapshot record ID. It checks the source object hash on each read. The view includes bounded screened acquisition receipt fields. A cited PDF page is returned only as a bounded PNG (`source-view/RECORD_ID/page/PAGE`); a cited JSON row is returned only when its exact source array position equals the snapshot raw fields. The older timing response uses `data[index]`; Roatan's archived response is a top-level array cited by unit, zero-based index, and source hash; Eindhoven uses exact JSON pointers for its RESULT, OVERALL and endpoint rows. Microplus v2 positions expose each separately retained JSON original through `?view=0` (primary, also the default) and `?view=1` (alternate when cited). Each view is bound to the snapshot record, exact source hash and URL, JSON pointer, and equal raw row. The detail viewer links both originals when two are cited. A cited San Mauro JPG can be shown only through its record-bound verified image route. A restricted AIDA HTML source offers only its separately hashed, exact-row packet derivative, never original HTML. Neither layout nor source identity is inferred from a mismatched citation. Missing, restricted, unsupported, mismatched, and uncited originals are denied with explicit status. The source viewer has no path or hash browsing, HTML originals, review mutations, or public route. Keep the bundle private and read-only.

The initial implementation did not provision the origin, private host, Access policy, secrets, owner identity, proxy or snapshot mount. For a fresh environment, provision and test those boundaries before activation, then verify direct-origin denial, Worker authentication and identity behavior, response headers, and the actual private host end to end. For the existing environment, consult the dated activation note above and recheck its current configuration. Do not tunnel `scripts/owner_evidence_web.py` or mount its session routes at this origin.

The private presentation-status route accepts a bounded reconciliation receipt only when the owner decision store is configured and its current revision and snapshot match the receipt. The receipt contains the local decision revision, owner store revision, snapshot digest, aggregate decision and gap counts, provider call count, and an optional reviewed-sample numerator, denominator, frame, selection and bias. Accepted athlete and distinct attempt counts and actual monetary cost stay unknown. A newer owner decision revision or active presentation snapshot makes the stored receipt stale on read; the page omits its counts. The authenticated status client can read only the stale receipt revision, run ID and cutoff, then replace it with a newer verified run using compare and swap. The same run cannot overwrite a stale receipt. Local completion and pending transfer do not establish remote activation or publication. The status token cannot write owner decisions.
