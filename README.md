# Freediving results: local evidence and extraction

This slice registers source bytes and acquisition provenance in a private archive, produces versioned PDF extraction artifacts, imports those artifacts into immutable PostgreSQL observations, and records reversible owner review decisions separately. It supports CMAS CWT men, AIDA Wakayama rankings and CMAS Athens distance, STA and speed candidates. All real pilot observations remain unreviewed. Pilot acceptance, identity review and publication scope live in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1), which remains open.

## Local owner review demo

A private loopback web interface now exercises proposals, approval/rejection/reversal and separate extraction validation/revocation on explicitly synthetic data. See [setup, access and trust boundary](docs/local-owner-review.md). No real pilot decisions or public site are created.

## Run

Requires Java 17+ and the Clojure CLI. PDF extraction and its tests also require Poppler `pdftotext` and `pdfinfo` on PATH (verified with 25.05.0). Clojure and dependencies are pinned in `deps.edn`. Archive/extraction commands need no running service; observation ingestion requires PostgreSQL. First run downloads Maven dependencies.

```sh
clojure -M:test
mkdir -p data
clojure -M:archive import data/archive test/fixtures/source.txt test/fixtures/manifest.edn
clojure -M:archive inspect data/archive ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
```

The committed fixture contains three synthetic bytes (`abc`), not a real results document. `/data/` is ignored. Keep real evidence and manifests under ignored private paths. The archive creates its own private root, but its parent directory must already exist. Use a dedicated archive path; do not use your home directory or an existing general-purpose directory. On macOS, `/tmp` and `/var` are symlinks: choose a real path or use `pwd -P` in the intended parent.

Both commands emit one EDN result. `import` returns `:sha256` and `:acquisition-id`. `inspect` verifies stored bytes and records, and returns `:artifact-path` plus `:acquisitions` containing each acquisition ID and its manifest. Errors exit nonzero; the API throws. A known artifact with no acquisition records indicates registration was interrupted before provenance publication; repeat the original import.

## Manifest contract

The example at `test/fixtures/manifest.edn` is the complete schema. Every key is required; extra keys are rejected.

| Key | Required value |
| --- | --- |
| `:sha256` | Lowercase 64-character SHA-256 of the original bytes |
| `:discovery-url` | Observed HTTP(S) page or URL where the source was discovered |
| `:final-url` | Observed final HTTP(S) acquisition URL |
| `:acquisition-method` | Nonblank description of how the bytes were acquired |
| `:retrieved-at` | ISO-8601 timestamp with UTC offset |
| `:content-type` | Observed MIME type, optionally with parameters |
| `:publisher` | Nonblank name of the party publishing the acquired copy |
| `:relationship` | `:publisher`, `:mirror`, or explicitly `:unknown` |
| `:mirror-of` | Identified original publisher or source for `:mirror`; otherwise `nil` |

URLs with user credentials, query strings, or fragments are rejected to reduce accidental credential storage. Do not place secrets, session data, or access tokens in any field or source file. URL paths and free text are not credential scanners. A source that needs a query URL cannot currently be represented faithfully; do not strip meaningful URL information merely to bypass validation.

These declarations record acquisition evidence; validation does not prove a publisher claim or fetch a URL. MIME type is not inferred from the bytes. Mirror links are recorded as supplied, never inferred from URL equivalence. Different acquisitions with the same content hash share one artifact and are not independent evidence.

## Legacy JSON import

`freediving.legacy/import!` and the CLI import a JSON array of acquisition entries, with an explicit evidence directory and a private EDN supplement:

```sh
clojure -M:legacy data/archive /absolute/evidence /absolute/manifest.json /absolute/config.edn
```

```clojure
{:version 1
 :entries {0 {:values {:publisher "Example federation"
                      :acquisition-method "direct HTTP download"
                      :relationship :publisher
                      :mirror-of nil}
              :rationale "Acquisition log identifies publisher and method."}}}
```

Entry indices are zero-based. Supply facts only when supported by recorded evidence. Config entries cannot overwrite observed mapped fields. Unknown indices, unsupported config versions, malformed JSON, or a non-array top level fail the command. An empty config is `{:version 1 :entries {}}`.

