# Public pilot deployment

Public URL: https://poc.alphacompose.com

Cloudflare Worker `freediving-results-poc` forwards only public routes through an
outbound Cloudflare Tunnel to the `bridge-vps` host. The application and PostgreSQL
17 bind loopback. Existing firewall rules and private Rama services are unchanged.
The gateway authenticates with a 256-bit secret, strips arbitrary forwarded
headers, bounds correction bodies, and passes Cloudflare's visitor IP for the
persistent correction rate limit. The origin rejects requests without this secret.
Host and browser Origin checks remain mandatory. Responses are not cached.

The initial production database is a new, empty real corpus. No synthetic records,
private source archives, reviewer approvals or extraction validations are seeded.
Owner review and ingestion roles have NOLOGIN until a separately configured private
workflow is needed. The public process has separate read-only projection and
correction-submission credentials, verified at startup. The owner UI is not public.
Remaining corpus and owner-review acceptance work is tracked in
[issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Deployment

Prerequisites: Java 17+ and Clojure CLI locally, SSH alias `bridge-vps` with sudo,
Wrangler `alphacompose` profile, and the existing `alphacompose-dns` Keychain token.
The scripts target Ubuntu 24.04, account `d55b062637980b94f707f6fb05281a88` and the
existing alphacompose.com zone. Do not use them for another host/account unchanged.

First host setup only:

```sh
ssh bridge-vps 'sudo -n bash -s' < deploy/install-packages.sh
```

Normal release, from a clean committed checkout:

```sh
bash deploy/release.sh
```

The normal workflow packages only source, resources, deployment scripts and pinned
JAR dependencies. On an existing host it uses the guarded migration checkpoint below:
exact checksum preflight, fresh root-private backup and disposable restore drill,
then all 21 checksummed migrations without seeding data. A first host install creates
an empty database and uses the bootstrap backup/migration path. It switches the
release, verifies readiness, provisions the tunnel/DNS idempotently, publishes the
Worker and verifies the custom domain.
A readiness failure restores the previous app symlink when one exists. Migrations
remain forward-applied; inspect the backup before any database rollback. No GitHub
push automatically deploys this project.

Migrations 12 and 13 add private JSON and PDF extraction reviews. The normal
release installs them without accepting an extraction, validating publication,
selecting an event or changing the active publication policy.

Migration 19 adds a private, append-only store for verified batch position
evidence. The normal release installs its schema and restricted ingestion grant.
It does not import retained evidence or add records to the legacy observation
tables. Import is a separate local operation against a verified archive using
`freediving.batch-evidence-db/import!`; see [private batch evidence import](batch-evidence-import.md).

Migration 20 adds private immutable tables for source-derived identity observations
and their snapshot reference. The normal release installs the schema and restricted
role grants without registering a snapshot or importing observations. Source
registration is a separate private operation; the migration alone changes no
identity decision or public result.

Migration 21 updates the identity event role check to allow a mechanically proven
automatic reversal. It changes no existing identity decision or public result.

For an already populated `freediving_canonical` database, use the dedicated
`deploy/migrate_canonical_21.py` helper from an exact staged release, as root.
It requires the existing private database marker and ACL, exact public and
canonical migration checksums, expected canonical revision and row counts, and
unchanged owner/public checkpoints. It writes a root-private custom dump,
restores it into a disposable database, and checks the retained data digest
before applying migration 21 in one transaction as `freediving_migrator`.
Retry at version 21 verifies the same state without applying SQL again. The
existing `migrate_only.py` targets the public database; `provision_canonical.py`
requires an empty canonical target. The attempt revision is from
`freediving.canonical_attempt_state` and can be 0 while identity events are 211.
After installing the exact merged release and rechecking current counts, the host
invocation for the copied 1-20, 0-attempt, 211-event, 281-source, 1-view state is:

```sh
revision=<exact-merged-40-hex-commit>
release="/opt/freediving/releases/$revision"
sudo -n python3 "$release/deploy/migrate_canonical_21.py" \
  --release "$release" --expected-revision "$revision" \
  --expected-public-database freediving_release_20260924_11 \
  --expected-attempt-revision 0 --expected-events 211 \
  --expected-source-rows 281 --expected-identity-rows 1
```

Replace the expected values only from a fresh read-only check. The helper does
not activate an owner snapshot or install the release. Preserve its printed
backup path and SHA-256 for a guarded restore if any postcheck fails.

### Migration-only operator checkpoint

When the host is at exact migrations 1-7 or 1-20, run this from a clean checkout of
the intended merged commit with the existing `bridge-vps` SSH alias:

```sh
bash deploy/migrate_only.sh
```

This stages a reproducible source/resources/JAR archive under
`/opt/freediving/releases/<commit>` and verifies its SHA-256 on the host. It does
not change `/opt/freediving/current`, restart a service, or call Cloudflare. The
root-only host helper checks all existing migration checksums, database owner and
restricted roles, public URL/config agreement, public table counts, and the
private owner snapshot/status bytes. It takes a root-private complete custom dump
under `/var/backups/freediving`, verifies the archive listing, restores it into a
new disposable database, compares schema, public row counts and (when present)
source identity row counts, then drops only that disposable database. Only then
does it run the existing checksummed
`freediving.deployment` migration main and verify exact migrations 1-21, unchanged
source identity counts (including existing private rows), unchanged public counts,
active public service/site, and unchanged public app and owner snapshot/status
pointers and bytes. An exact 1-21 retry skips the migration and repeats verification.
Other version sets, altered
checksums and mismatched configuration are refused.

The same guard runs before normal activation on an existing host. It refuses the
release if the active public site or private owner checkpoint is absent or changes.
The helper prints the retained dump path as the rollback checkpoint before any
migration. Save that path and the commit SHA in the private operator record. A
failed migration may leave a partially forward-migrated schema because the
existing migration main commits in modules. Inspect the retained dump and
database before retrying; do not restore over new submissions. A public write
during the dump can make the disposable restore count check differ from the live
database; this fails closed and requires a fresh checkpoint. This path does not
authorize public policy activation, corpus import, or release publication.

## HTML publication policy checkpoint

Normal deployment applies migration 9 but leaves the active publication policy unchanged. Policy-1 PDF validations and projections remain valid. HTML publication requires policy 2; deploying this code does not activate it or approve any observation.

Activation is a separate database-owner operation. Before it, retain a verified private database backup, identify the affected public rows, obtain authorization for their temporary withdrawal, and prepare genuine source review under policy 2. Activation immediately hides policy-1 rows. Each row needs a fresh explicit validation under policy 2 and a projection refresh; existing reviews are preserved as history, not copied into new validations.

From the matching repository checkout with Clojure CLI installed, with `FREEDIVING_MIGRATION_URL` supplied privately for the intended database, the manual helper is:

```sh
scripts/activate-html-publication.sh hide-existing-public-results "Authorized reason for policy transition"
```

The packaged VPS release contains the same guarded API. From that release directory, with `FREEDIVING_DATABASE_URL` supplied privately using the database-owner migration capability, invoke it without requiring Clojure CLI:

```sh
java -cp 'src:resources:lib/*' clojure.main -m freediving.public-results activate-html-policy hide-existing-public-results "Authorized reason for policy transition"
```

The guard verifies migration 9's checksum and active policy 1 before appending policy 2. It does not deploy, seed, validate or refresh records. Never put credentials in the reason. No real activation or deployment was performed while implementing this support. Policy identifiers cannot be reused; application rollback alone cannot restore policy-1 visibility. Any later rollback needs an explicitly supported fresh policy and fresh validations.

The existing custom domain remains `poc.alphacompose.com`. Genuine event replacement and corpus approval remain gates in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8).

