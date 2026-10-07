# Context map

Read [CONTEXT.md](CONTEXT.md) first. This map routes to evidence; it is not a
backlog. Dates and counts in historical reports apply only to their stated run.
Older references to an issue being open are historical, not current status.

## Core decisions and operating docs

| Read when | Location | Status |
| --- | --- | --- |
| Understanding purpose, vocabulary and boundaries | [CONTEXT.md](CONTEXT.md) | Active brief |
| Evaluating the LLM/deterministic ingestion boundary | [ADR 0001](docs/adr/0001-staged-evidence-ingestion.md) | Accepted direction; not a claim of implementation |
| Running ingestion locally and presenting it remotely | [ADR 0002](docs/adr/0002-local-ingestion-remote-presentation.md), [local evidence run](docs/local-evidence-run.md), [issue #64](https://github.com/jamiepratt/freediving-results/issues/64) | Supported private handoff implemented and validated under #73; live VPN transition/restoration certification remains open in #64 |
| Learning from prior collection passes | [2025-2026 ingestion lessons](docs/reference/ingestion-lessons-2025-2026.md) | Historical synthesis, reviewed 2026-10-02 |
| Installing or running local tools | [README](README.md) | Setup and detailed command contracts |
| Fetching or reusing sources | [Source acquisition](docs/source-acquisition.md) | Runbook plus dated batch history; read relevant sections only |
| Capturing AIDA selected-day results | [AIDA HTML ingestion](docs/aida-html-ingestion.md) | Implemented format contract |
| Building/querying private evidence | [Census contract](docs/census-evidence-contract.md), [snapshot](docs/unified-evidence-snapshot.md), [private bundle](docs/private-source-bundle.md) | Implemented contracts plus dated snapshots |
| Changing owner evidence display | [Owner workspace](docs/owner-evidence-workspace.md) | Implemented UI and access contract |
| Applying automatic reconciliation policy | [ADR 0003](docs/adr/0003-automatic-evidence-reconciliation.md), [issue #67](https://github.com/jamiepratt/freediving-results/issues/67), [completed integration #73](https://github.com/jamiepratt/freediving-results/issues/73) | Supported private deterministic/Jev path, authenticated review/reversal and incremental binding implemented; parent acceptance and #64 operational dependency remain separate |
| Checking existing review/publication contracts | [Revision relationships](docs/revision-relationships.md), [review rubric](docs/review-rubric.md), [event selections](docs/event-selections.md), [publication policy](docs/publication-policy.md) | Implemented boundaries; distinguish them from ADR 0003 direction |
| Deploying an approved change | [Deployment](docs/deployment.md) | Operational runbook |
| Reviewing bounded ingestion design and acceptance | [Issue #62](https://github.com/jamiepratt/freediving-results/issues/62) | Historical decisions and validated implementation evidence |
| Opening a bounded ingestion task | [Issue template](.github/ISSUE_TEMPLATE/ingestion-evidence.yml) | Intake for discovery, acquisition and parser gaps |

## Implementation and tests

| Area | Entry points | Read when |
| --- | --- | --- |
| Discovery | [Bounded discovery pass](docs/discovery-pass.md), `scripts/discovery_pass.py`, `scripts/discovery_frontier.py`, `scripts/discovery_refresh.py`, `scripts/discover_results.py`, `scripts/issue55_route_roster.py`, `scripts/fipsas_calendar.py`, `config/source-discovery.example.json` | Checking date limits, route states, refresh and coverage |
| Acquisition/reuse | `scripts/acquire_source.py`, `scripts/source_acquisition.py`, `scripts/source_inventory.py`; `test/test_source_inventory.py`, `test/test_source_acquisition.py` | Avoiding repeat fetches, checking pacing or receipts |
| Local evidence staging | [Local evidence run](docs/local-evidence-run.md), `scripts/local_evidence_run.py`; `tests/test_local_evidence_run.py` | Resuming local commands and binding a verified snapshot to its private bundle |
| Browser evidence | `scripts/browser_acquisition.py`, `scripts/capture_browser.py`, `scripts/browser-selection.example.json` | Capturing response and selected view context |
| Archive/PDF extraction | `src/freediving/archive.clj`, `src/freediving/extraction.clj`, adjacent parser modules; `test/freediving/` | Inspecting supported formats, hash guards, citations and replay |
| HTML/JSON extraction | `src/freediving/aida_html.clj`, `src/freediving/cmas_2025_indoor_json.clj`, `src/freediving/cmas_2026_roatan_json.clj` | Comparing format-specific paths |
| Recent timing evidence | `scripts/cmas_microplus_ingest.py`, `tests/test_cmas_microplus_ingest.py` | API repeats, rankings and Nordic PDF correspondences |
| Import orchestration | `src/freediving/pipeline.clj`, `src/freediving/observations.clj`, `deps.edn` | Checking stage receipts, immutable imports and test aliases |
| Private query projection | `scripts/census_contract.py`, `scripts/unified_evidence_snapshot.py`, `scripts/unified_evidence_query.py` | Distinguishing source evidence from observation imports |
| Private reconciliation and review delivery | `src/freediving/reconciliation_flow.clj`, `src/freediving/reconciliation_jev.clj`, `src/freediving/reconciliation_application.clj`, `scripts/owner_evidence_origin.py`; [local evidence run](docs/local-evidence-run.md), [owner workspace](docs/owner-evidence-workspace.md) | Checking versioned decisions, deterministic/cache reuse, reversible application, authenticated review and current versus historical authority |
| Product copy | `resources/evidence_workspace.html`, `resources/evidence_workspace.js`, `resources/owner.html`, `resources/owner.js`, `resources/public.html`, `resources/public.js` | Reviewing copy when visible behavior changes |

## Cold context: dated evidence

| Document | Status and read trigger |
| --- | --- |
| [7 October six August event views](docs/historical-six-august-events-20261007.md) | Six observed 2024 dates, 172 private versions and bounded ranking grids; literal NR/CR suffix omissions, independent raw audit and closed ordinary 2023 gate. |
| [7 October six September and August event views](docs/historical-six-september-august-events-20261007.md) | Six observed 2024 dates, 57 private versions and bounded ranking grids; Warsaw duplicate source-row ambiguity, independent raw audit and closed ordinary 2023 gate. |
| [7 October six September event views](docs/historical-six-september-events-20261007.md) | Six observed 2024 dates, 169 private versions and bounded ranking grids; raw evidence audit and closed ordinary 2023 gate. |
| [7 October Summer Static, Official Friday and OT Challenge views](docs/historical-summerstatic-friday-otchallenge-20261007.md) | Three observed 2024 dates, 46 private versions and bounded ranking grids; independent raw audit and closed ordinary 2023 gate. |
| [7 October Almere, Manta and Taipei views](docs/historical-almere-manta-taipei-20261007.md) | Three observed 2024 dates, 43 private versions and bounded ranking grids; Almere title/type difference, independent raw audit and closed ordinary 2023 gate. |
| [7 October Hong Kong, FreedivingFriends and Cyprus views](docs/historical-hongkong-friends-cyprus-20261007.md) | Three observed 2024 dates, 92 private versions and bounded ranking grids; literal Cyprus geography disagreement, independent raw audit and closed ordinary 2023 gate. |
| [7 October Warsaw, Taichung and October Monthly views](docs/historical-warsaw-taichung-octmonthly-20261007.md) | Three observed 2024 dates, 52 private versions and bounded ranking grids; independent raw audit, immutable prior references and closed ordinary 2023 gate. |
| [2025 onward inventory](docs/2025-onward-source-inventory-20260926.md) | September batch history, with later additions. Consult exact pass/date before claiming coverage. |
| [7 October bounded 2024 discovery](docs/historical-2024-inventory-20261007.md) | Dated route/lead manifest, explicit access and unchecked gaps, existing 2020 format replay, and closed ordinary 2023 staging gate. Consult before claiming historical coverage. |
| [7 October TrueNorth, Winter Cup and Taipei views](docs/historical-truenorth-wintercup-taipei-20261007.md) | Three observed 2024 dates, 81 private versions and complete bounded ranking filter grids; raw evidence, immutable prior references and closed ordinary 2023 gate. |
| [7 October Diving Republic, Monthly Freediving and ANT views](docs/historical-divingrepublic-monthly-ant-20261007.md) | Three observed 2024 dates, 46 private versions and complete bounded ranking filter grids; raw audit, preserved prior evidence and closed ordinary 2023 gate. |
| [7 October ANT #2, The Return and Synergic views](docs/historical-ant2-return-synergic-20261007.md) | Three observed 2024 dates, 19 private versions and bounded ranking grids; literal NR differences, independent raw audit and closed ordinary 2023 gate. |
| [7 October Malaysia, Brisbane and Apneacity views](docs/historical-malaysia-brisbane-apneacity-20261007.md) | Three observed 2024 dates, 47 private versions and complete bounded ranking grids; independent literal representation comparisons and closed ordinary 2023 gate. |
| [7 October Brisbane, Cetus and Raum views](docs/historical-brisbane-cetus-raum-20261007.md) | Three observed 2024 dates, 36 private versions, bounded ranking grids and separate Raum supporting Results; exact source accounting and closed ordinary 2023 gate. |
| [7 October Linkoping, Painushima and Koi views](docs/historical-linkoping-painushima-koi-20261007.md) | Three observed 2024 dates, private staging and bounded ranking filter accounting; exact raw evidence, prior corpus bindings and closed ordinary 2023 gate. |
| [7 October White Balance, Friends Cup and Memorial views](docs/historical-whitebalance-friends-memorial-20261007.md) | Three observed 2024 dates, 141 private versions and all bounded ranking filters; independent raw audit, preserved prior bindings and closed ordinary 2023 gate. |
| [7 October Official Friday, DFS and Siheung views](docs/historical-officialfriday-dfs-siheung-20261007.md) | Three observed 2024 dates including two calendar depth events, 124 private versions and all bounded ranking filters; exact classifications, independent raw audit and closed ordinary 2023 gate. |
| [7 October Free-diving.LV, Official Friday and Lundby views](docs/historical-freedivinglv-friday-lundby-20261007.md) | Three observed 2024 dates, 32 private versions and all bounded ranking filters; literal NR difference, Lundby title/date discrepancy, independent raw audit and closed ordinary 2023 gate. |
| [7 October Corsica selected views](docs/historical-corsica-2024-views-20261007.md) | Seven retained dates, private staging and independent raw-cell audit; preserves prior Kaunas evidence and unresolved category/finality scope. |
| [7 October Leipzig selected views](docs/historical-leipzig-2024-views-20261007.md) | Both observed dates, 82 privately staged positions and independent raw-cell audit; preserves earlier sources and explicit unchecked StartList/ranking scope. |
| [7 October Leipzig supporting representations](docs/historical-leipzig-supporting-20261007.md) | Three StartList URLs tested in both selected sessions, separate supporting row accounting and default ranking classification; preserves category/finality and source-equivalence uncertainty. |
| [7 October Swedish selected views](docs/historical-sweden-2024-views-20261007.md) | Three observed single-day EventResults views, 22 private versions and independent raw-cell audit; preserves prior evidence through immutable references and explicit ranking/category/finality gaps. |
| [7 October Swedish ranking filters](docs/historical-sweden-rankings-20261007.md) | Three observed ranking routes and their bounded discipline/gender filters; aggregate accounting, raw comparisons and session-dependent views remain separate from sporting attempts and review. |
| [7 October Warsaw, Ukmerge and Suzuka views](docs/historical-warsaw-ukmerge-suzuka-20261007.md) | Three single-day Results views and observed ranking filters; private staging, independent raw accounting and unknown category/finality remain separate from publication. |
| [5 October source refresh](docs/issue55-refresh-20261005.md) | Two bounded passes across 15 retained route records; private receipts and explicit child, calendar and access gaps. |
| [6 October retained reconciliation audit](docs/retained-corpus-reconciliation-audit-20261006.md) | Frozen source/year/family denominators, stratified structural audit, isolated reversal/cache controls and bounded genuine private review. Read before claiming reconciliation accuracy or #73 acceptance. |
| [October Microplus census](docs/cmas-microplus-census-20261001.md) | Later timing acquisition and Nordic PDF accounting; supersedes specific September access gaps. |
| [Championship inventory](docs/championship-inventory-20260925.md), [source gaps](docs/championship-source-gaps-20260925.md), [corpus](docs/championship-corpus-20260925.md) | Historical #8 baseline. Read before reusing its sources or counts. |
| [AIDA scope audit](docs/aida-scope-audit-20260925.md) | Historical selected-date coverage and missing scope. |
| [Athens mirror audit](docs/athens-mirror-audit-20260925.md) | PDF/JSON disagreements and mirror limitations. |
| [Indoor semantics](docs/indoor-semantics-audit-20260925.md) | Printed time/status ambiguity. |
| [Corpus relationship/status audit](docs/corpus-relationship-status-audit-20260927.md) | Bounded replay and counting distinctions. |
| [CMAS timing acquisition](docs/cmas-timing-acquisition-20260925.md), [2025 JSON ingestion](docs/cmas-2025-json-ingestion.md) | Historical timing routes, source bindings and parser evidence. |

## Documentation placement

`docs/` holds durable explanations and runbooks; `docs/adr/` records real
decisions; `docs/reference/` holds useful low-frequency synthesis. Existing
historical files keep their paths to preserve links. Add `docs/archive/` only
when retiring material, `docs/external/` for imported untrusted references, and
`docs/generated/` for safe generated reports. No empty directories are required.

Private originals, database snapshots and generated evidence packets remain
outside Git even when indexed by a versioned report. Issue intake lives in
`.github/ISSUE_TEMPLATE/`. Do not store future plans, TODO lists or unresolved
decision logs in local docs.
