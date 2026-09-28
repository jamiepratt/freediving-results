(ns freediving.attempt-view-adapter-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.attempt-comparison :as comparison]
            [freediving.attempt-view-adapter :as adapter]))

(def bundle-root (System/getenv "FREEDIVING_PRIVATE_CORPUS"))

(deftest exact-view-census-preserves-every-selected-source-position
  (when bundle-root
    (let [{:keys [observations census request]} (adapter/load-exact-views bundle-root)]
      (is (= 138 (count observations)))
      (is (= {:cmas {:source-rows 35 :versioned-rows 70 :parsed 35 :unparsed 0 :discrepant 0}
              :aida {:source-rows 103 :versioned-rows 206 :parsed 103 :unparsed 0 :discrepant 0}}
             (:selected census)))
      (is (= {:total-rows 209 :women 103 :men 106} (:aida-view census)))
      (is (= #{"CMAS" "AIDA"} (:federations request)))
      (is (every? #(= :unreviewed (get-in % [:candidate :review-status])) observations))
      (is (every? #(= :unknown (get-in % [:evidence :review])) observations))
      (is (= 138 (count (set (map #(select-keys (:reference %) [:source-sha256 :candidate-id]) observations)))))
      (is (every? #(seq (get-in % [:candidate :raw])) observations))
      (is (= #{"F"} (set (map #(get-in % [:candidate :source-parsed :gender])
                              (filter #(= "AIDA" (get-in % [:source :federation])) observations)))))
      (let [result (comparison/compare-attempts request observations)]
        (is (= 0 (get-in result [:coverage :ranked])))
        (is (= 138 (get-in result [:coverage :withheld])))
        (is (every? nil? (map :rank (:rows result))))))))

(deftest missing-or-corrupt-bundle-fails-closed
  (is (thrown? Exception (adapter/load-exact-views "/no/such/portable-corpus")))
  (when bundle-root
    (is (thrown? Exception (adapter/load-exact-views (str bundle-root "/payload")))))
  (let [temporary (.toFile (java.nio.file.Files/createTempDirectory "corrupt-attempt-bundle"
                                                                    (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (java.io.File. temporary "index.json") "{}")
      (is (thrown? Exception (adapter/load-exact-views (.getPath temporary))))
      (finally (.delete (java.io.File. temporary "index.json")) (.delete temporary)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.attempt-view-adapter-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
