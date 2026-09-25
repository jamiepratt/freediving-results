# Reviewed event selection

`freediving.event-selections` provides private reviewer operations for scoped public replacement. Migration 10 adds selection history and public snapshot checks. Installing it does not select an event, activate publication policy 2 or validate a source row. Verification uses synthetic evidence; real championship acceptance remains in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Scope and evidence

The default selection contract names a reconciled unit using federation, event ID, date, venue, discipline, category, round and session. This is narrower than an entire championship. Each inventoried observation has an exact job, ordinal, candidate, source hash and artifact hash, plus source-bound scope and an own-row participant identifier. A name or source hash alone cannot establish a sporting attempt. A rank is not a participant identifier; ranking exports remain supplemental.

The reviewer inventories selected and excluded versions explicitly. Repeated captures or parser jobs remain immutable observations. Only one version of a scoped sporting attempt can be selected. Exact equivalent source rows can be represented once; different source claims require the separately reviewed relationship evidence described in [revision relationships](revision-relationships.md). A possible, rejected or reversed successor cannot acquire replacement authority through selection. Acquisition order never establishes revision order or changed result values.

Missing required source scope is a blocker. Do not invent venue, event IDs, rounds, session identifiers, bibs or interpretation of a printed start/rank column to satisfy the API. Binding a value to source text proves its location, while the reviewer remains responsible for its meaning. This workflow does not automatically reconcile the retained real corpus.

The first cutover must account for every already eligible observation. Other event scopes are explicitly retained with their exact validations; they cannot silently disappear as a side effect of selecting this event. If the retained baseline cannot survive current relationship and projection checks, the entire cutover is rejected. Enrolling a retained event later must include its original inventory. After enrollment, newly acquired or newly validated unbound observations do not automatically join the published selection. Each subsequent event cutover requires its own reviewed inventory.

## Authority and rollback

The private Clojure API takes a JDBC URL first:

| Function | Additional argument | Purpose |
| --- | --- | --- |
| `snapshot` | None | Current selection and authority revisions for optimistic concurrency |
| `select!` | Selection request | Append a reviewed selection and refresh public projections |
| `rollback!` | Rollback request | Re-select an eligible historical selection without changing its contents |
| `history` | Exact event-scope map | Read append-only selection audit |
| `migrate!` | Restricted reviewer role | Apply checksummed migration 10 as database owner |

A selection request contains a unique `:id`, private `:actor` and `:reason`, the current `:base` snapshot, `:event-scope`, a vector of source-bound `:members`, a vector of `:selected` entries, and `:coverage`. Each selected entry names its exact `:reference` and `:validation-id`; a reviewed successor also names its `:relationship-id`. Coverage has `:completeness :partial` or `:complete` and a vector of explicit `:gaps`. The initial request can include `:retained` entries, each with another scope's `:descriptor` and exact `:validation-id`.

A rollback request contains only `:id`, `:actor`, `:reason`, current `:base` and the historical `:selection-id`. It cannot override the historical scope, selected versions or gap declaration. The known source inventory remains retained through rollback.

Selection requires the restricted reviewer database capability. Actor and reason are private audit text. Every selected observation needs its own current extraction validation under the active policy and current review revision. An identity link is neither required nor inferred: a validated source name can remain public with unresolved identity.

Selection and projection refresh commit atomically. Optimistic snapshots reject stale authority, and request IDs distinguish exact retries from conflicting reuse. The append-only audit preserves previous selections, source versions and decisions. Failed requests cannot leave a partial replacement.

Rollback appends another selection for the same scope; it does not delete history or restore old database contents. Historical validation IDs and relationship decisions must still be eligible. Revoked validations, changed reviews, reversed relationships and policy transitions can prevent rollback. Fresh validation does not silently rewrite the approval attached to an earlier selection. Policy rollback has separate requirements in [publication policy](publication-policy.md).

Multi-edge revision chains remain deliberately unsupported. Active reviewed pairs cannot share endpoints, so branching, cycles and chained replacement are refused. Do not describe pairwise synthetic tests as complete multi-generation publisher history.