The version 1 mapping is `requested_url` to `:discovery-url`, `resolved_url` to `:final-url`, `fetched_at` to `:retrieved-at`, `content_type` to `:content-type`, and `sha256` to `:sha256`. When `requested_url` is absent, `source_url` supplies discovery only. `archived_at`, browser `acquisition`, `authentication`, `source_kind`, and extraction fields remain original evidence; the adapter does not reinterpret them as retrieval timestamps, acquisition methods, or publisher claims. Browser records lacking final URL or MIME remain incomplete. Missing `:mirror-of` is explicit even when an evidence-backed supplement will eventually set it to nil.

Each entry produces `:imported`, `:skipped`, `:missing-fields` with field names, or `:rejected` with a machine-readable reason. Invalid entries do not prevent subsequent entries from being processed. Source files must be regular nonsymlink files beneath the explicit evidence directory; absolute entry paths and parent traversal are rejected. Declared byte counts, when present, and required SHA-256 hashes are verified. Canonical archive validation still rejects unsupported URLs, MIME types, timestamps, or relationships. Generic `:invalid-source-or-manifest` intentionally omits exception contents. CLI stdout contains only IDs, private storage paths, counts and statuses; detailed original fields stay in private lineage.

The archive retains exact manifest and config bytes under `evidence/<sha256>`. Each entry has a deterministic lineage record linking those hashes and its index to the original entry, mapping/version, complete supplement/rationale and normalized fields. Distinct manifest/config bytes produce distinct lineage even when the acquisition is unchanged. All unknown original fields are retained. Result reports are also content-addressed; the returned `:report-path` identifies the persisted report (without its own self-reference). Repeated imports verify evidence integrity and return skipped acquisitions without duplicating objects, acquisition records or identical lineage. Changed provenance creates separate acquisitions sharing content. The existing archive schema is unchanged.

Evidence uses the archive's private atomic storage and cooperating-process lock. Lineage is saved before acquisition registration; interruption can leave pending lineage or an acquisition without a final report. Repeat the same command to complete it. Report counts describe that invocation, not a historical transaction. Storage failures can stop an invocation; malformed top-level inputs have no trustworthy entry enumeration and fail as a whole after original bytes are retained. No rollback or power-loss durability is promised. JSON, config and size checks currently read complete files into memory; this adapter is intended for the bounded local pilot.

## Private PDF extraction

Extraction consumes registered source hashes; it never fetches documents. It uses the installed Poppler `pdftotext` tool and records its version. Keep source PDFs and extraction artifacts in the ignored private archive. This bounded worker reads complete files into memory and does not run OCR.

```sh
clojure -M:extract data/archive SOURCE_SHA256 data/extraction-options.edn
```

The options file is `{:actor "local-owner" :config {}}`. The API is `(freediving.extraction/extract! archive-root source-sha256 options)`. `:config` is retained processing metadata, not a set of tool flags; extraction uses fixed layout and UTF-8 settings. Changing the actor, config, parser/tool version or acquisition/evidence snapshot creates a distinct job. Repeating an unchanged job verifies and reuses its artifact. The receipt contains `:job-id`, `:artifact-sha256`, private `:artifact-path` and `:run-status` (`:created` or `:skipped`).

The private EDN artifact records the original PDF hash, acquisition manifests, retained evidence hashes, processing time and actor, tool/parser versions and config. The evidence snapshot includes all retained legacy evidence in that archive, including original manifest/config bytes and lineage; it is not a claim that every item supports each candidate. Exact tool text remains separate from parsed values, including page delimiters in `:raw-text`.

Extraction serializes cooperating writers with the archive lock. A private pending receipt references the completed artifact before final job publication; retry resumes that artifact after interruption. Verified unreferenced derived objects and recognized staging files are reclaimed under the lock. Corrupt content is rejected. The optional fourth API argument `{:on-progress callback}` receives `:extraction-artifact-ready` after the pending receipt is saved and before final publication; callback failure leaves a resumable job. Guarantees cover process interruption on trusted POSIX storage, not power-loss durability.

The first structured parser targets the 2025 CMAS outdoor CWT men seniors layout. It preserves source names and event representation codes, including `CMAS1` and `AIN`, without assigning citizenship or merging identities. Attempted depth, final depth, penalties, status and notes remain distinct. Missing values remain unknown: a blank status is not an inferred success, and units absent from the PDF are not invented. Extracted text is evidence, not an approved spelling correction.

