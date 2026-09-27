# Isolated corpus relationship and status audit, 27 September 2026

This is a bounded read-only audit for [issue #16](https://github.com/jamiepratt/freediving-results/issues/16), at the b44 cutoff `2026-09-27T14:52:15.018622+00:00`. It covers the existing isolated OVH ingestion corpus and retained dated inventories. It is not a global competition census, extraction approval, athlete identity decision or publication decision. The public application and #8 corpus were not changed.

## Corpus and archive boundary

Direct isolated PostgreSQL counts are **91 extraction jobs, 89 distinct observation source hashes and 11,150 immutable result-row observation versions**. The two extra jobs are incremental extractions of the same Camotes World Cup and Challenge source hashes, with disjoint cited positions. They are not 11,150 unique sporting attempts. The archive's verified inventory contains **106 acquisition records, 104 original byte hashes and 104 exact final routes**. Two routes have a second acquisition with equal bytes; no exact final route has changed bytes in this retained archive. The b44 portable bundle index independently verified at SHA-256 `cb3ace6d6f8b4881e88d5311e16b5f9a6fabb5ec639ed4858195b9f72d84c649`.

The deterministic corpus replay inspected all 91 jobs and all 11,150 observations. It found **29 cited FFESSM within-PDF same-result edges**, **zero same-citation parser-revision edges**, and **two proven source-route byte-duplicate edges** in the prior route evidence (Vestico default/`comp=6`, Athens JSON repeated route). The archive has two equal-byte acquisition groups; acquisition records and route edges are different scopes and are not added together. No live changed-byte publisher revision was observed. The changed-byte classification remains a synthetic contract test only.

The later Italian Open source comparison was kept at source-position scope. The FIPSAS Open PDF SHA-256 `04028fb5148595652f03ab2ee5de4fe6e050aab4cda9c6abc3c03d642fe6995a` is a byte-distinct rendering of the imported B29/CMAS PDF SHA-256 `b29ee59117eedefd4784840be9386b9b7e5cfa86bbb89c18825220b6e2fb5d88`: all **31/31 page texts** matched after whitespace normalization, and the retained audit accounts for **183/183 printed positions**. The national PDF SHA-256 `317abde175276c7f919bd5744e15271f86a9f778e8844e7cec53feb5dfbfa739` has **171 distinct cited supporting positions** with matching canonical attempt fields; 12 B29 positions are absent from that narrower ranking. All three original PDF hashes and the Open page comparison were independently replayed here. Neither supporting PDF created primary observations or a row-level identity decision.

The retained TuttinApnea combined-view ledgers remain separate supporting evidence: February 2026 has **1,888 exact printed-value links** (944 STA, 944 DYN), SHA-256 `62818be166ad3bc497c5ea61df1c0f1882ba5fc6437be28f3cd77d99c4747d46`; January 2025 has **1,258 exact point links** (605 STA, 653 DYN), SHA-256 `4797386c92cc1be3ae7ccbf3c73d479851d5327f430c92aac8015e2e05cf38df`. Both hashes were rechecked. Supporting links are not additional attempts.

The private relationship input and output are in `/srv/freediving-ingest/runs/20260927-b44-fipsas-2026-pdfs-final/`. Output `corpus-relationship-audit.json` is mode 0600, SHA-256 `2a3ee7baefcbc4e06c1a66a6cacf2dd4af01266c9a2794c482ad52f70392c075`; replay was byte-identical. The ledger keeps exact observation edges, source-route edges, archive acquisition groups, source-position comparisons, and unknown candidates in separate collections. Its zero unknown-candidate count means no candidate was established under these supplied exact evidence contracts, not that every cross-source overlap has been resolved.

## Dated inventory dispositions

These are **link or source counts within each named index**, not distinct event or sporting-attempt totals. The same event can appear in multiple indexes. The status language is the final status in the linked [dated inventory](2025-onward-source-inventory-20260926.md); a source can have a partial import while still being unsupported as a complete source.

| Bounded index | Links | Imported | Already present or duplicate | Unsupported | Inaccessible | Row limit |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| CMAS 2025 | 15 | 7 | 5 | 3 | 0 | Two scanned sources have partial imports: Camotes World Cup 143/146, Challenge 65/66; their three remaining positions are unresolved. |
| CMAS 2026 | 14 | 4 | 5 | 5 | 0 | Unsupported includes image-only and unavailable complete result sources. |
| FFESSM 2025 | 11 | 11 | 0 | 0 | 0 | Daily versus category ranking overlap remains unproved. |
| FFESSM 2026 | 9 | 9 | 0 | 0 | 0 | The 29 cited same-result links are within imported source PDFs. |
| Apnea Academy index | 20 | 7 | 0 | 13 | 0 | Supporting combined/team views and JPG/XLSX formats are not imported as attempts. |
| VDST index | 7 | 7 | 0 | 0 | 0 | All seven linked protocols imported after bounded continuations. |
| FEDAS index | 4 | 4 | 0 | 0 | 0 | Four linked championship PDFs imported. |
| FIPSAS 2026 linked PDFs | 38 | 28 | 2 | 7 | 1 | 3,387 primary positions imported; two retained unparsed within that count. |

For FIPSAS 2026, the calendar has **71 distinct event card IDs**. Fifty cards link to the 38 PDF URLs; 21 have no result PDF link at this cutoff. The 38 links map to 50 known event IDs: 40 on imported links, eight on unsupported links, one on the inaccessible link and one on the two duplicate PDF links. The duplicate PDFs share one event ID. All 71 event cards are known card identities, not 71 distinct competitions or sessions. Only 35 event pages were acquired; 36 event-page requests returned HTTP 503. Those page gaps are separate from PDF link dispositions.

The FIPSAS 2026 linked-PDF audit found 37 distinct retained source hashes and one 404 link without bytes. Each of the 28 imported PDF hashes has one source-bound row count, and all **3,387** imported positions match the isolated database's observation count on those same hashes, with zero per-source mismatches. Nine cited junior standings repeats in b43 and the 183/171 Italian Open supporting positions were excluded from that primary count. Ten links have no primary row count in this audit: seven unsupported scans, one inaccessible URL and two supporting duplicates. Their printed-row totals are not silently set to zero.

The separate FIPSAS 2025 dated calendar retained 17 event pages and one eligible 2025 final PDF, which imported 49 positions. Fourteen `Classifiche` labels had no link, and two cards linked a 2026 PDF. These are card/link findings, not additions to the 38-link 2026 cohort.

The private normalized count input and output are in the same b44 run. `corpus-status-audit.json` is mode 0600, SHA-256 `b00bd417ac719c8e674323e81f2e211a9f473cba686b2c8f7ea5575ea4325738`; replay was byte-identical. The report separates event cards, links, source hashes, typed imported positions and the wider 91/89/11,150 corpus snapshot. The script requires explicit row basis IDs before summing rows.

## Reviewable unknown clusters

| Candidate cluster | Why it stays unknown |
| --- | --- |
| FFESSM 2025 daily and category rankings | Several category PDFs print no competition date. A shared name, category and result cannot establish the same attempt or revision order. |
| CMAS index links marked already present against #8 | Route identity is known, but current index-download bytes were not fetched and compared to the older championship originals. |
| Submania 2026 Noxygen result view versus imported CMAS Croatian Open | The retained browser attempt did not produce a complete response or DOM, so no row-level comparison exists. |
| FIPSAS 2026 unlinked cards, seven scans and the 404 PDF | Missing or unreadable result bytes prevent a complete source-row census and cross-source comparison. |
| TuttinApnea January combined sheet unmatched cells | Exact supporting point links exist for 1,258 cells; unmatched cells remain source-level unknowns. |

These gaps remain under [issue #16](https://github.com/jamiepratt/freediving-results/issues/16). The synthetic changed-byte fixture proves the reviewable-unknown behavior, but no live predecessor/successor relationship or unique-attempt total follows from this audit.
