CREATE TABLE freediving.event_selections (
 id text PRIMARY KEY, revision bigint NOT NULL UNIQUE CHECK(revision>0),
 event_key text NOT NULL, body_edn text NOT NULL,
 db_role text NOT NULL, recorded_at timestamptz NOT NULL
);
CREATE TRIGGER immutable_event_selections BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.event_selections FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER stamp_event_selections BEFORE INSERT ON freediving.event_selections FOR EACH ROW EXECUTE FUNCTION freediving.stamp_revision();
REVOKE ALL ON freediving.event_selections FROM PUBLIC;
ALTER TABLE freediving.public_projection_cache ADD COLUMN selection_count bigint NOT NULL DEFAULT 0;
ALTER TABLE freediving.public_projection_cache ADD COLUMN relationship_count bigint NOT NULL DEFAULT 0;
-- Additive policy compatibility. Does not activate a publication policy.
ALTER TABLE freediving.public_projection_cache ADD COLUMN proposal_count bigint NOT NULL DEFAULT 0;
CREATE OR REPLACE VIEW freediving.public_results WITH (security_barrier=true) AS
SELECT c.result_id,c.source_name,c.identity_id,c.body_edn,encode(sha256(convert_to(c.validation_id,'UTF8')),'hex') AS correction_version
FROM freediving.public_projection_cache c
JOIN freediving.publication_decisions p ON p.id=c.validation_id
WHERE p.action='validate'
AND p.policy_version=c.policy_version
AND p.policy_version IN ('extraction-publication/1','extraction-publication/2')
AND p.policy_version=(SELECT policy_version FROM freediving.publication_policy_events ORDER BY revision DESC LIMIT 1)
AND NOT EXISTS (SELECT 1 FROM freediving.publication_decisions newer
                WHERE newer.job_id=p.job_id AND newer.ordinal=p.ordinal AND newer.revision>p.revision)
AND p.review_revision=COALESCE((SELECT max(r.revision) FROM freediving.review_decisions r
                             WHERE r.job_id=p.job_id AND r.ordinal=p.ordinal),0)
AND c.review_count=(SELECT count(*) FROM freediving.review_decisions)
AND c.validation_count=(SELECT count(*) FROM freediving.publication_decisions)
AND c.selection_count=(SELECT count(*) FROM freediving.event_selections)
AND c.relationship_count=(SELECT count(*) FROM freediving.revision_decisions)
AND c.proposal_count=(SELECT count(*) FROM freediving.revision_proposals);
CREATE FUNCTION freediving.lock_selection_authority() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 LOCK TABLE freediving.public_projection_cache IN ROW EXCLUSIVE MODE;
 LOCK TABLE freediving.publication_decisions,freediving.review_decisions,freediving.publication_policy_events,freediving.revision_decisions,freediving.revision_proposals,freediving.event_selections IN SHARE ROW EXCLUSIVE MODE;
END $$;
REVOKE ALL ON FUNCTION freediving.lock_selection_authority() FROM PUBLIC;
CREATE FUNCTION freediving.lock_revision_projection() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN LOCK TABLE freediving.public_projection_cache IN ROW EXCLUSIVE MODE; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION freediving.lock_revision_projection() FROM PUBLIC;
CREATE TRIGGER lock_revision_projection BEFORE INSERT ON freediving.revision_decisions FOR EACH ROW EXECUTE FUNCTION freediving.lock_revision_projection();
CREATE TABLE freediving.event_coverage_cache (
 event_key text PRIMARY KEY, body_edn text NOT NULL,
 selection_count bigint NOT NULL, relationship_count bigint NOT NULL, proposal_count bigint NOT NULL,
 review_count bigint NOT NULL, validation_count bigint NOT NULL, policy_revision bigint NOT NULL
);
REVOKE ALL ON freediving.event_coverage_cache FROM PUBLIC;
CREATE VIEW freediving.public_event_coverage WITH (security_barrier=true) AS
 SELECT body_edn FROM freediving.event_coverage_cache c
 WHERE c.selection_count=(SELECT count(*) FROM freediving.event_selections)
 AND c.relationship_count=(SELECT count(*) FROM freediving.revision_decisions)
 AND c.proposal_count=(SELECT count(*) FROM freediving.revision_proposals)
 AND c.review_count=(SELECT count(*) FROM freediving.review_decisions)
 AND c.validation_count=(SELECT count(*) FROM freediving.publication_decisions)
 AND c.policy_revision=(SELECT max(revision) FROM freediving.publication_policy_events);

CREATE TRIGGER lock_proposal_projection BEFORE INSERT ON freediving.revision_proposals FOR EACH ROW EXECUTE FUNCTION freediving.lock_revision_projection();
-- Installing support alone preserves the current validated projection.
UPDATE freediving.public_projection_cache
 SET relationship_count=(SELECT count(*) FROM freediving.revision_decisions),
     proposal_count=(SELECT count(*) FROM freediving.revision_proposals);
