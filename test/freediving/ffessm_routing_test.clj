(ns freediving.ffessm-routing-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.ffessm-2025-day2-test :as day2-test]
            [freediving.ffessm-2026-men-test :as men-test]))

(deftest newly-acquired-french-results-use-source-bound-parsers
  (is (= "ffessm-france-outdoor-day2-2025/1"
         (:parser-version (extraction/parse-pages [(str day2-test/header day2-test/white-row "\n")]))))
  (is (= "ffessm-2026-cwt-men/1"
         (:parser-version (extraction/parse-pages [men-test/sample-page])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-routing-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
