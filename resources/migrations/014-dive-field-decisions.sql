-- Decision authority is append-only and separate from reviewer-only identity decisions.
CREATE TABLE freediving.dive_field_decisions (
 id text PRIMARY KEY,
 source_position_id text NOT NULL,
 job_id text NOT NULL,
 ordinal integer NOT NULL,
 decision_type text NOT NULL CHECK (decision_type IN ('category', 'representation')),
 revision integer NOT NULL CHECK (revision > 0),
 action text NOT NULL CHECK (action IN ('assert', 'reverse')),
 actor_kind text NOT NULL CHECK (actor_kind IN ('automatic', 'human')),
 decision_key text UNIQUE,
 event_id text REFERENCES freediving.dive_field_decisions(id),
 body_edn text NOT NULL,
 db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(source_position_id, revision),
 FOREIGN KEY(job_id, ordinal) REFERENCES freediving.observations(job_id, ordinal),
 CHECK ((action='assert' AND event_id IS NULL) OR
        (action='reverse' AND event_id IS NOT NULL AND actor_kind='human')),
 CHECK ((actor_kind='automatic' AND decision_key IS NOT NULL) OR actor_kind='human')
);
CREATE UNIQUE INDEX dive_field_decision_reversal ON freediving.dive_field_decisions(event_id) WHERE event_id IS NOT NULL;
CREATE TRIGGER immutable_dive_field_decisions BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.dive_field_decisions FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE OR REPLACE FUNCTION freediving.stamp_dive_field_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.actor_kind='automatic' AND session_user<>TG_ARGV[0]) OR
    (NEW.actor_kind='human' AND session_user<>TG_ARGV[1]) THEN
   RAISE EXCEPTION 'Dive field decision role does not match actor kind';
 END IF;
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 IF NOT EXISTS (SELECT 1 FROM freediving.observations
                WHERE job_id=NEW.job_id AND ordinal=NEW.ordinal AND kind='result-row') THEN
   RAISE EXCEPTION 'Dive field decision target must be a result-row';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_dive_field_decisions BEFORE INSERT ON freediving.dive_field_decisions
 FOR EACH ROW EXECUTE FUNCTION freediving.stamp_dive_field_decision('pending_ingest','pending_reviewer');
REVOKE ALL ON freediving.dive_field_decisions FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.stamp_dive_field_decision() FROM PUBLIC;
