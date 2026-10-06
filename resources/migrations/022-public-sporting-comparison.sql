-- Independent public sporting authority. No approvals, selections or sporting rows seeded.
CREATE TABLE freediving.public_sporting_policy_events (
 revision bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 policy_version text NOT NULL,
 db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_public_sporting_policy BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.public_sporting_policy_events FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER stamp_public_sporting_policy BEFORE INSERT ON freediving.public_sporting_policy_events
 FOR EACH ROW EXECUTE FUNCTION freediving.stamp_revision();
INSERT INTO freediving.public_sporting_policy_events(policy_version) VALUES('aida-baseline-v1');
CREATE TABLE freediving.public_sporting_authority_events (
 revision bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 cohort_id text NOT NULL CHECK(cohort_id ~ '^[0-9a-f]{64}$'),
 action text NOT NULL CHECK(action IN ('publish','withdraw')),
 policy_version text NOT NULL,
 publication_policy_revision bigint NOT NULL,
 policy_revision bigint NOT NULL,
 snapshot jsonb NOT NULL,
 expected_members integer NOT NULL CHECK(expected_members >= 0),
 body_edn text NOT NULL CHECK(length(body_edn) <= 1048576),
 db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_public_sporting_authority BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.public_sporting_authority_events FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE FUNCTION freediving.public_sporting_local_snapshot() RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT jsonb_build_object(
 'reviews',(SELECT count(*) FROM freediving.review_decisions),
 'publications',(SELECT count(*) FROM freediving.publication_decisions),
 'selections',(SELECT count(*) FROM freediving.event_selections),
 'relationships',(SELECT count(*) FROM freediving.revision_decisions),
 'relationship_proposals',(SELECT count(*) FROM freediving.revision_proposals),
 'identity_events',(SELECT count(*) FROM freediving.athlete_identity_events),
 'field_events',(SELECT count(*) FROM freediving.dive_field_decisions),
 'local_canonical_events',(SELECT count(*) FROM freediving.canonical_attempt_events),
 'local_canonical_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.canonical_attempt_state s),
 'local_identity_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.canonical_identity_view s),
 'local_source_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.source_identity_snapshot s))
$$;
CREATE FUNCTION freediving.stamp_public_sporting_authority() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(781246916);
 NEW.db_role := session_user;
 NEW.recorded_at := clock_timestamp();
 NEW.publication_policy_revision := (SELECT max(revision) FROM freediving.publication_policy_events);
 NEW.policy_revision := (SELECT max(revision) FROM freediving.public_sporting_policy_events);
 NEW.snapshot := freediving.public_sporting_local_snapshot();
 IF NEW.policy_version <> (SELECT policy_version FROM freediving.public_sporting_policy_events ORDER BY revision DESC LIMIT 1) THEN
 RAISE EXCEPTION 'Stale sporting policy'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_public_sporting_authority BEFORE INSERT ON freediving.public_sporting_authority_events
 FOR EACH ROW EXECUTE FUNCTION freediving.stamp_public_sporting_authority();
CREATE TABLE freediving.public_sporting_members (
 event_revision bigint NOT NULL REFERENCES freediving.public_sporting_authority_events(revision),
 result_id text NOT NULL CHECK(result_id ~ '^[0-9a-f]{64}$'),
 source_body_edn text NOT NULL,
 source_sha256 text NOT NULL,
 artifact_sha256 text NOT NULL,
 observation_id text NOT NULL,
 ordinal integer NOT NULL,
 PRIMARY KEY(event_revision,result_id)
);
CREATE TRIGGER immutable_public_sporting_members BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.public_sporting_members FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE FUNCTION freediving.stamp_public_sporting_member() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM freediving.public_sporting_authority_events e
 WHERE e.revision=NEW.event_revision AND e.action='publish' AND e.db_role=session_user
 AND e.snapshot=(SELECT jsonb_build_object(
 'reviews',(SELECT count(*) FROM freediving.review_decisions),
 'publications',(SELECT count(*) FROM freediving.publication_decisions),
 'selections',(SELECT count(*) FROM freediving.event_selections),
 'relationships',(SELECT count(*) FROM freediving.revision_decisions),
 'relationship_proposals',(SELECT count(*) FROM freediving.revision_proposals),
 'identity_events',(SELECT count(*) FROM freediving.athlete_identity_events),
 'field_events',(SELECT count(*) FROM freediving.dive_field_decisions),
 'local_canonical_events',(SELECT count(*) FROM freediving.canonical_attempt_events),
 'local_canonical_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.canonical_attempt_state s),
 'local_identity_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.canonical_identity_view s),
 'local_source_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.source_identity_snapshot s)))
 AND NOT EXISTS(SELECT 1 FROM freediving.public_sporting_authority_events n WHERE n.cohort_id=e.cohort_id AND n.revision>e.revision)) THEN
 RAISE EXCEPTION 'Current owned sporting authority required'; END IF;
 SELECT p.body_edn,d.source_sha256,d.artifact_sha256,
 encode(sha256(convert_to(row(d.job_id,d.ordinal,d.candidate_id,d.artifact_sha256)::text,'UTF8')),'hex'),d.ordinal
 INTO STRICT NEW.source_body_edn,NEW.source_sha256,NEW.artifact_sha256,NEW.observation_id,NEW.ordinal
 FROM freediving.public_results p JOIN freediving.public_projection_cache c USING(result_id)
 JOIN freediving.publication_decisions d ON d.id=c.validation_id WHERE p.result_id=NEW.result_id;
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_public_sporting_member BEFORE INSERT ON freediving.public_sporting_members
 FOR EACH ROW EXECUTE FUNCTION freediving.stamp_public_sporting_member();
CREATE VIEW freediving.public_sporting_comparison WITH (security_barrier=true) AS
 SELECT e.cohort_id,e.revision,e.body_edn,e.policy_version,
 (SELECT jsonb_agg(jsonb_build_object('result-id',m.result_id,'source-sha256',m.source_sha256,
 'artifact-sha256',m.artifact_sha256,'observation-id',m.observation_id,'ordinal',m.ordinal,
 'source-body',p.body_edn) ORDER BY m.result_id)
 FROM freediving.public_sporting_members m JOIN freediving.public_results p USING(result_id)
 WHERE m.event_revision=e.revision AND m.source_body_edn=p.body_edn)::text AS bindings_json
 FROM freediving.public_sporting_authority_events e
 WHERE e.action='publish' AND e.policy_version='aida-baseline-v1'
 AND e.policy_revision=(SELECT max(revision) FROM freediving.public_sporting_policy_events)
 AND e.publication_policy_revision=(SELECT max(revision) FROM freediving.publication_policy_events)
 AND e.snapshot=(SELECT jsonb_build_object(
 'reviews',(SELECT count(*) FROM freediving.review_decisions),
 'publications',(SELECT count(*) FROM freediving.publication_decisions),
 'selections',(SELECT count(*) FROM freediving.event_selections),
 'relationships',(SELECT count(*) FROM freediving.revision_decisions),
 'relationship_proposals',(SELECT count(*) FROM freediving.revision_proposals),
 'identity_events',(SELECT count(*) FROM freediving.athlete_identity_events),
 'field_events',(SELECT count(*) FROM freediving.dive_field_decisions),
 'local_canonical_events',(SELECT count(*) FROM freediving.canonical_attempt_events),
 'local_canonical_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.canonical_attempt_state s),
 'local_identity_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.canonical_identity_view s),
 'local_source_state',(SELECT encode(sha256(convert_to(COALESCE(string_agg(row(s.*)::text,',' ORDER BY singleton),''),'UTF8')),'hex') FROM freediving.source_identity_snapshot s)))
 AND NOT EXISTS(SELECT 1 FROM freediving.public_sporting_authority_events n WHERE n.cohort_id=e.cohort_id AND n.revision>e.revision)
 AND e.expected_members=(SELECT count(*) FROM freediving.public_sporting_members m JOIN freediving.public_results p USING(result_id)
 WHERE m.event_revision=e.revision AND m.source_body_edn=p.body_edn);
REVOKE ALL ON freediving.public_sporting_policy_events,freediving.public_sporting_authority_events,freediving.public_sporting_members,freediving.public_sporting_comparison FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.public_sporting_local_snapshot(),freediving.stamp_public_sporting_authority(),freediving.stamp_public_sporting_member() FROM PUBLIC;
