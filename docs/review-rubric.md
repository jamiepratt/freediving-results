# Private candidate review rubric

Implementation guidance awaiting owner review. These are not owner labels, identity conclusions, calibrated confidence scores, or permission to publish. Retrieval never creates an approval. Remaining pilot work is tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

| Review category | Required evidence | Review API category |
| --- | --- | --- |
| Mechanical extraction repair | Exact source page/line proving an extraction error in glyphs, spacing or columns; immutable original and proposed value | `:extraction-repair` |
| Normalization | Explicit reversible rule for a name format; original spelling remains in the observation; no inferred identity or nationality | `:name-normalization` |
| Identity match | Meaningful local observation anchor, registered cross-source references, and contextual evidence beyond matching names; inspect contradictory context | `:identity-matching` |
| Substantive correction | Exact source-backed reason for altering a substantive value, before/after values, unresolved contradictions | `:substantive-correction` |
| Abstain / no-match | Describe missing, ambiguous or conflicting evidence; no supported candidate in this corpus does not establish distinct identity | Identity outcome `:unknown` or `:unmatched`, when an owner elects to propose one |

A candidate comparison bucket is not a person. Case, diacritic, spacing, token-order, and shared-token signals only retrieve candidates. Preserve all exact source names, including damaged glyphs. A damaged name resembling a complete name may be reviewed as a candidate, never silently repaired or merged. Missing parsed names require abstention. CMAS1/AIN and representation codes do not establish nationality.

Duplicate acquisitions of identical source bytes, repeated listings, and extraction versions remain grouped as source evidence. Their row count is not a corroboration count. Distinct source documents may share upstream data and are not automatically independent truth. Review exact hashes, acquisitions, observation versions, original values, page/line evidence, comparison configuration, and uncertainty details.

Owner review prompts are approve, reject, no-match, and needs-more-evidence. These prompts are not stored decisions in exported packets. The review API records approve/reject against an explicit proposal; no-match is an identity outcome, and needs-more-evidence means withhold approval and gather evidence. A rejected identity proposal retains its proposed local anchor. Existing approval reversals remain append-only. Review database capability, not possession of an HTML file, controls decisions.

## Offline export

Set `FREEDIVING_DATABASE_URL` to the private local database. Write a single EDN map such as `{:offset 0 :limit 50 :max-observations 10000}` and run `clojure -M -m freediving.packets export REQUEST.edn OUTPUT-DIRECTORY`. The parent directory must already exist. The new output directory is 0700 and `packets.edn` / `packets.html` are 0600. Keep outputs in ignored private storage. Symlinks, including ancestors, are rejected; use their real filesystem paths.

Identical reruns are accepted. Conflicting output refuses overwrite. The HTML escapes source content and works offline. Structured summaries aid review; expandable details retain complete machine-readable provenance. The EDN export preserves the full result. Check `:total`, `:offset`, `:limit`, and `:has-more?`; each page is bounded, while candidate groups inside a case are never silently truncated. Corpus bounds fail explicitly rather than produce partial retrieval. Use the same immutable corpus and configuration across pages and compare corpus IDs.
