(ns freediving.jev-candidates-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [freediving.evaluation-protocol-test :as fixture]
            [freediving.evaluation-providers :as providers]
            [freediving.evaluation-providers-test :as http]
            [freediving.evaluation-test :as runner]
            [freediving.evaluation :as evaluation]
            [freediving.aida-html :as html]
            [freediving.aida-html-test :as html-fixture]
            [freediving.cmas-2025-indoor-json :as indoor-json]
            [freediving.cmas-2025-indoor-json-test :as json-fixture]
            [freediving.candidates :as retrieval]
            [freediving.spelling-normalization :as spelling]
            [freediving.jev-candidates :as candidates]))

(defn- source-row [job format artifact]
  (let [payload (first (:candidates artifact))
        archived (pr-str artifact)]
    {:job-id job :ordinal 0 :kind "result-row" :source-format format
     :candidate-id (str job "-candidate")
     :source-sha256 (:source-sha256 artifact) :artifact-sha256 (fixture/sha archived)
     :parser-version (:parser-version artifact) :schema-version (:schema-version artifact)
     :source-page-url (:source-page-url artifact) :payload payload
     :source-lines []}))

(deftest html-and-json-pairs-retain-format-locators-and-exact-source-rows
  (let [html-text (html-fixture/document (assoc html-fixture/cells 1 "BECHTEL Timothy"))
        html-artifact (assoc (html/parse-html html-text) :schema-version 4
                             :source-sha256 (fixture/sha html-text))
        json-bytes (.getBytes
                    (str/replace (String. ^bytes (json-fixture/static-source [json-fixture/static-row]) "UTF-8")
                                 "\"PlaName\":\"Timothy\"" "\"PlaName\" : \"Timothy\"")
                    "UTF-8")
        json-text (String. ^bytes json-bytes "UTF-8")
        json-artifact (assoc (indoor-json/parse-result json-bytes
                                                       {:view-url json-fixture/static-view-url
                                                        :json-url json-fixture/static-json-url})
                             :schema-version 5 :source-sha256 (fixture/sha json-text))
        rows [(source-row "html" :html html-artifact)
              (source-row "json" :json json-artifact)]
        archives {"html" (pr-str html-artifact) "json" (pr-str json-artifact)}
        build (fn [rows]
                (with-redefs-fn {#'freediving.jev-candidates/artifact
                                 (fn [_ job] (get archives job))}
                  #(candidates/candidate-case "reviewer" rows
                                              {:job-id "html" :ordinal 0}
                                              {:job-id "json" :ordinal 0})))
        pair (build rows)]
    (is (= pair (build rows)))
    (is (= (select-keys (first rows) [:parser-version :schema-version :source-format])
           (get-in pair [:input :left :source-version])))
    (is (= (select-keys (second rows) [:parser-version :schema-version :source-format])
           (get-in pair [:input :right :source-version])))
    (is (= {:table 1 :row 2} (get-in pair [:input :left :sources 0 :locator])))
    (is (= :html (get-in pair [:input :left :sources 0 :source-format])))
    (is (= (get-in html-artifact [:candidates 0 :raw :html])
           (get-in pair [:input :left :sources 0 :exact-lines 0])))
    (is (= {:row-index-zero-based 0 :source-page-url json-fixture/static-view-url}
           (get-in pair [:input :right :sources 0 :locator])))
    (is (= :json (get-in pair [:input :right :sources 0 :source-format])))
    (is (str/includes? (get-in pair [:input :right :sources 0 :exact-lines 0])
                       "\"PlaName\" : \"Timothy\""))
    (is (not (contains? (get-in pair [:input :right :sources 0]) :page)))
    (is (thrown? Exception (build (assoc-in rows [0 :payload :flags] [:changed]))))
    (is (thrown? Exception (build (assoc-in rows [1 :parser-version] "unknown/1"))))
    (is (thrown? Exception (build (assoc-in rows [1 :payload :parsed :source-name] "tampered"))))))

(defn- example []
  (let [left (pr-str {:pages [{:page 1 :lines [{:line 1 :text "Anastasiia Petrova"}]}]})
        right (pr-str {:pages [{:page 1 :lines [{:line 1 :text "Anastasia Petrova"}]}]})
        row (fn [job name archive]
              {:job-id job :ordinal 0 :kind "result-row" :source-format :pdf
               :candidate-id (str job "-candidate")
               :source-sha256 (fixture/sha (str job "-source"))
               :artifact-sha256 (fixture/sha archive)
               :payload {:parse-status :parsed :parsed {:source-name name :representation "POL"}}
               :source-lines [{:page 1 :line 1 :text name}]})]
    {:rows [(row "left" "Anastasiia Petrova" left)
            (row "right" "Anastasia Petrova" right)]
     :artifacts {"left" left "right" right}}))

