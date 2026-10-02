# Bounded discovery pass

`scripts/discovery_pass.py` checks explicitly configured dated HTML or JSON
indexes. It does not crawl an entire federation or prove ingestion coverage.
The declaration names federation, years, source families, route IDs and cutoff.
Each route is checked or records a gap. Every discovered in-scope lead must have
an acquired, gap or unsupported disposition before the pass says `complete`.
A request budget returns a `checkpoint` with unchecked routes or leads; run the
same config again to resume. Change `as_of` and `scope.cutoff` for a new pass.

```json
{
  "schema": "scoped-result-discovery/v1",
  "cutoff": "2025-01-01",
  "as_of": "2026-10-02",
  "scope": {
    "federation": "Example",
    "years": [2025, 2026],
    "source_families": ["results"],
    "routes": ["example-calendar-2026"],
    "cutoff": "2026-10-02"
  },
  "sources": [{
    "id": "example-calendar-2026",
    "publisher": "Example",
    "year": 2026,
    "source_family": "results",
    "index_url": "https://example.org/calendar",
    "index_representation": "html",
    "candidate_representation": "pdf",
    "allowed_host": "example.org",
    "context": {}
  }]
}
```

Run `python scripts/discovery_pass.py config.json private-output --request-budget 20`.
The output is private evidence. `frontier-<config hash>.json` stores route and
lead dispositions, including overlapping edges. `refresh-state.json` keeps
publisher check dates and source version hashes. `summary.json` separates
request reservations, archive reuse, gaps, unchecked work and stopping state.
Source bytes and acquisition receipts remain in `archive/`; the lease database
there is shared by `AcquisitionClient` processes for host pacing. Use one writer
per frontier checkpoint; independent workers may share the lease database.

Indexes are checked each new pass. Result refresh uses
`discovery-refresh-policy/v1`: provisional sources after 1 day, recent or
undated sources after 7 days, older stable sources after 90 days; recent means
publisher publication date within 365 days. These are initial configurable
cadences, not observed publisher schedules. A verified archive hash never
advances `last_publisher_check`. A due refresh retains any changed byte version.
When no verified archive exists, retrieval uses request budget even if refresh
was not due. HTTP 304 can be recorded with external response evidence, but this
acquisition client does not send conditional requests.

Configured `results`, `rankings` and `names` families retain index citations.
For JSON index rows, `publication_date`, `provisional`, `period`, `category`,
`rules_url`, `script` and `locator` can be retained as source metadata. These
fields are evidence, not a person match, ranking interpretation or dive count.
Only explicitly dated links become leads through this interface. Undated links
are counted as a route coverage gap; they need a separate supported route
contract. Image sheets, workbooks and arbitrary page traversal are
unsupported here and remain explicit route gaps. The 2025-2026 route roster
keeps its own date limit and does not become a historical census through this
pass.
