# Retained AIDA promotion preflight

`scripts/retained_aida_promotion.py` creates a private, read-only checkpoint. It
compares an isolated canonical handoff with a fresh readback, replays original
AIDA packets against the frozen snapshot, and checks the target canonical and
owner store revisions. The output includes exact canonical event requests and
source rows. Its owner candidate intents are **not DecisionStore proposals**:
source-derived automatic approvals have no owner-store registration route.
The isolated human reversal remains provenance for the replay; it is not a
new live owner action.

## Dedicated private canonical target

From a clean checkout equal to merged `origin/main`, provision the dedicated
PostgreSQL target with the active owner snapshot hash:

```sh
bash deploy/provision_canonical.sh SNAPSHOT_SHA256
```

The wrapper pins the audited public database name
`freediving_release_20260924_11` and stages the exact committed release on
`bridge-vps`. Its root-only helper creates `freediving_canonical` with no PUBLIC
database access, applies the existing checksummed migrations 1-20, and verifies
an empty `target-state` at revision zero. It keeps a root-private intent and a
custom dump under `/var/backups/freediving/canonical`; a disposable restore is
checked before migration. An interrupted setup can resume only against that
intent and the verified migration transaction prefix. The helper checks that
public counts, config, service, site and owner snapshot/status stay unchanged.
It does not change the public release link or Worker.

For subsequent private manual apply, derive the target JDBC URL from the
root-only `/etc/freediving/migration.env` by replacing only its database path
with `freediving_canonical`. Use that exact URL for both
`FREEDIVING_REVIEW_URL` and `FREEDIVING_APP_URL`, and pin `PGDATABASE` to
`freediving_canonical`, `PGUSER` to `freediving_migrator`, `PGHOST` to
`127.0.0.1`, and `PGPORT` to the verified JDBC port. Keep credentials in the
process environment and private operator session, never in the runbook or logs.

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

The manual apply route is described below. Before host replay, reread the
target and owner revisions, snapshot, source refs, and event prefix; invalidate
the package on any change. Exact canonical replay preserves the human reversal
at its original sequence position. Owner candidate intents need a durable
flow-ledger binding. The owner store accepts the verified export as pending
proposals only. A failed preflight leaves the existing target unchanged.

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

## Guarded private preparation checkpoint

`scripts/retained_aida_activation_checkpoint.py` is a manually run, read-only
preparation gate. It accepts a freshly generated promotion preflight, its
pending-only owner envelope, a fresh target-state readback, the current owner
SQLite store, the frozen snapshot, and any recovered AIDA packet path. Hashes
for all JSON inputs are mandatory. Its target must have revision zero, no
events, no source rows, and the exact frozen snapshot ID. The owner binding and
revision must match the preflight and envelope, with no proposals or human
events. The adapter replays each AIDA packet against its receipt and original.

Run this only on the private host after obtaining `target-state` with the
target PostgreSQL connection as shown above. Generate the preflight from that
exact target readback, then regenerate the flow export and pending owner
envelope from the new preflight. Make an owner-only output directory outside
the repository. Substitute actual SHA-256 values from the files, never hashes
copied from an earlier target check:

```sh
mkdir -m 700 /private/aida-activation
FREEDIVING_REVIEW_URL='jdbc:postgresql://localhost/TARGET_DB?user=reviews_owner' \
  clojure -M -m freediving.retained-aida-apply target-state \
  SNAPSHOT_SHA256 /private/aida-activation/target-state.json
python3 scripts/retained_aida_activation_checkpoint.py \
  --preflight /private/promotion-preflight.json --preflight-sha256 PREFLIGHT_SHA256 \
  --envelope /private/pending-envelope.json --envelope-sha256 ENVELOPE_SHA256 \
  --target /private/aida-activation/target-state.json --target-sha256 TARGET_SHA256 \
  --owner-db /private/owner-decisions.sqlite --snapshot-dir /private/snapshot \
  --recovered-packet aida-source-name=/private/recovered/packet.json \
  --output-dir /private/aida-activation
```

The output has phase receipts, a SQLite backup, a separate restore drill, and
`checkpoint.json`, all owner-only. A repeat with unchanged inputs must return
the same checkpoint. A changed target, owner history, packet, source ref, or
envelope stops before a completion checkpoint. Preserve the whole directory.
This gate does not write to PostgreSQL or register proposals. Preserve its
owner-only SQLite backup and restore drill for the manual apply below. The 207
proposals remain pending, and the two human-correction exclusions remain
unresolved.

