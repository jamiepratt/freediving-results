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

## Source-only freediving protocol

Jev configuration can opt into `:identity-protocol :freediving-source-v1` with
`:diagnostics-version 2`. Legacy configurations keep their exact request and
adapter identities. The new `shadow-adapters/6` request freezes the protocol
descriptor, source input, model and effective execution settings before dispatch.
The immutable descriptor and `question` function live in
`freediving.evaluation-protocol`; both sequential and native batch questions use
explicit JSON pointers to the compared records. Question keys identify outputs.

Input schema `freediving-source/1` contains `:left` and `:right`. Each record has
`:record-id`, `:fields`, `:sources`, `:uncertainties` and `:publisher-identity`.
Every field listed by `field-keys` is present, with `{:value nil :evidence-ids []}`
for unknown facts. Known values are original strings with source citations.
Closed schemas reject extra keys and nested metadata, including owner labels,
review reasons and post-review identities. Source strings remain untrusted data.
Schema validation checks provenance structure, not truth: callers independently
verify facts, source document hashes, record associations and any publisher
person-identifier uniqueness contract against archived material.

`source-evidence` verifies the exact UTF-8 artifact digest, then extracts exact
page-local lines from archived EDN `:pages`, or artifact-local lines from plain
text. Sources retain document/artifact digests, family, observation, page and line
references. Repeated exports retain their dependence. Event representation never
becomes nationality. No metadata is invented. Single requests have a hard 24,576
UTF-8 byte bound including JSON framing and instructions, plus any smaller
configured request bound. This is conservative byte admission, not exact provider
token accounting.

Synthetic tests verify preservation, schema boundaries and loopback transport,
not model classification accuracy. Outcomes remain advisory. Existing48 labels
and historical results informed the design, so comparisons are exploratory.
Freeze the protocol before fresh held-out collection; group shared people,
documents and source families conservatively and report remaining leakage.
Confidence is not calibrated accuracy. Report false merges, missed matches,
abstentions and errors with denominators; fewer abstentions alone is not success.

### Native Jev requests

Opt into `:identity-protocol :freediving-source-v1`, `:diagnostics-version 2`,
`:native-batch-size 1` for sequential single-question requests, or sizes 2-8 for
native multiple questions. These use `shadow-adapters/7` and `shadow-runner/6`.
`freediving.evaluation-providers/prepare-batches` freezes membership, order,
source state, explicit record pointers, question IDs and configuration before
any dispatch. Only exactly equal records are deduplicated; conflicting bodies
for one record ID are rejected. Shared batch state exposes other pairs' records:
size 1 versus size 2 is also a context change, not a pure execution speed control.

Optional `:companion-assessments` is a separate ordered vector drawn from
`:name-variation`, `:contradiction`, `:source-quality`. Independent yes/no/unknown
assessments remain separate from the identity answer; no combined confidence or
calibration is inferred. Benchmark this configuration as a separate arm.

Local bounds: eight pairs, 32 questions, 24,576 UTF-8 bytes for state plus longest
question, 49,152 bytes for the entire request (or a lower configured request
limit), existing response limit up to 1 MiB, deadline up to 60 seconds per request,
exactly one attempt, HTTP concurrency one. This is conservative byte admission, not exact token accounting,
with headroom below the documented Jev 32k state-plus-longest question and 64k
total limits; these are local conservative limits, not claimed
provider question limits. All planned requests undergo admission before dispatch.
The finite dataset and per-request deadline bound total scheduling.

Adapters 6 and 7 reject duplicate JSON keys, missing/unexpected answer IDs,
invalid distributions, choices below the maximum probability, and missing or
mismatched pinned returned models. Native partial responses preserve valid
expected answers; missing/malformed siblings are explicit errors. Any schema
failure halts later batches even when all expected identity answers were valid.
Auth, billing, rate-limit, oversized-response and unknown external outcomes also
halt scheduling. Undispatched cases remain in denominators. No timeout or
interrupted request is automatically resent. Start and completion records are
at the actual HTTP request boundary; replay reuses completed or uncertain batches.

`:batches` retains request receipts and case/evidence links. `:request-metrics`
contains batch sizes, request/question counts, latency samples and sum, usage
and known-usage request count, request errors, and request-level cost. Case
latency is nil and case cost unknown with `:accounting :request-level-only`;
no request cost is duplicated across cases. Stored wall time describes the
completion invocation including local overhead; recovered runs are not clean
throughput benchmarks. Measure whole-arm wall time externally for comparisons.
Actual billed cost remains unknown unless separately available; estimates must
be labelled. Source text and receipts stay private; response text and credentials
are not stored by the new adapters.

### Opt-in native failure diagnostics

Add `:native-diagnostics-version 1` to a native configuration to select
`shadow-adapters/8`. Version 2 selects `/9`, described below; other values or use
without native batching are rejected.
The option and adapter version create new immutable request/run identities;
the exact HTTP body, model, source protocol, question semantics and runner bounds
remain unchanged. Omitting it preserves adapters 1-7, including frozen adapter 7
configurations and completed, failed or uncertain replay. It does not retrofit
stored requests or redispatch them.

