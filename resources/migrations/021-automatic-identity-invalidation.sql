-- A reviewer-led rebuild may record a mechanically proven automatic reversal.
CREATE OR REPLACE FUNCTION freediving.stamp_athlete_identity_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.actor_kind IN ('automatic','model') AND session_user<>TG_ARGV[0]
     AND NOT (NEW.actor_kind='automatic' AND NEW.action='reverse' AND session_user=TG_ARGV[1])) OR
    (NEW.actor_kind='human' AND session_user<>TG_ARGV[1]) THEN
   RAISE EXCEPTION 'Athlete identity event role does not match actor kind';
 END IF;
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 RETURN NEW;
END $$;