## Guarded manual apply

`scripts/retained_aida_manual_apply.py` has two host stages. Use a private
directory outside Git with mode 0700. Keep the snapshot, recovered packet,
preflight, flow export, envelope, and activation checkpoint outside Git too.
The command reads the current canonical target and owner SQLite store. It
backs up both stores, restores each backup to a separate disposable store, and
checks exact target identity before the first write. Put database credentials
in the usual PostgreSQL environment or passfile, never in the command line.
Set `PGHOST`, `PGUSER`, and `PGDATABASE` to the canonical target, and
`FREEDIVING_PG_DRILL_DATABASE` to an existing empty disposable database. The
database and host in `FREEDIVING_REVIEW_URL` must match those PostgreSQL
settings. Set `FREEDIVING_APP_URL` for canonical event writes. Both stages
fetch the current authenticated private active binding and require the exact
snapshot and bundle manifest before each write. Set `CF_ACCESS_CLIENT_ID`,
`CF_ACCESS_CLIENT_SECRET`, and `OWNER_EVIDENCE_STATUS_TOKEN` in the environment
for that read and the final status commit.

When the active owner store lacks source-derived AIDA refs, first run the
`rebind` stage with its current owner revision. It verifies every original and
recovered packet, completes SQLite and PostgreSQL backup and restore drills,
then performs one owner compare-and-swap rebind. It records a private phase
receipt. The rebind changes the owner revision, so regenerate the promotion
preflight, flow export, pending envelope, and activation checkpoint against
fresh target and owner reads before running `apply`.

```sh
mkdir -m 700 /private/aida-manual-apply
python3 scripts/retained_aida_manual_apply.py rebind \
  --snapshot-dir /private/snapshot --snapshot-sha256 SNAPSHOT_SHA256 \
  --bundle-sha256 BUNDLE_MANIFEST_SHA256 \
  --owner-db /private/owner-decisions.sqlite --expected-owner-revision 1 \
  --recovered-packet aida-source-name=/private/recovered/packet.json \
  --phase-dir /private/aida-manual-apply
```

After regenerating and hashing the four files, run the apply stage. Keep
status credentials out of arguments and receipts. `--run-dir` points to the
completed retained local run. The current private owner origin must support
the v3 application status receipt before this stage can complete.

```sh
python3 scripts/retained_aida_manual_apply.py \
  --preflight /private/preflight.json --preflight-sha256 PREFLIGHT_SHA256 \
  --flow /private/flow-export.json --flow-sha256 FLOW_SHA256 \
  --envelope /private/pending-envelope.json --envelope-sha256 ENVELOPE_SHA256 \
  --checkpoint /private/activation/checkpoint.json \
  --checkpoint-sha256 CHECKPOINT_SHA256 \
  --bundle-sha256 BUNDLE_MANIFEST_SHA256 \
  --owner-db /private/owner-decisions.sqlite --snapshot-dir /private/snapshot \
  --recovered-packet aida-source-name=/private/recovered/packet.json \
  --run-dir /private/completed-run --phase-dir /private/aida-manual-apply
```

The apply stage accepts only an empty or exact canonical event prefix with
all-or-empty matching source rows. It registers source rows, replays the exact
211 events including the human reversal, reads back the final canonical
projection, registers the 207 pending owner proposals with compare-and-swap,
and reads back the owner store. Only then does it commit the private v3 status
receipt. Phase receipts support an unchanged retry after interruption. A new
human owner action, unrelated canonical event, changed source, changed
snapshot, or changed package stops the retry. Failure leaves the old live
presentation in place; no public route is enabled.

For a disposable local rehearsal only, use `--isolated-rehearsal` with
`FREEDIVING_PG_ISOLATED=1`, an `aida_rehearsal_*` target database, and an
`aida_drill_*` restore database. Supply an owner-only JSON file with the exact
`snapshot_sha256` and `bundle_manifest_sha256` as `--active-binding FILE
--active-binding-sha256 SHA256`. That mode checks the pinned binding, suppresses
the remote status write, and records a private rehearsal receipt.
