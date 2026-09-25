# Novi Sad timing semantics and row readiness, 25 September 2026

Parser `cmas-2026-indoor-time/2` confines the substantive timing warning to timing candidates. The previous artifact-wide warning also blocked 412 independently headed distance rows. Publication policy is unchanged: unknown errors and malformed rows still block validation, and every row needs an explicit source review. [Issue #8](https://github.com/jamiepratt/freediving-results/issues/8) remains open.

## Source evidence

The retained 32-page senior/junior PDF and 34-page masters PDF contain 648 rows. Reproducing the source-only census, geometry and column checks confirms 412 distance rows on 33 pages and 236 timing rows on 33 different pages. All raw values, parsed values, coordinates, source lines, metadata and geometry remain unchanged in the new artifacts. Distance candidate maps are identical; timing candidates gain the row-local warning.

| Timing section | Rows | Established source claim | Unresolved meaning |
| --- | ---: | --- | --- |
| STA | 105 | Exact tokens beneath `Realized Distance (m)` and `Final Distance (m)` | Printed metre headings conflict with timing-shaped tokens. |
| 2X50 | 47 | Exact left and final tokens; `Final Result (time)` labels the final column | Left column has no label; component units and precision are unspecified. |
| 4X50 | 47 | Same heading distinction as 2X50 | Same missing column role and component semantics. |
| 8X50 | 37 | Exact token beneath `Final Result (time)` | Component units are unspecified, including the literal master token `07:52:05`. |

Six blank cells, ten literal zero cells, sixteen DSQ rows and four wrapped notes remain distinct. A left-column value with a blank final does not supply a final result. Different padding does not establish a penalty or conversion. Status, identity and missing categories are never inferred.

The [official event page](https://www.cmas.org/freediving-events/2026-cmas-world-championship-freediving-indoor-juniors-seniors.html) links [senior/junior](https://www.cmas.org/media/com_eventbooking/cmas-pack%20Seniors%20Juniors.pdf) and [masters](https://www.cmas.org/media/com_eventbooking/cmas-pack%20Masters.pdf) packs. Their page-7 starting-order text supports generic time for STA and speed, but supplies no applicable result-column legend, token-component definition or correction. This context does not resolve the result sheets. Corrected event results or an issuer explanation tied to these exact sections would be needed to resolve their conflicting or absent labels. Unknown units alone do not authorize a duration conversion.

## Version and authority boundaries

The executable `/1` parser and its artifact-wide warning are preserved for exact historical replay. Fresh `/2` jobs are distinct; unchanged repeats reuse them. Archived-source validation checks warning placement along with fields, page context, geometry and parser identity. Relabeling an artifact, stripping warnings or moving them between rows cannot create a valid import. Unsupported pages remain unsupported.

A separate diagnostic database contains only copies of the two old and two new versions: 1,296 versioned observations representing 648 source positions. Public diagnostic calls produce:

| Version | Distance ready | Timing ready | Eligible |
| --- | ---: | ---: | ---: |
| Preserved `/1` | 0 of 412 | 0 of 236 | 0 |
| New `/2` | 412 of 412 | 0 of 236 | 0 |

Readiness is an automated prerequisite, not an accuracy attestation. No real validation, identity decision, policy activation or event selection was created. The [B11 corpus](championship-corpus-20260925.md) stayed stopped and unchanged: its historical count remains 604 ready out of 4,736 unreviewed observations. The new versions were not imported there.

Private evidence is in the batch-15 checkout under ignored `data/b15-evidence/`: `source-audit/REVIEW-PACKET.md` and its 236-row ledger identify exact examples and missing evidence; `parent/diagnostic-index.jsonl` addresses every old/new row; `indoor-archive/` preserves original and new jobs. Source reproduction, replay, diagnostics and preservation scripts are retained. Real names and PDF bytes are not Git fixtures.

Validation reproduced the overblocking through public diagnostics before the fix. Core checks passed 182 tests/1,236 assertions; the isolated PostgreSQL suite passed 212 tests/1,205 assertions across 14 suites. Negative cases cover warning removal/movement, changed fields/page/context/identity, malformed mixed sections and legacy replay; synthetic validation/revocation grants no real authority. Independent review found no remaining defects. All 4,679 fingerprinted B07/B11 files remain unchanged; synthetic and diagnostic clusters were stopped. Three fresh supporting context captures retain HTTP metadata and hashes, with identical acquisition IDs on repeated registration. The two pack byte hashes match the first audit captures.

Broader source gaps, genuine review, policy activation and event cutover remain separate acceptance checkpoints in issue #8. The [Athens audit](athens-mirror-audit-20260925.md) and [AIDA scope audit](aida-scope-audit-20260925.md) describe their own unresolved evidence. This change establishes no complete championship coverage or publisher revision relationship.