Artifacts retain exact extracted text by page with 1-based text line coordinates. These are coordinates in the extraction output, not PDF bounding boxes. Other layouts receive `:unsupported-needs-parser`; their result counts remain unknown. Blank text pages receive `:needs-OCR`. Nonblank lines in the supported layout are either classified as headers/footers or retained as parsed/unparsed candidates. Reconciliation records page, candidate, parsed and unresolved counts; it does not establish completeness through automated counts alone.

The versioned AIDA Wakayama DYN export parser retains wrapped name fragments and their exact Unicode spelling, then records joining fragments with spaces as a mechanical extraction repair. It does not remove hyphens, normalize names, infer citizenship from the source's `Nationality` column, or deduplicate equal ranks. Result and announced distances, explicit metre units, points and penalties are separate fields. Zero points do not imply disqualification; absent cards and statuses remain unknown. The browser print timestamp is retained as evidence, not used as the event date.

AIDA artifacts use schema 2 and parser `aida-wakayama-ranking/1`; existing CMAS and unsupported artifacts retain schema 1 and their existing parser identity. Detection runs on extracted text before job lookup, so repeat calls still invoke `pdftotext` but reuse the verified stored artifact. A formerly unsupported AIDA job is retained alongside the new version. AIDA candidates carry all constituent `:source-lines`, raw name fragments, repair records and explicit unresolved reasons; consumers must branch on the artifact schema instead of assuming the CMAS single-line candidate shape.

Section metadata is scoped to each printed ranking. In the inspected 15-page source, the first seven pages explicitly identify DYN and Female; the following eight-page ranking omits those labels, so its discipline and category remain unknown. The private source has 182 candidates (86 in the labelled section and 96 in the unlabelled section), matching independent page rendering and text inspection. All 182 remain unresolved pending owner review and missing metadata evidence. This is extraction reconciliation, not 182 reviewed identity cases. No original PDF, real extracted rows or private reconciliation artifacts are committed.

The Athens parser `cmas-athens-pool/6` uses schema 3 for the 2025 indoor PDF. Its distance families are DNF, DYNBF and DYN: printed date/category, realized and final distances, explicit metre units, representation codes, notes and explicit DQ/DNS statuses. Decimal-comma tokens remain separate from decimal values. Blank statuses and absent penalties remain unknown; distance differences do not become inferred penalties. Header-only continuation pages retain section evidence and require their own table/unit headers. New or malformed section boundaries prevent metadata from leaking into later tables.

Athens reconciliation covers all 67 pages of the inspected source. Unsupported layouts in other inputs still have unknown candidate/parsed/unparsed/unresolved counts and remain preserved as exact text. The inspected source has 162 DNF result rows on pages 1-11, plus two detached `fi` extraction fragments retained as unparsed candidates on page 3. Per-page result counts are 3, 10, 41, 9, 41, 15, 7, 19, 6, 8, 3. All 164 candidates remain unresolved. Rendered pages and an independent row inventory were compared; this is extraction validation, not owner identity review. Extracted names, including the known missing-ligature anomaly, are unchanged.

DYNBF pages 12-22 contain 177 result rows, with per-page counts 4, 10, 41, 13, 46, 16, 9, 19, 6, 9, 4. Of these, 176 are parsed; one damaged multiline name remains an unparsed candidate with both original source lines retained. No name ordering or spelling correction is inferred. Final-only DNS distances use printed column alignment; blank realized distances stay unknown. DOLPHIN KICK and WALL AT START remain notes, with no numerical penalty inferred. Printed page-number footers are classified separately. All 11 pages were rendered and independently reconciled row by row.

DYN pages 57-67 contain 169 result rows, with per-page counts 4, 8, 41, 14, 41, 16, 7, 21, 5, 10, 2. Of these, 166 are parsed. One damaged multiline name on page 58 remains unparsed. The first two page 59 rows remain unparsed because their rank and notes occupy shared cells: each row preserves its own raw values and note fragment, with the complete block in `:group-evidence`. The standalone shared rank is classified as group evidence, not a third athlete. No shared value is assigned to an individual.

