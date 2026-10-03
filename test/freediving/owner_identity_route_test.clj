(ns freediving.owner-identity-route-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.data.json :as json]
            [freediving.athlete-identity :as identity]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.owner-decision-export :as export]
            [freediving.owner-identity-route :as route]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-flow :as flow]
            [freediving.reviews :as reviews]))

(defn signed-feed [secret feed]
  (let [payload (json/write-str feed)
        mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256"))
    {:payload_json payload
     :signature (.formatHex (java.util.HexFormat/of)
                            (.doFinal mac (.getBytes payload "UTF-8")))}))

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

(deftest signed-owner-runner-persists-before-real-identity-write
  (let [source (fixture/synthetic 1 "owner-signed-runner/1")
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
          owner {:id "owner-store:4" :store_revision 4 :binding_revision 2
                 :snapshot_sha256 sha :decision_id (:id decision)
                 :action "approve" :actor "owner" :reason "same athlete"
                 :proposal {:selected_option "same_person" :canonical_binding binding}}
          base (update (flow/empty-ledger) :events conj
                       {:id "original-flow" :decision-id (:id decision)})
          persisted (atom [])
          opts {:config {:version "synthetic/1"} :policy {:version "synthetic/1"}
                :persist-flow! #(swap! persisted conj %)
                :import-token "private-import-token-for-test"
                :current-bindings {(:id decision) binding}
                :active-snapshot-sha256 sha :active-binding-revision 2
                :reviewer-url reviewer
                :identity-revisions {(:id decision) 0}}
          feed (signed-feed (:import-token opts)
                            {:events [owner] :store_revision 4 :next_revision 4})
          result (application/run-imported! base [decision] feed opts)]
      (is (= :materialized (get-in result [:results (:id decision) :status])))
      (is (= 1 (:accepted-group-count (identity/private-projection app))))
      (is (= 2 (count @persisted)))
      (is (= 2 (count (:events (first @persisted))))))))

(deftest signed-source-owner-action-projects-and-reverses-without-pg-observation
  (let [sha (apply str (repeat 64 "d"))
        rows (mapv (fn [record]
                     (let [ref {:kind "source-derived" :snapshot_sha256 sha
                                :snapshot_record_id record :source_name "synthetic-aida"
                                :source_sha256 (apply str (repeat 64 "a"))
                                :packet_sha256 (apply str (repeat 64 "b"))
                                :citation {:date "2026-01-01" :row record}
                                :adapter_version "test-adapter/1"
                                :observation_version (apply str (repeat 64 "c"))}]
                       {:observation-id (str "source-observation:" record)
                        :source-name "Synthetic Diver" :parse-status :parsed
                        :citation ref :source-observation-ref ref}))
                   [(apply str (repeat 64 "1")) (apply str (repeat 64 "2"))])
        refs (into {} (map (juxt :observation-id :citation) rows))
        registration {:snapshot-sha256 sha :rows rows :verified-refs refs}
        [a b] (mapv :observation-id rows)
        decision (identity/jev-decision (identity/empty-ledger rows) a b)
        binding {:decision_id (:id decision) :reconciliation_run_revision 1
                 :reconciliation_event_id "original-flow"
                 :observation_revisions (mapv refs [a b])
                 :evidence_bindings (mapv (fn [item source-id]
                                            {:evidence_id (:evidence-id item)
                                             :snapshot_record_id (get-in refs [source-id :snapshot_record_id])
                                             :observation_revision (refs source-id)})
                                          (:evidence decision) [a b])}
        owner {:id "owner-store:3" :store_revision 3 :binding_revision 2
               :snapshot_sha256 sha :decision_id (:id decision) :action "approve"
               :actor "owner" :reason "Source cited"
               :proposal {:selected_option "same_person" :canonical_binding binding}}
        base (update (flow/empty-ledger) :events conj
                     {:id "original-flow" :decision-id (:id decision)})
        opts {:config {:version "synthetic/1"} :policy {:version "synthetic/1"}
              :persist-flow! (fn [_]) :import-token "private-import-token-for-test"
              :current-bindings {(:id decision) binding}
              :active-snapshot-sha256 sha :active-binding-revision 2
              :reviewer-url reviewer :source-registration registration}
        feed (signed-feed (:import-token opts)
                          {:events [owner] :store_revision 3 :next_revision 3})
        forged-ref (assoc (refs a) :observation_version (apply str (repeat 64 "f")))
        forged-binding (-> binding
                           (assoc-in [:observation_revisions 0] forged-ref)
                           (assoc-in [:evidence_bindings 0 :observation_revision] forged-ref))
        forged-owner (assoc-in owner [:proposal :canonical_binding] forged-binding)
        forged (application/run-imported! base [decision]
                                          (signed-feed (:import-token opts)
                                                       {:events [forged-owner]
                                                        :store_revision 3 :next_revision 3})
                                          (assoc-in opts [:current-bindings (:id decision)]
                                                    forged-binding))
        approved (application/run-imported! base [decision] feed opts)]
    (is (= :unresolved (get-in forged [:results (:id decision) :status])))
    (is (= :materialized (get-in approved [:results (:id decision) :status])))
    (is (= 1 (:accepted-group-count (identity/private-canonical-view reviewer))))
    (is (= :materialized (get-in (application/run-imported! base [decision] feed opts)
                                 [:results (:id decision) :status])))
    (let [reverse-event (assoc owner :id "owner-store:4" :store_revision 4
                               :action "reverse" :reason "Different people")
          reversed (application/run-imported! (:flow-ledger approved) [decision]
                                              (signed-feed (:import-token opts)
                                                           {:events [reverse-event]
                                                            :store_revision 4 :next_revision 4}) opts)]
      (is (= :reversed (get-in reversed [:results (:id decision) :status])))
      (is (= 0 (:accepted-group-count (identity/private-canonical-view reviewer))))
      (is (= 1 (count (:negative-pairs (identity/private-projection reviewer))))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'freediving.owner-identity-route-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
