(ns freediving.tuttinapnea-aggregate-links-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.tuttinapnea-aggregate-links :as links]
            [freediving.tuttinapnea-2026 :as individual]))

(def combined-line
  "APNEA FIRENZE  FIRENZE  TOSCANA  GURTSKAIA SOFIA  F  B  158  4,24  250,4")

(defn candidate [sha discipline line result]
  {:source-sha256 sha
   :source-lines [{:page 2 :line line
                   :text (str "APNEA FIRENZE  FIRENZE  TOSCANA  GURTSKAIA SOFIA  F  "
                              (if (= discipline "DYN") "B  " "") result "  92,4")}]
   :raw {:fields {:location ["APNEA FIRENZE" "FIRENZE" "TOSCANA" "GURTSKAIA SOFIA"]
                  :gender "F" :result (if (= discipline "DYN") ["B" result "92,4"]
                                          [result "92,4"])}}
   :parsed {:team "APNEA FIRENZE" :source-name "GURTSKAIA SOFIA"
            :gender "F" :discipline discipline :realised-performance result
            :type (when (= discipline "DYN") "B") :status :result}})

(deftest combined-row-cites-the-two-original-result-positions
  (let [individuals [(candidate individual/dynamic-sha256 "DYN" 14 "158")
                     (candidate individual/static-sha256 "STA" 27 "4,24")]
        result (links/reconcile links/combined-sha256 [combined-line] individuals)]
    (is (= 1 (:combined-row-count result)))
    (is (= 2 (count (:links result))))
    (is (= #{["DYN" "158"] ["STA" "4,24"]}
           (set (map (juxt :discipline :raw-result) (:links result)))))
    (is (= #{[2 14] [2 27]}
           (set (map (juxt (comp :page :individual-position)
                           (comp :line :individual-position)) (:links result)))))
    (is (every? #(= {:page 1 :line 1 :text combined-line}
                    (:combined-citation %)) (:links result)))
    (is (= result (links/reconcile links/combined-sha256 [combined-line]
                                   (reverse individuals))))))

(deftest uncertain-and-uncited-rows-never-create-proven-links
  (let [source (candidate individual/dynamic-sha256 "DYN" 14 "158")
        different-name (assoc-in source [:parsed :source-name] "A DIFFERENT ATHLETE")
        missing-citation (dissoc source :source-lines)
        ambiguous (assoc-in source [:source-lines 0 :line] 20)]
    (is (empty? (:links (links/reconcile links/combined-sha256 [combined-line]
                                         [different-name missing-citation]))))
    (is (empty? (:links (links/reconcile links/combined-sha256 [combined-line]
                                         [source ambiguous]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (links/reconcile (apply str (repeat 64 "0")) [combined-line] [source])))))

(defn -main []
  (let [result (run-tests 'freediving.tuttinapnea-aggregate-links-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
