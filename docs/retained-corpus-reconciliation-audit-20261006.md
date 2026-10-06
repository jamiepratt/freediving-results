# Retained-corpus reconciliation audit, 6 October 2026

This is the bounded historical test report for [#76](https://github.com/jamiepratt/freediving-results/issues/76), under [ADR 0003](adr/0003-automatic-evidence-reconciliation.md). It consolidates the retained deterministic, Jev, source-audit and reversal controls with the later genuine private-review checkpoint. Its assessment is an explicitly partial result. Global identity accuracy, complete coverage and a production ingestion cutover are unmeasured.

## Frozen authority and scope

| Authority | Frozen value |
| --- | --- |
| Evidence cutoff | `2026-10-01T12:39:47Z` |
| Snapshot manifest SHA-256 | `f88ab39422650049df7b641e8daacf5fa803dc893f3a8f7aa72f02469f252dc3` |
| SQLite SHA-256 | `278daebb34b6770cf52baf1cc412148f3384435c09fd113e5ab6267815bf6a71` |
| Original source bundle SHA-256 | `bc6ffa00f776099d4e4b255a2b0d8262c092cebac8ef2930994fd1d020f4ecae` |
| Frozen PostgreSQL dump SHA-256 | `d330ac5b17df6ce2a44560713500e23380708965ea093d88891952c33fceaecc` |
| Structural PostgreSQL binding SHA-256 | `ae9a0033cdf13e7d19cc955f430c80bccc060dda513d934f963db41dd45bf93b` |
| Separate isolated decision-store revision 1 SHA-256 | `21ccebacb74222adbb63657185e176995de39b45ccbaf31da0e946f9ab4cffc7` |
| Checked affiliate-name input, [#75](https://github.com/jamiepratt/freediving-results/issues/75) | `6003e4ee6be7b289aa14af960bf2f1444e8b5812bb2208d407ef776af7c0a6e3` |

The manifest and SQLite hashes and SQLite integrity were checked again for this report. Of 42 original manifest inputs, 28 were recovered at declared paths and four at relocated paths with identical hashes; ten remain unrecovered. This describes packet availability, separately from original-source bytes. Supported accepted relationships below have individual original-byte/citation checks. Missing packets do not make the frozen SQLite rows disappear, and those rows do not establish missing original availability.

The earlier historical release authority contains 58 observation identity-anchor approvals and one name correction. Its six source hashes and observation bindings do not match this snapshot. It remains separate; those approvals are excluded from this test's accepted counts. The isolated revision-1 store is test authority, not a migration or publication grant. Checked #75 input contains 56 assertions: all 31 cited candidate references resolve and three affiliate source hashes verify; nine affiliate/name-evidence gaps remain explicit. This establishes input binding, not approval of all alias links.

No attempt-source acquisition, reimport, provider dispatch, production write or website mutation occurred in this audit batch. Earlier genuine owner actions are identified separately below. Originals, athlete-level rows, receipts, exact audit selections and private stores stay outside Git and GitHub.

## Corpus denominators

The query frame is every record in the pinned SQLite `records` table. Year means its normalized `event_date` column matches `YYYY-MM-DD`; absent or ranged dates remain unknown. The field may originate in a cited calendar or selected view, so a populated date is not an independently established exact dive date. Source namespaces and parser/schema families retain their original boundaries. The frozen snapshot has no federation-mapping table; parser-prefix labels below do not assert one.

| Metric | Count | Meaning |
| --- | ---: | --- |
| Mixed evidence records | 16,361 | Inventory, not sporting attempts |
| Candidate-position records | 13,840 | Includes rankings, replay candidates and historical version appearances |
| Normalized 2025 / 2026 / unknown date candidates | 2,371 / 4,464 / 7,005 | No year inferred from filenames |
| Source records | 232 | Metadata records, not distinct original objects |
| Distinct candidate source-object references | 204 | Non-null reference strings; not a global verified-byte total |
| Distinct source-object references across all record kinds | 223 | Broader reference frame than candidate positions |
| Candidates with no normalized source-object reference | 355 | GIA workbook candidates remain in their private cited packet scope |
| Unique structured PostgreSQL observation versions | 11,150 | Exact `(job_id, ordinal)` reference keys |
| Structured PostgreSQL reference appearances | 11,342 | 192 repeats: 56 FFESSM ranking references and 136 retained replays |
| Distinct scalar `observation_version` strings | 11,154 | Includes four Roatan job IDs through normalizer fallback; not 11,154 versions |
| Candidates excluding replay/version collections | 13,642 | Removes 136 retained candidates and 62 historical Roatan versions; still not attempts |
| Provisional athletes across the corpus | unknown | No authoritative complete projection |
| Accepted athletes / confirmed distinct sporting attempts across the corpus | unknown / unknown | Cohort projections below cannot be summed into global truth |

The PostgreSQL binding covers all 11,150 frozen versions, with zero unreferenced versions. It detects 136 record-level parser disagreements in the `retained` replay namespace. Those replay candidates reference already imported observations with different parser versions; they are not 136 new observations or permitted approvals. Their mismatch is quarantined from accepted relationship frames. The four extra scalar strings equal Roatan job IDs; the historical Roatan version collection has 62 row appearances, not four versions.

The candidate-origin role accounting is: 112 explicit aggregate-ranking positions, 371 API-result positions, 142 daily-result positions, 460 selected-date HTML positions, 76 supporting final-PDF positions, 62 historical version appearances, 136 retained replay candidates, 11,023 parsed positions whose sporting role remains unresolved, and 1,458 other cited candidates with unresolved role. These sum to 13,840. Stored publisher authority is primary for 105 source records, unknown for one, and unspecified for 126; primary publication does not establish a unique sporting attempt. General mirror and publisher revision direction remain unknown.

The other record kinds are 935 aggregates, 166 discovery leads, 30 discovery routes, 94 endpoint-source records, two events, 62 gaps, 238 manual observation-version records, 290 other records, 438 relationships, six repeated positions and 28 summaries. Leads, aggregate points, manual transcriptions, parser output and pre-existing candidate relationships confer no identity, same-attempt or publication approval.

| Source namespace | 2025 | 2026 | Unknown date | Candidate positions | Source records | Hash refs | Input |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| adriatic-4375-2025-02-18 | 11 | 0 | 0 | 11 | 0 | 1 | declared hash verified |
| adriatic-4375-2025-02-19 | 12 | 0 | 0 | 12 | 0 | 1 | declared hash verified |
| adriatic-4375-2025-02-20 | 14 | 0 | 0 | 14 | 0 | 1 | declared hash verified |
| adriatic-4375-2025-02-21 | 11 | 0 | 0 | 11 | 0 | 1 | declared hash verified |
| adriatic-4375-2025-02-24 | 8 | 0 | 0 | 8 | 0 | 1 | declared hash verified |
| adriatic-4375-2025-02-25 | 7 | 0 | 0 | 7 | 0 | 1 | declared hash verified |
| aida-4408-2025-08-30 | 24 | 0 | 0 | 24 | 0 | 1 | relocated hash verified |
| aida-4408-2025-08-31 | 22 | 0 | 0 | 22 | 0 | 1 | declared hash verified |
| aida-4464-2025-04-26 | 67 | 0 | 0 | 67 | 0 | 1 | declared hash verified |
| aida-4464-2025-04-27 | 66 | 0 | 0 | 66 | 0 | 1 | declared hash verified |
| aida-4994-2026-04-11 | 0 | 52 | 0 | 52 | 0 | 1 | declared hash verified |
| aida-4994-2026-04-12 | 0 | 50 | 0 | 50 | 0 | 1 | declared hash verified |
| apnea-file-reconciliation | 0 | 0 | 0 | 0 | 0 | 0 | declared hash verified |
| asti | 0 | 91 | 0 | 91 | 0 | 1 | unrecovered |
| barracuda | 0 | 7 | 0 | 7 | 0 | 1 | unrecovered |
| baseline | 1508 | 3165 | 6477 | 11150 | 105 | 89 | unrecovered |
| cagliari | 0 | 105 | 0 | 105 | 0 | 1 | unrecovered |
| cmas-microplus-2026 | 0 | 279 | 76 | 355 | 105 | 82 | declared hash verified |
| cmas-worldcup-2026 | 0 | 126 | 0 | 126 | 0 | 1 | declared hash verified |
| eindhoven-noxy5 | 0 | 92 | 0 | 92 | 0 | 1 | unrecovered |
| ffessm-correspondences | 0 | 0 | 0 | 0 | 0 | 0 | declared hash verified |
| ffessm-daily | 71 | 0 | 0 | 71 | 2 | 2 | declared hash verified |
| ffessm-rankings | 0 | 0 | 56 | 56 | 9 | 9 | declared hash verified |
| firenze | 0 | 155 | 0 | 155 | 0 | 1 | unrecovered |
| friday | 0 | 30 | 0 | 30 | 0 | 1 | unrecovered |
| gap | 0 | 0 | 0 | 0 | 0 | 0 | relocated hash verified |
| gia | 355 | 0 | 0 | 355 | 0 | 0 | unrecovered |
| italian-open-2025 | 0 | 0 | 186 | 186 | 0 | 1 | declared hash verified |
| komaros-pages-01-07 | 0 | 0 | 0 | 0 | 0 | 0 | relocated hash verified |
| komaros-pages-08-13 | 0 | 0 | 0 | 0 | 0 | 0 | relocated hash verified |
| komaros-visual | 0 | 47 | 0 | 47 | 0 | 1 | declared hash verified |
| liberamente | 0 | 177 | 0 | 177 | 1 | 1 | unrecovered |
| mabini-4545-2025-05-01 | 30 | 0 | 0 | 30 | 0 | 1 | declared hash verified |
| mabini-4545-2025-05-02 | 23 | 0 | 0 | 23 | 0 | 1 | declared hash verified |
| mabini-4545-2025-05-04 | 22 | 0 | 0 | 22 | 0 | 1 | declared hash verified |
| mabini-4545-2025-05-05 | 16 | 0 | 0 | 16 | 0 | 1 | declared hash verified |
| mabini-4545-2025-05-06 | 25 | 0 | 0 | 25 | 0 | 1 | declared hash verified |
| retained | 79 | 57 | 0 | 136 | 2 | 2 | unrecovered |
| roatan-issue8 | 0 | 31 | 62 | 93 | 2 | 2 | declared hash verified |
| route-roster-v3 | 0 | 0 | 0 | 0 | 0 | 0 | declared hash verified |
| route-roster-v4 | 0 | 0 | 0 | 0 | 0 | 0 | declared hash verified |
| san-mauro-jpg | 0 | 0 | 148 | 148 | 6 | 4 | declared hash verified |

Hash refs count distinct strings per namespace. They do not sum to the corpus total because namespaces overlap. Zero-row Komaros page inputs are verified derivations, not source positions. Unknown is based on the frozen normalized event-date column; a filename year or calendar context is not substituted. All counts precede accepted identity or attempt equivalence.

| Source/parser family | 2025 | 2026 | Unknown date | Candidate positions |
| --- | ---: | ---: | ---: | ---: |
| aida-selected-html-packet/v1 | 358 | 102 | 0 | 460 |
| apnea-academy-file-reconciliation/v1 | 0 | 0 | 0 | 0 |
| asti-blu-visual-evidence/v1 | 0 | 91 | 0 | 91 |
| barracuda-visual-evidence/v1 | 0 | 7 | 0 | 7 |
| baseline:belgrade | 0 | 57 | 0 | 57 |
| baseline:cmas | 597 | 466 | 190 | 1253 |
| baseline:fedas | 201 | 0 | 279 | 480 |
| baseline:ffessm-daily | 71 | 0 | 0 | 71 |
| baseline:ffessm-ranked-views | 0 | 0 | 148 | 148 |
| baseline:fipsas | 49 | 275 | 427 | 751 |
| baseline:fipsas-classifica | 0 | 1933 | 752 | 2685 |
| baseline:italian | 0 | 0 | 183 | 183 |
| baseline:san | 0 | 155 | 0 | 155 |
| baseline:tuttinapnea-monthly | 0 | 0 | 4498 | 4498 |
| baseline:unknown | 0 | 0 | 0 | 0 |
| baseline:vdst | 437 | 279 | 0 | 716 |
| baseline:vestico | 153 | 0 | 0 | 153 |
| cagliari-visual-evidence/v1 | 0 | 105 | 0 | 105 |
| cmas-microplus-private-census/v2 | 0 | 279 | 76 | 355 |
| cmas-worldcup-2026-visual-evidence/v1 | 0 | 126 | 0 | 126 |
| eindhoven-2026-noxy-private-accounting/v1 | 0 | 92 | 0 | 92 |
| ffessm-2025-daily-relationships/v1 | 0 | 0 | 0 | 0 |
| ffessm-2025-daily/v1 | 71 | 0 | 0 | 71 |
| ffessm-2025-rankings/v1 | 0 | 0 | 56 | 56 |
| firenze-visual-evidence/v1 | 0 | 155 | 0 | 155 |
| friday-night-visual-evidence/v1 | 0 | 30 | 0 | 30 |
| gia-2025-individual-workbook-census/v1 | 355 | 0 | 0 | 355 |
| issue55-route-roster/v3 | 0 | 0 | 0 | 0 |
| italian-open-2025-visual-evidence/v3 | 0 | 0 | 186 | 186 |
| komaros-visual-evidence/v1 | 0 | 47 | 0 | 47 |
| liberamente-visual-evidence/v1 | 0 | 177 | 0 | 177 |
| roatan-2026-cwt-men-private-census/v1 | 0 | 31 | 62 | 93 |
| san-mauro-jpg-supplement/v1 | 0 | 0 | 148 | 148 |
| worker-gap-reconciliation/v1 | 0 | 0 | 0 | 0 |
| worker-retained-artifact-reconciliation/v1 | 79 | 57 | 0 | 136 |

## Relationship and audit frames

These frames overlap the inventory and each other. Directional candidate approvals, unique pair decisions, provisional source records and accepted groups are different denominators.

| Decision family / retained frame | Before | Accepted / after | Unresolved and proof limit |
| --- | --- | --- | --- |
| AIDA publisher-person identity, six selected HTML views | 281 provisional source records, 71 publisher-person IDs, 590 within-ID pairs | 210 automatic minimal edges; 69 accepted repeated groups in the isolated projection; one preserved prior human negative pair | This is source-person consistency. Accepted global athletes and distinct attempts unknown; category/session fields absent in all 281 rows. |
| CMAS exact-name identity, dated PG-bound frame | 1,127 rows | 157 directional approvals representing 79 unique test pairs; selected provisional groups 1,048 before reversal | 970 unresolved rows: 869 competing candidates, 100 lacking a distinctive exact match, one insufficient name. Corpus retrieval completeness remains unknown. |
| Microplus same-result views | Five snapshot positions expanded to ten grouped/individual cited views from two source objects | Five deterministic links, five accepted test attempt groups | View equivalence supported for this exact frame. No human-caused ten-to-five reduction is claimed. |
| AIDA missing-context attempt control | 540 independent candidate pairs | Zero new source-backed same-attempt decisions | All 540 unresolved; a retained correction and 541 ledger events persist without calls. |
| Affiliate native-script / romanization evidence | 56 checked assertions, nine explicit affiliate/name-evidence gaps | Evidence input accepted; no independently labelled native-script identity accuracy sample | An assertion or non-ASCII Latin name is not a verified cross-script identity link. |
| Source revision / mirror direction | Repeated bytes, views and existing relationship candidates retained | Microplus same-result equality only in its supported frame | Publisher revision direction, general mirrors and common-name identity remain unknown where no discriminator is supplied. |
| Category, representation, penalties and row semantics | Original values and absence retained | Exact Microplus source/scope fields checked | No model fills absent representation/category values or turns penalties/status tokens into an identity decision. |

AIDA sampling used the 210 automatic minimal edges as its frame. SHA-256 ordering of the private stable event key selected 36 edges, with forced coverage of all six source views and observed unusual strata, including all five same-result-field edges. The selection oversamples unusual cases; it is not a random population accuracy study. The 36 edges have 72 directly checked packet row/anchor citations and zero structural failures. Independent real-world identity truth labels are **0/36**, leaving **36/36 truth unknown**; identity error and global accuracy are therefore unknown, not zero.

Full AIDA strata overlap: 169 cross-view, 58 cross-year, 41 same-day, 195 differing-discipline, 30 non-ASCII-name and five same-result-field edges. Sample strata are respectively 31, 13, five, 32, six and five. Source-view anchor exposure is 8/7/13/16/9/14 across the six views; an edge can expose two views. All 210 event references and all 281 original rows were independently checked with zero structural binding/hash failures. Non-ASCII Latin names are not a native-script alias test.

The CMAS audit checks all 79 unique approved pairs for cited immutable PostgreSQL observation compatibility, with zero structural citation failures and zero independent identity truth labels. It uses retained raw-source provenance; this is not a new visual audit of all 16 original CMAS sources. This cannot establish a zero identity-error rate. The 874 candidate-bearing unresolved rows produced 842 wire-valid name-only questions and 32 oversized requests in the prior probe. There is no retained source-backed discriminator or publisher-ID alias that certifies a projection-changing question, so certified supported Jev questions are zero. No paid call is warranted by those counts.

The Microplus audit verifies both raw JSON source hashes and retained receipts, all ten exact source-position anchors, 70 source/scope fields and all five equal-result pairs, with zero structural failures. The actual retained competition identifier is 28; the earlier budget preflight's identifier 33 is a stale label, excluded from authority. These checks substantiate source view equality, not a universal duplicate-dive rule.

## High-impact anomaly dispositions

| Anomaly / control | Observed denominator | Disposition |
| --- | --- | --- |
| Common-name collision and competing people | CMAS 869 competing rows; AIDA accepted frame has zero observed exact-name-collision edges | Remain unresolved outside publisher-scoped IDs; no population collision rate inferred |
| Equal performance on separate dives | All five AIDA same-result-field edges checked; 540 missing-context candidate pairs | Identity edges do not collapse dives; same-attempt control remains unresolved |
| Publisher revisions and source overlap | 136 parser mismatches; 192 repeated PG references; 76 unreviewed Microplus relationship records in the frozen snapshot | Mismatches quarantined, references deduplicated as observations, only five supported view links accepted |
| Date and session scope | 7,005 candidates with unknown normalized exact date; all 281 AIDA rows lack category/session values | Keep unknown/range/calendar context; no invented round or dive date |
| Native-script aliases | 56 checked assertions and nine affiliate/name-evidence gaps; no independently labelled alias identity sample | Preserve script/romanization claims and gaps; no native-link accuracy claim |
| Representation/category ambiguity | AIDA category fields absent in 281 rows; zero observed representation-difference edges in accepted frame; CMAS category/session values present in 613/147 of 1,127 rows | Zero exposed conflicts is not tested country-change handling or completeness; missing values remain unknown |
| Repeated source tables and aggregates | Firenze seven repeated athlete rows; Italian Open 186 overlapping candidate appearances, six repeated summaries; San Mauro 148 candidates plus 238 manual versions | Preserve positions and summaries separately; no unsupported distinct-attempt count |
| Penalty/status uncertainty | Cited PDF/visual frames include undefined or ambiguous printed codes and missing source meanings | Raw values retained; structural audit does not attest extraction accuracy or infer penalty cause |
| Missing original manifest packets | Ten of 42 still unrecovered | Inventory denominator retained, broad raw-source revalidation unavailable; no reacquisition in this test |

## Replay, reversal and Jev controls

The isolated CMAS identity ledger reverses one of 79 approved pairs and recomputes linked groups from 79 to 78, total selected provisional groups from 1,048 to 1,049, and event count from 79 to 80. Its append-only correction survives unchanged replay and blocks automatic relinking. These provisional groups are not confirmed global athletes. AIDA replay preserves the prior human negative pair and all 210 automatic events at identity revision 211. Its missing-context control remains unresolved with the earlier reversal retained. Microplus attempt reversal changes five links to four and test groups from five to six; unchanged source replay preserves that human correction and adds no call. The six counterfactual groups are not six newly verified dives. Source-revision reversal is unavailable because this frame contains no accepted source-certified publisher revision direction. Attempt reversal supplies the required attempt/source-decision control without inventing such a revision.

Historical Jev replay uses the retained `reconciliation-jev/1` input and actual receipt model `jev-1.13.0`. The exact model/input/cache key reuses one answer, retains unknown, and leaves ledger revision 2 unchanged. A policy-only reevaluation reuses the compatible retained distribution as `cached-jev` at revision 3. Both controls make zero provider callbacks. Current `reconciliation-jev/3` invalidates the old template key and stops at `missing-budget-baseline` before dispatch. Historical compatibility controls do not claim that the current template can reuse an incompatible answer or that every corpus case has a cache hit.

| Provider/accounting metric | Measured value / basis |
| --- | --- |
| New paid calls in this audit batch | 0 |
| Identifiable historical dispatches in the retained #76 ledger | 1 |
| Historical provider usage | 1,453 input tokens, 57 output tokens from receipt |
| Conservative cumulative reservation | $0.002688 |
| Previously recorded published-rate input-only estimate | $0.000061026; excludes unsupported billing assumptions |
| Actual settled provider bill | unknown |
| Owner-accepted cumulative budget boundary | Estimated spend strictly below $10 and at most 15,000 cumulative calls |
| New corpus deterministic-frame cache hits | 0; no Jev question dispatched |
| Offline historical cache-control hits | 1 unchanged reuse plus 1 policy-only reclassification; zero callbacks |
| Historic full-corpus runtime and provider latency | unknown; no retrospective runtime manufactured |

The one historical response chose unknown: distribution same-attempt 0.43, distinct-attempts 0, unknown 0.57; provider confidence 0.35. A high-confidence unknown could not approve an affirmative link. The original policy is `reconciliation-approval-v2`; offline policy reevaluation is `b21-offline-policy/1`. Selected accepted rule versions are `athlete-identity/1`, `attempt-relationships/1` and `source-identity/1`, with candidate comparison `unicode-nfd-token/1` and append-only flow contract `reconciliation-flow/1`. Selected AIDA adapter version is `aida-snapshot-observation/3` and Microplus evidence adapter is `cmas-microplus-attempt-evidence/1`.

Provider confidence is an output score, not measured accuracy, cost or a label. Historical dashboard estimates are outside the one identifiable retained-ledger dispatch denominator and are not treated as settled batch billing.

Review-queue counts are bounded separately: the genuine Microplus checkpoint has zero pending delivery, and the historical Jev frame retains one unknown outcome. A global pending-review count is unavailable. The 970 unresolved CMAS rows and 540 AIDA missing-context pairs are unresolved work denominators, not automatically approvals or a measured scored queue. Deterministic links carry rule provenance and no fabricated Jev confidence.

Latency has an explicit measurement basis: the fresh 36-edge AIDA source-audit script took 0.1094 seconds offline, and a prior CMAS isolated replay recorded 5.9 seconds locally. Neither measures provider latency or full-corpus runtime. The retained historical request/dispatch/result files contain no elapsed provider duration. Fresh synthetic local tests verify behavior, not historical throughput.

## Later authenticated private-review checkpoint

The later checkpoint binds the same frozen snapshot and source bundle. Genuine owner actions approve five same-attempt relationships, reverse one, then approve it again. The immutable seven-event HMAC feed contains approvals 221-225, reversal 226 and reapproval 227. Accepted group counts by prefix are `[5,5,5,5,5,6,5]`. The five relationships were already deterministically accepted before human approval; human review changes review authority and the reversible decision history.

Authenticated [private owner evidence](https://poc.alphacompose.com/owner-evidence) readback shows five human-approved accepted canonical attempt links and zero pending delivery. Both sides acknowledge revision 227 with 14 receipts. Fourteen exact public callback retries add zero rows or table changes; unchanged outbox work is 0/0 and provider calls zero. All 25 other canonical tables remain unchanged, including 211 identity decisions, 281 source identity observations and one view. Existing 1,575 public observations, 81 caches, 81 decisions, two selections and configuration remain unchanged. There is no genuine live identity-review claim and no public release.

## Acceptance assessment and operational conclusion

| #76 acceptance criterion | Assessment |
| --- | --- |
| Exact input, hashes, cutoff and gaps pinned; no unchanged source reimport | Pass for this explicit partial frame; ten missing packets, unknown dates and nine affiliate gaps disclosed |
| Every accepted relationship cites source and decision provenance; rows/versions/athletes/attempts separate | Pass in the audited accepted cohorts; corpus-wide accepted athlete and sporting-attempt totals explicitly unknown |
| Deterministic replay, Jev cache, unknown handling, identity and attempt reversal, human correction preservation | Pass in isolated retained controls; current-template invalidation reported separately from historical cache compatibility |
| Unresolved cases, sample errors with denominators and clear #73 go/no-go | Explicit partial result supported; structural failures and real-world truth unknowns reported separately |

The evidence supports **GO for the exact bounded frozen Microplus review/delivery checkpoint already demonstrated**. It supports **NO-GO for declaring [#73](https://github.com/jamiepratt/freediving-results/issues/73) generally accepted or switching the normal production ingestion path**: #73 must independently assess its incremental, invalidation, interruption and required metrics criteria. Parent [#67](https://github.com/jamiepratt/freediving-results/issues/67) is also independently assessed. Those issue gates are not redefined by this historical report.

The accepted-partial interpretation is expressly allowed by #76. It does not require impossible full-corpus ground truth, paid calls without supplied discriminating evidence, or guessed counts. It also does not imply global completeness, publication authority, extraction attestations or production cutover.

## Reproducibility and validation

The private report stores the complete 16,361-record denominator ledger, all namespace/year/family and baseline parser/year strata, exact sample selection, source/decision hashes and control results. The original frozen artifacts remain unchanged. This document contains aggregates and source labels only.

| Derived evidence | SHA-256 |
| --- | --- |
| Complete denominator aggregate | `b4177dc5b44f6c9cd868d5ae7408edbd13678740a7738fb5f371d1abb51425a1` |
| Private record ledger | `0ef3bb526ac1de76cc125d1ca92d7f8ccd0a5a7ae9a94a186f8c3a749d17bfaf` |
| Reproducible measurement script | `7bd065d833019162d21860fc5ccfcefc4ef49813e4c182f3b412590d22941947` |
| AIDA stratified approval audit | `e8b7842ed34da6a879e2920f15b0049eed778a28ae08a982f0101d4f6752584a` |
| Fresh consolidated source/reversal/cache controls | `22a54108d83777103ef10096120af435b6db6f31ce7bffda4c8684d33f11a5d7` |
| Microplus isolated reversal report | `ee8112f521f7150872f2a7278c39941301783f4f47a4a22d20a829d977f1ee1c` |
| Genuine private-review/delivery checkpoint | `831dfb5a4c75185a70d5a9115400c5b76da53edaaceaecd4663e704a44869eba` |

Counts were reconciled to the frozen snapshot and existing PostgreSQL binding authority. The current existing flow/Jev suite passed 35 tests and 134 assertions without provider dispatch. Documentation validation checks table sums, source-year equality, private-data exclusion, links and `git diff --check`; no artificial executable test was added for a report-only change.
