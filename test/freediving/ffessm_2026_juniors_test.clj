(ns freediving.ffessm-2026-juniors-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.ffessm-2026-juniors :as juniors]))

(defn source-pages []
  (let [segments (vec (str/split (slurp (io/resource "fixtures/ffessm-2026-b16/juniors.txt")) #"\f" -1))]
    (if (= "" (last segments)) (pop segments) segments)))

(deftest every-junior-position-is-source-cited
  (let [pages (source-pages)
        result (extraction/parse-pages juniors/source-sha256 pages)
        rows (:candidates result)]
    (is (juniors/supported? pages))
    (is (= :needs-review (:status result)))
    (is (= 6 (get-in result [:reconciliation :candidate-count])))
    (is (= 6 (get-in result [:reconciliation :parsed-count])))
    (is (= {"Femmes FIM" 2 "Hommes FIM" 2 "Hommes CWT bi" 2}
           (get-in result [:reconciliation :section-counts])))
    (is (= [9 10 16 17 23 24] (mapv #(get-in % [:coordinates :line]) rows)))
    (is (= [1 2 1 2 1 2] (mapv #(get-in % [:parsed :rank]) rows)))
    (is (= ["FIM" "FIM" "FIM" "FIM" "CWT bi" "CWT bi"]
           (mapv #(get-in % [:parsed :discipline]) rows)))
    (is (= ["Juniors Femmes" "Juniors Femmes" "Juniors Hommes" "Juniors Hommes"
            "Juniors Hommes" "Juniors Hommes"]
           (mapv #(get-in % [:parsed :category]) rows)))
    (is (= ["Tps immersion 1'07'75" "Tps d'immersion 1'21'25"
            "record de France" nil "record de France" nil]
           (mapv #(get-in % [:raw :fields :comment]) rows)))
    (is (= [25M 25M 40M 35M 36M 35M]
           (mapv #(get-in % [:parsed :final-performance]) rows)))
    (is (every? #(and (= 1 (get-in % [:coordinates :page]))
                      (= (get-in % [:raw :line]) (get-in % [:source-lines 0 :text]))
                      (= :parsed (:parse-status %))
                      (= :unreviewed (:review-status %))
                      (= "m" (get-in % [:parsed :unit]))
                      (= :valid (get-in % [:parsed :result-status]))
                      (nil? (get-in % [:parsed :event-date]))) rows))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest junior-source-identity-is-enforced
  (is (thrown? clojure.lang.ExceptionInfo
               (extraction/parse-pages (apply str (repeat 64 "0")) (source-pages)))))

(deftest changed-junior-score-remains-visible-and-incomplete
  (let [pages (update (source-pages) 0 str/replace "36       Blanc" "??       Blanc")
        result (juniors/parse-pages pages)]
    (is (= 6 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= :unparsed (:parse-status (nth (:candidates result) 4))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2026-juniors-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
