(ns freediving.owner-identity-route-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [freediving.athlete-identity :as identity]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.owner-decision-export :as export]
            [freediving.owner-identity-route :as route]
            [freediving.reconciliation-flow :as flow]
            [freediving.reviews :as reviews]))

(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(use-fixtures :each
  (fn [f]
    (when-not (and admin app reviewer) (throw (ex-info "Run scripts/test-postgres.sh test-owner-identity-route" {})))
    (fixture/sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! admin "observations_app")
    (reviews/migrate! admin "observations_app" "reviews_owner")
    (f)))

(deftest imported-owner-approval-binds-exact-pair-and-revision
  (let [source (fixture/synthetic 1 "owner-identity-route/1")
        job (get-in source [:artifact :job-id])
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")]
    (fixture/publish! source)
    (observations/import! app (:root source) job)
    (let [decision (identity/private-jev-decision app a b)
          revisions (export/load-observation-revisions! app [[job 0] [job 1]])
          versions (mapv revisions [[job 0] [job 1]])
          binding {:decision_id (:id decision) :reconciliation_run_revision 1
                   :reconciliation_event_id "original-flow"
                   :observation_revisions versions
                   :evidence_bindings (mapv (fn [revision]
                                              {:evidence_id (str (:job_id revision) ":" (:ordinal revision))
                                               :snapshot_record_id (apply str (repeat 64 "a"))
                                               :observation_revision revision}) versions)}
          snapshot-sha (apply str (repeat 64 "b"))
          remote {:id "owner-store:3" :store_revision 3 :binding_revision 2
                  :snapshot_sha256 snapshot-sha
                  :decision_id (:id decision) :action "approve"
                  :proposal {:selected_option "same_person" :canonical_binding binding}}
          ledger (-> (flow/empty-ledger)
                     (update :events conj {:id "original-flow" :decision-id (:id decision)})
                     (flow/append-human-event {:id "owner-store:3" :decision-id (:id decision)
                                               :status :approved :action :same-person
                                               :remote-store-revision 3 :remote-binding-revision 2
                                               :remote-snapshot-sha256 snapshot-sha
                                               :remote-event remote :actor "owner" :reason "Reviewed"}))]
      (is (= 1 (:accepted-group-count
                (route/record-imported-approval! reviewer ledger decision binding 0))))
      (is (= 1 (:accepted-group-count
                (route/record-imported-approval! reviewer ledger decision binding 0))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (route/record-imported-approval! reviewer ledger decision binding 1)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (route/record-imported-approval! reviewer ledger decision
                                                    (assoc binding :observation_revisions []) 0)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (route/record-imported-approval! reviewer
                                                    (update-in ledger [:events 1 :remote-event]
                                                               assoc :snapshot_sha256 (apply str (repeat 64 "c")))
                                                    decision binding 0)))
      (is (= 1 (:accepted-group-count (identity/private-projection app))))
      (let [reversal (assoc remote :id "owner-store:5" :store_revision 5 :action "reverse")
            reversed-ledger (flow/append-human-event
                             ledger {:id "owner-store:5" :decision-id (:id decision)
                                     :status :reversed :action :same-person
                                     :remote-store-revision 5 :remote-binding-revision 2
                                     :remote-snapshot-sha256 snapshot-sha
                                     :remote-event reversal :actor "owner"
                                     :reason "Split pair"})]
        (is (= 0 (:accepted-group-count
                  (route/record-imported-decision!
                   reviewer reversed-ledger decision binding 1))))
        (is (= 1 (count (:negative-pairs (identity/private-projection app)))))))))

(deftest imported-owner-rejection-records-negative-pair
  (let [source (fixture/synthetic 1 "owner-identity-negative/1")
        job (get-in source [:artifact :job-id])
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")]
    (fixture/publish! source)
    (observations/import! app (:root source) job)
    (let [decision (identity/private-jev-decision app a b)
          revisions (export/load-observation-revisions! app [[job 0] [job 1]])
          versions (mapv revisions [[job 0] [job 1]])
          binding {:decision_id (:id decision) :reconciliation_run_revision 1
                   :reconciliation_event_id "original-flow"
                   :observation_revisions versions
                   :evidence_bindings (mapv (fn [revision]
                                              {:evidence_id (str (:job_id revision) ":" (:ordinal revision))
                                               :snapshot_record_id (apply str (repeat 64 "a"))
                                               :observation_revision revision}) versions)}
          sha (apply str (repeat 64 "b"))
          remote {:id "owner-store:4" :store_revision 4 :binding_revision 2
                  :snapshot_sha256 sha :decision_id (:id decision)
                  :action "reject" :proposal {:canonical_binding binding}}
          ledger (-> (flow/empty-ledger)
                     (update :events conj {:id "original-flow" :decision-id (:id decision)})
                     (flow/append-human-event {:id "owner-store:4" :decision-id (:id decision)
                                               :status :rejected :action :different-person
                                               :remote-store-revision 4 :remote-binding-revision 2
                                               :remote-snapshot-sha256 sha :remote-event remote
                                               :actor "owner" :reason "Two people"}))]
      (is (= 0 (:accepted-group-count
                (route/record-imported-decision! reviewer ledger decision binding 0))))
      (is (= 1 (count (:negative-pairs (identity/private-projection app)))))
      (is (= 0 (:accepted-group-count
                (route/record-imported-decision! reviewer ledger decision binding 0)))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'freediving.owner-identity-route-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
