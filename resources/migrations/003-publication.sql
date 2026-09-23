CREATE TABLE freediving.publication_decisions (
 id text PRIMARY KEY, job_id text NOT NULL, ordinal integer NOT NULL,
 revision integer NOT NULL CHECK(revision > 0), review_revision integer NOT NULL CHECK(review_revision >= 0),
 policy_version text NOT NULL, action text NOT NULL CHECK(action IN ('validate','revoke')),
 candidate_id text NOT NULL, artifact_sha256 text NOT NULL, source_sha256 text NOT NULL,
 body_edn text NOT NULL, db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(job_id,ordinal,revision),
 FOREIGN KEY(job_id,ordinal) REFERENCES freediving.observations(job_id,ordinal)
);
CREATE TRIGGER immutable_publication_decisions BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.publication_decisions FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE FUNCTION freediving.stamp_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.job_id || '/' || NEW.ordinal::text, 11));
 IF NEW.revision <> COALESCE((SELECT max(revision) FROM freediving.publication_decisions WHERE job_id=NEW.job_id AND ordinal=NEW.ordinal),0)+1 THEN
 RAISE EXCEPTION 'Stale publication revision'; END IF;
 IF NEW.review_revision <> COALESCE((SELECT max(revision) FROM freediving.review_decisions WHERE job_id=NEW.job_id AND ordinal=NEW.ordinal),0) THEN
 RAISE EXCEPTION 'Stale review revision'; END IF;
 IF NOT EXISTS(SELECT 1 FROM freediving.observations o JOIN freediving.extractions e USING(job_id)
 WHERE o.job_id=NEW.job_id AND o.ordinal=NEW.ordinal AND o.candidate_id=NEW.candidate_id
 AND e.artifact_sha256=NEW.artifact_sha256 AND e.source_sha256=NEW.source_sha256) THEN
 RAISE EXCEPTION 'Publication provenance mismatch'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_publication_decisions BEFORE INSERT ON freediving.publication_decisions FOR EACH ROW EXECUTE FUNCTION freediving.stamp_publication();
REVOKE ALL ON freediving.publication_decisions FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.stamp_publication() FROM PUBLIC;
CREATE TABLE freediving.publication_policy_events (
 revision bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 policy_version text NOT NULL UNIQUE, reason text NOT NULL,
 db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_publication_policy_events BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.publication_policy_events FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
INSERT INTO freediving.publication_policy_events(policy_version,reason) VALUES('extraction-publication/1','Initial explicit extraction validation policy');
REVOKE ALL ON freediving.publication_policy_events FROM PUBLIC;
CREATE FUNCTION freediving.stamp_publication_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_publication_policy_events BEFORE INSERT ON freediving.publication_policy_events FOR EACH ROW EXECUTE FUNCTION freediving.stamp_publication_policy();
REVOKE ALL ON FUNCTION freediving.stamp_publication_policy() FROM PUBLIC;
