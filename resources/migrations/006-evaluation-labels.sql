-- Corpus mode is installed by the trusted administrator, never by a label request.
CREATE TABLE freediving.evaluation_corpus (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 corpus_id text NOT NULL, mode text NOT NULL CHECK(mode IN ('synthetic','real'))
);
CREATE TABLE freediving.evaluation_labels (
 id text PRIMARY KEY, pair_key text NOT NULL,
 revision integer NOT NULL CHECK(revision > 0),
 outcome text NOT NULL CHECK(outcome IN ('match','no-match','unknown','revoke')),
 body_edn text NOT NULL, db_role text NOT NULL DEFAULT session_user,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(pair_key,revision)
);
CREATE TRIGGER immutable_evaluation_corpus BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.evaluation_corpus FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE TRIGGER immutable_evaluation_labels BEFORE UPDATE OR DELETE OR TRUNCATE ON freediving.evaluation_labels FOR EACH STATEMENT EXECUTE FUNCTION freediving.reject_mutation();
CREATE FUNCTION freediving.stamp_evaluation_label() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.db_role := session_user; NEW.recorded_at := clock_timestamp(); RETURN NEW; END $$;
CREATE TRIGGER stamp_evaluation_label BEFORE INSERT ON freediving.evaluation_labels FOR EACH ROW EXECUTE FUNCTION freediving.stamp_evaluation_label();
REVOKE ALL ON freediving.evaluation_corpus,freediving.evaluation_labels FROM PUBLIC;
REVOKE ALL ON FUNCTION freediving.stamp_evaluation_label() FROM PUBLIC;
