# Freediving results: local evidence and extraction

This slice registers existing source bytes and explicit acquisition provenance in a private local archive, then produces private page-preserving extraction artifacts and structured CMAS CWT men, AIDA Wakayama rankings and CMAS Athens DNF/DYNBF candidates. It does not implement the PostgreSQL observations database, resolve identities, or publish data. Pilot acceptance and remaining scope live in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1), which remains open.

## Run

Requires Java 17+ and the Clojure CLI. PDF extraction and its tests also require Poppler `pdftotext` and `pdfinfo` on PATH (verified with 25.05.0). Clojure is pinned in `deps.edn`; the legacy adapter also uses pinned `data.json`; no running services are needed. First run downloads Maven dependencies.

```sh
clojure -M:test
mkdir -p data
clojure -M:archive import data/archive test/fixtures/source.txt test/fixtures/manifest.edn
clojure -M:archive inspect data/archive ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
```

The committed fixture contains three synthetic bytes (`abc`), not a real results document. `/data/` is ignored. Keep real evidence and manifests under ignored private paths. The archive creates its own private root, but its parent directory must already exist. Use a dedicated archive path; do not use your home directory or an existing general-purpose directory. On macOS, `/tmp` and `/var` are symlinks: choose a real path or use `pwd -P` in the intended parent.

Both commands emit one EDN result. `import` returns `:sha256` and `:acquisition-id`. `inspect` verifies stored bytes and records, and returns `:artifact-path` plus `:acquisitions` containing each acquisition ID and its manifest. Errors exit nonzero; the API throws. A known artifact with no acquisition records indicates registration was interrupted before provenance publication; repeat the original import.

## Manifest contract

The example at `test/fixtures/manifest.edn` is the complete schema. Every key is required; extra keys are rejected.

| Key | Required value |
| --- | --- |
| `:sha256` | Lowercase 64-character SHA-256 of the original bytes |
| `:discovery-url` | Observed HTTP(S) page or URL where the source was discovered |
| `:final-url` | Observed final HTTP(S) acquisition URL |
| `:acquisition-method` | Nonblank description of how the bytes were acquired |
| `:retrieved-at` | ISO-8601 timestamp with UTC offset |
| `:content-type` | Observed MIME type, optionally with parameters |
| `:publisher` | Nonblank name of the party publishing the acquired copy |
| `:relationship` | `:publisher`, `:mirror`, or explicitly `:unknown` |
| `:mirror-of` | Identified original publisher or source for `:mirror`; otherwise `nil` |

URLs with user credentials, query strings, or fragments are rejected to reduce accidental credential storage. Do not place secrets, session data, or access tokens in any field or source file. URL paths and free text are not credential scanners. A source that needs a query URL cannot currently be represented faithfully; do not strip meaningful URL information merely to bypass validation.

These declarations record acquisition evidence; validation does not prove a publisher claim or fetch a URL. MIME type is not inferred from the bytes. Mirror links are recorded as supplied, never inferred from URL equivalence. Different acquisitions with the same content hash share one artifact and are not independent evidence.

## Legacy JSON import

`freediving.legacy/import!` and the CLI import a JSON array of acquisition entries, with an explicit evidence directory and a private EDN supplement:

```sh
clojure -M:legacy data/archive /absolute/evidence /absolute/manifest.json /absolute/config.edn
```

```clojure
{:version 1
 :entries {0 {:values {:publisher "Example federation"
                      :acquisition-method "direct HTTP download"
                      :relationship :publisher
                      :mirror-of nil}
              :rationale "Acquisition log identifies publisher and method."}}}
```

Entry indices are zero-based. Supply facts only when supported by recorded evidence. Config entries cannot overwrite observed mapped fields. Unknown indices, unsupported config versions, malformed JSON, or a non-array top level fail the command. An empty config is `{:version 1 :entries {}}`.

