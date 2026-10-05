# Championship source search, 5 October 2026

Cutoff: 2026-10-05 20:55 UTC. Scope: the missing official 2025 Athens indoor championship attempt PDF and 2026 Roatan CWT-men championship attempt PDF in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8). This was a finite route search, not a new corpus or a claim of complete source absence. Six explicit web queries and seven primary publisher discovery/result requests were used; no result original was acquired. Search snippets were leads only. Previously checked CMAS login/403 downloads, Athens configured PDF 404s, and Roatan document 404 were not requested again.

## Queries and dispositions

Searches were run on 5 October 2026, approximately 20:53-20:54 UTC. The wording below is exact.

| Query | Disposition |
| --- | --- |
| `site:cmas.org 2025 Athens freediving indoor world championship official results pdf DNF DYN STA May 2025` | CMAS event, results index and Day 1 report surfaced; all are previously known routes, with no new exact PDF URL. |
| `site:microplustimingservices.com 2025 CMAS World Championship Freediving Indoor Athens pdf results` | Legacy Athens schedule surfaced, already represented in the retained official JSON corpus. Other Book.pdf hits were finswimming championships, not this event. |
| `site:cmas.org 2026 Roatan freediving CWT men "results" pdf August 21` | CMAS Roatan event and results index surfaced; neither exposed a new CWT-men PDF route. A `Results DAY 1 official.pdf` hit was a different competition, not Roatan. |
| `site:cmas-eventsystem.microplustimingservices.com/pdf/ Roatan CWT MEN 2026 results` | No new exact publisher document link surfaced. |
| `site:microplustimingservices.com 2026 CMAS depth Roatan CWT men 3559 PDF result` | No new exact publisher document link surfaced. |
| `2025 Athens CMAS freediving indoor championship results PDF Greek underwater federation Hellenic official` | Found [Diving Greece event home](https://diving-greece.gr/cmas-2025/index.php) and [Greek federation home](https://www.eoyda.gr/) as publisher/organizer leads. Neither yielded a verified result document in this pass. The Venezuelan federation PDF is the known mirror, not an official original. |

## Primary route checks

Seven web open/click requests, 5 October 2026, approximately 20:54-20:55 UTC:

| Route | Observed result and role |
| --- | --- |
| [Diving Greece Athens home](https://diving-greece.gr/cmas-2025/index.php) | Web open returned 403. CMAS's [Day 2 report](https://www.cmas.org/news/cmas-2025-indoor-freediving-world-championship-day-2-report.html?format=print&print=1&tmpl=component) names Diving Greece as event collaborator, but no document or publisher redirect was verified. No identical retry. |
| [Greek federation home](https://www.eoyda.gr/) | Web open timed out. Search indexing mentions the 2025 Athens national team; no event result route was verified. No identical retry. |
| [CMAS Athens Day 2 report](https://www.cmas.org/news/cmas-2025-indoor-freediving-world-championship-day-2-report.html?format=print&print=1&tmpl=component) | Opened. Names 21 May 2025 DYN-BF at Athens and asserts a CMAS competition results PDF. Its `Full results on cmas.org` link goes to the same [CMAS results index](https://www.cmas.org/freediving/results.html), which was opened via that link; the Athens entry still displays 4 KB. This gives no fresh PDF path or publisher finality/revision evidence. |
| [CMAS Roatan event](https://www.cmas.org/freediving-events/2026-cmas-world-championship-freediving-depth.html) | Opened. It still labels Day 1 CWT men and Day 5 CWT men as continuation, within 14-27 August 2026. Attempts to open its two linked result views through the search browser returned internal errors; no result document, source-heading check or changed publisher API response was obtained. |

The four rows account for seven requests: two organizer opens, two CMAS page opens, one CMAS results-index click, and two Roatan result-view clicks. Web-rendered pages/snippets were inspected; no bytes were archived. The organizer pages' 403/timeout and result-view internal errors are access outcomes of this pass, not claims about all access methods. No download was inferred from a `4 KB` archive label or a report's statement that a PDF exists.

## Retained evidence and open gates

No new, changed or corrupt source original was evidenced, so no acquisition, parser replay or RED/GREEN code cycle occurred. The [retained championship inventory](championship-inventory-20260925.md) still accounts for 42 official Athens JSON views and 792 source positions across seven disciplines; its missing official PDF and coverage caveats remain. The [Roatan source gap audit](championship-source-gaps-20260925.md) retains two exact official CWT-men result API objects: unit 3551 (17 August, 7 positions, SHA-256 `146c9c90514fd7d6ef5bf8fdfe832eebdb94c88f5d2c15480569665c0aa02e45`) and unit 3559 (21 August, 24 positions, SHA-256 `bfc73892f1e9fcd01daa86e64b8bdc2f8f0ceaf36ab4a78373ec87651d316f45`). Their 31 source positions do not prove 31 distinct attempts. The known medalists PDF is not a complete attempt source; the known Athens Venezuelan PDF remains a mirror. This pass did not rehash private originals, alter observations or make an owner decision.

The precise future source trigger is a newly evidenced official Athens or Roatan document link, a changed publisher document listing, or accessible organizer result route. Confirm publisher discovery/redirect and exact championship, session and category headings before acquiring new bytes; then preserve the original/hash/receipt and reconcile its rows to retained views. Otherwise the current source gaps remain open. Source finality, session overlap, extraction/identity review and publication require separate evidence and authenticated owner-workspace decisions under issue #8.
