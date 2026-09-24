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

The LLM adapter implements [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat), nonstreaming JSON-object output with an explicit outcome. It requires a completed response and validates that outcome. Supply the complete endpoint and explicit model in each configuration. Use a concrete model version when available; aliases are not pinned. Optional LLM-only `:max-completion-tokens` (integer 1-16384) is immutable and sent as `max_completion_tokens`; it bounds generated tokens, separately from response bytes. Both requested model and returned model are retained, with missing returned version represented as nil. Provider availability and model access are not established by local fixtures.

`prepare-request [config case]` builds a secret-free exact JSON body and records adapter version. `execute! [prepared runtime]` performs one attempt. Response bytes and the entire response deadline are bounded; redirects are refused. Legacy adapters retain successful raw responses privately; opt-in diagnostics below retain sanitized fields only. Errors retain sanitized categories and HTTP status without raw error messages/bodies. Timeout or transport failure can leave external execution unknown. Token usage is separate from money: adapters report cost unknown because these contracts do not provide actual metered charges. Local fixture costs are also unknown, not invented zero bills.

### Opt-in sanitized diagnostics

Set `:diagnostics-version 2` on a Jev or LLM configuration to select `shadow-adapters/4`. It accepts one complete JSON value surrounded by JSON whitespace (space, tab, CR, LF), for the outer envelope and nested LLM content. Another value, trailing junk and other whitespace characters are rejected. Quoted strings are preserved; outcomes must still match exactly. Response bounds, sanitized diagnostics and terminal-error halting remain unchanged.

The option is immutable and changes request/run identities. `:diagnostics-version 1` still selects `/3`, preserving its original parsing behavior, including erroneous rejection of legal trailing whitespace. Without diagnostics, `/1` and `/2` retain their exact identities and behavior, including `/2` configurations with `:max-completion-tokens`. Stored runs replay without dispatch. Switching to version 2 creates a new execution identity; it neither rewrites nor recovers earlier failures. The whitespace defect is proven by offline synthetic HTTP responses, but is not an established cause of the historical live baseline failure because its response body was not retained.

Adapters 3 and 4 return only allowlisted fields, even for successful predictions. Neither stores the raw response body. Invalid predictions remain nonretryable `:error :invalid-response`; they never become abstentions or recovered predictions. `:validation-reasons` is a bounded vector of fixed keywords:

- `:invalid-outer-json` or `:invalid-envelope` for unreadable JSON or incorrect response structure.
- `:missing-content`, `:invalid-content-type`, `:invalid-content-json`, or `:invalid-outcome` for LLM content failures.
- `:non-stop-finish` and `:refusal` for incomplete/refused LLM responses.
- `:invalid-choice-type`, `:invalid-choice`, `:invalid-confidence`, or `:invalid-probabilities` for Jev prediction failures.
- `:invalid-model` or `:invalid-usage` for malformed metadata.

Multiple independent failures may appear. LLM `:finish-reason` is restricted to `:stop`, `:length`, `:content-filter`, `:tool-calls`, `:function-call`, `:missing`, or `:unknown`. Refusal text, unknown finish strings, exception messages and arbitrary provider fields are never retained. JSON must contain one complete value with no trailing data; partial, unparsable or excessively nested content produces sanitized errors. Unparseable outer JSON cannot safely yield partial metadata.

A returned model must be a 1-200 character identifier using letters, digits, `.`, `_`, `:`, `/`, or `-`, starting with a letter or digit. Known runtime bearer substrings and common credential markers are rejected. These checks reduce accidental secret retention; identifier syntax cannot establish the meaning of an arbitrary string. Configuration remains trusted and must never contain credentials. Missing/null returned model and usage remain optional. Each present allowlisted usage counter (`input_tokens`, `output_tokens`, `prompt_tokens`, `completion_tokens`, `total_tokens`) must independently be an integer from 0 through 1,000,000,000. A bad counter does not erase other valid counters or a safe returned model; a bad model does not erase valid usage. Unknown counters are discarded. This bounded metadata survives durable storage/replay, but remains provider-reported and is never actual billed cost. Cost stays unknown.

Runtime bearer values never enter run identity. Set a nonsecret `:scope-id` when account or route meaning changes, even if endpoint/model stay the same. Reusing a store/configuration across such a change without changing scope would replay the old result. Credential rotation alone need not change scope.

### Opt-in strict outcome contract

Set `:output-contract :identity-outcome-v1` with `:provider :llm`, `:model "gpt-4.1-nano-2025-04-14"` and `:diagnostics-version 2` to select `shadow-adapters/5`. Other contract values, models, providers and diagnostic combinations are rejected before dispatch. Omit the option to retain adapters 1-4 unchanged.

This changes only the transport output contract: `response_format` becomes `json_schema`, named `identity_outcome_v1`, with `strict: true`. Its object has one required string property, `outcome`, with enum `match`, `no_match`, `abstain`, and `additionalProperties: false`. System/user messages, source input, model, token cap, deadline and stop policy remain unchanged. The option and adapter version produce a distinct immutable request/run identity. Old stores replay without HTTP; selecting this contract does not repair or overwrite an old run.

