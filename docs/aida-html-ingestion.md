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
