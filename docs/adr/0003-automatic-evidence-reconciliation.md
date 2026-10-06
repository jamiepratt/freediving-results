# ADR 0003: Automatic evidence reconciliation with later review

Date: 2026-10-02. Status: accepted owner direction.
Implementation status, updated 2026-10-06: the supported private reconciliation
and authenticated review path is implemented. Its bounded integration acceptance
is recorded in [closed #73](https://github.com/jamiepratt/freediving-results/issues/73);
live VPN transition/restoration certification remains open in
[#64](https://github.com/jamiepratt/freediving-results/issues/64).
Implementation scope and dependencies: [issue #67](https://github.com/jamiepratt/freediving-results/issues/67).

## Decision

Minimize owner intervention. Run reconciliation from cheapest to most expensive:
reuse retained evidence and decisions, apply deterministic rules, then send only
unresolved judgments to Jev. Approve qualifying decisions automatically from the
first run using conservative provisional policies. Review and reverse individual
decisions afterward; a new manually labelled calibration batch is not a startup
prerequisite. Initial confidence gates are provisional, not measured accuracy.

This settles the reconciliation deferral in ADR 0001 and the earlier
advisory-only/manual-review-first direction for the new reconciliation path.
Existing APIs and historical experiments retain their actual contracts until
implementation changes them. Automatic reconciliation does not manufacture
human attestations or grant extraction-publication authority.

## Evidence and decision boundaries

Keep source objects, source positions, observation versions, sporting attempts,
athletes and their relationships distinct. Originals remain immutable. A source
row may support several category memberships or several representations of one
attempt; a ranking or mirror is not an extra attempt. Preserve raw and final
performance, status, penalties, notes, names and units independently.

Category memberships and represented country or organization belong to the dive
and its event context. Preserve original labels and the evidence for normalized
values. Keep discipline separate from category. Federation/neutral codes are
not necessarily countries, and representation is not nationality. Missing facts
remain unknown; an athlete name, residence or later representation cannot fill
a historical dive's country field.

A decision records its subject/type, exact evidence versions and citations,
original and proposed values, supporting and conflicting evidence, dependencies,
rule or model/template version, approval policy, actor kind and status. Automatic
approval, human review, unresolved, superseded and reversed are distinguishable.
Deterministic decisions carry rule evidence and no invented model confidence.

## Deterministic decisions first

Reuse verified source bytes and decisions before recomputation. Group repeated
acquisitions and parser versions without inflating attempt counts. Apply explicit
field mappings and supported heading semantics before model assistance.

Identity rules may use verified publisher identifiers within their actual
namespace, accepted aliases, or distinctive exact full names with compatible
context, one plausible candidate and no conflicting evidence. Version the
normalization, distinctiveness and contradiction rules. The owner's domain
observation is that name collisions are unusual except for common names; this
is useful prior guidance, not a measured collision rate or proof of identity.
Common names, ambiguous readings and competing candidates require resolution.
Preserve native scripts and evidenced romanizations. Country changes must not
exclude otherwise plausible matches. A bib or rank is not a global athlete ID.

Candidate retrieval uses indexes and multiple matching routes. A candidate
bucket is not a person, and no candidate does not prove global distinctness.
Stable provisional athlete records can exist while cross-record links remain
unknown. Accepted grouping counts must retain that qualification.

Source equivalence, revision direction, same sporting attempt and same athlete
are separate decisions. Matching names or performance alone cannot collapse
separate dives. Retrieval time alone cannot order publisher revisions. Missing
session/round/attempt scope must remain visible. Validate group compatibility
before adding links so a weak chain cannot silently join conflicting people.

## Jev request and approval policy

Ordinary code constructs every routine request from versioned templates and
retained structured evidence. No other LLM constructs each Jev query. Reuse the
existing compact request builder and durable run store; preserve source-label
mappings locally while omitting audit-only bulk from the wire payload.

Shared state carries stable domain rules. Each question's structured instructions
carry only the relevant facts/excerpts, headings, candidates, scope and known
contradictions. Unrelated case evidence stays out of shared state. Source text
is data, never executable instructions. Include original spellings and explicit
extraction uncertainty. Missing information is not negative evidence.

Use finite choices with an unknown option for identity, same-attempt,
source/revision, category/representation and row-semantics questions. Category
and representation judgments must follow supplied source meanings; Jev must not
invent absent source values. Jev accepts text; exceptional scan transcription
and verification follow ADR 0001 before those artifacts become decision inputs.

Batch independent questions within request limits; run dependent questions only
after their inputs exist. Cache by relevant evidence, candidate set, question,
template, model and configuration. Changed source evidence invalidates affected
requests; unrelated corpus changes should not trigger wholesale rescoring.
Policy-only reevaluation can reuse compatible retained distributions.

Retain chosen answer, complete probability distribution, provider confidence,
actual model, request/result identity, usage, exact input and policy/template
versions. Confidence describes the model's output distribution; it is not an
observed correctness rate. Preserve raw probabilities and validation outcomes.

Use conservative, configurable, versioned thresholds per decision type and
action. Gates consider confidence, selected probability, alternatives, evidence
adequacy and group conflicts. No single numeric threshold is established by this
ADR. High-confidence unknown, stale evidence, malformed answers and unresolved
contradictions cannot approve an affirmative relationship. Provider errors leave
explicit resumable work rather than silently making a decision.

Review samples of automatic approvals after processing, alongside low-confidence
cases. Report sample sizes, selection bias, reversals and measured error rates
separately from provider confidence; use that evidence to improve policies.
Historical assistant-labelled Jev experiments do not establish fresh calibration.
Do not impose a new mandatory human calibration gate before the first run.

## Reversal and local/remote state

Approve through an append-only decision ledger, preserving every observation
and previous decision. Identity merges are reversible relationships, not deletion
of source records. Reversal recomputes affected groups, counts and projections,
invalidates dependent decisions, and preserves independently supported facts.
Human corrections prevent automatic reapplication until explicitly superseded
by a human. New conflicting evidence is shown for review.

Reconciliation executes locally as part of the ingestion direction in ADR 0002.
The owner reviews through https://poc.alphacompose.com/owner-evidence. Persistent
decision authority is separate from immutable evidence snapshots: snapshot
replacement and subsequent local runs must preserve remote human corrections.
Bind each projection to its evidence snapshot and decision revision. Stale
exports cannot overwrite newer decisions. The implemented synchronization and
incremental binding contract is documented in the
[local evidence run](../local-evidence-run.md), with acceptance in
[#73](https://github.com/jamiepratt/freediving-results/issues/73).

Pending scored decisions sort from low to high Jev confidence with stable ties.
Scoreless failures and gaps stay visible separately. All automatic approvals
remain searchable and reversible. Show alternatives, evidence, actor, versions,
audit history and the downstream impact of a reversal. Scores across question
types are not calibrated accuracy rankings.

## Implementation at the decision date and alternatives

The following describes the building blocks on 2 October 2026, before the
private reconciliation and review implementation below shipped:

- `jev_candidates.clj` builds source-bound candidate requests in code.
- `evaluation_protocol.clj` and durable evaluation runs already provide compact
  question-local evidence, versioned requests, probabilities/confidence and replay.
- `spelling_normalization.clj` has reversible spelling selection with a fixed
  0.95 probability/confidence gate and 0.20 margin. These are existing thresholds,
  not evidence for general reconciliation accuracy.
- `reviews.clj` records explicit proposals/decisions; the then-existing general identity
  path still requires reviewer authority. `revisions.clj` has an exclusive-endpoint
  contract that cannot silently become a general multi-source attempt graph.
- The then-deployed owner evidence viewer read immutable SQLite and exposed no
  review writes. Accepting this ADR alone did not implement those writes.

The supported private implementation now includes
[`reconciliation_flow.clj`](../../src/freediving/reconciliation_flow.clj),
[`reconciliation_jev.clj`](../../src/freediving/reconciliation_jev.clj) and
[`reconciliation_application.clj`](../../src/freediving/reconciliation_application.clj):
append-only deterministic decisions, bounded versioned Jev requests and cache
reuse, approval gates, dependency invalidation and reversible canonical routing.
[`owner_evidence_origin.py`](../../scripts/owner_evidence_origin.py) provides
authenticated inspection, preview and review actions, with signed human-event
delivery and separately acknowledged flow/canonical destinations. Normal local
runs preserve those corrections and immutable proposal bindings across reruns
and guarded presentation updates.

The [retained #76 audit](../retained-corpus-reconciliation-audit-20261006.md) is
historical and explicitly partial; later [#73 acceptance](https://github.com/jamiepratt/freediving-results/issues/73)
verifies the supported private integration. The 6 October private readback has
five accepted same-attempt links and unknown current canonical identity status.
Historical identity totals are not current accepted global athletes; independent
identity accuracy, complete corpus coverage and actual provider billing remain
unknown. These limits do not change this ADR's accepted policy.

Rejected defaults: an LLM preparing every query; Jev for deterministic cases;
mandatory manual review before all approvals; blind approval from confidence
alone; destructive merges; inferring nationality; counting mirrors/versions as
dives; treating automatic approval as human review or public publication.

## User-facing copy impact

The private owner UI now distinguishes automatic approval, human review,
unresolved, superseded and reversed states, sorts scored pending decisions by
confidence, and shows cited evidence, audit history and reversal impact. Current
canonical readback keeps identity status unknown separately from verified
same-attempt acceptance. Public reviewed-coverage copy remains governed by
publication policy. External CMS/email surfaces are unverified.

## References

- [Ingestion direction](0001-staged-evidence-ingestion.md) and
  [local execution/presentation](0002-local-ingestion-remote-presentation.md).
- [Review rubric](../review-rubric.md), [revision relationships](../revision-relationships.md),
  [owner workspace](../owner-evidence-workspace.md), and
  [historical Jev evaluation](../local-shadow-evaluation.md).
- [TypeSafe request schema](https://docs.typesafe.ai/api),
  [text state](https://docs.typesafe.ai/concepts/state), and
  [confidence semantics](https://docs.typesafe.ai/confidence), checked 2026-10-02.
