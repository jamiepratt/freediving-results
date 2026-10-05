(ns freediving.reconciliation-flow-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-budget :as budget]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-transport :as transport])
  (:import [com.sun.net.httpserver HttpServer]
           [java.net InetSocketAddress]))

(def policy {:version "test/1"
             :thresholds {:identity {:same-person {:min-confidence 0.9 :min-probability 0.9 :min-margin 0.2}
                                     :different-person {:min-confidence 0.9 :min-probability 0.9 :min-margin 0.2}}}})
(def config {:provider :jev :model "jev-1.13.0" :max-request-bytes 49152
             :native-batch-size 8})
(defn decision [id]
  {:id id :family :identity :action :same-person :choices [:same-person :different-person :unknown]
   :evidence [{:evidence-id (str id "-source") :citation {:source-sha256 "sha-test" :locator "row 1"} :exact-excerpt "Named result row"}]
   :candidates ["athlete:a" "athlete:b"] :dependencies [] :evidence-adequate? true})
(def answer {:outcome :same-person :confidence 0.96
             :probabilities {:same-person 0.94 :different-person 0.04 :unknown 0.02}
             :model-version "jev-1.13.0"})

(def budget-pricing {:version "test-rate/1" :model "jev-1.13.0"
                     :source "synthetic published rate" :input-usd-per-million 0.1M
                     :output-usd-per-million 0M :max-input-tokens 64000
                     :max-output-tokens 64000})
