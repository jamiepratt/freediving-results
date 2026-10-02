-- Model approvals use the ingest role but remain distinct from deterministic rules.
ALTER TABLE freediving.athlete_identity_events
 DROP CONSTRAINT athlete_identity_events_actor_kind_check;
ALTER TABLE freediving.athlete_identity_events
 ADD CONSTRAINT athlete_identity_events_actor_kind_check
 CHECK (actor_kind IN ('automatic', 'model', 'human'));

ALTER TABLE freediving.dive_field_decisions
 DROP CONSTRAINT dive_field_decisions_actor_kind_check;
ALTER TABLE freediving.dive_field_decisions
 ADD CONSTRAINT dive_field_decisions_actor_kind_check
 CHECK (actor_kind IN ('automatic', 'model', 'human'));

DO $$
DECLARE old_name text;
BEGIN
 SELECT conname INTO old_name
 FROM pg_constraint
 WHERE conrelid='freediving.dive_field_decisions'::regclass
   AND contype='c'
   AND position('decision_key' IN pg_get_constraintdef(oid)) > 0;
 IF old_name IS NULL THEN
   RAISE EXCEPTION 'Existing dive field decision key constraint missing';
 END IF;
 EXECUTE format('ALTER TABLE freediving.dive_field_decisions DROP CONSTRAINT %I', old_name);
END $$;
ALTER TABLE freediving.dive_field_decisions
 ADD CONSTRAINT dive_field_decisions_model_key_check
 CHECK ((actor_kind IN ('automatic', 'model') AND decision_key IS NOT NULL)
        OR actor_kind='human');

DO $$
DECLARE old_name text;
BEGIN
 SELECT conname INTO old_name
 FROM pg_constraint
 WHERE conrelid='freediving.dive_field_decisions'::regclass
   AND contype='c'
   AND position('event_id' IN pg_get_constraintdef(oid)) > 0
   AND position('action' IN pg_get_constraintdef(oid)) > 0;
 IF old_name IS NULL THEN
   RAISE EXCEPTION 'Existing dive field reversal constraint missing';
 END IF;
 EXECUTE format('ALTER TABLE freediving.dive_field_decisions DROP CONSTRAINT %I', old_name);
END $$;
ALTER TABLE freediving.dive_field_decisions
 ADD CONSTRAINT dive_field_decisions_model_reversal_check
 CHECK ((action='assert' AND event_id IS NULL)
        OR (action='reverse' AND event_id IS NOT NULL AND actor_kind IN ('human','model')));

CREATE OR REPLACE FUNCTION freediving.stamp_athlete_identity_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.actor_kind IN ('automatic','model') AND session_user<>TG_ARGV[0]) OR
    (NEW.actor_kind='human' AND session_user<>TG_ARGV[1]) THEN
   RAISE EXCEPTION 'Athlete identity event role does not match actor kind';
 END IF;
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION freediving.stamp_dive_field_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.actor_kind IN ('automatic','model') AND session_user<>TG_ARGV[0]) OR
    (NEW.actor_kind='human' AND session_user<>TG_ARGV[1]) THEN
   RAISE EXCEPTION 'Dive field decision role does not match actor kind';
 END IF;
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 IF NOT EXISTS (SELECT 1 FROM freediving.observations
                WHERE job_id=NEW.job_id AND ordinal=NEW.ordinal AND kind='result-row') THEN
   RAISE EXCEPTION 'Dive field decision target must be a result-row';
 END IF;
 IF NEW.action='reverse' AND NEW.actor_kind='model' AND NOT EXISTS (
      SELECT 1 FROM freediving.dive_field_decisions prior
      WHERE prior.id=NEW.event_id AND prior.action='assert' AND prior.actor_kind='model'
        AND prior.source_position_id=NEW.source_position_id
        AND prior.decision_type=NEW.decision_type
        AND NOT EXISTS (SELECT 1 FROM freediving.dive_field_decisions reversal
                        WHERE reversal.event_id=prior.id)) THEN
   RAISE EXCEPTION 'Model reversal must target an active model assertion';
 END IF;
 RETURN NEW;
END $$;
