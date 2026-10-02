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

(defn -main [& _]
  (let [result (clojure.test/run-tests 'freediving.canonical-attempt-store-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
