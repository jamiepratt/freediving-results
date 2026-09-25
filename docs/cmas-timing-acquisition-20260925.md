# CMAS timing-service acquisition, 25 September 2026

This private acquisition supplements the [championship inventory](championship-inventory-20260925.md). The batch 2 record establishes retained source coverage; the dated batch 3 section records subsequent 2025 depth extraction. Neither grants owner approval, identity decisions or public replacement. Remaining acceptance is tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Evidence chain and the 2025 depth discrepancy

Fresh federation archive requests retained their HTTP redirect chains. The [2025 depth archive](https://www.cmas.org/document/2025,-cmas-world-championship-freediving-depth/download.html) redirects to `https://cmas.microplustimingservices.com/#/competition-schedule/3`. The [masters archive](https://www.cmas.org/document/2025,-cmas-world-championship-freediving-depth-masters/download.html) redirects to `https://cmas.microplustimingservices.com/event-detail/4`; the retained senior event page also links there.

The timing application's public configuration identifies its unauthenticated API. [Competition 3](https://cmas-api.microplustimingservices.com/api/competitions/3) explicitly identifies the 2025 Mytikas depth championship, seniors and masters, September 5-18. Its schedule and documents establish both divisions. [Competition 4](https://cmas-api.microplustimingservices.com/api/competitions/4) instead identifies the November 2025 Asian junior finswimming championship in Jakarta. The current app's observed route literals require `competition-schedule/:competitionId` or a full discipline/category/event/phase/unit result route; `/event-detail/4` is not one of those current routes. Thus the federation link is stale or incorrect for identifying these depth results. This does not imply missing masters competition or justify treating competition 4 as Mytikas. Historical intent of that old URL remains unknown.

The private evidence retains federation HTML, redirect metadata, API configuration, competition identity/schedule/document responses, per-unit result/document responses, and exact downloaded PDF bytes. Selected route literals and their public bundle hash are retained separately; the entire application bundle is not an archived result source.

## 2025 depth

All 20 `RES` document links returned HTTP 200 PDFs: 23 pages total. Start lists (`STL`) were distinguished in retained document inventories and were not downloaded as results. Eight PDFs cover senior men/women across CWT, FIM, CNF and CWT-BF. Twelve cover masters: men M3 and men M1+M2 in each discipline; women M1 in FIM/CNF/CWT-BF and women M1+M3 in CWT. These are observed publisher headings, not inferred age ranges or exhaustive eligibility declarations.

| Discipline | Senior men / men M3 | Senior women / women masters / men M1+M2 |
| --- | --- | --- |
| CWT | September 9 | September 10 |
| FIM | September 12 | September 13 |
| CNF | September 14 | September 15 |
| CWT-BF | September 16 | September 17 |

Dates above are supported by retained PDF result headers and schedule responses. They correct the earlier planned FIM dates. The CWT men and men M3 documents are explicitly linked with `-v2` filenames. Original batch 1 documents remain untouched; these names do not establish a verified predecessor/successor relationship. No records were automatically superseded.

The 20 retained unit result responses contain 352 records. That is a transport count, not an independently reconciled attempt count.

## 2026 depth

[Competition 30 documents](https://cmas-api.microplustimingservices.com/api/competitions/30/documents) lists eight `RES` and eight medalist (`MED`) documents. Seven result links returned HTTP 200 PDFs, 15 pages total. Medalist PDFs were not downloaded as attempt sources. The linked CWT men results URL returned HTTP 404; its response body and metadata remain retained as `incoming/c30-doc52.pdf`, which is HTML despite the local suffix and is not registered as a PDF.

All nine schedule units and their result/document API responses were retained:

| Schedule date | Session | Unit | Result API records | PDF acquisition |
| --- | --- | ---: | ---: | --- |
| August 17 | CWT men first session | 3551 | 7 | No unit document linked |
| August 18 | CWT women | 3552 | 23 | Acquired |
| August 19 | FIM men | 3563 | 32 | Acquired |
| August 20 | FIM women | 3554 | 24 | Acquired |
| August 21 | CWT men continuation | 3559 | 24 | Linked result returned 404 |
| August 23 | CNF men | 3555 | 30 | Acquired |
| August 24 | CNF women | 3556 | 17 | Acquired |
| August 25 | CWT-BF men | 3557 | 31 | Acquired |
| August 26 | CWT-BF women | 3558 | 23 | Acquired |

The 211 API records must not be blindly summed as unique attempts: overlap or continuation relationships are unreconciled. Dates in this table are schedule fields, not independently reconciled row dates. PDF championship range dates and report-generation dates are preserved separately in source text.

Acquired PDFs show senior men/women plus masters M1 women in every acquired women's discipline, additionally M2 women in CWT. Men's FIM and CWT-BF show M1/M2/M3; CNF shows M1/M2. Absent category headings remain unresolved, not zero participation.

## 2025 indoor

The [senior/junior archive](https://www.cmas.org/document/2025-cmas-world-championship-freediving-indoor/download.html) redirects to legacy app selector `#/2/schedule-bydate`; the [masters archive](https://www.cmas.org/document/2025,-cmas-world-championship-freediving-indoor-masters/download.html) redirects to `#/1/schedule-bydate`. The retained configuration maps those selectors to `CMAS_2` and `CMAS_1` exports. Both contain junior, senior and master men/women. The selectors separate discipline groups, so the archive labels cannot establish exclusive category coverage.

Forty-two `CGR1` result JSON views were acquired, one per seven disciplines and six broad categories. Their `Document.Eng` identifies `Result`; `Competition.Eng`, `Category.Eng` and `Event.Date` establish the following header coverage:

| Export | Result date | Discipline |
| --- | --- | --- |
| CMAS_2 | May 20 | Dynamic Apnea Without Fin |
| CMAS_2 | May 21 | Dynamic Apnea Bi Fins |
| CMAS_1 | May 22 | Speed Apnea 8x50 and 2x50 |
| CMAS_1 | May 23 | Static Apnea and Speed Apnea 4x50 |
| CMAS_2 | May 24 | Dynamic Apnea |

These are retained result JSON documents, not acquired PDFs or parsed observations. Generic legacy sport labels such as Swimming/Diving do not override the explicit discipline headers. Age-subdivision completeness and result rows remain unreconciled.

The views reference 30 distinct PDF filenames. All 30 URLs constructed by the publisher's configured `DOCUMENTSPATH` returned HTTP 404. The configured path points under `assets/export/ExportOWTestNew/pdf/`; some different speed disciplines reuse the same filename. No PDF URL was guessed or substituted. Raw failed responses, metadata and the per-view filename mapping are retained. One interrupted request timed out before a bounded retry returned 404; no successful source response was overwritten.

One result response is not valid UTF-8: `incoming/indoor-2-TFSEF011CLAS07 001.JSON`, SHA-256 `84b1294ebab01c4c173cca7a2d49b9d9c9ccf3349c656f3d01962ec5ee76af84`, first failure at byte offset 12982. A Latin-1 byte-preserving inspection exposed its ASCII header fields only; it is not an approved decoding for athlete names. Raw bytes remain intact. The other 41 result views decode as UTF-8. No independent row/cell reconciliation or CMAS JSON parser was implemented.

## Batch 2 private artifacts and validation

Integrated private root: `data/championship-restart-20260925-b02/cmas/`. Its original acquisition root is `/Users/jamiep/.codex/worktrees/b02-cmas/freediving-results/data/championship-restart-20260925-b02-cmas/`; the coordinator verifies hashes when copying the retained corpus. Original manifest source paths remain provenance, not rewritten history. `incoming/` holds responses plus SHA-256/byte-count/status/MIME/retrieval/discovery metadata; `archive/` holds 161 successful HTTP acquisitions sharing 150 content-addressed objects. Failed response bodies remain outside the successful archive. The successful objects include 27 verified PDF signatures totaling 38 pages. All 194 retained response metadata hashes and lengths were independently recomputed. Thirty-three failed HTTP responses remain outside successful registration: the 30 indoor links, CWT men 2026 link, a duplicate first indoor PDF probe and an erroneous comma-bearing indoor archive probe. The corrected federation URL and redirect are retained separately.

`registration-input.edn` and `acquisition-report.edn` retain exact manifests and duplicate-registration results: every repeated registration skipped. `extraction-report.edn` records 27 existing-parser extraction jobs, each replay skipped. At batch 2, only three 2025 senior women's PDFs were supported: CWT 29, FIM 30 and CNF 18 candidates, 77 total. The remaining 24 PDFs/35 pages were explicitly unsupported, not zero-result sources. No genuine publisher revision matching, full-field reconciliation, reviewer attestation or database import occurred in that acquisition batch.

The supported jobs use `cmas-women-depth/1`, schema 2: `c3-doc11.pdf` CWT (SHA-256 `5028e652c49758e4a6a5e442457b65fd57b92055edadbf30477887e6c371561c`), `c3-doc23.pdf` FIM (`b732612eb386fff9781e5c119429289bcdd38bcf11c268252fe58cc1b55172c9`) and `c3-doc33.pdf` CNF (`425037388b725fe22dcb521be0aff88e4c285b8f71ad585c7840017dc4be7124`). The timing CWT men's `c3-doc6.pdf` (`8a71457edfed39f01fe03771fcd8478de1cf0b59ed16cd8bf95e36518a150985`) is distinct from batch 1's federation PDF (`2c0d8cd66d9ccb9fbef9ab6bd76ca94d38942c099ad13984bc07e6f2f89183f3`). Its header says `Freediving Depth`; at batch 2, the schema 1 `cmas-cwt-men/1` fallback required `Freediving Outdoor` and marked it unsupported. Thus batch 1's 44 parsed men cannot be assumed to describe this new timing document. Even the two CWT women's PDFs differ in byte hash. No source equivalence or automatic revision relationship was asserted.

`coverage-summary.json` retains per-PDF pages/hashes/header dates, all unit counts, indoor headers and the decoding exception. `indoor-pdf-link-evidence.json` maps all 42 views to 30 configured filenames. `route-discovery-evidence.json` records selected current routes. All acquisitions are private and publication-blocked. Batch 1 evidence is unchanged. That research-only batch changed no parser or product behavior; RED/GREEN tests do not apply. Existing archive verification and actual registration/extraction replay provide the applicable validation.

## Batch 3 2025 depth extraction

On 25 September 2026, schema-2 parser `cmas-2025-depth/1` added the 17 previously unsupported 2025 timing PDFs: senior men in all four disciplines, senior women CWT-BF and the twelve masters documents. The three supported women's CWT/FIM/CNF documents retain `cmas-women-depth/1`. Changed parser identities preserve earlier unsupported jobs; no filename or byte difference establishes a sporting-result revision.

The parser uses printed discipline/category context, including combined masters subsections and inline CWT categories. It supports the inspected split headers, DSQ penalties, explicit zero depth for DNS and record text. Declared, attempted and final depth remain separate. Exact names, representation codes, status, notes, row lines and metadata lines are retained; unknown values remain unknown. Malformed headings, contradictory context and ambiguous rows fail closed. It does not infer units, nationality, identities, cards or successful results. HTML and 2026 parser behavior is unchanged.

All 20 PDFs contain 23 pages and 352 source rows. Of these, 332 parse and 20 remain explicitly unparsed:

| Source | Discipline | Printed category/division | Source rows | Parsed | Unparsed |
| --- | --- | --- | ---: | ---: | ---: |
| `c3-doc6.pdf` | CWT | Men seniors | 44 | 37 | 7 |
| `c3-doc7.pdf` | CWT | Men M3 | 3 | 3 | 0 |
| `c3-doc11.pdf` | CWT | Women seniors | 29 | 29 | 0 |
| `c3-doc12.pdf` | CWT | Women M1 (4), M3 (1) | 5 | 5 | 0 |
| `c3-doc13.pdf` | CWT | Men M1 (8), M2 (6) | 14 | 14 | 0 |
| `c3-doc16.pdf` | FIM | Men seniors | 44 | 35 | 9 |
| `c3-doc17.pdf` | FIM | Men M3 | 3 | 3 | 0 |
| `c3-doc23.pdf` | FIM | Women seniors | 30 | 30 | 0 |
| `c3-doc24.pdf` | FIM | Women M1 | 4 | 4 | 0 |
| `c3-doc25.pdf` | FIM | Men M1 (10), M2 (6) | 16 | 16 | 0 |
| `c3-doc28.pdf` | CNF | Men seniors | 30 | 30 | 0 |
| `c3-doc29.pdf` | CNF | Men M3 | 1 | 1 | 0 |
| `c3-doc33.pdf` | CNF | Women seniors | 18 | 18 | 0 |
| `c3-doc34.pdf` | CNF | Women M1 | 7 | 7 | 0 |
| `c3-doc35.pdf` | CNF | Men M1 (8), M2 (6) | 14 | 14 | 0 |
| `c3-doc38.pdf` | CWT-BF | Men seniors | 39 | 35 | 4 |
| `c3-doc39.pdf` | CWT-BF | Men M3 | 2 | 2 | 0 |
| `c3-doc43.pdf` | CWT-BF | Women seniors | 27 | 27 | 0 |
| `c3-doc44.pdf` | CWT-BF | Women M1 | 6 | 6 | 0 |
| `c3-doc45.pdf` | CWT-BF | Men M1 (11), M2 (5) | 16 | 16 | 0 |
| **Total** | | | **352** | **332** | **20** |

All unparsed rows are on page 2 of `c3-doc6.pdf`, `c3-doc16.pdf` and `c3-doc38.pdf`, respectively seven, nine and four rows.

Numeric token counts cannot distinguish absent interior columns on these pages. Exact text remains available for review without shifting values into inferred fields. Absent category headings and unavailable sources remain separate coverage gaps.

Independent comparison against source text and a separate row inventory matched all 352 rows to parsed or explicitly unresolved candidates, with no missing/extra rows or remaining field/context/evidence mismatches. All 587 nonblank lines are accounted for exactly once. Source statuses total 232 blank, 72 `PEN`, 36 `DSQ`, 11 `DNS` and one literal `WR MM3`; blank status remains unknown. Two source depth cells contain zero: one parsed on CWT men's page 1 and one retained unresolved on page 2. Seven rendered page samples supported glyph/layout checks. Three initial audit-only note representation differences were independently adjudicated against Poppler text and renders; original audit logs remain retained. Neither automated comparison nor visual sampling supplies owner attestation. This comparison covers the 20 timing PDFs; the two earlier federation documents were checked separately below.

The separate batch 1 comparison verified the older federation CWT PDFs: 44 men and 29 women rows, all 73 parsed, with zero missing/extra rows or field/context/evidence mismatches and all 96 nonblank lines accounted for exactly once. The men's source has no declared-depth column; that value remains unknown. Its legacy schema-1 artifact lacks some field envelopes, `:source-lines` and `:metadata-evidence`; exact coordinates, `:raw :line` and source header context were independently verified without rewriting that artifact. The women's 29 rows had no structural gaps. Private `b01-audit/` retains this separate comparison. Together the 22 acquired 2025 PDFs contain 425 document-version rows: 405 parsed and 20 unparsed. These are not deduplicated unique attempts or verified publisher revisions.

Re-extraction across all 27 retained PDFs created 17 new job IDs; all 27 unchanged replays skipped. The three women's jobs and seven unsupported 2026 jobs retained their IDs. All 790 copied historical files remained byte-identical after extraction, and the 68 audit files were verified when copied. Private `audit/final-summary.json`, `census.json`, `comparison.json` and `job-identities.json` retain totals, source hashes, row outcomes and old/new job IDs. The combined test suite passed 114 tests and 816 assertions, including parser identity/replay compatibility.

The isolated private root is `data/championship-restart-20260925-b03/`, containing `cmas/`, `extract_replay.clj`, `new-extractions.json` and `audit/`. Earlier corpora remain intact. All rows remain unreviewed and publication-blocked. This batch imports no observations, creates no real reviewer attestations and changes no publication policy or deployment. Remaining extraction and coverage acceptance is tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

Reproduce in the checkout retaining this private corpus. Use fresh replay destinations so existing reports remain intact:

```sh
clojure -M data/championship-restart-20260925-b03/extract_replay.clj data/championship-restart-20260925-b03/cmas/archive data/championship-restart-20260925-b03/cmas/extraction-report.edn data/championship-restart-20260925-b03/replayed-extractions.json
cp -R data/championship-restart-20260925-b03/audit data/championship-restart-20260925-b03/audit-replay
python3 data/championship-restart-20260925-b03/audit-replay/compare.py data/championship-restart-20260925-b03/replayed-extractions.json
```

## Batch 4 geometry-backed continuations

On 25 September 2026, `cmas-2025-depth/2` added the 20 previously unparsed page-2 rows: seven in `c3-doc6.pdf` (CWT men), nine in `c3-doc16.pdf` (FIM men) and four in `c3-doc38.pdf` (CWT-BF men). All 20 timing PDFs now contain 352 parsed rows across 23 pages. The batch 3 table above remains the historical `/1` outcome.

The parser supplements exact Poppler layout lines with `pdftotext -bbox-layout` word positions. It maps an unambiguous prior-page column-heading block to physical columns, then requires an exact word-sequence match for each continuation row. This accommodates headings whose physical lines differ from Poppler's layout text. It does not count numeric tokens to assign fields. All 39 absent numeric cells in the target rows remain unknown; the literal achieved-depth zero remains zero. In particular, a DSQ row with a penalty and blank final depth retains both fields separately.

Inherited context requires the same event/date, matching report timestamp and page dimensions, sequential page numbers and an unambiguous preceding table. Unexpected content, contradictory headings, malformed geometry and ambiguous word matches fail closed. The extension deliberately handles the evidenced adjacent-page layout; it does not establish cross-document continuation or publisher revision relationships.

Schema 2's existing page/line, raw-value and candidate contract is preserved. Raw bbox XML, matched row words, header positions and source metadata supplement that contract. Parser `/2` and geometry tool arguments create distinct immutable jobs. Text-only `/1` remains available without changed historical semantics. Before importing a `/2` artifact, source replay compares its layout, geometry and parsed output with the hash-verified PDF under the recorded Poppler version. Rehashed field or geometry changes are rejected. No database migration or publication-policy change is required.

The independent audit freshly reproduced all 352 source rows from PDF geometry, matched every raw and typed field and checked all 587 nonblank layout lines exactly once. All 20 target rows and their 163 words also match independent Poppler bbox evidence. Three continuation-page renders were inspected at 1132 by 1600 pixels. Earlier seven-page samples and note adjudications remain preserved; this is not exhaustive visual review of all glyphs or owner attestation. Source status totals remain 232 blank, 72 `PEN`, 36 `DSQ`, 11 `DNS` and one literal `WR MM3`; both source depth zeros are now parsed.

Public extraction replay produced 17 new `/2` jobs. The three women's jobs and seven unsupported 2026 depth jobs retain their IDs; all 27 repeated calls skip duplicate creation. The separate legacy federation comparison still matches 73 rows and 96 nonblank lines, with unchanged job IDs and explicit historic men's evidence-envelope gaps. Together these sources account for 425 document-version rows, all parsed, without declaring unique attempts or supersession.

The isolated private root is `data/championship-restart-20260925-b04/`. It preserves the complete 914-file batch 3 tree; a separately copied 63-file legacy archive supports compatibility replay. New reports and scripts live under `b04-audit/`, including `copy-verification.json`, `continuation-source-baseline.json`, `baseline-reproduction.json`, `new-extractions.json`, `comparison.json`, `geometry-comparison.json`, `legacy-replay.json` and `METHOD.md`. Source expectations were established before new parser output. Initial provisional parser probes remain separate from final replay evidence.

Final verification passed 126 tests and 902 assertions, including synthetic missing-column, context-reset, malformed-geometry, legacy-output and rehashed-artifact regressions. The narrow depth suite passed 29 tests and 214 assertions; two targeted pipeline version tests passed four assertions. Clojure repair and lint gates were clean. All 17 new artifacts passed archived-source replay validation, and all 914 historical files plus 63 legacy files remained hash-identical. An independent coordinator rerun reproduced the zero-difference row and geometry comparisons.

All extracted rows remain unreviewed and publication-blocked. This batch performs no database import, real review, identity decision, deployment, policy activation or public replacement. Remaining source/layout, revision and publication acceptance stays in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

To reproduce from the checkout retaining the private corpus, use new output paths and a copied audit directory so previous reports remain intact:

```sh
clojure -M data/championship-restart-20260925-b04/b04-audit/replay.clj data/championship-restart-20260925-b04/cmas/archive data/championship-restart-20260925-b04/new-extractions.json data/championship-restart-20260925-b04/replayed-extractions.json
cp -R data/championship-restart-20260925-b04/b04-audit data/championship-restart-20260925-b04/audit-replay
python3 data/championship-restart-20260925-b04/audit-replay/compare.py data/championship-restart-20260925-b04/replayed-extractions.json
python3 data/championship-restart-20260925-b04/audit-replay/compare_geometry.py data/championship-restart-20260925-b04/replayed-extractions.json
```

## Batch 5 2026 depth reconciliation

The seven acquired Roatan result PDFs contain 180 source rows across 15 pages. An independent source census was frozen before comparison with the new parser output. Per-table dates below come from the PDFs; championship range dates and report-generation timestamps remain separate evidence.

| Source | Discipline | Table date | Printed divisions and rows | Pages | Source rows |
| --- | --- | --- | --- | ---: | ---: |
| `c30-doc48.pdf` | CWT | August 18 | Women: seniors 20, M1 2, M2 1 | 1 | 23 |
| `c30-doc49.pdf` | FIM | August 19 | Men: seniors 24, M1 5, M2 1, M3 2 | 2 | 32 |
| `c30-doc50.pdf` | FIM | August 20 | Women: seniors 22, M1 2 | 1 | 24 |
| `c30-doc59.pdf` | CNF | August 23 | Men: seniors 24, M1 5, M2 1 | 3 | 30 |
| `c30-doc60.pdf` | CNF | August 24 | Women: seniors 16, M1 1 | 2 | 17 |
| `c30-doc61.pdf` | CWT-BF | August 25 | Men: seniors 23, M1 5, M2 1, M3 2 | 4 | 31 |
| `c30-doc63.pdf` | CWT-BF | August 26 | Women: seniors 21, M1 2 | 2 | 23 |
| **Total** | | | | **15** | **180** |

All 15 pages were rendered at 1131 by 1600 pixels and inspected for layout, wrapped notes and category boundaries. Source statuses are 156 blank, 22 `DSQ` and two `DNS`. Blank status remains unknown, including rows with penalties. The source has 25 blank final-result cells, including one ranked senior women's CWT-BF row. Its final result is not calculated from other columns. No literal numeric zero occurs in these seven documents; zero preservation is a synthetic regression requirement. Medal and record columns remain separate from notes.

These documents do not cover either CWT-men session: unit 3551 has no linked PDF and the unit 3559 continuation's linked PDF returned 404. Retained JSON is supporting evidence, not a substitute for those PDFs. No attempt deduplication, cross-document continuation or supersession is inferred. Absent category headings remain unresolved coverage.

Schema-2 parser `cmas-2026-depth/1` parses all 180 rows. Each page must establish its own printed discipline/date, column headings and category; category transitions within a page are explicit. Poppler word positions preserve blank interior cells and bind wrapped notes to their source row. Unknown or competing category boundaries, invalid dates, malformed geometry and numeric boxes crossing column boundaries fail closed. Exact row text, constituent lines, header/category evidence, column geometry and raw bbox XML remain available. All units and blank statuses stay unknown; records and medals are not inferred from rank.

The final independent comparison passed 1,980 raw-cell checks, 2,880 typed/context-field checks and 1,496 source-word checks, including exact candidate geometry. All 407 nonblank lines are accounted for exactly once: 191 row-evidence lines, including 11 wrapped notes, and 216 metadata/footer lines. Six deliberate audit-report corruptions were detected, covering raw values, typed values, geometry, source lines, publication state and invented nationality. The batch coordinator independently reproduced the frozen source census and final zero-difference comparison. These checks and visual inspection provide no owner attestation.

Public extraction created seven new jobs alongside the preserved unsupported artifacts. The 20 timing jobs from 2025 retain their IDs; all 27 repeated extractions skip duplicate creation. All 24 geometry jobs pass archived-source replay validation. The 352 earlier timing rows and 73 separate legacy federation rows still match their independent baselines; both legacy job IDs remain unchanged. Together these PDFs account for 605 document-version rows, without claiming unique attempts. The legacy men's evidence-envelope gaps remain explicit. All 1,113 copied historical files remain hash-identical to the original batch 4 tree.

Tests passed: the new namespace has 14 tests and 62 assertions; combined extraction/archive/HTML coverage has 140 tests and 964 assertions; targeted pipeline version registration has one test and two assertions. Repair and lint gates passed. Synthetic regressions cover zeros, absent interior cells, page/category boundaries, wrapped notes, malformed input, immutable prior jobs, idempotency, rehashed tampering and preserved legacy dispatch/import contracts. No database migration is required.

The private root is `data/championship-restart-20260925-b05/`. `b05-audit/` contains frozen source expectations, rendered pages, `new-extractions.json`, `comparison.json`, `history-verification.json`, `comparator-negative-controls.json`, `final-summary.json` and reproducible scripts. `b05-parent-review/` retains the coordinator's independent reproduction and 24 source-replay validations; `b05-parent-2025/` retains historical comparisons. Parser logs and the observed RED/GREEN history are in the separate `data/b05-parser/` directory.

To repeat the final comparator without replacing its original report:

```sh
python3 data/championship-restart-20260925-b05/b05-audit/reconcile.py data/championship-restart-20260925-b05/b05-audit/new-extractions.json data/championship-restart-20260925-b05/comparison-rerun.json
```

All 180 new rows remain unreviewed and publication-blocked. This batch adds no source acquisitions, database observations, genuine reviews, identity decisions, policy activation, deployment or public replacement. Remaining coverage, revision, import and publication acceptance stays in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).
