# Local shadow evaluation

This bounded local harness compares identical held-out cases through deterministic rules, a Jev HTTP adapter and an OpenAI-compatible chat adapter. Outcomes are `:match`, `:no-match`, `:abstain` or `:error`. None has review, identity merge or publication authority. The [reviewed-label export](local-reviewed-labels.md) verifies explicit pair decisions against the local database before scoring. Genuine owner labels and live provider evaluation remain in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Reproduce the synthetic run

```sh
mkdir -p data
clojure -M:shadow run data/shadow test/fixtures/shadow-evaluation.edn test/fixtures/shadow-configs.edn
clojure -M:test-shadow
```

The fixture has one development case and three held-out cases: one invented match label, one invented no-match label and one unlabeled case. Its hashes and identifiers are invented references, not archived athlete evidence. The sample configuration uses rules and two explicitly named unavailable-provider stubs. Stubs abstain; they are not model predictions. The tests additionally run both real HTTP adapter implementations against local Jev-shaped and LLM-shaped servers, including a three-comparator comparison and replay with no extra HTTP requests.

The command prints only run/input/report hashes. Inspect private contents with `(freediving.evaluation/inspect-run root run-id)`. Do not print that map to shared logs. Repeating unchanged inputs verifies and returns the stored receipt. The parent directory must exist; use an ignored private path with no symlink ancestors. No database or running service is required for the sample command.

The CLI only permits rules, stubs and HTTP at numeric `127.0.0.1`. It sends the literal nonsecret bearer `local-fixture-only` to loopback stand-ins. It never loads real credentials. The Clojure API supports explicitly configured HTTPS endpoints with separate runtime credentials scoped to each configuration ID: `{:providers {"jev" {:bearer-token secret}}}`. It never shares a default bearer across providers. No live call is part of this verification. Keep secrets out of datasets/configuration. Do not put athlete data or private traces in Git.

## Dataset and labels

`freediving.evaluation-data/validate-dataset!` accepts schema version 1. See the committed fixture for the full shape. Each case has a stable case ID, explicit `:development` or `:held-out` split, provider `:input`, exact evidence references, source-family groups, person groups and a label or nil. Evidence references include source and extraction SHA-256, observation and evidence IDs, page and inclusive line range. The dataset records rubric version and the author, method and limitations of grouping.

Synthetic labels require `:provenance :synthetic` and a fixture ID. File labels with `:provenance :owner` require review ID, reviewer, ISO timestamp `:reviewed-at`, `:review-artifact-sha256`, and reviewed evidence IDs belonging to the case. These remain assertions and enter a separate asserted stratum. File flags and self-consistent hashes cannot authenticate them. Only the database-verified export path can produce verified owner metrics. Rejected proposals, retrieval outcomes and matching source names are never automatically converted into labels.

Cases sharing source bytes, extraction bytes, exact evidence, observations, declared source families or person groups cannot cross splits. Rejecting every cross-split edge also prevents transitive grouping leakage. Group different-byte copies/revisions and every repeated person manually when assembling the dataset. Unknown identities or undiscovered copies cannot be ruled out by validation. Shared documents can force large groups; do not weaken grouping to obtain a convenient holdout. Keep development cases outside the scored set and do not tune prompts on held-out labels.

Only `:input` goes to providers, never the separate label/split/provenance fields. The dataset author must keep labels and review answers out of `:input` itself. Rules inspect `:left`/`:right` authority and subject ID. They decide only under an explicit `:identifier-policy :unique-person-id-v1` assertion that the authority assigns unique person IDs; otherwise they abstain. Source names and representation codes do not meet that contract. No existing federation field is asserted to meet it.

## Provider contracts