The page 61 multiline note is joined with a space only when adjacent note fragments align with the current page's Notes column and the intervening result has no existing note. Exact `:source-lines`, raw `:note-lines` and a `:repairs` entry retain the transformation evidence. The realized-only DQ row preserves an unknown final distance using current-page column evidence. DQ SP OK DIR and DQ EQUIPMENT are explicit notes/statuses, not inferred penalties. All 11 DYN pages were rendered and independently reconciled row by row; printed May 24 date, category and metre units retain source evidence.

The distance families contain 510 candidates: 504 parsed rows and six unparsed candidates (two detached DNF fragments, two damaged multiline names, and two rows with shared DYN cells). All remain unresolved. Multiline candidates add `:source-lines`; consumers must use those constituent lines for coverage instead of counting only the starting coordinate. `:group-evidence` references may overlap row evidence and must not be counted again. Every one of the source's 1,241 nonblank lines is accounted for once. Prior DNF/DYNBF candidates are unchanged; earlier parser artifacts remain under their original jobs.

STA pages 40-48 contain 129 result rows, with per-page counts 1, 7, 42, 43, 7, 15, 3, 7, 4. One damaged multiline name on page 41 remains unparsed, with both source lines retained. The two multiline medal/record notes on pages 45 and 46 retain their constituent lines and explicit mechanical join evidence. Printed May 23 date and category remain scoped to their section. Repeated and skipped ranks remain unchanged; the nonzero final time beside an explicit DQ remains intact.

STA final-time tokens remain exact source strings, separate from normalized durations. These pages print only `Final Result`, without a minutes/seconds legend. Colon components and decimal precision can be retained, but normalized duration and units remain unknown with an explicit unresolved reason. Realized time and penalties are absent and remain unknown. Invalid or ambiguous time notation is retained for review, never guessed. All nine pages were rendered and independently reconciled row by row.

SPEED 8X50 pages 23-30 contain 41 rows (6, 3, 12, 10, 2, 5, 1, 2 per page). SPEED 2X50 pages 31-39 contain 60 rows (5, 6, 15, 14, 3, 8, 3, 5, 1); SPEED 4X50 pages 49-56 contain 54 rows (5, 6, 17, 13, 2, 5, 2, 4). All 25 pages share the same realized/final result columns. Independent rendering confirms the two blank final cells on page 53; realized times are not copied into them.

Speed result tokens retain decimal-only and colon-separated notation, components and fraction precision. Normalized durations and time units remain unknown because no unit legend is printed. Realized and final results remain separate; absent split times and penalties remain unknown. Status-only DQ/DNS rows retain blank result cells, and blank statuses remain unknown. Extracted final-column headings include a `%` token that is not visible in the rendered headings; exact header evidence and explicit ambiguity remain, without interpreting it as a penalty or percentage unit. Rendered name glyph anomalies are recorded privately without altering extracted names.

Every speed row and source line was compared with an independent inventory. Combined Athens coverage is 794 candidates: 787 parsed and seven unparsed, all unresolved. This includes five unparsed result rows and two detached DNF fragments. Every one of the source's 1,241 nonblank lines is accounted for once. Prior distance/STA candidates are unchanged, CWT/AIDA job identities remain unchanged, and earlier parser artifacts remain preserved under their versioned jobs. Ambiguous wrapped/adjacent lines and unrecognized notes remain visible; repeated ranks and listings are not deduplicated. Remaining pilot scope is tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

All real candidates remain unreviewed and publication remains blocked. The review commands below support explicit local owner decisions; no command publishes results. Owner review, additional parsers and pilot acceptance remain tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Public API

### Local PostgreSQL

`freediving.observations/import!` takes a JDBC URL, archive root and completed extraction job ID. It verifies the receipt, artifact SHA-256, extraction identity, source bytes, acquisition records and referenced retained evidence before writing. Schemas 1, 2 and 3 are supported explicitly. An earlier acquisition/evidence snapshot remains valid when the archive later gains additional records. Unknown fields remain in the exact artifact bytes and complete candidate EDN; SQL metadata does not replace source evidence.

An extraction version is a job and its exact artifact. A candidate ID identifies the source hash plus its text positions, separate from the extraction version. Repeated athlete names or ranks at different positions remain separate candidates. These coordinates are extraction text locations, not proof of identity or stable PDF geometry across different text-extraction tools. Parser/config changes append versions. Conflicting content under an existing job fails. Each import commits its extraction and all observations together; concurrent reruns serialize per job and return one created import followed by skipped imports.

