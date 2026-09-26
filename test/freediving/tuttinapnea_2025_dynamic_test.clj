(ns freediving.tuttinapnea-2025-dynamic-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.shell :as shell]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.tuttinapnea-2025-dynamic :as dynamic]))

(def heading
  (str "RESOCONTO GARA NAZIONALE\n"
       "\" TUTTINAPNEA GENNAIO 2025 \"\n"
       "DINAMICA\n"
       "Sesso   Specialità   Distanza raggiunta   Penalita'\n"
       "PUNTEGGIO\nDINAMICA\n"
       "CLASSIFICA FEMMINILE\n"))

(deftest january-dynamic-keeps-printed-distance-and-citation
  (let [page (str heading
                  " SOTTOPRESSIONE         BIGNAZZI MARTA          F          B          167          167\n")
        result (dynamic/parse-pages dynamic/source-sha256 [page])
        row (first (:candidates result))]
    (is (= dynamic/parser-version (:parser-version result)))
    (is (= {:page-count 1 :candidate-count 1 :parsed-count 1 :unparsed-count 0}
           (select-keys (:reconciliation result)
                        [:page-count :candidate-count :parsed-count :unparsed-count])))
    (is (= {:event-month "2025-01" :event-date nil :discipline "DYN"
            :team "SOTTOPRESSIONE" :source-name "BIGNAZZI MARTA"
            :gender "F" :type "B" :realised-performance "167" :points "167"
            :unit "m" :status :result}
           (select-keys (:parsed row)
                        [:event-month :event-date :discipline :team :source-name
                         :gender :type :realised-performance :points :unit :status])))
    (is (= {:page 1 :line 8} (select-keys (:coordinates row) [:page :line])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest january-dynamic-routes-through-source-bound-extraction
  (let [page (str heading
                  " NPS                 Angiulli Davide       M       B       140       10       126\n"
                  " WATER INSTINCT      Luca Bianchi          M       B                 x        PN\n"
                  " UONEA TRAINING      Olivieri Marina       F       B                          0\n")
        result (extraction/parse-pages dynamic/source-sha256 [page])
        rows (:candidates result)
        artifact {:schema-version 3 :source-sha256 dynamic/source-sha256
                  :parser-version dynamic/parser-version}]
    (is (= dynamic/parser-version (:parser-version result)))
    (is (= {:candidate-count 3 :parsed-count 2 :unparsed-count 1}
           (select-keys (:reconciliation result)
                        [:candidate-count :parsed-count :unparsed-count])))
    (is (= "10" (get-in rows [0 :parsed :yellow-card])))
    (is (= "126" (get-in rows [0 :parsed :points])))
    (is (= "x" (get-in rows [1 :parsed :red-card])))
    (is (= :null-result (get-in rows [1 :parsed :status])))
    (is (= :unparsed (get-in rows [2 :parse-status])))
    (is (extraction/tuttinapnea-2025-dynamic-artifact? artifact))
    (is (extraction/tuttinapnea-2025-dynamic-claim? artifact))
    (is (false? (extraction/tuttinapnea-2025-dynamic-artifact?
                 (assoc artifact :source-sha256 (apply str (repeat 64 "0"))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (extraction/parse-pages (apply str (repeat 64 "0")) [page])))))

(deftest archived-january-dynamic-result-must-replay-exactly
  (let [page (str heading "SOTTOPRESSIONE  BIGNAZZI MARTA  F  B  167  167\n")
        raw (str page "\f")
        artifact (merge (dynamic/parse-pages dynamic/source-sha256 [page])
                        {:source-sha256 dynamic/source-sha256 :raw-text raw
                         :tool {:name "pdftotext" :version "test-version"
                                :arguments ["-layout" "-enc" "UTF-8"]}})]
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                  shell/sh (fn [& args]
                             {:exit 0 :out (if (= "-v" (second args)) "" raw)
                              :err (if (= "-v" (second args)) "test-version" "")})]
      (is (= artifact (extraction/validate-tuttinapnea-2025-dynamic-artifact!
                       "archive" artifact)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (extraction/validate-tuttinapnea-2025-dynamic-artifact!
                    "archive" (assoc-in artifact [:candidates 0 :parsed :points] "999")))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.tuttinapnea-2025-dynamic-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
