# Project context

## Purpose

Efficiently collect all freediving results available online, across federations,
competition levels and historical years. Build an authoritative, auditable
database that retains original evidence and records how data was cleaned,
reconciled and related. Authority is the goal, not a property granted by download
or successful parsing.

The current evidence baseline concentrates on competitions held in 2025-2026.
That is a bounded census, not the project's permanent date limit. Every coverage
claim needs a dated cutoff, checked routes and explicit gaps. Competition dates
and publication dates are different facts.

## Agreed ingestion direction

1. Discover links using existing inventories and, where useful, parallel LLM
   agents. Overlapping discovery is acceptable. Collect event lists, results,
   athlete-name evidence, and published rankings and points.
2. Fetch documents into a verifiable archive, reusing retained evidence when
   appropriate. Keep discovery and retrieval provenance, including selected
   date/filter for interactive pages.
3. Parse supported documents in deterministic script batches. Use an LLM for
   necessary discovery or format exceptions, including developing reusable
   parsers and their recognition functions. Routine fetching and supported
   parsing should not require an LLM per document or row.

Each discovery pass declares its federation/year/source-family scope and dated
cutoff. Finish when the declared routes have been checked and every discovered
in-scope lead has a disposition. A budget limit creates a resumable checkpoint,
not completion. Inaccessible or unsupported evidence remains an explicit gap;
finishing a discovery pass does not imply complete ingestion or global coverage.

Refresh discovery indexes on later discovery runs. Recheck archived result
sources selectively: recent or provisional results more often, older results
less often. Reuse verified evidence within a run, retain changed versions, and
distinguish archive integrity from a fresh publisher check. Exact intervals are
not yet specified.

Generalize parsers incrementally: retain existing source restrictions until
another document demonstrates the same format. Broaden that parser and its
recognizer with evidence and regression checks, rather than generalizing all
format families before the next sweep.

If multiple parsers claim overlapping source rows, pause those rows and retain
the competing claims for investigation. Continue other documents. Use LLM
assistance where necessary, then encode a tested deterministic routing rule.
Process supported sections of a partial document with parsers covering separate
source positions. Retain explicit gaps for unsupported or ambiguous rows;
partial extraction does not establish complete document coverage.

For unusual scans, direct LLM transcription is an allowed exception. Retain the
transcription, exact source page/region citations and verification evidence;
replay the retained artifact without another LLM call. This does not require
a bespoke parser for every unique scan or grant review/publication approval.
Verify with an independent second transcription, deterministic comparison, and
source inspection of disagreements plus a sample of agreements. Retain both
passes and verification evidence; agreement alone can still hide shared errors.

Preserve native-script names and publisher-stated correspondence to romanized
names, including evidence from national affiliates and local organizations.
Keep event rankings, annual rankings and printed dive points with their original
scope. These are evidence for later reconciliation, not automatic identity or
attempt decisions. See [the ingestion decision](docs/adr/0001-staged-evidence-ingestion.md).

## Vocabulary and invariants

| Term | Meaning |
| --- | --- |
| Discovery lead | A cited route or link worth examining; not proof of an acquired result. |
| Acquisition | A retrieval with URL, time, representation, view context and receipt. |
| Source object | Retained bytes identified by hash; several acquisitions may share bytes. |
| Source position | A cited PDF line/region, HTML row, JSON pointer or workbook cell/row. |
| Observation version | Immutable extraction output tied to source and parser version. |
| Sporting attempt | A real dive; source positions and observation versions are not its count. |
| Relationship | An evidenced or explicitly uncertain link between sources, results or people. |

- Keep original values, spelling, units, points, rank, penalties, status and notes.
  Cleaning must not overwrite source evidence. Unknown values stay unknown.
- Retain alternate sources, mirrors, aggregates and revisions with their roles.
  Repeated evidence is useful but does not create extra sporting attempts.
- Parsing, source-row accounting, relationship review, identity approval and
  public publication are separate operations. Ingestion grants none of the latter.
- Verify byte hashes and exact citations. Replays should reuse completed work;
  changed source bytes and parser versions remain auditable.
