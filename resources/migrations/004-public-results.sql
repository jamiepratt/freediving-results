CREATE TABLE freediving.public_projection_cache (
 result_id text PRIMARY KEY,
 job_id text NOT NULL, ordinal integer NOT NULL,
 validation_id text NOT NULL REFERENCES freediving.publication_decisions(id),
 policy_version text NOT NULL,
 review_count bigint NOT NULL, validation_count bigint NOT NULL,
 source_name text NOT NULL, identity_id text,
 body_edn text NOT NULL,
 FOREIGN KEY(job_id,ordinal) REFERENCES freediving.observations(job_id,ordinal)
);
CREATE VIEW freediving.public_results WITH (security_barrier=true) AS
SELECT c.result_id,c.source_name,c.identity_id,c.body_edn
FROM freediving.public_projection_cache c
JOIN freediving.publication_decisions p ON p.id=c.validation_id
WHERE p.action='validate'
AND p.policy_version=c.policy_version
AND p.policy_version='extraction-publication/1'
AND p.policy_version=(SELECT policy_version FROM freediving.publication_policy_events ORDER BY revision DESC LIMIT 1)
AND NOT EXISTS (SELECT 1 FROM freediving.publication_decisions newer
                WHERE newer.job_id=p.job_id AND newer.ordinal=p.ordinal AND newer.revision>p.revision)
AND p.review_revision=COALESCE((SELECT max(r.revision) FROM freediving.review_decisions r
                             WHERE r.job_id=p.job_id AND r.ordinal=p.ordinal),0)
AND c.review_count=(SELECT count(*) FROM freediving.review_decisions)
AND c.validation_count=(SELECT count(*) FROM freediving.publication_decisions);
REVOKE ALL ON freediving.public_projection_cache,freediving.public_results FROM PUBLIC;
