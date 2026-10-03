# ADR 0002: Local ingestion and automatic remote evidence presentation

Date: 2026-10-02. Status: accepted owner decisions.
Implementation: opt-in command wiring and synthetic coordinator checks exist;
live remote and VPN validation remain. Remaining work is tracked in
[issue #64](https://github.com/jamiepratt/freediving-results/issues/64).

## Context

Source access can depend on the local network route. The owner uses NordVPN
obfuscated connections and wants acquisition and parsing to run locally.
Earlier remote-worker defaults are superseded for new ingestion runs.

The existing private viewer serves a pinned SQLite snapshot and verified source
bundle through `poc.alphacompose.com/owner-evidence`. The deployment helper stages
versions, checks the private service and rolls back a failed activation. Current
deployment still involves a manual transfer/activation sequence. The October
activation history also records VPN interference with Tailscale SSH.

## Decisions

1. Run discovery, fetching and parsing locally. Keep original source bytes,
   citations, parser outputs and local checkpoints independently of remote
   presentation. The remote host serves verified evidence.
2. After local verification succeeds, automatically transfer and activate the
   snapshot and matching private source bundle on the existing owner website.
   A separate publish command is not the default. Evidence remains owner-only;
   deployment grants no identity approval or public result publication.
3. Preserve the last working remote presentation when transfer or validation
   fails. Retain local artifacts and a retry checkpoint. Local completion and
   remote presentation are distinct states; confirm the remotely served snapshot
   before reporting presentation complete.
4. Automatically switch away from NordVPN if it prevents deployment access, then
   restore the prior connection state after success or failure. Do not change
   unrelated network services. Complete or pause publisher requests before the
   transition; do not fetch sources through a temporary deployment route.
5. Record pending restoration before changing the connection so interruption
   recovery can restore it. If state cannot be determined, switching cannot be
   verified, or restoration fails, stop the affected phase with a recoverable
   checkpoint and explicit network status. Do not report a failed restoration
   as successful completion.

## Alternatives and limits

- Remote acquisition is not the default because it cannot use the owner's local
  NordVPN route. Historical remote receipts remain valid evidence of those runs.
- Requiring an explicit publish command after every verified run is rejected.
- Asking the owner to switch networks on every normal deployment is rejected;
  unresolved network failures can still require intervention.
- A connected VPN does not prove obfuscation or guarantee publisher access.
  The configured obfuscated server/category must be verified separately from
  tunnel status; retain actual acquisition outcomes and existing request pacing.
- NordVPN's [current macOS instructions](https://support.nordvpn.com/hc/en-us/articles/19615332252561-How-to-connect-and-disconnect-from-NordVPN-s-obfuscated-servers)
  select Obfuscated Servers in the app. Its Linux CLI examples are not a macOS
  automation contract. Local inspection of NordVPN 10.6.0 found no bundled CLI
  or AppleScript dictionary; macOS exposes NordVPN network services. Those
  observations do not verify an automatic disconnect/reconnect implementation
  or restoration of the exact server/category.

## Evidence and product impact

See [source acquisition](../source-acquisition.md),
[deployment](../deployment.md), [owner workspace](../owner-evidence-workspace.md),
[`owner_evidence_activate.py`](../../deploy/owner_evidence_activate.py) and
[its tests](../../deploy/test_owner_evidence_activate.py).

User-facing copy impact: the owner workspace must distinguish the active remote
snapshot/cutoff from a newly finished local run. Any displayed deployment status
must identify pending transfer, failed validation and the retained prior
presentation. Keep existing partial-coverage and unresolved-evidence labels.
This ADR changes no runtime UI; implementation and copy changes remain in #64.
