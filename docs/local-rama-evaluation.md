# Local Agent-o-rama evaluation

The optional `freediving.evaluation-rama` module registers the reviewed shadow evaluator in Agent-o-rama. It accepts a private export receipt ID, resolves that receipt against the live PostgreSQL database, and returns opaque evaluation references. It has no review or publication authority. Pilot acceptance and production work remain in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Runtime and local checks

The `:rama` alias pins Agent-o-rama 0.10.0, Rama 1.9.0 and Clojure 1.12.4. Use Java 21. Dependencies resolve from the Red Planet Labs public Maven repository and the ordinary Clojure repositories. Ordinary archive and evaluation commands do not load these dependencies. IPC needs no separately installed Rama cluster.

```sh
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH \
  scripts/test-postgres.sh rama:test-rama
```

The test helper creates and removes its own synthetic PostgreSQL cluster. The optional suite uses real Rama IPC and loopback HTTP fixtures. Synthetic pair decisions are test data, never genuine owner reviews. No provider credentials or live athlete data are needed.

## Worker configuration and invocation

Create an export using the [reviewed-label commands](local-reviewed-labels.md). Keep its immutable receipt in a private directory. The graph input is the returned 64-character `:receipt-id`, not a path or dataset.

Load `freediving.evaluation-rama` with `clojure -M:rama`. Its constructor takes a qualified symbol naming a trusted zero-argument configuration function on the worker classpath:

```clojure
(require '[freediving.evaluation-rama :as evaluation-rama])
(evaluation-rama/evaluation-module 'your.private.configuration/load-evaluation)
```

That function must return this map, resolving private values inside the worker:

```clojure
{:db-url database-url
 :receipt-directory private-export-directory
 :private-root persistent-private-evaluation-store
 :configs comparator-configurations
 :runtime {:providers runtime-provider-credentials}}
```

The identifiers above represent values supplied by the private loader. They are not runnable configuration or credentials. Use the existing [provider configuration contract](local-shadow-evaluation.md); credentials remain separate from comparator configuration and immutable receipts. Do not capture resolved credentials in a module definition or put them in graph input. The loader symbol is trusted code, never visitor input.

The agent is `evaluate`; obtain the fully qualified module name with `com.rpl.rama/get-module-name`. After launching the module in a local Rama IPC instance, obtain an Agent-o-rama client and call `(evaluation-rama/invoke! client receipt-id)`. This wrapper rejects non-hash input before Rama can record it. Calling Agent-o-rama directly bypasses that protection: node validation cannot erase input already recorded by the framework.

The agent object builder resolves configuration at worker initialization with automatic object tracing disabled. The optional alias also disables raw framework logging. Private dataset bytes, database connections and provider credentials stay behind that object. Initialization and evaluation failures omit the original exception and its cause. Evaluation failures return the fixed blocked reason `:evaluation-unavailable`; an export without eligible labels returns `:no-eligible-reviewed-labels` before any provider dispatch.

## Verification and persistence

`freediving.evaluation-labels/resolve-receipt!` requires a lowercase 64-character hash, a regular 0600 receipt file and a 0700 export directory. It rejects symlinks, oversized or multiple EDN values, mismatched content identity, and stale database snapshots. It returns the private receipt only after authoritative verification. The evaluator checks the database again before execution or replay and after provider work.

Successful graph results contain status and opaque run, verified-report, report and export references. Inspect reports privately with `freediving.evaluation/inspect-verified-run`; current database verification still applies. Do not display historical receipts as current verified metrics after review changes.

Keep the same private store across retries on the same trusted host. Completed provider attempts and immutable receipts replay without redispatch. A failed framework result delivery can retry the node against that store. The evaluator's interrupted-attempt and bounded-retry rules still apply: a provider timeout can leave an unknown external outcome. This is not exactly-once provider execution or billing, distributed storage, power-loss durability, or a production deployment.

The local database uses the documented trusted-machine role boundary. Export hashes authenticate content identity; they do not prove human review. This module covers evaluation only. The separate [local import graph](local-rama-pipeline.md) coordinates archive registration, extraction, ingestion and private candidate readiness through their existing APIs. Neither module establishes an acquisition-to-publication pipeline. Genuine labels, owner extraction validation, additional source collection, authorized live provider measurements and production acceptance remain separate requirements.

The trusted loader optionally returns `:enriched-dataset` alongside its existing
configuration. The worker routes this through `run-enriched-verified!`; Rama
still receives only the opaque original receipt reference and safe hash results.
Enriched source inputs and credentials remain in the worker/private storage.
Native Jev batch size and optional companion assessments live in `:configs`.
