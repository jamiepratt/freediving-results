# Local evidence run

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
