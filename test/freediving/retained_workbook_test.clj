(ns freediving.retained-workbook-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.parser-routing :as routing]
            [freediving.retained-workbook :as workbook])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- xlsx [cell-value]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[path content]
              {"xl/worksheets/sheet1.xml"
               (str "<worksheet><sheetData><row r='2'><c r='A2' t='inlineStr'><is><t>"
                    cell-value "</t></is></c></row></sheetData></worksheet>")}]
        (.putNextEntry zip (ZipEntry. path))
        (.write zip (.getBytes content "UTF-8"))
        (.closeEntry zip)))
    (.toByteArray out)))

(deftest packet-cell-must-match-original-workbook
  (let [bytes (xlsx "source value")
        cell {:citation "Example!A2" :value "source value" :formula nil :cell_type "inlineStr"}
        row {:sheet "Example" :row 2 :cells {"A2" cell}}
        sheets [{:name "Example" :rows [row]}]]
    (is (true? (workbook/packet-cells-match? bytes sheets {"Example" "xl/worksheets/sheet1.xml"})))
    (is (false? (workbook/packet-cells-match? bytes
                                              [{:name "Example" :rows [(assoc row :cells {"A2" (assoc cell :value "forged")})]}]
                                              {"Example" "xl/worksheets/sheet1.xml"})))))

(deftest unsupported-workbooks-and-receipts-claim-nothing
  (let [bytes (xlsx "source value")
        document {:format :workbook :source-sha256 workbook/retained-source-sha256
                  :positions []}
        result (workbook/claims-for-document document {:bytes bytes :receipt {}})]
    (is (empty? (:claims result)))
    (is (= :failed (:source-verification result)))))

(deftest row-positions-retain-cell-citations-and-evidence-role
  (let [hash workbook/retained-source-sha256
        rows [{:sheet "FIRENZE 2025" :row 2 :kind "dynamic_result"
               :cells {"A2" {:citation "FIRENZE 2025!A2"}
                       "F2" {:citation "FIRENZE 2025!F2"}}}
              {:sheet "FIRENZE 2025" :row 2 :kind "secondary_score"
               :cells {"O2" {:citation "FIRENZE 2025!O2"}}}]
        positions (workbook/positions-for-census hash {:sheets [{:rows rows}]})]
    (is (= ["FIRENZE 2025!A2" "FIRENZE 2025!F2"]
           (get-in positions [0 :coordinates :cell-citations])))
    (is (= [:individual-result :aggregate]
           (mapv #(get-in % [:coordinates :evidence-role]) positions)))
    (let [routed (routing/route-document
                  {:format :workbook :source-sha256 hash :positions positions}
                  [{:parser-id "fixture" :parser-version "1" :match-reason "fixture"
                    :source-restriction {:sha256s #{hash} :formats #{:workbook}}
                    :supported-positions (set (map :id positions))
                    :claimed-positions (set (map :id positions))}])]
      (is (= [:individual-result :aggregate]
             (mapv #(get-in % [:coordinates :evidence-role]) (:routed routed)))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.retained-workbook-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
