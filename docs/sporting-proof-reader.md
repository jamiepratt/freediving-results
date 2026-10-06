# Private exact sporting proof capability

The owner sporting workspace reads current source authority and canonical relationship
state through an independent `sporting_proof_read` PostgreSQL login. The source
database is explicit, currently `freediving_release_20260924_11`; the relationship
database is `freediving_canonical`. The reader binds both readbacks, retained source
coordinates and runtime hashes. A missing import remains a missing mapping.

The source grant is exactly SELECT on `extractions`, `observations`,
`extraction_reviews`, `pdf_extraction_reviews`, `review_proposals`, `review_decisions`,
`publication_decisions`, `publication_policy_events`, `revision_proposals`,
`revision_decisions` and `event_selections`. Canonical grants are exactly SELECT on
`extractions`, `observations`, `canonical_attempt_evidence`,
`canonical_attempt_events` and `canonical_attempt_state`. Existing public and
canonical status roles retain their existing grants. Role inheritance, elevated
role attributes, object ownership, sequence privileges, schema creation, extra
column/table privileges, future default grants and executable security-definer
functions cause verification to fail. The role defaults to read-only transactions.

The config lives at
`/var/lib/freediving-owner-evidence/sporting-proof-reader/config.json`, root owned,
0640 in a 0750 directory for the private service group. Its six fields are
`jdbc_url`, `database`, `canonical_jdbc_url`, `canonical_database`, `runtime_path`
and `runtime_manifest_sha256`. Resolved database credentials stay in this intended
host config and process memory. Public service credentials and grants are unchanged.

The proof transport uses one persistent 192 MiB JVM, one active request and two
waiting requests. It shares the request's existing 12 second deadline. The service
retains TasksMax 64 and MemoryMax 1G. A failed or changed proof produces denial;
source diagnostics and typed relationship staging never submit sporting approvals.

## Guarded host checkpoint

Use an exact clean integrated commit for the code archive and proof runtime. The
normal code archive includes both private proof modules and the host preparation
helpers. Packaging makes no source acquisition or provider call:

```sh
python3 deploy/private_owner_preflight.py --candidate "$candidate" --prepare \
  --confirm "$candidate" --output "$private_dir/owner-code.tar"
python3 deploy/sporting_proof_runtime.py --candidate "$candidate" \
  --output "$private_dir/proof-runtime"
```

Transfer the private archive and runtime through the existing verified SSH archive
path. On the host, stage the independently pinned runtime before capturing a fresh
comparison guard. `$runtime_sha` is the SHA-256 of its `manifest.json`:

```sh
python3 deploy/sporting_proof_runtime.py --stage --runtime "$uploaded_runtime" \
  --runtime-manifest-sha256 "$runtime_sha"
python3 deploy/comparison_activate.py capture --public-database freediving_release_20260924_11
```

Save the guard in a root-only file. Provision the new role once using the staged
runtime path, exact code manifest and exact fresh guard. The command prints only
non-secret effects. Existing matching capabilities are verified without rotation;
a conflicting role, config, grant or pin refuses the checkpoint.

```sh
python3 deploy/provision_sporting_proof_reader.py --execute \
  --database freediving_release_20260924_11 --canonical-database freediving_canonical \
  --public-database freediving_release_20260924_11 \
  --runtime "$staged_runtime" --runtime-manifest-sha256 "$runtime_sha" \
  --app-manifest "$bundle/private-owner-manifest.json" --app-manifest-sha256 "$app_sha" \
  --guard "$guard" --guard-sha256 "$guard_sha"
```

For a later committed proof runtime, stage it first and capture a fresh guard while
the installed capability still points to its old runtime. Add `--update-runtime`
and the exact existing capability SHA to the same host checkpoint command:

```sh
python3 deploy/provision_sporting_proof_reader.py --execute --update-runtime \
  --database freediving_release_20260924_11 --canonical-database freediving_canonical \
  --public-database freediving_release_20260924_11 \
  --runtime "$staged_runtime" --runtime-manifest-sha256 "$runtime_sha" \
  --app-manifest "$bundle/private-owner-manifest.json" --app-manifest-sha256 "$app_sha" \
  --config-sha256 "$existing_proof_config_sha" --guard "$guard" --guard-sha256 "$guard_sha"
```

