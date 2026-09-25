# AIDA dated-view scope audit, 25 September 2026

The three official event pages account for all 16 retained AIDA result dates. All 24 extraction versions and 3,483 versioned rows pass the existing typed HTML scope replay. These are source diagnostics, not extraction validation, identity review, revision confirmation or publication approval. [Issue #8](https://github.com/jamiepratt/freediving-results/issues/8) remains open.

## Official context

| Event | Official scheduled competition dates | Venue evidence |
| --- | --- | --- |
| [Wakayama 4349](https://www.aidainternational.org/EventPage/4349) | June 28 DYNB; June 29 DNF; July 1 STA; July 2 DYN, 2025 | Explicitly names Akibasan Park Prefectural Swimming Pool |
| [Limassol 4350](https://www.aidainternational.org/EventPage/4350) | September 23 CNF women; 24 CNF men; 25 CWTB women; 26 CWTB men; 28 FIM women; 29 FIM men; October 1 CWT men; 2 CWT women, 2025 | Describes a 170m-depth competition site one mile from the beach and vessel support; no unique named venue established |
| [Budapest 4852](https://www.aidainternational.org/EventPage/4852) | June 2 DYNB; June 3 DNF; June 5 STA; June 6 DYN, 2026 | Explicitly names Duna Arena pool Complex, 1138 Budapest, Népfürdő u. 36 |

Each schedule date links an official StartList route with a `day_index`. All 16 links are labelled `Pre. Results`; they do not establish finality. Competition-day labels and navigation selectors are not federation-assigned round, session, bib or attempt IDs.

The complete browser response bodies and rendered DOMs are retained as six separate supporting archive objects. Their schedule and venue sections agree exactly after HTML text normalization. Recorded hashes, lengths, retrieval timestamps, final URLs and exact schedule markup support replay. Retrieval time is not a publisher revision timestamp. EventPage routes came from the assignment and use explicit self-discovery provenance; the schedules supply the discovered StartList links. Direct scripted retrieval of Wakayama returned 403; ordinary internal-browser navigation returned 200 without an access challenge.

These are browser-decoded response bodies, not compressed wire captures. DOMs were captured in bounded chunks and checked against full character counts and closing markup. Three 1280x720 venue screenshots retain rendered context. Twenty-five original raw response files, including a supplementary pre-recapture file outside the 24-version inventory, contain neither schedule nor venue sections. Their navigation links do not make external context part of an old extraction.

## Full-row key audit

All 3,483 rows expose the eight existing typed fields: federation, event ID, event name, selected date, own-row discipline, gender/category, source name and exact official profile href. No row supplies a typed venue, round, session, bib or attempt ID. The 16 dated views contain 1,942 rows; eight repeated extraction versions account for another 1,541 rows. Repeated versions have identical raw fields and diagnostic key sets. This does not confirm supersession or authorize choosing a version.

The internal tuple of event ID, selected date, discipline, category and profile href is unique within every retained artifact. Even profile href alone has no duplicate within a dated view. These tuples are derived diagnostic keys, not publisher-assigned attempt IDs. Profile hrefs refer to source profiles, not reviewed cross-source athlete identities.

One same-event, discipline, category and profile tuple appears on two dates: a men's FIM source profile in Limassol on September 29 and October 2. The October 2 row also differs from that date's CWT-women schedule label. Full schedule comparison found exactly this one exception among both the 1,942 representative rows and all 3,483 versioned rows. It is not safe to inherit discipline or gender from a schedule, collapse these dates, or infer a correction, rescheduled attempt or additional round. Exact private row references retain the unresolved case.

## Scope contract outcome

The [explicit dated-view contract](event-selections.md#explicit-aida-dated-views) makes this narrower evidence usable without invented venue, round or session values. It is opt-in and partial-only, preserves the existing full-scope contract, rejects ambiguous complete-artifact keys and supplies no automatic revision or selection decision. The final read-only validator passes all 24 retained artifacts and 3,483 rows. Exact unapproved descriptors are in `key-audit/unapproved-descriptors.edn`; they were not submitted to the real database.

Supporting EventPage objects are separately hashed and archived. They are not new extraction context, mutable URL bindings or automatic overrides of original evidence. No external-context binding API was introduced. Tests reject unsupported external paths and supplied venue/round/session/attempt/bib values under the dated-view contract.

## Review packet and preservation

The private batch checkout retains `data/b13-evidence/context-audit/OWNER-PACKET.md`, the 16-row `schedule-index.json`, exact schedule exceptions, and the six-object `supporting-archive/`. `key-audit/rows.jsonl` addresses every retained observation with exact job, ordinal, candidate, source and artifact references; `views.json`, collision reports and preservation reports retain the full census. These are inspection inputs, not executable approvals.

Existing response, source, artifact and acquisition files remain unchanged. The separate B11 real database was not started or modified; its last verified state remains 55 versions, 4,736 unreviewed observations, empty authority tables and zero public rows under policy 1. The [corpus audit](championship-corpus-20260925.md) records that verification. Fresh source context supplies no inherited approval, policy activation or public cutover.

Source-specific unresolved interpretation includes the Limassol schedule exception, actual round/session/attempt meaning and publisher revision ordering/finality. Missing CMAS sources, indoor timing semantics, genuine reviews and authorized cutover remain separate acceptance gates in #8. This audit does not convert them into a blanket owner-approval request.

## Validation

Synthetic TDD exercised opt-in matching, full-artifact missing fields and duplicate participants, unsupported/malformed table census, partial coverage, required validation and relationship decisions, rollback, spelling/case collision bypasses and retained-baseline restrictions. Final narrow suites passed 32 revision tests / 156 assertions and 29 selection tests / 113 assertions. Combined verification passed 172 core tests / 1,154 assertions and 210 PostgreSQL tests / 1,186 assertions across 14 isolated suites. Ordered Clojure repair/lint and documentation link checks passed. All test clusters stopped and removed themselves.

Independent review reproduced two ambiguity/coverage bypasses before their regression fixes and found no remaining blockers afterward. The coordinator independently checked six exact archive copies, 25 original response hashes and all 3,483 descriptor envelopes, then reran the final daily validator across all 24 artifacts. None of these synthetic decisions or automated checks is a genuine source-review attestation.
