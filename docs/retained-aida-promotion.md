# Retained AIDA promotion preflight

`scripts/retained_aida_promotion.py` creates a private, read-only checkpoint. It
compares an isolated canonical handoff with a fresh readback, replays original
AIDA packets against the frozen snapshot, and checks the target canonical and
owner store revisions. The output includes exact canonical event requests and
source rows. Its owner candidate intents are **not DecisionStore proposals**:
source-derived automatic approvals have no owner-store registration route.
The isolated human reversal remains provenance for the replay; it is not a
new live owner action.

Obtain a fresh isolated readback using the same cohort and receipt that made
the handoff:

```sh
FREEDIVING_REVIEW_URL='jdbc:postgresql://localhost/ISOLATED_DB?user=reviews_owner' \
  clojure -M -m freediving.retained-aida-apply readback \
  /private/run/reconciliation/aida-cohort.json COHORT_SHA256 \
  /private/run/reconciliation/canonical-receipt.json /private/current-readback.json
```

On the **target**, `target-state` writes the exact event requests and source
rows to a private 0600 JSON file. A fresh empty target is represented with
revision zero and empty rows. It fails if a nonempty target has a stale
canonical projection or different active snapshot.

```sh
FREEDIVING_REVIEW_URL='jdbc:postgresql://localhost/TARGET_DB?user=reviews_owner' \
  clojure -M -m freediving.retained-aida-apply target-state \
  SNAPSHOT_SHA256 /private/target-state.json
```

Hash the three files before preflight. The owner DB argument must be an
existing private SQLite store or an exact read-only copy of it. The tool opens
it in SQLite read-only mode. `--expected-owner-revision` and
`--expected-production-revision` are operator checkpoints from a current
read, not values inferred from the isolated handoff.

```sh
python3 scripts/retained_aida_promotion.py \
  --handoff /private/handoff.json --handoff-sha256 HANDOFF_SHA256 \
  --current-readback /private/current-readback.json \
  --current-readback-sha256 READBACK_SHA256 \
  --production-history /private/target-state.json \
  --production-history-sha256 TARGET_SHA256 \
  --expected-production-revision 0 \
  --owner-db /private/owner-decisions.sqlite --expected-owner-revision 1 \
  --snapshot-dir /private/snapshot \
  --recovered-packet aida-source-name=/private/recovered/packet.json \
  --output /private/promotion-package.json
```

The recovered packet must match the frozen manifest and replay against its
receipt and original HTML. All registered rows must reverify; any gap fails.
The active owner binding must contain each exact source-derived ref and zero
existing proposals. The target source rows must be an exact subset of the
isolated rows, with no other canonical row family. Its existing event history
must be an exact prefix of the isolated requests. A mismatched input or
checkpoint fails without writing a package. A repeated command accepts only
byte-identical output. Standard output contains counts and a package hash,
not athlete rows.

For a frozen AIDA packet whose manifest path is missing, a trusted private
operator can explicitly rebind the same snapshot with
`DecisionStore.bind_verified_snapshot(..., recovered_packet_paths={source_name:
packet_path}, expected_revision=current_revision, idempotency_key=unique_key)`.
The recovered packet must match the frozen manifest hash and replay against
its receipt and original HTML. The rebind requires zero AIDA source gaps and
appends a new binding revision; it does not change snapshot record membership.
The owner origin does not perform this rebind automatically. A changed owner
revision, snapshot, packet, receipt, or original fails the operation. Reread
the owner binding and store revisions before running the preflight above.

There is no apply command for this package. Before any host replay, reread
the target and owner revisions, snapshot, source refs, and event prefix;
invalidate the package on any change. Exact canonical replay must preserve
the human reversal at its original sequence position. Owner candidate intents
need a real flow-ledger reconciliation event binding and an authorized owner
action. The current owner store rejects automatic approval on source-derived
revisions. On preflight failure, the existing target remains unchanged;
discard the private package or rerun against fresh checkpoints.

## Pending owner proposal checkpoint

`freediving.retained-aida-flow` builds a private source identity decision and
`reconciliation-flow/1` ledger from an exact, hash-pinned promotion preflight.
It calls the current athlete identity retrieval over all registered source rows,
keeps every retrieved candidate, and runs the source context guard with provider
execution disabled. It reports every active intent as supported or unresolved.
An intent sharing a publisher person with the isolated human reversal stays
unresolved. The input must be a current preflight made from the isolated
canonical readback and a fresh target checkpoint; the runner itself does not
replay the AIDA originals.

```sh
mkdir -m 700 /private/aida-flow
shasum -a 256 /private/promotion-preflight.json
clojure -M -m freediving.retained-aida-flow \
  /private/promotion-preflight.json PREFLIGHT_SHA256 \
  /private/aida-flow/flow-export.json
shasum -a 256 /private/aida-flow/flow-export.json
```

`scripts/retained_aida_owner_bridge.py` constructs a pending-only owner export
from that flow export, a fresh `load_source_observations` result with zero gaps,
and a current read-only owner state. Pass `expected_preflight_sha256` from the
exact preflight file bytes. The bridge verifies all retrieved candidates against
the snapshot adapter and owner binding, the selected canonical pair, source
event lineage, publisher person and printed name, policy and rule versions, and
the complete intent denominator. Only human-correction exclusions backed by
the verified reversal are allowed. A source decision without a matching current
flow event fails the export. Keep the flow, source rows, and owner envelope in
an owner-only directory outside Git.

A trusted private caller may pass a successful envelope to
`owner_decision_export_adapter.register_verified_export` with the same immutable
snapshot directory and an explicit `recovered_packet_paths` mapping when the
frozen packet is only at its recovered private path. The adapter replays the
packet against its original and receipt before `DecisionStore.register_batch`
commits. The owner revision and binding must still equal the envelope values.
Keep the export and every source row outside Git. A rejected or incomplete
bridge result is not an import checkpoint.

For the retained AIDA cohort checked on 2026-10-04, an isolated owner copy
rebound all 281 canonical source refs with zero gaps. The preflight has 211
canonical events, including one human reversal, and 209 active intents. The
hash-pinned flow export supports 207 decisions: 24 pair-only and 183 with 3-10
retrieved candidates. Two intents sharing the human-corrected publisher person
remain unresolved. The bridge accepts those 207 as pending-only proposals with
the complete denominator. The verified export adapter registered them in an
isolated owner copy at revision 209; an idempotent replay kept that revision.
This is an isolated checkpoint, not a live import.
A live promotion still requires a fresh production target readback, current
owner revision, guarded synchronization, authenticated browser review, and
separate publication authority.
Track that remaining work in [#72](https://github.com/jamiepratt/freediving-results/issues/72)
and [#73](https://github.com/jamiepratt/freediving-results/issues/73).
