# Official championship acquisition, 25 September 2026

Cutoff: 2026-09-25. This is a dated discovery and acquisition inventory, not a complete results corpus. Scope is completed CMAS and AIDA world championships in 2025-2026, including discovered category divisions. Unmet coverage and implementation acceptance remain in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

`Acquired` means bytes and provenance are retained privately, not reviewed or published. `Unresolved` means coverage has not been established. `Unavailable` describes an observed failed access path, not proof that the source does not exist. Planned schedules do not establish actual result dates or zero attempts for omitted categories.

## Event inventory

| Championship | Official evidence and discovered sessions | Acquisition/coverage at cutoff |
| --- | --- | --- |
| CMAS 2025 indoor, Athens, seniors/juniors | [Event](https://www.cmas.org/freediving-events/2025-cmas-world-championship-freediving-indoor.html), [information pack](https://www.cmas.org/media/com_eventbooking/2025%20SENIORS-JUNIORS%20WC%20Freediving-Pool-Athens%20infopack%20March.pdf): May 18-25 overall. Planned competition May 20 DNF; May 21 DYN-BF; May 22 8x50 and 2x50; May 23 STA and 4x50; May 24 DYN. | Legacy selectors 1 and 2 together expose 42 acquired result JSON views across seven disciplines and junior/senior/master men/women. All 30 configured PDF links returned 404. Selector 2 covers distance; selector 1 covers static/speed. Age subdivisions and rows remain unreconciled. See [timing evidence](cmas-timing-acquisition-20260925.md). |
| CMAS 2025 indoor masters, Athens | Same pack places masters on the same competition days; separate [archive entry](https://www.cmas.org/document/2025,-cmas-world-championship-freediving-indoor-masters/download.html). | Selector 1 is not masters-only: both legacy selectors contain junior/senior/master categories. Result JSON retained; PDFs unavailable through the observed configured links. No master row completeness claim. |
| CMAS 2025 depth seniors, Mytikas | [Event](https://www.cmas.org/freediving-events/cmas-2025-world-championship-freediving-depth.html), [pack](https://www.cmas.org/media/com_eventbooking/2025%20SENIORS%20WC%20Freediving-OUTDOOR%20infopack.pdf): September 5-18 overall. Planned September 9/10 CWT M/W, 11/12 FIM M/W, 14/15 CNF M/W, 16/17 CWT-BF M/W. | Eight senior result PDFs acquired from competition 3 across all four disciplines and both genders. Actual FIM dates are September 12/13. Competition 3 explicitly contains Mytikas seniors/masters; competition 4 identifies unrelated Asian junior finswimming. The federation event link is stale or incorrect. Distinct timing/federation CWT files are not automatically revisions. |
| CMAS 2025 depth masters, Mytikas | [Event](https://www.cmas.org/freediving-events/cmas-2025-world-championship-freediving-depth-masters.html), [pack](https://www.cmas.org/media/com_eventbooking/MASTERS%202025%20WC%20Freediving-OUTDOOR%20infopack.pdf): September 5-18. Same planned discipline sequence. [M1 men CWT result](https://www.cmas.org/media/com_eventbooking/Result_CWT-MM1_M.pdf) is dated September 10, unlike the planned men's September 9 date. | Twelve masters result PDFs acquired from competition 3: men M3 and M1+M2 in each discipline; women M1 in FIM/CNF/CWT-BF and M1+M3 in CWT. Row reconciliation and absent category headings remain unresolved. |
| CMAS 2026 indoor seniors/juniors, Novi Sad | [Event](https://www.cmas.org/freediving-events/2026-cmas-world-championship-freediving-indoor-juniors-seniors.html): June 9-15 overall. Acquired result headers: June 11 DNF and 8x50; June 12 DYN-BF and 2x50; June 13 STA and 4x50; June 14 DYN. | 32-page official PDF acquired. All seven disciplines have senior and junior men/women headings. Row reconciliation and extraction review unresolved. |
| CMAS 2026 indoor masters, Novi Sad | [Official results](https://www.cmas.org/document/2026,-cmas-world-championship-freediving-indoor-masters/download.html), same four result dates/disciplines as seniors/juniors. | 34-page official PDF acquired. Present category headings listed below; missing headings remain unresolved. |
| CMAS 2026 depth, Roatan | [Event](https://www.cmas.org/freediving-events/2026-cmas-world-championship-freediving-depth.html): August 14-27, seniors and masters. Nine linked result sessions: CWT M, CWT W, FIM M, FIM W, CWT M continuation, CNF M, CNF W, CWT-BF M, CWT-BF W. | All nine unit result/document API responses retained, including August 17 CWT men and August 21 continuation. Seven result PDFs acquired, 15 pages; linked CWT men PDF returned 404. First CWT unit has no linked PDF. Schedule dates and observed senior/master headings are detailed in [timing evidence](cmas-timing-acquisition-20260925.md); rows and continuation overlap remain unreconciled. |
| AIDA 2025 pool, Wakayama, event 4349 | [Official attempts](https://www.aidainternational.org/StartList/4349): June 28 DYNB, June 29 DNF, July 1 STA, July 2 DYN. | All four displayed tabs acquired and independently reconciled: 193, 176, 162, 182 rows, 713 total; men/women present. Fresh complete DOM captures supplement preserved truncated originals. Coverage is limited to exposed views. |
| AIDA 2025 depth, Limassol, event 4350 | [Official event and schedule](https://www.aidainternational.org/EventPage/4350): September 20-October 3 overall. Eight result dates: September 23 CNF W; 24 CNF M; 25 CWTB W; 26 CWTB M; 28 FIM W; 29 FIM M; October 1 CWT M; October 2 CWT W. | All eight displayed result dates acquired and independently reconciled: 36, 51, 51, 59, 50, 56, 49, 49 rows, 401 total. Actual date/discipline/gender cells checked. The official page extends beyond the earlier announcement's October 1 end date. |
| AIDA 2026 pool, Budapest, event 4852 | [Official event](https://www.aidainternational.org/EventPage/4852): May 30-June 7 overall. Displayed result dates and schedule: June 2 DYNB; June 3 DNF; June 5 STA; June 6 DYN. | All four displayed result dates acquired and independently reconciled: 220, 209, 187, 212 rows, 828 total; men/women present. Complete chunked DOM recaptures retained separately. |

The [CMAS archive](https://www.cmas.org/freediving/results.html) has separate masters sections. Its para section has no 2025-2026 completed-result entry at this cutoff. That does not establish absence of para participation inside another championship. Dedicated AIDA junior/master/para editions were not established by this discovery; category completeness remains unresolved.

The [AIDA 2026 depth event](https://aidainternational.org/EventPage/4987), September 27-October 10, and [CMAS 2026 para championship](https://www.cmas.org/freediving/calendar.html), November 25-30, are outside the completed-event cutoff. World Games, cups, local and continental competitions and pre-2025 history are excluded. Rankings are supplemental publications, not additional attempts or independent corroboration.

## Acquired CMAS masters category headings

The 2026 masters PDF contains these headings; an absent heading is not a zero-result declaration. M1/M2/M3 are retained publisher labels, without inferred age boundaries.

| Date | Discipline | Present headings |
| --- | --- | --- |
| June 11 | DNF | M1 men/women, M2 men/women, M3 men |
| June 11 | 8x50 | M1 men/women |
| June 12 | DYN-BF | M1, M2, M3 men/women |
| June 12 | 2x50 | M1 men/women, M2 men/women, M3 men |
| June 13 | STA | M1, M2, M3 men/women |
| June 13 | 4x50 | M1 men/women, M2 women, M3 men |
| June 14 | DYN | M1, M2, M3 men/women |

## Batch 1 retained sources and access evidence

The first isolated private root is `data/championship-restart-20260925-b01/` in the preserved batch 1 checkout. Its original DOM evidence includes capture defects identified below; hash integrity did not establish DOM completeness. It contains incoming response bytes/metadata, separate browser DOM evidence, a content-addressed archive and replayable extraction artifacts. Earlier corpora and public data were not modified. Real names and source bytes are not Git fixtures. Acquisition is not held-out identity eligibility, extraction approval or publication authority.

| Official PDF | Bytes | SHA-256 |
| --- | ---: | --- |
| [2026 indoor seniors/juniors](https://www.cmas.org/document/2026,-cmas-world-championship-freediving-indoor/download.html) | 3,048,637 | `f403777b250b7ae816adea945db4cd5ddea51be7349c2efa57046a08671a5758` |
| [2026 indoor masters](https://www.cmas.org/document/2026,-cmas-world-championship-freediving-indoor-masters/download.html) | 3,314,467 | `b221351a6043d820384b0745903c146bcb6e4ffa58c7e17984d032a43156e0c6` |
| [2025 CWT senior men](https://www.cmas.org/media/com_eventbooking/Result_CWT-MS_M.pdf) | 160,801 | `2c0d8cd66d9ccb9fbef9ab6bd76ca94d38942c099ad13984bc07e6f2f89183f3` |
| [2025 CWT senior women](https://www.cmas.org/media/com_eventbooking/Result_CWT-WS_W.pdf) | 132,920 | `dbaa2eeb572c1ccd851897e7fa39cbbf8ee3717312570062c0a5ff9e1a739c92` |

All four returned HTTP 200 and `application/pdf`, with verified PDF signatures. The known CWT URLs were supplied by issue #8; the current event page does not directly link them. Their manifest discovery URL records the issue, not an invented archive-to-PDF link.

Observed CMAS HTTP 303 destinations, followed by HTTP 200 `text/html`:

| Archive entry | Final URL |
| --- | --- |
| 2025 indoor | `https://results-ws.microplustimingservices.com/CMAS/Results/#/2/schedule-bydate` |
| 2025 indoor masters | `https://results-ws.microplustimingservices.com/CMAS/Results/#/1/schedule-bydate` |
| 2025 depth | `https://cmas.microplustimingservices.com/#/competition-schedule/3` |
| 2025 depth masters | `https://cmas.microplustimingservices.com/event-detail/4` |
| 2026 depth | `https://cmas.microplustimingservices.com/#/competition-schedule/30` |

The two older indoor responses share one byte hash; the three newer app shells share another. Shared shell bytes do not establish shared events/results. Each acquisition preserves its full destination and discovery context. These HTML shells are not result-bearing documents.

AIDA direct scripted access was unavailable (HTTP 403 in the discovery check); the internal browser returned HTTP 200 without a login or challenge. Authentication requirements were not established. No cookies or credentials were exported. Captures retain the decoded HTTP response body re-encoded as UTF-8, not compressed wire bytes; separate serialized DOM evidence records the selected `li.active a.days`. No discipline/gender selector was exposed in these attempt views, so filters are `{}`. Wakayama date clicks reload the same URL; Limassol's official schedule also supplies `?day_index=3`, which selects September 23. Both selection mechanisms require retained date evidence.

Full source hashes, acquisition IDs, retrieval timestamps, MIME types and redirect chains remain in private manifests. Registration and extraction replay results are recorded beside the archive. Synthetic tests establish changed-byte retention and tamper rejection; no genuine publisher revision relationship is claimed.

## Batch 1 replay result

Fifteen acquisitions share twelve source-byte objects. Every unchanged registration replay skipped duplicate creation. Nine result sources were passed through the existing extractors; all nine repeated extraction calls reused their jobs. The five AIDA responses produced 749 candidates, with selected dates and row counts matching the captured browser views and deterministic source replay passing. The CMAS CWT PDFs produced 44 men and 29 women candidates. These are parser counts, not owner accuracy attestations or database imports.

The existing Novi Sad parser supports only junior DNF in this source: 11 candidates from the first two senior/junior PDF pages, with pages 3-32 explicitly unsupported. All 34 masters pages remain unsupported, so its zero parsed candidates must not be described as zero sporting attempts. All extraction artifacts remain private and publication-blocked. No PostgreSQL corpus, review decision or public projection was changed in this acquisition slice.


## Batch 2 reconciliation and current limits

The private root is `data/championship-restart-20260925-b02/`, with separate `aida/` and `cmas/` archives. The CMAS corpus was copied from its isolated worker checkout with all 790 file hashes verified; the original remains intact. Batch 1's 117-file tree digest is unchanged. No raw source bytes or names were committed to Git.

AIDA now covers 16 unique response views and 1,942 source rows: 713 Wakayama, 401 Limassol and 828 Budapest. All are parsed; none is owner-reviewed. Independent response-to-extraction checks passed 118,462 comparisons, including names, exact cells, coordinates, zeros, cards, remarks and unknown states. Eight fresh pool captures add separate extraction versions, not additional attempts. Full evidence and per-session counts are in [AIDA ingestion](aida-html-ingestion.md#restart-session-reconciliation-25-september-2026).

The audit found all eight original pool DOMs truncated, seven within result cells. Original response HTML and extractions were intact. Fresh pool response hashes match the originals, and complete chunked DOMs now agree with selected dates and every source cell after standard HTML newline normalization. Earlier defective evidence remains unchanged, with its limits explicitly retained. A valid hash verifies supplied bytes, not completeness of a browser capture.

Batch 2 registered 19 AIDA acquisitions across two archives, representing 15 distinct response hashes. All registrations/extractions replayed without duplication. Including five earlier artifacts, the independent audit checked 24 versions and 3,483 versioned rows with 212,463 comparisons and zero source-to-parser mismatches. This is automated reconciliation, not reviewer attestation or sporting-result revision matching.

CMAS retained 194 HTTP response bodies: 161 successful acquisitions sharing 150 objects, plus 33 failed responses kept separately. The 27 valid PDFs contain 38 pages. Three senior women's PDFs produced 77 candidates; 24 PDFs covering 35 pages remain unsupported. Forty-two indoor result JSON views and 29 depth unit result views remain unparsed supporting evidence. One indoor response has invalid UTF-8. Thirty configured indoor PDF URLs and the linked 2026 CWT men PDF returned 404. Details, actual category/date evidence and distinct timing document hashes are in [CMAS timing acquisition](cmas-timing-acquisition-20260925.md).

No database import, real review decision, policy activation, deployment or public event replacement occurred. Missing-source coverage, unsupported PDF layouts, JSON decoding, revision/continuation matching, HTML review/public citations and reversible event replacement remain acceptance gates in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).
