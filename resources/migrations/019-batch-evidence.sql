-- Dedicated immutable position evidence. This role does not write legacy observations.
CREATE TABLE freediving.batch_position_evidence (
 job_id text PRIMARY KEY CHECK (job_id ~ '^[0-9a-f]{64}$'),
 record_sha256 text NOT NULL CHECK (record_sha256 ~ '^[0-9a-f]{64}$'),
 source_sha256 text NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
 position_id text,
 parser_id text,
 parser_version text,
 record_kind text NOT NULL CHECK (record_kind IN ('batch-observation','batch-evidence','batch-exception')),
 evidence_role text,
 revision text NOT NULL CHECK (revision ~ '^[0-9a-f]{64}$'),
 acquisitions_edn text NOT NULL,
 record_edn text NOT NULL,
 imported_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION freediving.reject_batch_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable batch position evidence'; END $$;
CREATE TRIGGER immutable_batch_position_evidence BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.batch_position_evidence FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_batch_evidence_mutation();
REVOKE ALL ON freediving.batch_position_evidence FROM PUBLIC;
