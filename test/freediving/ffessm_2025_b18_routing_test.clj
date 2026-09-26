(ns freediving.ffessm-2025-b18-routing-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]))

(def sources
  [["bipalmes-femmes" "726e1db16e08984de01a18f3e891d5374ca9b18f419532393cac40b311d2a8f3" 4]
   ["bipalmes-hommes" "a35bc35b4071821119498a482653594fe1eca2794bf2e68e4377e7b9f46ec3c1" 7]
   ["sans-palmes-femmes" "4bcaf0b69091bb80e31f1d3b87a582f14fc4801762c2ff513932a983ff4bac54" 6]
   ["sans-palmes-hommes" "92400e1e404f066c79018b1128c76a05f2ec05e4a907b1c80c35b106f2efa209" 12]
   ["immersion-libre-femmes" "0f2a7ce0dd6aacd16bbf2ccac62e9ddec695248049b400ca9ee89668c133c3a7" 7]
   ["immersion-libre-hommes" "801f0e1e68dd70942395beb9bd316a8b6e73f5cac55cc44f20d0c026c8852e3b" 9]])

(defn pages [name]
  (let [raw (slurp (io/resource (str "fixtures/ffessm-2025-b18/" name ".txt")))
        parts (vec (str/split raw #"\f" -1))]
    (if (= "" (last parts)) (pop parts) parts)))

(deftest six-official-category-pdfs-route-to-source-bound-parsers
  (doseq [[name sha count-expected] sources]
    (let [artifact (extraction/parse-pages sha (pages name))]
      (is (= 3 (:schema-version artifact)) name)
      (is (= count-expected (get-in artifact [:reconciliation :parsed-count])) name)
      (is (= :needs-review (:status artifact)) name)
      (is (= :blocked (get-in artifact [:publication :status])) name))))

(deftest lookalike-cannot-use-another-source-hash
  (doseq [[name _ _] sources]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source PDF"
                          (extraction/parse-pages (apply str (repeat 64 "0")) (pages name))) name)))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2025-b18-routing-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
