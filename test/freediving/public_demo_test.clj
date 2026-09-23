(ns freediving.public-demo-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.public-demo :as demo]
            [freediving.corrections :as corrections]
            [clojure.string :as str]
            [freediving.observations-test :as fixture]
            [freediving.observations :as observations]
            [freediving.publication :as publication]
            [freediving.public-results :as public]))
(deftest rejects-non-demo-and-mismatched-endpoints-before-writing
  (doseq [[admin ingest reviewer]
          [["jdbc:postgresql://127.0.0.1:5432/owner_demo?user=admin" "x" "y"]
           ["jdbc:postgresql://127.0.0.1:5432/public_demo?user=admin"
            "jdbc:postgresql://127.0.0.1:5433/public_demo?user=observations_app"
            "jdbc:postgresql://127.0.0.1:5432/public_demo?user=reviews_owner"]]]
    (is (thrown-with-msg? Exception #"loopback public_demo" (demo/seed! admin ingest reviewer "/unused")))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.public-demo-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))

(deftest synthetic-demo-publishes-only-validated-rows
  (let [url #(str/replace (System/getenv %) "/observations_test?" "/public_demo?")
        admin (url "FREEDIVING_TEST_ADMIN_URL") ingest (url "FREEDIVING_TEST_URL")
        reviewer (url "FREEDIVING_TEST_REVIEW_URL") reader (url "FREEDIVING_TEST_PUBLIC_URL")
        root (.toString (java.nio.file.Files/createTempDirectory "public-demo-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (fixture/sql! fixture/admin "CREATE DATABASE public_demo")
    (try
      (let [receipt (demo/seed! admin ingest reviewer (str root "/fixtures"))
            rows (public/results reader)
            active (first (public/search-source-name reader "Alex Éxample"))
            reversed (first (public/search-source-name reader "Casey Sample"))]
        (is (= 12 (count rows)))
        (is (string? (corrections/target-version (url "FREEDIVING_TEST_SUBMIT_URL") (:result-id active))))
        (is (= 2 (count (public/athlete-history reader (get-in active [:identity :id])))))
        (is (= "Alex Example" (get-in active [:effective :source-name])))
        (is (= "Casey Sample" (get-in reversed [:effective :source-name])))
        (is (= [:approve :reverse] (mapv :action (:correction-audit reversed))))
        (is (= :unresolved (get-in (first (public/search-source-name reader "Synthetic Participant 03")) [:identity :status])))
        (is (not (re-find #"PRIVATE|unvalidated" (pr-str rows))))
        (is (= 14 (:observations (:counts receipt))))
        (is (= "Alex Éxample" (get-in (first (:observations (observations/inspect ingest (first (:jobs receipt))))) [:payload :parsed :source-name])))
        (is (thrown-with-msg? Exception #"empty database" (demo/seed! admin ingest reviewer (str root "/again"))))
        (let [t (get-in receipt [:targets :revoke])
              before (observations/inspect ingest (:job-id t))
              d (publication/diagnose reviewer t)]
          (publication/decide! reviewer
                               (merge t {:id "demo-test-revoke" :action :revoke
                                         :base-revision (:revision d) :review-revision (:review-revision d)
                                         :policy-version publication/current-policy :observation (:observation d)
                                         :actor "synthetic-test" :reason "Synthetic revocation demonstration"
                                         :evidence [{:page 1 :line (inc (:ordinal t))}] :attestations {}}))
          (is (= [] (public/results reader)))
          (is (nil? (public/result reader (:result-id active))))
          (is (= (select-keys before [:artifact :observations])
                 (select-keys (observations/inspect ingest (:job-id t)) [:artifact :observations])))
          (is (= {:refreshed 11} (public/refresh! reviewer)))
          (is (= 11 (count (public/results reader))))
          (is (= [] (public/search-source-name reader "Synthetic Participant 04")))))
      (finally (fixture/sql! fixture/admin "DROP DATABASE public_demo WITH (FORCE)")))))
