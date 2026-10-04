# Local evidence run

## Retained AIDA private status

After a completed retained AIDA run, `python3 scripts/local_evidence_run.py retained-status --run-dir /private/retained-run` prints a sanitized JSON checkpoint. It rechecks staged snapshot files, every staged bundle input and recovered source, the cohort export, and any applied canonical receipt against their recorded hashes. The checkpoint binds the frozen cutoff, snapshot and bundle hashes, input role hashes, export hash, source rows and gaps, isolated decision revision, provider call count, and the canonical receipt's identity and human correction revisions when applied. It contains no local paths, source rows, athlete fields, or credentials.

An applied canonical receipt records what the local canonical apply reported at that moment. `current_revision_verified` remains false because this read-only command does not query the authoritative store again. The isolated DecisionStore revision and isolated owner correction revision are separate from canonical release revisions. Remote status remains pending and unverified; this command neither syncs the normal presentation-status record nor activates a host. It rejects normal run and remote activation flags. A changed staged input or incomplete canonical checkpoint stops with an error. Keep the full run and printed checkpoint private until a guarded activation and owner-route readback can establish a live presentation.

`counts.candidate_edges` counts edges in this retained export, including only incremental edges on a correction-preserving rerun. It is not the total automatic decision history. `canonical.accepted_group_count` is the canonical receipt's grouping count at apply time; neither count is a global athlete count.

## Retained AIDA recovery input

For the private `retained` command, a frozen AIDA packet missing from its original manifest path can be supplied in the retained plan's optional `recovered_packets` list:

```json
"recovered_packets": [{
  "source_name": "aida-source-name",
  "path": "/private/recovery/packet.json",
  "sha256": "<frozen packet SHA-256>",
  "receipt_sha256": "<receipt SHA-256>",
  "original_sha256": "<original HTML SHA-256>"
}]
```

Use a new private run directory after changing the plan. The packet path must be a regular `packet.json` with adjacent regular `receipt.json`; the receipt's relative `body.path` locates the original. The runner checks all three hashes, requires the packet hash to match the frozen snapshot manifest, then stages and rechecks the three files in `cohort-bundle/recovered-packets/`. Staging rejects symlink paths. The bundle manifest and completed checkpoint bind their hashes; `metrics` rechecks staged recovery bytes. Source replay still verifies the packet against the receipt and original and requires every position to match the frozen snapshot and observations. Missing packets without an explicit recovery remain reported gaps. The command has no remote presentation step.

`scripts/local_evidence_run.py` coordinates existing local commands and the private snapshot and source-bundle builders. It does not fetch by itself. With an explicit remote configuration, the same `run` command presents the verified snapshot and bundle remotely after local completion. Keep the plan, run directory, source inventory and all outputs outside Git, under private storage. Commands in the plan run locally with the invoking user's privileges. Use absolute paths and only trusted plans.

A plan declares a UTC cutoff, ordered command stages, hash-checked outputs, snapshot packet inputs, explicit excluded packets, and a source inventory for `private_source_bundle.py`. Discovery, acquisition and parser CLIs can be stage commands. For example:

```json
{
  "schema": "local-evidence-run-plan/v1",
  "cutoff": "2026-10-03T00:00:00Z",
  "stages": [
    {"name": "acquire", "command": ["python3", "/path/to/scripts/acquire_source.py", "..."], "outputs": ["/private/acquisition-receipt.json"]},
    {"name": "parse", "command": ["python3", "/private/parser.py", "..."], "outputs": ["/private/packet.json"]}
  ],
  "inputs": [{"name": "parsed-packet", "path": "/private/packet.json"}],
  "excluded": [{"name": "unavailable-page", "path": "/private/gap.json", "reason": "publisher unavailable"}],
  "source_inventory": "/private/source-inventory.json"
}
```

Run `python3 scripts/local_evidence_run.py run --plan /private/plan.json --run-dir /private/run-20261003`. Use a new run directory for a changed plan or new cutoff. The coordinator writes `state.json` atomically after each successful stage. On interruption, rerun the same command; completed stages are skipped only if all declared output hashes still match. A changed completed output stops the run for inspection. A failed snapshot build can resume without rerunning completed acquisition or parsing.

