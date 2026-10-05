(ns freediving.canonical-attempt-store-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [freediving.canonical-attempt-store :as store]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]
            [freediving.source-relationships :as relationships]
            [freediving.source-relationships-test :as source-fixture]))

(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))
(use-fixtures :each
  (fn [f]
    (when-not (and admin app) (throw (ex-info "Run scripts/test-postgres.sh test-canonical-attempt-store" {})))
    (fixture/sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! admin "observations_app")
    (reviews/migrate! admin "observations_app" "reviews_owner")
    (f)))

(deftest durable-attempt-revision-and-private-count-recover
  (let [base (source-fixture/attempt-fixture)
        revision {:id "source-revision" :action :accept :type :source-revision
                  :pair ["official" "mirror"]
                  :evidence {:kind :publisher-correction :predecessor "official"
                             :successor "mirror"
                             :citation {:source-id "mirror" :locator "header"
                                        :text "Publisher correction notice"}}}]
    (is (= 0 (:revision (store/persist! app base))))
    (let [linked (relationships/append-attempt-event base revision)]
      (is (= 1 (:revision (store/persist! app linked))))
      (is (= 1 (:revision (store/persist! app linked))))
      (is (= linked (store/private-ledger app)))
      (is (= (relationships/project-attempts linked) (store/private-projection app)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/persist! app (relationships/append-attempt-event
                                        base {:id "other-revision" :action :accept
                                              :type :source-revision :pair ["official" "mirror"]
                                              :evidence (:evidence revision)}))))
      (is (= 1 (:revision (store/private-projection app))))
      (fixture/sql! app "UPDATE freediving.canonical_attempt_state SET projection_edn='{}'")
      (is (thrown? clojure.lang.ExceptionInfo (store/private-projection app)))
      (is (thrown? clojure.lang.ExceptionInfo (store/private-ledger app)))
      (is (thrown? clojure.lang.ExceptionInfo (store/persist! app linked)))
      (is (= (relationships/project-attempts linked) (store/rebuild! app)))
      (is (= (relationships/project-attempts linked) (store/private-projection app)))
      (let [automatic-id (get-in (store/private-projection app) [:automatic-links 0 :id])
            split (relationships/append-attempt-event
                   linked {:id "owner-split" :action :reverse :event-id automatic-id})]
        (is (= 2 (get-in (store/persist! app split) [:counts :accepted-attempts])))
        (is (= 2 (get-in (store/private-projection app) [:counts :accepted-attempts])))
        (is (= "70m" (get-in (store/private-ledger app)
                             [:observation-versions "v1" :values :raw-performance])))
        (is (= "1m" (get-in (store/private-ledger app)
                            [:observation-versions "v1" :values :penalty])))
        (is (= (relationships/project-attempts split) (store/rebuild! app)))))))

