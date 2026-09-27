(ns freediving.fipsas-monte-conero-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.fipsas-monte-conero-2026 :as monte]))

(defn- pages []
  (-> (vec (repeat 16 "2° Trofeo Centro Sub Monte Conero\nClassiﬁca ESM - DYN\n"))
      (assoc 0 (str "2° Trofeo Centro Sub Monte Conero\nClassiﬁca ESM - DYN\n"
                    " 1           Rossi      Ada       Club Example       1990                    0:42.00                                  0:38.66                 50,00                       0:38.66                50,00           0:03.34           2.5\n"))
      (assoc 6 (str "2° Trofeo Centro Sub Monte Conero\nClassiﬁca 1CM - DYN\n"
                    "             Bianchi    Luca      Club Example       1993                    1:54.00\n"
                    "                                                                                                                                                    BO\n"
                    "                                                                                                                                                    SUPERFICIE\n"))
      (assoc 8 (str "2° Trofeo Centro Sub Monte Conero\nClassiﬁca EF - DYNB\n"
                    " 3           Verdi      Sara      Club Example       1988                                             125,00            1:45.36                125,00                       1:45.36              125,00           8.0\n"))))

(deftest source-bound-individual-positions
  (let [artifact (monte/parse-pages monte/source-sha256 (pages))
        rows (:candidates artifact)]
    (is (= :partial-unsupported-needs-review (:status artifact)))
    (is (= 3 (count rows)))
    (is (= [1 7 9] (mapv #(get-in % [:coordinates :page]) rows)))
    (is (= "Rossi Ada" (get-in rows [0 :parsed :source-name])))
    (is (= 50.00M (get-in rows [0 :parsed :final-performance])))
    (is (= :blackout (get-in rows [1 :parsed :status])))
    (is (nil? (get-in rows [1 :parsed :final-performance])))
    (is (= "BO SUPERFICIE" (get-in rows [1 :raw :status])))
    (is (= 125.00M (get-in rows [2 :parsed :final-performance])))
    (is (= "DYNB" (get-in rows [2 :parsed :discipline])))
    (is (every? #(= :blocked (get-in % [:publication :status])) rows))
    (is (every? #(= (get-in % [:raw :line])
                    (get-in artifact [:pages (dec (get-in % [:coordinates :page]))
                                      :lines (dec (get-in % [:coordinates :line])) :text])) rows))))

(deftest rejects-different-source
  (is (thrown? clojure.lang.ExceptionInfo
               (monte/parse-pages (apply str (repeat 64 "0")) (pages)))))

(deftest extraction-routes-exact-source
  (is (= monte/parser-version
         (:parser-version (extraction/parse-pages monte/source-sha256 (pages))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-monte-conero-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
