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
JAR dependencies. It takes a private database backup, applies all ten checksummed
migrations without seeding data, switches the release, verifies readiness, provisions
the tunnel/DNS idempotently, publishes the Worker and verifies the custom domain.
A readiness failure restores the previous app symlink when one exists. Migrations
remain forward-applied; inspect the backup before any database rollback. No GitHub
push automatically deploys this project.

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
