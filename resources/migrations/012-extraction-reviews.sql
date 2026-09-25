-- Extraction accuracy attestations are private review records. They do not
-- satisfy publication validation, identity, source relationship or selection.
CREATE TABLE freediving.extraction_reviews (
 id text PRIMARY KEY,
 job_id text NOT NULL,
 ordinal integer NOT NULL,
 revision integer NOT NULL CHECK (revision > 0),
 action text NOT NULL CHECK (action IN ('accept', 'revoke')),
 event_id text REFERENCES freediving.extraction_reviews(id),
 body_edn text NOT NULL,
 db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (job_id, ordinal, revision),
 FOREIGN KEY (job_id, ordinal) REFERENCES freediving.observations(job_id, ordinal),
 CHECK ((action = 'accept' AND event_id IS NULL) OR
        (action = 'revoke' AND event_id IS NOT NULL))
);
CREATE UNIQUE INDEX extraction_review_event_revoked
 ON freediving.extraction_reviews(event_id) WHERE event_id IS NOT NULL;
CREATE TRIGGER immutable_extraction_reviews BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.extraction_reviews FOR EACH STATEMENT
 EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER stamp_extraction_reviews BEFORE INSERT
 ON freediving.extraction_reviews FOR EACH ROW
 EXECUTE FUNCTION freediving.stamp_review();
REVOKE ALL ON freediving.extraction_reviews FROM PUBLIC;
