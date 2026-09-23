CREATE SCHEMA IF NOT EXISTS freediving;
CREATE TABLE IF NOT EXISTS freediving.schema_migrations (version integer PRIMARY KEY, sha256 text NOT NULL);
CREATE TABLE IF NOT EXISTS freediving.extractions (
 job_id text PRIMARY KEY CHECK (job_id ~ '^[0-9a-f]{64}$'),
 artifact_sha256 text NOT NULL UNIQUE CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
 source_sha256 text NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
 parser_version text NOT NULL, schema_version integer NOT NULL CHECK (schema_version IN (1,2,3)),
 artifact_bytes bytea NOT NULL, imported_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS freediving.observations (
 job_id text NOT NULL REFERENCES freediving.extractions(job_id),
 ordinal integer NOT NULL CHECK (ordinal >= 0), candidate_id text NOT NULL,
 kind text NOT NULL CHECK (kind IN ('result-row','fragment','unclassified')),
 classification_reason text NOT NULL, payload_edn text NOT NULL,
 PRIMARY KEY (job_id, ordinal), UNIQUE (job_id, candidate_id)
);
CREATE OR REPLACE FUNCTION freediving.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable ingestion records'; END $$;
DROP TRIGGER IF EXISTS immutable_extractions ON freediving.extractions;
CREATE TRIGGER immutable_extractions BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.extractions FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
DROP TRIGGER IF EXISTS immutable_observations ON freediving.observations;
CREATE TRIGGER immutable_observations BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.observations FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
