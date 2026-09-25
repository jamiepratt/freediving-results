# Championship source gap audit, 25 September 2026

Batch 12 accounts for the retained 2025 indoor JSON rows and rechecks official discovery routes. It recovered no missing attempt-results PDF. One newly acquired 2026 CWT-men medalists PDF supplies supporting category evidence only. [Issue #8](https://github.com/jamiepratt/freediving-results/issues/8) remains open; no source review, identity decision, database import, policy activation or public replacement occurred.

## Official discovery and unavailable paths

The [CMAS results archive](https://www.cmas.org/freediving/results.html) still sends 2025 indoor readers to the legacy Microplus app. The [Athens event page](https://www.cmas.org/freediving-events/2025-cmas-world-championship-freediving-indoor.html) links `results.microplustimingservices.com`, while the archive redirects to `results-ws.microplustimingservices.com`. Both returned the same app-shell hash. The current event-host configuration matches the retained configuration exactly; its document path still points to `assets/export/ExportOWTestNew/pdf/`.

On the event-linked host, the configured general document index and two representative configured PDF paths returned HTTP 404 on 25 September at 08:42-08:44 UTC: `documents.json`, `CLS-JUM--TIM-ALL.pdf` and `CLS-SEF-DNF-FINAL-ALL.pdf`. These filenames came from retained result fields, not guessed URLs. Each response was 4,905 bytes of HTML, not a PDF. The prior 30 archive-host PDF failures remain retained; they were not all retried. The sampled live speed Results and Summary views expose no PDF anchor. The federation's [day 1 report](https://www.cmas.org/news/cmas-2025-indoor-freediving-world-championship-day-1-report/amp.html) points back to its results archive rather than supplying a different result-PDF URL. This bounded search establishes failed observed routes, not universal absence.

Fresh Roatan federation/API responses preserve the same eight comparable byte hashes as batch 2, including both CWT-men document lists. The live Reports page also exposes the unchanged missing result URL. No changed evidence justified repeating that 404. The original `c30-doc52.pdf` failed response is **zero bytes with no recorded MIME type**, not HTML; its filename never established PDF content.

## Retained indoor JSON accounting

All 42 `CGR1` response hashes and lengths match the retained acquisition metadata. Every transport row, field/type occurrence, header, source filename, date and category is inventoried privately. All filenames match exactly one retained index entry with the same category and discipline. Eighteen index/file counters agree and 24 differ, so these index and result snapshots are not assumed synchronized.

| Discipline | Date in 2025 | Junior women | Junior men | Senior women | Senior men | Master women | Master men | Total |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DNF | May 20 | 3 | 10 | 50 | 56 | 13 | 30 | 162 |
| DYN-BF | May 21 | 4 | 10 | 54 | 62 | 15 | 32 | 177 |
| 8x50 | May 22 | 3 | 6 | 10 | 12 | 3 | 7 | 41 |
| 2x50 | May 22 | 5 | 6 | 15 | 14 | 6 | 14 | 60 |
| STA | May 23 | 1 | 7 | 42 | 43 | 10 | 26 | 129 |
| 4x50 | May 23 | 5 | 6 | 17 | 13 | 4 | 9 | 54 |
| DYN | May 24 | 4 | 8 | 55 | 57 | 12 | 33 | 169 |
| **Total** | | **25** | **53** | **243** | **257** | **63** | **151** | **792** |

These are transport rows, not a verified unique-attempt census. All 792 full-row hashes differ. There is no repeated family/discipline/`PlaCod` across category views, but one senior-men DYN-BF view reuses a code on two rows with different given name, representation, heat, lane and result. Its 791 distinct keys cannot be treated as 791 people or attempts. Master row labels expose M1/M2 and some M3 men; M3 men are absent in 4x50/8x50 and no M3-women row label appears. Missing labels do not establish zero participation or category completeness.

The retained indexes distinguish 42 `CGR1`, 42 start-list and 37 `SUM` files. A browser/network check of junior-men 8x50 identifies `CLAS`/`CGR1` as Results and `RIEP`/`SUM` as Summary. Fresh bodies have the same six source codes, names, lanes and final values. Results groups them into two heats; Summary uses overall ranks and different heat, gap and medal fields. These are two presentations of six records, not twelve attempts. This sampled relationship is not extrapolated to all unacquired summaries.

### Encoding ambiguity

Forty-one views are strict UTF-8. The remaining 31,246-byte senior-women DNF response retains SHA-256 `84b1294ebab01c4c173cca7a2d49b9d9c9ccf3349c656f3d01962ec5ee76af84`. Exactly one invalid byte, `0x98` at offset 12982, occurs in `/data/19/PlaSurname`. All 50 rows can be structurally accounted for with reversible byte escaping; this does not decode the affected name. No replacement character or guessed charset was substituted.

A fresh response is byte-identical and declares `application/json` without a charset. Its Brotli content encoding concerns compression, not character decoding. The affected string remains unresolved, with its exact raw bytes retained privately.

## Historical Athens mirror comparison

The older 67-page Athens PDF was acquired on 23 September from a [Venezuelan federation mirror](https://www.fvas.com.ve/wp-content/uploads/2025/05/Resultados-CMAS-Freediving-Indoor-Atenas-2025.pdf). Original and restored copies share SHA-256 `9bb85a2d3a672f2d3e78f156478874fd02de5fd717c9653fe2c663a2eba8f2ed`, 2,855,256 bytes. Its retained provenance explicitly identifies a mirror; no referring CMAS page or original CMAS-hosted copy was established. It was not freshly downloaded in this batch or represented as independent corroboration.

The existing parser's 794 candidates comprise 792 result rows and two detached text fragments. There are 787 parsed rows and five unparsed result rows. All seven discipline totals equal the JSON totals above, but this is not full value reconciliation. A multiset diagnostic using discipline/category/representation/final token matches 649 parsed PDF entries; 138 PDF-side and 143 JSON-side entries remain unmatched. Numeric-token formatting is normalized only for this diagnostic, with original values preserved and no unit conversion.

A separate name-text diagnostic yields 702 possible correspondences, without confirming athlete identity or attempt equivalence. Of these, 701 categories, 644 representation strings and 624 final-value tokens agree. Differences include PDF `CMAS1` versus JSON `CMA`, zero versus blank values, and a speed-8x50 master-women category discrepancy. Rank/order cannot safely align PDF overall standings with per-heat JSON. All unmatched rows, unparsed rows and differences remain explicit private evidence. The mirror is not substituted into the restart corpus.

## Roatan CWT-men coverage

The [official event page](https://www.cmas.org/freediving-events/2026-cmas-world-championship-freediving-depth.html) explicitly calls day 5 a continuation of day 1. This establishes the publisher's session description, not supersession or a reviewed row relationship.

| Unit | Date in 2026 | API age-group rows | Attempt-results PDF |
| --- | --- | --- | --- |
| 3551, event 661 / phase 594 | August 17 | SENM 7 | No document linked |
| 3559, event 655 / phase 588 | August 21 | SENM 16, M1M 5, M2M 1, M3M 2 | Unchanged linked URL; retained 404 |

No `ParID` or `ResID` overlaps between the two responses. The seven other units have 180 retained API rows, making 211 transport rows across all nine units. These counts do not resolve cross-session sporting relationships.

The [linked CWT-men medalists PDF](https://cmas-eventsystem.microplustimingservices.com/pdf/2026%20CMAS%20WCH%20FRD%20DEPTH%20CWT%20MEN%20MEDALISTS.pdf) returned HTTP 200, `application/pdf`, a verified PDF signature and 1,059,103 bytes. SHA-256: `f4851f926d05048feeb04cd6c233d3a324ae3ae339a9237fd8f49ee9b76d99ac`. Its one page prints August 21 and nine medalists across senior/M1/M2/M3 men. It is registered in a separate supporting archive; repeated registration yields the same acquisition ID. Medalists are not complete attempt results, so this document was not sent through attempt extraction or imported into B11.

## Validation and private evidence

The batch retains 24 fresh HTTP bodies: 21 successful responses and three explicit 404s. Every body hash and length was independently recomputed. Bodies are HTTP content after transport decompression, not compressed wire captures. Redirects, MIME headers, retrieval times and discovery context are retained. Browser notes distinguish observed UI/network behavior from direct HTTP captures; browser content export was unavailable, so no exported DOM is claimed.

The coordinator reproduced JSON accounting, comparison diagnostics and depth reconciliation. All 790 batch-2 CMAS files remained hash-identical; original/restored Athens PDF, manifest and text hashes also match. No B11 database was started or changed. This was research and documentation, with no production code changes or invented RED/GREEN tests.

Private evidence is under the batch-12 checkout's ignored `data/b12-evidence/`: `json-audit/` contains reproducible row/field inventories and comparison scripts; `historical-pdf/` contains provenance, page coverage and historical extraction references; `depth-gap/` contains exact API/document bodies and preservation checks; `discovery/` contains official-page/configuration captures, response metadata and browser notes; `supporting-archive/` contains only the newly registered medalists PDF. Names and source bytes are not Git fixtures. Remaining primary-source, semantic reconciliation, scope, genuine-review and cutover acceptance stays in issue #8.