Official documentation checked 2026-09-24 lists structured outputs for the [pinned GPT-4.1 nano snapshot](https://developers.openai.com/api/docs/models/gpt-4.1-nano). The [Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs) distinguishes schema adherence from JSON-object mode and documents strict schemas, enums, required fields and refusal handling. This verifies documented support, not live account/model availability.

Adapter 5 also validates the exact decoded object locally. Extra keys or a non-object shape yield `:invalid-output-shape`; missing or invalid outcome values remain errors. Refusals, non-stop finishes and malformed JSON remain explicit errors with independently valid sanitized metadata. There is no coercion, recovery or conversion of failures to abstentions. Legal JSON whitespace and existing response/deadline bounds remain supported. Raw content is discarded, including failed content, so historical invalid-outcome text cannot be recovered.

Synthetic contract and durable replay tests establish these mechanics only. They establish no decision accuracy or empirical performance gain. The incomplete live baseline, actual-cost evidence and any separately authorized frozen evaluation remain tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Persistence and metrics

Runs accept at most ten configurations. Preflight limits planned response bytes plus per-attempt overhead to 32 MiB, and the canonical input/request identity to 32 MiB, before storage or dispatch. Copies, EDN encoding and retained prior runs add disk/memory overhead; these are admission bounds, not an operating-system memory cap. Response limits, timeout, retry count and scope belong to immutable configuration.

`freediving.evaluation/run!` evaluates only held-out cases for every configuration. Content identities include the complete dataset, normalized prepared requests/configuration, harness and adapter versions. It atomically writes private content-addressed inputs, requests, start/completion attempts and report records under a cooperating-process lock. Directories use 0700 and files 0600. Replay/inspection verifies referenced objects, including request and attempt traces. The owner and parent directory are trusted; hashes are not encryption.

Every attempt has a durable start before dispatch. A process killed after dispatch but before completion publication leaves an unknown external outcome. Restart records that uncertainty and does not resend that unfinished attempt. Completed attempts replay without new calls. Retryable errors may retry within the configured 1-3 attempt limit and bounded exponential backoff. A provider may have acted before timeout; retries do not guarantee exactly-once execution or billing. To deliberately retry a terminal failed/unknown run, change explicit configuration/scope and retain the old record.

Opt-in immutable `:stop-on-terminal-error? true` halts only the affected comparator after HTTP 400/401/402/403/404/422/429, invalid response or missing credential. This overrides retries, including 429 because it can signal billing failure. Other comparators continue. The failed attempt stays durable; remaining cases are `:error :comparator-halted`, explicitly `:dispatch-status :not-dispatched`, with no attempts, no latency and `:cost {:status :not-incurred}`. Report `:dispatch` separates evaluated cases, undispatched cases and attempts. Error denominators retain all held-out cases, including undispatched cases; these are never successes. Cost/latency metrics distinguish undispatched counts from unknown actual charges/durations. A missing credential records an adapter attempt, though no HTTP call occurred. Existing configurations without these options preserve their previous identities and replay behavior.

Latency sums measured attempt durations; interrupted durations remain unknown. Metrics require exactly one result per scored case and stratify synthetic, asserted file labels, database-verified owner labels and unlabeled cases. False merges count predicted matches against no-match labels; missed matches count predicted no-matches against match labels. Their denominators are all negative and positive labels respectively, including errors and abstentions, which are reported separately and never counted as correct. Zero-denominator rates are nil. Review volume means abstentions plus errors; all shadow decisions still need owner review. Cost totals include only explicitly metered amounts by currency and separately count unknowns.

Results retain `:unknown-external-attempt-count` and a possible-duplicate-work warning when retries follow an unknown outcome. This store provides process-interruption recovery on a trusted local POSIX filesystem, not power-loss durability, distributed locking, disaster recovery or a production retention policy. No directory fsync guarantee is made. A 50-case reviewed pilot would establish feasibility, not calibrated safety thresholds or production accuracy. Synthetic test rates establish neither.

## Agent-o-rama boundary

The companion checkout at commit `fad26a6db5c4d9647c190dc3df587401b9ee5efb` exposes Clojure nodes as `(fn [node request] ...)` with `aor/result!`; see [module.clj](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/blob/fad26a6db5c4d9647c190dc3df587401b9ee5efb/ipc/src/bridge/module.clj). Its [replay contract](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/blob/fad26a6db5c4d9647c190dc3df587401b9ee5efb/ipc/REPLAY.md) explicitly excludes deduplication of external tool side effects.

`freediving.evaluation-node/node-function` takes `result!`, a trusted private dataset resolver, the private store root, configurations and runtime credentials. It returns a function accepting the node handle and a 64-character dataset content reference. The resolver must verify the requested content hash before returning data. Node inputs and results contain opaque references; private dataset bytes stay in the worker. Inject `aor/result!` at graph construction and use persistent same-host private storage across retries.

Executable tests verify this older function contract and repeated-node local persistence using an injected callback. For database-verified evaluation, use the separate optional [local Rama module](local-rama-evaluation.md), which registers the reviewed evaluator and resolves private export receipts through live database verification. The dependency-free shim does not establish that authority. No companion source was changed; production deployment remains a separate checkpoint in issue #1.
