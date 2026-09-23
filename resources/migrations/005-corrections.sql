CREATE OR REPLACE VIEW freediving.public_results WITH (security_barrier=true) AS
SELECT c.result_id,c.source_name,c.identity_id,c.body_edn,encode(sha256(convert_to(c.validation_id,'UTF8')),'hex') AS correction_version
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
CREATE TABLE freediving.correction_requests (
 id uuid PRIMARY KEY, result_id text NOT NULL, version text NOT NULL,
 job_id text NOT NULL, ordinal integer NOT NULL,
 suggestion text NOT NULL CHECK(char_length(suggestion) BETWEEN 1 AND 1000 AND octet_length(suggestion)<=4000),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND octet_length(reason)<=8000),
 evidence text NOT NULL CHECK(char_length(evidence) BETWEEN 1 AND 2000 AND octet_length(evidence)<=8000),
 client_key text NOT NULL CHECK(client_key ~ '^[a-f0-9]{64}$'),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(job_id,ordinal) REFERENCES freediving.observations(job_id,ordinal)
);
CREATE INDEX correction_requests_client ON freediving.correction_requests(client_key,recorded_at);
CREATE TABLE freediving.correction_rate_buckets (
 client_key text PRIMARY KEY, window_start timestamptz NOT NULL, attempts integer NOT NULL
);
CREATE TABLE freediving.correction_triage (
 id uuid PRIMARY KEY, request_id uuid NOT NULL REFERENCES freediving.correction_requests(id),
 revision integer NOT NULL CHECK(revision>0), action text NOT NULL CHECK(action IN ('dismiss','link-proposal')),
 proposal_id text REFERENCES freediving.review_proposals(id),
 actor text NOT NULL CHECK(char_length(actor) BETWEEN 1 AND 200 AND octet_length(actor)<=800),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND octet_length(reason)<=8000),
 db_role text NOT NULL DEFAULT session_user, recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(request_id,revision), CHECK((action='dismiss' AND proposal_id IS NULL) OR (action='link-proposal' AND proposal_id IS NOT NULL))
);
CREATE TRIGGER immutable_correction_requests BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.correction_requests FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER immutable_correction_triage BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.correction_triage FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE FUNCTION freediving.correction_target_version(target text) RETURNS text LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,freediving AS $$
 SELECT encode(sha256(convert_to(c.validation_id,'UTF8')),'hex')
 FROM freediving.public_projection_cache c JOIN freediving.public_results p USING(result_id) WHERE c.result_id=target
