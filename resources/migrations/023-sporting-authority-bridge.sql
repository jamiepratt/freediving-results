-- Authenticated ordered safe receipts. No production authority or members seeded.
CREATE TABLE freediving.public_sporting_bridge_receipts (
 revision bigint PRIMARY KEY CHECK(revision>0),
 previous_sha256 text NOT NULL CHECK(previous_sha256 ~ '^[0-9a-f]{64}$'),
 head_sha256 text NOT NULL UNIQUE CHECK(head_sha256 ~ '^[0-9a-f]{64}$'),
 action text NOT NULL CHECK(action IN ('stage','approve','reverse')),
 publication_sha256 text CHECK(publication_sha256 ~ '^[0-9a-f]{64}$'),
 binding_sha256 text NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
 decision_sha256 text NOT NULL CHECK(decision_sha256 ~ '^[0-9a-f]{64}$'),
 policy text NOT NULL CHECK(policy='aida-baseline-v1'),
 key_id text NOT NULL CHECK(key_id ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 db_role text NOT NULL DEFAULT session_user
);
CREATE TRIGGER immutable_public_sporting_bridge_receipts BEFORE UPDATE OR DELETE OR TRUNCATE
 ON freediving.public_sporting_bridge_receipts FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER stamp_public_sporting_bridge_receipts BEFORE INSERT ON freediving.public_sporting_bridge_receipts
 FOR EACH ROW EXECUTE FUNCTION freediving.stamp_revision();
ALTER TABLE freediving.public_sporting_authority_events ADD COLUMN receipt_revision bigint
 REFERENCES freediving.public_sporting_bridge_receipts(revision);
CREATE UNIQUE INDEX public_sporting_receipt_once ON freediving.public_sporting_authority_events(receipt_revision) WHERE receipt_revision IS NOT NULL;
CREATE OR REPLACE VIEW freediving.public_sporting_comparison WITH (security_barrier=true) AS
 SELECT e.cohort_id,e.revision,e.body_edn,e.policy_version,
 (SELECT jsonb_agg(jsonb_build_object('result-id',m.result_id,'source-sha256',m.source_sha256,
 'artifact-sha256',m.artifact_sha256,'observation-id',m.observation_id,'ordinal',m.ordinal,
 'source-body',p.body_edn) ORDER BY m.result_id)
 FROM freediving.public_sporting_members m JOIN freediving.public_results p USING(result_id)
 WHERE m.event_revision=e.revision AND m.source_body_edn=p.body_edn)::text AS bindings_json,
 r.revision AS bridge_revision,r.head_sha256 AS bridge_head,r.binding_sha256 AS bridge_binding,
 r.publication_sha256 AS bridge_publication,r.key_id AS bridge_key
 FROM freediving.public_sporting_authority_events e
 JOIN freediving.public_sporting_bridge_receipts r ON r.revision=e.receipt_revision
 WHERE r.action='approve' AND r.revision=(SELECT max(revision) FROM freediving.public_sporting_bridge_receipts) AND e.action='publish' AND e.policy_version='aida-baseline-v1'
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
REVOKE ALL ON freediving.public_sporting_bridge_receipts FROM PUBLIC;