The version 1 mapping is `requested_url` to `:discovery-url`, `resolved_url` to `:final-url`, `fetched_at` to `:retrieved-at`, `content_type` to `:content-type`, and `sha256` to `:sha256`. When `requested_url` is absent, `source_url` supplies discovery only. `archived_at`, browser `acquisition`, `authentication`, `source_kind`, and extraction fields remain original evidence; the adapter does not reinterpret them as retrieval timestamps, acquisition methods, or publisher claims. Browser records lacking final URL or MIME remain incomplete. Missing `:mirror-of` is explicit even when an evidence-backed supplement will eventually set it to nil.

Each entry produces `:imported`, `:skipped`, `:missing-fields` with field names, or `:rejected` with a machine-readable reason. Invalid entries do not prevent subsequent entries from being processed. Source files must be regular nonsymlink files beneath the explicit evidence directory; absolute entry paths and parent traversal are rejected. Declared byte counts, when present, and required SHA-256 hashes are verified. Canonical archive validation still rejects unsupported URLs, MIME types, timestamps, or relationships. Generic `:invalid-source-or-manifest` intentionally omits exception contents. CLI stdout contains only IDs, private storage paths, counts and statuses; detailed original fields stay in private lineage.

The archive retains exact manifest and config bytes under `evidence/<sha256>`. Each entry has a deterministic lineage record linking those hashes and its index to the original entry, mapping/version, complete supplement/rationale and normalized fields. Distinct manifest/config bytes produce distinct lineage even when the acquisition is unchanged. All unknown original fields are retained. Result reports are also content-addressed; the returned `:report-path` identifies the persisted report (without its own self-reference). Repeated imports verify evidence integrity and return skipped acquisitions without duplicating objects, acquisition records or identical lineage. Changed provenance creates separate acquisitions sharing content. The existing archive schema is unchanged.

Evidence uses the archive's private atomic storage and cooperating-process lock. Lineage is saved before acquisition registration; interruption can leave pending lineage or an acquisition without a final report. Repeat the same command to complete it. Report counts describe that invocation, not a historical transaction. Storage failures can stop an invocation; malformed top-level inputs have no trustworthy entry enumeration and fail as a whole after original bytes are retained. No rollback or power-loss durability is promised. JSON, config and size checks currently read complete files into memory; this adapter is intended for the bounded local pilot.

## Private PDF extraction

Extraction consumes registered source hashes; it never fetches documents. It uses the installed Poppler `pdftotext` tool and records its version. Keep source PDFs and extraction artifacts in the ignored private archive. This bounded worker reads complete files into memory and does not run OCR.

```sh
clojure -M:extract data/archive SOURCE_SHA256 data/extraction-options.edn
```

The options file is `{:actor "local-owner" :config {}}`. The API is `(freediving.extraction/extract! archive-root source-sha256 options)`. `:config` is retained processing metadata, not a set of tool flags; extraction uses fixed layout and UTF-8 settings. Changing the actor, config, parser/tool version or acquisition/evidence snapshot creates a distinct job. Repeating an unchanged job verifies and reuses its artifact. The receipt contains `:job-id`, `:artifact-sha256`, private `:artifact-path` and `:run-status` (`:created` or `:skipped`).

The private EDN artifact records the original PDF hash, acquisition manifests, retained evidence hashes, processing time and actor, tool/parser versions and config. The evidence snapshot includes all retained legacy evidence in that archive, including original manifest/config bytes and lineage; it is not a claim that every item supports each candidate. Exact tool text remains separate from parsed values, including page delimiters in `:raw-text`.

Extraction serializes cooperating writers with the archive lock. A private pending receipt references the completed artifact before final job publication; retry resumes that artifact after interruption. Verified unreferenced derived objects and recognized staging files are reclaimed under the lock. Corrupt content is rejected. The optional fourth API argument `{:on-progress callback}` receives `:extraction-artifact-ready` after the pending receipt is saved and before final publication; callback failure leaves a resumable job. Guarantees cover process interruption on trusted POSIX storage, not power-loss durability.

