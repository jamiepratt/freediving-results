-- Private evidence and append-only relationship history; the singleton is derived state.
CREATE TABLE freediving.canonical_attempt_evidence (
 digest text PRIMARY KEY CHECK (digest ~ '^[0-9a-f]{64}$'),
 body_edn text NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE freediving.canonical_attempt_events (
 revision integer PRIMARY KEY CHECK (revision > 0),
 id text NOT NULL UNIQUE,
 body_edn text NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE freediving.canonical_attempt_state (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 evidence_digest text NOT NULL REFERENCES freediving.canonical_attempt_evidence(digest),
 revision integer NOT NULL CHECK (revision >= 0),
 projection_edn text NOT NULL,
 rebuilt_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_canonical_attempt_evidence BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.canonical_attempt_evidence FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER immutable_canonical_attempt_events BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.canonical_attempt_events FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
REVOKE ALL ON freediving.canonical_attempt_evidence,freediving.canonical_attempt_events,freediving.canonical_attempt_state FROM PUBLIC;