(deftest signed-owner-attempt-event-requires-exact-current-endpoints
  (let [revision (fn [id sha]
                   {:job_id (str "job-" id) :ordinal 0 :candidate_id id
                    :artifact_sha256 (str "artifact-" id) :source_sha256 sha
                    :parser_version "parser/1"})
        base (-> (source-fixture/attempt-fixture)
                 (assoc-in [:observation-versions "v1" :observation-revision]
                           (revision "v1" "aa"))
                 (assoc-in [:observation-versions "v3" :observation-revision]
                           (revision "v3" "bb")))
        pair ["v1" "v3"]
        binding {:decision_id "owner-same-attempt"
                 :evidence_bindings (mapv (fn [id]
                                            {:evidence_id id :observation_revision
                                             (get-in base [:observation-versions id :observation-revision])}) pair)}
        proposal {:canonical_binding binding}
        owner {:id "owner-store:9" :store_revision 9 :decision_id "owner-same-attempt"
               :action "approve" :proposal (assoc proposal :selected_option "same_attempt")}
        event {:id "owner-store:9" :action :accept :type :same-attempt
               :pair pair :evidence {:kind :verified-scope}}
        request {:event event :owner-event owner :binding binding
                 :subject-snapshot (relationships/attempt-subjects base :same-attempt pair)
                 :expected-revision 0}]
    (store/persist! app base)
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/record-owner-decision!
                  app (assoc request :owner-event
                             (assoc-in owner [:proposal :selected_option]
                                       "distinct_attempts")))))
    (is (= 1 (:revision (store/record-owner-decision! app request))))
    (is (= 1 (:revision (store/record-owner-decision!
                         app (assoc request :expected-revision 1)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/record-owner-decision!
                  app (assoc request :owner-event (assoc owner :id "forged")))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/record-owner-decision!
                  app (assoc request :event (assoc event :id "owner-store:10")
                             :owner-event (assoc owner :id "owner-store:10" :store_revision 10)
                             :expected-revision 0))))
    (let [source-pair ["official" "mirror"]
          source-binding {:decision_id "owner-source-revision"
                          :evidence_bindings (mapv (fn [[source-id version-id]]
                                                     {:evidence_id source-id
                                                      :observation_revision
                                                      (get-in base [:observation-versions version-id
                                                                    :observation-revision])})
                                                   [["official" "v1"] ["mirror" "v3"]])}
          source-owner {:id "owner-store:10" :store_revision 10
                        :decision_id "owner-source-revision" :action "approve"
                        :proposal {:canonical_binding source-binding
                                   :selected_option "right_revises_left"}}
          source-event {:id "owner-store:10" :action :accept :type :source-revision
                        :pair source-pair
                        :evidence {:kind :publisher-correction :predecessor "official"
                                   :successor "mirror"
                                   :citation {:source-id "mirror" :locator "header"
                                              :text "Publisher correction notice"}}}
          source-request {:event source-event :owner-event source-owner
                          :binding source-binding :expected-revision 1
                          :subject-snapshot
                          (relationships/attempt-subjects base :source-revision source-pair)}]
      (is (= 2 (:revision (store/record-owner-decision! app source-request))))
      (is (= 1 (count (:source-relationships (store/private-projection app)))))
      (let [correct-owner (assoc source-owner :id "owner-store:12"
                                 :store_revision 12 :action "correct"
                                 :correction {:action "unrelated"})
            correct-request (assoc source-request
                                   :event {:id "owner-store:12" :action :reverse
                                           :type :source-revision :pair source-pair
                                           :event-id "owner-store:10"}
                                   :owner-event correct-owner :expected-revision 2)]
        (is (= 3 (:revision (store/record-owner-decision! app correct-request))))
        (is (empty? (:source-relationships (store/private-projection app))))
        (is (= 3 (:revision (store/record-owner-decision! app correct-request))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (store/record-owner-decision!
                      app (assoc correct-request :owner-event
                                 (assoc correct-owner :correction {:action "right_revises_left"})))))))
    (let [reverse-request (-> request
                              (assoc :event {:id "owner-store:11" :action :reverse
                                             :type :same-attempt :pair pair
                                             :event-id "owner-store:9"}
                                     :owner-event (assoc owner :id "owner-store:11"
                                                         :store_revision 11 :action "reverse")
                                     :expected-revision 3))]
      (is (= 4 (:revision (store/record-owner-decision! app reverse-request))))
      (is (= 4 (:revision (store/record-owner-decision! app reverse-request))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/record-owner-decision!
                    app (assoc reverse-request :owner-event
                               (assoc (:owner-event reverse-request) :action "reject")))))
      (is (= 4 (:revision (store/private-projection app)))))
    (is (= (store/private-projection app) (store/rebuild! app)))))

(deftest cited-views-of-one-snapshot-row-project-one-reversible-attempt
  (let [snapshot-row "frozen-result-91"
        revision (fn [source-sha view]
                   {:kind "source-derived"
                    :adapter_version "cmas-microplus-attempt-evidence/1"
                    :snapshot_record_id snapshot-row
                    :source_sha256 source-sha
                    :citation {:json_pointer view}
                    :observation_version view})
        base (-> (source-fixture/attempt-fixture)
                 (assoc-in [:observation-versions "v1" :observation-revision]
                           (revision "aa" "/0"))
                 (assoc-in [:observation-versions "v3" :observation-revision]
                           (revision "bb" "/1")))
        pair ["v1" "v3"]
        binding {:decision_id "one-result-two-views"
                 :evidence_bindings (mapv (fn [id]
                                            {:evidence_id id
                                             :snapshot_record_id snapshot-row
                                             :observation_revision
                                             (get-in base [:observation-versions id :observation-revision])})
                                          pair)}
        owner {:id "owner-store:9" :store_revision 9 :decision_id "one-result-two-views"
               :action "approve" :proposal {:canonical_binding binding
                                            :selected_option "same_attempt"}}
        event {:id "owner-store:9" :action :accept :type :same-attempt
               :pair pair :evidence {:kind :verified-scope}}
        request {:event event :owner-event owner :binding binding
                 :subject-snapshot (relationships/attempt-subjects base :same-attempt pair)
                 :expected-revision 0}]
    (store/persist! app base)
    (is (= 1 (get-in (store/record-owner-decision! app request)
                     [:counts :accepted-attempts])))
    (is (= 1 (get-in (store/private-projection app) [:counts :accepted-attempts])))
    (let [reversal (-> request
                       (assoc :event {:id "owner-store:10" :action :reverse
                                      :type :same-attempt :pair pair
                                      :event-id "owner-store:9"}
                              :owner-event (assoc owner :id "owner-store:10"
                                                  :store_revision 10 :action "reverse")
                              :expected-revision 1))]
      (is (= 2 (get-in (store/record-owner-decision! app reversal)
                       [:counts :accepted-attempts])))
      (is (= 2 (get-in (store/record-owner-decision! app reversal)
                       [:counts :accepted-attempts])))
      (is (= (store/private-projection app) (store/rebuild! app))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'freediving.canonical-attempt-store-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
