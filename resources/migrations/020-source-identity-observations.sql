-- Frozen, caller-verified source observations used by the private identity ledger.
CREATE TABLE freediving.source_identity_snapshot (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 snapshot_sha256 text NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE TABLE freediving.source_identity_observations (
 observation_id text PRIMARY KEY CHECK (observation_id ~ '^source-observation:[0-9a-f]{64}$'),
 snapshot_sha256 text NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 body_edn text NOT NULL
);
CREATE TRIGGER immutable_source_identity_snapshot BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.source_identity_snapshot FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER immutable_source_identity_observations BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.source_identity_observations FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
REVOKE ALL ON freediving.source_identity_snapshot,freediving.source_identity_observations FROM PUBLIC;
