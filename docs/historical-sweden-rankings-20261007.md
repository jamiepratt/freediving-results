# Swedish 2024 ranking filters, 7 October 2026

This bounded [issue #195](https://github.com/jamiepratt/freediving-results/issues/195)
pass checks the three ranking links retained with the
[Swedish result originals](historical-sweden-2024-views-20261007.md).
Cutoff: **2026-10-06T23:01:47Z**, 7 October in Warsaw. Full historical acceptance
remains open.

The actual filter forms submit POST to the same EventRanking URL. Every observed
discipline option, OVERALL, Male, Female and the Select Gender placeholder was
checked. The placeholder submits an empty gender value but returns Male. GET
views inherit the preceding event's filters in the same browser session:
Stockholm and Juniordykarna initially returned OVERALL/Female. The initial Lundby
GET selected STA/Male but showed zero rows; explicit STA/Male POST returned five.
That initial GET omits five baseline male STA rows, all present in the later POST.
An empty default response does not establish absent performances.

| Actual ranking source | Discipline options plus OVERALL | Requested POST combinations | Effective discipline/gender views | Aggregate rows across effective views |
| --- | --- | ---: | ---: | ---: |
| [Lundby 4412](https://www.aidainternational.org/Events/EventRanking-4412) | STA | 6 | 4 | 12 |
| [Stockholm 4396](https://www.aidainternational.org/Events/EventRanking-4396) | DYN, DNF, STA, DYNB | 15 | 10 | 12 |
| [Juniordykarna 4401](https://www.aidainternational.org/Events/EventRanking-4401) | DYN, DNF, STA, DYNB | 15 | 10 | 16 |

All **39 acquisitions** returned HTTP 200 HTML, with no document redirects.
The three GETs and 36 POSTs produced 25 distinct byte objects. Retained originals
are CDP-decoded response bodies re-encoded as UTF-8, not compressed wire bytes.
Receipts retain genuine request and HTTP-response clocks separately from capture
completion, exact request method/body, URLs, MIME, bytes, hashes, prior controls
and rendered selected controls. All 24 effective discipline/gender combinations
were checked, including empty views. No result pagination controls were observed;
the one date link on each page navigates to EventResults.

The ledger retains **69 acquired aggregate row instances, 484 cells and 1,936
cell representations**, including exact row/cell HTML, raw text, decoded text and
normalized value. Counting each byte object once yields 40 aggregate positions:
22 discipline rows and 18 OVERALL rows. These are separate from sporting attempt
positions and private observation versions.

Discipline tables print Medals, #, Name, Nationality, Result, Announced, Points
and Penalties. All 170 acquired shared fields match the result originals on
Name/Diver, Nationality, Result/RP, Announced/AP and Points. Every explicit POST
discipline view has zero unmatched rows, missing reference rows or changed
common fields. This descriptive comparison grants no source-equivalence
attestation or owner review. OVERALL prints #,
Name with country, discipline points and total Points. Its 107 discipline cells
retain 43 exact printed scores matching original Points and 64 literal `-`
without a retained component. All 35 acquired totals equal the printed numeric
component sums. A dash remains a printed dash, without an inferred dive
or zero. Formatting differences remain in the ledger. Medals, ranks and
Penalties retain their aggregate context; Start, Line, Official Top, Card and
Remarks are absent, and OVERALL also omits AP/RP. No card, penalty, finality,
category or sporting relationship is inferred.

All three ranking leads have typed `not-attempt-source` resolutions limited to
the checked filter grid. Their 25 source objects bring the cumulative manifest
to **six events, 44 sources, 830 census positions, 830 private versions, 15 routes,
418 leads, six gaps and zero relationships**. The separate prior 82 Leipzig
supporting Results positions and nine aggregate positions remain preserved.
There are zero new attempt positions, private versions, database imports,
reviews or relationship decisions. Distinct sporting attempts remain unknown.

Real CLI validation and three ranking queries succeed. Ordinary 2023 staging is
**CLOSED**, with 433 blockers: 13 routes, 397 nonresolved leads, 17 unknown-category
checks and six census gaps. Ordinary ingest rejects before opening a nonexistent
candidate or creating output. Existing legacy importers are not universally
gated. All 37 unchanged parser/cohort tests pass; new RED/GREEN is inapplicable
because no reusable behavior changed. No stage changed, so additional adapter
replay/tamper cycles were unnecessary; every prior source/receipt/stage binding
was independently reverified through the immutable indexes.

Private evidence remains outside Git at
`/Users/jamiep/.codex/private-corpora/issue195-b44-sweden-ranking-sources/`.
Prior B38-B43 indexes reference 983 verified files without recursively copying
corpora. All 830 prior positions/versions, 418 lead IDs, staged source/receipt
bindings and prior typed attempt-result resolutions remain. Directories are
0700, files 0600, with no symlinks. The independent audit passes 51,403 checks
before sealing and another 576 read-only seal checks: **51,979 checks, zero
errors**. All 108 indexed files and index/checkpoint/manifest/ledger cross-bindings
pass; the index excludes itself and the checkpoint. No evidence file changed
after sealing.

| Artifact | SHA-256 |
| --- | --- |
| Index | `958ebf38414ed973383be366b1462faa12114b71c1a9497d236b2adcce039bc4` |
| Checkpoint | `5c269b644014705c283528a9ea0bc51cfcdd5687e432ef3b0e87e4732b0ea356` |
| Manifest | `13cb6b47383e217b5d7eb7d064075e0d182bc267fcb195d67ab13a40740d9cc7` |
| Comparison ledger | `511d9d5831836bbc8c0072b0e3197334814f7488d859a2174fbdd7521f697b68` |

Formal category, finality, wider publisher history, full event and historical
coverage remain unresolved. No production, deployment, authority, public,
identity, relationship, VPN or paid-API changes occurred. Remaining acceptance
and the next bounded calendar scope are recorded in
[issue #195](https://github.com/jamiepratt/freediving-results/issues/195).
