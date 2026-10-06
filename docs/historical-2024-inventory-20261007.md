# Bounded 2024 discovery, 7 October 2026

This completed discovery pass supports [issue #195](https://github.com/jamiepratt/freediving-results/issues/195). Competition scope is 1 January through 31 December 2024; the inventory cutoff is **2026-10-06T22:12:51Z** (7 October in Warsaw). It records inspected listings and explicit gaps, not global coverage or a cleared historical cohort. The [historical contract](historical-cohort-contract.md) provides reusable validation, queries and a gate for ordinary 2023 private staging.

| Discovery surface | Checked evidence | Inventory outcome |
| --- | --- | --- |
| [AIDA official calendar](https://www.aidainternational.org/Events/EventCalendar) | Year 2024; empty month, country and event-type filters; all 18 observed pages | 351 unique event cards; no inference that all events produced attempts |
| [AIDA Kaunas 4008](https://www.aidainternational.org/EventPage/4008), [Corsica 4009](https://www.aidainternational.org/EventPage/4009) | Retained default selected dates, schedule and result selectors | 11 dated `Pre. Results` links and two supplemental ranking leads; nine other selected dates unchecked |
| [AIDA Deutschland archive](https://aida-deutschland.de/ergebnislisten-competitions/) | Fresh HTTP 200, one complete index page | 2024 event 4036 matches the calendar's exact event URL; podium aggregates excluded |
| [CMAS result archive](https://www.cmas.org/freediving/results.html) | Transformed cached official index, all sections with titles labelled 2024 | 17 listing records, including two annual rankings; ten document URLs and seven index/title locators |
| [CMAS event calendar](https://www.cmas.org/freediving-events.html) | Observed offsets 0 through 70; 2024 at 40/50/60, 2023 boundary at 70 | 25 dated calendar records; cached pagination drift prevents a current live enumeration claim |

AIDA calendar reproduction: select `#year=2024`, clear `#month`, `#event_type_id` and `#country_id`, click `#apply_event_calendar`, then follow `a[title="Next Page"]` through page 18. Read `.eventcalendar__events__single` cards. Eighteen HTTP 200 decoded response bodies were retained separately from their DOM captures, with URL, selected filters, page, retrieval time, byte length and SHA-256. These are CDP-decoded responses re-encoded as UTF-8, not compressed wire bytes. One event, 3503, spans 31 December 2024 through 1 January 2025; both dates remain explicit and its result sessions remain unchecked.

The two AIDA EventPage originals retain 140 cited default-view positions: 113 Kaunas DYNB positions on 24 June and 27 Corsica CNF women positions on 6 September. Existing `issue55_aida_selected_html.build` accounted for every row, preserving raw fields and exact table/tbody coordinates. These are two private source objects and two source projection packets, with **zero imported observation versions**. Their remaining dates and linked StartList representations are unchecked. Schedules label the links `Pre. Results`; no final publisher history, row review, same-attempt relationship or publication authority was inferred. Rankings add no attempt positions.

CMAS direct HTTP checks returned 403, and one internal-browser results visit also returned 403. Failed response bytes remain separate from cached web-reader observations. The query `site:cmas.org/freediving/results.html "2024"` and the observed pagination selectors are recorded privately. Cached representations are explicitly identified as transformed evidence, with unknown publisher retrieval times. No CMAS 2024 original result object was acquired. Sixteen possible result/calendar correspondences remain unverified; the 42 CMAS listing records are not 42 proven distinct events. July and November Deep Dominica listings remain separate.

The bounded national roster derives from actual listings: 48 AIDA calendar country labels and seven printed CMAS country contexts, plus 16 CMAS listings whose country was not established by the retained transformed view. AIDA Germany was checked; the other national archive routes remain unchecked. The observed AIDA member directory, CMAS national directory and linked Barborka organizer route retain their unchecked result-archive dispositions. This does not claim every affiliate was searched.

## Queryable private checkpoint

The private directory is `/Users/jamiep/.codex/private-corpora/issue195-b38-2024-cohort-manifest/`. Its `manifest.json` has SHA-256 `9c2f041b9880321dafc2d00ca8e8145c50c6be8f3401d1a70d6066dc2a08d2c6`. Raw sources, receipts, row fields and executable evidence recipes remain outside Git, with directories 0700 and files 0600.

The manifest records **11 routes**: six checked, two unavailable and three unchecked. Only the bounded AIDA calendar enumeration and German index cross-check have resolved route dispositions. All **406 lead records** retain explicit dispositions: 364 AIDA event/result/ranking leads and 42 CMAS listing records. There are 404 unchecked source states and two checked partial AIDA event views; zero leads are resolved. The nested census contains two events, two source objects, 140 parsed source positions, zero imported observation versions and two event-coverage gaps. Confirmed distinct attempts remain unknown.

Validation and a Kaunas query succeeded. The ordinary 2023 gate returned **CLOSED**, with 417 route/lead/census blockers. An ordinary staging call rejected the cohort before opening its candidate file or writing output. Existing legacy importers are outside this new staging entrypoint; no ordinary 2023 cohort was run. No owner exception was supplied.

```sh
python3 scripts/historical_cohort.py validate /Users/jamiep/.codex/private-corpora/issue195-b38-2024-cohort-manifest/manifest.json
python3 scripts/historical_cohort.py query /Users/jamiep/.codex/private-corpora/issue195-b38-2024-cohort-manifest/manifest.json --lead aida-event-4008
python3 scripts/historical_cohort.py gate /Users/jamiep/.codex/private-corpora/issue195-b38-2024-cohort-manifest/manifest.json --year 2023
```

## Older format prototype

The September report's retained EventResults-2789 original was absent from its documented worktree and the checked local acquisition/portable inventories. A separate recovery acquisition of the exact [official source](https://www.aidainternational.org/Events/EventResults-2789) returned HTTP 200. Its selected date is **26 September 2020**, outside the 2024 census. Recovery does not establish equality with the missing September bytes or a publisher revision.

The recovered 104,709-byte source has SHA-256 `a45c17622bcdb0e5642e1bd5b8c27827be511b27f1b3747c7d1f596f77061f6d`. Existing `freediving.aida-html/extract!` and `validate-artifact!` replayed it using `aida-html/1`, schema 4: one source object, 42 parsed positions, one extraction version, zero database imports. The source's legacy `Line`/`Official Top` layout and absent line cells were already supported. Start 32, table 1 row 33, preserves simultaneous WHITE and Dqsp tokens with an explicit contradiction flag. No parser implementation change was needed.

Independent HTMLParser reconciliation checked all 42 unique citations and 714 field comparisons. Identical extraction reused the same job; tampered artifact content was rejected. Prototype artifact SHA-256 is `17ed2ef7dd27e205d183ff5174610ed83f258f266b20343cdbb9f1c403b2c583`; its separate checkpoint SHA-256 is `bba1f9985af9024dbcb4cc7c59212bd18dd0cc6a215291acb1a54751528d391d`. Existing synthetic parser tests passed 8 tests/57 assertions; they remain distinct from real-source verification.

## Integration boundary

New contract tests exercised RED/GREEN for closed-gate rejection, query accounting and typed resolutions. Eleven historical contract tests and six existing census tests passed. The Clojure worker reported 376 tests/2,123 assertions passing, plus the narrow parser checks. No production database, private owner runtime, public response, policy, signer, grant or deployment changed. No VPN change or paid model call occurred.

[Issue #195](https://github.com/jamiepratt/freediving-results/issues/195) retains the unresolved historical scope. The first reviewed public DNF slice, genuine historical batch-policy acceptance and public automatic-link calibration remain deferred prerequisites; this discovery pass supplies no approvals or numerical thresholds.
