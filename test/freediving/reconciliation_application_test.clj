(ns freediving.reconciliation-application-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [freediving.athlete-identity :as identity]
            [freediving.owner-identity-route :as owner-identity]
            [freediving.canonical-attempt-store :as attempt-store]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy]
            [freediving.reviews :as reviews]
            [freediving.source-relationships :as relationships]
            [freediving.source-relationships-jev-test :as attempt-fixture]))

(defn signed-feed [secret feed]
  (let [payload (json/write-str feed)
        mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256"))
    {:payload_json payload :signature (.formatHex (java.util.HexFormat/of)
                                                  (.doFinal mac (.getBytes payload "UTF-8")))}))

(def config {:provider :jev :model "jev-test" :version "app-test/1"})
(defn decision [id family choices candidates]
  {:id id :family family :action (first choices) :choices choices
   :subject {:id id} :candidates candidates :dependencies []
   :evidence [{:evidence-id (str id "-evidence")
               :citation {:source-sha256 "synthetic" :locator "row 1"}
               :fact "Synthetic cited result"}]
   :evidence-adequate? true})

(deftest signed-owner-import-persists-before-canonical-identity-route
  (let [d (decision "identity" :identity
                    [:same-person :different-person :unknown] ["a" "b"])
        binding {:decision_id "identity" :reconciliation_run_revision 1
                 :reconciliation_event_id "flow-1" :observation_revisions []
                 :evidence_bindings []}
        sha (apply str (repeat 64 "a"))
        event {:id "owner-store:1" :decision_id "identity" :store_revision 1
               :binding_revision 1 :action "approve" :actor "owner"
               :reason "synthetic" :snapshot_sha256 sha
               :proposal {:selected_option "same_person" :canonical_binding binding}}
        envelope (signed-feed "private-import-token-for-test"
                              {:events [event] :store_revision 1 :next_revision 1})
        calls (atom [])]
    (with-redefs [owner-identity/record-imported-decision!
                  (fn [_ ledger decision current revision]
                    (swap! calls conj [:canonical (:id decision) current revision
                                       (count (:events ledger))])
                    {:accepted-group-count 1})]
      (let [result (application/run-imported!
                    (flow/empty-ledger) [d] envelope
                    {:config config :policy policy/default-policy
                     :persist-flow! (fn [ledger]
                                      (swap! calls conj [:persist (count (:events ledger))]))
                     :import-token "private-import-token-for-test"
                     :current-bindings {"identity" binding}
                     :active-snapshot-sha256 sha
                     :active-binding-revision 1
                     :reviewer-url "synthetic-review"
                     :identity-revisions {"identity" 0}})]
        (is (= :materialized (get-in result [:results "identity" :status])))
        (is (= [[:persist 1] [:persist 1]
                [:canonical "identity" binding 0 1]] @calls))))))

(deftest signed-owner-identity-rejection-materializes-explicit-negative
  (let [d (decision "identity" :identity
                    [:same-person :different-person :unknown] ["a" "b"])
        binding {:decision_id "identity" :reconciliation_run_revision 1
                 :reconciliation_event_id "flow-1" :observation_revisions []
                 :evidence_bindings []}
        sha (apply str (repeat 64 "a"))
        event {:id "owner-store:2" :decision_id "identity" :store_revision 2
               :binding_revision 1 :action "reject" :actor "owner"
               :reason "different people" :snapshot_sha256 sha
               :proposal {:selected_option "same_person" :canonical_binding binding}}
        imported (application/import-remote-review-events
                  (flow/empty-ledger) [d]
                  (signed-feed "private-import-token-for-test"
                               {:events [event] :store_revision 2 :next_revision 2})
                  {:import-token "private-import-token-for-test"
                   :current-bindings {"identity" binding}
                   :active-snapshot-sha256 sha :active-binding-revision 1})
        seen (atom nil)]
    (is (= :different-person (get-in (flow/inspect imported [d]) ["identity" :action])))
    (with-redefs [owner-identity/record-imported-decision!
                  (fn [_ ledger _ _ _]
                    (reset! seen (last (:events ledger)))
                    {:negative-pairs [{:pair ["a" "b"]}]})]
      (let [result (application/run! imported [d]
                                     {:config config :policy policy/default-policy
                                      :persist-flow! (fn [_] true)
                                      :reviewer-url "synthetic-review"
                                      :current-bindings {"identity" binding}
                                      :identity-revisions {"identity" 0}
                                      :imported-only? true})]
        (is (= :materialized (get-in result [:results "identity" :status])))
        (is (= :different-person (:action @seen)))))))

(deftest signed-owner-attempt-approval-routes-to-transactional-store
  (let [base (attempt-fixture/partial-ledger)
        d (relationships/attempt-jev-decision base "attempt-1" :same-attempt
                                              ["v1" "v3"] {})
        binding {:decision_id "attempt-1" :reconciliation_run_revision 1
                 :reconciliation_event_id "flow-1" :observation_revisions []
                 :evidence_bindings []}
        sha (apply str (repeat 64 "a"))
        event {:id "owner-store:8" :decision_id "attempt-1" :store_revision 8
               :binding_revision 1 :action "approve" :actor "owner"
               :reason "same dive" :snapshot_sha256 sha
               :proposal {:selected_option "same_attempt" :canonical_binding binding}}
        envelope (signed-feed "private-import-token-for-test"
                              {:events [event] :store_revision 8 :next_revision 8})
        seen (atom nil)]
    (with-redefs [attempt-store/private-ledger (fn [_] base)
                  attempt-store/record-owner-decision!
                  (fn [_ request]
                    (reset! seen request)
                    {:revision 1 :counts {:accepted-attempts 1}})]
      (let [result (application/run-imported!
                    (flow/empty-ledger) [d] envelope
                    {:config config :policy policy/default-policy
                     :persist-flow! (fn [_] true)
                     :import-token "private-import-token-for-test"
                     :current-bindings {"attempt-1" binding}
                     :active-snapshot-sha256 sha
                     :active-binding-revision 1
                     :attempt-url "synthetic-db"
                     :attempt-revisions {"attempt-1" 0}})]
        (is (= :materialized (get-in result [:results "attempt-1" :status])))
        (is (= :accept (get-in @seen [:event :action])))
        (is (= :same-attempt (get-in @seen [:event :type])))
        (is (= ["v1" "v3"] (get-in @seen [:event :pair])))
        (let [reverse-event (assoc event :id "owner-store:9" :store_revision 9
                                   :action "reverse")
              replay (application/run-imported!
                      (:flow-ledger result) [d]
                      (signed-feed "private-import-token-for-test"
                                   {:events [reverse-event] :store_revision 9
                                    :next_revision 9})
                      {:config config :policy policy/default-policy
                       :persist-flow! (fn [_] true)
                       :import-token "private-import-token-for-test"
                       :current-bindings {"attempt-1" binding}
                       :active-snapshot-sha256 sha
                       :active-binding-revision 1
                       :attempt-url "synthetic-db"
                       :attempt-revisions {"attempt-1" 1}})]
          (is (= :reversed (get-in replay [:results "attempt-1" :status])))
          (is (= :reverse (get-in @seen [:event :action])))
          (is (= "owner-store:8" (get-in @seen [:event :event-id]))))))))

(deftest signed-owner-field-approval-routes-to-transactional-review-store
  (let [d (decision "field" :category-representation
                    [:category :representation :both :neither :unknown]
                    [{:field "category"}])
        binding {:decision_id "field" :reconciliation_run_revision 1
                 :reconciliation_event_id "flow-1" :observation_revisions []
                 :evidence_bindings []}
        target {:job-id "synthetic-job" :ordinal 0
                :source-position-id "synthetic-position"
                :snapshot-record-id (apply str (repeat 64 "b"))}
        sha (apply str (repeat 64 "a"))
        event {:id "owner-store:10" :decision_id "field" :store_revision 10
               :binding_revision 1 :action "approve" :actor "owner"
               :reason "category column" :snapshot_sha256 sha
               :proposal {:id "field" :selected_option "category" :proposed ["senior"]
                          :canonical_binding binding}}
        envelope (signed-feed "private-import-token-for-test"
                              {:events [event] :store_revision 10 :next_revision 10})
        seen (atom nil)
        active-decision (atom "field")]
    (with-redefs [reviews/dive-fields (fn [_ _]
                                        {:revision 1 :category {:decision-id "owner-store:10"}})
                  reviews/dive-decision-history
                  (fn [_ _] [{:id "owner-store:10"
                              :request {:binding {:decision_id @active-decision}}}])
                  reviews/import-owner-dive-field!
                  (fn [_ request]
                    (reset! seen request)
                    {:id (:id request) :status (if (:event-id request)
                                                 :reversed :accepted)})]
      (let [result (application/run-imported!
                    (flow/empty-ledger) [d] envelope
                    {:config config :policy policy/default-policy
                     :persist-flow! (fn [_] true)
                     :import-token "private-import-token-for-test"
                     :current-bindings {"field" binding}
                     :active-snapshot-sha256 sha
                     :active-binding-revision 1
                     :reviewer-url "synthetic-review"
                     :field-targets {"field" {:target target
                                              :decision-type :category
                                              :base-revision 0}}})]
        (is (= :materialized (get-in result [:results "field" :status])))
        (is (= target (:target @seen)))
        (is (= ["senior"] (:proposed @seen)))
        (let [rejection (assoc event :id "owner-store:11" :store_revision 11
                               :action "reject")
              reversed (application/run-imported!
                        (:flow-ledger result) [d]
                        (signed-feed "private-import-token-for-test"
                                     {:events [rejection] :store_revision 11
                                      :next_revision 11})
                        {:config config :policy policy/default-policy
                         :persist-flow! (fn [_] true)
                         :import-token "private-import-token-for-test"
                         :current-bindings {"field" binding}
                         :active-snapshot-sha256 sha
                         :active-binding-revision 1
                         :reviewer-url "synthetic-review"
                         :field-targets {"field" {:target target
                                                  :decision-type :category
                                                  :base-revision 1}}})]
          (is (= :reversed (get-in reversed [:results "field" :status])))
          (is (= "owner-store:10" (:event-id @seen)))
          (reset! active-decision "another-decision")
          (is (= :unresolved
                 (get-in (application/run-imported!
                          (:flow-ledger result) [d]
                          (signed-feed "private-import-token-for-test"
                                       {:events [rejection] :store_revision 11
                                        :next_revision 11})
                          {:config config :policy policy/default-policy
                           :persist-flow! (fn [_] true)
                           :import-token "private-import-token-for-test"
                           :current-bindings {"field" binding}
                           :active-snapshot-sha256 sha
                           :active-binding-revision 1
                           :reviewer-url "synthetic-review"
                           :field-targets {"field" {:target target
                                                    :decision-type :category
                                                    :base-revision 1}}})
                         [:results "field" :status]))))))))

(deftest remote-owner-corrections-require-proof-and-current-evidence
  (let [d (decision "identity" :identity
                    [:same-person :different-person :unknown] ["a" "b"])
        sha (apply str (repeat 64 "a"))
        binding {:decision_id "identity" :reconciliation_run_revision 1
                 :reconciliation_event_id "flow-event-1" :observation_revisions []
                 :evidence_bindings []}
        event {:id "owner-store:17" :decision_id "identity" :store_revision 17
               :binding_revision 2 :action "reverse" :actor "owner"
               :reason "different athlete" :snapshot_sha256 sha
               :proposal {:selected_option "same_person" :canonical_binding binding}}
        secret "private-import-token-for-test"
        feed (signed-feed secret {:events [event] :store_revision 17 :next_revision 17})
        opts {:import-token secret :current-bindings {"identity" binding}
              :active-snapshot-sha256 sha :active-binding-revision 2}
        imported (application/import-remote-review-events
                  (flow/empty-ledger) [d] feed opts)]
    (is (= :reversed (get-in (flow/inspect imported [d]) ["identity" :status])))
    (is (= :human (get-in (flow/inspect imported [d]) ["identity" :origin])))
    (is (= imported
           (application/import-remote-review-events imported [d] feed opts)))
    (is (= :unresolved
           (get-in (application/run! imported [d]
                                     {:config config :policy policy/default-policy
                                      :persist-flow! (fn [_] true)
                                      :reviewer-url "synthetic-review"})
                   [:results "identity" :status])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/import-remote-review-events
                  (flow/empty-ledger) [d] feed {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/import-remote-review-events
                  (flow/empty-ledger) [d]
                  (assoc feed :payload_json (str (:payload_json feed) " ")) opts)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/import-remote-review-events
                  (flow/empty-ledger) [d] feed
                  (assoc opts :active-snapshot-sha256 (apply str (repeat 64 "b"))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/import-remote-review-events
                  (flow/empty-ledger) [d] feed
                  (assoc opts :active-binding-revision 3))))
    (is (= imported
           (application/import-remote-review-events
            (flow/empty-ledger) [d] feed
            (assoc opts :active-snapshot-sha256 (apply str (repeat 64 "b"))
                   :verified-snapshot-bindings {sha {"identity" binding}}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/import-remote-review-events
                  imported [d] (signed-feed secret {:events [(assoc event :id "owner-store:16" :store_revision 16)]
                                                    :store_revision 17 :next_revision 16}) opts)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/import-remote-review-events
                  (flow/empty-ledger) [d]
                  (signed-feed secret {:events [(assoc-in event [:proposal :canonical_binding :reconciliation_event_id] "wrong")]
                                       :store_revision 17 :next_revision 17}) opts)))))

(deftest remote-owner-approval-and-correction-preserve-human-action
  (let [d (decision "identity" :identity
                    [:same-person :different-person :unknown] ["a" "b"])
        sha (apply str (repeat 64 "a"))
        binding {:decision_id "identity" :reconciliation_run_revision 1
                 :reconciliation_event_id "flow-1" :observation_revisions []
                 :evidence_bindings []}
        base {:decision_id "identity" :binding_revision 2 :snapshot_sha256 sha
              :actor "owner" :reason "source checked"
              :proposal {:selected_option "same_person" :canonical_binding binding}}
        events [(assoc base :id "owner-store:3" :store_revision 3 :action "approve")
                (assoc base :id "owner-store:4" :store_revision 4 :action "correct"
                       :correction {:action "different_person"})]
        opts {:import-token "private-import-token-for-test"
              :current-bindings {"identity" binding} :active-snapshot-sha256 sha
              :active-binding-revision 2}
        envelope (signed-feed (:import-token opts)
                              {:events events :store_revision 4 :next_revision 4})
        imported (application/import-remote-review-events
                  (flow/empty-ledger) [d] envelope opts)]
    (is (= 2 (count (:events imported))))
    (is (= :human (get-in (flow/inspect imported [d]) ["identity" :origin])))
    (is (= :approved (get-in (flow/inspect imported [d]) ["identity" :status])))
    (is (= :different-person (get-in (flow/inspect imported [d]) ["identity" :action])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/import-remote-review-events
                  (flow/empty-ledger) [d]
                  (signed-feed (:import-token opts)
                               {:events [(assoc-in (first events)
                                                   [:proposal :selected_option]
                                                   "forged_choice")]
                                :store_revision 3 :next_revision 3}) opts)))))

