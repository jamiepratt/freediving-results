# Retained AIDA source append guard

`deploy/retained_source_import_guard.py` checks the append of the two retained
June 3, 2026 AIDA artifact versions. Both use `aida-html/1`; their job and artifact
hashes differ. Each contains 209 positions: 103 selected women and 106 additional
unselected men. Source ingestion grants no review, identity, relationship,
publication, selection or sporting authority.

The `manifest` command consumes independently rehearsed, hash-pinned full rows
in JSON (`extractions` and `observations` arrays). It verifies source and artifact
hashes, exact job IDs, full ordinal coverage and row schemas. Supply the actual
retained source manifest hash, never a placeholder. The expected-row file must
be independently bound to that retained manifest by the importer rehearsal.
Only the new extraction's database-generated `imported_at` differs from the
rehearsal; verification requires it inside the declared apply window.

`capture` reads every `freediving` table in each supplied database, requiring
migration versions 1 through 23. It retains full existing-row SHA-256
fingerprints, schema metadata (including functions, grants, triggers, indexes
and views), and a separately pinned `comparison_activate.capture_guard` JSON.
It stores hashes and ingestion identifiers, without athlete/artifact payloads.
Use every source, canonical and public database present in the protected guard.
Each PostgreSQL snapshot is transaction-consistent; separate databases and the
owner/sporting guard do not share a distributed transaction. Freeze concurrent
writes for the append and compare fresh before/after checkpoints.

`preflight` declares exact remaining insert counts and refuses conflicting or
partial prior jobs. `verify` preserves every existing row fingerprint and all
unrelated tables, requiring all 2 expected extractions and 418 observations.
Zero-write replay is accepted. When source and public database names coincide,
`manifest --public-database <source-database>` explicitly binds both guard
aliases. Their exact count/hash summaries must match the captured database;
only these two ingestion table entries may change. Other public projections,
canonical tables, owner/sporting ledgers, capabilities, frozen files and grants
remain guarded. This does not alter the old deployment equality guard: capture a
fresh deployment checkpoint after the verified import.

Commands create output files exclusively with mode 0600. Every consumed
checkpoint/manifest/file requires its byte SHA-256 through the CLI. The helper
never executes ingestion, creates authorities, or rolls back database rows.
Apply through the normal source-only importer between preflight and verification.

The private report is registered separately at
`<owner-state>/aida-diff/config.json`. `comparison_activate.py capture` guards its
config, full report hash and permissions when present. Activation accepts only
that registered path through `--aida-diff-config`, renders the pinned report as
the service identity, and attaches `OWNER_EVIDENCE_AIDA_DIFF_CONFIG` to derived
env files. A failed read prevents activation. The checkpoint pins the report
without backing it up. Compatible rollback restores derived app/env files only;
changed report pins refuse rollback rather than restoring stale evidence. With
no registered diff config, existing deployment guards/checkpoints stay compatible.