The adapter implements the official [TypeSafe API](https://docs.typesafe.ai/api): bearer-authenticated `POST /v1/systemone`, explicit model, state and a named Choice question. It checks the returned choice, confidence and full probability distribution. Its [confidence](https://docs.typesafe.ai/confidence) is a statistic of that distribution, not evidence of calibrated accuracy for freediving. No threshold enables action.

The LLM adapter implements [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat), nonstreaming JSON-object output with an explicit outcome. It requires a completed response and validates that outcome. Supply the complete endpoint and explicit model in each configuration. Use a concrete model version when available; aliases are not pinned. Both requested model and returned model are retained, with missing returned version represented as nil. Provider availability and model access are not established by local fixtures.

`prepare-request [config case]` builds a secret-free exact JSON body and records adapter version. `execute! [prepared runtime]` performs one attempt. Response bytes and the entire response deadline are bounded; redirects are refused. Successful raw responses stay private. Errors retain sanitized categories and HTTP status without raw error messages/bodies. Timeout or transport failure can leave external execution unknown. Token usage is separate from money: adapters report cost unknown because these contracts do not provide actual metered charges. Local fixture costs are also unknown, not invented zero bills.

Runtime bearer values never enter run identity. Set a nonsecret `:scope-id` when account or route meaning changes, even if endpoint/model stay the same. Reusing a store/configuration across such a change without changing scope would replay the old result. Credential rotation alone need not change scope.

## Persistence and metrics

Runs accept at most ten configurations. Preflight limits planned response bytes plus per-attempt overhead to 32 MiB, and the canonical input/request identity to 32 MiB, before storage or dispatch. Copies, EDN encoding and retained prior runs add disk/memory overhead; these are admission bounds, not an operating-system memory cap. Response limits, timeout, retry count and scope belong to immutable configuration.

`freediving.evaluation/run!` evaluates only held-out cases for every configuration. Content identities include the complete dataset, normalized prepared requests/configuration, harness and adapter versions. It atomically writes private content-addressed inputs, requests, start/completion attempts and report records under a cooperating-process lock. Directories use 0700 and files 0600. Replay/inspection verifies referenced objects, including request and attempt traces. The owner and parent directory are trusted; hashes are not encryption.

Every attempt has a durable start before dispatch. A process killed after dispatch but before completion publication leaves an unknown external outcome. Restart records that uncertainty and does not resend that unfinished attempt. Completed attempts replay without new calls. Retryable errors may retry within the configured 1-3 attempt limit and bounded exponential backoff. A provider may have acted before timeout; retries do not guarantee exactly-once execution or billing. To deliberately retry a terminal failed/unknown run, change explicit configuration/scope and retain the old record.

Latency sums measured attempt durations; interrupted durations remain unknown. Metrics require exactly one result per scored case and stratify synthetic, asserted file labels, database-verified owner labels and unlabeled cases. False merges count predicted matches against no-match labels; missed matches count predicted no-matches against match labels. Their denominators are all negative and positive labels respectively, including errors and abstentions, which are reported separately and never counted as correct. Zero-denominator rates are nil. Review volume means abstentions plus errors; all shadow decisions still need owner review. Cost totals include only explicitly metered amounts by currency and separately count unknowns.

Results retain `:unknown-external-attempt-count` and a possible-duplicate-work warning when retries follow an unknown outcome. This store provides process-interruption recovery on a trusted local POSIX filesystem, not power-loss durability, distributed locking, disaster recovery or a production retention policy. No directory fsync guarantee is made. A 50-case reviewed pilot would establish feasibility, not calibrated safety thresholds or production accuracy. Synthetic test rates establish neither.

## Agent-o-rama boundary

The companion checkout at commit `fad26a6db5c4d9647c190dc3df587401b9ee5efb` exposes Clojure nodes as `(fn [node request] ...)` with `aor/result!`; see [module.clj](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/blob/fad26a6db5c4d9647c190dc3df587401b9ee5efb/ipc/src/bridge/module.clj). Its [replay contract](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/blob/fad26a6db5c4d9647c190dc3df587401b9ee5efb/ipc/REPLAY.md) explicitly excludes deduplication of external tool side effects.

`freediving.evaluation-node/node-function` takes `result!`, a trusted private dataset resolver, the private store root, configurations and runtime credentials. It returns a function accepting the node handle and a 64-character dataset content reference. The resolver must verify the requested content hash before returning data. Node inputs and results contain opaque references; private dataset bytes stay in the worker. Inject `aor/result!` at graph construction and use persistent same-host private storage across retries.

Executable tests verify this older function contract and repeated-node local persistence using an injected callback. For database-verified evaluation, use the separate optional [local Rama module](local-rama-evaluation.md), which registers the reviewed evaluator and resolves private export receipts through live database verification. The dependency-free shim does not establish that authority. No companion source was changed; production deployment remains a separate checkpoint in issue #1.