`inspect` returns full artifact provenance and observation payloads, including raw text, parsed values, unknown fields, unresolved reasons and mechanical repairs. `list` reports extraction versions. `count` distinguishes sources, versions, unique source-position candidates and versioned observations, with result-row, fragment and unclassified totals. Classification records a reason: known detached Athens `fi` tokens remain fragments, while ambiguous lines without sufficient result evidence remain unclassified. These are ingestion categories, not owner decisions or publication approval.

The checked-in SQL migration has a recorded checksum. Run migrations using a separate database owner; ingestion uses a restricted application role. The application receives SELECT/INSERT only, and triggers also reject UPDATE/DELETE/TRUNCATE on ingestion tables. This protects existing records from ordinary application writes. Database owners/superusers can alter privileges or disable/drop enforcement. Direct INSERT access is not a substitute for the importer’s evidence validation. Role administration, authentication, backups, production migrations and production access control remain separate concerns tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

```clojure
(require '[freediving.observations :as observations])
(observations/import! jdbc-url archive-root extraction-job-id)
(observations/list-extractions jdbc-url)
(observations/counts jdbc-url)
(observations/inspect jdbc-url extraction-job-id)
```

`scripts/local-postgres.sh` starts a dedicated development cluster, bound only to `127.0.0.1`, with no system service registration. Put PostgreSQL `initdb`, `pg_ctl` and `psql` on PATH. The script refuses to start or stop clusters it did not create. Use the same PostgreSQL major version for subsequent starts.

```sh
scripts/local-postgres.sh start data/local-postgres 55480
psql -h 127.0.0.1 -p 55480 -d postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE observations_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE DATABASE observations_pilot;'
export FREEDIVING_DATABASE_URL="jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=$(id -un)"
clojure -M:observations migrate observations_app
export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=observations_app'
clojure -M:observations import data/archive EXTRACTION_JOB_ID
clojure -M:observations list
clojure -M:observations count
clojure -M:observations inspect EXTRACTION_JOB_ID > data/observation-inspection.edn
scripts/local-postgres.sh stop data/local-postgres
```

Run role/database creation once. Restart with the same `start` command; records persist. The helper uses **trust authentication on loopback**, suitable only for a trusted local development machine. Other local processes can impersonate database roles. It is not a production authentication or deployment configuration. Private cluster files inherit owner-only permissions; protect inspection output too (`umask 077`). Never supply a production database to development tests.

`scripts/test-postgres.sh` creates a fresh disposable loopback cluster, runs the PostgreSQL integration suite and stops/removes that cluster on exit. It also requires Python 3 to choose an available port. It never resets an existing database. The ordinary `clojure -M:test` suite remains independent of PostgreSQL. Both suites use synthetic evidence only.

Local verification used PostgreSQL 17.5. The three inspected private artifacts imported as three sources/versions and 1,020 source-position observations: 1,013 parsed result rows, five unparsed result rows and two detached fragments. All three reruns skipped; stored artifact bytes and every candidate payload matched the archive exactly. All 1,020 remain unreviewed, with no identity or publication approval. No real source bytes or rows are committed.

### Local owner review

Review decisions are append-only overlays on one exact `(job-id, ordinal)` observation. The stored extraction artifact and candidate payload never change. Proposals have no effect until approved through a separate reviewer database role. Rejection has no effective value; reversal restores the value and active approval that preceded the reversed approval. History retains proposals, decisions, reasons, evidence, before/after values, actor labels, database role and database time.

Categories are explicit: `:extraction-repair`, `:name-normalization`, `:identity-matching` and `:substantive-correction`. The review API never approves automatically. Identity outcomes are `{:outcome :unknown}`, `{:outcome :no-match}` or `{:outcome :matched :identity-id "local-anchor"}`. An identity anchor is a locally assigned reference, not a verified federation account or inferred person. Each observation has at most one effective identity outcome. Name normalization alone does not assign identity.

Review targets must exist and be classified as result rows. Detached fragments and unclassified material cannot be linked to identities through this API. Legacy evidence references identify existing text page/line positions in the target extraction. Registered cross-source references additionally bind a job, observation ordinal, candidate ID, source hash and artifact hash to a line on that observation's page. Those coordinates establish traceability, not that the proposed claim is true. The reviewer must examine the evidence. Corrections affect derived fields; original names, raw text, parsed values, representation codes and uncertainties remain available through observation inspection.