(deftest candidate-pair-keeps-exact-source-evidence-and-stable-identity
  (let [{:keys [rows artifacts]} (example)
        build #(with-redefs-fn {#'freediving.jev-candidates/artifact (fn [_ job] (get artifacts job))}
                 (fn [] (candidates/candidate-case "reviewer" % {:job-id "left" :ordinal 0}
                                                   {:job-id "right" :ordinal 0})))
        case (build rows)
        config {:id "jev" :provider :jev :model "jev-1.13.0" :endpoint "https://example.com"
                :identity-protocol :freediving-compact-v3 :max-attempts 1}]
    (is (= case (build rows)))
    (is (= "Anastasiia Petrova" (get-in case [:input :left :sources 0 :exact-lines 0])))
    (is (= "POL" (get-in case [:input :left :fields :representation :value])))
    (is (= "right-candidate" (get-in case [:input :right :sources 0 :observation-id])))
    (is (= 2 (count (:question-ids (first (providers/prepare-batches config [case]))))))
    (is (not= (:case-id case)
              (:case-id (build (assoc-in rows [1 :payload :parsed :source-name] "Anastasia Pétrova")))))
    (is (not= (:case-id case)
              (:case-id (build (assoc-in rows [1 :parser-version] "different-parser/2")))))
    (is (not= (:case-id case)
              (:case-id (build (assoc-in rows [1 :payload :uncertainties] [:ambiguous-layout])))))
    (is (thrown? Exception
                 (with-redefs-fn {#'freediving.jev-candidates/artifact (fn [_ _] "tampered")}
                   (fn [] (candidates/candidate-case "reviewer" rows {:job-id "left" :ordinal 0}
                                                     {:job-id "right" :ordinal 0})))))))

(deftest candidate-run-is-durable-and-replay-does-not-redispatch
  (let [{:keys [rows artifacts]} (example)
        calls (atom 0)
        applied (atom [])
        answer (fn [choice probabilities]
                 {:type "choice" :choice choice :confidence 0.98 :probabilities probabilities})]
    (http/with-server
      (fn [exchange]
        (swap! calls inc)
        (http/reply! exchange 200
                     (json/write-str
                      {:model "jev-1.13.0" :usage {:input_tokens 27}
                       :answers {:identity_0 (answer "match" {:match 0.96 :no_match 0.02 :abstain 0.02})
                                 :spelling_0 (answer "left" {:left 0.96 :right 0.01
                                                             :equally_plausible 0.01 :unknown 0.01
                                                             :not_applicable 0.01})}})))
      (fn [url]
        (let [root (runner/root)
              config {:id "jev" :provider :jev :model "jev-1.13.0" :endpoint url
                      :identity-protocol :freediving-compact-v3 :max-attempts 1}
              runtime {:jev-dispatch-gate :credential-checks-passed-v1
                       :providers {"jev" {:bearer-token "fixture-secret"}}}
              score #(candidates/score-and-normalize! root "reviewer"
                                                      {:job-id "left" :ordinal 0}
                                                      {:job-id "right" :ordinal 0}
                                                      config runtime)
              overrides {#'freediving.jev-candidates/artifact (fn [_ job] (get artifacts job))
                         #'retrieval/load-corpus (fn [& _] rows)
                         #'spelling/apply-run! (fn [_ run-id provider _]
                                                 (swap! applied conj [run-id provider])
                                                 [{:status :applied}])}
              first-run (with-redefs-fn overrides score)
              second-run (with-redefs-fn overrides score)
              result (get-in (evaluation/inspect-run root (:run-id first-run))
                             [:report :providers "jev" :results 0])]
          (is (= first-run second-run))
          (is (= 1 @calls))
          (is (= :match (:outcome result)))
          (is (= :left (get-in result [:spelling :outcome])))
          (is (= 0.96 (get-in result [:spelling :probabilities :left])))
          (is (= 2 (count @applied))))))))

(deftest candidate-dispatch-needs-credential-gate
  (is (thrown? Exception
               (candidates/score-and-normalize! "root" "reviewer" {:job-id "left" :ordinal 0}
                                                {:job-id "right" :ordinal 0}
                                                {:provider :jev :identity-protocol :freediving-compact-v3}
                                                {}))))
