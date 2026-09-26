(ns freediving.tuttinapnea-aggregate-links-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
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

(def january-combined-line
  "         APNEA FIRENZE            GURTSKAIA SOFIA                       F       130,9       124,6        255,5")

(defn january-candidate [sha discipline line raw-result points]
  (let [raw-line (str "    APNEA FIRENZE         GURTSKAIA SOFIA                          F          "
                      (if (= discipline "DYN") "B          " "")
                      raw-result "                                               " points)]
    {:source-sha256 sha
     :source-lines [{:page 1 :line line :text raw-line}]
     :raw {:line raw-line
           :fields {:location ["APNEA FIRENZE" "GURTSKAIA SOFIA"]
                    :gender "F"
                    :result (if (= discipline "DYN") ["B" raw-result points]
                                [raw-result points])}}
     :parsed {:team "APNEA FIRENZE" :source-name "GURTSKAIA SOFIA"
              :gender "F" :discipline discipline :status :result
              :realised-performance raw-result :points points
              :type (when (= discipline "DYN") "B")}}))

(deftest january-combined-points-link-to-exact-individual-rows
  (let [individuals [(january-candidate links/january-dynamic-sha256 "DYN" 22 "130,9" "130,9")
                     (january-candidate links/january-static-sha256 "STA" 17 "5,56" "124,6")]
        result (links/reconcile-january links/january-combined-sha256
                                        [january-combined-line] individuals)]
    (is (= 1 (:combined-row-count result)))
    (is (= #{["DYN" "130,9"] ["STA" "124,6"]}
           (set (map (juxt :discipline :raw-result) (:links result)))))
    (is (= #{[1 22] [1 17]}
           (set (map (juxt (comp :page :individual-position)
                           (comp :line :individual-position)) (:links result)))))
    (is (= result (links/reconcile-january links/january-combined-sha256
                                           [january-combined-line] (reverse individuals))))
    (is (empty? (:links (links/reconcile-january links/january-combined-sha256
                                                 [january-combined-line]
                                                 [(assoc-in (first individuals) [:parsed :points] "130")]))))))

(deftest january-ambiguous-or-uncited-evidence-remains-unknown
  (let [source (january-candidate links/january-dynamic-sha256 "DYN" 22 "130,9" "130,9")]
    (is (empty? (:links (links/reconcile-january links/january-combined-sha256
                                                 [january-combined-line]
                                                 [source (assoc-in source [:source-lines 0 :line] 23)]))))
    (is (empty? (:links (links/reconcile-january links/january-combined-sha256
                                                 [january-combined-line]
                                                 [(dissoc source :source-lines)]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (links/reconcile-january links/combined-sha256
                                          [january-combined-line] [source])))))

(deftest reversed-two-token-printed-name-can-link-with-other-exact-evidence
  (let [rows [{:team "WATER INSTINCT" :city "ROMA" :region "LAZIO"
               :combined-name "Evelina Casano" :individual-name "CASANO EVELINA"
               :gender "F" :type "B" :performance "178,5" :static "3,09" :total "244,65"}
              {:team "LIVING APNEA" :city "ROMA" :region "LAZIO"
               :combined-name "Rulli Monica" :individual-name "RULLI MONICA"
               :gender "F" :type "B" :performance "150" :static "4,04" :total "235,4"}
              {:team "SOTTOPRESSIONE" :city "MILANO" :region "LOMBARDIA"
               :combined-name "CRISTINA SERESINI" :individual-name "SERESINI CRISTINA"
               :gender "F" :type "R" :performance "77" :static "3,52" :total "158,2"}
              {:team "MONDOVI'" :city "MONDOVI' (CN)" :region "PIEMONTE"
               :combined-name "Paolo Frigo" :individual-name "FRIGO PAOLO"
               :gender "M" :type "R" :performance "140" :static "7,4" :total "301"}]
        combined (mapv (fn [{:keys [team city region combined-name gender type
                                    performance static total]}]
                         (str team "  " city "  " region "  " combined-name
                              "  " gender "  " type "  " performance "  " static
                              "  " total)) rows)
        individuals (map-indexed
                     (fn [i {:keys [team city region individual-name gender type performance]}]
                       (let [line (str team "  " city "  " region "  " individual-name
                                       "  " gender "  " type "  " performance "  " performance)]
                         {:source-sha256 individual/dynamic-sha256
                          :source-lines [{:page 1 :line (inc i) :text line}]
                          :raw {:fields {:location [team city region individual-name]
                                         :gender gender :result [type performance performance]}}
                          :parsed {:team team :source-name individual-name :gender gender
                                   :discipline "DYN" :type type :realised-performance performance
                                   :status :result}})) rows)
        result (links/reconcile links/combined-sha256 [(str/join "\n" combined)]
                                individuals)]
    (is (= 4 (count (:links result))))
    (is (= #{"178,5" "150" "77" "140"}
           (set (map :raw-result (:links result)))))
    (is (empty? (:links (links/reconcile
                         links/combined-sha256 [(first combined)]
                         [(first individuals)
                          (-> (first individuals)
                              (assoc-in [:parsed :source-name] "EVELINA CASANO")
                              (assoc-in [:raw :fields :location 3] "EVELINA CASANO")
                              (assoc-in [:source-lines 0 :line] 99))]))))))

(defn -main []
  (let [result (run-tests 'freediving.tuttinapnea-aggregate-links-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