Each request has a caller-chosen idempotency ID. Repeating the same request returns the saved result; reusing its ID with changed content fails. Requests carry the effective observation revision they were based on. Concurrent changes serialize per observation; stale requests fail instead of overwriting a newer decision. To revise a decision, read the current effective values and revision and submit a new proposal. Reversal is explicit and only applies to a currently active approval, preserving intervening decisions and the full audit trail.

The migration owner, ingestion role and reviewer role are separate. A supplied `:actor` string is an audit label and never authenticates the caller. Database privileges determine who may make decisions. Ordinary ingestion and public database sessions cannot approve, reject or reverse. These APIs and their history are private administrative interfaces, not public response models. Database owners/superusers can bypass enforcement; the dedicated reviewer is trusted to use the validated API. Clojure enforces before-values, evidence, conflicts and reversal eligibility; direct SQL with reviewer credentials can bypass those checks and corrupt the effective projection. Protect reviewer credentials and do not expose them to ingestion workers or a public application.

The local cluster helper uses loopback trust authentication, so another process on the same machine can impersonate a role. This proves role separation in an isolated development database, not production owner authentication. Network login, authenticated review UI and deployment acceptance remain in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1). Local publication filtering is described below.

After creating the restricted reviewer role, apply migration 1 and then migration 2 as the database owner. Both are checksummed. Rerunning the observation migration resets the ingestion role's table grants, so rerun the review migration afterward to restore review access.

```sh
psql -h 127.0.0.1 -p 55480 -d observations_pilot -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE reviews_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;'
export FREEDIVING_DATABASE_URL="jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=$(id -un)"
clojure -M:observations migrate observations_app
clojure -M:reviews migrate observations_app reviews_owner
export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=observations_app'
clojure -M:reviews propose data/proposal.edn
clojure -M:reviews effective data/target.edn
export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=reviews_owner'
clojure -M:reviews decide data/decision.edn
clojure -M:reviews history data/target.edn
```

`target.edn` is `{:job-id "EXACT_EXTRACTION_JOB_HASH" :ordinal 0}`. Ordinals are zero-based, while evidence page and line numbers are one-based. A proposal has this shape, using the actual before value and revision from `effective`:

```clojure
{:id "proposal-unique-id"
 :job-id "EXACT_EXTRACTION_JOB_HASH" :ordinal 0 :base-revision 0
 :category :identity-matching :field :identity
 :before {:outcome :unknown}
 :after {:outcome :matched :identity-id "owner-assigned-local-anchor"}
 :evidence [{:page 1 :line 1}]
 :actor "proposer-label" :reason "Specific evidence and reasoning"}
```

For a field correction, use `:category :substantive-correction`, an existing scalar parsed field as `:field`, and its exact `:before` and proposed `:after`. Nested values and adding absent fields are outside this bounded API. Unparsed result rows can receive identity decisions but cannot receive scalar corrections where no parsed field exists. A normalization proposal targets `:source-name`. Evidence validity does not establish a normalization or correction as accurate.

An approval request is `{:id "decision-unique-id" :proposal-id "proposal-unique-id" :action :approve :base-revision 0 :actor "owner-label" :reason "Why the evidence supports this decision"}`. Rejection uses `:action :reject`. A reversal uses `:action :reverse` and `:event-id "decision-unique-id"` instead of `:proposal-id`, with the current revision and a new ID. Rejected proposals cannot later be approved; submit a new proposal. Every decision advances the revision, including rejection, even though rejection leaves values unchanged.

The Clojure API has the same maps: `freediving.reviews/propose!`, `decide!`, `effective` and `history` each take a JDBC URL followed by the request or target. `migrate!` takes the administrator URL, ingestion role and reviewer role. `effective` returns `:revision`, `:identity`, `:fields` and per-field `:active` approval IDs. Read history together with observation inspection for exact source evidence. CLI commands emit EDN and exit nonzero on invalid requests.

Run synthetic PostgreSQL ingestion and review checks in a disposable cluster (the second command runs only review checks):

```sh
scripts/test-postgres.sh
scripts/test-postgres.sh test-reviews
```