## Event selection checkpoint

Migration 10 installs append-only event selections and public visibility checks. Normal deployment applies it without choosing an event or activating a publication policy. Before a real cutover, retain the normal database backup, reconcile the exact scoped source inventory and gaps, and obtain current extraction validations and relationship decisions. The first cutover also requires an explicit retained baseline for eligible observations outside that scope. See [the private selection contract](event-selections.md).

Selection is an explicit reviewer action, separate from deployment. Its transaction refreshes public projections atomically. Rollback appends a reviewed selection only while the historical approvals and relationships remain valid under the current policy. Reverting application code or restoring old validation IDs cannot authorize publication. No real selection, activation or deployment occurred during synthetic verification.

## Database preparation

Before normal release migration and application activation, `deploy/prepare_database.py` reads the root-private
`migration.env` and `public.env` as data, without sourcing shell code. The migration,
public-read and correction-submit URLs must name the same loopback host, port and
database, using their separate bootstrap roles. The backup uses that database and
port through the local PostgreSQL socket as `postgres`; ambient `PG*` variables
cannot redirect it. A malformed URL or inconsistent configuration refuses the
release before creating a backup or running migrations. Failed dumps are removed;
a migration failure retains the completed dump and prevents activation.

The supported configuration is the single-quoted assignment format generated by
`bootstrap.py`: host `127.0.0.1`, port 1-65535, an ASCII database name starting with
a letter or underscore (up to 63 letters, digits or underscores), and exactly the
`user`, `password`, `connectTimeout`, and `socketTimeout` query options. Roles are
`freediving_migrator`, `reviews_public`, and `corrections_submit`. Passwords use
1-256 unreserved ASCII characters; timeouts are positive integers up to 999.
Duplicate keys, URL escapes, extra options and shell commands are refused.
Changing the selected database requires updating all three URLs consistently.
Before changing them, retain the previous configuration root-private, a completed
dump of the selected database, and the previous release. Record which database the
dump contains; inspect that checkpoint before any database recovery. Preserve the
old database and existing snapshots. An app symlink rollback does not restore the
database or configuration, and migrations remain forward-applied.
The helper suppresses child output to keep connection credentials out of logs.

