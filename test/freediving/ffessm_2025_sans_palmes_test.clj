(ns freediving.ffessm-2025-sans-palmes-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2025-sans-palmes :as parser]))

(def sources
  [[parser/women-sha256 "sans-palmes-femmes" 6 "Femmes" "F"]
   [parser/men-sha256 "sans-palmes-hommes" 12 "Hommes" "M"]])

(defn pages [name]
  (let [segments (vec (str/split (slurp (io/resource (str "fixtures/ffessm-2025-b18/" name ".txt"))) #"\f" -1))]
    (if (str/blank? (last segments)) (pop segments) segments)))

(deftest every-printed-position-is-cited-and-review-blocked
  (doseq [[sha name expected category gender] sources]
    (let [source-pages (pages name)
          result (parser/parse-pages sha source-pages)
          candidates (:candidates result)
          lines (str/split (first source-pages) #"\n" -1)]
      (is (= :needs-review (:status result)) name)
      (is (= 3 (:schema-version result)) name)
      (is (= expected (count candidates)) name)
      (is (= (vec (range 9 (+ 9 expected)))
             (mapv #(get-in % [:coordinates :line]) candidates)) name)
      (is (= expected (get-in result [:reconciliation :expected-rows])) name)
      (is (= expected (get-in result [:reconciliation :parsed-count])) name)
      (is (= :complete (get-in result [:reconciliation :coverage])) name)
      (is (every? (fn [candidate]
                    (and (= (nth lines (dec (get-in candidate [:coordinates :line])))
                            (get-in candidate [:raw :line])
                            (get-in candidate [:source-lines 0 :text]))
                         (= 1 (get-in candidate [:coordinates :page]))
                         (= :parsed (:parse-status candidate))
                         (= :unreviewed (:review-status candidate))
                         (= "CNF" (get-in candidate [:parsed :discipline]))
                         (= category (get-in candidate [:parsed :category]))
                         (= gender (get-in candidate [:parsed :gender]))
                         (= "m" (get-in candidate [:parsed :unit]))
                         (nil? (get-in candidate [:parsed :event-date])))) candidates) name)
      (is (= :blocked (get-in result [:publication :status])) name))))

(deftest ties-foreign-nationalities-penalties-and-disqualification
  (let [women (:candidates (parser/parse-pages parser/women-sha256 (pages "sans-palmes-femmes")))
        men (:candidates (parser/parse-pages parser/men-sha256 (pages "sans-palmes-hommes")))
        simonis (last women)
        goix (nth men 10)
        dq (last men)]
    (is (= [1 1 3 4 5 6] (mapv #(get-in % [:parsed :rank]) women)))
    (is (= [1 2 2 4 5 6 7 8 9 10 11 nil] (mapv #(get-in % [:parsed :rank]) men)))
    (is (= ["Syrienne" "Belge"] (mapv #(get-in % [:raw :fields :nationality]) [(nth women 3) simonis])))
    (is (= "-8" (get-in simonis [:raw :fields :final-points])))
    (is (= -8M (get-in simonis [:parsed :final-points])))
    (is (= 32M (get-in simonis [:parsed :depth-penalty])))
    (is (= -38M (get-in goix [:parsed :final-points])))
    (is (= :disqualified (get-in dq [:parsed :result-status])))
    (is (= "DQ" (get-in dq [:raw :fields :rank])))
    (is (= 0M (get-in dq [:parsed :final-points])))))

(deftest source-hash-boundary
  (doseq [[sha name] sources]
    (is (parser/supported? sha (pages name)) name)
    (is (not (parser/supported? (apply str (repeat 64 "0")) (pages name))) name)
    (is (not (parser/supported? sha (pages (if (= name "sans-palmes-femmes")
                                             "sans-palmes-hommes" "sans-palmes-femmes")))) name)))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2025-sans-palmes-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