All review verification uses synthetic observations. The three real pilot sources still have zero owner-reviewed identity/correction cases. Synthetic approvals do not count toward the required 50 reviewed cases, and no review command authorizes publication.

### Private candidate review packets

Candidate retrieval reads immutable observations without creating proposals or decisions. `freediving.candidates/load-corpus` takes a JDBC URL and configuration; `packets` takes that corpus and pagination/configuration options. `packet` selects a case using an exact `{:job-id ... :ordinal ...}` reference. Retrieval preserves source spelling and derives versioned Unicode case/diacritic and token-order comparison keys. A shared long token is only a weak, ambiguous signal. Missing parsed names abstain. CMAS1/AIN, matching names and source representation never establish citizenship or identity.

One packet groups a source document and comparison-name key, retaining all original observations and versions. This groups repeated listings for review; it does not assert they describe one person. Equal source hashes share a document group even across acquisitions. Distinct hashes are document counts, not independent corroboration. Reciprocal packets are views of the same candidate pair, not independent reviewed cases.

Packets record exact source/acquisition hashes, parser and processing provenance, observation versions, original values, page/line evidence, uncertainties, configuration and deterministic IDs. `:unknown`, `:no-candidate`, `:candidate` and `:ambiguous` are retrieval outcomes. No-candidate is bounded by this corpus and algorithm; it is not an owner no-match decision. The [review rubric](docs/review-rubric.md) is implementation guidance awaiting owner review, not reviewed labels or calibrated confidence.

```sh
# data/packet-request.edn: {:offset 0 :limit 50 :max-observations 10000}
export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=observations_app'
clojure -M:packets export data/packet-request.edn data/private-packets
```

The export creates private machine-readable EDN and escaped offline HTML. Use a dedicated output directory beneath an existing private parent. Pagination reports the total and whether more cases remain; candidates within each case are retained completely. The corpus bound fails explicitly rather than returning partial retrieval. IDs include the corpus snapshot and comparison configuration, so adding observations changes packet IDs. Keep these administrative artifacts private.

Local verification on the three imported sources produced 488 comparison buckets from 1,020 observations: 48 candidate, 144 ambiguous, 289 no-candidate and seven unknown packets. There are 148 unique unordered candidate pairs, including 89 across source documents. Of the packets, 232 group repeated listings and 35 retain AIN/CMAS1 representation values. These are retrieval coverage counts, not reviewed cases, people, or independent corroborations. All real observations remain private and unreviewed; the required owner-reviewed count is still zero.

A candidate's local anchor identifies an exact observation, not a verified person. For new source-backed identity proposals, copy its reference into `:identity-target` and `:evidence`, and use its `:identity-id` in the matched outcome. The reference shape is `{:job-id ... :ordinal ... :candidate-id ... :source-sha256 ... :artifact-sha256 ... :page ... :line ...}`; the ID is `local-observation:JOB-ID:ORDINAL`. The review API validates that anchor against a named result row and retains it even on rejection. Legacy local identity strings and target-only evidence remain compatible. Evidence validity does not imply approval; the owner must supply a current revision, before value, reason and explicit decision through the review API.

### Local extraction validation and public reads

The [publication policy](docs/publication-policy.md) defines eligibility independently of identity review. `freediving.publication/diagnose` reports automated blockers for an exact `{:job-id ... :ordinal ...}`. Readiness is not approval. `decide!` requires an explicit reviewer decision, exact provenance, current revisions, source-row evidence and visual-accuracy/substantive-error attestations. No parser result automatically creates a validation. Validation/revocation history is append-only; original blocked publication flags remain immutable.

`freediving.public-results` provides read-only `results`, `search-source-name`, `result`, `athlete-history` and `coverage` APIs, each taking a database URL first. Search uses the exact original source name, including when identity is unresolved or an approved name correction exists. Public result and identity IDs are opaque. Missing and private result IDs both return nil; history and coverage count visible results only.

The restricted public reader can select only the sanitized public view. A trusted reviewer explicitly calls `refresh!` to prepare the eligible corpus. Any review or publication decision invalidates that snapshot; affected observations require revalidation, followed by another refresh. Reversals therefore hide stale corrections and identity history immediately. A correction without publicly available evidence withholds its result, including transitive dependencies. Public records contain source citations, allowlisted original/raw/effective values, approved/reversed correction audit, explicit unknown fields and partial-pilot coverage. Archived PDFs, private proposals and processing records remain private.

