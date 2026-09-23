# Freediving results: local evidence archive

This slice registers existing source bytes and explicit acquisition provenance in a private local archive. It does not extract results, implement the PostgreSQL observations database, resolve identities, or publish data. Pilot acceptance and remaining scope live in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1), which remains open.

## Run

Requires Java 17+ and the Clojure CLI. Clojure is pinned in `deps.edn`; the legacy adapter also uses pinned `data.json`; no running services are needed. First run downloads Maven dependencies.

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

Tests use synthetic bytes, temporary directories, and local JVM subprocesses. No network sources, credentials, or private evidence are used.
