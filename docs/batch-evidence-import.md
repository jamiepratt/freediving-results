# Private batch evidence import

`freediving.batch-evidence-db` imports retained per-position replay records into
`freediving.batch_position_evidence`. This is a separate PostgreSQL evidence
store. It does not add legacy observations, reconcile athletes, accept an
extraction, or publish results.

Migration 19 is installed by the normal database migration path. It grants the
restricted `observations_app` role `SELECT` and `INSERT` on this table only.
Rows are append-only. A rerun skips an identical job; a conflicting job fails.
New source bytes or parser versions retain separate job revisions. The importer
verifies archived source bytes and acquisitions, derivation receipts and object
hashes, replay job identity, citation, position, candidate, role, and source
binding before starting its transaction. Any invalid record or interruption
rolls back that import call. Rerunning resumes from durable rows.

For an isolated local database after installing migration 19:

```clojure
(require '[freediving.batch-evidence-db :as evidence])
(evidence/import! restricted-jdbc-url verified-private-archive-root)
(evidence/inspect restricted-jdbc-url)
```

Result positions use `batch-observation` within this evidence table; rankings,
aggregates, and placeholders use `batch-evidence`. Unsupported and unresolved
positions use `batch-exception`. None is a distinct sporting-attempt count.
The import result reports created and skipped records. Use the batch replay
report for requests, cache reuse, coverage, and exception counts.

`scripts/test-postgres.sh test-batch-evidence-db` exercises this contract in a
disposable PostgreSQL cluster. The retained workbook gate is
`clojure -M:test-retained-workbook-batch` with the three private
`RETAINED_GIA_WORKBOOK`, `RETAINED_GIA_RECEIPT_MANIFEST`, and
`RETAINED_GIA_CENSUS_PACKET` paths set. It requires the exact retained packet
digest before validating its cited cells and 898 source positions.