Create `reviews_public` as a restricted login role, then apply migrations 1 through 4 in order as the database owner. Existing review examples show creation of `observations_app` and `reviews_owner`.

```sh
psql -h 127.0.0.1 -p 55480 -d observations_pilot -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE reviews_public LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;'
export FREEDIVING_DATABASE_URL="jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=$(id -un)"
clojure -M:publication migrate reviews_owner
clojure -M:public-results migrate reviews_owner reviews_public
export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=reviews_owner'
clojure -M:publication diagnose data/target.edn
# Only after actual source review and explicit evidence-backed validation:
clojure -M:public-results refresh
export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:55480/observations_pilot?user=reviews_public'
clojure -M:public-results list
clojure -M:public-results coverage
clojure -M:public-results search-source-name 'Exact source spelling'
```

The public CLI emits EDN; errors omit database details. Run the synthetic PostgreSQL checks with `scripts/test-postgres.sh`, or select `test-publication` / `test-public-results`. This is local readiness with trusted reviewer credentials and loopback development authentication, not a deployed public service. All real pilot observations remain private, with zero publication validations and zero owner-reviewed identity cases. Scope and genuine review remain in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

### Archive registration

```clojure
(require '[freediving.archive :as archive])
(def manifest (archive/read-manifest "test/fixtures/manifest.edn"))
(archive/register! "data/archive" "test/fixtures/source.txt" manifest)
(archive/inspect "data/archive" (:sha256 manifest))
```

An acquisition ID is SHA-256 over the UTF-8 canonical EDN manifest (sorted keys). Map ordering does not affect identity. Repeating the same manifest is idempotent and does not rewrite existing artifacts or records. Changing any provenance field creates another acquisition; this preserves the supplied acquisition descriptions, including timestamp spelling, rather than attempting semantic deduplication.

An optional fourth argument to `register!`, `{:on-progress callback}`, observes `{:phase :artifact-ready :sha256 ...}` after verified artifact publication and before acquisition publication. Callback exceptions interrupt registration; retrying the same input completes it. The callback runs while the archive lock is held and must not call archive operations recursively.

## Storage and recovery guarantees

The archive contains `objects/<sha256>`, `acquisitions/<acquisition-id>.edn`, a `.lock`, and a `tmp/` staging directory. Object hashes and acquisition IDs are suitable opaque references for later database records or Agent-o-rama work. Archive metadata is not the results database.

Registration streams bytes to a private staging file, verifies that staged hash against the manifest, forces the file contents, and atomically renames it. Provenance is published in a separate atomic step. A process-wide lock and OS file lock serialize cooperating readers/writers, including separate JVMs. Interrupted writes cannot publish a partial file. Retrying removes recognized private staging remnants and completes missing provenance. Existing corrupt content or altered provenance causes failure; import never silently overwrites it. Inspection also verifies integrity and rejects unknown hashes.

Directories are created with POSIX `0700`; files with `0600`. Existing archive entries with group/other permissions are rejected. Parent traversal, symlinks anywhere along archive/source paths, nonregular files, malformed manifests, and mismatched hashes are rejected. Use a POSIX local filesystem supporting atomic rename and file locks. The archive assumes a trusted owner and trusted parent directory; it is not protection against an adversarial process changing filesystem paths concurrently. Hard links and ACL-based access are outside this POSIX-mode check.

Recovery is tested across abrupt JVM termination and concurrent processes. This is a process-interruption guarantee, not a power-loss or filesystem-failure durability guarantee: parent directory entries are not fsynced. Restore/backups, VPS transfer, and production recovery acceptance remain outside this slice in issue #1.

## Development checks

```sh
clj-nrepl-eval --discover-ports
clj-paren-repair src/freediving/archive.clj test/freediving/archive_test.clj test/freediving/test_runner.clj
clj-kondo --lint src test
clojure -M:test
```

Tests use synthetic bytes/PDFs, temporary directories, Poppler, and local JVM subprocesses. Extraction checks include abrupt termination, concurrent calls, changed processing metadata, corrupted artifacts, malformed lines, unknown dates, Unicode/representation preservation, bounded REPL printing and CLI validation. No network sources, credentials, or private evidence are used.
