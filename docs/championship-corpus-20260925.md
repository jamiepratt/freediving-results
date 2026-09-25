# Isolated championship corpus, 25 September 2026

The current B16 isolated PostgreSQL 17 corpus contains 57 extraction versions, 47 source hashes and 5,384 immutable observations. It preserves B11's 55 versions and 4,736 rows, adding two indoor parser-2 versions with 648 repeated-version rows. These are not 648 newly discovered attempts. B11 remains a separately preserved baseline. Every observation remains unreviewed and publication-ineligible. No identity, extraction validation, revision confirmation, event selection, policy activation or production cutover occurred. Remaining acceptance is tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8); the identity evaluation in issue #6 is separate.

The original B11 baseline was:

| Imported evidence | Versions | Versioned observations |
| --- | ---: | ---: |
| AIDA retained attempts and recaptures | 24 | 3,483 |
| CMAS 2025 depth timing PDFs | 20 | 352 |
| CMAS 2025 federation CWT PDFs | 2 | 73 |
| CMAS 2026 depth PDFs | 7 | 180 |
| CMAS 2026 indoor PDFs | 2 | 648 |
| Total | 55 | 4,736 |

AIDA covers 16 exposed views and 1,942 source rows; the other 1,541 rows are repeated acquisition versions. All 24 versions remain distinct, including eight historical truncated DOM captures and their separate complete recaptures. The CMAS counts describe source-document rows, not deduplicated attempts. The corpus has 3,195 source-position candidate IDs; this is not a sporting-identity census.

Six complete private archives were copied into a consolidated archive with 742 file comparisons and no conflicting bytes overwritten. Historical derivations remain retained. Another 45 unique historical CMAS jobs, represented by 94 archive copies and 698 rows, are explicitly outside this database import: 17 older depth jobs with 275 rows, two older distance jobs with 412 rows, 24 unsupported zero-row jobs and two original Novi Sad jobs with 11 rows. Zero extracted rows never establish zero participation. Previous Athens pilot artifacts are not substituted for the restart's unreconciled 2025 indoor sources.

## B11 verification and preservation

All 55 imports passed exact archived-source replay and artifact/source hash checks. Immediate unchanged retries skipped all 55 jobs. Independent database inspection compared all 4,736 candidate payloads and ordinals with the original retained artifacts, including parser/schema versions and artifact bytes. A separate restore of a PostgreSQL custom-format backup passed the same comparison.

All 2,442 fingerprinted original files remained unchanged. Older corpora were neither migrated nor used as ingestion destinations. Seven retained authority backup/report files were hash-checked separately. The existing live authority database rejected the ambient role, so this batch does not claim a fresh live authority snapshot; no access configuration was changed. Production was not accessed.

Normal deployment migrations 1-10 initialized only the new corpus. Twelve decision/proposal/label/correction/selection/cache tables are empty. The restricted public role sees zero results and zero event-coverage rows. Publication policy remains its initial version 1. Real-corpus metadata is not a review decision. Reviewer and correction roles are NOLOGIN; the inspection role has SELECT only and defaults to read-only transactions. The helper's loopback trust authentication remains a trusted-local limitation.

## B11 review readiness and source limitations

Read-only diagnostics found 604 rows with no automated publication blocker under the current policy. They still require genuine source inspection and explicit validation. All 4,736 rows remain ineligible. Automated readiness does not establish source accuracy, complete coverage or sporting identity.

All 3,483 AIDA versioned observations require separately authorized policy-2 activation and fresh validation. The two current indoor artifacts carry a source-semantics blocker across all 648 rows, including 412 unchanged distance rows. The underlying timing ambiguity concerns 236 rows; 105 STA rows retain conflicting metre headings and 94 2X50/4X50 rows retain an unlabeled first result column. Ninety-nine PDF rows also lack publication-required performance/status; reasons overlap. Printed tokens remain unchanged, without inferred units or durations.

The 44 legacy federation men's rows retain historical field-envelope and source-line limitations even though the automated readiness check passes. Their exact text coordinates, original artifact and source PDF remain available. No evidence was fabricated to upgrade that artifact.

The [dated inventory](championship-inventory-20260925.md) and [CMAS acquisition audit](cmas-timing-acquisition-20260925.md) retain the outstanding coverage evidence: 30 unavailable configured 2025 indoor PDF links, 42 supporting result JSON views, subsequently inventoried as 792 transport rows with semantic discrepancies and one invalid-UTF-8 string still unresolved, missing 2026 CWT men's PDFs and unresolved initial/continuation overlap, and unestablished category completeness. These are distinct from imported rows awaiting review. The [batch 12 gap audit](championship-source-gaps-20260925.md) did not change this database or substitute the historical Athens mirror.

