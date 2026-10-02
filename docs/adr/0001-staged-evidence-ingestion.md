# ADR 0001: Separate discovery, acquisition and deterministic extraction

Date: 2026-10-02. Status: accepted project direction from the owner request.
Implementation status: partial. Detailed contracts remain in
[issue #62](https://github.com/jamiepratt/freediving-results/issues/62).

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
  ambiguous/partial matches, scan exceptions, refresh policy and stopping rules
  remain unresolved in #62; this decision does not select their design.

## Evidence

See [the retrospective](../reference/ingestion-lessons-2025-2026.md),
[source acquisition](../source-acquisition.md),
[census evidence contract](../census-evidence-contract.md), and
[`extraction.clj`](../../src/freediving/extraction.clj).

User-facing copy impact: None. This records ingestion direction without changing
the product. Future UI behavior requires its own copy review.
