# Local reviewed-label export

This bridge connects explicit pair decisions to the private shadow evaluator. It implements a local verification boundary for [issue #1](https://github.com/jamiepratt/freediving-results/issues/1). No genuine pilot labels have been created. Synthetic decisions and synthetic database demonstrations do not count toward the required owner reviews.

## Decision semantics

A label concerns two exact observation versions. `match` asserts that this pair describes the same person; `no-match` explicitly asserts that it does not. Unknown, abstention, a rejected proposal, generic identity no-match, and different identity anchors cannot supply a negative pair label. Existing observation review histories remain intact. The pair API creates evaluation decisions separately and cannot merge identities or approve publication.

Each decision retains source and extraction hashes, observation references, exact source-line evidence, review revisions, rubric version, actor label, reason, database role and database time. Later revocation or changes to reviewed evidence invalidate the earlier export. A fresh export records exclusion reasons instead of silently treating uncertain evidence as a negative label.

## Authority and provenance

An administrator configures the corpus as synthetic or real. Request files cannot promote synthetic data to genuine owner provenance. Synthetic demonstrations must use synthetic configuration, including tests that exercise reviewer privileges. Database role authority establishes which local capability recorded a decision; the actor string is audit metadata, not cryptographic proof of human identity.

The exporter reads one bounded database snapshot without inserting decisions. Verification compares the export to the authoritative database again. A matching file hash alone is insufficient. The runner rechecks the database before scoring or replaying a verified export. Historical private receipts remain useful audit records after a reversal, but do not establish current label validity.

Local PostgreSQL helpers use loopback trust authentication. Other local processes can impersonate database roles. Reviewers with direct SQL access and database owners remain trusted; owners can bypass protections. These interfaces do not establish production authentication or authorize a public service.

## Case counts and splits

An unordered pair is one case, including reciprocal requests. Repeated listings, extraction versions and source copies need conservative grouping before any held-out evaluation. Grouping does not assert person identity or independent corroboration. This exporter conservatively places the entire corpus in one held-out group. It supplies no development subset, so the dataset cannot support independent training or prompt tuning. Source and person groups stay with the dataset. Unknown copy relationships and unknown identities remain limitations of the bounded corpus.

Unlabeled observations remain separate. An export with no eligible genuine decisions reports not-evaluable, with no successful owner accuracy claim and no provider dispatch for an empty evaluation. Genuine source inspection and rubric review remain owner actions tracked in issue #1.

## API and local commands

Apply observation and review migrations first. Migration 6 is additive. Its administrator-installed corpus mode is immutable; use a separate database for synthetic demonstrations. Use the existing restricted reviewer role for decisions. Never run synthetic seed or label examples against real observations.

```sh
# Administrator connection, explicitly chosen corpus mode:
clojure -M:labels migrate reviews_owner synthetic
# Reviewer connection after actually inspecting both rows:
clojure -M:labels decide data/pair-decision.edn
# Read-only export and verification, all output remains private:
clojure -M:labels history data/pair.edn data/private-pair-history
clojure -M:labels export data/export-options.edn data/private-label-exports
clojure -M:labels verify data/private-label-exports/RECEIPT_HASH.edn
# Fresh export and verified comparison using local providers only:
clojure -M:shadow run-reviewed data/reviewed-shadow data/export-options.edn test/fixtures/shadow-configs.edn
```

Commands use `FREEDIVING_DATABASE_URL`. Keep credentials outside input files and logs. The migration example deliberately selects synthetic mode. A real corpus requires an administrator's explicit `real` configuration; configuration itself creates no label or human review.

The Clojure interfaces are `freediving.evaluation-labels/migrate!`, `history`, `decide!`, `export`, and `verify!`. `history` takes the exact pair vector and returns its evaluation revision, observation review revisions, and append-only events; its CLI writes the private history to a content-addressed receipt. Export options are `{:rubric-version "your-reviewed-rubric-version" :max-labels 1000}`. The bound covers label history, including superseded events. An over-limit export fails rather than silently truncating history.

History and exports have a 10 MB encoded byte limit. Referenced artifacts are capped at 10 MB, observation payloads at 1 MB, and each observation's review history at 1 MB and 1,000 events. Statements have a ten-second deadline and locks a five-second deadline. These admission limits bound this local workflow; they are not an operating-system memory quota.

An explicit request has this shape. Replace references and revisions with actual inspected evidence; these placeholders are not runnable approvals:

```clojure
{:id "unique-pair-decision"
 :pair [{:job-id "EXACT_LEFT_JOB_HASH" :ordinal 0
         :candidate-id "EXACT_LEFT_CANDIDATE_ID"
         :source-sha256 "EXACT_LEFT_SOURCE_HASH"
         :artifact-sha256 "EXACT_LEFT_ARTIFACT_HASH" :page 1 :line 3}
        {:job-id "EXACT_RIGHT_JOB_HASH" :ordinal 5
         :candidate-id "EXACT_RIGHT_CANDIDATE_ID"
         :source-sha256 "EXACT_RIGHT_SOURCE_HASH"
         :artifact-sha256 "EXACT_RIGHT_ARTIFACT_HASH" :page 2 :line 7}]
 :base-revision 0 :review-revisions [0 0]
 :outcome :match
 :actor "reviewer-label" :reason "What evidence supports this exact pair"
 :rubric-version "your-reviewed-rubric-version"}
```

`:base-revision` is the pair's current evaluation revision. `:review-revisions` contains each observation's current review revision in request pair order. Exact retries preserve the event; a changed request cannot reuse its ID. Use a new ID and current revisions for a later `:no-match`, `:unknown`, or `:revoke` decision.

`freediving.evaluation/run-verified!` takes private root, trusted database URL, export receipt, configurations, and optional runtime provider credentials. `inspect-verified-run` requires the same database boundary to check freshness. The runner preserves content-addressed export receipts and provider traces with 0700 directories and 0600 files. Database verification happens before dispatch, after provider work, and on verified replay/inspection. A later database change can still occur after a check; each report describes a verified historical snapshot, not continuing authority.

Plain `run!`, plain `inspect-run`, and `evaluation-data/metrics` treat file owner labels as assertions. This also applies when inspecting older stored runs. Plain inspection recomputes an unverified metrics view, while retaining the original stored report hash for historical integrity. Use `evaluation-data/metrics-verified` with a database URL and receipt for verified metrics; maps containing `:verified? true` cannot substitute for the database.

```sh
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-labels
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH scripts/test-postgres.sh test-label-integration
clojure -M:test-shadow
```

All these demonstrations use synthetic observations and labels. The integration test compares rules, Jev-shaped HTTP and LLM-shaped HTTP on the same exported case, checks duplicate-free replay and rejects labels revoked before or during evaluation. It makes no live provider calls.
