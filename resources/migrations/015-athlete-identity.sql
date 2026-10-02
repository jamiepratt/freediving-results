-- Identity links are append-only; observations and provisional IDs are never merged away.
CREATE TABLE freediving.athlete_identity_events (
 revision integer PRIMARY KEY CHECK (revision > 0),
 id text NOT NULL UNIQUE,
 action text NOT NULL CHECK (action IN ('accept', 'reject', 'reverse')),
 actor_kind text NOT NULL CHECK (actor_kind IN ('automatic', 'human')),
 body_edn text NOT NULL,
 db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_athlete_identity_events BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.athlete_identity_events FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE OR REPLACE FUNCTION freediving.stamp_athlete_identity_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.actor_kind='automatic' AND session_user<>TG_ARGV[0]) OR
    (NEW.actor_kind='human' AND session_user<>TG_ARGV[1]) THEN
   RAISE EXCEPTION 'Athlete identity event role does not match actor kind';
 END IF;
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_athlete_identity_event BEFORE INSERT ON freediving.athlete_identity_events
 FOR EACH ROW EXECUTE FUNCTION freediving.stamp_athlete_identity_event('pending_ingest','pending_reviewer');
REVOKE ALL ON freediving.athlete_identity_events FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.stamp_athlete_identity_event() FROM PUBLIC;