[Source bindings](revision-relationships.md) cannot manufacture missing venue, round, session or participant evidence. A source name, start order, rank or changed hash does not establish a scoped attempt or confirmed revision. No real selection inventory or relationship decision was created.

## B11 private inspection and recovery

The batch checkout retains ignored `data/b11-evidence/OPERATIONS.md`, an exact `import-manifest.json`, import/replay reports, a row-addressable `review-index.jsonl`, original/copy fingerprints and an independently verified backup. Real names and source bytes are not Git fixtures. The index contains each exact job, ordinal, source/artifact hash, coordinate and diagnostic; it is not a held-out identity selection or score.

The dedicated database is `championship_b11` on loopback port 55511; the separate restore drill is `championship_b11_restore`. The private cluster is `data/b11-evidence/postgres17`. Read-only source inspection uses the retained `owner-config.edn` and `source_inspector` role at loopback port 8791. Startup verified the complete imported corpus; authenticated HTML/PDF evidence endpoints were checked for all five imported groups and signed out without decisions. Follow the private operations file for exact restart, capability handling, backup and stop commands, and the [source viewer documentation](local-source-inspection.md) for its trust boundary. Preserve the archive alongside database backups; extraction artifacts in a dump do not replace original source objects.

## Subsequent AIDA scope audit

The [batch 13 audit](aida-scope-audit-20260925.md) replayed all AIDA rows and produced exact unapproved dated-view descriptors. An explicit partial-only contract removes the need to fabricate venue/round/session fields for this narrower scope. Separate official context captures expose one schedule-result exception; they grant no new authority. B11 remained stopped and unchanged throughout that audit.

The [batch 15 indoor audit](indoor-semantics-audit-20260925.md) creates separate parser-2 artifacts with timing warnings confined to timing rows. In an isolated diagnostic copy, all 412 distance rows pass automated readiness and all 236 timing rows remain blocked; none is eligible. B11 was not started or changed, so its stored versions and the historical readiness counts above remain unchanged.


## B16 synchronization and current review checkpoint

B16 restores a freshly verified B11 backup into `championship_b16` in the same private loopback cluster. This preserves B11 as an unchanged recovery baseline and avoids replacing any retained version. The copied corpus retains its original real-corpus identifier; it is a local snapshot, not a separately authorized review population. Two exact `cmas-2026-indoor-time/2` jobs were imported through the public observation API:

| Job | Versioned rows | Structurally ready distance rows | Blocked timing rows |
| --- | ---: | ---: | ---: |
| `b035efaef9e24ba8cee6e2b5cbcfa85416702068713442098dd7bfe1259fba65` | 478 | 295 | 183 |
| `1856ecc03a0935e946c36a17dd1d961dff64a82e66bde7f8a2c33c2a42c52d38` | 170 | 117 | 53 |

All 57 stored artifacts and 5,384 payloads match their retained originals, including ordinals and parser/schema versions. Both new imports immediately replayed as skipped. A new backup restored into `championship_b16_restore` passed the same full comparison. The original B11 database still matches all 55 original artifacts and 4,736 payloads. Copy verification covers 678 archive-file comparisons. The original 2,442 source files and original B11 dump hash remained unchanged.

Current diagnostics contain 1,016 structurally ready versioned rows: the historical 604 plus 412 new distance rows. They do not count unique sporting attempts. All 5,384 rows remain unreviewed and ineligible, including every structurally ready row. Old indoor parser-1 versions remain retained with their historical blockers. The 44 legacy men's rows still need special care for incomplete historical field envelopes; readiness does not repair that evidence. All 3,483 AIDA rows still require separate policy-2 authorization and fresh validation. The 236 new timing rows still need an authoritative source explanation of printed units, headings and result columns.

Review priority is recorded privately by exact job and ordinal: current parser-2 distance evidence first, other structurally ready evidence next, legacy men's rows with an explicit evidence caveat, AIDA pending the separate policy checkpoint, then blocked and retained historical versions. This order is a review aid, not authority, a selected publication population, identity scoring or deduplication. Missing official sources and unresolved event coverage remain separate source gaps, not parser failures. No Athens mirror artifact was imported.

The B16 checkout retains ignored `data/b16-evidence/OPERATIONS.md`, the complete copied archive, old/new manifests, import and restore checks, backup hashes, a prioritized all-version `review-index.jsonl`, and a separate `current-indoor-review-index.jsonl` containing all 648 current parser-2 rows. The operations file separates owner review from publisher/source gates. Both corpus and restore have zero decision/proposal/label/correction/selection/cache rows, zero public results/coverage and initial publication policy 1. No production service was contacted or changed. The shared private PostgreSQL cluster is stopped after verification. Follow the B16 operations file to restart it safely and inspect only the B16 snapshot.