Run focused regressions with:

```sh
python3 -m unittest discover -s deploy -p test_prepare_database.py
```

For isolated restore drills, the helper accepts `--config-dir`, `--backup-dir`,
and `--backup-only`. The normal activation uses fixed production defaults and
performs migration after the backup. Keep drill configuration and dumps private.

Credentials are generated on the VPS in `/etc/freediving` (root-only), never in Git.
Worker secret synchronization uses SSH and stdin. Public service memory is capped
at 640 MiB, heap at 384 MiB, CPU at one core; tunnel memory is capped at 192 MiB.
Public services restart after failure and reboot.

## Operations and rollback

### Private canonical status reader

The owner origin accepts `OWNER_EVIDENCE_CANONICAL_STATUS_CONFIG` only at
`/var/lib/freediving-owner-evidence/canonical-reader/config.json`. This root-owned
0640 file, readable by the `freediving-evidence` group, selects the canonical
database, frozen export files and a separately pinned JVM runtime. It contains a
dedicated database credential and stays on the host. The reader passes credentials
to the JVM through stdin and suppresses child diagnostics.

From an exact clean committed checkout, package that runtime outside the repository:

```sh
python3 deploy/canonical_status_runtime.py --candidate <commit> --output /private/canonical-runtime
```

The manifest binds nine Clojure sources and five pinned JARs. The JVM needs Java 17+;
it uses one visible processor, Serial GC and a 256 MiB heap. Activation verifies
that runtime and private code share the exact candidate commit, then verifies the
configured read capability as the `freediving-evidence` UID/GID with no supplementary
groups before changing active links. This check reads the config, frozen exports,
nested runtime sources and JARs as the service identity and suppresses child
diagnostics. Staging the runtime, frozen export and config is a separate operator
checkpoint from snapshot activation.

`deploy/provision_canonical_status_reader.py` prepares the host capability explicitly.
Its default prints effects; `--execute` creates `canonical_status_read` with only
SELECT on the nine required canonical tables and writes the private config. It
explicitly sets the reader directory to root:`freediving-evidence` 0750 and the
config and pinned export to root:`freediving-evidence` 0640, including under umask
0077. Linked inputs/ancestors and unsafe existing reader directories refuse before
role creation. It requires exact snapshot, runtime manifest, export and existing
status hashes.
Existing config or role refuses creation; verify and reuse it rather than rotating
credentials during reruns. Authorize this capability/config change with the code
and status refresh. This helper changes no canonical evidence or owner history.

Identity and same-attempt readbacks use their existing replay and projection
verifiers in read-only repeatable-read transactions. A failed scope stays unknown.
The attempt receipt additionally requires exact frozen export proposals and owner
event bindings. An identity view requiring rebuild cannot become current through
a status refresh. Roll back only derived status/code/config after checking current
owner and canonical guards; retain later human history and the read capability.