The coordinator builds and verifies `snapshot/` before making it visible, then builds and verifies `bundle/`. It inserts `snapshot.sqlite` and `snapshot/manifest.json` into the bundle inventory as `snapshot.sqlite` and `snapshot-manifest.json`; those IDs are reserved. It checks their hashes against the bundle manifest. The run is locally complete only after both artifacts verify. `state.json` records a dated `verified_partial` coverage claim, explicit excluded gaps and unknown distinct-attempt count. A successful local run without remote configuration sets `remote.status` to `pending`. With remote configuration, the same command attempts presentation automatically after local verification. It records `remote.status: active` only after authenticated owner readback verifies the snapshot, source bundle and cited PDF rendering. A remote failure records `remote.status: failed`, retains the previous `remote.active` binding where rollback succeeds, and leaves `local.status: complete` plus snapshot and bundle files for retry. `coverage.cutoff` and `coverage.gaps` remain visible in every state. Local failure records `local.status: failed` and does not change a previously completed run directory.

## Synthetic local reconciliation checkpoint

An optional `reconciliation` plan entry exercises the existing Clojure reconciliation application after verified local staging. This first integration mode is limited to disposable synthetic decisions and never activates remote presentation:

```json
"reconciliation": {
  "mode": "synthetic",
  "spec": "/private/synthetic-decisions.edn",
  "name_evidence": {"status": "gap", "reason": "no affiliate roster in this synthetic fixture"}
}
```

The spec is trusted local EDN with `:config`, a vector of `:decisions`, optional `:deterministic-results`, and `:synthetic-answers` keyed by decision ID. Synthetic answers use the existing Jev batch response shape. There is no live provider or production import. A checked affiliate input may replace the gap with `{"status":"checked","path":"/private/affiliate-input.json","sha256":"..."}`; its snapshot binding, source bytes and receipts are verified with the existing affiliate query contract. Without either checked input or a reasoned gap, the plan fails before staging.

The flow ledger lives at `reconciliation/flow.edn` under the private run directory. The application persists pending dispatch before synthetic scoring and routes supported decisions through its canonical application rules. `state.json` records a separate `reconciliation` checkpoint with snapshot binding, spec and ledger hashes, decision revision, flow status counts, local canonical outcomes, explicit name gap and synthetic provider calls. Completed matching runs skip scoring; a changed spec or ledger stops for inspection. `accepted_athletes` and `distinct_attempts` remain null. A reconciliation failure leaves `local.status: complete` and `remote.status: pending`. This checkpoint does not project decisions into the remote owner store or satisfy the retained 2025-2026 corpus run in issue #73.

The private `python3 scripts/local_evidence_run.py metrics --run-dir /private/run-20261003` command reads the completed synthetic reconciliation receipt from `state.json` and verifies its ledger hash. The receipt binds run ID, snapshot hash, decision revision, spec and ledger hashes, policy, template, model and config versions, and reconciliation stage. Coverage uses the supplied decision count as its denominator, with automatic approvals, unknown outcomes, errors, conflicts and pending review by family. Source gaps combine excluded snapshot inputs and the affiliate-name gap. `calls_this_execution` counts synthetic transport dispatches in that execution; `calls_recorded` accumulates completed executions in this checkpoint. `cache_hits_this_execution` counts only cached policy reevaluations appended during this execution; `cache_hits_recorded` counts durable cached events in the ledger. An interrupted dispatch before a completed report can leave its actual call count unknown. Provider usage, latency, actual monetary cost, accepted athletes, distinct sporting attempts and observation versions remain null until measured or authoritatively bound. An optional synthetic `:review-sample` requires its sampling frame, reviewed denominator, error numerator, selection method and selection bias; its rate is a sample estimate, never provider confidence or corpus accuracy.

For disposable owner-event synchronization, add `"owner_sync_config":"/private/owner-sync.edn"` to that reconciliation entry. The owner-only regular EDN file supplies the signed transport fields (`:base-url`, Access service credentials, `:import-token`), exact `:current-bindings`, `:active-snapshot-sha256`, `:active-binding-revision`, and applicable canonical target options. The runner checks file ownership and mode, authenticates the signed feed, persists each event in the private flow ledger, and routes it to its canonical target before synthetic scoring. It sends an authenticated ACK for each completed target to the owner's SQLite outbox. The private ledger's ACK cursor advances only after both target responses. A lost ACK response replays the idempotent callback and ACK. Failed canonical routing leaves the PostgreSQL ACK pending. The completed checkpoint is rechecked for newer owner events on every invocation; unchanged feeds reuse retained answers without provider calls. The report includes `remote_store_revision`, the latest imported human event revision. The origin and Cloudflare Worker must both deploy the `/owner-evidence/api/decision-events/ack` endpoint before this mode can complete remotely. The local runner CLI test covers a lost flow ACK response and a missing canonical target; completed remote ACK plus PostgreSQL reversal is verified in separate component and private owner CLI tests, not as one runner CLI end-to-end test. The separate `owner_decision_export_adapter.py deliver` CLI remains available for private local delivery.

