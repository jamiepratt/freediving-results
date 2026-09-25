# Athens mirror discrepancy audit, 25 September 2026

The retained Venezuelan federation mirror contains 792 result rows on 67 pages. Two additional historical extraction candidates are detached `fi` tokens, not result rows. The PDF remains a mirror acquired on 23 September, SHA-256 `9bb85a2d3a672f2d3e78f156478874fd02de5fd717c9653fe2c663a2eba8f2ed`. This audit neither establishes official CMAS acquisition nor substitutes the mirror into the [restart corpus](championship-corpus-20260925.md). Remaining acceptance stays in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Independent PDF baseline

A source-only baseline uses PDF table borders, name-cell row boundaries, positioned glyphs and numeric column boundaries. It retains every row's exact category/date/discipline evidence, literal values, blank cells, printed zeros, shared cells and glyph coordinates. Historical parser output and timing JSON are excluded from baseline construction. Eleven complete pages were rendered and inspected; the other 56 received programmatic reconciliation, not claimed visual review.

All 792 rows reconcile one-to-one with the historical extraction, with two fragments accounted separately. Compared raw fields agree on 775 rows. The remaining 17 comprise two truncated names caused by detached ligatures, seven literal-ligature versus expanded-text representations, three names with ambiguous combining-mark order, two rows with shared cells, and three multiline notes already retained correctly in parsed values and source evidence. No numeric extraction discrepancy remains against this baseline.

The three combining-mark rows have unambiguous rank/result columns but no defensible Unicode ordering for the overlapping name glyph. The two shared-cell rows visibly share rank and notes; the text extractor does not retain the cell-border evidence needed to assign those fields safely. Page 3's detached ligatures belong to printed names in the independent geometry baseline, but Poppler places the detached tokens outside the table. That output alone cannot safely reconstruct the names.

## Versioned extraction behavior

Schema-3 `cmas-athens-pool/7` adds positioned evidence and partial field recovery to the unchanged `/6` parser. The three combining-mark rows retain unparsed status and unknown names, while rank, representation, results, notes and context gain independently verified field values. Existing timing-unit ambiguity remains intact. Unique physical-row matching conserves the extracted glyph multiset; it does not reconstruct a name or normalize source values.

The 41 parsed name transcriptions on page 3 now carry the substantive `detached-name-fragment-unresolved` flag. This conservative page-wide warning prevents silent acceptance of truncated names when Poppler cannot locate the fragments correctly. Original transcriptions remain available. The two fragments and two shared-cell result rows remain explicit and unresolved.

Counts remain 794 candidates: 787 parsed result rows, five unparsed result rows and two fragments. Every original raw/parsed payload, coordinate, source line and metadata reference remains unchanged. A separate new extraction job retains bbox evidence; an unchanged repeat reuses that job. The new version does not rewrite historical jobs or increase parsed counts.

Import replays `/7` against the hash-verified PDF, rejecting altered fields, missing warnings, geometry tampering and parser-identity downgrades, including forged empty candidate lists. Genuine `/6` artifacts retain exact source replay through the preserved implementation. Historical `/4` and `/5` bytes remain retained, but those versions cannot be freshly imported from an Athens PDF without an executable historical replay contract. Layout-only `parse-pages` continues to expose `/6`; normal PDF extraction selects `/7`. Mixed-source dispatch retains existing parser precedence.

Independent comparison verified all new partial fields, geometry, warnings and unchanged evidence. Ten semantic corruption controls rejected injected defects. The coordinator reproduced those checks and verified all 81 original/copied historical archive files unchanged. The copied recovery archive needed its empty private staging directory restored before extraction; originals were not modified.

## Comparison with supporting JSON

All 42 retained JSON response hashes, lengths and 792 row objects were checked against original bytes. The malformed surname retains its one invalid byte through reversible escaping; no guessed encoding or replacement character is introduced. JSON remains supporting evidence under the agreed PDF-source contract.

| Diagnostic input | PDF rows | JSON rows | Multiset overlap | Residual PDF/JSON multiplicities | Name-key pairs | Unpaired PDF/JSON rows |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Historical parsed output | 787 | 792 | 649 | 138 / 143 | 702 | 90 / 90 |
| Independent source baseline | 792 | 792 | 654 | 138 / 138 | 705 | 87 / 87 |

The first row's unpaired PDF count includes five unparsed result rows. Source-baseline coverage does not mean 792 production rows parse successfully.

The multiset key uses discipline, category/sex, exact representation and a formatting-normalized final token. Original commas, precision, leading zeros, blanks and values remain retained; token normalization establishes no units or semantic equivalence. Six groups have repeated keys, including two imbalanced groups where a particular residual row cannot be identified. Full group membership and residual multiplicity are retained instead of assigning arbitrary pairs.

