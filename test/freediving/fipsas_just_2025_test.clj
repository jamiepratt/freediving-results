(ns freediving.fipsas-just-2025-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.fipsas-just-2025 :as just]))

(defn- sample-pages []
  (-> (vec (repeat 23 "34° Trofeo Just Apnea\n"))
      (assoc 0 "34° Trofeo Just Apnea\nTriggiano - 13-14 dicembre 2025\nClassiﬁca società\n")
      (assoc 2 (str "34° Trofeo Just Apnea\nClassiﬁca 1CF - DYN\n"
                    " 1           Rossi      Ada        Club Example A.S.D.       1997                    1:35.00                                   1:40.57                105,50                       1:40.57              105,50            0:05.57         15.0\n"))
      (assoc 4 (str "34° Trofeo Just Apnea\nClassiﬁca 1CM - DYN\n"
                    "             Bianchi    Luca       Club Example A.S.D.       1983                    2:15.00                                   2:08.99                 99,50      BO\n"))
      (assoc 15 (str "34° Trofeo Just Apnea\nClassiﬁca EM - DYNB\n"
                     " 3           Verdi      Marco      Club Example A.S.D.       1982                                           163,50            2:18.00                160,00      PG               2:18.00              157,00           8.0\n"))
      (assoc 16 (str "34° Trofeo Just Apnea\nClassiﬁca EM (21) - DYNB\n"
                     " 1           Viola      Carlo      Club Example A.S.D.       1997                                                    65,50            1:06.08                 75,00                       1:06.08                75,00          20.0\n"
                     "                                                                                                                                                                          R.I.\n"))
      (assoc 18 (str "34° Trofeo Just Apnea\nClassiﬁca ESM - DYN\n"
                     " 4           Blu        Sandro     Club Example A.S.D.       1965                    1:40.00                                  1:15.29                 75,00                       1:15.29                75,00           0:24.71         0.75\n"))
      (assoc 21 (str "34° Trofeo Just Apnea\nClassiﬁca STAM - STA\n"
                     " 1           Neri       Paolo      Club Example A.S.D.       1997                    6:46.00                                  6:45.36                                             6:45.36                             10.0\n"))))

(deftest exact-source-and-source-position-contract
  (let [artifact (just/parse-pages just/source-sha256 (sample-pages))
        rows (:candidates artifact)]
    (is (= :partial-unsupported-needs-review (:status artifact)))
    (is (= 6 (count rows)))
    (is (= [3 5 16 17 19 22] (mapv #(get-in % [:coordinates :page]) rows)))
    (is (= "Rossi Ada" (get-in rows [0 :parsed :source-name])))
    (is (= 105.5M (get-in rows [0 :parsed :final-performance])))
    (is (= :blackout (get-in rows [1 :parsed :status])))
    (is (nil? (get-in rows [1 :parsed :final-performance])))
    (is (= "PG" (get-in rows [2 :parsed :penalty])))
    (is (= 157M (get-in rows [2 :parsed :final-performance])))
    (is (= "R.I." (get-in rows [3 :raw :penalty-note])))
    (is (= "R.I." (get-in rows [3 :parsed :penalty-note])))
    (is (= 0.75M (get-in rows [4 :parsed :points])))
    (is (= "6:45.36" (get-in rows [5 :parsed :final-performance])))
    (is (= "min:sec.centisec" (get-in rows [5 :parsed :unit])))
    (is (every? #(= :blocked (get-in % [:publication :status])) rows))
    (is (every? #(= (get-in % [:raw :line])
                    (get-in artifact [:pages (dec (get-in % [:coordinates :page]))
                                      :lines (dec (get-in % [:coordinates :line])) :text])) rows))))

(deftest different-source-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (just/parse-pages (apply str (repeat 64 "0")) (sample-pages)))))

(deftest extraction-routes-the-exact-official-source
  (let [artifact (extraction/parse-pages just/source-sha256 (sample-pages))]
    (is (= just/parser-version (:parser-version artifact)))
    (is (= 6 (count (:candidates artifact))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-just-2025-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