The first structured parser targets the 2025 CMAS outdoor CWT men seniors layout. It preserves source names and event representation codes, including `CMAS1` and `AIN`, without assigning citizenship or merging identities. Attempted depth, final depth, penalties, status and notes remain distinct. Missing values remain unknown: a blank status is not an inferred success, and units absent from the PDF are not invented. Extracted text is evidence, not an approved spelling correction.

Artifacts retain exact extracted text by page with 1-based text line coordinates. These are coordinates in the extraction output, not PDF bounding boxes. Other layouts receive `:unsupported-needs-parser`; their result counts remain unknown. Blank text pages receive `:needs-OCR`. Nonblank lines in the supported layout are either classified as headers/footers or retained as parsed/unparsed candidates. Reconciliation records page, candidate, parsed and unresolved counts; it does not establish completeness through automated counts alone.

The versioned AIDA Wakayama DYN export parser retains wrapped name fragments and their exact Unicode spelling, then records joining fragments with spaces as a mechanical extraction repair. It does not remove hyphens, normalize names, infer citizenship from the source's `Nationality` column, or deduplicate equal ranks. Result and announced distances, explicit metre units, points and penalties are separate fields. Zero points do not imply disqualification; absent cards and statuses remain unknown. The browser print timestamp is retained as evidence, not used as the event date.

AIDA artifacts use schema 2 and parser `aida-wakayama-ranking/1`; existing CMAS and unsupported artifacts retain schema 1 and their existing parser identity. Detection runs on extracted text before job lookup, so repeat calls still invoke `pdftotext` but reuse the verified stored artifact. A formerly unsupported AIDA job is retained alongside the new version. AIDA candidates carry all constituent `:source-lines`, raw name fragments, repair records and explicit unresolved reasons; consumers must branch on the artifact schema instead of assuming the CMAS single-line candidate shape.

Section metadata is scoped to each printed ranking. In the inspected 15-page source, the first seven pages explicitly identify DYN and Female; the following eight-page ranking omits those labels, so its discipline and category remain unknown. The private source has 182 candidates (86 in the labelled section and 96 in the unlabelled section), matching independent page rendering and text inspection. All 182 remain unresolved pending owner review and missing metadata evidence. This is extraction reconciliation, not 182 reviewed identity cases. No original PDF, real extracted rows or private reconciliation artifacts are committed.

The Athens parser `cmas-athens-distance/2` uses schema 3 for the 2025 indoor PDF. Its supported families are DNF and DYNBF distance tables: printed date/category, realized and final distances, explicit metre units, representation codes, notes and explicit DQ/DNS statuses. Decimal-comma tokens remain separate from decimal values. Blank statuses and absent penalties remain unknown; distance differences do not become inferred penalties. Header-only continuation pages retain section evidence and require their own table/unit headers. New or malformed section boundaries prevent metadata from leaking into later tables.

Athens reconciliation is explicitly partial. Counts apply only to supported pages; each unsupported page has unknown candidate/parsed/unparsed/unresolved counts and remains preserved as exact text. The inspected source has 162 DNF result rows on pages 1-11, plus two detached `fi` extraction fragments retained as unparsed candidates on page 3. Per-page result counts are 3, 10, 41, 9, 41, 15, 7, 19, 6, 8, 3. All 164 candidates remain unresolved. Rendered pages and an independent row inventory were compared; this is extraction validation, not owner identity review. Extracted names, including the known missing-ligature anomaly, are unchanged.

DYNBF pages 12-22 contain 177 result rows, with per-page counts 4, 10, 41, 13, 46, 16, 9, 19, 6, 9, 4. Of these, 176 are parsed; one damaged multiline name remains an unparsed candidate with both original source lines retained. No name ordering or spelling correction is inferred. Final-only DNS distances use printed column alignment; blank realized distances stay unknown. DOLPHIN KICK and WALL AT START remain notes, with no numerical penalty inferred. Printed page-number footers are classified separately. All 11 pages were rendered and independently reconciled row by row.

