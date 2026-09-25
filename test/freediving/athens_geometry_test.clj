(ns freediving.athens-geometry-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [freediving.athens :as legacy]
            [freediving.athens-geometry :as athens]
            [freediving.extraction :as extraction]
            [freediving.archive :as archive]
            [freediving.observations :as observations]
            [freediving.depth-2026-test :as pdf]
            [freediving.extraction-test :as fixture]))

(def wrapped-page
  (str fixture/dynbf-header "3    ̇ Synthetic SURNAME\n"
       "    Given TUR 176,0 176,0 BRONZE MEDAL\n"))
(def words
  [["3" 20 24 100] ["̇" 48 48 99] ["Synthetic" 65 95 101]
   ["SURNAME" 100 130 101] ["Gi" 35 45 101] ["ven" 46 60 101]
   ["TUR" 200 220 100] ["176,0" 280 310 100] ["176,0" 340 370 100]
   ["BRONZE" 400 425 100] ["MEDAL" 430 450 100]])
(defn xml [ws]
  (str "<doc><page width=\"600\" height=\"800\">"
       (apply str (for [[s x end y] ws]
                    (str "<word xMin=\"" x "\" xMax=\"" end "\" yMin=\"" y "\" yMax=\"" (+ y 9) "\">" s "</word>")))
       "</page></doc>"))

