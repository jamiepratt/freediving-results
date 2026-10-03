-- Roll back this schema only before source rows or source identity events exist.
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM freediving.source_identity_observations) OR
    EXISTS (SELECT 1 FROM freediving.athlete_identity_events
            WHERE body_edn LIKE '%source-observation:%') THEN
   RAISE EXCEPTION 'Source identity data must be preserved before rollback';
 END IF;
END $$;
DROP TABLE freediving.source_identity_observations;
DROP TABLE freediving.source_identity_snapshot;
DELETE FROM freediving.schema_migrations WHERE version=20;