```sh
ssh bridge-vps 'sudo systemctl status freediving-public freediving-tunnel'
ssh bridge-vps 'sudo journalctl -u freediving-public -n 30 --no-pager'
python3 deploy/verify.py
```

App releases live under `/opt/freediving/releases/<commit>`. To roll back application
code, point `/opt/freediving/current` at the previous compatible release and restart
`freediving-public`. Run that release's `deploy/health.py` as root, then run the
public verification. Roll back Worker code by checking out the matching commit and
running `python3 deploy/publish-edge.py`. Do not restore a database backup over new
submissions without an explicit recovery decision.

Pre-deployment PostgreSQL custom-format dumps are in `/var/backups/freediving`
(root-only). They are local recovery copies, not an independently verified offsite
backup. Offsite backup integration and restore drills remain tracked in issue #1.
To withdraw the site, disable its Worker custom-domain route; stopping the tunnel
also makes its origin unavailable. Private data remains on the VPS/local archive.

## Owner evidence remote activation checkpoint

### Consolidated image-PDF queue

The issue #172 queue is a separate read-only file on the private origin. Rebuild
its audit against the **currently active** snapshot before staging it. Verify the
queue's audit digest, the audit's snapshot digest, all nine pinned source stages,
and the 799 candidate accounting (750 verified, 49 unresolved). The expected
queue has 468 stable entries: 49 field questions and 419 relationship candidates.
Keep the audit, queue, originals and transcripts outside Git.

First verify the exact active snapshot, installed private app version, current
owner decision revision, a current decision-store backup with isolated restore
check, Access owner policy, direct-origin denial and authenticated owner access.
The installed app must already contain `/owner-evidence/api/issue172-queue`.
The normal public release does not install this private app or queue. Stage the
matching private code through the guarded owner activation procedure below,
preserving the active snapshot and source bundle; do not change the owner
decision store. Then copy the pinned audit and queue to a root-only import
directory on the private host and run the helper there:

```sh
sudo -n python3 /var/lib/freediving-owner-evidence/import/code/deploy/owner_evidence_activate.py \
  --queue-source /var/lib/freediving-owner-evidence/import/issue172/owner-queue-v1.json \
  --expected-queue-sha256 "$QUEUE_SHA256" \
  --audit-source /var/lib/freediving-owner-evidence/import/issue172/audit-v1.json \
  --expected-audit-sha256 "$AUDIT_SHA256" \
  --queue-snapshot-sha256 "$ACTIVE_SNAPSHOT_SHA256"
```

The helper requires an exact active snapshot binding, copies the queue to
`/var/lib/freediving-owner-evidence/issue172-queue/owner-queue-v1.json`, pins its
hash in the root-only environment, restarts the private service, checks the
authenticated loopback queue route, and retains a root-private rollback
checkpoint. An unchanged rerun returns `unchanged`. Read back the custom-domain
owner page and queue API, all 468 entries by paging, decision revision and public
health. Check expired/unauthorized Access and direct-origin denial, and measure
latency with the live proposal count. If any acceptance check fails, run the
helper's `--rollback-queue-sha256 "$QUEUE_SHA256"` from the same private code
installation, then verify the prior private snapshot and decisions still read.
Do not close #172 before authenticated custom-domain readback succeeds.

### Candidate code checkpoint for private host setup

The private comparison inspector uses an independent pinned runtime, preserving
the existing canonical reader runtime and genuine owner decisions. Its release
helper is `deploy/comparison_activate.py`. The authenticated view remains at
`https://poc.alphacompose.com/owner-evidence`.

`deploy/comparison_runtime.py --repo <checkout> --output <new-private-directory>
--candidate <exact-commit> --bundle <verified-retained-bundle> --cutoff <UTC-cutoff>`
packages committed comparison code/JARs and a private retained packet. It copies
only the pinned CMAS PDF source; restricted AIDA HTML originals stay outside the
host package. Archive regular package files without directory/link members and
retain the archive/config hashes separately. Extract a pinned archive through
`deploy/private_archive_extract.py` into a new root-private incoming directory.

On the host, use these commands with the deployed public database name and exact
private paths/hashes. The staged config initially retains local package paths;
`stage` relocates only the fixed package layout and installs immutable private
packet/runtime/PDF versions as root with the evidence-service group, files 0640
and directories 0750. `stage` leaves active app/config pins unchanged.