(defn successful-response [request]
  {:model "jev-1.13.0" :usage {:input_tokens 1 :output_tokens 1}
   :answers (zipmap (:decision-ids request)
                    (repeat {:type "choice" :choice "same_person" :confidence 0.96
                             :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})

(deftest provider-budget-survives-restart-and-changed-evidence
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-budget-test"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        flow-path (.resolve root "flow.edn")
        budget-path (.resolve root "budget.edn")
        calls (atom 0)
        opts {:config config :policy policy :provider-budget-path budget-path
              :provider-pricing budget-pricing
              :execute! (fn [request] (swap! calls inc) (successful-response request))}
        initial [(decision "a")]
        changed [(assoc-in (decision "a") [:evidence 0 :exact-excerpt] "Changed row")]]
    (budget/initialize! budget-path [])
    (flow/run-file! flow-path initial opts)
    (flow/run-file! flow-path initial opts)
    (flow/run-file! flow-path changed opts)
    (is (= 2 @calls))
    (is (= 2 (count (:reservations (budget/load-ledger! budget-path)))))
    (is (every? :reported-usage (:reservations (budget/load-ledger! budget-path))))))

(deftest provider-budget-stops-before-exact-ten-dollar-boundary
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-budget-limit"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        budget-path (.resolve root "budget.edn")
        calls (atom 0)
        pricing (assoc budget-pricing :input-usd-per-million 78.125M)
        opts {:config config :policy policy :provider-budget-path budget-path
              :provider-pricing pricing
              :execute! (fn [request] (swap! calls inc) (successful-response request))}]
    (budget/initialize! budget-path [])
    (flow/run-file! (.resolve root "one.edn") [(decision "a")] opts)
    (is (= :provider-budget-exhausted
           (try (flow/run-file! (.resolve root "two.edn") [(decision "b")] opts)
                nil
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (= 1 @calls))
    (is (= 5M (:reserved-usd (first (:reservations (budget/load-ledger! budget-path))))))))

(deftest provider-budget-keeps-uncertain-dispatch-and-rejects-unpriced-requests
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-budget-unknown"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        budget-path (.resolve root "budget.edn")
        calls (atom 0)
        opts {:config config :policy policy :provider-budget-path budget-path
              :provider-pricing budget-pricing
              :execute! (fn [_] (swap! calls inc) (throw (Error. "process death")))}]
    (budget/initialize! budget-path [])
    (is (thrown? Error (flow/run-file! (.resolve root "unknown.edn")
                                       [(decision "a")] opts)))
    (is (= 1 (count (:reservations (budget/load-ledger! budget-path)))))
    (is (nil? (:reported-usage (first (:reservations (budget/load-ledger! budget-path))))))
    (is (= :unpriced-request
           (try (flow/run-file! (.resolve root "unpriced.edn") [(decision "b")]
                                (assoc opts :provider-pricing (dissoc budget-pricing :source)))
                nil (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (= 1 @calls))
    (is (= 1 (count (:reservations (budget/load-ledger! budget-path)))))))

(deftest provider-budget-serializes-concurrent-dispatches
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-budget-race"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        budget-path (.resolve root "budget.edn")
        calls (atom 0)
        opts {:config config :policy policy :provider-budget-path budget-path
              :provider-pricing (assoc budget-pricing :input-usd-per-million 93.75M)
              :execute! (fn [request] (swap! calls inc) (successful-response request))}
        run-one (fn [id]
                  (try (flow/run-file! (.resolve root (str id ".edn")) [(decision id)] opts)
                       :ok
                       (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))]
    (budget/initialize! budget-path [])
    (let [a (future (run-one "a"))
          b (future (run-one "b"))]
      (is (= #{:ok :provider-budget-exhausted} #{@a @b})))
    (is (= 1 @calls))
    (is (= 1 (count (:reservations (budget/load-ledger! budget-path)))))))

(deftest application-dispatch-requires-budget-baseline-and-records-raw-usage
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-application-budget"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        flow-path (.resolve root "flow.edn")
        budget-path (.resolve root "budget.edn")
        calls (atom 0)
        d (decision "a")
        strict-policy (assoc-in policy [:thresholds :identity :same-person :min-confidence] 1.0)
        opts {:config (assoc config :endpoint "https://api.typesafe.ai/jev")
              :policy strict-policy :persist-flow! #(flow/save-ledger! flow-path %)
              :provider-budget-path budget-path :provider-pricing budget-pricing
              :execute! (fn [request]
                          (swap! calls inc)
                          {:raw-response (json/write-str (successful-response request))
                           :http-status 200})}]
    (is (= :missing-budget-baseline
           (try (application/run! (flow/empty-ledger) [d] opts)
                nil (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (zero? @calls))
    (budget/initialize! budget-path [])
    (application/run! (flow/empty-ledger) [d] opts)
    (is (= 1 @calls))
    (flow/run-file! flow-path [d]
                    (-> opts (dissoc :provider-budget-path :provider-pricing)
                        (dissoc :persist-flow!)))
    (is (= 1 @calls))
    (is (= {:input_tokens 1 :output_tokens 1}
           (:reported-usage (first (:reservations (budget/load-ledger! budget-path))))))))

(deftest historical-provider-attempt-is-carried-into-cumulative-budget
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-budget-history"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        budget-path (.resolve root "budget.edn")
        historical {:type :reservation :id "prior-1" :historical? true
                    :request-hash (apply str (repeat 64 "a"))
                    :source-sha256 (apply str (repeat 64 "b"))
                    :price-version "published-jev-2026-10-05"
                    :reserved-usd 6M :reported-usage {:input_tokens 1453 :output_tokens 57}}
        pricing (assoc budget-pricing :input-usd-per-million 62.5M)
        calls (atom 0)]
    (budget/initialize! budget-path [historical])
    (is (thrown? clojure.lang.ExceptionInfo
                 (budget/initialize! budget-path [])))
    (is (= :provider-budget-exhausted
           (try (flow/run-file! (.resolve root "flow.edn") [(decision "a")]
                                {:config config :policy policy
                                 :provider-budget-path budget-path :provider-pricing pricing
                                 :execute! (fn [_] (swap! calls inc) {})})
                nil (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (zero? @calls))
    (is (= historical (first (:reservations (budget/load-ledger! budget-path)))))))

(deftest source-derived-identity-awaits-verified-context-without-provider-call
  (let [sha (apply str (repeat 64 "a"))
        ids (mapv #(str "source-observation:" (apply str (repeat 64 %))) ["1" "2" "3"])
        refs (into {} (map (fn [id]
                             [id {:kind "source-derived" :snapshot_sha256 sha
                                  :snapshot_record_id (subs id (count "source-observation:"))
                                  :source_sha256 sha :packet_sha256 sha
                                  :source_name "synthetic"
                                  :observation_version sha :adapter_version "synthetic/1"
                                  :citation {:row id}}]) ids))
        source (assoc (decision "source")
                      :subject {:pair (subvec ids 0 2) :target-id (first ids)
                                :observation-versions refs}
                      :candidates ids
                      :evidence (mapv (fn [id]
                                        {:evidence-id id :citation (refs id)
                                         :fact "Same printed name"}) ids))
        calls (atom 0)
        opts {:config config :policy policy
              :execute! (fn [_] (swap! calls inc) (throw (ex-info "unexpected call" {})))
              :deterministic-results {"source" {:status :approve :rule-version "rule/forged"}}}
        first-run (flow/run! (flow/empty-ledger) [source] opts)
        rerun (flow/run! first-run [source] opts)
        view (get (flow/inspect rerun [source] config) "source")]
    (is (= 0 @calls))
    (is (= (:events first-run) (:events rerun)))
    (is (= :unresolved (:status view)))
    (is (= :source-context-unverified (:reason view)))
    (is (= "test/1" (:policy-version view)))
    (is (= "source-identity/1" (:rule-version (last (:events rerun)))))
    (is (= (set (vals refs))
           (set (map :citation (:evidence (last (:events rerun)))))))
    (let [corrected (flow/append-human-event rerun
                                             {:id "signed-human-rejection" :decision-id "source"
                                              :status :rejected :reason "different people"})]
      (is (= :rejected (get-in (flow/inspect (flow/run! corrected [source] opts)
                                             [source] config)
                               ["source" :status]))))))

(deftest retained-deterministic-and-jev-decisions-replay-without-http
  (let [calls (atom 0)
        execute! (fn [request] (swap! calls inc)
                   {:model "jev-1.13.0" :usage {} :answers (zipmap (:decision-ids request) (repeat {:type "choice" :choice "same_person" :confidence 0.96 :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        opts {:config config :policy policy :execute! execute!
              :deterministic-results {"rule" {:status :approve :rule-version "rule/1"}}}
        decisions [(decision "rule") (decision "model")]
        first-run (flow/run! (flow/empty-ledger) decisions opts)
        rerun (flow/run! first-run decisions opts)]
    (is (= 1 @calls))
    (is (= (:events first-run) (:events rerun)))
    (is (= :approved (get-in (flow/inspect rerun decisions) ["model" :status])))
    (is (= :deterministic (get-in (flow/inspect rerun decisions) ["rule" :origin])))))

(deftest changed-evidence-invalidates-cached-approval
  (let [calls (atom 0)
        execute! (fn [request] (swap! calls inc)
                   {:model "jev-1.13.0" :usage {} :answers (zipmap (:decision-ids request) (repeat {:type "choice" :choice "same_person" :confidence 0.96 :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        opts {:config config :policy policy :execute! execute!}
        initial [(decision "model")]
        changed [(assoc-in (decision "model") [:evidence 0 :exact-excerpt] "Changed row")]
        ledger (flow/run! (flow/empty-ledger) initial opts)
        rerun (flow/run! ledger changed opts)]
    (is (= 2 @calls))
    (is (= 4 (count (:events rerun))))
    (is (= :approved (get-in (flow/inspect rerun changed) ["model" :status])))))

(deftest changed-question-context-invalidates-cached-answer
  (doseq [[field before after]
          [[:subject {:event "cup-a"} {:event "cup-b"}]
           [:uncertainties ["source label unclear"] ["source label confirmed"]]
           [:contradictions [] ["different printed ID"]]]]
    (let [calls (atom 0)
          execute! (fn [request]
                     (swap! calls inc)
                     {:model "jev-1.13.0" :usage {}
                      :answers (zipmap (:decision-ids request)
                                       (repeat {:type "choice" :choice "same_person"
                                                :confidence 0.96
                                                :probabilities {"same_person" 0.94
                                                                "different_person" 0.04
                                                                "unknown" 0.02}}))})
          opts {:config config :policy policy :execute! execute!}
          original (assoc (decision "model") field before)
          changed (assoc original field after)
          first-run (flow/run! (flow/empty-ledger) [original] opts)
          rerun (flow/run! first-run [changed] opts)]
      (is (= 2 @calls) (name field))
      (is (= :approved (get-in (flow/inspect rerun [changed] config)
                               ["model" :status])) (name field)))))

(deftest dependency-and-human-override-control-dispatch
  (let [calls (atom [])
        execute! (fn [request]
                   (swap! calls conj (:decision-ids request))
                   {:model "jev-1.13.0" :usage {} :answers (zipmap (:decision-ids request) (repeat {:type "choice" :choice "same_person" :confidence 0.96 :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "first")
                   (assoc (decision "second") :dependencies ["first"])]
        blocked (flow/append-human-event (flow/empty-ledger)
                                         {:id "human-1" :decision-id "first"
                                          :status :rejected :reason "source conflict"})
        result (flow/run! blocked decisions {:config config :policy policy :execute! execute!})]
    (is (empty? @calls))
    (is (= :human (get-in (flow/inspect result decisions) ["first" :origin])))
    (is (= :dependency-blocked (get-in (flow/inspect result decisions) ["second" :status])))))

(deftest timeout-is-explicit-and-not-redispatched
  (let [calls (atom 0)
        execute! (fn [_] (swap! calls inc) (throw (ex-info "timeout" {:error :timeout})))
        opts {:config config :policy policy :execute! execute!}
        decisions [(decision "model")]
        first-run (flow/run! (flow/empty-ledger) decisions opts)
        rerun (flow/run! first-run decisions opts)]
    (is (= 1 @calls))
    (is (= :timeout (get-in (flow/inspect rerun decisions) ["model" :status])))))

(deftest independent-decisions-batch-and-dependent-questions-follow
  (let [requests (atom [])
        execute! (fn [request]
                   (swap! requests conj (:decision-ids request))
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "a") (decision "b")
                   (assoc (decision "c") :dependencies ["a"])]
        result (flow/run! (flow/empty-ledger) decisions
                          {:config config :policy policy :execute! execute!})]
    (is (= [["a" "b"] ["c"]] @requests))
    (is (every? #(= :approved (:status %))
                (vals (flow/inspect result decisions))))))

(deftest policy-reassessment-reuses-retained-answer
  (let [calls (atom 0)
        execute! (fn [request]
                   (swap! calls inc)
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "a")]
        initial (flow/run! (flow/empty-ledger) decisions
                           {:config config :policy policy :execute! execute!})
        stricter (assoc-in (assoc policy :version "test/2")
                           [:thresholds :identity :same-person :min-confidence] 0.99)
        reassessed (flow/run! initial decisions
                              {:config config :policy stricter :execute! execute!})]
    (is (= 1 @calls))
    (is (= :unresolved (get-in (flow/inspect reassessed decisions) ["a" :status])))
    (is (= "test/2" (get-in (flow/inspect reassessed decisions) ["a" :policy-version])))))

(deftest stale-retained-answer-cannot-bypass-dispatch
  (let [calls (atom 0)
        execute! (fn [request]
                   (swap! calls inc)
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "a")]
        stale (assoc answer :decision-key "old-evidence" :template-version "reconciliation-jev/1")
        result (flow/run! (flow/empty-ledger) decisions
                          {:config config :policy policy :execute! execute!
                           :retained-answers {"a" stale}})]
    (is (= 1 @calls))
    (is (= :jev (get-in (flow/inspect result decisions) ["a" :origin])))))

(deftest human-correction-unblocks-dependent-question-on-rerun
  (let [calls (atom 0)
        execute! (fn [request]
                   (swap! calls inc)
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "first") (assoc (decision "second") :dependencies ["first"])]
        blocked (flow/append-human-event (flow/empty-ledger)
                                         {:id "human-no" :decision-id "first" :status :rejected})
        first-run (flow/run! blocked decisions {:config config :policy policy :execute! execute!})
        corrected (flow/append-human-event first-run
                                           {:id "human-yes" :decision-id "first" :status :approved :action :same-person})
        rerun (flow/run! corrected decisions {:config config :policy policy :execute! execute!})]
    (is (= 1 @calls))
    (is (= :approved (get-in (flow/inspect rerun decisions) ["second" :status])))))

(deftest unresolved-deterministic-result-falls-through-to-jev
  (let [calls (atom 0)
        execute! (fn [request]
                   (swap! calls inc)
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "a")]
        result (flow/run! (flow/empty-ledger) decisions
                          {:config config :policy policy :execute! execute!
                           :deterministic-results {"a" {:status :unresolved :rule-version "rule/1"}}})]
    (is (= 1 @calls))
    (is (= :jev (get-in (flow/inspect result decisions) ["a" :origin])))))

(deftest jev-choice-determines-approved-action
  (let [execute! (fn [request]
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "different_person" :confidence 0.96
                                              :probabilities {"same_person" 0.03 "different_person" 0.95 "unknown" 0.02}}))})
        decisions [(decision "a")]
        result (flow/run! (flow/empty-ledger) decisions
                          {:config config :policy policy :execute! execute!})]
    (is (= :approved (get-in (flow/inspect result decisions) ["a" :status])))
    (is (= :different-person (:action (last (:events result)))))))

(deftest private-ledger-persists-and-projects-approved-decisions
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-ledger-test"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "ledger.edn")
        decisions [(decision "a")]
        execute! (fn [request]
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        result (flow/run! (flow/load-ledger! path) decisions
                          {:config config :policy policy :execute! execute!})]
    (flow/save-ledger! path result)
    (is (= result (flow/load-ledger! path)))
    (is (= [{:decision-id "a" :family :identity :action :same-person :origin :jev}]
           (mapv #(select-keys % [:decision-id :family :action :origin])
                 (:approved-decisions (flow/project-private (flow/load-ledger! path)
                                                            decisions config)))))))

(deftest private-ledger-rejects-history-truncation
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-ledger-test"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "ledger.edn")
        ledger (flow/append-human-event (flow/empty-ledger)
                                        {:id "human-1" :decision-id "a" :status :rejected})]
    (flow/save-ledger! path ledger)
    (is (thrown? clojure.lang.ExceptionInfo
                 (flow/save-ledger! path (flow/empty-ledger))))))

(deftest interrupted-dispatch-is-checkpointed-before-http
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-interrupt-test"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "ledger.edn")
        decisions [(decision "a")]
        calls (atom 0)
        first-execute! (fn [_] (swap! calls inc) (throw (Error. "simulated process death")))
        second-execute! (fn [_] (swap! calls inc) (throw (Error. "must not resend")))]
    (is (thrown? Error
                 (flow/run-file! path decisions
                                 {:config config :policy policy :execute! first-execute!})))
    (is (= :unknown-external-outcome
           (get-in (flow/inspect (flow/load-ledger! path) decisions) ["a" :status])))
    (flow/run-file! path decisions {:config config :policy policy :execute! second-execute!})
    (is (= 1 @calls))))

(deftest file-backed-loopback-batches-replays-and-invalidates-evidence
  (let [root (java.nio.file.Files/createTempDirectory "reconciliation-loopback-test"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "ledger.edn")
        requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        decisions [(decision "a") (decision "b")]]
    (.createContext server "/jev"
                    (reify com.sun.net.httpserver.HttpHandler
                      (handle [_ exchange]
                        (let [body (slurp (.getRequestBody exchange))
                              questions (get (json/read-str body) "questions")
                              ids (vec (keys questions))
                              response (json/write-str
                                        {:model "jev-1.13.0" :usage {}
                                         :answers (into {}
                                                        (map (fn [id]
                                                               [id {:type "choice" :choice "same_person"
                                                                    :confidence 0.96
                                                                    :probabilities {"same_person" 0.94
                                                                                    "different_person" 0.04
                                                                                    "unknown" 0.02}}]) ids))})
                              bytes (.getBytes response "UTF-8")]
                          (swap! requests conj ids)
                          (.sendResponseHeaders exchange 200 (alength bytes))
                          (with-open [output (.getResponseBody exchange)]
                            (.write output bytes))))))
    (.start server)
    (try
      (let [url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/jev")
            config (assoc config :endpoint url :timeout-ms 2000)
            runtime {:bearer-token "synthetic-loopback-token"}
            opts {:config config :policy policy
                  :execute! #(transport/execute! % runtime)}
            changed (assoc-in (decision "a") [:evidence 0 :exact-excerpt] "Revised result row")]
        (flow/run-file! path decisions opts)
        (flow/run-file! path decisions opts)
        (flow/run-file! path [changed (decision "b")] opts)
        (is (= [["a" "b"] ["a"]] @requests))
        (is (= 2 (count (:approved-decisions
                         (flow/project-private (flow/load-ledger! path)
                                               [changed (decision "b")] config))))))
      (finally (.stop server 0)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-flow-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