(deftest detached-mark-does-not-erase-unambiguous-result-fields
  (let [old (legacy/parse-pages [wrapped-page])
        result (athens/parse-pages-with-geometry [wrapped-page] (xml words))
        c (first (:candidates result))]
    (is (= "cmas-athens-pool/6" (:parser-version old)))
    (is (= "cmas-athens-pool/7" (:parser-version result)))
    (is (= :unparsed (:parse-status c)))
    (is (nil? (:parsed c)))
    (is (= [3 "TUR" 176M 176M "BRONZE MEDAL"]
           (mapv #(get-in c [:fields % :value]) [:rank :representation :realized-distance :final-distance :notes])))
    (is (= :ambiguous (get-in c [:fields :source-name :status])))
    (is (= ["Gi" "ven" "̇" "Synthetic" "SURNAME"] (mapv :text (:name-geometry-evidence c))))
    (is (= (:raw (first (:candidates old))) (:raw c)))
    (is (= (:reconciliation old) (:reconciliation result)))
    (is (some #{:detached-combining-mark} (:unresolved-reasons c)))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest detached-fragments-do-not-silently-certify-truncated-names
  (let [page (str fixture/athens-header "1 Sy nthetic NAME GBR 100 100\nfi\n")
        old (legacy/parse-pages [page])
        result (athens/parse-pages-with-geometry [page] "<doc><page width=\"600\" height=\"800\"><word xMin=\"10\" xMax=\"11\" yMin=\"800\" yMax=\"800\">fi</word></page></doc>")
        c (first (:candidates result))]
    (is (= (:raw (first (:candidates old))) (:raw c)))
    (is (= (:parsed (first (:candidates old))) (:parsed c)))
    (is (some #{:detached-name-fragment-unresolved} (:unresolved-reasons c)))
    (is (= ["fi"] (mapv :text (:detached-fragment-geometry c))))
    (is (= 2 (get-in result [:reconciliation :candidate-count])))
    (is (= (:reconciliation old) (:reconciliation result)))))

(deftest recovered-sta-fields-retain-existing-time-uncertainty
  (let [page (str fixture/sta-header "3    ̇ Synthetic SURNAME\n    Given TUR 05:35 BRONZE MEDAL\n")
        ws (concat (take 7 words) [["05:35" 340 370 100]] (drop 9 words))
        c (first (:candidates (athens/parse-pages-with-geometry [page] (xml ws))))]
    (is (= :ambiguous (get-in c [:fields :unit :status])))
    (is (= :time-unit-not-explicit (get-in c [:fields :unit :reason])))
    (is (= [5 35] (get-in c [:fields :final-time :value :components])))
    (is (= :unparsed (:parse-status c)))))

(def synthetic-stream
  (str "BT /F1 8 Tf 20 750 Td "
       "(2025 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR, GREECE) Tj 0 -20 Td "
       "(MAY, 20, 2025) Tj 0 -20 Td (DNF FINAL RESULTS) Tj 0 -20 Td "
       "(SENIORS \\261 WOMEN) Tj 0 -20 Td "
       "(# Name & surname Country Realized Final Notes) Tj 0 -20 Td "
       "(Distance \\(m\\) Distance \\(m\\)) Tj 0 -20 Td "
       "(1 Sample NAME GBR 100 100) Tj ET"))
(deftest extraction-selects-versioned-geometry-evidence
  (let [{:keys [root result hash options receipt]} (pdf/extract-stream synthetic-stream)]
    (is (= "cmas-athens-pool/7" (:parser-version result)))
    (is (= 3 (:schema-version result)))
    (is (string? (:geometry-xml result)))
    (when (:geometry-xml result)
      (is (= result (extraction/validate-geometry-artifact! root result))))
    (is (= (:job-id receipt) (:job-id (extraction/extract! root hash options))))))

(deftest earlier-source-families-keep-their-dispatch-precedence
  (let [mixed (str/replace synthetic-stream ") Tj ET"
                           ") Tj 0 -20 Td (2025 CMAS World Championship Freediving Outdoor) Tj 0 -20 Td (CWT Women SENIORS) Tj ET")
        {:keys [result]} (pdf/extract-stream mixed)
        expected (extraction/parse-pages (mapv :text (:pages result)))]
    (is (= "cmas-women-depth/1" (:parser-version result)))
    (is (= expected (select-keys result (keys expected))))
    (is (nil? (:geometry-xml result)))))

(deftest uncertain-geometry-never-recovers-fields
  (doseq [geometry [(xml (into words (mapv #(update % 3 + 30) words)))
                    (xml (assoc-in words [0 1] -1))
                    (xml (assoc-in words [4 0] "Altered"))
                    (xml (vec (remove #(= "̇" (first %)) words)))
                    "<doc></doc>"]]
    (let [c (first (:candidates (athens/parse-pages-with-geometry [wrapped-page] geometry)))]
      (is (= :unparsed (:parse-status c)))
      (is (nil? (:name-geometry-evidence c)))
      (is (nil? (get-in c [:fields :rank]))))))

(defn install-artifact [root artifact]
  (let [job (pdf/sha (pr-str (pdf/canonical (select-keys artifact observations/identity-keys))))
        artifact (assoc artifact :job-id job)]
    (archive/derive! root job (constantly artifact) nil)))

(deftest new-evidence-replays-and-legitimate-legacy-six-remains-immutable
  (let [{:keys [root result receipt]} (pdf/extract-stream synthetic-stream)
        old (-> (merge result (legacy/parse-pages (mapv :text (:pages result))))
                (dissoc :geometry-xml) (update :tool dissoc :geometry-arguments))
        old-receipt (install-artifact root old)
        bytes (slurp (:artifact-path old-receipt))
        historical (edn/read-string bytes)]
    (is (not= (:job-id receipt) (:job-id old-receipt)))
    (is (= historical (extraction/validate-legacy-athens-artifact! root historical)))
    (doseq [job [(:job-id receipt) (:job-id old-receipt)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Database URL required"
                            (observations/import! nil root job))))
    (is (= bytes (slurp (:artifact-path (install-artifact root old)))))))

(deftest rehashed-changes-and-historical-downgrades-fail-before-database
  (let [{:keys [root result]} (pdf/extract-stream (str/replace synthetic-stream ") Tj ET" ") Tj 0 -20 Td (fi) Tj ET"))]
    (doseq [changed (concat [(assoc-in result [:candidates 0 :parsed :final-distance] 999M)
                             (update-in result [:candidates 0 :unresolved-reasons] #(vec (remove #{:detached-name-fragment-unresolved} %)))
                             (assoc result :geometry-xml "")]
                            (for [version ["cmas-athens-pool/4" "cmas-athens-pool/5" "cmas-athens-pool/6" "cmas-cwt-men/1"]]
                              (-> result (dissoc :geometry-xml) (update :tool dissoc :geometry-arguments)
                                  (assoc :parser-version version)))
                            [(-> result (dissoc :geometry-xml) (update :tool dissoc :geometry-arguments)
                                 (assoc :parser-version "cmas-cwt-men/1" :candidates []))])]
      (let [receipt (install-artifact root (update changed :config assoc :mutation (pdf/sha (pr-str changed))))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay|geometry extraction contract"
                              (observations/import! nil root (:job-id receipt))))))))
