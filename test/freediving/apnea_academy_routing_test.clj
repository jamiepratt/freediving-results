(ns freediving.apnea-academy-routing-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.san-mauro-static-2026 :as static]
            [freediving.tuttinapnea-2026 :as tutt]))

(def static-page
  (str "GIRO D'ITALIA IN APNEA - TROFEO SAN MAURO\nPomigliano d'Arco 1 marzo 2026\nAPNEA STATICA\n"
       "Classifica femminile\n( MM,SS )\n"
       " 1 In Apnea A.S.D.                  Giovagnoli Arianna              F         5,36             117,60\n"
       "Classifica maschile\n"
       " 1 A.S.D. Tresse Diving Club        Beretta Lorenzo                M          6,43             141,05\n"))
(def tutt-page
  (str "RESOCONTO GARA NAZIONALE\nTUTTINAPNEA FEBBRAIO 2026\nSTATICA\nPUNTEGGIO\n"
       "IN APNEA  ROMA  LAZIO  MARICA SANTACROCE  F  3,55  10  74,025\n"))
(def tutt-dynamic-page
  (str "RESOCONTO GARA NAZIONALE\nTUTTINAPNEA FEBBRAIO 2026\nDINAMICA\nPUNTEGGIO\n"
       "SOTTOPRESSIONE  MILANO  LOMBARDIA  SILVANA LONGONI  F  B  119  10  107,1\n"))

(deftest exact-source-routing-preserves-result-evidence
  (doseq [[sha page version unit] [[static/source-sha256 static-page static/parser-version "min,sec"]
                                   [tutt/static-sha256 tutt-page tutt/parser-version "min,sec"]
                                   [tutt/dynamic-sha256 tutt-dynamic-page tutt/parser-version "m"]]]
    (let [result (extraction/parse-pages sha [page])
          row (first (:candidates result))]
      (is (= version (:parser-version result)))
      (is (= 3 (:schema-version result)))
      (is (= unit (get-in row [:parsed :unit])))
      (is (= {:page 1 :line (get-in row [:coordinates :line])}
             (select-keys (:coordinates row) [:page :line])))
      (is (= (get-in row [:raw :line]) (:text (first (:source-lines row)))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source"
                        (extraction/parse-pages (apply str (repeat 64 "0")) [static-page])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source"
                        (extraction/parse-pages (apply str (repeat 64 "0")) [tutt-page]))))

(deftest source-bound-import-replay-rejects-tampering
  (doseq [[sha page] [[static/source-sha256 static-page]
                      [tutt/static-sha256 tutt-page]
                      [tutt/dynamic-sha256 tutt-dynamic-page]]]
    (let [raw (str page "\f")
          artifact (merge (extraction/parse-pages sha [page])
                          {:source-sha256 sha :raw-text raw
                           :tool {:name "pdftotext" :version "test-version"
                                  :arguments ["-layout" "-enc" "UTF-8"]}})
          validate! (if (= sha static/source-sha256)
                      extraction/validate-san-mauro-static-artifact!
                      extraction/validate-tuttinapnea-artifact!)
          claim? (if (= sha static/source-sha256)
                   extraction/san-mauro-static-claim?
                   extraction/tuttinapnea-claim?)]
      (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                    shell/sh (fn [& args] {:exit 0 :out (if (= "-v" (second args)) "" raw)
                                           :err (if (= "-v" (second args)) "test-version" "")})]
        (is (= artifact (validate! "archive" artifact)))
        (is (claim? (assoc artifact :parser-version "generic/1")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                              (validate! "archive" (assoc artifact :parser-version "generic/1"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                              (validate! "archive" (assoc-in artifact [:candidates 0 :parsed :unit] "forged"))))))))

(deftest extraction-identity-selects-source-bound-schema-three
  (doseq [[sha page version] [[static/source-sha256 static-page static/parser-version]
                              [tutt/static-sha256 tutt-page tutt/parser-version]
                              [tutt/dynamic-sha256 tutt-dynamic-page tutt/parser-version]]]
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf" :acquisitions [{:url "https://example.test"}]})
                  archive/extraction-evidence (fn [& _] [])
                  archive/derive! (fn [_ _ build & _] (build))
                  shell/sh (fn [& args]
                             (let [tool (first args)]
                               {:exit 0
                                :out (case tool "pdftotext" (str page "\f") "pdfinfo" "Pages: 1\n")
                                :err (if (some #{"-v"} args) "test-version" "")}))]
      (let [artifact (extraction/extract! "archive" sha {:actor "test" :config {}})]
        (is (= version (:parser-version artifact)))
        (is (= 3 (:schema-version artifact)))
        (is (= (if (= sha static/source-sha256) 2 1)
               (get-in artifact [:reconciliation :candidate-count])))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.apnea-academy-routing-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