```sh
sudo -n python3 <code>/deploy/comparison_activate.py stage \
  --public-database <public-database> --config <payload>/config.json \
  --config-sha256 <original-config-sha256>
sudo -n python3 <code>/deploy/comparison_activate.py capture \
  --public-database <public-database> > <root-private-guard.json>
sudo -n python3 <code>/deploy/comparison_activate.py activate \
  --public-database <public-database> --guard <root-private-guard.json> \
  --bundle <code> --bundle-manifest-sha256 <owner-code-manifest-sha256> \
  --config <returned-installed-config> --config-sha256 <returned-config-sha256>
```

Activation runs the actual reader as the evidence service user before any active
swap, verifies 138 source positions/276 retained versions with zero real ranks,
checks CMAS PDF access, rereads the complete live guard, and probes the
authenticated inspector after restart. The guard hashes owner history, all
canonical/public tables, existing app/config/env/unit, presentation status,
snapshot/source manifests, canonical reader/runtime/export, and public code/config.
It refuses drift. The root-private activation checkpoint supports
`comparison_activate.py rollback --public-database <public-database>` for known
derived states only, including interrupted swaps. Rollback restores private
app/env/comparison config and preserves newer human history/status/database data.
It refuses changed backups or a newer deployment. No schema migration, sporting
approval, public eligibility update, or public deployment occurs in this path.

`deploy/private_owner_preflight.py` prepares a code-only archive for a later,
separately authorized private host setup. It is independent of `deploy/release.sh`.
It reads one clean committed checkout, requires its exact HEAD SHA, and checks the
tracked origin, workspace, activation, service, SSH transfer and matching local
reconciliation/presentation helper files. The archive does not contain a complete
local Clojure runtime or source checkout; run reconciliation from the exact
candidate checkout with its dependencies.
The default invocation is read-only. It never connects to the VPS, database,
Cloudflare or NordVPN and never includes snapshots, source objects or credentials.

```sh
CANDIDATE=$(git rev-parse HEAD)
python3 deploy/private_owner_preflight.py --candidate "$CANDIDATE"
```

After reviewing that commit and obtaining separate authorization for preparation,
create a private directory outside the repository and package exactly that commit:

```sh
install -d -m 0700 /private/owner-code-checkpoint
python3 deploy/private_owner_preflight.py --candidate "$CANDIDATE" \
  --prepare --confirm "$CANDIDATE" \
  --output /private/owner-code-checkpoint/owner-code.tar
```

For approved host installation, extract each exact code/runtime tar with the
packaged `deploy/private_archive_extract.py` helper and its independently retained
archive hash. Use a new destination in an existing unlinked parent that the
service can traverse:

```sh
sudo -n python3 /exact-code/deploy/private_archive_extract.py \
  --archive /private/owner-code.tar --archive-sha256 <archive-sha256> \
  --target /opt/freediving/private-stage/code
sudo -n python3 /exact-code/deploy/private_archive_extract.py \
  --archive /private/canonical-runtime.tar --archive-sha256 <archive-sha256> \
  --target /opt/freediving/private-stage/runtime
```

The helper verifies and extracts the same open archive, refuses existing targets,
linked inputs/ancestors, traversal, non-regular members, duplicate names and
file/directory collisions. Each new directory is explicitly 0755 and each regular
file 0644 regardless of umask 0077; root execution retains root ownership. File
bytes remain pinned. This contract is for code/runtime only. It leaves the archive,
parent, credentials, private backups and derived caches at their existing modes.
Use the root/app-group 0750/0640 provisioner contract for reader secrets and frozen
exports, then run activation's service-identity preflight before changing links.

The archive is mode 0600, includes per-file SHA-256 values and cannot overwrite an
existing archive. Its `host_ready: false` output is intentional. Archive creation
does not authorize installation or establish a live gate. Before a later host
action, the operator must verify and retain these root-private checkpoints:

1. The exact candidate commit and archive manifest match the chosen host code,
   `private_evidence_transfer.py`/`private_evidence_ssh.py` protocol and local
   caller. Install the complete matching code and helpers outside public releases.