Existing HTML inspection, validation and citations remain supported under policy 2. Typed AIDA bindings expose replayed event routes, visible event/date context and exact own-row fields/profile links through the revision descriptor. The opt-in dated-view contract below can describe the retained HTML without fabricated venue, round or session values. The [isolated corpus audit](championship-corpus-20260925.md) imports these observations without enrolling or approving them.

## Public display

Public responses retain PDF or HTML row citations and the existing correction submission version. Selected rows display scoped coverage and declared gaps. Overall pilot coverage remains partial, even when one reconciled scope is declared complete. Missing sessions do not become zero attempts.

A reviewed source replacement does not imply that this row's values changed. Public wording separates it from unavailable earlier history. Earlier values remain unknown in this projection; no before/after delta is manufactured. Identity histories contain only eligible selected records with separately approved identity links.

Private selection requests, reviewer labels and raw evidence are not public response fields. Public coverage exposes only the reviewed scope and gap declaration. As with other reviewer APIs, this is a trusted administrative capability, not public authentication; direct reviewer SQL can bypass application semantics and database owners can bypass database protections.

Authority changes invalidate cached public rows and coverage. On refresh, a scope is withheld if any selected row cannot produce a current eligible projection. This prevents a revoked or unsupported row from leaving a misleading complete-coverage declaration. An intentionally empty partial selection still exposes its explicit gaps. Other eligible retained scopes remain available after refresh.

## Synthetic verification

Run `scripts/test-postgres.sh test-event-selections` for the isolated selection suite and `scripts/test-postgres.sh test-selection-migration` for upgrade-order checks. The full script provisions and removes its own synthetic database. It never uses production credentials or approves real source rows.

Browser verification exercised three versioned rows becoming two public records, a reviewed replacement with unchanged values, rollback restoring the earlier exact record, revoked approval blocking rollback, an unrelated session remaining visible, revised-only missing history, and an empty selected scope retaining its gap declaration. The synthetic source values and decisions are not evidence of real publisher revisions.

## Explicit AIDA dated views

An HTML descriptor may add `:scope-contract :aida-date-view/v1` beside `:reference` and `:scope`. Its required bindings are `:federation`, `:event-id`, `:date`, `:discipline`, `:category` and `:source-athlete-id`, using the existing exact `[:html-scope field]` evidence. Optional `:source-name` and `:event-name` bindings retain original text. Other scope fields are rejected rather than silently ignored. The default eight-field contract remains unchanged.

The request's `:event-scope` contains the contract marker and the first five source fields, excluding the profile reference. This is an explicit internal grouping contract, not a publisher-assigned session or attempt ID. Raw source spellings remain unchanged. The marker participates in stored scope identity, preserving legacy event keys. Different dates and different contracts cannot become revision matches.

The complete retained artifact must replay with visible event/date context, supported attempt rows and a unique scoped profile key for every row. Missing fields, hidden or conflicting context, unsupported tables, malformed rows and duplicate participants fail closed, including rows omitted from the requested inventory. Collision checks conservatively compare discipline/gender spelling variants and UUID letter case without rewriting source claims or establishing positive identity matches. Overlapping participants cannot evade duplicate protection through separately selected spelling variants of the same daily scope.

Dated views permit only `:partial` coverage. Their gaps must include the exact `freediving.event-selections/daily-view-gap` value: `Daily view only; venue, round and session are not established.` Dated-view descriptors cannot enter the initial `:retained` baseline, which lacks their required coverage declaration; enroll them explicitly. This restriction can block initial adoption when other dated-view rows are already eligible. It never silently hides those rows.

Uniqueness within an artifact does not prove a single round, complete participation, or equivalent attempts across captures. Source changes still require explicit relationship review; every selected version still requires its own current validation. The [full retained-source audit](aida-scope-audit-20260925.md) records the exact packet and a Limassol schedule exception. No supplemental EventPage URL or mutable external context is accepted as a typed scope binding. Supporting context remains separately archived inspection evidence.