For each expected answer, `/8` retains the first failed validation category in
`:validation-reasons`: `:missing-answer`, `:invalid-answer-type`,
`:invalid-choice-type`, `:unsupported-choice`, `:missing-confidence`,
`:invalid-confidence`, `:missing-probabilities`, `:invalid-probabilities-type`,
`:invalid-probability-keys`, `:invalid-probability-type`,
`:invalid-probability-range`, `:invalid-probability-sum`, or
`:choice-probability-inconsistency`. The existing `:missing-answer` or
`:invalid-answer` error and strict acceptance criteria remain. A valid sibling
survives an invalid answer; the request still fails and halts later dispatch.
Confidence need not equal the selected probability: the official confidence
page describes a distribution statistic without specifying its formula.

Request reasons distinguish `:invalid-outer-json` (including duplicate keys),
`:invalid-envelope`, `:missing-answers`, `:invalid-answers-type`,
`:missing-answer-identifiers`, `:extra-answer-identifiers`, `:missing-model`,
`:invalid-model`, `:model-mismatch`, `:missing-usage`, and `:invalid-usage`.
No unexpected IDs, malformed values, raw response text or exception strings
are retained. Returned model metadata survives only when sanitized and equal
to the pinned requested model. Independently valid allowlisted integer token
counters survive even when another counter or the decision is invalid; unknown
usage keys are ignored. Metadata failures invalidate all decisions.

