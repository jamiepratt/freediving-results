(ns freediving.reviews-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.shell :as shell]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(use-fixtures :each (fn [f]
                      (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! fixture/admin "observations_app")
                      (reviews/migrate! fixture/admin "observations_app" "reviews_owner") (f)))
(defn sample []
  (let [{:keys [root artifact] :as s} (fixture/synthetic 1 "review-synthetic/1")]
    (fixture/publish! s) (observations/import! fixture/app root (:job-id artifact))
    {:job-id (:job-id artifact) :ordinal 0}))
(defn proposal [target id]
  (merge target {:id id :base-revision 0 :category :identity-matching :field :identity
                 :before {:outcome :unknown} :after {:outcome :matched :identity-id "synthetic-person-1"}
                 :evidence [{:page 1 :line 1}] :reason "Synthetic evidence" :actor "test-proposer"}))
(deftest pending-approval-and-reversal-retain-original
  (let [target (sample) before (observations/inspect fixture/app (:job-id target))
        p (reviews/propose! fixture/app (proposal target "p1"))]
    (is (= 0 (:revision (reviews/effective fixture/app target))))
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target))))
    (is (= p (reviews/propose! fixture/app (proposal target "p1"))))
    (reviews/decide! reviewer {:id "a1" :proposal-id "p1" :action :approve :base-revision 0 :actor "owner" :reason "Checked synthetic evidence"})
    (is (= {:outcome :matched :identity-id "synthetic-person-1"} (:identity (reviews/effective fixture/app target))))
    (reviews/decide! reviewer {:id "r1" :event-id "a1" :action :reverse :base-revision 1 :actor "owner" :reason "Undo synthetic decision"})
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target))))
    (is (= 3 (count (reviews/history fixture/app target))))
    (is (= (:observations before) (:observations (observations/inspect fixture/app (:job-id target)))))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.reviews-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(deftest rejection-corrections-and-stacked-reversals
  (let [t (sample) p (proposal t "p1")]
    (reviews/propose! fixture/app p)
    (reviews/decide! reviewer {:id "reject" :proposal-id "p1" :action :reject :base-revision 0 :actor "owner" :reason "No support"})
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))
    (doseq [[id base before after] [["p2" 1 "Éxample" "Example"] ["p3" 2 "Example" "Example A"]]]
      (reviews/propose! fixture/app (merge p {:id id :base-revision base :category :name-normalization :field :source-name :before before :after after}))
      (reviews/decide! reviewer {:id (str "a" id) :proposal-id id :action :approve :base-revision base :actor "owner" :reason "Synthetic normalization"}))
    (is (= "Example A" (get-in (reviews/effective fixture/app t) [:fields :source-name])))
    (is (thrown-with-msg? Exception #"active" (reviews/decide! reviewer {:id "bad-reverse" :event-id "ap2" :action :reverse :base-revision 3 :actor "owner" :reason "Not top"})))
    (doseq [[id event base expected] [["r3" "ap3" 3 "Example"] ["r2" "ap2" 4 "Éxample"]]]
      (reviews/decide! reviewer {:id id :event-id event :action :reverse :base-revision base :actor "owner" :reason "Undo"})
      (is (= expected (get-in (reviews/effective fixture/app t) [:fields :source-name]))))
    (is (thrown-with-msg? Exception #"active" (reviews/decide! reviewer {:id "repeat" :event-id "ap2" :action :reverse :base-revision 5 :actor "owner" :reason "Again"})))))
(deftest conflicting-concurrent-approvals-and-idempotency
  (let [t (sample) p (proposal t "p1")]
    (reviews/propose! fixture/app p)
    (reviews/propose! fixture/app (assoc p :id "p2" :after {:outcome :no-match}))
    (let [gate (promise) requests (mapv #(hash-map :id (str "a" %) :proposal-id % :action :approve :base-revision 0 :actor "owner" :reason "Check") ["p1" "p2"])
          workers (mapv (fn [r] (future @gate (try (reviews/decide! reviewer r) (catch Exception _ :conflict)))) requests)]
      (deliver gate true)
      (let [results (mapv deref workers) winner (first (filter map? results)) request (:request winner)]
        (is (= 1 (count (filter #{:conflict} results))))
        (is (= 1 (:revision (reviews/effective fixture/app t))))
        (is (= winner (reviews/decide! reviewer request)))
        (is (thrown-with-msg? Exception #"idempotency" (reviews/decide! reviewer (assoc request :reason "changed"))))))
    (is (thrown-with-msg? Exception #"idempotency" (reviews/propose! fixture/app (assoc p :reason "changed"))))))
(deftest invalid-proposals-and-privileges
  (let [t (sample) p (proposal t "p1")]
    (doseq [[change message] [[#(assoc % :ordinal 99) #"Unknown"]
                              [#(assoc % :evidence []) #"evidence"]
                              [#(assoc % :evidence [{:page 99 :line 1}]) #"evidence"]
                              [#(assoc % :before nil) #"Before"]
                              [#(assoc % :base-revision 1) #"Stale"]
                              [#(assoc % :category :automatic) #"category"]
                              [#(assoc % :after {:outcome :matched}) #"identity"]]]
      (is (thrown-with-msg? Exception message (reviews/propose! fixture/app (change p)))))
    (reviews/propose! fixture/app p)
    (is (thrown-with-msg? Exception #"capability" (reviews/decide! fixture/app {:id "a1" :proposal-id "p1" :action :approve :base-revision 0 :actor "owner" :reason "forged actor"})))
    (doseq [url [fixture/app reviewer] table ["review_proposals" "review_decisions"] op ["UPDATE %s SET id=id" "DELETE FROM %s" "TRUNCATE %s"]]
      (is (thrown? java.sql.SQLException (fixture/sql! url (format op (str "freediving." table))))))
    (is (thrown? java.sql.SQLException (fixture/sql! fixture/app "INSERT INTO freediving.review_decisions(id,job_id,ordinal,revision,action,body_edn) VALUES('fake','x',0,1,'approve','{}')")))
    (is (= 0 (:revision (reviews/effective fixture/app t))))))
(deftest forged-ingest-proposal-cannot-cross-observation-envelope
  (let [t (sample) forged (assoc (proposal t "body-id") :ordinal 1 :action :propose)]
    (with-open [c (java.sql.DriverManager/getConnection fixture/app)
                s (.prepareStatement c "INSERT INTO freediving.review_proposals(id,job_id,ordinal,body_edn) VALUES('sql-id',?,0,?)")]
      (.setString s 1 (:job-id t)) (.setString s 2 (pr-str forged)) (.executeUpdate s))
    (is (thrown-with-msg? Exception #"envelope" (reviews/decide! reviewer {:id "forged-approval" :proposal-id "sql-id" :action :approve :base-revision 0 :actor "owner" :reason "Must reject forged body"})))
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))))
(deftest substantive-and-repair-fields-preserve-artifact-bytes
  (let [t (sample) original (observations/inspect fixture/app (:job-id t)) p (proposal t "correction")]
    (doseq [[id base category field before after] [["correction" 0 :substantive-correction :performance 1 2]
                                                   ["repair" 1 :extraction-repair :unit nil "m"]]]
      (reviews/propose! fixture/app (merge p {:id id :base-revision base :category category :field field :before before :after after}))
      (reviews/decide! reviewer {:id (str "approve-" id) :proposal-id id :action :approve :base-revision base :actor "owner" :reason "Synthetic source check"}))
    (is (= 2 (get-in (reviews/effective fixture/app t) [:fields :performance])))
    (is (= "m" (get-in (reviews/effective fixture/app t) [:fields :unit])))
    (is (= (seq (:artifact-bytes original)) (seq (:artifact-bytes (observations/inspect fixture/app (:job-id t))))))
    (let [p (first (reviews/history fixture/app t))]
      (is (= "observations_app" (:db-role p)))
      (is (= (:job-id t) (get-in p [:observation :job-id])))
      (is (= 64 (count (get-in p [:observation :artifact-sha256])))))))
(deftest migration-checksum-and-role-boundaries
  (is (= {:schema-version 2} (reviews/migrate! fixture/admin "observations_app" "reviews_owner")))
  (is (thrown-with-msg? Exception #"Separate" (reviews/migrate! fixture/admin "observations_app" "observations_app")))
  (fixture/sql! fixture/admin "UPDATE freediving.schema_migrations SET sha256='tampered' WHERE version=2")
  (is (thrown-with-msg? Exception #"checksum" (reviews/migrate! fixture/admin "observations_app" "reviews_owner"))))
(deftest fragments-and-unclassified-cannot-be-reviewed
  (let [t (sample)]
    ;; Synthetic fixture only: manufacture immutable non-result observations under separate ordinals.
    (doseq [[ordinal kind] [[2 "fragment"] [3 "unclassified"]]]
      (fixture/sql! fixture/admin (str "INSERT INTO freediving.observations(job_id,ordinal,candidate_id,kind,classification_reason,payload_edn) VALUES('" (:job-id t) "'," ordinal ",'synthetic-" ordinal "','" kind "','test','{}')"))
      (is (thrown-with-msg? Exception #"result-row" (reviews/propose! fixture/app (proposal (assoc t :ordinal ordinal) (str "p" ordinal))))))))
(deftest public-role-and-reapplied-role-grants
  (let [t (sample) p (proposal t "p1") public (System/getenv "FREEDIVING_TEST_PUBLIC_URL")]
    (reviews/propose! fixture/app p)
    (fixture/sql! fixture/admin "GRANT USAGE ON SCHEMA freediving TO reviews_public")
    (fixture/sql! fixture/admin "GRANT SELECT ON ALL TABLES IN SCHEMA freediving TO reviews_public")
    (is (= 0 (:revision (reviews/effective public t))))
    (is (thrown-with-msg? Exception #"capability" (reviews/decide! public {:id "a" :proposal-id "p1" :action :approve :base-revision 0 :actor "owner" :reason "No authority"})))
    (fixture/sql! fixture/admin "GRANT INSERT ON freediving.observations TO reviews_owner")
    (fixture/sql! fixture/admin "GRANT CREATE ON SCHEMA freediving TO reviews_owner,observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (is (thrown? java.sql.SQLException (fixture/sql! reviewer "INSERT INTO freediving.observations SELECT * FROM freediving.observations LIMIT 0")))
    (is (thrown? java.sql.SQLException (fixture/sql! reviewer "CREATE TABLE freediving.forbidden(x int)")))
    (is (thrown? java.sql.SQLException (fixture/sql! fixture/app "CREATE TABLE freediving.forbidden(x int)")))))
(deftest forged-provenance-is-not-approved
  (let [t (sample) p (reviews/propose! fixture/app (proposal t "valid"))
        forged (assoc p :id "forged" :observation (assoc (:observation p) :source-sha256 "fake"))]
    (with-open [c (java.sql.DriverManager/getConnection fixture/app)
                s (.prepareStatement c "INSERT INTO freediving.review_proposals(id,job_id,ordinal,body_edn) VALUES('forged',?,0,?)")]
      (.setString s 1 (:job-id t)) (.setString s 2 (pr-str forged)) (.executeUpdate s))
    (is (thrown-with-msg? Exception #"provenance" (reviews/decide! reviewer {:id "a" :proposal-id "forged" :action :approve :base-revision 0 :actor "owner" :reason "Forged provenance"})))
    (is (= 0 (:revision (reviews/effective fixture/app t))))))
(deftest request-metadata-cannot-fabricate-history
  (let [t (sample) p (proposal t "p")]
    (is (thrown-with-msg? Exception #"Unexpected" (reviews/propose! fixture/app (assoc p :db-role "owner"))))
    (reviews/propose! fixture/app p)
    (is (thrown-with-msg? Exception #"Unexpected" (reviews/decide! reviewer {:id "a" :proposal-id "p" :event-id "unrelated" :action :approve :base-revision 0 :actor "owner" :reason "Check"})))))
(deftest concurrent-retries-and-explicit-no-match
  (let [t (sample) p (assoc (proposal t "p") :after {:outcome :no-match})
        request {:id "a" :proposal-id "p" :action :approve :base-revision 0 :actor "owner" :reason "No matching identity"}]
    (reviews/propose! fixture/app p)
    (let [gate (promise) workers (mapv (fn [_] (future @gate (reviews/decide! reviewer request))) (range 4))]
      (deliver gate true)
      (is (apply = (mapv deref workers))))
    (is (= {:outcome :no-match} (:identity (reviews/effective fixture/app t))))
    (let [r {:id "r" :event-id "a" :action :reverse :base-revision 1 :actor "owner" :reason "Reconsider"}
          result (reviews/decide! reviewer r)]
      (is (= result (reviews/decide! reviewer r)))
      (is (thrown-with-msg? Exception #"Stale" (reviews/decide! reviewer (assoc r :id "stale")))))
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))
    (reviews/propose! fixture/app (assoc (proposal t "p2") :base-revision 2))
    (let [r {:id "reject" :proposal-id "p2" :action :reject :base-revision 2 :actor "owner" :reason "Unresolved"}
          result (reviews/decide! reviewer r)]
      (is (= result (reviews/decide! reviewer r))))
    (is (= 3 (:revision (reviews/effective fixture/app t))))))
(deftest cli-requires-exactly-one-edn-request
  (let [file (java.io.File/createTempFile "review-request-" ".edn")]
    (try
      (spit file "{:job-id \"synthetic\" :ordinal 0} {:ignored \"second request\"}")
      (let [r (shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "freediving.reviews" "effective" (.getPath file))]
        (is (= 1 (:exit r)))
        (is (re-find #"Expected one EDN request" (:err r))))
      (finally (.delete file)))))
