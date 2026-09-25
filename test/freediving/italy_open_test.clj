(ns freediving.italy-open-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
            [freediving.italy-open :as italy]))

(def heading "Classiﬁca OPEN M - CWTB OPEN International")
(def row " 1           Example       Athlete        Example Club                 1990      2:00.00            40     2:01.00             40                  2:01.00           40     0:01.00         20.0")
(def continuation "             Other         Athlete        Example Club                 1991      2:00.00            40     2:01.00             40")
(defn page [& lines]
  (str/join "\n" lines))

(deftest italian-open-routes-and-preserves-table-evidence
  (let [result (extraction/parse-pages [(page "Campionati Italiani Open di Apnea Outdoor" heading
                                              "Posizione   Cognome   Nome   Società" row
                                              "DQ: Squaliﬁca (vedi note) Penalità: PG-SC: Salto corsia - PG-PA: Presenza assistente - PG-PG: Penalità Generica")
                                        (page "Posizione   Cognome   Nome   Società" continuation)])
        [a b] (:candidates result)]
    (is (= italy/parser-version (:parser-version result)))
    (is (= 3 (:schema-version result)))
    (is (= [1 2] (mapv #(get-in % [:coordinates :page]) [a b])))
    (is (= [4 2] (mapv #(get-in % [:coordinates :line]) [a b])))
    (is (= ["Example Athlete" "Other Athlete"] (mapv #(get-in % [:parsed :source-name]) [a b])))
    (is (= [1 nil] (mapv #(get-in % [:parsed :rank]) [a b])))
    (is (= ["OPEN M" "OPEN M"] (mapv #(get-in % [:parsed :category]) [a b])))
    (is (= row (get-in a [:raw :line])))
    (is (= {:page 1 :line 2 :text heading} (first (:metadata-evidence b))))
    (is (nil? (get-in a [:parsed :event-date])))
    (is (nil? (get-in a [:parsed :unit])))
    (is (= 2 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :rank-shaped-line-count])))
    (is (= 7 (get-in result [:reconciliation :nonblank-line-count])))
    (is (= 5 (count (get-in result [:reconciliation :noncandidate-lines]))))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest changed-heading-and-unknown-line-remain-unresolved
  (let [r (extraction/parse-pages [(page "Campionati Italiani Open di Apnea Outdoor"
                                         "Classiﬁca OPEN M - XYZ OPEN International"
                                         "unexplained")])]
    (is (= italy/parser-version (:parser-version r)))
    (is (= [1] (get-in r [:reconciliation :unsupported-pages])))
    (is (= 3 (get-in r [:reconciliation :nonblank-line-count])))))

(deftest unranked-status-is-preserved-as-adjacent-source-evidence
  (let [result (extraction/parse-pages
                [(page "Campionati Italiani Open di Apnea Outdoor" heading "BO" continuation "Superficie")])
        c (first (:candidates result))]
    (is (= :parsed (:parse-status c)))
    (is (nil? (get-in c [:parsed :rank])))
    (is (= ["BO" "Superficie"]
           (mapv (comp str/trim :text) (get-in c [:raw :annotation-evidence]))))
    (is (= [3 5] (mapv :line (get-in c [:raw :annotation-evidence]))))
    (is (= 1 (get-in result [:reconciliation :candidate-count])))))
