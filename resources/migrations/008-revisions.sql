CREATE TABLE freediving.revision_proposals (
 id text PRIMARY KEY, successor_job text NOT NULL, successor_ordinal integer NOT NULL,
 predecessor_job text, predecessor_ordinal integer,
 body_edn text NOT NULL, db_role text NOT NULL, recorded_at timestamptz NOT NULL,
 FOREIGN KEY(successor_job,successor_ordinal) REFERENCES freediving.observations(job_id,ordinal),
 FOREIGN KEY(predecessor_job,predecessor_ordinal) REFERENCES freediving.observations(job_id,ordinal),
 CHECK ((predecessor_job IS NULL) = (predecessor_ordinal IS NULL))
);
CREATE TABLE freediving.revision_decisions (
 id text PRIMARY KEY, proposal_id text NOT NULL REFERENCES freediving.revision_proposals(id),
 revision integer NOT NULL UNIQUE CHECK(revision>0),
 action text NOT NULL CHECK(action IN ('confirm','reject','reverse','acknowledge-missing')),
 event_id text REFERENCES freediving.revision_decisions(id),
 body_edn text NOT NULL, db_role text NOT NULL, recorded_at timestamptz NOT NULL,
 CHECK ((action='reverse') = (event_id IS NOT NULL))
);
CREATE UNIQUE INDEX revision_once ON freediving.revision_decisions(proposal_id) WHERE action <> 'reverse';
CREATE UNIQUE INDEX revision_reverse_once ON freediving.revision_decisions(event_id) WHERE event_id IS NOT NULL;
CREATE TRIGGER immutable_revision_proposals BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.revision_proposals FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER immutable_revision_decisions BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.revision_decisions FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE FUNCTION freediving.stamp_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.db_role := session_user; NEW.recorded_at := clock_timestamp(); RETURN NEW; END $$;
CREATE TRIGGER stamp_revision_proposals BEFORE INSERT ON freediving.revision_proposals FOR EACH ROW EXECUTE FUNCTION freediving.stamp_revision();
CREATE TRIGGER stamp_revision_decisions BEFORE INSERT ON freediving.revision_decisions FOR EACH ROW EXECUTE FUNCTION freediving.stamp_revision();
REVOKE ALL ON freediving.revision_proposals,freediving.revision_decisions FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.stamp_revision() FROM PUBLIC;
