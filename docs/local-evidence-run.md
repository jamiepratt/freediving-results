# Local evidence run

`scripts/local_evidence_run.py` coordinates existing local commands and the private snapshot and source-bundle builders. It does not fetch by itself or deploy remotely. Keep the plan, run directory, source inventory and all outputs outside Git, under private storage. Commands in the plan run locally with the invoking user's privileges. Use absolute paths and only trusted plans.

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

The coordinator builds and verifies `snapshot/` before making it visible, then builds and verifies `bundle/`. It inserts `snapshot.sqlite` and `snapshot/manifest.json` into the bundle inventory as `snapshot.sqlite` and `snapshot-manifest.json`; those IDs are reserved. It checks their hashes against the bundle manifest. The run is locally complete only after both artifacts verify. `state.json` records a dated `verified_partial` coverage claim, explicit excluded gaps and unknown distinct-attempt count. A successful local run sets `remote.status` to `pending` and records the pending snapshot hash. `remote.active` stays null until a later authenticated remote verification establishes the active version. Local failure records `local.status: failed` and does not change a previously completed run directory. Remote transfer, activation and failure reporting remain separate work under [issue #64](https://github.com/jamiepratt/freediving-results/issues/64).

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

`scripts/evidence_presentation.py` exports `present(run_dir, remote, ...)` for a private remote adapter. It verifies the completed local snapshot and bundle again, writes `remote.pending`, checks the exact SSH staging receipt, calls guarded activation, and reads the authenticated owner `/owner-evidence/api/overview` response. The response must be HTTP 200 and carry both the served `snapshot_sha256` and `bundle_manifest_sha256`. The private origin adds the latter only when its source bundle loaded and verified. A host-local activation checkpoint alone never changes `remote.active`.

The adapter supplies `stage(run_dir)`, `activate(receipt)`, `owner_overview()`, and `rollback(receipt)`. `stage` must use `ssh_stage` or an equivalent verified transport. `activate` must use the guarded host helper with the receipt's snapshot and source-bundle paths and hashes. If owner-route verification fails after activation, `rollback` must call the guarded host helper with `--rollback-candidate-sha256 <snapshot-sha256> --rollback-source-manifest-sha256 <bundle-manifest-sha256>`. The helper rejects a stale rollback request and restores the previous host pin, links, service, and private source bundle. An interrupted local coordinator invocation remains pending; rerunning verifies input and repeats safe stages. Failures record `remote.failed` and keep the local bundle for retry. An unchanged active binding is skipped only after another owner-route check.

The caller must attest publisher requests have stopped before a route probe. A reachable VPS route leaves VPN state alone. If blocked, the caller must inject a verified network control and private restoration journal; the existing `macos_nordvpn.deployment_route` boundary records and restores exact prior state. There is no verified unattended NordVPN 10.6.0 control or live SSH/activation adapter yet, so this coordinator has no production command. Tests run a disposable SSH stand-in, fake host activation/owner response, and fake VPN control. Do not use this contract as evidence that the live owner route has been updated.
