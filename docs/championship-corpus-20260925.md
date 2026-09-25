# Isolated championship corpus, 25 September 2026

The audited restart sources now have a separate local PostgreSQL 17 corpus. It contains 55 extraction versions, 47 source hashes and 4,736 immutable observations. Every observation remains unreviewed and publication-ineligible. No identity, extraction validation, revision confirmation, event selection, policy activation or production cutover occurred. Remaining acceptance is tracked in [issue #8](https://github.com/jamiepratt/freediving-results/issues/8); the identity evaluation in issue #6 is separate.

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

## Verification and preservation

All 55 imports passed exact archived-source replay and artifact/source hash checks. Immediate unchanged retries skipped all 55 jobs. Independent database inspection compared all 4,736 candidate payloads and ordinals with the original retained artifacts, including parser/schema versions and artifact bytes. A separate restore of a PostgreSQL custom-format backup passed the same comparison.

All 2,442 fingerprinted original files remained unchanged. Older corpora were neither migrated nor used as ingestion destinations. Seven retained authority backup/report files were hash-checked separately. The existing live authority database rejected the ambient role, so this batch does not claim a fresh live authority snapshot; no access configuration was changed. Production was not accessed.

Normal deployment migrations 1-10 initialized only the new corpus. Twelve decision/proposal/label/correction/selection/cache tables are empty. The restricted public role sees zero results and zero event-coverage rows. Publication policy remains its initial version 1. Real-corpus metadata is not a review decision. Reviewer and correction roles are NOLOGIN; the inspection role has SELECT only and defaults to read-only transactions. The helper's loopback trust authentication remains a trusted-local limitation.

## Review readiness and source limitations

Read-only diagnostics found 604 rows with no automated publication blocker under the current policy. They still require genuine source inspection and explicit validation. All 4,736 rows remain ineligible. Automated readiness does not establish source accuracy, complete coverage or sporting identity.

All 3,483 AIDA versioned observations require separately authorized policy-2 activation and fresh validation. The two current indoor artifacts carry a source-semantics blocker across all 648 rows, including 412 unchanged distance rows. The underlying timing ambiguity concerns 236 rows; 105 STA rows retain conflicting metre headings and 94 2X50/4X50 rows retain an unlabeled first result column. Ninety-nine PDF rows also lack publication-required performance/status; reasons overlap. Printed tokens remain unchanged, without inferred units or durations.

The 44 legacy federation men's rows retain historical field-envelope and source-line limitations even though the automated readiness check passes. Their exact text coordinates, original artifact and source PDF remain available. No evidence was fabricated to upgrade that artifact.

The [dated inventory](championship-inventory-20260925.md) and [CMAS acquisition audit](cmas-timing-acquisition-20260925.md) retain the outstanding coverage evidence: 30 unavailable configured 2025 indoor PDF links, 42 unreconciled supporting result JSON views including one invalid-UTF-8 response, missing 2026 CWT men's PDFs and unresolved initial/continuation overlap, and unestablished category completeness. These are distinct from imported rows awaiting review.

[Source bindings](revision-relationships.md) cannot manufacture missing venue, round, session or participant evidence. A source name, start order, rank or changed hash does not establish a scoped attempt or confirmed revision. No real selection inventory or relationship decision was created.

## Private inspection and recovery

The batch checkout retains ignored `data/b11-evidence/OPERATIONS.md`, an exact `import-manifest.json`, import/replay reports, a row-addressable `review-index.jsonl`, original/copy fingerprints and an independently verified backup. Real names and source bytes are not Git fixtures. The index contains each exact job, ordinal, source/artifact hash, coordinate and diagnostic; it is not a held-out identity selection or score.

The dedicated database is `championship_b11` on loopback port 55511; the separate restore drill is `championship_b11_restore`. The private cluster is `data/b11-evidence/postgres17`. Read-only source inspection uses the retained `owner-config.edn` and `source_inspector` role at loopback port 8791. Startup verified the complete imported corpus; authenticated HTML/PDF evidence endpoints were checked for all five imported groups and signed out without decisions. Follow the private operations file for exact restart, capability handling, backup and stop commands, and the [source viewer documentation](local-source-inspection.md) for its trust boundary. Preserve the archive alongside database backups; extraction artifacts in a dump do not replace original source objects.
