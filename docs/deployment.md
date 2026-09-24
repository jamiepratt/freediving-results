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
JAR dependencies. It takes a private database backup, applies all six checksummed
migrations without seeding data, switches the release, verifies readiness, provisions
the tunnel/DNS idempotently, publishes the Worker and verifies the custom domain.
A readiness failure restores the previous app symlink when one exists. Migrations
remain forward-applied; inspect the backup before any database rollback. No GitHub
push automatically deploys this project.

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