- Original documents, private corpora, receipts and real athlete fixtures stay
  outside Git. Never include credentials or browser sessions in evidence bundles.

## Implemented system

Execution direction: discovery, fetching and parsing run locally. The owner can
use a NordVPN obfuscated connection for source access. A normal ingestion run
should produce a verified evidence snapshot and source bundle for presentation
on the existing remote owner website. Verified runs should update that private
presentation automatically, retaining the last working version if transfer or
validation fails. Fetching and remote transfer are separate phases because VPN
routing can interfere with the VPS connection. Automatic NordVPN switching for
deployment is authorized, with restoration afterward and resumable failure.
See [ADR 0002](docs/adr/0002-local-ingestion-remote-presentation.md).
The automated handoff is not yet implemented;
see [issue #64](https://github.com/jamiepratt/freediving-results/issues/64).

Python scripts handle bounded discovery, paced acquisition, browser capture,
evidence packets and a private SQLite evidence snapshot. Clojure implements the
content-addressed archive, extraction, immutable PostgreSQL observations and
separate review/publication APIs. These stores have different roles; the SQLite
snapshot is not the production observation database.

PDF dispatch in `src/freediving/extraction.clj` uses `supported?` checks,
ordered branches and source-specific hash guards. HTML, JSON, image and workbook
routes also exist, often as separate modules/scripts. `parser-routing.clj`
accounts for competing source-position claims; `parser-adapters.clj` registers
bounded retained PDF, HTML, Microplus JSON and GIA individual workbook replay.
The PDF bridge verifies archived bytes and derives local extracted pages. The
workbook bridge checks the original XLSX, acquisition receipt and cited cells;
its standings, combined scores and secondary scores remain aggregate evidence.
The retained San Mauro JPG packet has no independent blind second pass or cited
comparison, so image claims remain unsupported. `parser-batch.clj` routes
retained inputs without acquisition or imports. Broad discovery of native-script
name mappings and annual rankings is scope, not verified coverage.

## Working entry points

- Start with [CONTEXT-MAP.md](CONTEXT-MAP.md); load only the relevant runbook or
  dated evidence report. Code/tests settle claims about implemented behavior.
- Java 17+, Clojure CLI and Poppler are documented in [README.md](README.md).
  Python acquisition setup is in the [source guide](docs/source-acquisition.md).
- Read-only CLI help: `python3 scripts/acquire_source.py --help` and
  `python3 scripts/discover_results.py --help`.
- Clojure suite: `clojure -M:test`. Choose narrower tests for the changed module;
  database tests need the isolated setup documented in the README.
- Acquisition reuse checks: `python3 -m unittest discover -s test -p test_source_inventory.py`.
- Future work and unresolved choices belong in GitHub:
  [ingestion design #62](https://github.com/jamiepratt/freediving-results/issues/62),
  [2025-2026 census #55](https://github.com/jamiepratt/freediving-results/issues/55),
  [owner workspace #54](https://github.com/jamiepratt/freediving-results/issues/54),
  [attempt comparison #27](https://github.com/jamiepratt/freediving-results/issues/27).

## Accepted reconciliation direction

Reconcile automatically with minimal intervention: reuse retained work, apply
deterministic rules, then use Jev only for unresolved judgments. Ordinary code
builds versioned Jev requests from retained evidence; no LLM constructs each
query. Qualifying decisions may be approved automatically from the first run
under conservative provisional policies, with review and rollback afterward.
A new manually labelled calibration batch is not a prerequisite. Confidence and
full answer distributions remain distinct from measured accuracy.

Keep category memberships and represented country/organization on each dive,
with citations and unknowns. Preserve originals and reversible identity/attempt
links. Human corrections survive reruns and remote snapshot replacement. Pending
scored decisions sort from low to high confidence in the private owner workspace;
automatic approvals remain inspectable. Reconciliation does not grant human
review attestations or public publication authority.

See [ADR 0003](docs/adr/0003-automatic-evidence-reconciliation.md) for the accepted
policy and [issue #67](https://github.com/jamiepratt/freediving-results/issues/67)
for implementation. Compact Jev request building, score storage and automatic
spelling decisions exist; general automatic reconciliation and remote review
writes are not yet implemented.