This verifies exact existing grants before and after the update and changes only
`runtime_path` and `runtime_manifest_sha256`. Credentials, database targets,
source data and authority history remain unchanged. A stale guard/config pin
refuses the update; a change observed after swapping restores only this exact
derived config write. A concurrent newer config is preserved. Capture a new guard
before private app activation.

Capture a new guard after this capability exists. Attach the independent typed
relationship ledger using the exact current signer provision/config pins and code
archive. This initializes empty immutable schema at
`/var/lib/freediving-owner-evidence/sporting-bridge/ledger/relationships.sqlite`.
It uses the existing private writable ledger directory, submits no review, and
preserves every sporting authority event and signer byte. An already attached
ledger is verified without resetting history.

```sh
python3 deploy/provision_sporting_authority.py attach-relationships --execute \
  --public-database freediving_release_20260924_11 \
  --bundle "$bundle" --bundle-manifest-sha256 "$app_sha" \
  --provision-sha256 "$signer_receipt_sha" --owner-config-sha256 "$sporting_config_sha" \
  --guard "$guard" --guard-sha256 "$guard_sha"
```

Capture a fresh guard after ledger preparation. Activate the private code with the
existing comparison config/packet/runtime, passing the separate proof capability
explicitly:

```sh
python3 deploy/comparison_activate.py activate \
  --public-database freediving_release_20260924_11 \
  --bundle "$bundle" --bundle-manifest-sha256 "$app_sha" \
  --config "$existing_comparison_config" --config-sha256 "$comparison_config_sha" \
  --proof-config /var/lib/freediving-owner-evidence/sporting-proof-reader/config.json \
  --guard "$guard"
```

Activation verifies the proof runtime matches the code candidate, runs the proof
reader as the exact service UID/GID and rechecks current guards before publication.
When the proof capability is enabled, authenticated health reads the sporting
review first and then the existing inspector to warm their read runtimes before
reading
`/owner-evidence/api/sporting-authority/proofs?limit=1`. The page must report the
complete 276-version inventory, 138 source positions, current paired canonical
bindings and the exact installed capability hash. Explicit unmapped diagnostics
are accepted. An unavailable review, 503, missing capability or invalid binding
fails activation and invokes guarded rollback. Only HTTP 503 with exactly
`Retry-After: 1` permits up to three attempts with one second between attempts.
Each request retains its existing 12 second service budget and 14 second client
timeout; response validation stays strict. Health submits no action and never
persists or logs review CSRF tokens.
Rollback restores derived app/environment/comparison files only. New sporting or
relationship history, changed signer/proof capability/runtime/grants, or another
deployment causes rollback refusal. PostgreSQL, frozen SQLite and authority
ledgers are never restored. Preserve partial capability setup on interruption and
inspect fresh pins before continuation.