## Private transfer staging

After local completion, `python3 scripts/private_evidence_transfer.py stage --run-dir /private/run-20261003 --destination /private/remote-staging` copies the verified snapshot and matching source bundle into an owner-only destination directory. The destination must already exist with mode 0700. This path may be a disposable local stand-in; it is not an active presentation path. The command never changes an `active` link or updates `state.json` to presented.

The command verifies the completed run, snapshot, bundle, and their hash binding before transfer. It stages under `staging/<snapshot-sha256>-<bundle-manifest-sha256>/` and returns a JSON receipt containing the relative staging path and pinned hashes. That directory has `snapshot/` and `source-bundle/` inputs matching the guarded activation helper's snapshot and source-bundle arguments. The receipt is written only after destination verification. A later transport/VPN adapter can call this staging contract and pass the resulting paths and hashes to guarded activation after its own network transition; remote authentication and activation are not part of this command.

Rerun the same command after interruption. Existing complete files are hash-checked and skipped; a `.part` file resumes after its existing prefix is checked against the verified source. A corrupt staged file or partial copy stops with an error for inspection. Leave the previous active presentation in place until authenticated remote validation succeeds. Keep this destination and its receipt outside Git and private storage.

## VPN transition boundary

`scripts/macos_nordvpn.py` defines a fail-closed transition contract for a future verified NordVPN control. It has no live macOS control implementation or deployment CLI. A caller must confirm publisher requests are stopped. If the VPS is reachable, the boundary leaves the VPN alone. If access is blocked, it requires a verified exact NordVPN connection state, endpoint and category, writes a private restoration journal before disconnecting that one service, verifies the changed state and VPS route, and restores and verifies the original state after deployment, failure or interruption. An unresolved `restore_pending` journal blocks a new transition; `recover_connection` retries restoration from it. A restoration failure is explicit and leaves the journal for recovery.

NordVPN 10.6.0 on macOS has not exposed a verified unattended interface that both selects and confirms an obfuscated connection and restores its exact prior state. Do not plug the system `scutil --nc` service listing or a recent app UI selection into this contract as proof of category or connection. Remote SSH staging and guarded activation remain separate issue #64 work.

## SSH transfer staging

`python3 scripts/private_evidence_transfer.py ssh-stage --run-dir /private/run-20261003 --host owner-vps --remote-root /private/remote-staging --remote-script /opt/freediving/scripts/private_evidence_transfer.py` sends the same verified snapshot and bundle to an owner-only remote staging root. The root must already exist at mode 0700. The remote host must have matching `private_evidence_transfer.py`, `private_evidence_ssh.py`, `private_source_bundle.py`, and `unified_evidence_snapshot.py` installed together, with Python dependencies available. The protocol handshake fails closed on a version mismatch. SSH authentication and host-key policy come from the local SSH configuration; this command does not supply credentials or relax host checking.

The transport invokes the remote helper only under `staging/<snapshot-hash>-<bundle-manifest-hash>/`. It checks remote complete objects by hash and compares any partial prefix with the local verified file before resuming. Remote files use mode 0600 and directories mode 0700. The remote helper verifies the snapshot, source bundle and receipt before the command reports success. Rerun after interruption. Mismatched remote objects or partials require inspection; the command does not overwrite them. Its JSON receipt identifies staged inputs for a later guarded activation step. It never changes active presentation or marks the run presented. This command assumes an already reachable SSH route; a verified VPN transition controller and transactional activation are separate issue #64 work.

## Synthetic presentation coordinator

`scripts/evidence_presentation.py` exports `present(run_dir, remote, ...)` for a private remote adapter. It verifies the completed local snapshot and bundle again, writes `remote.pending`, checks the exact SSH staging receipt, calls guarded activation, and reads the authenticated owner `/owner-evidence/api/overview` response. The response must be HTTP 200 and carry both the served `snapshot_sha256` and `bundle_manifest_sha256`. The private origin adds the latter only when its source bundle loaded and verified. The pending, failed, and active revisions contain both hashes. A host-local activation checkpoint alone never changes `remote.active`.