$$;
CREATE FUNCTION freediving.submit_correction(request_id uuid,target text,target_version text,suggested text,why text,citation text,client text)
RETURNS TABLE(status text,receipt uuid,duplicate boolean) LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,freediving AS $$
DECLARE t record; old record; token text; now_at timestamptz:=clock_timestamp();
BEGIN
 IF target IS NULL OR target !~ '^[a-f0-9]{64}$' OR target_version IS NULL OR target_version !~ '^[a-f0-9]{64}$'
 OR client IS NULL OR client !~ '^[a-f0-9]{64}$' OR request_id IS NULL
 OR suggested IS NULL OR char_length(suggested) NOT BETWEEN 1 AND 1000 OR octet_length(suggested)>4000 OR btrim(suggested)=''
 OR why IS NULL OR char_length(why) NOT BETWEEN 1 AND 2000 OR octet_length(why)>8000 OR btrim(why)=''
 OR citation IS NULL OR char_length(citation) NOT BETWEEN 1 AND 2000 OR octet_length(citation)>8000 OR btrim(citation)='' THEN
 RETURN QUERY SELECT 'invalid'::text,NULL::uuid,false; RETURN; END IF;
 IF regexp_replace(suggested || why || citation,E'[\\t\\n\\r]','','g') ~ '[[:cntrl:]]' THEN
 RETURN QUERY SELECT 'invalid'::text,NULL::uuid,false; RETURN; END IF;
 FOR token IN SELECT (regexp_matches(citation,'[a-zA-Z][a-zA-Z0-9+.-]*:[^[:space:]<>"\\]+','g'))[1] LOOP
 IF token !~ '^https?://[^/?#@:]+(:[0-9]+)?(/[^?#]*)?(#page=[0-9]{1,6})?$' THEN
 RETURN QUERY SELECT 'invalid'::text,NULL::uuid,false; RETURN; END IF;
 END LOOP;
 -- Freeze every relation that can change visibility before the fresh READ COMMITTED query.
 LOCK TABLE freediving.review_decisions,freediving.publication_decisions,freediving.publication_policy_events,freediving.public_projection_cache IN SHARE MODE;
 PERFORM pg_advisory_xact_lock(781246916);
 SELECT c.* INTO t FROM freediving.public_projection_cache c JOIN freediving.public_results p USING(result_id)
 WHERE c.result_id=target AND encode(sha256(convert_to(c.validation_id,'UTF8')),'hex')=target_version;
 IF NOT FOUND THEN RETURN QUERY SELECT 'unavailable'::text,NULL::uuid,false; RETURN; END IF;
 SELECT * INTO old FROM freediving.correction_requests WHERE id=request_id;
 IF FOUND THEN
 IF old.client_key=client AND old.result_id=target AND old.version=target_version AND old.suggestion=suggested AND old.reason=why AND old.evidence=citation THEN
 RETURN QUERY SELECT 'pending'::text,old.id,true;
 ELSE RETURN QUERY SELECT 'conflict'::text,NULL::uuid,false; END IF; RETURN; END IF;
 SELECT * INTO old FROM freediving.correction_requests WHERE client_key=client AND result_id=target AND version=target_version AND suggestion=suggested AND reason=why AND evidence=citation LIMIT 1;
 IF FOUND THEN RETURN QUERY SELECT 'pending'::text,old.id,true; RETURN; END IF;
 DELETE FROM freediving.correction_rate_buckets WHERE window_start<=now_at-interval '1 hour';
 IF (SELECT count(*) FROM freediving.correction_rate_buckets)>=1001 AND NOT EXISTS(SELECT 1 FROM freediving.correction_rate_buckets WHERE client_key=client) THEN
 RETURN QUERY SELECT 'rate-limited'::text,NULL::uuid,false; RETURN; END IF;
 INSERT INTO freediving.correction_rate_buckets VALUES('__global',now_at,1),(client,now_at,1)
 ON CONFLICT(client_key) DO UPDATE SET attempts=LEAST(freediving.correction_rate_buckets.attempts+1,101);
 IF EXISTS(SELECT 1 FROM freediving.correction_rate_buckets WHERE (client_key=client AND attempts>5) OR (client_key='__global' AND attempts>100)) THEN
 RETURN QUERY SELECT 'rate-limited'::text,NULL::uuid,false; RETURN; END IF;
 IF (SELECT count(*) FROM freediving.correction_requests)>=10000 THEN RETURN QUERY SELECT 'capacity'::text,NULL::uuid,false; RETURN; END IF;
 INSERT INTO freediving.correction_requests(id,result_id,version,job_id,ordinal,suggestion,reason,evidence,client_key)
 VALUES(request_id,target,target_version,t.job_id,t.ordinal,suggested,why,citation,client);
 RETURN QUERY SELECT 'pending'::text,request_id,false;
END $$;
REVOKE ALL ON freediving.correction_requests,freediving.correction_rate_buckets,freediving.correction_triage FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.correction_target_version(text),freediving.submit_correction(uuid,text,text,text,text,text,text) FROM PUBLIC;

CREATE FUNCTION freediving.stamp_correction_triage() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,freediving AS $$
DECLARE previous integer;
BEGIN
 PERFORM pg_advisory_xact_lock(781246917);
 SELECT COALESCE(max(revision),0) INTO previous FROM freediving.correction_triage WHERE request_id=NEW.request_id;
 IF previous>=100 OR NEW.revision<>previous+1 THEN RAISE EXCEPTION 'Invalid triage revision'; END IF;
 IF btrim(NEW.actor)='' OR btrim(NEW.reason)='' THEN RAISE EXCEPTION 'Triage reason and actor required'; END IF;
 IF NEW.action='link-proposal' AND NOT EXISTS(
 SELECT 1 FROM freediving.correction_requests r JOIN freediving.review_proposals p USING(job_id,ordinal)
 WHERE r.id=NEW.request_id AND p.id=NEW.proposal_id) THEN RAISE EXCEPTION 'Proposal target mismatch'; END IF;
 NEW.db_role:=session_user; NEW.recorded_at:=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER stamp_correction_triage BEFORE INSERT ON freediving.correction_triage FOR EACH ROW EXECUTE FUNCTION freediving.stamp_correction_triage();
REVOKE ALL ON FUNCTION freediving.stamp_correction_triage() FROM PUBLIC;
