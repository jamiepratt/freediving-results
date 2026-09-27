(ns freediving.fedas-integration-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.fedas-indoor :as indoor]
            [freediving.fedas-outdoor :as outdoor]))

(def indoor-page
  (str "RESULTADOS DEL CAMPEONATO DE ESPAÑA DE APNEA INDOOR\nVitoria 26-27 de Abril de 2025\n"
       "CLASIFICACIÓN MASCULINA STA\n"
       "   1       Garikoitz         IRURETAGOIENA     Euskadi                 8:08        OK    100,00"))
(def outdoor-page
  (str "FEDERACIÓN ESPAÑOLA DE ACTIVIDADES SUBACUÁTICAS\nRadazul, Tenerife, 04-06/09/2026\n"
       "VIII CAMPEONATO DE ESPAÑA DE APNEA OUTDOOR TENERIFE 2026\nCLASIFICACIÓN MASCULINA FIM\n"
       "   1       Test               DIVER            FECDAS       86         86                   86      OK   100,00"))

(deftest only-selected-fedas-source-hashes-route-to-their-parsers
  (doseq [[sha page version] [[indoor/source-2025-sha256 indoor-page indoor/parser-version]
                              [outdoor/outdoor-2026-sha256 outdoor-page
                               (outdoor/parser-version outdoor/outdoor-2026-sha256)]]]
    (let [result (extraction/parse-pages sha [page])]
      (is (= version (:parser-version result)))
      (is (= 3 (:schema-version result)))
      (is (= :blocked (get-in result [:publication :status])))
      (is (= 1 (count (:candidates result))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source"
                        (extraction/parse-pages (apply str (repeat 64 "0")) [indoor-page])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source"
                        (extraction/parse-pages outdoor/outdoor-2025-sha256 [outdoor-page]))))

(deftest unsupported-fedas-claims-cannot-enter-observations
  (doseq [artifact [{:source-sha256 outdoor/outdoor-2025-sha256
                     :parser-version "fedas-outdoor-2025/1" :schema-version 3}
                    {:source-sha256 (apply str (repeat 64 "0"))
                     :parser-version "fedas-indoor-2026/1" :schema-version 3}]]
    (is (extraction/fedas-claim? artifact))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                          (extraction/validate-fedas-artifact! "archive" artifact)))))

(deftest fedas-extraction-identity-and-source-replay
  (doseq [[sha page version] [[indoor/source-2025-sha256 indoor-page indoor/parser-version]
                              [outdoor/outdoor-2026-sha256 outdoor-page
                               (outdoor/parser-version outdoor/outdoor-2026-sha256)]]]
    (let [raw (str page "\f")
          validate! extraction/validate-fedas-artifact!]
      (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"
                                               :acquisitions [{:url "https://example.test"}]})
                    archive/extraction-evidence (constantly [])
                    archive/derive! (fn [_ _ build & _] (build))
                    shell/sh (fn [& args]
                               {:exit 0 :out (case (first args)
                                               "pdfinfo" "Pages: 1\n"
                                               "pdftotext" raw)
                                :err (if (some #{"-v"} args) "test-version" "")})]
        (let [artifact (extraction/extract! "archive" sha {:actor "test" :config {}})]
          (is (= version (:parser-version artifact)))
          (is (= 3 (:schema-version artifact)))
          (is (= artifact (validate! "archive" artifact)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                                (validate! "archive" (assoc-in artifact [:candidates 0 :parsed :unit] "forged"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                                (validate! "archive" (assoc artifact :parser-version "generic/1")))))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fedas-integration-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