The adapter supplies `stage(run_dir)`, `activate(receipt)`, `owner_overview()`, and `rollback(receipt)`. `stage` must use `ssh_stage` or an equivalent verified transport. `activate` must use the guarded host helper with the receipt's snapshot and source-bundle paths and hashes. If owner-route verification fails after activation, `rollback` must call the guarded host helper with `--rollback-candidate-sha256 <snapshot-sha256> --rollback-source-manifest-sha256 <bundle-manifest-sha256>`. The helper rejects a stale rollback request and restores the previous host pin, links, service, and private source bundle. An interrupted local coordinator invocation remains pending; rerunning verifies input and repeats safe stages. Failures record `remote.failed` and keep the local bundle for retry. If rollback itself fails, `remote.active` becomes null and `remote.rollback_error` identifies the unresolved failure. An unchanged active binding is skipped only after another owner-route check; a mismatched response clears the recorded active binding.

The caller must attest publisher requests have stopped before a route probe. A reachable VPS route skips the VPN boundary entirely. If blocked, the caller must inject a verified network control and private restoration journal; the existing `macos_nordvpn.deployment_route` boundary records and restores exact prior state. `scripts/private_evidence_remote.py` now supplies `PrivateEvidenceRemote` for this interface. Its `stage` uses the verified SSH protocol; `activate` invokes the guarded host helper with the exact staged snapshot and source bundle paths, pin hashes, and a preinstalled code bundle. Its rollback invokes that helper with both candidate hashes. Owner readback sends a caller-supplied owner identity `CF_Authorization` cookie to the pinned custom domain, with a bounded timeout and no redirects. The cookie is held only in memory and must represent an owner login accepted by the Access policy; a service token cannot satisfy the owner email gate. It checks the overview hashes, a cited PDF source-view response against an included staged object hash and byte count, then the cited PDF page as bounded `image/png` bytes. Access denial, stale responses and source mismatch fail before the coordinator records active. The code bundle and helper must already be installed and configured on the host. There is no unattended NordVPN 10.6.0 control, and no live host or Access test has been performed. Tests use disposable SSH and command/HTTP stand-ins. Do not use this contract as evidence that the live owner route has been updated.

## Opt-in remote presentation

Create a private JSON configuration outside Git with exactly these fields: `host`, `root`, `remote_script`, `ssh`, `code_bundle`, `activation_script`, `owner_url`, `cited_record_id`, and `cited_source_sha256`. The owner URL is pinned to `https://poc.alphacompose.com/owner-evidence`; no host or destination is inferred from a test fixture. The cited record ID and SHA-256 must name an included PDF in the verified bundle. The host must already have the matching transport helper, code bundle and guarded activation helper installed. Review [deployment](deployment.md) before a live run; the public release workflow does not install these private scripts or helper.

Put the owner Access JWT in a private environment variable. Pass its **name**, never its value, to the command:

```sh
python3 scripts/local_evidence_run.py run \
  --plan /private/plan.json --run-dir /private/run-20261003 \
  --remote-config /private/remote.json \
  --owner-access-jwt-env OWNER_EVIDENCE_ACCESS_JWT \
  --publisher-requests-stopped
```

The final flag attests that publisher requests are stopped. The coordinator requires it before any SSH route probe or transfer. Its bounded SSH probe retains normal host-key policy and BatchMode. An unreachable route fails closed because there is no verified unattended macOS NordVPN controller for exact obfuscated selection and restoration. The command does not disconnect a VPN. Rerun the same command after fixing route or credentials; the completed local stages are skipped after hash verification. A configured remote failure exits nonzero and leaves a retry checkpoint. Without `--remote-config`, the result remains remote pending and no remote request occurs. A local disposable stand-in can exercise the `run()` coordinator API with an injected remote and route probe; the CLI has no synthetic live-host bypass.

## Private checkpoint status sync

After the owner origin and Worker have the separate status writer credentials configured, add `--status-access-jwt-env STATUS_ACCESS_JWT --status-token-env OWNER_EVIDENCE_STATUS_TOKEN` to `run`. These flags name private environment variables, never credential values. The first holds a signed Cloudflare Access service assertion for the dedicated status client ID; the second holds the origin status token. The sync endpoint is pinned to `https://poc.alphacompose.com/owner-evidence/api/presentation-status`. The command publishes after local verification, at pending transfer, and after success or failure. Rerunning the same run retries an interrupted sync. A failed status sync exits nonzero and leaves the local checkpoint intact.

The synced record contains a stable run ID, revision, local snapshot hash and cutoff, verified partial gap count, pending and failed snapshot hashes, and the origin's currently served active snapshot and source-bundle hashes. It contains no local paths, source bytes, credentials, gap reasons, or athlete fields. The owner page displays unavailable when no record has reached the origin. The origin stores the record in an independent owner-only file and rejects stale revisions, changed bindings for the same run, older cutoffs, and active claims not matching the currently served verified bundle. Browser writes are denied; an owner Access login reads the result.