(deftest machine-fetch-uses-service-credentials-on-exact-event-route
  (let [server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        seen (atom nil)
        signed (signed-feed "private-import-token-for-test"
                            {:events [] :store_revision 0 :next_revision 0})]
    (.createContext server "/owner-evidence/api/decision-events"
                    (reify com.sun.net.httpserver.HttpHandler
                      (handle [_ exchange]
                        (reset! seen {:path (str (.getRequestURI exchange))
                                      :client-id (.getFirst (.getRequestHeaders exchange) "CF-Access-Client-Id")
                                      :import-token (.getFirst (.getRequestHeaders exchange) "X-Freediving-Import-Token")})
                        (let [bytes (.getBytes (json/write-str signed) "UTF-8")]
                          (.sendResponseHeaders exchange 200 (alength bytes))
                          (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server)
    (try
      (let [url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            result (application/fetch-owner-review-events
                    url 0 {:access-client-id "machine.access"
                           :access-client-secret "service-test-secret"
                           :import-token "private-import-token-for-test"
                           :allow-loopback-http? true})]
        (is (= signed result))
        (is (= {:path "/owner-evidence/api/decision-events?after_revision=0"
                :client-id "machine.access" :import-token "private-import-token-for-test"}
               @seen))
        (is (thrown? clojure.lang.ExceptionInfo
                     (application/fetch-owner-review-events
                      url 0 {:access-client-id "machine.access"
                             :access-client-secret "service-test-secret"
                             :import-token "private-import-token-for-test"}))))
      (finally (.stop server 0)))))

(deftest approved-identity-routes-and-row-semantics-remains-unresolved
  (let [identity-decision (decision "identity" :identity
                                    [:same-person :different-person :unknown] ["a" "b"])
        row-decision (decision "row" :row-semantics
                               [:attempt :aggregate :summary :not-result :unknown] ["row-1"])
        calls (atom [])
        execute! (fn [request]
                   {:model "jev-test" :usage {}
                    :answers (into {}
                                   (for [id (:decision-ids request)]
                                     [id (if (= id "identity")
                                           {:type "choice" :choice "same_person" :confidence 0.99
                                            :probabilities {"same_person" 0.98 "different_person" 0.01
                                                            "unknown" 0.01}}
                                           {:type "choice" :choice "attempt" :confidence 0.99
                                            :probabilities {"attempt" 0.98 "aggregate" 0.005
                                                            "summary" 0.005 "not_result" 0.005
                                                            "unknown" 0.005}})]))})
        opts {:config config :policy policy/default-policy :execute! execute!
              :persist-flow! (fn [_] true)
              :identity-url "synthetic-db" :reviewer-url "synthetic-review"
              :identity-revisions {"identity" 0}}]
    (with-redefs [identity/record-model-event! (fn [& args]
                                                 (swap! calls conj [:approve args])
                                                 {:accepted-group-count 1})
                  identity/sync-model-correction! (fn [& args]
                                                    (swap! calls conj [:reverse args])
                                                    {:accepted-group-count 0})]
      (let [first-run (application/run! (flow/empty-ledger)
                                        [identity-decision row-decision] opts)
            corrected (flow/append-human-event (:flow-ledger first-run)
                                               {:id "owner-no" :decision-id "identity"
                                                :status :rejected :reason "not same person"})
            second-run (application/run! corrected [identity-decision row-decision] opts)]
        (is (= :materialized (get-in first-run [:results "identity" :status])))
        (is (= :unresolved (get-in first-run [:results "row" :status])))
        (is (= :no-canonical-role-ledger (get-in first-run [:results "row" :reason])))
        (is (= :reversed (get-in second-run [:results "identity" :status])))
        (is (= [:approve :reverse] (mapv first @calls)))))))

(deftest attempt-approval-replays-and-human-correction-updates-count
  (let [base (attempt-fixture/partial-ledger)
        d (relationships/attempt-jev-decision base "attempt-1" :same-attempt ["v1" "v3"] {})
        saved (atom [])
        saved-attempts (atom [])
        opts {:config config :policy (assoc-in policy/default-policy
                                               [:thresholds :same-attempt :same-attempt]
                                               {:min-confidence 0.98 :min-probability 0.97 :min-margin 0.3})
              :persist-flow! #(swap! saved conj %)
              :execute! (fn [_]
                          {:model "jev-test" :usage {}
                           :answers {"attempt-1" {:type "choice" :choice "same_attempt"
                                                  :confidence 0.99
                                                  :probabilities {"same_attempt" 0.98
                                                                  "distinct_attempts" 0.01
                                                                  "unknown" 0.01}}}})
              :persist-attempt! #(swap! saved-attempts conj %)
              :attempt-ledger base}
        _ (is (thrown? clojure.lang.ExceptionInfo
                       (application/run! (flow/empty-ledger) [d]
                                         (dissoc opts :persist-attempt!))))
        first-run (application/run! (flow/empty-ledger) [d] opts)
        replay (application/run! (:flow-ledger first-run) [d]
                                 (assoc opts :attempt-ledger (:attempt-ledger first-run)))
        corrected (flow/append-human-event (:flow-ledger replay)
                                           {:id "owner-attempt-no" :decision-id (:id d)
                                            :status :rejected :reason "distinct dives"})
        correction (application/run! corrected [d]
                                     (assoc opts :attempt-ledger (:attempt-ledger replay)))
        strict-policy (assoc-in (assoc (:policy opts) :version "policy/2")
                                [:thresholds :same-attempt :same-attempt :min-confidence] 1.0)
        stale (application/run! (:flow-ledger first-run) [d]
                                (assoc opts :policy strict-policy
                                       :attempt-ledger (:attempt-ledger first-run)))]
    (is (= :applied (get-in first-run [:results "attempt-1" :status])))
    (is (= 1 (get-in (relationships/project-attempts (:attempt-ledger first-run))
                     [:counts :accepted-attempts])))
    (is (= :replayed (get-in replay [:results "attempt-1" :status])))
    (is (= :reversed (get-in correction [:results "attempt-1" :status])))
    (is (= 0 (get-in (relationships/project-attempts (:attempt-ledger correction))
                     [:counts :accepted-attempts])))
    (is (= :reversed (get-in stale [:results "attempt-1" :status])))
    (is (= 0 (get-in (relationships/project-attempts (:attempt-ledger stale))
                     [:counts :accepted-attempts])))
    (is (= 5 (count @saved)))
    (is (= 3 (count @saved-attempts)))))

(deftest field-classification-routes-and-both-meaning-stays-explicit
  (let [d (decision "field" :category-representation
                    [:category :representation :both :neither :unknown] [{:field "category"}])
        calls (atom [])
        target {:source-position-id "position-1" :job-id "job-1" :ordinal 0}
        dictionary {:version "dictionary/1" :federation "CMAS" :event-id "event-1"}
        answer (fn [choice]
                 {:model "jev-test" :usage {}
                  :answers {"field" {:type "choice" :choice choice :confidence 0.99
                                     :probabilities (if (= choice "both")
                                                      {"category" 0.005 "representation" 0.005
                                                       "both" 0.98 "neither" 0.005 "unknown" 0.005}
                                                      {"category" 0.98 "representation" 0.005
                                                       "both" 0.005 "neither" 0.005 "unknown" 0.005})}}})
        opts {:config config :policy policy/default-policy :persist-flow! (fn [_] true)
              :execute! (fn [_] (answer "category"))
              :field-url "synthetic-db" :reviewer-url "synthetic-review"
              :field-targets {"field" {:target target :decision-type :category
                                       :dictionary dictionary :base-revision 0}}}]
    (with-redefs [reviews/approve-jev-dive-field! (fn [& args]
                                                    (swap! calls conj [:approve args])
                                                    {:id "model-field"})
                  reviews/dive-fields (fn [& args]
                                        (swap! calls conj [:read args])
                                        {:revision 1})
                  reviews/sync-human-jev-dive-field! (fn [& args]
                                                       (swap! calls conj [:reverse args])
                                                       {:id "human-reverse"})
                  reviews/invalidate-jev-dive-field! (fn [& args]
                                                       (swap! calls conj [:invalidate args])
                                                       {:id "model-invalidate"})]
      (let [approved (application/run! (flow/empty-ledger) [d] opts)
            human (flow/append-human-event (:flow-ledger approved)
                                           {:id "owner-field-no" :decision-id "field"
                                            :origin :human :status :reversed
                                            :actor "owner" :reason "wrong column"})
            reversed (application/run! human [d] opts)
            both (application/run! (flow/empty-ledger) [d]
                                   (assoc opts :execute! (fn [_] (answer "both"))))
            stricter (assoc-in (assoc (:policy opts) :version "policy/2")
                               [:thresholds :category-representation :category :min-confidence] 1.0)
            stale (application/run! (:flow-ledger approved) [d]
                                    (assoc opts :policy stricter))]
        (is (= :materialized (get-in approved [:results "field" :status])))
        (is (= :reversed (get-in reversed [:results "field" :status])))
        (is (= [:approve :read :reverse :read :invalidate] (mapv first @calls)))
        (is (= :unresolved (get-in both [:results "field" :status])))
        (is (= :both-requires-separate-source-bound-fields
               (get-in both [:results "field" :reason])))
        (is (= :reversed (get-in stale [:results "field" :status])))
        (is (= :unresolved (:current-flow-status (nth (second (last @calls)) 3))))))))

(deftest child-before-parent-materializes-in-order-and-then-invalidates
  (let [parent (decision "parent" :identity
                         [:same-person :different-person :unknown] ["a" "b"])
        child (assoc (decision "child" :identity
                               [:same-person :different-person :unknown] ["c" "d"])
                     :dependencies ["parent"])
        calls (atom [])
        fail-parent? (atom false)
        opts {:config config :policy policy/default-policy
              :identity-url "synthetic-db" :reviewer-url "synthetic-review"
              :identity-revisions {"parent" 0 "child" 0}
              :persist-flow! (fn [_] true)
              :execute! (fn [request]
                          {:model "jev-test" :usage {}
                           :answers (into {}
                                          (for [id (:decision-ids request)]
                                            [id {:type "choice" :choice "same_person" :confidence 0.99
                                                 :probabilities {"same_person" 0.98
                                                                 "different_person" 0.01
                                                                 "unknown" 0.01}}]))})}]
    (with-redefs [identity/record-model-event! (fn [_ _ d & _]
                                                 (swap! calls conj [:approve (:id d)])
                                                 (when (and @fail-parent? (= "parent" (:id d)))
                                                   (throw (ex-info "parent unavailable" {})))
                                                 {:accepted-group-count 1})
                  identity/sync-model-correction! (fn [_ _ d & _]
                                                    (swap! calls conj [:human-reverse (:id d)])
                                                    {:accepted-group-count 0})
                  identity/private-projection (fn [_] {:revision 2})
                  identity/invalidate-model-dependency! (fn [_ _ d & _]
                                                          (swap! calls conj [:dependency-reverse (:id d)])
                                                          {:accepted-group-count 0})]
      (let [approved (application/run! (flow/empty-ledger) [child parent] opts)
            corrected (flow/append-human-event (:flow-ledger approved)
                                               {:id "owner-parent-no" :decision-id "parent"
                                                :status :rejected :reason "bad parent"})
            second-run (application/run! corrected [child parent] opts)
            _ (reset! fail-parent? true)
            canonical-failure (application/run! (:flow-ledger approved) [child parent] opts)]
        (is (= [[:approve "parent"] [:approve "child"]
                [:human-reverse "parent"] [:dependency-reverse "child"]
                [:approve "parent"] [:dependency-reverse "child"]]
               @calls))
        (is (= :reversed (get-in second-run [:results "child" :status])))
        (is (= :reversed (get-in canonical-failure [:results "child" :status])))))))

(deftest dispatch-is-durable-before-provider-call
  (let [d (decision "durable" :identity
                    [:same-person :different-person :unknown] ["a" "b"])
        order (atom [])
        _ (application/run! (flow/empty-ledger) [d]
                            {:config config :policy policy/default-policy
                             :persist-flow! (fn [_] (swap! order conj :durable))
                             :checkpoint! (fn [_] (swap! order conj :extra-checkpoint))
                             :execute! (fn [_]
                                         (swap! order conj :provider)
                                         {:model "jev-test" :usage {}
                                          :answers {"durable" {:type "choice" :choice "unknown"
                                                               :confidence 0.99
                                                               :probabilities {"same_person" 0.01
                                                                               "different_person" 0.01
                                                                               "unknown" 0.98}}}})})]
    (is (= [:durable :extra-checkpoint :provider :durable] @order))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-application-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
