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
