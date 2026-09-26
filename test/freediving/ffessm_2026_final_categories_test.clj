(ns freediving.ffessm-2026-final-categories-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.ffessm-2026-final-categories :as final]))

(def sources
  [[final/cnf-men-sha256 "sans-palmes-hommes" {:epreuve 11 :championnat-de-france 10} 16 5 10]
   [final/fim-women-sha256 "immersion-libre-femmes" {:championnat-de-france 4} 4 0 0]
   [final/fim-men-sha256 "immersion-libre-hommes" {:epreuve 10 :championnat-de-france 9} 17 2 9]])

(defn pages [name]
  (let [raw (slurp (io/resource (str "fixtures/ffessm-2026-b16/" name ".txt")))
        segments (vec (str/split raw #"\f" -1))]
    (if (= "" (last segments)) (pop segments) segments)))

(deftest every-printed-result-is-cited-and-review-blocked
  (doseq [[sha name sections ranked disqualified links] sources]
    (let [result (extraction/parse-pages sha (pages name))
          rows (:candidates result)
          parsed (map :parsed rows)]
      (is (final/supported? sha (pages name)) name)
      (is (= :needs-review (:status result)) name)
      (is (= sections (get-in result [:reconciliation :section-counts])) name)
      (is (= (+ ranked disqualified) (count rows)) name)
      (is (= (count rows) (get-in result [:reconciliation :parsed-count])) name)
      (is (= ranked (count (filter :rank parsed))) name)
      (is (= disqualified (count (filter #(= :disqualified (:result-status %)) parsed))) name)
      (is (= links (get-in result [:reconciliation :linked-subset-count])) name)
      (is (every? #(and (= 1 (get-in % [:coordinates :page]))
                        (pos? (get-in % [:coordinates :line]))
                        (= (get-in % [:raw :line]) (get-in % [:source-lines 0 :text]))
                        (nil? (get-in % [:parsed :event-date]))
                        (= "m" (get-in % [:parsed :unit]))) rows) name)
      (is (= :blocked (get-in result [:publication :status])) name))))

(deftest penalties-and-disqualifications-preserve-source-values
  (let [cnf (:candidates (final/parse-pages final/cnf-men-sha256 (pages "sans-palmes-hommes")))
        fim-women (:candidates (final/parse-pages final/fim-women-sha256 (pages "immersion-libre-femmes")))
        fim-men (:candidates (final/parse-pages final/fim-men-sha256 (pages "immersion-libre-hommes")))
        goix (first (filter #(= "Maxime Goix" (get-in % [:parsed :source-name])) cnf))
        passalboni (last fim-women)
        gombert (first (filter #(= "Charles-Antoine Gombert" (get-in % [:parsed :source-name])) fim-men))]
    (is (= "8m" (get-in goix [:raw :fields :depth-penalty])))
    (is (= 28M (get-in goix [:parsed :final-performance])))
    (is (= 21M (get-in passalboni [:parsed :depth-penalty])))
    (is (= 35M (get-in passalboni [:parsed :final-performance])))
    (is (= :disqualified (get-in gombert [:parsed :result-status])))
    (is (= "DQ syncope immergée" (get-in gombert [:parsed :reason])))
    (is (nil? (get-in gombert [:parsed :rank])))))

(deftest source-identity-is-enforced
  (doseq [[sha name] (map #(take 2 %) sources)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (extraction/parse-pages (apply str (repeat 64 "0")) (pages name))) name)
    (is (false? (final/supported? sha (pages (if (= name "immersion-libre-femmes") "sans-palmes-hommes" "immersion-libre-femmes")))) name)))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2026-final-categories-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
