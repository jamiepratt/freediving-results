(ns freediving.jev-candidates-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [freediving.evaluation-protocol-test :as fixture]
            [freediving.evaluation-providers :as providers]
            [freediving.evaluation-providers-test :as http]
            [freediving.evaluation-test :as runner]
            [freediving.evaluation :as evaluation]
            [freediving.candidates :as retrieval]
            [freediving.spelling-normalization :as spelling]
            [freediving.jev-candidates :as candidates]))

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
