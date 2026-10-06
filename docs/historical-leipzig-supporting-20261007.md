# Leipzig supporting representations, 7 October 2026

This bounded [issue #195](https://github.com/jamiepratt/freediving-results/issues/195) pass reconciles four links observed in the [previous Leipzig report](historical-leipzig-2024-views-20261007.md). Cutoff: **2026-10-06T22:45:57Z**, 7 October in Warsaw. It preserves both original EventPage result views and all earlier private evidence. Competition dates remain 2-3 November 2024.

The internal browser inspected [StartList day 0](https://www.aidainternational.org/StartList/4036?day_index=0), [day 1](https://www.aidainternational.org/StartList/4036?day_index=1), [navigation](https://www.aidainternational.org/StartList/4036#start) and [ranking](https://www.aidainternational.org/EventRanking/4036). Seven StartList acquisitions returned two byte objects. Two EventPage context acquisitions exactly reproduced the retained B41 hashes; one ranking acquisition retained its default view. All ten responses were HTTP 200 HTML. Originals are CDP-decoded response bodies re-encoded as UTF-8; receipts retain request, HTTP response and capture clocks separately, plus URLs, MIME, byte length and SHA-256.

All three StartList URLs returned **Results**, with all 11 result columns. After the observed EventPage `#day_1` selection, each showed 42 rows and parent `li.active` date 2024-11-02. After `#day_2`, each showed 40 rows and date 2024-11-03. The initial day-0 request also showed November 3. Thus neither query value establishes a selected date independently of the observed browser session. Displayed title/header identify Leipzig. Limassol 2021 appears only inside an HTML comment.

| Retained representation | Rows | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| StartList, 2 November | 42 | 70,889 | `8645e1d2826e09a5776dbd65d262ebf549de93c95a1cc47e0890d9f827d3ee2e` |
| StartList, 3 November | 40 | 68,918 | `c7d1aab88111ff78e2f23ba20de8364ccf238f77127be63a36b80ace7d9acfb9` |
| Default ranking, male DYN | 9 | 26,801 | `9c42c41923798100ab1839173974c8c5c038d22c8a6131079c8bdc67346e5e10` |

An independent HTML offset tree compared every StartList row against **both** retained dates. The corresponding date matches all 42/40 ordered rows and 462/440 normalized fields, with zero unmatched, missing or changed normalized fields. Against the other date, zero complete rows match: November 2 has 42 unmatched rows and 40 missing baseline rows; November 3 has 40 unmatched and 42 missing. All 82 row HTML representations differ from EventPage. Only 164 of 902 cell HTML, raw-text and decoded-text representations are equal in each comparison; all 902 normalized values match. These differences remain in the private ledger.

Across the two unique StartList objects: 72 WHITE, six RED, four YELLOW; six zero Points; two exact `Dqsp` remarks; zero blank fields. These are **82 supporting source positions, zero new staged versions**, separate from the existing 808 census positions/versions. Seven acquisitions do not multiply that object-level count. Normalized field agreement is descriptive comparison, with no sporting identity or source-equivalence attestation.

Ranking defaults to DYN/Male and nine rows with eight aggregate columns: Medals, #, Name, Nationality, Result, Announced, Points and Penalties. All nine match November 3 male DYN rows on the five shared name/nationality/result/announced/points fields, with zero November 2 matches. Medals are blank in nine rows, Penalties are zero in nine, and Points are zero in one. Card, remarks, official top and start columns are absent. Its default representation is resolved as `not-attempt-source`; wider filters remain unchecked. Its nine aggregate rows add no attempt positions. The three actual Results leads are checked but unresolved, bound to both retained supporting sources and the comparison ledger. The existing contract represents this honestly; no code extension or fabricated TDD RED was needed.

The cumulative manifest contains three events, 16 sources, 808 census positions, 808 private versions, three gaps, zero relationships, 12 routes and 412 leads. Real validation/query succeed. The ordinary 2023 gate stays **CLOSED**, with 424 blockers: ten routes, 397 nonresolved leads, 14 unknown-category checks and three census gaps. Resolving the default ranking removes one lead blocker; Results uncertainty stays explicit. Ordinary ingestion rejects before reading a nonexistent candidate or creating output. Legacy importers are not universally intercepted. All 37 existing cohort, private-adapter, selected-parser and census tests pass. No adapter behavior or stage versions changed, so fresh adapter replay/tamper tests were unnecessary; prior original/stage bindings were independently reverified.

Independent audit passes **22,179 checks**, including all 295 acquired supporting/aggregate row instances, 3,218 cells and 12,872 cell representations. It caught newline normalization in November 3 row 29's private ledger fragment; rebuilding from exact byte decoding preserved its embedded CRLF, then every check passed. Source originals were unchanged. The final corpus retains the complete 229-file B41 copy and verifies all 672 indexed B38-B41 original files and three original stage bindings. All 268 final indexed files pass size/hash checks; directories are 0700, files 0600, with no symlinks.

Private corpus: `/Users/jamiep/.codex/private-corpora/issue195-b42-leipzig-supporting-sources/`. SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| Index | `230f9cef86463142cb48cd70c0e78875a8e67a14bff7e62927ba0ae410b4da8f` |
| Checkpoint | `fb455b31532f313cbffb0afba7e7097a4308a57173acbd1a3a11e0c37e73f7b4` |
| Manifest | `91765e5dc4120d18456335b35aeda44a07eb6ef6cc56e109e2b8e6dd6498f1e4` |
| Comparison ledger | `b9538f2ae6d588372853af6d711c64a4b07a3cc4f5c855409833ba03fe34b897` |
| Independent raw audit | `4a9e8a63a78aaca62827ae3d3928c105db4224c6df36c02227b43b53da213eb3` |
| Independent manifest audit | `7d2c954b101da4bccbb6ca4b1273bc0c99b015c00012ff8ee9d5b3216543d122` |

All earlier source paths, receipts, stage bindings, 412 lead IDs and typed resolutions remain. Category, finality, wider publisher history and source equivalence remain unknown. All rows remain unreviewed. No database, production, deployment, owner-authority, identity, relationship, VPN or paid-model changes occurred. #195 remains open for its full historical and genuine review acceptance.
