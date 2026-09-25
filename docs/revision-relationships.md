# Private result revision relationships

`freediving.revisions` records possible same-result revisions and explicit missing history separately from athlete identity, field corrections and publication validation. Both observation versions remain immutable. The API adds no automatic public suppression, policy activation or real review decisions. Championship acceptance remains in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Evidence and matching

Each observation reference binds extraction job, ordinal, candidate ID, source hash and artifact hash. Scope descriptors bind claimed values to retained source evidence. A reviewer must assess the meaning of those bindings; a matching value cannot establish that a rank is a bib, a report date is an event date, or a source name is an athlete identifier.

A binding is `{:reference exact-reference :path artifact-path :value exact-value}`. Allowed paths address the referenced candidate's `:raw` subtree or retained page-line text. Participant IDs, bib, name and attempt must use that candidate's own raw evidence. Configuration, actor/parser metadata and another candidate's raw fields are not source bindings. Scope and revision-evidence values are nonblank strings or numbers. Typed revision evidence pairs a binding with `:kind` equal to `:report-id`, `:version`, `:revision-timestamp`, `:correction-note` or `:missing-source-notice`. These labels identify the proposed interpretation; they do not certify that interpretation.

Matching requires federation/event ID, date, venue, discipline, category, round and session. A supplied attempt discriminator also participates. A scoped athlete ID or bib can yield a possible revision; a matching name alone cannot establish a replacement or an identity. Repeated scoped matches remain ambiguous. Candidate search is limited to the supplied comparison set, not a completeness assertion about an entire championship.

Report IDs, version labels, correction notes and timestamps support review. Different source hashes prove different bytes. Retrieval order does not establish revision order. A document revision does not establish that each row changed. The API does not manufacture a before/after delta or infer replacement direction from either signal.

Typed revision evidence can also bind `[:acquisitions n :manifest :final-url]` to retain a filename such as `-v2.pdf` from its exact registered acquisition. This exception is unavailable to scope matching. A URL version label remains evidence for explicit review, not automatic supersession or a claimed correction timestamp.

An unavailable predecessor is represented by a missing reference and unknown previous values. The revised observation can be retained and its missing-history evidence reviewed without creating a fake observation. Acknowledging missing history does not confirm a replacement of an identified earlier row.

## Authority and persistence

Migration 8 adds checksummed, append-only proposal and decision tables. Restricted ingestion credentials may propose; explicit decisions require the reviewer database capability. Actor names and reasons are audit text, not authorization. Exact retries are idempotent; changed requests cannot reuse an ID. Optimistic revisions and transactional locking guard competing decisions. Reversal appends history without deleting either source observation or the earlier decision.

The revision number is global to relationship decisions. Read the current diagnostic before submitting a new proposal or decision. Active confirmed pairs cannot share endpoints: branching, cycles and multi-edge revision chains are deliberately refused. Reversing an existing decision permits a separately proposed, explicitly reviewed alternative; it does not erase the earlier rationale. Chain composition is not part of this contract.

| API | Arguments after JDBC URL | Result or action |
| --- | --- | --- |
| `reference` | `{:job-id job :ordinal n}` | Verified exact observation envelope |
| `candidates` | Successor descriptor, vector of predecessor descriptors | Read-only scoped match hints |
| `propose!` | Proposal map | Append possible revision or revised-only history |
| `decide!` | Decision map | Reviewer-only confirm, reject, reverse or acknowledge missing history |
| `diagnostics` | None | Global revision and current relationship states |
| `history` | Proposal ID | Proposal and retained decision audit |
| `migrate!` | Ingestion role, reviewer role | Apply/check migration as database owner |

Proposals contain `:id`, `:predecessor` (descriptor or nil), `:successor`, `:base-revision`, `:actor`, `:reason`, `:mapping-rationale` and nonempty `:revision-evidence`. A descriptor contains `:reference` and `:scope`, a map from scope field to binding. Decisions contain `:id`, `:proposal-id`, `:base-revision`, `:actor`, `:reason` and `:action`. Actions are `:confirm`, `:reject`, `:acknowledge-missing` or `:reverse`; reversal additionally names `:event-id`. Confirmation requires a scoped predecessor. Missing-history acknowledgement uses its own status and cannot stand in for confirmation.

The normal deployment migration runner includes migration 8. Local verification uses `scripts/test-postgres.sh test-revisions`, which creates and removes its own synthetic PostgreSQL cluster. No database environment means the tests fail, not skip.

As with the existing review API, reviewer SQL is a trusted administrative capability and can bypass application-level semantics. Database owners can bypass database protections. Synthetic loopback tests verify session privileges, not production authentication. Keep these APIs and evidence private.

## Public integration boundary

Read-only diagnostics distinguish possible, rejected, confirmed, reversed and missing-history relationships. None is an extraction validation or an athlete-identity approval. Existing public projections and eligibility remain unchanged, including the substantive blockers on ambiguous indoor timing fields.

A consumer must read the current relationship state and exact source references, check publication eligibility separately, and account for pending or conflicting evidence. A cached confirmed relationship cannot be treated as permanently current after a reversal. The separate [publication path](publication-policy.md) supports HTML citations under explicitly activated policy 2. This revision backend still supplies no public deduplication, event replacement, rollback of published selection or revision wording. Those acceptance gates remain in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Genuine evidence boundary

The [retained CMAS inspection](cmas-timing-acquisition-20260925.md#batch-8-revision-evidence-inspection) supports filename/version and report-header evidence, but supplies no reviewed predecessor relationship. Inspected CWT PDFs lack stable athlete-ID/bib columns. Separate 2026 initial and continuation units have different scoped identifiers and dates. No genuine record was confirmed, rejected or reversed during this batch. Backend fixtures are synthetic, not reconstructed publisher history.
