(ns freediving.ffessm-2025-immersion-libre-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2025-immersion-libre :as immersion]))

(def sources
  [[immersion/women-sha256 "immersion-libre-femmes" 7 (vec (range 10 17)) "Femmes" "F"]
   [immersion/men-sha256 "immersion-libre-hommes" 9 (vec (range 9 18)) "Hommes" "M"]])

(defn pages [name]
  (let [raw (slurp (io/resource (str "fixtures/ffessm-2025-b18/" name ".txt")))
        segments (vec (str/split raw #"\f" -1))]
    (if (= "" (last segments)) (pop segments) segments)))

(deftest every-printed-immersion-position-has-an-exact-citation
  (doseq [[sha name count-expected lines category gender] sources]
    (let [source (pages name)
          result (immersion/parse-pages sha source)
          rows (:candidates result)]
      (is (immersion/supported? sha source) name)
      (is (= :needs-review (:status result)) name)
      (is (= 3 (:schema-version result)) name)
      (is (= count-expected (count rows)) name)
      (is (= lines (mapv #(get-in % [:coordinates :line]) rows)) name)
      (is (= (mapv #(get-in % [:raw :line]) rows)
             (mapv #(nth (str/split (first source) #"\n" -1)
                         (dec (get-in % [:coordinates :line]))) rows)) name)
      (is (every? #(and (= :parsed (:parse-status %))
                        (= :unreviewed (:review-status %))
                        (= category (get-in % [:parsed :category]))
                        (= gender (get-in % [:parsed :gender]))
                        (= "FIM" (get-in % [:parsed :discipline]))
                        (= "m" (get-in % [:parsed :unit]))
                        (nil? (get-in % [:parsed :event-date]))
                        (= 1 (get-in % [:coordinates :page]))
                        (= (get-in % [:raw :line]) (get-in % [:source-lines 0 :text]))) rows) name)
      (is (= :complete (get-in result [:reconciliation :coverage])) name)
      (is (= count-expected (get-in result [:reconciliation :parsed-count])) name)
      (is (= count-expected (get-in result [:reconciliation :unresolved-count])) name)
      (is (= :blocked (get-in result [:publication :status])) name))))

(deftest ranks-nationality-and-penalties-preserve-the-print
  (let [women (:candidates (immersion/parse-pages immersion/women-sha256 (pages "immersion-libre-femmes")))
        men (:candidates (immersion/parse-pages immersion/men-sha256 (pages "immersion-libre-hommes")))
        banegas (nth women 4)
        provenzani (nth men 3)
        romeo (last men)]
    (is (= "Italienne" (get-in (first women) [:raw :fields :nationality])))
    (is (= "Italienne" (get-in (first women) [:parsed :nationality])))
    (is (= [1 2 3 3 5 6 8 9 10] (mapv #(get-in % [:parsed :rank]) men)))
    (is (= "3" (get-in provenzani [:raw :fields :rank])))
    (is (= "85 m" (get-in provenzani [:raw :fields :announced-depth])))
    (is (= 84M (get-in provenzani [:parsed :realized-depth])))
    (is (= 1M (get-in provenzani [:parsed :depth-penalty])))
    (is (= 1M (get-in provenzani [:parsed :plate-penalty])))
    (is (= 82M (get-in provenzani [:parsed :final-points])))
    (is (= 59M (get-in banegas [:parsed :final-points])))
    (is (= "Jaune" (get-in banegas [:parsed :card])))
    (is (= 13M (get-in romeo [:parsed :depth-penalty])))
    (is (= 36M (get-in romeo [:parsed :final-points])))))

(deftest hash-and-layout-are-bound
  (let [women (pages "immersion-libre-femmes")
        altered (update women 0 str/replace "Résultats Immersion Libre Femmes" "Other Results")]
    (is (false? (immersion/supported? immersion/men-sha256 women)))
    (is (false? (immersion/supported? immersion/women-sha256 altered)))
    (is (= :partial-unsupported-needs-parser (:status (immersion/parse-pages immersion/women-sha256 altered))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2025-immersion-libre-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
