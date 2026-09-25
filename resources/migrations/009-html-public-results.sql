-- Additive policy compatibility. Does not activate a publication policy.
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
AND c.validation_count=(SELECT count(*) FROM freediving.publication_decisions);
