(ns freediving.ffessm-routing-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.shell :as shell]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.ffessm-2025-day2-test :as day2-test]
            [freediving.ffessm-2026-men-test :as men-test]
            [freediving.ffessm-2026-bipalmes-women-test :as bifins-women-test]
            [freediving.ffessm-2026-regular-categories :as regular]
            [freediving.ffessm-2026-regular-categories-test :as regular-test]))

(deftest newly-acquired-french-results-use-source-bound-parsers
  (is (= "ffessm-france-outdoor-day2-2025/1"
         (:parser-version (extraction/parse-pages [(str day2-test/header day2-test/white-row "\n")]))))
  (is (= "ffessm-2026-cwt-men/1"
         (:parser-version (extraction/parse-pages [men-test/sample-page])))))

(deftest new-french-category-sources-route-through-extraction
  (doseq [[sha256 page expected-version expected-count]
          [["b603390af45204f9e7720d71d59efb3ebb9d13b4a65f01ac55e97419e9fc863c"
            bifins-women-test/source-page "ffessm-2026-bipalmes-women/1" 11]
           [regular-test/men-sha regular-test/men-page "ffessm-2026-bipalmes-hommes/1" 8]
           [regular-test/women-sha regular-test/women-page "ffessm-2026-sans-palmes-femmes/1" 5]]]
    (let [artifact (extraction/parse-pages sha256 [page])]
      (is (= expected-version (:parser-version artifact)))
      (is (= 3 (:schema-version artifact)))
      (is (= expected-count (get-in artifact [:reconciliation :parsed-count])))
      (is (or (extraction/ffessm-2026-bipalmes-women-artifact?
               (assoc artifact :source-sha256 sha256))
              (extraction/ffessm-2026-regular-artifact?
               (assoc artifact :source-sha256 sha256))))))
  (is (= "ffessm-2026-bipalmes-hommes/1" (regular/parser-version regular/men-sha256)))
  (is (nil? (regular/parser-version (apply str (repeat 64 "0"))))))

(deftest lookalike-french-category-source-cannot-use-another-hash
  (doseq [page [bifins-women-test/source-page regular-test/men-page regular-test/women-page]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source PDF"
                          (extraction/parse-pages (apply str (repeat 64 "0")) [page])))))

(deftest archived-category-source-replay-rejects-altered-candidates
  (let [sha256 regular-test/women-sha
        page regular-test/women-page
        raw (str page "\f")
        artifact (assoc (extraction/parse-pages sha256 [page])
                        :source-sha256 sha256 :raw-text raw
                        :tool {:name "pdftotext" :version "pdftotext test"
                               :arguments ["-layout" "-enc" "UTF-8"]})]
    (with-redefs [archive/inspect (fn [_ _] {:artifact-path "/archived/source.pdf"})
                  shell/sh (fn [& args]
                             (if (= ["pdftotext" "-v"] (take 2 args))
                               {:exit 0 :out "" :err "pdftotext test"}
                               {:exit 0 :out raw :err ""}))]
      (is (= artifact (extraction/validate-ffessm-2026-regular-artifact! :archive artifact)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differs from archived source replay"
                            (extraction/validate-ffessm-2026-regular-artifact!
                             :archive (assoc artifact :candidates [])))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-routing-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