Validate the owner surface at
[the sporting workspace](https://poc.alphacompose.com/owner-evidence/sporting) and
verify the current custom-domain public comparison remains unchanged while real
authority is absent. Full source/public eligibility requirements remain tracked in
[issue 194](https://github.com/jamiepratt/freediving-results/issues/194).

## Exact source accuracy capability

Source accuracy acceptance/revocation uses the existing append-only
`extraction_reviews` and `pdf_extraction_reviews` receipts. Its separate
`sporting_source_review` login has the same eleven source table SELECT grants and
INSERT only on these two receipt tables. It has no canonical database grants,
source observation/extraction writes, publication writes, memberships, owned
objects, schema CREATE, sequence privileges or executable SECURITY DEFINER
functions. Existing public, status and paired proof reader grants stay exact.

The root-owned `source-review/config.json` contains only `jdbc_url`, `database`,
`runtime_path` and `runtime_manifest_sha256`, mode 0640, service group
`freediving-evidence` (983 on the current host), parent mode 0750. Provisioning
requires explicit execution, an exact clean candidate, matching app/runtime pins
and a fresh full authority guard. It creates no receipt. Matching existing
capabilities are verified without password rotation; partial/conflicting setup
refuses. The source review operation shares the persistent proof JVM and existing
12 second request deadline, so it adds no JVM. Only the authenticated owner can
submit an exact-version action through the CSRF-protected gateway; `current` and
unknown sporting actions have no POST route.

For the existing B34 installation, prepare/upload the committed code archive and
proof runtime using the commands above. On the host, use the uploaded archive's
helpers for this sequential checkpoint. `$bundle`, `$uploaded_runtime`, `$app_sha`
and `$runtime_sha` are exact staged input paths/pins; `$existing_proof_config_sha`
is the independently verified installed proof config SHA. Keep each guard in a
root-only file and calculate its SHA after capture:

```sh
python3 deploy/sporting_proof_runtime.py --stage --runtime "$uploaded_runtime" \
  --runtime-manifest-sha256 "$runtime_sha"
python3 deploy/comparison_activate.py capture --public-database freediving_release_20260924_11 > "$guard"
sha256sum "$guard"
python3 deploy/provision_sporting_proof_reader.py --execute --update-runtime \
  --database freediving_release_20260924_11 --canonical-database freediving_canonical \
  --public-database freediving_release_20260924_11 \
  --runtime "$staged_runtime" --runtime-manifest-sha256 "$runtime_sha" \
  --app-manifest "$bundle/private-owner-manifest.json" --app-manifest-sha256 "$app_sha" \
  --config-sha256 "$existing_proof_config_sha" --guard "$guard" --guard-sha256 "$guard_sha"
python3 deploy/comparison_activate.py capture --public-database freediving_release_20260924_11 > "$guard"
sha256sum "$guard"
# Stage 1: compatible application and proof runtime, writer disabled.
python3 deploy/comparison_activate.py activate \
  --public-database freediving_release_20260924_11 \
  --bundle "$bundle" --bundle-manifest-sha256 "$app_sha" \
  --config "$existing_comparison_config" --config-sha256 "$comparison_config_sha" \
  --proof-config /var/lib/freediving-owner-evidence/sporting-proof-reader/config.json \
  --guard "$guard"
# Capture the active compatible application's guard before creating the role.
python3 deploy/comparison_activate.py capture --public-database freediving_release_20260924_11 > "$guard"
sha256sum "$guard"
# Stage 2: provision capability without receipts, then activate the SAME app.
python3 deploy/provision_source_review.py --execute \
  --database freediving_release_20260924_11 --public-database freediving_release_20260924_11 \
  --runtime "$staged_runtime" --runtime-manifest-sha256 "$runtime_sha" \
  --app-manifest "$bundle/private-owner-manifest.json" --app-manifest-sha256 "$app_sha" \
  --guard "$guard" --guard-sha256 "$guard_sha"
python3 deploy/provision_source_review.py --verify
python3 deploy/comparison_activate.py capture --public-database freediving_release_20260924_11 > "$guard"
sha256sum "$guard"
python3 deploy/comparison_activate.py activate \
  --public-database freediving_release_20260924_11 \
  --bundle "$bundle" --bundle-manifest-sha256 "$app_sha" \
  --config "$existing_comparison_config" --config-sha256 "$comparison_config_sha" \
  --proof-config /var/lib/freediving-owner-evidence/sporting-proof-reader/config.json \
  --source-review-config /var/lib/freediving-owner-evidence/source-review/config.json \
  --guard "$guard"
```

Recalculate `$guard_sha` from each newly captured guard before provisioning.
Stage 1 must complete with no `OWNER_EVIDENCE_SOURCE_REVIEW_CONFIG` in either
environment. Use the same exact `$bundle`, `$app_sha` and proof runtime in stage 2.
The first guard has `protected.source_review: null`. After provisioning, the next
guard pins reviewer config, exact effective grants and shared runtime. Activation
checks its database/runtime match the proof reader and candidate, checks mode and
service group readability, then atomically adds
`OWNER_EVIDENCE_SOURCE_REVIEW_CONFIG` to both private environments. These checks
perform no receipt INSERT probe. Rollback records pin this capability and every
current PostgreSQL authority table in both paired databases. Changed grants,
config/runtime or any table digest refuses rollback before restoring derived
files. The retained current proof configuration is also read through the
checkpoint's `before.app` as the service identity before any swap. An incompatible
B34 application with the new runtime refuses rollback before replacing files.

The final stage 2 rollback disables the reviewer environment while retaining the
compatible application/runtime and the new role/config/data. It creates no
receipt and restores no database or authority ledger. Invoke only the final
stage 2 checkpoint after a fresh authority/table guard:

```sh
python3 deploy/comparison_activate.py rollback \
  --public-database freediving_release_20260924_11
```

Do not restore the earlier B34 app or old proof runtime over current capabilities.
Stage 1 failures that cannot return to a compatible application retain their
checkpoint for forward repair. Never use a stale checkpoint over newer receipts.

Deploy the gateway route change using the existing profile and custom domain:

```sh
wrangler --profile alphacompose --config deploy/wrangler.jsonc deploy
```

This route-only deployment needs no database migration, public app replacement or
secret rotation. Validate authenticated owner proof forms, exact source inspector
and current publication diagnostics on
[the sporting workspace](https://poc.alphacompose.com/owner-evidence/sporting).
Keep all 67 guarded PostgreSQL tables and all 86 existing public responses identical
to the pre-activation manifest. Health and inspection are reads only; do not submit
accept/revoke, sporting Approve/Reverse or relationship decisions during activation.


## Independently cited sporting meanings

The owner sporting proof view reads a separately pinned private
`OWNER_EVIDENCE_SPORTING_RULES_CONFIG`. Its catalog binds immutable rule bytes,
issuer, edition, effective dates, section/page and named claim to each exact
source/artifact/parser/reference, coordinates, view, event date, scope and scoring
policy. Missing or changed pins withhold concrete interpretations. Current
signed authority rechecks these inputs; old publication, detail and peer links
cannot retain ranks after rule withdrawal. The catalog grants no human review,
distinctness, publisher finality or public selection.

The bounded June 2026 DNF evidence uses the issuer-linked
[AIDA / World Apnea edition 17.8](https://drive.google.com/file/d/100SY8IWiAsTeYyljLLZO5YdvCVHB6Sih/view),
SHA-256 `6dd75bf0b74a4f7434e2b7660780728c11dc8de6800892f1be94e50b81c5f4f8`.
Section 2.5.2 states applicability from 25 May 2026. AP is announced performance;
RP is achieved performance, subject to the discipline's measurement rules.
Dynamic scoring is 0.5 point per metre with the prescribed rounding and penalty
rules. RED denotes disqualification and zero points. Positive printed RP beside
zero Points therefore remains achieved source evidence, never a valid final
ranking distance. Immediate cards can change; the rulebook's review process does
not establish that the retained result page is a later final publication.

The private catalog accounts for 103 AIDA positions / 206 versions and 35 CMAS
positions / 70 versions. Distinct attempts remain unknown. It preserves raw AP,
RP, Points, card, final, rank, penalty and status text. Missing penalties remain
unknown. The two AIDA extractions share one source hash and parser version; they
are extraction versions, not publisher revisions. CMAS's
[official pool rule download](https://www.cmas.org/document/freediving/freediving-regulations/pool-competitions/2026,-cmas-freediving-international-rules-pool-competitions-en.html)
was inaccessible during this bounded pass. Its page title is not a retained rule
edition. CMAS printed final/rank values and DQ markers remain source evidence;
post-penalty meaning and event applicability need supported authority.

Concrete sporting facts require exact applicable rule/source meanings. Positive
publisher and event claims require independently bound source authority; a
scoring formula cannot provide it. Source approval, cohort selection and
publication remain separate authenticated actions. The private view shows
citations, scope, supported interpretation, unknowns and conflicts alongside
unchecked controls. Existing public results stay under their current authority.
Unresolved acceptance remains [issue #194](https://github.com/jamiepratt/freediving-results/issues/194).
