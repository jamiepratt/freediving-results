# Local Agent-o-rama import pipeline

The optional `freediving.pipeline-rama` module coordinates existing archive, extraction, observation and candidate APIs on one trusted local host. Its `import` agent has four stages: archive, extraction, ingestion and readiness. It does not acquire sources, call model providers, approve identities or publish results. Pilot acceptance remains in [issue #1](https://github.com/jamiepratt/freediving-results/issues/1).

## Runtime and verification

Use Java 21, Poppler `pdftotext` and `pdfinfo`, and PostgreSQL 17. The `:rama` alias pins Agent-o-rama 0.10.0, Rama 1.9.0 and Clojure 1.12.4. Ordinary archive and observation commands do not load Rama.

```sh
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH \
  scripts/test-postgres.sh test-pipeline
PATH=/Applications/Postgres.app/Contents/Versions/17/bin:$PATH \
  scripts/test-postgres.sh rama:test-pipeline-rama
```

Each helper invocation owns an isolated synthetic PostgreSQL cluster and removes it on exit. These checks require no real pilot evidence or provider credentials.

## Register and invoke

Register an existing local PDF from a trusted Clojure process. Supply the complete [acquisition manifest](../README.md#manifest-contract), with observed provenance and the exact byte hash. Missing facts must remain missing.

```clojure
(require '[freediving.pipeline :as pipeline]
         '[freediving.pipeline-rama :as pipeline-rama])

(pipeline/register-job! private-registry-root
  {:source absolute-local-pdf-path
   :manifest recorded-acquisition-manifest
   :options {:actor operator-label :config processing-config}})
;; => {:job-id "64-character-lowercase-hash"}

(pipeline-rama/pipeline-module 'your.private.configuration/load-pipeline)
```

These symbols represent private operator-supplied values, not literal runnable configuration. The loader returns `{:registry-root ... :archive-root ... :database-url ...}`. Use the restricted ingestion database role after applying the existing observations migration separately. The graph does not migrate databases. Keep roots dedicated and private, with existing trusted parent directories; symlink paths are rejected.

Launch the module in local Rama IPC, obtain its name with `com.rpl.rama/get-module-name`, and obtain the `import` agent client through `agent-manager` and `agent-client`. Call `(pipeline-rama/invoke! client job-id)` with the registered hash. The integration test contains a complete synthetic setup.

Registration binds source location, supplied manifest, extraction options, pipeline/parser versions, candidate packet version/comparison configuration and installed PDF tool versions. Re-register after changes to those inputs. Execution identity additionally binds the private archive/database configuration and current archive acquisition/evidence snapshot. Stage receipts are immutable. Repeating a stage checks current inputs and prerequisites rather than treating a previous graph completion as authority.

`pipeline/stage!` is the private service interface for `:archive`, `:extraction`, `:ingestion` and `:readiness`. The graph exposes stage, fixed status/reason and opaque job, execution, receipt, source, extraction, artifact and corpus references. It also exposes the fixed readiness states `:review-status :unreviewed` and `:publication-status :blocked`. Candidate readiness describes a corpus snapshot; it is not an approval or continuing guarantee after that corpus changes. Later nodes carry the original execution ID and reject a changed evidence snapshot.

## Privacy and authority

Use a trusted qualified configuration function resolved inside an untraced agent object. Keep database credentials, local paths, manifests, PDF bytes and extracted names behind that worker boundary. Module configuration contains the loader symbol only. Use `pipeline-rama/invoke!` for graph invocation: validation rejects non-hash input before Rama records it. Direct calls to framework invocation bypass that admission check.

Archive and extraction retain original evidence. Ingestion uses its existing transactional idempotency and conflict verification. Candidate retrieval produces private review material; it never creates review decisions, extraction validations, identity approvals or public projections. Unsupported layouts and unresolved parser results require attention, without being described as worker crashes.

Unsupported or partially unsupported layouts stop at extraction. OCR needs and unparsed candidates return an unresolved outcome. Parsed rows still require owner extraction validation: graph readiness is only evidence-processing readiness. Missing provenance, unavailable local sources, hash failures, stale receipts and database failures have separate fixed outcomes.

The registry limits each encoded record to 1 MiB. Candidate retrieval uses its existing 10,000-observation default bound and computes a private corpus readiness receipt; it does not export a complete packet collection. Use the existing candidate/packet interfaces to inspect that corpus privately. PDF processing retains the existing local extraction worker's in-memory processing limits; this graph adds no operating-system memory quota or distributed worker scheduler.

Persistence assumes the same private local stores and database remain available to retries on the same trusted host. This is not distributed storage, production authentication or a power-loss durability guarantee. Administrative database access and processes running as the same OS user remain trusted. The graph does not replace the separate owner review and publication gates.