The combined Athens result is 341 candidates: 338 parsed rows, one unparsed result row and two detached DNF fragments. All remain unresolved. Multiline candidates add `:source-lines`; consumers must use those constituent lines for coverage instead of counting only the starting coordinate. The prior DNF candidates remain unchanged, and the earlier `cmas-athens-dnf/1` artifacts are preserved under their original jobs.

Pages 23-67 remain unsupported: SPEED 8X50 (23-30), SPEED 2X50 (31-39), STA (40-48), SPEED 4X50 (49-56) and DYN (57-67). Their counts are not included in supported-page totals. DYN includes merged ranks and shared multiline notes requiring separate handling. Ambiguous wrapped/adjacent lines and unrecognized notes in supported tables remain unparsed; the parser does not silently deduplicate repeated ranks or source listings. Existing CWT/AIDA job identities and artifacts are unchanged; old unsupported Athens artifacts remain alongside new versioned jobs. Remaining parser scope is tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

All candidates remain unreviewed and publication remains blocked. No command publishes results or approves corrections. The database, owner review workflow, additional parsers and pilot acceptance remain tracked in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Public API

```clojure
(require '[freediving.archive :as archive])
(def manifest (archive/read-manifest "test/fixtures/manifest.edn"))
(archive/register! "data/archive" "test/fixtures/source.txt" manifest)
(archive/inspect "data/archive" (:sha256 manifest))
```

An acquisition ID is SHA-256 over the UTF-8 canonical EDN manifest (sorted keys). Map ordering does not affect identity. Repeating the same manifest is idempotent and does not rewrite existing artifacts or records. Changing any provenance field creates another acquisition; this preserves the supplied acquisition descriptions, including timestamp spelling, rather than attempting semantic deduplication.

An optional fourth argument to `register!`, `{:on-progress callback}`, observes `{:phase :artifact-ready :sha256 ...}` after verified artifact publication and before acquisition publication. Callback exceptions interrupt registration; retrying the same input completes it. The callback runs while the archive lock is held and must not call archive operations recursively.

## Storage and recovery guarantees

The archive contains `objects/<sha256>`, `acquisitions/<acquisition-id>.edn`, a `.lock`, and a `tmp/` staging directory. Object hashes and acquisition IDs are suitable opaque references for later database records or Agent-o-rama work. Archive metadata is not the results database.

Registration streams bytes to a private staging file, verifies that staged hash against the manifest, forces the file contents, and atomically renames it. Provenance is published in a separate atomic step. A process-wide lock and OS file lock serialize cooperating readers/writers, including separate JVMs. Interrupted writes cannot publish a partial file. Retrying removes recognized private staging remnants and completes missing provenance. Existing corrupt content or altered provenance causes failure; import never silently overwrites it. Inspection also verifies integrity and rejects unknown hashes.

Directories are created with POSIX `0700`; files with `0600`. Existing archive entries with group/other permissions are rejected. Parent traversal, symlinks anywhere along archive/source paths, nonregular files, malformed manifests, and mismatched hashes are rejected. Use a POSIX local filesystem supporting atomic rename and file locks. The archive assumes a trusted owner and trusted parent directory; it is not protection against an adversarial process changing filesystem paths concurrently. Hard links and ACL-based access are outside this POSIX-mode check.

Recovery is tested across abrupt JVM termination and concurrent processes. This is a process-interruption guarantee, not a power-loss or filesystem-failure durability guarantee: parent directory entries are not fsynced. Restore/backups, VPS transfer, and production recovery acceptance remain outside this slice in issue #1.

## Development checks

```sh
clj-nrepl-eval --discover-ports
clj-paren-repair src/freediving/archive.clj test/freediving/archive_test.clj test/freediving/test_runner.clj
clj-kondo --lint src test
clojure -M:test
```

Tests use synthetic bytes/PDFs, temporary directories, Poppler, and local JVM subprocesses. Extraction checks include abrupt termination, concurrent calls, changed processing metadata, corrupted artifacts, malformed lines, unknown dates, Unicode/representation preservation, bounded REPL printing and CLI validation. No network sources, credentials, or private evidence are used.
