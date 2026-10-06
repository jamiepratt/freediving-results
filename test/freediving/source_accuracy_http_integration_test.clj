(ns freediving.source-accuracy-http-integration-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [freediving.private-sporting-proofs-test :as proof]
            [freediving.source-accuracy-review-test :as accuracy]
            [freediving.observations-test :as db]
            [freediving.reviews :as reviews])
  (:import [java.lang ProcessBuilder$Redirect]))
(deftest authenticated-http-real-jvm-receipts-and-paired-proof
  (proof/with-database
    (fn []
      (proof/role! "sporting_source_review" proof/source-tables)
      (db/sql! db/admin "ALTER ROLE sporting_source_review SET default_transaction_read_only=off")
      (db/sql! db/admin "GRANT INSERT ON freediving.extraction_reviews,freediving.pdf_extraction_reviews TO sporting_source_review")
      (let [pdf (accuracy/sample) html (accuracy/html-sample)
            rows (mapv #(merge (get-in % [:config :rows 0]) {:year "2026" :environment "pool" :discipline "dnf" :gender "women"}) [pdf html])
            config {:jdbc_url proof/proof-url :database "observations_test" :canonical_jdbc_url proof/relationship-url :canonical_database "observations_test"}
            python (or (System/getenv "SOURCE_ACCURACY_TEST_PYTHON") "python3")
            process (.start (doto (ProcessBuilder. ^java.util.List [python "tests/source_accuracy_http_driver.py"])
                              (.redirectError ProcessBuilder$Redirect/INHERIT)))
            result (with-open [writer (io/writer (.getOutputStream process))
                               reader (io/reader (.getInputStream process))]
                     (.write writer (json/write-str {:config config :review_url accuracy/review-url :rows rows}))
                     (.flush writer) (.close writer)
                     (json/read-str (slurp reader) :key-fn keyword))]
        (is (= 0 (.waitFor process)))
        (is (= [200 200] (:accepted result)))
        (is (= [200 200] (:revoked result)))
        (is (= ["verified" "verified"] (:accuracy result)))
        (is (= [nil nil] (:publication result)))
        (is (= 409 (:stale result)))
        (is (= 409 (:unknown_replay result)))
        (is (= true (:replayed result)))
        (is (= 503 (:reader_denied_write result)))
        (is (= 404 (:sporting_approval result)))
        (is (= 0 (:sporting_revision result)))
        (is (= [nil nil] (:withdrawn result)))
        (doseq [[sample history] [[pdf reviews/pdf-extraction-history] [html reviews/extraction-history]]]
          (let [events (history accuracy/review-url (:target sample))]
            (is (= [:accept :revoke] (mapv :action events)))
            (is (every? #(= :authenticated-owner-http (get-in % [:owner-response :type])) events))
            (is (every? #(= "synthetic-owner@example.test" (:actor %)) events))))))))
(defn -main [& _]
  (let [r (run-tests 'freediving.source-accuracy-http-integration-test)] (shutdown-agents)
       (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
