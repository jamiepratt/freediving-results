# Local publication eligibility and public projections

This implementation is a local readiness boundary for [issue #1](https://github.com/jamiepratt/freediving-results/issues/1). It does not deploy a site or approve the real pilot corpus. All real observations remain private; synthetic decisions do not count as owner-reviewed pilot cases.

The isolated [2025-2026 championship acquisition](championship-inventory-20260925.md) retains source provenance, browser-state evidence and automated AIDA row reconciliation. Those checks are not reviewer attestations. It does not activate a policy, import historical approvals, replace events or authorize publication. HTML inspection/review and optional policy-2 publication are supported. Real review, source gaps and event cutover remain tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

The [2025 CMAS depth extraction](cmas-timing-acquisition-20260925.md#batch-4-geometry-backed-continuations) retains 352 parsed source rows across 20 PDFs, including 20 geometry-backed continuation rows. New `cmas-2025-depth/2` jobs supplement preserved `/1` artifacts; retained `cmas-women-depth/1` jobs remain unchanged. All are unreviewed and publication-blocked. Source replay and automated reconciliation supply no visual-accuracy attestation. These extraction batches added no observations, real reviews or policy activation.

The [2026 CMAS depth extraction](cmas-timing-acquisition-20260925.md#batch-5-2026-depth-reconciliation) adds 180 parsed rows across seven PDFs using `cmas-2026-depth/1`, preserving earlier unsupported jobs. Raw values, category/date context, medals, records and geometry remain source-bound; unknown units and blank statuses stay unknown. Missing CWT-men sources remain explicit gaps. All new rows remain unreviewed and publication-blocked. No review approval transfers to them, and this parser change activates no policy or database migration.

The [2026 Novi Sad distance extraction](cmas-timing-acquisition-20260925.md#batch-6-2026-indoor-distance) retains 412 distance rows across senior, junior and printed master categories. Geometry-backed schema-2 jobs preserve the older junior-only artifacts. Blank finals, zeros and source notes remain distinct. Its historical version leaves 236 STA/speed rows unsupported; the [new timing version](cmas-timing-acquisition-20260925.md#batch-7-2026-indoor-sta-and-speed) retains those rows with explicit source ambiguities. All distance rows remain unreviewed and publication-blocked; reconciliation supplies no visual-accuracy attestation or inherited approval. No policy activation or database migration is included.

Historical `cmas-2026-indoor-time/1` artifacts carry artifact-wide `source-semantics-unresolved`. The [version-2 audit](indoor-semantics-audit-20260925.md) confines that warning to all 236 timing rows in new, separately replayed artifacts. The 412 independently headed distance candidates remain identical and can pass automated readiness; no validation transfers. STA's printed metre headings conflict with timing-shaped values; speed 2X50/4X50 has an unlabeled first result column. Exact strings and notation remain uninterpreted. All timing rows remain blocked. Existing policy and historical artifacts are unchanged.

## Separate decisions

[Result revision relationships](revision-relationships.md) use separate append-only proposals and reviewer decisions. A confirmed replacement or acknowledged missing predecessor is not publication validation or identity approval. Migration 8 alone does not alter this policy or suppress public projections. Migration 10 adds separate [reviewed event selection](event-selections.md), with exact validation IDs, explicit coverage and reversible cutover. Real replacement decisions remain gates in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

Extraction validation establishes whether a particular source row can be represented faithfully. Identity review establishes whether an observation belongs to an approved local identity. An extraction validation never creates an identity link. A validated row with unknown identity can appear under its exact original source name.

Policies `extraction-publication/1` and `extraction-publication/2` apply to an exact extraction job, artifact hash, candidate ID, source hash and observation ordinal. Policy 1 retains the PDF contract; policy 2 adds HTML evidence. Only the explicitly active policy can validate or expose rows. Validation and revocation append immutable events. The original extraction's `:publication {:status :blocked}` and all original bytes and candidate values remain unchanged. That parser flag records the extraction-time state, not the later validation decision.

The reviewer must inspect the cited source row and its context. A validation request attests to source visual accuracy and absence of unresolved substantive extraction errors. Parser success, reconciled counts, or an identity approval alone cannot supply those attestations. Actor labels and free-text reasons are audit metadata; database capabilities determine authority.

| Check | Rationale |
| --- | --- |
| Parsed `result-row` with a source name | Fragments, unclassified text and unparsed rows cannot safely represent a result. |
| Discipline and a retained performance or explicit result status | A number without its discipline is insufficient context. DNS/DQ and other explicit non-finish statuses can legitimately have blank performance cells. |
| Registered source citation and row coordinates | Publisher/URL/hash and source page/line must trace the observation to its exact extraction. Validation evidence must include the row itself. |
| No substantive parser error or invalid field | Unknown error codes fail closed. Ambiguous names, merged cells and ambiguous headings require resolution, rather than an implicit override. |
| Explicit visual-accuracy and substantive-error attestations | Automated checks cannot discover every glyph or layout error. A reviewer must inspect evidence, including known external reconciliation findings. |

Dates, units, category, status, penalties, cards and identity may remain unknown when the source does not establish them. Unknown units never authorize conversion to seconds/metres; time notation remains syntax only. Source representation remains distinct from citizenship. Missing discipline still blocks publication even when the parser correctly reports that absence. Policy readiness means that automated prerequisites pass, not that the human attestations have been supplied or verified.

Every decision binds a policy version, publication revision and review revision. Exact retries return the existing event; changed requests cannot reuse its ID. Competing decisions serialize per observation. A new review decision, including a rejection or reversal, invalidates the previous extraction validation until explicitly revalidated. This is deliberately conservative. Validation of one extraction version never approves another version. Revocation retains the earlier event but removes current eligibility.

`diagnose` returns `:ready?`, `:eligible?`, machine-readable `:reasons`, both revisions, policy version and exact `:observation` provenance. `diagnose-many` evaluates supplied targets in one private database snapshot. No diagnostic writes a decision. Preserve invalid/unparsed/error-bearing artifacts; resolving those structural blockers requires a new extraction version. A scalar correction does not erase their original parser error flags.

After genuine source review, a decision file has this shape. Copy provenance and revisions from the current diagnostic; the evidence must include that row's actual page/line. These placeholders are not runnable approvals:

```clojure
{:id "unique-validation-id"
 :job-id "EXACT_JOB_HASH" :ordinal 0
 :base-revision 0 :review-revision 0
 :policy-version "extraction-publication/1"
 :observation {:job-id "EXACT_JOB_HASH" :ordinal 0
               :candidate-id "EXACT_CANDIDATE_ID"
               :source-sha256 "EXACT_SOURCE_HASH"
               :artifact-sha256 "EXACT_ARTIFACT_HASH"}
 :action :validate
 :actor "reviewer-label"
 :reason "What source evidence was checked and why this row is faithful"
 :evidence [{:page 1 :line 1}]
 :attestations {:source-visual-accuracy true
                :no-unresolved-substantive-errors true}}
```

Use `clojure -M:publication decide REQUEST.edn` with reviewer credentials. `history TARGET.edn` returns the private audit. Revocation uses a new ID, current revisions, `:action :revoke`, an explanation and row evidence; `:attestations {}` is allowed. All request fields remain required; unexpected fields and trailing EDN forms are rejected.

For HTML under policy 2, replace the evidence with the exact `{:table 1 :row 2}` coordinate from the current observation and use its current diagnostic policy. These are 1-based document-table and table-row indices, including header rows. Do not supply invented PDF coordinates. Cross-observation review evidence additionally includes the exact job, ordinal, candidate, source hash and artifact hash. New captures or parser jobs never inherit either kind of approval.

HTML evidence replays the immutable parser against retained source text and verifies its source hash, extraction identity and observation binding. Event context comes from a unique retained heading or event-branding image alternative text, never from the document title alone. Comments, including stale commented-out headings, are ignored. Missing or ambiguous context remains a blocker. Selected dates and retained filters remain separate evidence; a source hash does not establish capture completeness. Historical truncated DOM captures are not repaired by later acquisitions.

Policy 2 permits explicitly partial HTML row coverage, not a claim that a whole event or ranking is complete. Unsupported rows, invalid fields and contradictory card/remark evidence are not cleared by parser success or reconciliation. The reviewer still supplies both accuracy attestations. Identity remains unresolved unless separately reviewed.

## Public boundary

Private administrative APIs expose evidence needed for review. Public APIs read only a restricted database view over explicitly prepared projections. The projection builder selects public fields; it never copies full candidate records, extraction artifacts, raw PDF bytes, private archive paths, processing configuration, actor credentials, private proposals or rejected decisions.

Public records retain original source values separately from effective approved corrections. Public correction audit contains approved corrections and their reversals with source evidence and reasons. Reversed corrections cease affecting effective values. Identity histories contain only active approved links among eligible public observations. References to private linked observations are withheld.

Exact original source names remain searchable after a name correction. Source representation codes are not citizenship. Missing metadata stays explicit rather than becoming inferred units, dates, successful results or nationality. Coverage describes only the visible partial pilot; it reveals no private corpus counts.

The trusted reviewer prepares eligible projections with `refresh!`. Before event enrollment, this includes all eligible observations. After enrollment, the explicit event selections and retained baseline constrain that set; newly validated observations do not silently join a selected event. A change to any review or publication decision invalidates the cached snapshot globally, so public reads return no stale rows. Revalidate affected observations as needed, then refresh. Event selection also tracks relationship and selection authority. This conservative whole-pilot refresh is intended for the bounded local corpus.

Policy activation is a separate append-only database-owner action. Policy version identifiers cannot be reused, so an old version cannot be reactivated to revive its validations. Rollbacks require a fresh version identifier and new validations. An application that does not implement the active policy cannot validate results. Updating code alone does not activate a database policy; deployment of a new policy must explicitly activate it. Real pilot activation changes and validation decisions require separate evidence-backed review.

The owner-only API is `activate-policy! ADMIN-URL VERSION REASON`, also exposed as `clojure -M:publication activate-policy VERSION REASON`. Version 1 is installed by migration 3. Migration 9 adds public-view support for version 2 without changing the active policy or old migration checksums. Existing policy-1 PDF rows remain visible after that upgrade. The [guarded activation checkpoint](deployment.md#html-publication-policy-checkpoint) verifies migration 9 and requires an explicit acknowledgement: activation hides policy-1 rows until fresh validations and projection refresh. Activating an unsupported version also makes rows ineligible; refresh cannot restore them.

## Authority and limits

Apply migrations as a database owner. Use separate restricted ingestion, reviewer and public reader roles. The public reader receives access to the public view only, with no private table reads, writes or schema creation. Reviewer credentials are trusted administrative capabilities; direct reviewer SQL can bypass Clojure semantic validation. Database owners can bypass database protections.

The development helper uses loopback trust authentication. Another local process can impersonate a role. These tests verify privileges within a database session, not production authentication. The [local owner review demo](local-owner-review.md) adds a capability-authenticated loopback HTTP interface. Separate [real-source inspection](local-source-inspection.md) defaults to read-only access; enabling review requires explicit configuration and reviewer database authority. No public login boundary or deployment is provided. Protect private reports and reviewer credentials. Reviewer-approved public text must itself be suitable for public display; an allowlist of fields is not a secret scanner for intentionally supplied strings.

Projection preparation and public reads load the bounded pilot into memory. A simultaneous refresh can return PostgreSQL serialization failure `40001`; retry the complete refresh. Its transaction either commits a complete snapshot or leaves the previous cache intact, with the visibility checks still applied. Public reads already in a repeatable-read transaction retain their earlier snapshot until that transaction ends.

The [local public interface](local-public-results.md) reads these restricted projections. Further pilot coverage, genuine owner review, [anonymous correction triage](local-corrections.md), evaluation and production acceptance remain tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Restart corpus diagnostic

The separate [Athens mirror audit](athens-mirror-audit-20260925.md) grants no publication authority. Its source-only baseline and JSON diagnostic correspondences are not reviewed attempts or confirmed replacements. Detached glyphs, shared-cell ambiguity and genuine source disagreements remain explicit; automated agreement cannot clear them.

The [isolated corpus audit](championship-corpus-20260925.md) found 604 of 4,736 imported observations structurally ready under the unchanged initial policy, with zero eligible or public rows. Readiness is not an accuracy attestation. That unchanged corpus retains `/1` indoor artifacts blocking all 648 contained rows. Separate `/2` diagnostic copies make 412 distance rows ready and keep 236 timing rows blocked, with zero eligible rows. AIDA requires separately authorized policy 2. No real review authority was added.