2. The intended PostgreSQL database has every checksummed migration 1-21 applied,
   including 21, before any private activation. Retain a verified database backup;
   a migration gap blocks activation. The normal release applies migrations but
   does not install this private archive.
3. The root-owned 0600 `owner-evidence.env` has the gateway, snapshot pin, owner
   allowlist and separate status writer configuration needed by this candidate.
   Obtain values from the approved secret store without writing them
   into the archive, shell history, logs or Git. Check the separate Worker bindings.
4. Read-check the exact Access app and owner-only policy for
   `poc.alphacompose.com/owner-evidence*`, audience, issuer, owner allowlist and
   service clients. A missing Access read credential or mismatched policy blocks
   Worker activation. Capture current tunnel, DNS and Worker state and the prior
   private pin as rollback targets before changing them.
5. Stage a verified snapshot and matching source bundle, then use the guarded
   host helper. Run `owner_evidence_cloudflare.py` without `--activate` first.
   Only after all checks pass, separately authorize its explicit `--activate`.
   Check the existing custom domain with an owner login, cited source and PDF
   readback, an unauthenticated denial and public health. On failure follow the
   rollback sequence below; keep the prior private presentation available.

No automated workflow is wired to the repository or this checkpoint. A Git push
or merge cannot run this script or activate the private infrastructure.

Accepted workflow, 2 October 2026: verified local ingestion runs should
automatically update the private owner presentation. Failed transfer or validation
must preserve the last working presentation and a resumable local result. This
does not grant public result publication or owner review approval. The activation
helper below supplies part of this behavior. The opt-in local command now wires
the tested handoff, while private host setup and live validation remain in #64.
Automatic NordVPN switching is authorized when needed to reach the VPS, followed
by restoration of the prior connection state. The recovery and verification
boundary is recorded in [ADR 0002](adr/0002-local-ingestion-remote-presentation.md).

