# CMAS timing-service acquisition, 25 September 2026

This private acquisition supplements the [championship inventory](championship-inventory-20260925.md). It establishes retained source coverage, not row reconciliation, owner approval, identity decisions or public replacement. Remaining acceptance is tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

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

## Private artifacts and validation

Integrated private root: `data/championship-restart-20260925-b02/cmas/`. Its original acquisition root is `/Users/jamiep/.codex/worktrees/b02-cmas/freediving-results/data/championship-restart-20260925-b02-cmas/`; the coordinator verifies hashes when copying the retained corpus. Original manifest source paths remain provenance, not rewritten history. `incoming/` holds responses plus SHA-256/byte-count/status/MIME/retrieval/discovery metadata; `archive/` holds 161 successful HTTP acquisitions sharing 150 content-addressed objects. Failed response bodies remain outside the successful archive. The successful objects include 27 verified PDF signatures totaling 38 pages. All 194 retained response metadata hashes and lengths were independently recomputed. Thirty-three failed HTTP responses remain outside successful registration: the 30 indoor links, CWT men 2026 link, a duplicate first indoor PDF probe and an erroneous comma-bearing indoor archive probe. The corrected federation URL and redirect are retained separately.

`registration-input.edn` and `acquisition-report.edn` retain exact manifests and duplicate-registration results: every repeated registration skipped. `extraction-report.edn` records 27 existing-parser extraction jobs, each replay skipped. Only three 2025 senior women's PDFs are currently supported: CWT 29, FIM 30 and CNF 18 candidates, 77 total. The remaining 24 PDFs/35 pages are explicitly unsupported, not zero-result sources. No genuine publisher revision matching, full-field reconciliation, reviewer attestation or database import occurred.

`coverage-summary.json` retains per-PDF pages/hashes/header dates, all unit counts, indoor headers and the decoding exception. `indoor-pdf-link-evidence.json` maps all 42 views to 30 configured filenames. `route-discovery-evidence.json` records selected current routes. All acquisitions are private and publication-blocked. Batch 1 evidence is unchanged. This research-only slice changes no parser or product behavior; RED/GREEN tests do not apply. Existing archive verification and actual registration/extraction replay provide the applicable validation.
