(ns freediving.ffessm-2025-bipalmes-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2025-bipalmes :as bipalmes]))

(def sources
  [[bipalmes/women-sha256 "bipalmes-femmes" 4 "Femmes" "F"]
   [bipalmes/men-sha256 "bipalmes-hommes" 7 "Hommes" "M"]])

(defn pages [name]
  (let [raw (slurp (io/resource (str "fixtures/ffessm-2025-b18/" name ".txt")))
        segments (vec (str/split raw #"\f" -1))]
    (if (= "" (last segments)) (pop segments) segments)))

(deftest every-printed-bipalmes-position-is-cited-and-blocked
  (doseq [[sha name expected category gender] sources]
    (let [source-pages (pages name)
          result (bipalmes/parse-pages sha source-pages)
          rows (:candidates result)
          printed (str/split (first source-pages) #"\n" -1)]
      (is (bipalmes/supported? sha source-pages) name)
      (is (= :needs-review (:status result)) name)
      (is (= 3 (:schema-version result)) name)
      (is (= expected (count rows)) name)
      (is (= expected (get-in result [:reconciliation :candidate-count])) name)
      (is (= expected (get-in result [:reconciliation :parsed-count])) name)
      (is (= :complete (get-in result [:reconciliation :coverage])) name)
      (is (= (vec (range 9 (+ 9 expected)))
             (mapv #(get-in % [:coordinates :line]) rows)) name)
      (is (every? (fn [row]
                    (let [{:keys [page line]} (:coordinates row)]
                      (and (= 1 page)
                           (= (nth printed (dec line)) (get-in row [:raw :line]))
                           (= (get-in row [:raw :line])
                              (get-in row [:source-lines 0 :text]))
                           (= :parsed (:parse-status row))
                           (= :unreviewed (:review-status row))
                           (= category (get-in row [:parsed :category]))
                           (= gender (get-in row [:parsed :gender]))
                           (= "CWT-BI" (get-in row [:parsed :discipline]))
                           (= "m" (get-in row [:parsed :unit]))
                           (nil? (get-in row [:parsed :event-date]))))) rows) name)
      (is (= :blocked (get-in result [:publication :status])) name))))

(deftest printed-ranks-nationalities-and-penalty-survive
  (let [women (:candidates (bipalmes/parse-pages bipalmes/women-sha256
                                                 (pages "bipalmes-femmes")))
        men (:candidates (bipalmes/parse-pages bipalmes/men-sha256
                                               (pages "bipalmes-hommes")))
        goix (last men)]
    (is (= ["Italienne" "Belge" "Française" "Syrienne"]
           (mapv #(get-in % [:raw :fields :nationality]) women)))
    (is (= [1 1 3 4 5 6 7] (mapv #(get-in % [:parsed :rank]) men)))
    (is (= "55 m" (get-in goix [:raw :fields :announced-depth])))
    (is (= "52" (get-in goix [:raw :fields :realized-depth])))
    (is (= "3" (get-in goix [:raw :fields :depth-penalty])))
    (is (= "1" (get-in goix [:raw :fields :plate-penalty])))
    (is (= "48" (get-in goix [:raw :fields :final-points])))
    (is (= [55M 52M 3M 1M 48M]
           (mapv #(get-in goix [:parsed %])
                 [:announced-depth :realized-depth :depth-penalty
                  :plate-penalty :final-points])))
    (is (= "Jaune" (get-in goix [:parsed :card])))
    (is (= :valid (get-in goix [:parsed :result-status])))))

(deftest source-identity-and-row-count-guard
  (let [women (pages "bipalmes-femmes")
        modified (update women 0 str/replace #"(?m)^    4        ALNABWANY.*\n" "")]
    (is (false? (bipalmes/supported? bipalmes/men-sha256 women)))
    (is (= :partial-unsupported-needs-parser
           (:status (bipalmes/parse-pages bipalmes/women-sha256 modified))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2025-bipalmes-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
