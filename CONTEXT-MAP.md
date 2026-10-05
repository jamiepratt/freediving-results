# Context map

Read [CONTEXT.md](CONTEXT.md) first. This map routes to evidence; it is not a
backlog. Dates and counts in historical reports apply only to their stated run.
Older references to an issue being open are historical, not current status.

## Core decisions and operating docs

| Read when | Location | Status |
| --- | --- | --- |
| Understanding purpose, vocabulary and boundaries | [CONTEXT.md](CONTEXT.md) | Active brief |
| Evaluating the LLM/deterministic ingestion boundary | [ADR 0001](docs/adr/0001-staged-evidence-ingestion.md) | Accepted direction; not a claim of implementation |
| Running ingestion locally and presenting it remotely | [ADR 0002](docs/adr/0002-local-ingestion-remote-presentation.md) | Accepted policy; opt-in command wiring implemented, live handoff unverified |
| Learning from prior collection passes | [2025-2026 ingestion lessons](docs/reference/ingestion-lessons-2025-2026.md) | Historical synthesis, reviewed 2026-10-02 |
| Installing or running local tools | [README](README.md) | Setup and detailed command contracts |
| Fetching or reusing sources | [Source acquisition](docs/source-acquisition.md) | Runbook plus dated batch history; read relevant sections only |
| Capturing AIDA selected-day results | [AIDA HTML ingestion](docs/aida-html-ingestion.md) | Implemented format contract |
| Building/querying private evidence | [Census contract](docs/census-evidence-contract.md), [snapshot](docs/unified-evidence-snapshot.md), [private bundle](docs/private-source-bundle.md) | Implemented contracts plus dated snapshots |
| Changing owner evidence display | [Owner workspace](docs/owner-evidence-workspace.md) | Implemented UI and access contract |
| Applying automatic reconciliation policy | [ADR 0003](docs/adr/0003-automatic-evidence-reconciliation.md), [issue #67](https://github.com/jamiepratt/freediving-results/issues/67) | Accepted policy; general reconciliation and remote review writes remain implementation work |
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
| Product copy | `resources/evidence_workspace.html`, `resources/evidence_workspace.js`, `resources/owner.html`, `resources/owner.js`, `resources/public.html`, `resources/public.js` | Reviewing copy when visible behavior changes |

## Cold context: dated evidence

| Document | Status and read trigger |
| --- | --- |
| [2025 onward inventory](docs/2025-onward-source-inventory-20260926.md) | September batch history, with later additions. Consult exact pass/date before claiming coverage. |
| [5 October source refresh](docs/issue55-refresh-20261005.md) | Two bounded passes across 15 retained route records; private receipts and explicit child, calendar and access gaps. |
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
