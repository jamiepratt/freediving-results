(ns freediving.ffessm-2025-monofin-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]))

(def sources
  [["48e9f2a761b432382af672d7fe7adc555594435e55b527013287b586eee1f84f" "monopalme-femmes" 2 0 "Femmes"]
   ["d88ec7eb3fef240f7f76dadaef8ec87c90475f288d67e4d8ba00fc2844cbac36" "monopalme-hommes" 8 3 "Hommes"]
   ["4c141387c47911b87b23dffd747c1c30ecaa250290aa9f39fae18b69c3ba39d4" "monopalme-hommes-juniors" 1 0 "Hommes Juniors"]])

(defn pages [name]
  (let [raw (slurp (io/resource (str "fixtures/ffessm-2025-b17/" name ".txt")))
        segments (vec (str/split raw #"\f" -1))]
    (if (= "" (last segments)) (pop segments) segments)))

(deftest printed-monofin-results-are-cited-and-review-blocked
  (doseq [[sha name count-expected dq-expected category] sources]
    (let [result (extraction/parse-pages sha (pages name))
          rows (:candidates result)]
      (is (= :needs-review (:status result)) name)
      (is (= 3 (:schema-version result)) name)
      (is (= count-expected (count rows)) name)
      (is (= count-expected (get-in result [:reconciliation :parsed-count])) name)
      (is (= (case name "monopalme-femmes" [9 10] "monopalme-hommes" (vec (range 10 18)) [10])
             (mapv #(get-in % [:coordinates :line]) rows)) name)
      (is (= (mapv #(get-in % [:raw :line]) rows)
             (mapv #(nth (str/split (first (pages name)) #"\n" -1)
                         (dec (get-in % [:coordinates :line]))) rows)) name)
      (is (= dq-expected (count (filter #(= :disqualified (get-in % [:parsed :result-status])) rows))) name)
      (is (every? #(and (= category (get-in % [:parsed :category]))
                        (nil? (get-in % [:parsed :event-date]))
                        (= 1 (get-in % [:coordinates :page]))
                        (= (get-in % [:raw :line]) (get-in % [:source-lines 0 :text]))) rows) name)
      (is (= :blocked (get-in result [:publication :status])) name))))

(deftest penalties-and-dq-preserve-print
  (let [women (:candidates (extraction/parse-pages (ffirst sources) (pages "monopalme-femmes")))
        men (:candidates (extraction/parse-pages (first (second sources)) (pages "monopalme-hommes")))
        andre (second women)
        dq (filter #(= :disqualified (get-in % [:parsed :result-status])) men)]
    (is (= "-72" (get-in andre [:raw :fields :final-points])))
    (is (= -72M (get-in andre [:parsed :final-points])))
    (is (= "75 m" (get-in andre [:raw :fields :announced-depth])))
    (is (= 2M (get-in andre [:parsed :realized-depth])))
    (is (= 73M (get-in andre [:parsed :depth-penalty])))
    (is (= 1M (get-in andre [:parsed :plate-penalty])))
    (is (= #{"Gilles GAMBINI" "Mathieu MARAIO" "Stéphane TOURREAU"}
           (set (map #(get-in % [:parsed :source-name]) dq))))
    (is (every? #(and (nil? (get-in % [:parsed :rank]))
                      (= "DQ" (get-in % [:raw :fields :rank]))
                      (= "Rouge" (get-in % [:parsed :card]))
                      (= 0M (get-in % [:parsed :final-points]))) dq))))

(deftest source-hash-boundary
  (doseq [[_ name] sources]
    (is (thrown? clojure.lang.ExceptionInfo
                 (extraction/parse-pages (apply str (repeat 64 "0")) (pages name))) name)))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2025-monofin-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