The official API marks the `usage` object required. Adapters 6/7 tolerated its
absence or null; `/8` requires an object. Its documented integer counters are
not individually marked required, so an empty usage object is accepted with
no measured counters. An empty set of valid counters is stored as nil usage and
does not increment `:usage-known-request-count`. Existing counter bounds (0 through 1,000,000,000) apply.
This stricter envelope check is versioned; it cannot explain the earlier live
batch's `:invalid-answer`, whose malformed answer was not retained. That
historical failure's precise category remains unknown. Offline loopback tests
cover redaction, independent metadata, partial outcomes, terminal halting,
request-level accounting, distinct identities and replay without new HTTP calls.
Remaining live benchmark work is tracked in [issue #4](https://github.com/jamiepratt/freediving-results/issues/4).

Provider contracts checked 2026-09-24: [API](https://docs.typesafe.ai/api),
[models and limits](https://docs.typesafe.ai/models),
[fan-out](https://docs.typesafe.ai/patterns/fan-out),
[parallel questions](https://docs.typesafe.ai/cookbooks/parallel_questions), and
[confidence](https://docs.typesafe.ai/confidence). Provider limits may change;
the frozen local admission settings remain part of each request identity.

### Exploratory run, 2026-09-24

The frozen `jev-1.13.0` protocol ran against 48 definitive owner-reviewed pairs
(29 match, 19 no-match); two unknown decisions remain preserved separately.
All 437 source references were checked against independent extraction of six
source PDFs. Missing metadata stayed explicit. Both arms used one HTTP request
at a time, one attempt, a 15-second deadline and no companion questions.
Arm A sent one pair per request; B planned two. B shared additional pair records
in state, so this was also a context change, not a pure execution comparison.

| Measure | A: complete | B: partial, halted |
| --- | ---: | ---: |
| Requests / questions | 48 / 48 | 3 of 24 / 6 of 48 |
| Pairs per request, in frozen order | 48 batches of 1 | First 3 batches of 2 |
| Match / no-match / abstain | 29 / 10 / 9 | 4 / 0 / 1 |
| Invalid answers / undispatched pairs | 0 / 0 | 1 / 42 |
| Decisive / all pairs | 39/48 | 4/48 |
| Error outcomes / all pairs | 0/48 | 43/48 |
| False merges / no-match labels | 0/19 | 0/19 |
| Missed matches / match labels | 0/29 | 0/29 |
| Rama invocation wall time | 61.009 s | 24.851 s |
| Request-loop wall time | 39.245 s | 3.952 s |
| Summed request latency | 37.581 s | 3.214 s |
| Request latency mean / median / p95 | 782.94 / 768.02 / 879.43 ms | 1071.20 / 1028.03 / 1174.77 ms |
| Dispatched pairs / Rama wall second | 0.787 | 0.241 |
| Input / output tokens | 236,323 / 2,092 | 28,526 / 252 |
| Token-based cost estimate, USD | 0.009925566 | 0.001198092 |
| Actual billed cost | Unknown | Unknown |

B's third HTTP 200 response contained one invalid answer. Valid siblings were
retained, and scheduling stopped with 42 pairs undispatched. The rejected field
is unknown because the invalid raw answer was not retained. B's zero observed
false merges and missed matches do not establish quality: 43/48 outcomes are
errors, including undispatched work. Its partial timing cannot establish a
throughput advantage. Among the five jointly valid pairs, choices agreed, but
all five probability distributions changed (maximum absolute difference 0.16).

The preserved historical reference is 7 match, 7 no-match and 34 abstain,
14/48 decisive. A's 39/48 decisive outcomes are descriptive only: existing labels
and outcomes informed design. There is no unbiased improvement, calibration or
production-accuracy claim. Local timings were uncontrolled; a brief historical
replay-copy audit overlapped late B verification. Estimates use the checked
$0.042 per million input tokens with free output, not billing receipts.

Completed A and partial B replayed with zero provider calls and unchanged private
stores. Live label authority passed before and after; owner decisions remained
unchanged. Historical replay from a copy also made zero calls and retained its
run identity. All 793 original historical files retained hashes, modes and
modification times. Private receipts retain exact order, requests, configurations
and paired outcomes; only aggregates appear here. Independent combined validation
passed 93 tests and 1,401 assertions.

The domain/source acceptance evidence supports [#3](https://github.com/jamiepratt/freediving-results/issues/3).
The incomplete B benchmark left [#4](https://github.com/jamiepratt/freediving-results/issues/4)
and [#5](https://github.com/jamiepratt/freediving-results/issues/5) open at that checkpoint.
The later complete F comparison is recorded below; B remains unchanged.
Fresh held-out evaluation is tracked in [#6](https://github.com/jamiepratt/freediving-results/issues/6);
actual billing evidence remains tracked under [#1](https://github.com/jamiepratt/freediving-results/issues/1).

### Probability contract audit, 2026-09-24

The [HTTP API](https://docs.typesafe.ai/api) describes Choice probabilities as
floats summing to 1; the [Choice guide](https://docs.typesafe.ai/primitives/choice)
also states that their sum is 1. The
[Python response documentation](https://docs.typesafe.ai/sdk/python/api/types/responses)
instead says they sum to approximately 1, without specifying an error bound.
The [JavaScript response interface](https://docs.typesafe.ai/sdk/javascript/api/interfaces/ChoiceResponse)
adds no numerical bound. These checked pages specify neither decimal precision,
rounding mode, normalization order nor an allowed deviation. Two-decimal examples
are examples, not a general rounding guarantee. Vendor clarification is needed
to establish a different numerical acceptance contract.

The [Jev 1.13 limitations](https://docs.typesafe.ai/model-jaggedness/jev-1.13)
discuss numeric reasoning and inconsistent answers across separate questions.
Those limitations do not define an exception to normalization within one Choice.
[Confidence](https://docs.typesafe.ai/confidence) is derived from a distribution,
but its formula is unspecified; it is not the chosen probability or evidence of
calibration for these identity decisions.

The adapter decodes JSON decimal numbers as binary doubles using `data.json`,
rejects duplicate keys, and accepts a probability sum only when
`abs(sum - 1) < 0.00001`. This is a local strict policy, not a documented vendor
tolerance. Ordinary double representation and addition error for three bounded
probabilities is far smaller than this threshold. Decimal-looking values such as
`0.33, 0.33, 0.33` fail that policy; their appearance cannot establish whether the
provider rounded them. No normalization or tolerance change is justified by this
audit.

The later adapter-8 diagnostic D made one request and rejected `identity_0` with
`invalid-probability-sum`, retaining its valid sibling. Its rejected numbers were
not persisted: the deviation and cause remain unknown. This observation cannot
recover the earlier B failure cause. A new diagnostic cannot recover either
historical response.

### Opt-in numerical diagnostics

Native configurations with `:native-diagnostics-version 2` select immutable
`shadow-adapters/9`. Validation remains unchanged. An `invalid-probability-sum`
answer now includes `:probability-diagnostics` after its answer type, choice,
confidence, exact probability keys and numeric ranges have passed validation:

- `:values`: exactly three finite numbers in `[0,1]`, under fixed keyword keys
  `:match/:no_match/:abstain` or `:yes/:no/:unknown` for companions.
- `:count`, `:sum`, `:absolute-deviation`, and `:tolerance`: respectively 3,
  a number in `[0,3]`, a number in `[0,2]`, and `0.00001`.
- `:comparison :strict-less-than` and `:rounding-cause :unestablished`.

These are decoded numerical values, not original decimal text or proof of the
provider's internal precision. No raw response, arbitrary keys, strings, invalid
ranges or nonfinite values enter this diagnostic. Invalid metadata still blocks
decisions; independently valid request counters remain available. Valid siblings
survive, terminal failures stop later dispatch, and uncertain/completed runs
remain non-dispatching on replay. Versions 7 and 8 retain their previous behavior.
HTTP body, model, domain protocol, bounds and question semantics are unchanged.

The subsequent separately scoped `/9` diagnostic E used the original failed B
batch's two ordered cases, an exact matching HTTP body, a fresh store and
`:scope-id "jev-probability-diagnostic-20260924-07"`. It retained D's model,
protocol and execution limits, verified current label authority before/after,
and replayed without dispatch. Successful new evidence cannot establish an
undocumented tolerance or explain historical failures. Remaining probability
contract context is recorded in [#4](https://github.com/jamiepratt/freediving-results/issues/4).

### Complete native comparison, 2026-09-24

The separately authorized E diagnostic returned two valid answers in one request.
It did not recover the rejected values or causes of B/D. A fresh full comparison,
F (`jev-native-comparison-20260924-08`), then completed all 24 requests and 48
identity questions. A was reused without redispatch; failed B and diagnostic D/E
stores were preserved. This is an exploratory systems comparison, including an
observed false merge, not evidence for automatic identity decisions.

F froze the original 48 cases, order, source evidence and question semantics.
Independent preparation verified all 24 HTTP bodies byte-for-byte against B
(17,550-21,887 bytes), and pair mappings against A. Only the configuration ID,
scope and opt-in numerical diagnostics changed from B. Requested and returned
models were `jev-1.13.0` in every A/F request. F used adapter 9, native diagnostics
2, `freediving-source-v1`, diagnostics 2, batch size 2, no companions, concurrency
1, one attempt, zero retry delay, a 15-second request deadline, 49,152/65,536-byte
request/response limits and terminal stopping. No validation tolerance changed.
No other tests or audits ran during F's timed dispatch.

| Measure | A: preserved single-question arm | F: complete native arm |
| --- | ---: | ---: |
| Requests / identity questions | 48 / 48 | 24 / 48 |
| Pairs per request, in frozen order | 48 batches of 1 | 24 batches of 2 |
| Match / no-match / abstain | 29 / 10 / 9 | 30 / 15 / 3 |
| Decisive coverage | 39/48 (81.25%) | 45/48 (93.75%) |
| Errors / undispatched pairs | 0 / 0 | 0 / 0 |
| False merges / no-match labels | 0/19 | 1/19 (5.26%) |
| Missed matches / match labels | 0/29 | 0/29 |
| Outer wrapper wall time, different scopes | 61.009 s | 57.825 s |
| Request-loop wall time | 39.245 s | 22.717 s |
| Summed request latency | 37.581 s | 22.046 s |
| Request latency mean / median / p95 | 782.94 / 768.02 / 879.43 ms | 918.56 / 898.65 / 1021.03 ms |
| Request latency min / max | 714.57 / 984.05 ms | 839.97 / 1200.95 ms |
| Dispatched pairs / request-loop second | 1.223 | 2.113 |
| Successfully evaluated pairs / request-loop second | 1.223 | 2.113 |
| Dispatched or successfully evaluated pairs / wrapper second | 0.787 | 0.830 |
| Requests with token counters | 48/48 | 24/24 |
| Input / output tokens | 236,323 / 2,092 | 229,795 / 2,013 |
| Request errors / HTTP 200 responses | 0 / 48 | 0 / 24 |
| Actual billed cost | Unknown | Unknown |

Successfully evaluated includes valid abstentions, not just decisive choices.
Median and p95 use nearest-rank order statistics. Request timings and token
counters remain request-level; neither is charged to every pair. No new cost
estimate or billing receipt was obtained. A's wrapper measured its Rama
invocation; F's direct verified-API wrapper includes repeated authority checks,
report inspection and persistence, but excludes JVM startup and initial
preflight. These outer timings are not directly comparable.

All 48 pairs had valid results in both arms. Choices changed for six: five
previous abstentions became correct no-match decisions, and one became an
incorrect match. The other 42 choices stayed the same: 29 matches, ten
no-matches and three abstentions. Probability distributions changed for 45/48
pairs; the maximum absolute component difference was 0.48 and the mean across
all 144 components was 0.06306. Lower abstention came with a false merge; it is
not sufficient evidence of better decision quality. Both error-rate denominators
remain 48, and false-merge/missed-match denominators include abstentions.

A and F were noncontemporaneous, uncontrolled runs. Native shared state exposed
the other pair's records, so observed timing and answer differences cannot be
attributed solely to execution strategy. Stochastic variation was not isolated.
Existing labels and outcomes informed protocol design: no unbiased improvement,
calibration or production-accuracy claim follows. The historical 7 match, 7
no-match, 34 abstain reference remains unchanged; companions and concurrent HTTP
arms were not run.

The full reviewed receipt was verified before/after F and replay against the
user-designated recovered authority. All 13 logical table fingerprints, 51
label-history events and latest 50 decisions (29 match, 19 no-match, two unknown)
remained unchanged. The two unknown decisions were excluded from provider cases
and scoring. This snapshot authority does not establish uninterrupted history;
possible post-backup loss remains acknowledged. Independent checks re-extracted
six PDFs, verified 437 source references and 96 parsed records, and reproduced
all 48 source-only inputs. Closed-schema and metadata-key checks found no owner
labels/reasons in provider input.

F replay used a throwing dispatch guard: zero provider calls, identical results
and verified report, unchanged private store bytes/modes/modification times.
All 1,907 inventoried prior evidence files and frozen inputs remained unchanged.
Credentials stayed in memory; the exact credential/service-token scan passed.
Private evidence uses 0700 directories and 0600 files outside the disposable
worktree. New private harness admission/launch/replay behavior followed TDD;
independent ordered repair, lint and five tests/23 assertions passed. Shipped
adapter regression evidence remains 107 tests/1,779 assertions from the prior
implementation batch; this evidence-only update did not rerun that full suite.

F raw run: `adeb3b1c98bf2f2dc18f567c2be86a0c39bd33719a9629d06da0d1fdd4ce7901`.
Verified report: `8b6de8b69084cfe82140ba050199fd49ebe354494e5dc3bca06a550a412938f2`.
Exact inputs, request receipts and paired probabilities remain private.
The completed exploratory benchmark supplies the remaining comparison evidence
for [#4](https://github.com/jamiepratt/freediving-results/issues/4) and
[#5](https://github.com/jamiepratt/freediving-results/issues/5).
Fresh held-out owner review remains [#6](https://github.com/jamiepratt/freediving-results/issues/6);
actual billing and broader pilot/release limits remain
[#1](https://github.com/jamiepratt/freediving-results/issues/1).

### Question-local structure protocol

Without a probability-sum tolerance option, `freediving-question-local-v1` selects `shadow-adapters/10` through
`prepare-batches`, including batch size 1. It requires `jev-1.13.0`, native
diagnostics 2, no companions, and batch size 1 or 2. The original
`freediving-source/1` closed evidence schema remains unchanged.

Shared `state` contains the exact original freediving guidance. Each Choice
question carries structured `instructions` with complete `left` and `right`
source records and a `question` explicitly referencing them. Criteria and
advisory semantics are unchanged. Only structure and necessary references
change; no source enrichment or wording experiment is included. The same pair
has identical question content in either batch size, including unknowns,
source rows and extraction uncertainty. No other pair appears in its question
or shared state. The [official API](https://docs.typesafe.ai/api#question-types)
supports structured question instructions and question-specific data.

Requests retain the protocol descriptor and new adapter identity. Historical
protocols, request identities and replay behavior remain unchanged. Strict
response validation, numerical diagnostics, terminal stopping, one attempt,
sequential HTTP, per-request accounting and durable replay are retained.
The original `/10` protocol rejects companions and other batch sizes to keep the
structure experiment bounded. Direct `prepare-request` remains the historical
single-question API; use `prepare-batches` with size 1 for this new protocol.

Offline tests verify evidence isolation, identical per-pair questions across
batch sizes, unchanged guidance/criteria, invalid configuration rejection,
strict probability validation with valid sibling retention, and zero-call
replay. These transport tests establish no model accuracy.

### Repeated structure-only experiment, 2026-09-25

Before dispatch, [issue #6](https://github.com/jamiepratt/freediving-results/issues/6)
recorded code `b593795e0ee1769dcab5eb46b4ce236eab233b83` and frozen private receipt
SHA-256 `a9c4ba6955eaf3d538295ff9008e19ea6fdf8ccc2e292aeeaf92f49c6c9a8b05`.
The predeclared order was S1, B1, B2, S2: single, double, double, single.
All used the original 48 cases/order, consecutive pairing, identical guidance
and per-pair questions, model `jev-1.13.0`, one HTTP request at a time, one
attempt, no companions, 15-second deadlines, 49,152/65,536-byte request/response
limits and native diagnostics 2. A terminal error stopped its arm; other
predeclared controls continued. No replacement or adaptive rerun was added.

| Measure | S1 | B1 | B2 | S2 |
| --- | ---: | ---: | ---: | ---: |
| Requests completed / planned | 29/48 | 24/24 | 24/24 | 48/48 |
| Questions dispatched | 29 | 48 | 48 | 48 |
| Match / no-match / abstain | 15/11/2 | 29/17/2 | 29/17/2 | 29/17/2 |
| Errors / undispatched pairs | 20/19 | 0/0 | 0/0 | 0/0 |
| Decisive coverage, all 48 pairs | 54.17% | 95.83% | 95.83% | 95.83% |
| False merges / negative labels | 0/19 | 0/19 | 0/19 | 0/19 |
| Missed matches / positive labels | 0/29 | 0/29 | 0/29 | 0/29 |
| Request-loop wall time, seconds | 22.481 | 22.242 | 22.222 | 38.165 |
| Summed HTTP latency, seconds | 21.593 | 21.578 | 21.563 | 36.911 |
| Request latency median / p95, ms | 719/884 | 889/949 | 877/988 | 729/981 |
| Input tokens | 157,835 | 247,477 | 247,477 | 261,157 |
| Output tokens | 1,262 | 2,013 | 2,013 | 2,085 |
| Actual billed cost | Unknown | Unknown | Unknown | Unknown |

S1 request 29 returned HTTP 200 with probabilities match 0.81, no_match 0.01,
abstain 0.17. Their decoded sum was 0.9900000000000001, failing the unchanged
strict `abs(sum - 1) < 0.00001` contract. Its outcome remains an error; the
remaining 19 pairs were not dispatched. The cause of the deviation is unknown.
S1's partial run cannot support a whole-corpus timing or accuracy comparison.
There were 125 requests and 173 questions in total; 172 valid pair outcomes.

All jointly valid choices agreed, within and between arms. Paired probability
comparisons below use maximum absolute component change and mean absolute
change across all three components of jointly valid pairs.

| Comparison | Jointly valid | Changed distributions | Maximum | Mean |
| --- | ---: | ---: | ---: | ---: |
| S1 vs S2, single repeat | 28 | 25 | 0.05 | 0.01286 |
| B1 vs B2, double repeat | 48 | 39 | 0.08 | 0.01333 |
| S1 vs B1 | 28 | 24 | 0.08 | 0.01571 |
| S1 vs B2 | 28 | 25 | 0.07 | 0.01381 |
| S2 vs B1 | 48 | 37 | 0.10 | 0.01431 |
| S2 vs B2 | 48 | 39 | 0.09 | 0.01194 |

Observed between-arm probability changes were similar in scale to repeat
variation. These controls establish neither a causal batching effect nor
probability equivalence. The completed double runs used half the requests and
about 58% of S2's request-loop time in this local experiment. Two repeats,
a partial single arm, fixed order/pairing, shared people/documents, and provider
variability limit generalization. These are development diagnostics; existing
labels and historical outcomes informed the protocol. No unbiased improvement,
calibrated accuracy, or production-safety claim follows. Historical A/F remain
descriptive references only. Token counters are not billed-cost receipts.

Independent PDF re-extraction reproduced all 48 source inputs from six PDFs,
96 parsed records and 437 source references. The original 50 owner decisions
retain two unknowns outside scoring. No richer facts, owner reasons or prior
model answers entered requests. The protocol is frozen at the identity above;
fresh selection and scoring remain blocked on the owner's sample size,
collection scope, false-merge criterion and genuine new reviews in #6.

The owner receipt verified before/after live execution and replay. All 13
logical authority-table fingerprints and 430 inventoried historical files
retained their prior state. Replay made zero provider calls, reproduced the
identical report and left all 653 run-store files' bytes, modes and modification
times unchanged. The live credential/service-token scan found no retained
secrets. Adapter validation passed 84 tests / 1,610 assertions, with clean
ordered delimiter repair and lint. Paired choices, probabilities, exact requests,
source evidence and owner data remain private; only aggregates are committed.

Raw run identity:
`c0efa8b594952b4e7886b43a347ab5f97c6a365d5ddef60f272330e8231ebc90`.
The partial S1 is preserved permanently. Its validation failure is not repaired
or erased by successful controls, and no probability normalization was added.

### Owner-approved 81-case held-out run, 2026-09-25

The owner selected 81 cases and approved their existing assistant judgments and
reasons by reference in [issue #6](https://github.com/jamiepratt/freediving-results/issues/6#issuecomment-5835653016).
The reasons remain assistant-authored. The original blank approval CSV and
seven earlier owner adjudications are preserved outside this selected sample.
Labels comprise 46 match and 35 no-match decisions. These are file-backed owner
assertions, not database-verified review exports.

The sample preserves the selected packet's exact IDs/order: 36 Croatian Open
2026 and 45 Italian Open Outdoor 2026 comparisons, 116 apparent source people
without cross-case reuse, two documents/events and one publisher. Owner
attestations cover development document/family and selected-person disjointness;
hidden aliases and copies remain possible. Independent checks reproduced all
162 selected PDF rows and 980 source-evidence entries. Labels and reasons stayed
outside provider inputs.

Before dispatch, the batch froze S1 then B1: 81 single-question requests followed
by 41 consecutive two-question requests, with a final singleton. Per-pair
questions were identical. The maximum was 122 requests / 162 questions, with
one HTTP request at a time, one attempt, no replacements or adaptive retries.
The model, protocol, schema, strict parser and execution limits remained frozen
at `jev-1.13.0`, `freediving-question-local-v1`, `freediving-source/1` and
`shadow-adapters/10`; evaluation source remained byte-identical to
`b593795e0ee1769dcab5eb46b4ce236eab233b83`. This run has no repeated controls and
cannot isolate a causal batching effect.

| Measure | S1 | B1 |
| --- | ---: | ---: |
| Requests completed / planned | 37/81 | 19/41 |
| Questions dispatched | 37 | 38 |
| Match / no-match / abstain | 23/13/0 | 24/13/0 |
| Errors, including undispatched cases | 45 | 44 |
| Undispatched cases | 44 | 43 |
| Decisive coverage, all 81 cases | 44.44% | 45.68% |
| False merges / all negative labels | 0/35 | 0/35 |
| Missed matches / all positive labels | 0/46 | 0/46 |
| Request-loop wall time, seconds | 26.738 | 17.385 |
| Summed HTTP latency, seconds | 25.494 | 16.726 |
| Request latency median / p95, ms | 657/880 | 883/933 |
| Input / output tokens | 157,115 / 1,604 | 151,928 / 1,590 |
| Actual billed cost | Unknown | Unknown |

Both arms returned HTTP 200 with probabilities match 0.93, no_match 0.01,
abstain 0.05 for the same case. The sum, 0.9900000000000001, failed the unchanged
strict tolerance of 0.00001. The cause is unestablished. Each arm halted as
predeclared; B1 retained its valid sibling answer. No normalization or retry was
introduced. There were 56 HTTP calls / 75 question attempts total. Only 13 of
35 negative labels received valid predictions in either arm. The zero observed
errors among valid decisions do not establish acceptance over all 81 cases.

All 36 jointly valid choices agreed. Sixteen probability distributions changed;
maximum absolute component difference was 0.03 and mean absolute difference was
0.005185. These partial results establish neither full-sample accuracy nor
production safety. [Issue #6](https://github.com/jamiepratt/freediving-results/issues/6)
remains open for its unmet evaluation gate and billing evidence where available.

Private artifacts are in
`data/heldout-evaluation-81-20260925-b3/` in the saved project. Approval receipt
SHA-256: `9f4b9a709469b187c19ee3ca66417bd8b6510f704772a706d76f0999c0af2150`;
frozen request/configuration receipt:
`a374bebcd20a56de6f2a18acc2c72c1d8556ff184801948390bfe6c65e88826f`;
live report: `06867c4a793c981dbb1631cb5b749f067b6a2fb13831d63bb656f263ec0f8d76`.
Run identity:
`ca12c8b9a0ee0f9899d31d0a370aa0ae79160206448923d9f5abf4003c9dc58f`.

The frozen adapter exposes sanitized diagnostics, not raw HTTP bytes. All
exposed results, failures and partial-run diagnostics are retained; raw response
bytes are unavailable by design. Replay made zero calls and reproduced the
same result/report. All 751 bound files and 351 run-store files retained their
bytes, modes and modification times. A live credential/service-token scan found
no retained secrets. The unchanged adapter passed 84 tests / 1,610 assertions;
private harness delimiter repair, lint and preflight passed. No identity merges,
publication, deployment or database mutation occurred.

### Inclusive probability-sum tolerance

For question-local Jev requests, set `:probability-sum-tolerance 0.02` to select
`shadow-adapters/11`. Probability components must sum to **0.98 through 1.02,
including both endpoints**. Decimal summation avoids rejecting a boundary due
to binary floating-point arithmetic. For example, `0.93 / 0.01 / 0.05` is
accepted unchanged; probabilities are never normalized.

Individual components must still be within [0, 1], contain exactly the expected
choice keys, and agree with the selected maximum-probability choice. Confidence,
model, usage, response structure and execution limits retain their validation.
The option accepts only `0.02` with `freediving-question-local-v1`; omitted
options preserve adapter `/10` and its historical strict sum validation.

The new option changes the immutable request/run identity without changing the
wire body, prompt, model or source evidence. Existing stored results and errors
remain unchanged and replay without HTTP. Synthetic loopback tests cover both
inclusive endpoints, nearby rejected sums, unchanged probabilities, other
validation failures, distinct run identities and replay of both versions.

### Full same-sample /11 evaluation, 2026-09-26

The owner [authorized the frozen full /11 run](https://github.com/jamiepratt/freediving-results/issues/6#issuecomment-5842781379)
on the same 81 cases, S1 then B1, with explicit probability-sum tolerance 0.02.
All 122 prepared objects and wire hashes reproduced the B6 packet. Wire bodies,
case order, labels, source evidence, model and execution limits remained unchanged.
The separate B7 store contains 122 completed requests / 162 question attempts,
with one request at a time, one attempt, no retries or companion requests.

| Measure | S1 | B1 |
| --- | ---: | ---: |
| Requests completed / planned | 81/81 | 41/41 |
| Questions dispatched | 81 | 81 |
| Match / no-match / abstain | 46/35/0 | 46/35/0 |
| Errors / undispatched cases | 0/0 | 0/0 |
| Valid and decisive coverage | 81/81 (100%) | 81/81 (100%) |
| False merges / all negative labels | 0/35 | 0/35 |
| Missed matches / all positive labels | 0/46 | 0/46 |
| Request-loop wall time, seconds | 34.580 | 20.396 |
| Summed HTTP latency, seconds | 32.278 | 19.149 |
| Request latency median / p95, ms | 391/469 | 452/582 |
| Reported input / output tokens | 379,377 / 3,518 | 356,577 / 3,398 |
| Actual billed cost | Unknown | Unknown |

All 81 paired choices agreed. Probability distributions differed on 35 cases;
maximum absolute component difference was 0.03, mean absolute component difference
0.004115 across 243 components. Private paired outcomes retain every choice and
probability distribution. Token counters are provider-reported usage, not billing.

These are descriptive results against owner-approved, assistant-authored judgments
and reasons. The seven earlier owner adjudications remain excluded. Of the 81
cases, 38 had already been exposed in partial /10 execution, and the /11 validator
choice followed those results. The run is not untouched fresh held-out evidence.
The 116 apparent people, two event/documents and one publisher limit independence;
owner-attested development disjointness does not rule out hidden aliases or copies.
No repeat controls establish a causal batching benefit, calibration, unbiased
generalization or production safety. No /10 result filled a /11 gap. B3/B4 remain
separate and unchanged. [Issue #6](https://github.com/jamiepratt/freediving-results/issues/6)
remains open for an explicit acceptance decision under these limitations; the /10
conditional closure rule was not transferred to /11.

Private artifacts: `data/heldout-evaluation-81-20260926-b7/` in the saved project.
Run identity: `b437d63afdbfde706dbea25a7e0e3721dfdb9b02ef8adb71836fab36669608aa`.
Live report SHA-256: `751185331e36003c576bf11979d2320d4715b81994ce147fcdc08df8ed778cd2`;
summary: `4cdaee1d6be963438920c352342789234c1aae5256158ff05a41bc5ccee58db1`;
paired outcomes: `598301a4c7e70ba6fa5dd531d1d14022d705a39a451862126bb38d86a082c9a8`;
launch manifest: `fbf35d1eea9445052c80f5331623600449a47964355320d68cfc1e9522d592a4`.
The frozen adapter retains sanitized diagnostics, not raw HTTP response bytes.

The batch-specific `scripts/jev_frozen_run.py` launcher verifies hash-bound inputs
and executables before credentials, records an exclusive fsynced launch marker,
and refuses every later live launch. Its Clojure entrypoint verifies prepared
objects and run identity before execution; replay requires complete evidence and
forbids dispatch. Offline tests cover interrupted launches, tamper rejection,
the complete CLI, synthetic full execution and terminal-arm stopping. Four wrapper
tests and the shadow suite (87 tests / 1,651 assertions) passed; Clojure repair and
lint were clean. Live replay made zero calls and reproduced result/report with all
615 store files unchanged in bytes, permissions and modification times. All 462
inventoried prior files retained their hashes. An in-memory exact credential and
service-token scan found no retained secrets. No deployment or authority mutation.

### Five-question comparison, 2026-09-26

The owner requested a larger-batch comparison with unchanged questions. Fixed
batch size five was the largest size up to eight for which every consecutive
group fit the unchanged 49,152-byte request cap: maximum 47,548 bytes, versus
59,261 for size six. The frozen L5 arm comprised sixteen five-question requests
and one singleton, covering all 81 original cases in order. All question objects,
shared state, labels and source evidence exactly matched the B7 inputs.

With `:probability-sum-tolerance 0.02`, question-local batch sizes 3-8 now select
`shadow-adapters/12`. Sizes 1-2 retain `/11`, and omitted tolerance retains `/10`.
The `/12` parser uses the same rounded-sum validation as `/11`, without
normalization. Request and response byte caps, per-question bound, pinned model,
15-second timeout, one attempt, terminal stopping and no-companion rule remain.

| Measure | Single (S1) | Pairs (B1) | Five (L5) |
| --- | ---: | ---: | ---: |
| Requests | 81 | 41 | 17 |
| Valid / total cases | 81/81 | 81/81 | 81/81 |
| Match / no-match | 46/35 | 46/35 | 46/35 |
| False merges / 35 negative labels | 0 | 0 | 0 |
| Missed matches / 46 positive labels | 0 | 0 | 0 |
| Abstentions / errors / undispatched | 0/0/0 | 0/0/0 | 0/0/0 |
| Request-loop wall time, seconds | 34.580 | 20.396 | 10.308 |
| Summed HTTP latency, seconds | 32.278 | 19.149 | 9.766 |
| Request latency median / p95, ms | 391/469 | 452/582 | 574/726 |
| Reported input tokens | 379,377 | 356,577 | 342,897 |
| Reported output tokens | 3,518 | 3,398 | 3,326 |

L5 made exactly 17 new requests / 81 question attempts, with no retries or
resends. Every choice agreed with both earlier arms. Probability distributions
differed on 34 cases versus S1 and 32 versus B1; maximum absolute component
difference was 0.04 in both comparisons. Mean absolute component differences
across all 243 components were 0.003621 and 0.003704 respectively. Stored-value
comparisons and a 1e-9 roundoff threshold gave the same changed-case counts.
Actual billed cost remains unknown.

The runs occurred at different times, without randomized order or repeat
controls. Their observed latency differences do not establish a causal batching
benefit. All 81 cases had already been exposed before L5, and larger grouping
was selected after the earlier results. The original owner approval by reference
of assistant-authored labels/reasons, excluded seven adjudications, two-document
dependence and hidden-identity limitations remain. This comparison supplies no
fresh held-out, calibrated, generalization or production-safety claim.
[Issue #6](https://github.com/jamiepratt/freediving-results/issues/6) remains open.

Private evidence: `data/heldout-evaluation-81-20260926-b8/` in the saved project.
Run identity: `d0f689974a196c734b8d492011da3a6b12ed5b0b93e9a76328bd20789b202ac2`.
Live report SHA-256: `597a218f7548d1553ff225742f5f59482d04bf37048450d5fac56901e2896fd1`;
summary: `270d6f0640d5797e1bb852a33a4fecd8ab92ab9e086ad7f2a3082d0c78383ac8`;
paired outcomes: `372204b157326461bea9bf1e36d6c37378a6c5b84aff28d5a721e07adeddead3`.
The separate launch manifest binds executable/input hashes and the expected
identity. The one-shot launcher refuses any second live launch after a durable
start. Replay made zero calls and preserved all 90 store files; all 1,064 prior
inventory files retained bytes, size, permissions and modification times.
Credential/service-token scanning found no retained secrets. Raw HTTP response
bytes remain unavailable under the existing adapter design.

TDD covered larger-group preparation and content equality, HTTP parsing,
unchanged byte bounds, terminal stopping, interrupted launch refusal, and replay.
The shadow suite passed 90 tests / 1,675 assertions; eight combined old/new
launcher tests passed. Clojure delimiter repair and lint were clean. The B7
packet still reproduces all 122 original prepared objects and its run identity.