The [1 October 2026 activation report in #54](https://github.com/jamiepratt/freediving-results/issues/54)
records successful owner HTML/API access using the v8 snapshot after PRs #58-#60.
The instructions below describe fresh setup and guarded updates; the September
Access-token failure is historical, not a current unfulfilled prerequisite.
Recheck live configuration before changing it. New acquisition and parsing run
locally; the remote server receives verified evidence for presentation. The
repeatable handoff is tracked in [#64](https://github.com/jamiepratt/freediving-results/issues/64).

The read-only owner evidence origin and `/owner-evidence` Worker route are prepared,
but **not activated by `deploy/release.sh`**. The private snapshot remains separate
from the public PostgreSQL database, public service, release tar and Git. The
normal release reconciles the public tunnel ingress and preserves an already
activated `owner-origin.alphacompose.com` ingress; unexpected tunnel drift stops
it. Worker private bindings are secrets, which Wrangler preserves on later
[deploys](https://developers.cloudflare.com/workers/wrangler/commands/workers/).

Activation requires a Cloudflare Access self-hosted application for exactly
`poc.alphacompose.com/owner-evidence*`, one Allow policy with only the exact owner
email selector(s), its audience and issuer, and a token with `Access: Apps and
Policies Read`. The current `alphacompose` Wrangler token returned HTTP 403 for
Access application reads on 28 September 2026. An operator must supply a separate
account token with `Access: Apps and Policies Read` and configure the Access
app/policy before activation. Pass that token only on stdin with
`--access-token-stdin`; the helper continues to use the `alphacompose` Wrangler
profile for tunnel and Worker changes. Access
must protect both `/owner-evidence` and child paths. The Worker independently
checks the signed assertion, audience, issuer and owner allowlist. No private
binding is set by a normal public release.

On the VPS, first create `/etc/freediving/owner-evidence.env` as root, mode 0600,
with unquoted `KEY=VALUE` lines for:

```text
OWNER_EVIDENCE_GATEWAY_SECRET=<new random ASCII secret of at least 16 characters>
OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com
OWNER_EVIDENCE_EMAILS=<comma-separated lowercase owner email addresses>
OWNER_EVIDENCE_SNAPSHOT_SHA256=<sha256 of snapshot.sqlite>
```

For private local checkpoint status sync, add these three lines to the same root-owned file before activation:

```text
OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json
OWNER_EVIDENCE_STATUS_TOKEN=<independent random ASCII token, 24 to 256 characters>
OWNER_EVIDENCE_STATUS_CLIENT_ID=<dedicated Cloudflare Access service client ID ending in .access>
```

Set Worker secret `OWNER_EVIDENCE_STATUS_CLIENT_ID` to that exact client ID. The writer sends a signed service assertion plus the separate status token; owner browser requests cannot write. Keep the status file outside snapshot versions so guarded activation and service restart retain it. The private code bundle must include `scripts/private_presentation_status.py` alongside `scripts/owner_evidence_origin.py`.

Before enabling the status writer, create a dedicated Cloudflare Access service token and add exactly one Service Auth policy for that token to the existing owner evidence Access application. Keep the exact owner-email Allow policy. The guarded Cloudflare preflight resolves the configured status Client ID through the Access Service Tokens Read API and checks the token ID against that Service Auth policy. A read token without this permission, a missing service token, or a broader policy blocks activation. The preflight checks Worker secret names, not their hidden values; its guarded activation writes the verified Client ID binding. Keep the status token and service Client Secret outside Git and the private code archive.

For signed owner decision feed and ACK, configure a separate import client and
origin token in the same root-owned file:

```text
OWNER_EVIDENCE_IMPORT_CLIENT_ID=<dedicated Cloudflare Access service client ID ending in .access>
OWNER_EVIDENCE_IMPORT_TOKEN=<independent random ASCII token, 24 to 256 characters>
```

The import client and token must differ from the status writer's credentials.
Add a separate Service Auth policy containing only this service token to the
existing owner evidence Access application. Retain the exact owner-email Allow
and status-writer policies. The guarded Cloudflare preflight resolves both
client IDs with Access Service Tokens Read, requires these three exact policies,
and sets the Worker `OWNER_EVIDENCE_IMPORT_CLIENT_ID` binding. Feed and ACK
requests also need the import Client Secret, stored outside Git and the private
code archive. Do not use the owner browser identity for machine requests.

Do not reuse the public gateway secret. The origin reads only the pinned private
SQLite snapshot, not PostgreSQL or review credentials. Retain the verified source
snapshot and its manifest in a separate private backup before activation. Check
that `manifest.json` declares the same SHA-256 and that the code bundle and
snapshot were not copied into the public release tar. Stage the code files,
snapshot, and matching private source bundle on the VPS under root-only
temporary directories outside `/opt/freediving/releases`:

For a later candidate, keep the prior pin in this root-owned mode 0600 file.
The guarded host helper verifies the candidate snapshot and matching private
source bundle first, then atomically updates the pin during local activation.
It restores the prior pin, service files, and active links if activation or health
fails, including the prior service active and enabled state. It retains old staged snapshots. Its root-private
`/var/lib/freediving-owner-evidence/activation-checkpoint/status.json` records
`pending`, `failed`, or `active` for the local host step. On a retry, a pending
step first restores the prior local state; invalid recovery stops the private
service. This recovery runs even when candidate files are missing or corrupt.
`active` confirms only the local host step, not transfer durability,
Cloudflare activation, browser acceptance, or the full #64 handoff.
Normal `deploy/release.sh` does not run this candidate pin transaction.

```sh
# Run from the matching repository checkout with independently checked digests.
SNAPSHOT_DIR=/absolute/path/to/private/snapshot
SOURCE_BUNDLE_DIR=/absolute/path/to/private/source-bundle
SHA256=$(shasum -a 256 "$SNAPSHOT_DIR/snapshot.sqlite" | awk '{print $1}')
SOURCE_MANIFEST_SHA256=$(shasum -a 256 "$SOURCE_BUNDLE_DIR/manifest.json" | awk '{print $1}')
ssh bridge-vps 'sudo -n install -d -m 0700 /var/lib/freediving-owner-evidence/import/code /var/lib/freediving-owner-evidence/import/snapshot /var/lib/freediving-owner-evidence/import/source-bundle /var/lib/freediving-owner-evidence/import/source-bundle/objects'
tar -cf - scripts/owner_evidence_origin.py scripts/private_presentation_status.py scripts/owner_decision_store.py scripts/aida_snapshot_observations.py scripts/issue55_aida_selected_html.py scripts/cmas_microplus_snapshot_observations.py scripts/cmas_microplus_ingest.py scripts/cmas_microplus_finalize.py scripts/unified_evidence_query.py scripts/route_roster_query.py scripts/owner_source_view.py scripts/private_source_bundle.py scripts/vestico_safe_derivative.py resources/evidence_workspace.html resources/evidence_workspace.js resources/evidence_workspace.css | ssh bridge-vps 'sudo -n tar -xf - -C /var/lib/freediving-owner-evidence/import/code'
tar -C "$SNAPSHOT_DIR" -cf - manifest.json snapshot.sqlite | ssh bridge-vps 'sudo -n tar -xf - -C /var/lib/freediving-owner-evidence/import/snapshot'
COPYFILE_DISABLE=1 tar -C "$SOURCE_BUNDLE_DIR" -cf - manifest.json objects | ssh bridge-vps 'sudo -n tar -xf - -C /var/lib/freediving-owner-evidence/import/source-bundle'
tar -cf - deploy/owner_evidence_activate.py deploy/freediving-owner-evidence.service | ssh bridge-vps 'sudo -n tar -xf - -C /var/lib/freediving-owner-evidence/import/code'
ssh bridge-vps "sudo -n python3 /var/lib/freediving-owner-evidence/import/code/deploy/owner_evidence_activate.py --bundle-dir /var/lib/freediving-owner-evidence/import/code --snapshot-source /var/lib/freediving-owner-evidence/import/snapshot --expected-sha256 '$SHA256' --source-bundle /var/lib/freediving-owner-evidence/import/source-bundle --expected-source-manifest-sha256 '$SOURCE_MANIFEST_SHA256'"
```

The host helper verifies the manifest, database hash, file type and root-only
configuration before staging. It binds the source bundle to the selected snapshot,
stages both with owner-only permissions, and reads the embedded route roster.
It starts a dedicated `freediving-evidence` service
on loopback port 8081, checks both pinned responses, and restores previous
pin, links/unit, and active environment if restart or health fails. Re-running
unchanged inputs is idempotent. The
private service has no public database credentials.

From the same checkout, read-check Access policy, private origin positive and
negative responses, tunnel drift, and DNS before any Cloudflare write:

Feed the separate Access read token from a secure store into stdin. The example
uses the `Shell Access` 1Password item after `op` authentication. Do not put the
token in a command argument, shell history or a repository file.

```sh
op read 'op://Shell Access/freediving-owner-evidence-access-read/credential' | python3 deploy/owner_evidence_cloudflare.py --access-token-stdin --access-app-id "$ACCESS_APP_ID" --issuer "$ACCESS_ISSUER"
```

A 403, wrong policy, owner mismatch, origin failure, tunnel drift or DNS conflict
stops here. Once those checks pass and the public site is healthy, the explicit
activation command adds the private tunnel ingress and proxied CNAME, deploys
the prepared private-route Worker code with `wrangler --profile alphacompose`,
then sets the verified private Worker bindings in one secret bulk deployment. The
new route fails closed until those bindings are present:

```sh
op read 'op://Shell Access/freediving-owner-evidence-access-read/credential' | python3 deploy/owner_evidence_cloudflare.py --access-token-stdin --access-app-id "$ACCESS_APP_ID" --issuer "$ACCESS_ISSUER" --activate
python3 deploy/verify.py
```

Finish with an authenticated owner browser check at
`https://poc.alphacompose.com/owner-evidence`, including source/detail API and
an unauthenticated denial check. Do not declare activation complete before these
checks. The private origin URL is `https://owner-origin.alphacompose.com` only
for the Worker; direct unauthenticated requests must return 403. Keep the
pre-activation tunnel configuration, DNS state, Worker version and private
snapshot hash as a rollback receipt.

If any check fails after activation, remove `OWNER_EVIDENCE_GATEWAY_SECRET` from
the Worker first to close the route, then restore the recorded Worker version and
prior tunnel configuration/DNS as needed. Stop the private service only after the
Worker route is closed. Do not overwrite public ingress or public `GATEWAY_SECRET`.
The host helper retains prior app/snapshot versions for local service rollback.
An incomplete activation is not evidence that owner access or archive durability
has been verified; keep [issue #54](https://github.com/jamiepratt/freediving-results/issues/54)
open until remote checks and the wider UI acceptance are complete.
