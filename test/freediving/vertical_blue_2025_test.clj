(ns freediving.vertical-blue-2025-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.vertical-blue-2025 :as vertical]))

(def sample-page
  (str "VERTICAL BLUE 2025\nRESULTS\nDAY 3 - July 3, 2025\n"
       "Name    Gender    Country    Category Discipline    Depth Declared Depth Reached Dive Time Card Penalties Final Perfomance Record Reason\n"
       "Alfredo Miguel ROËN\n"
       "                    M          Spain           Senior     CWT         95      86      0:02:54    Y        1            76                   Early turn\n"
       "MARTÍN\n"
       "Simon BENNETT           M           Chile          Senior    CWT-BF       67     DNS                DNS                    0                      DNS\n"
       "Claire PARIS            F                          Senior      FIM        57      57      0:02:22    W                     57\n"))

(deftest printed-results-retain-position-and-uncertainty
  (let [result (vertical/parse-pages [sample-page])
        [penalty dns claire] (:candidates result)]
    (is (= 3 (get-in result [:reconciliation :candidate-count])))
    (is (= "Alfredo Miguel ROËN MARTÍN" (get-in penalty [:parsed :source-name])))
    (is (= [1 5] ((juxt #(get-in % [:coordinates :page]) #(get-in % [:coordinates :line])) penalty)))
    (is (= "95" (get-in penalty [:raw :fields :depth-declared])))
    (is (= 76M (get-in penalty [:parsed :final-performance])))
    (is (= "2025-07-03" (get-in penalty [:parsed :event-date])))
    (is (= "DNS" (get-in dns [:parsed :status])))
    (is (nil? (get-in dns [:parsed :depth-reached])))
    (is (nil? (get-in claire [:parsed :representation])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest unexpected-result-value-is-visible-but-unparsed
  (let [page (str sample-page "Zed TEST           M           Chile          Senior    CNF       50      50      0:02:54    ?                     50\n")
        result (vertical/parse-pages [page])]
    (is (= 4 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :unparsed (:parse-status (last (:candidates result)))))))

(deftest wrapped-country-and-reason-retain-source-lines
  (let [page (str "VERTICAL BLUE 2025\nRESULTS\nDAY 7 - July 9, 2025\n"
                  "Name Gender Country Category Discipline Depth Declared Depth Reached Final Perfomance\n"
                  "                               United States\n"
                  "Claire PARIS            F                          Senior     CWT         62      62      0:02:11    W                  62\n"
                  "                                of America\n"
                  "                                                                                                                                             DQ\n"
                  "Vera GIAMPIETRO     F       Switzerland        Senior     CNF         53      47      0:01:51    R                   0\n"
                  "                                                                                                                                            Pulling\n"
                  "July 7 2025 16:34\n")
        result (vertical/parse-pages [page])
        [claire vera] (:candidates result)]
    (is (= "United States of America" (get-in claire [:parsed :representation])))
    (is (= "DQ Pulling" (get-in vera [:parsed :status])))
    (is (= 3 (count (:source-lines claire))))
    (is (true? (get-in result [:reconciliation :per-page 0 :date-conflict?])))))

(deftest line-broken-name-preserves-printing-and-a-searchable-name
  (let [page (str "VERTICAL BLUE 2025\nRESULTS\nDAY 1 - July 1, 2025\n"
                  "Name Gender Country Category Discipline Depth Declared Depth Reached Final Perfomance\n"
                  "Mathias LLANO-\n"
                  "                   M       Colombia     Seniors      CNF        70        70      0:02:50    W                     70\n"
                  "WHITE\n")
        candidate (first (:candidates (vertical/parse-pages [page])))]
    (is (= "Mathias LLANO- WHITE" (get-in candidate [:raw :fields :source-name])))
    (is (= "Mathias LLANO-WHITE" (get-in candidate [:parsed :source-name])))))

(deftest official-layout-dispatches-through-public-extraction
  (let [pages (mapv (fn [day]
                      (str "VERTICAL BLUE 2025\nRESULTS\nDAY " day " - July " day ", 2025\n"
                           "Name Gender Country Category Discipline Depth Declared Depth Reached Final Perfomance\n"))
                    (range 1 10))]
    (is (= vertical/parser-version (:parser-version (extraction/parse-pages pages))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vertical-blue-2025-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
