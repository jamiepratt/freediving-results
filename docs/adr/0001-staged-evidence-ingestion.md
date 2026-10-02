# ADR 0001: Separate discovery, acquisition and deterministic extraction

Date: 2026-10-02. Status: accepted project direction from the owner request.
Implementation status: partial. Detailed contracts remain in
[issue #62](https://github.com/jamiepratt/freediving-results/issues/62).

Reconciliation follow-up: [ADR 0003](0003-automatic-evidence-reconciliation.md)
settles the later automatic reconciliation policy. Deferral language below records
the original ingestion boundary; ingestion alone still makes no reconciliation
or publication decision. Cross-federation scoring remains outside that follow-up.

## Context

The project seeks an authoritative database of online freediving results across
federations and historical years. The 2025-2026 work already has deterministic
parsers, private archives and exact citations, but many routes and formats need
separate discovery or source-specific handling. Running an LLM over every fetch
and result row would repeat work already handled by code.

## Decision

Separate ingestion into three stages with retained outputs:

1. **Discovery:** use existing explored-link inventories and parallel LLM
   investigation where useful to find uncollected evidence. Overlapping searches
   are permitted. Preserve where each lead was found and what scope was checked.
2. **Acquisition:** fetch documents to the archive with original evidence and
   retrieval provenance. Reuse verified acquisitions rather than repeatedly
   downloading the same selected view within a run.
3. **Extraction:** run supported parsers in deterministic batches. Where a
   format is unsupported, use an LLM when necessary to extend a parser and its
   recognition function, or create both. Retain source and parser versions so
   repaired extraction can replay without rediscovering or refetching evidence.

Declare the federation, year and source-family scope of each discovery pass,
including its routes and dated cutoff. Complete the pass when those routes have
been checked and every discovered in-scope lead has an evidenced disposition.
Inaccessible, missing or unsupported sources remain explicit gaps; unchecked
in-scope leads prevent completion. Leads outside the declared scope remain
available for another pass. Reaching a time, request or LLM budget creates a
resumable checkpoint, not a completed pass. Fixed effort alone is rejected as
the completion criterion. This defines completion of bounded discovery, not
successful ingestion of every source or complete worldwide coverage.

Use selective refresh across runs. Refresh discovery indexes to find new links;
recheck recent or provisional result sources more often and older results less
often. Reuse verified acquisitions within a run and retain changed source
versions with their retrieval provenance. Archive reuse establishes local byte
integrity, not publisher freshness. Checking every result on every run is rejected
for unnecessary requests; checking only on explicit request is rejected because
publisher corrections can otherwise remain undiscovered. Exact intervals and
freshness-state rules remain implementation details in #62. This is a policy
decision, not creation of a scheduled automation.

Generalize parsers incrementally. Keep existing source restrictions until another
document demonstrates the same format, then broaden the parser and recognizer
with source-backed tests. Upfront generalization of all retained format families
is not a prerequisite for further collection. This trades gradual coverage for
lower risk of applying a source-specific parser to an incompatible document.

When multiple parsers claim overlapping source rows, pause the affected rows,
retain the competing claims and investigate, using LLM assistance where needed.
Continue processing other documents. Resolve the ambiguity with a tested
deterministic routing rule before retrying those rows. Automatically choosing
by parser priority is rejected as the default: recognition mistakes could
otherwise go unnoticed.

Process supported sections without waiting for complete document support.
Multiple parsers may cover disjoint source positions, retaining each parser's
scope and citations. Unsupported or ambiguous rows remain explicit gaps; an
unexamined section remains a coverage gap even when its row count is unknown.
Overlapping claims still follow the ambiguity rule above. Holding the entire
document until every section is supported is rejected as the default because it
delays usable evidence. Partial extraction must not be reported as complete
document coverage.

For unusual scanned documents, permit direct LLM transcription as an exception
to reusable parser development. Retain the transcription, exact source
page/region citations and verification evidence as a replayable artifact; replay
must not require another model call. A unique scan need not receive a bespoke
parser. This exception does not make the transcription authoritative or approve
identities or publication. Requiring a reusable parser for every scan is rejected
because it can add work without producing a reusable extraction method.

Verify exceptional scan transcriptions with an independent second pass that
does not see the first transcription. Compare the retained outputs
deterministically, then inspect disagreements and a sample of agreements against
the source. Preserve both outputs and verification evidence; unresolved readings
remain explicit. Agreement between passes is not proof of correctness because
both can make the same mistake. Mandatory human verification of every row is
not the default. Sampling details and escalation criteria belong to the
implementation contract in #62.

Discovery includes event calendars and lists, individual results, local and
international federation/organizer evidence, native-script athlete names and
explicit romanized-name correspondences, event and annual rankings, and points
assigned to dives. Preserve the publisher's scope and original fields. An
annual ranking, an event ranking and a daily result are different evidence views.

Source accounting belongs to ingestion: explain which source positions parsed
and which remain unresolved. Choosing canonical identities, cleaning rules,
same-attempt relationships, source precedence or cross-federation scoring is
deferred. Record evidence needed for those decisions without making them.

## Consequences and alternatives

- Routine supported extraction can replay without an LLM. Exceptions must remain
  visible; a successful format check does not prove a complete or correct parse.
- Redundant discovery provides additional routes and corroboration. Its value
  does not justify duplicate downloads, imports or extra attempt counts.
- The project retains rankings and multilingual name evidence instead of
  restricting collection to attempt tables. A name correspondence is a cited
  publisher assertion, not an automatic person merge.
- Always using an LLM for every row is rejected for routine supported formats.
  Requiring deterministic code to discover every unfamiliar source is also too
  restrictive; LLM assistance remains available where it adds value.
- The existing `supported?` functions and source-hash guards are implementation
  evidence, not yet a uniform cross-format registry. The recognizer API,
  verification sampling, refresh intervals and checkpoint representation remain
  implementation details in #62; this decision does not select their design.

## Evidence

See [the retrospective](../reference/ingestion-lessons-2025-2026.md),
[source acquisition](../source-acquisition.md),
[census evidence contract](../census-evidence-contract.md), and
[`extraction.clj`](../../src/freediving/extraction.clj).

User-facing copy impact: None. This records ingestion direction without changing
the product. Future UI behavior requires its own copy review.
