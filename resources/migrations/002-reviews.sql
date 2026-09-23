CREATE TABLE freediving.review_proposals (
 id text PRIMARY KEY, job_id text NOT NULL, ordinal integer NOT NULL,
 body_edn text NOT NULL, db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(job_id,ordinal) REFERENCES freediving.observations(job_id,ordinal)
);
CREATE TABLE freediving.review_decisions (
 id text PRIMARY KEY, job_id text NOT NULL, ordinal integer NOT NULL,
 revision integer NOT NULL CHECK(revision > 0), action text NOT NULL CHECK(action IN ('approve','reject','reverse')),
 proposal_id text REFERENCES freediving.review_proposals(id),
 event_id text REFERENCES freediving.review_decisions(id),
 body_edn text NOT NULL, db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(job_id,ordinal,revision),
 FOREIGN KEY(job_id,ordinal) REFERENCES freediving.observations(job_id,ordinal),
 CHECK((action='reverse' AND event_id IS NOT NULL AND proposal_id IS NULL) OR
       (action IN ('approve','reject') AND proposal_id IS NOT NULL AND event_id IS NULL))
);
CREATE UNIQUE INDEX review_proposal_decided ON freediving.review_decisions(proposal_id) WHERE proposal_id IS NOT NULL;
CREATE UNIQUE INDEX review_event_reversed ON freediving.review_decisions(event_id) WHERE event_id IS NOT NULL;
CREATE TRIGGER immutable_review_proposals BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.review_proposals FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER immutable_review_decisions BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.review_decisions FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE FUNCTION freediving.stamp_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 IF NOT EXISTS(SELECT 1 FROM freediving.observations WHERE job_id=NEW.job_id AND ordinal=NEW.ordinal AND kind='result-row') THEN
 RAISE EXCEPTION 'Review target must be a result-row'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_review_proposals BEFORE INSERT ON freediving.review_proposals FOR EACH ROW EXECUTE FUNCTION freediving.stamp_review();
CREATE TRIGGER stamp_review_decisions BEFORE INSERT ON freediving.review_decisions FOR EACH ROW EXECUTE FUNCTION freediving.stamp_review();
REVOKE ALL ON freediving.review_proposals,freediving.review_decisions FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.stamp_review() FROM PUBLIC;