The separate name key uses discipline, Unicode casefold and whitespace collapse, accepting only singleton groups on both sides. Six pairs involve casefold expansion of a ligature; raw glyph differences remain explicit. Unknown names are excluded. These are diagnostic correspondences, not athlete identities, equivalent attempts or reviewed replacements. A materially different repeated JSON participant code remains two records. Results and Summary presentations are not double-counted.

Among the 705 diagnostic pairs:

- Four numerical disagreements, one category disagreement and two blank-final versus populated-JSON differences are confirmed against PDF renders. They cannot be fixed by copying JSON into extraction output.
- 58 representation differences use PDF `CMAS1` versus JSON `CMA`; their semantic mapping remains unestablished.
- 48 comparisons contain PDF zero versus JSON blank; 24 compare result tokens with JSON `DSQ` or `DNS`.
- 542 final-token differences are formatting-only under the stated diagnostic rule. Overall PDF ranks and per-heat JSON ranks are not interchangeable.

Every source field and context remains in the private ledgers. Realized values, notes, status codes, penalties, awards and splits have no established cross-source semantic mapping; final-token agreement does not declare those fields equal. All unmatched rows remain explicit.

## Private evidence and review limits

The batch checkout retains ignored `data/b14-evidence/pdf-audit/` and `cross-audit/`. The former contains the frozen source baseline, original text/bbox evidence, renders and row reconciliation. The latter contains complete raw ledgers, field inventories, collision groups, unmatched rows and a compact seven-row source-discrepancy packet, plus invalid-encoding and identifier-collision evidence. Real names and source documents are not Git fixtures.

The coordinator independently reproduced the baseline and seven complete cross-source reports byte-for-byte. Original acquisition files and historical extraction artifacts remain immutable. Neither automated checks nor visual inspection supply owner attestations, source supersession, identity decisions or publication authority.

Validation passed: core 180 tests / 1,211 assertions; isolated PostgreSQL 17, 211 tests / 1,193 assertions across 14 suites; targeted pipeline registration one test / two assertions. Ordered Clojure repair and lint checks passed. Observed RED/GREEN checks covered partial field recovery, fragment warnings, extraction versioning/replay, timing uncertainty, empty-candidate downgrades and mixed-source dispatch. Synthetic fixture repairs are recorded separately from behavior regressions. Test databases were stopped and removed; B11 stayed stopped and unchanged. All 880 fingerprinted earlier acquisition/audit files also remained unchanged.

The remaining unresolved names and shared-note meaning need authoritative spelling/source semantics. The bounded native-PDF follow-up below separates those gates from the resolved fragment associations. Missing official PDFs, category completeness, source disagreement and later genuine review remain separate gates in issue #8. No blanket approval request, corpus import, policy activation, deployment or public cutover is part of this audit.

## Bounded native-PDF conclusion

A final audit froze ten original inputs before inspecting native content streams, font mappings, marked-content annotations, positioned glyphs and cell borders. The five relevant full-page renders were inspected. Original and copied hashes still match; the source PDF hash above is unchanged.

Page 3's two detached fragments are conclusively associated with name cells at row 2 and row 8. Each native U+FB01 glyph is enclosed in an explicit `ActualText (fi)` span at the corresponding name position. This resolves source-level association and the publisher-provided ligature expansion without a spelling guess. These two fragments need no publisher clarification. The private packet retains exact glyph strings and coordinates.

For page 13 row 3, page 41 row 3 and page 58 row 1, the font explicitly maps character `21` to U+0307. Native paint order places the mark after `i`; horizontal coordinate sorting places it after the following `d`. Separate text positioning explains the conflict. There is no name-level ActualText or document structure tree establishing intended logical spelling. Paint order is retained as evidence, not promoted to an authoritative name. The exact remaining request is the publisher's intended Unicode spelling for these three printed rows.

Page 59's first two rows demonstrably share rank and Notes cells. The intervening horizontal border omits those columns. That proves the source cell span; it does not independently assign every gold-medal/world-record statement to each performance. The remaining publisher question is whether every statement in the shared Notes cell applies independently to both performances.

This closes the bounded investigation. Current `/7` inputs are Poppler layout/bbox evidence, which loses the native fragment positions and cell borders inspected here. No parser version, candidate, field, warning or historical payload changed. A new native extraction backend is outside this concluded supporting-mirror audit; these findings do not turn the mirror into an official restart source or satisfy owner review. Issue #8 retains the precise external gates.

Private `data/b16-glyph-evidence/` contains `REPORT.md`, reproducible `audit.py`, `frozen-inputs.json`, native page streams and `native-evidence.json` (SHA-256 `9cf49238266ed824c904e227baf9b9c80ac3d5c680d3f04a17bf29382a95d1bb`). Hash verification and exact glyph assertions passed, and a repeat reproduced the same evidence hash. This was research/documentation validation; no product behavior or TDD cycle is claimed.
