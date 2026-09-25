# AIDA HTML ingestion

`freediving.aida-html/extract!` extracts registered UTF-8 HTML into private schema-4 artifacts. `freediving.observations/import!` verifies those artifacts by replaying the parser against the archived source before inserting immutable observations. Existing PDF extraction identities and schemas 1-3 remain unchanged. Scope and acceptance: [issue #2](https://github.com/jamiepratt/freediving-results/issues/2).

Prefer supported official AIDA attempt HTML for new acquisitions. Keep rankings supplemental and CMAS on its document path. This is an operator-selected source preference, not an automatic crawler or fallback policy.

```clojure
(require '[freediving.aida-html :as html]
         '[freediving.observations :as observations])

;; Register source bytes with freediving.archive/register! first.
(def receipt (html/extract! archive-root source-sha256
                           {:actor "local operator" :config {}}))
(observations/import! database-url archive-root (:job-id receipt))
```

Run `observations/migrate!` as the database owner before importing schema 4. It applies checksummed migration 007, extending the extraction schema constraint without rewriting historical rows. The normal restricted application role still only inserts and reads observations. There is no network acquisition inside the parser.

## Evidence and interpretation

Supported tables have exact AIDA attempt headers, either the 11-column championship layout or the 12-column layout with Line and Official Top. The eight-column ranking layout is supplemental and separately identified. Unrecognized tables have explicit coverage counts; malformed rows remain unparsed. Missing source ranges, merged cells and nested tables do not become parsed rows.

Each candidate retains its 1-based document table and table-row coordinates, exact source row/cell HTML, decoded cell text, source hash, acquisition records and parser/tool versions. The artifact retains the complete HTML. Decoded fields trim outer formatting whitespace only; original cells remain available. Explicit metre performances are numeric with units. Colon time notation and record badges are retained; no time unit is inferred. Blank or absent fields remain unknown, including absent penalties and categories. Zero is preserved. A white card with a disqualification remark is flagged without changing either claim. Names and representation codes never establish identity or citizenship.

Date context comes from the selected date in the archived HTML. Ranking discipline/gender come from selected options. Acquisition evidence must record filters: AIDA's date selector changes session state and reloads the same URL. A URL alone cannot reproduce that selection. Archive any result-bearing dynamic payload required by another layout; this parser does not implement arbitrary live JSON feeds.

The archive now accepts structured browser acquisition context and verifies separately retained DOM evidence. See the [manifest contract](../README.md#manifest-contract). Official AIDA schedule links can also include an allowed literal `day_index` query; retain it together with the observed selected date. The [25 September inventory](championship-inventory-20260925.md) records the isolated restart corpus. The session audit below covers all exposed result dates for Wakayama 2025, Limassol 2025 and Budapest 2026. These private acquisitions do not replace the earlier corpus or establish complete championship coverage beyond those views.

The `:event-name` field currently retains the document title, which is generic on older event pages. Their specific event heading remains in the archived HTML. Points and penalties retain source text without numeric coercion. These limitations remain visible rather than being filled from inferred context.

Duplicate extraction/import calls reuse the same immutable job and observations when source bytes, acquisition evidence, parser, actor and config are unchanged. Changed source bytes or processing identity produce another retained version. This does not deduplicate athletes, rank and attempt listings, or mirrored publications into one sporting result.

HTML observations remain blocked from publication. The current review/publication path requires PDF page/line evidence; this change does not authorize HTML validation, identity merging, corrections or public release.

## Measured acquisition, 24 September 2026

| Source view | Captured rows | Coverage |
| --- | ---: | --- |
| Wakayama 2025 attempts, June 28 | 193 | DYNB, 91 female and 102 male |
| Wakayama 2025 attempts, July 2 | 182 | DYN, 86 female and 96 male |
| Mabini Depth Quest 2025 attempts, May 1 | 30 | Mixed depth disciplines, 6 female and 24 male |
| Wakayama DYN male ranking | 96 | Supplemental standings |
| Historical event 2789 | 42 | Format/contradiction check only, outside pilot coverage |

Sources: [Wakayama attempts](https://www.aidainternational.org/StartList/4349), [ranking](https://www.aidainternational.org/EventRanking/4349), [Mabini attempts](https://www.aidainternational.org/Events/EventResults-4545), [historical example](https://www.aidainternational.org/Events/EventResults-2789).

Direct HTTP returned 403 for the ranking and depth page. The Codex internal browser returned 200 without prompting for login. This does not establish whether authentication was present or required. Archived HTML is the browser's decoded HTTP response body re-encoded as UTF-8, not serialized DOM or compressed wire bytes. Retrieval times, final/discovery URLs, MIME types, SHA-256 hashes and selected dates are retained privately. No cookies or credentials were exported.

All rows in these completed-date views were in the full HTML response. The date-selection request only triggered a reload; its response was not retained. Live-date JSON polling was not needed. No pagination controls were found in the captured tables. Wakayama has four date tabs and Mabini five; the uncollected dates are coverage gaps, not evidence of zero attempts. This demonstrates representative pool/depth extraction, not complete event or historical HTML coverage.

The historical example's start 32 actually contains WHITE and Dqsp together. Both claims are preserved. Zero points alone are not treated as a red card: the June 28 source has 28 zero-point rows and 26 red cards.

## HTML versus PDF

The retained Wakayama PDF contains 182 ranking rows, including a 96-row segment corresponding to the live male ranking. There are 92 exact name/representation matches; their shared numeric fields agree. Four names differ. Rendered PDF inspection confirms three source-spelling/name-content differences and one hyphenated name whose PDF line break becomes an extra space in the existing PDF parser. Neither source was corrected. The PDF parser leaves discipline and category unknown for the matching male segment because those labels are absent in that exported segment. HTML ranking filters supply that context directly.

The 96 live male attempts agree with the live ranking on names, representation, announced/realised performance and points. Attempts additionally expose 85 white, one yellow and ten red cards, plus remarks. Rankings expose penalties but omit card/remark evidence. These listings and the mirrored PDF belong to the same AIDA source/export family, not independent corroboration. Comparisons are source-row reconciliation, not identity decisions. No general accuracy rate is claimed.

CMAS remains on `freediving.extraction/extract!` and the existing PDF/text path. The [official directory](https://www.cmas.org/freediving/results.html) was accessible; its 2025 depth championship link redirected to a timing application. The previously known [official CWT men PDF](https://www.cmas.org/media/com_eventbooking/Result_CWT-MS_M.pdf) remained directly accessible, matching the archived SHA-256 `2c0d8cd66d9ccb9fbef9ab6bd76ca94d38942c099ad13984bc07e6f2f89183f3`. This does not imply a direct directory-to-PDF link. CMAS document discovery and PDF extraction remain separate from AIDA table parsing.

A new isolated PostgreSQL corpus imported seven sources and 769 observations: 405 pilot attempts, 96 supplemental HTML rankings, 42 historical-format rows, 182 mirrored PDF rankings and 44 official CMAS PDF rows. All seven extraction and import reruns skipped duplicates. Source replay, every stored candidate payload and every artifact matched the archive; an independent HTML parser checked all 543 HTML rows and 5,933 field comparisons. Every row remains unreviewed. Changed-source revision retention and tamper rejection were demonstrated with synthetic fixtures, not invented publisher revisions.

Private acquisition, comparison and import evidence is kept under the batch's ignored `data/aida-html-ingestion-20260924-04/` directory. Real source documents and names are not fixtures in Git. Automated tests use synthetic HTML and existing synthetic PDF fixtures.


## Restart session reconciliation, 25 September 2026

The retained official attempt views cover all exposed result dates for three events. Dates and discipline/gender below were checked against actual result rows, not inferred from schedules.

| Event | Selected date | Result scope | Source rows |
| --- | --- | --- | ---: |
| Wakayama 4349 | 2025-06-28 | DYNB, female/male | 193 |
| Wakayama 4349 | 2025-06-29 | DNF, female/male | 176 |
| Wakayama 4349 | 2025-07-01 | STA, female/male | 162 |
| Wakayama 4349 | 2025-07-02 | DYN, female/male | 182 |
| Limassol 4350 | 2025-09-23 | CNF, female | 36 |
| Limassol 4350 | 2025-09-24 | CNF, male | 51 |
| Limassol 4350 | 2025-09-25 | CWTB, female | 51 |
| Limassol 4350 | 2025-09-26 | CWTB, male | 59 |
| Limassol 4350 | 2025-09-28 | FIM, female | 50 |
| Limassol 4350 | 2025-09-29 | FIM, male | 56 |
| Limassol 4350 | 2025-10-01 | CWT, male | 49 |
| Limassol 4350 | 2025-10-02 | CWT, female | 49 |
| Budapest 4852 | 2026-06-02 | DYNB, female/male | 220 |
| Budapest 4852 | 2026-06-03 | DNF, female/male | 209 |
| Budapest 4852 | 2026-06-05 | STA, female/male | 187 |
| Budapest 4852 | 2026-06-06 | DYN, female/male | 212 |

Sources: [Wakayama](https://www.aidainternational.org/StartList/4349), [Limassol](https://www.aidainternational.org/StartList/4350), [Budapest](https://www.aidainternational.org/StartList/4852). All 16 retained responses returned HTTP 200 and contain one supported table. Their 1,942 rows comprise 713 Wakayama, 401 Limassol and 828 Budapest attempts. The second batch added 11 previously missing sessions and 1,193 rows. All rows parsed; zero unsupported tables or unparsed rows were found in these views. All 1,942 remain unresolved for owner review.

An independent Python standard-library HTML parser read the retained response bytes directly, independently of jsoup. It compared every source row and cell range, decoded text, table/row coordinate, selected date, all 26 parsed fields and their known/unknown/invalid states, parse/review status and contradiction flags. The 16 unique source views passed 118,462 comparisons. Including eight separately retained recapture extraction versions, 24 artifacts and 3,483 versioned rows passed 212,463 comparisons with zero mismatches. These are source-to-parser checks, not independent publisher corroboration or reviewer attestation.

The unique rows retain 1,597 white, 121 yellow and 224 red cards, 232 zero-point values and seven explicit zero-metre realised performances. There are 34,466 parsed field states and 16,026 unknown states, with zero invalid field states. Source card and remark text remain separate from parser/review status; no additional sporting-result status was invented. In particular, zero points were not converted to disqualification. Categories, penalties, time units and other absent values retain the existing unknown semantics.

The audit discovered that a single browser evaluation returning a large DOM string can contain a literal `[Truncated]` marker. All eight original pool DOM captures were truncated; seven lost result cells, while Wakayama July 1 retained the full table but lost later document content. Their response HTML and schema-4 extraction were intact. Hash verification alone could not detect this capture defect: it correctly verified the incomplete bytes that had been supplied.

All eight pool views were recaptured using 50,000-character DOM slices, checking the concatenated character length and absence of truncation markers. Each new response SHA-256 exactly matched its earlier response. New timestamps and DOM hashes were registered as separate acquisitions; original evidence was preserved unchanged. Every one of the 16 views now has a complete DOM capture whose selected date and every result cell agree with the response after HTML-standard CRLF/CR-to-LF normalization. This normalization is reported separately from exact source-byte/cell comparisons. Fresh DOM evidence does not retroactively attest to an earlier capture.

Batch two retains 19 registered AIDA acquisitions across two isolated archives: 11 new sessions plus eight pool recaptures, representing 15 distinct response hashes. Twenty physical response captures include an unregistered duplicate September 25 capture retained during navigation verification. Archive registration and extraction reruns reused all 19 jobs/acquisitions; schema-4 replay validation also passed for the five earlier artifacts. No production parser changes were necessary.

Private evidence is under `data/championship-restart-20260925-b02/aida/`. Initial captures and `archive/` preserve the original acquisition history; `recaptures/` contains fresh pool captures and its separate archive. Reproduction scripts are `register_extract.clj`, `recaptures/register_extract.clj` and `reconcile.py`. The final audit is `recaptures/reconciliation-report.json`, with row-level checks in `recaptures/reconciliation-rows.jsonl` and input/artifact paths in `recaptures/reconciliation-input.json`. The first batch remains unchanged at its original private path.

With both private corpora still at their recorded locations, reproduce from the batch-two checkout:

```sh
clojure -M data/championship-restart-20260925-b02/aida/register_extract.clj
clojure -M data/championship-restart-20260925-b02/aida/recaptures/register_extract.clj
python3 data/championship-restart-20260925-b02/aida/reconcile.py data/championship-restart-20260925-b02/aida/recaptures
```

No database import, owner decisions, HTML publication support, identity merges or public replacement occurred. Rankings, alternative exports and publisher revisions are not silently deduplicated by this session audit. Remaining acceptance and later work stay in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).
