(ns freediving.evaluation-label-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [freediving.observations-test :as f]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.evaluation-labels-test :as fixture]
            [freediving.evaluation-labels :as labels]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-data :as data]
            [freediving.evaluation-providers-test :as http]))

(use-fixtures :each
  (fn [test]
    (f/sql! f/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! f/admin "observations_app")
    (reviews/migrate! f/admin "observations_app" "reviews_owner")
    (labels/migrate! f/admin "reviews_owner" :synthetic)
    (test)))

(defn- private-root []
  (str (.toRealPath (java.nio.file.Files/createTempDirectory
                     "reviewed-shadow-integration" (make-array java.nio.file.attribute.FileAttribute 0))
                    (make-array java.nio.file.LinkOption 0))))

(deftest synthetic-review-export-local-http-and-replay
  (let [pair (fixture/sample)
        before (observations/inspect f/app (:job-id (first pair)))
        _ (labels/decide! fixture/owner (fixture/request pair))
        receipt (labels/export fixture/owner {:rubric-version "pair-v1"})
        store (str (private-root) "/store")
        requests (atom [])]
    (http/with-server
      (fn [exchange]
        (let [request (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)]
          (swap! requests conj request)
          (http/reply! exchange 200
                       (if (:questions request)
                         "{\"model\":\"synthetic-jev\",\"answers\":{\"identity\":{\"type\":\"choice\",\"choice\":\"abstain\",\"confidence\":0.8,\"probabilities\":{\"match\":0.1,\"no_match\":0.1,\"abstain\":0.8}}}}"
                         "{\"model\":\"synthetic-llm\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"outcome\\\":\\\"abstain\\\"}\"}}]}"))))
      (fn [endpoint]
        (let [configs [{:id "rules" :provider :rules}
                       {:id "jev" :provider :jev :endpoint endpoint :model "synthetic-jev"}
                       {:id "llm" :provider :llm :endpoint endpoint :model "synthetic-llm"}]
              runtime {:providers {"jev" {:bearer-token "synthetic-only"}
                                   "llm" {:bearer-token "synthetic-only"}}}
              result (evaluation/run-verified! store fixture/owner receipt configs runtime)
              report (:report (evaluation/inspect-verified-run store fixture/owner (:verified-id result)))]
          (is (= :evaluated (:status result)))
          (is (= :synthetic-fixture (:label-source report)))
          (is (= 2 (count @requests)))
          (is (= result (evaluation/run-verified! store fixture/owner receipt configs runtime)))
          (is (= 2 (count @requests)))
          (is (apply = (map #(mapv :case-id (:results %)) (vals (:providers report)))))
          (doseq [provider (vals (:providers report))]
            (is (= 1 (get-in provider [:metrics :synthetic :case-count])))
            (is (= 0 (get-in provider [:metrics :owner :case-count])))
            (is (= :abstain (get-in provider [:results 0 :outcome]))))
          (is (not-any? #(re-find #"rubric-version|reviewer|provenance|label-1" (pr-str %)) @requests))
          (is (= (:observations before)
                 (:observations (observations/inspect f/app (:job-id (first pair))))))
          (is (= (seq (:artifact-bytes before))
                 (seq (:artifact-bytes (observations/inspect f/app (:job-id (first pair))))))))))))

(deftest empty-and-revoked-exports-block-before-dispatch
  (let [pair (fixture/sample)
        store (str (private-root) "/store")
        configs [{:id "rules" :provider :rules}]
        dispatches (atom 0)
        runtime {:on-progress (fn [_] (swap! dispatches inc))}
        empty-receipt (labels/export fixture/owner {:rubric-version "pair-v1"})]
    (is (= :blocked (:status (evaluation/run-verified! store fixture/owner empty-receipt configs runtime))))
    (is (zero? @dispatches))
    (labels/decide! fixture/owner (fixture/request pair))
    (let [receipt (labels/export fixture/owner {:rubric-version "pair-v1"})
          result (evaluation/run-verified! store fixture/owner receipt configs runtime)
          report (:report (evaluation/inspect-verified-run store fixture/owner (:verified-id result)))
          predictions (get-in report [:providers "rules" :results])]
      (is (= 0 (get-in (data/metrics-verified fixture/owner receipt predictions) [:owner :case-count])))
      (is (= 1 (get-in (data/metrics-verified fixture/owner receipt predictions) [:synthetic :case-count])))
      (labels/decide! fixture/owner (assoc (fixture/request pair) :id "revoke" :base-revision 1 :outcome :revoke))
      (let [before @dispatches]
        (is (thrown? Exception (evaluation/run-verified! store fixture/owner receipt configs runtime)))
        (is (thrown? Exception (evaluation/inspect-verified-run store fixture/owner (:verified-id result))))
        (is (thrown? Exception (data/metrics-verified fixture/owner receipt predictions)))
        (is (= before @dispatches)))
      (is (= :blocked (:status (evaluation/run-verified! store fixture/owner
                                                         (labels/export fixture/owner {:rubric-version "pair-v1"}) configs runtime)))))))

(deftest revocation-during-provider-work-cannot-publish-verified-metrics
  (let [pair (fixture/sample)
        store (str (private-root) "/store")
        _ (labels/decide! fixture/owner (fixture/request pair))
        receipt (labels/export fixture/owner {:rubric-version "pair-v1"})
        revoked? (atom false)]
    (is (thrown? Exception
                 (evaluation/run-verified! store fixture/owner receipt [{:id "rules" :provider :rules}]
                                           {:on-progress (fn [{:keys [phase]}]
                                                           (when (and (= :attempt-returned phase) (compare-and-set! revoked? false true))
                                                             (labels/decide! fixture/owner
                                                                             (assoc (fixture/request pair) :id "during-run-revoke" :base-revision 1 :outcome :revoke))))})))
    (is @revoked?)
    (is (not-any? #(.endsWith (.getName %) "-verified-manifest")
                  (.listFiles (io/file store "records"))))))

(deftest generic-no-match-and-rejection-never-become-pair-labels
  (let [[left right] (fixture/sample)
        proposal (fn [ref id after]
                   (merge (select-keys ref [:job-id :ordinal])
                          {:id id :base-revision 0 :category :identity-matching :field :identity
                           :before {:outcome :unknown} :after after
                           :evidence [(select-keys ref [:page :line])]
                           :actor "synthetic-only" :reason "Synthetic generic identity review"}))]
    (reviews/propose! f/app (proposal left "generic-no-match" {:outcome :no-match}))
    (reviews/decide! fixture/owner {:id "approve-generic" :proposal-id "generic-no-match" :action :approve :base-revision 0 :actor "synthetic-only" :reason "Generic, not a pair decision"})
    (reviews/propose! f/app (proposal right "reject-candidate" {:outcome :matched :identity-id "synthetic-anchor"}))
    (reviews/decide! fixture/owner {:id "reject-generic" :proposal-id "reject-candidate" :action :reject :base-revision 0 :actor "synthetic-only" :reason "Rejection is not a negative label"})
    (let [receipt (labels/export fixture/owner {:rubric-version "pair-v1"})]
      (is (= :not-evaluable (:status receipt)))
      (is (empty? (get-in receipt [:dataset :cases]))))))

(deftest export-cli-prints-readable-opaque-references
  (let [root (private-root)
        options (str root "/options.edn")
        _ (spit options "{:rubric-version \"pair-v1\"}")
        result (shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-m"
                         "freediving.evaluation-labels" "export" options (str root "/exports")
                         :env (assoc (into {} (System/getenv)) "FREEDIVING_DATABASE_URL" fixture/owner))
        value (when (zero? (:exit result)) (edn/read-string (:out result)))]
    (is (zero? (:exit result)))
    (is (string? (:receipt-id value)))
    (is (string? (:path value)))
    (is (= :not-evaluable (:status value)))))

(defn -main [& _]
  (let [r (run-tests 'freediving.evaluation-label-integration-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
