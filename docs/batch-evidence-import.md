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
digest before validating its 5,175 cited cells and 898 source positions.
If those packet bytes are unavailable, generate a separate private replacement
with `scripts/gia_2025_workbook_census.py --workbook ORIGINAL.xlsx
--expected-sha256 352ebb0c4119f35cb254d1a4b999e89ee86ed09c5ca3c81be7d60c33102f187b
--receipt-manifest ACQUISITION-MANIFEST.json --output REPLACEMENT.json`.
The explicit manifest must contain one matching HTTP 200 acquisition receipt
with the original byte count and source hash. Record the replacement packet's
own digest and provenance separately; matching position and cell counts do not
make it the missing packet. The pinned retained packet gate still requires the
historical digest. A replacement needs separate source-bound replay and owner
acceptance before it can stand in for that packet. To retain the related team
aggregate exclusion, place its verified
`408dc419f22536d319976077851563cbbe316891bab8b328d348df275b8b987f.xlsx`
beside the individual workbook; the same manifest supplies its receipt.
