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
