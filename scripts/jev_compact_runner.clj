(ns jev-compact-runner
  "Deterministic compact packet preparation, hash-bound checking and one-shot execution."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-protocol :as protocol]
            [freediving.evaluation-providers :as providers]
            [jev-frozen-runner :as original :refer [canonical sha require! read-edn save!]]))

(defn byte-count [s] (alength (.getBytes ^String s "UTF-8")))
(defn run-identity [dataset configs requests]
  (sha (canonical {:schema-version 1 :harness-version "shadow-runner/6" :dataset dataset :requests requests
                   :configurations (mapv #(select-keys % [:id :max-attempts :retry-delay-ms :scope-id :stop-on-terminal-error?]) configs)})))
(defn derive-packet
  "Pure preparation. Find largest supported fixed group size using actual wire
  bodies and unchanged provider limits. The original complete dataset is retained."
  [prior]
  (let [dataset (:dataset prior)
        attempts (mapv (fn [size]
                         (let [config (assoc (first (:configs prior)) :id (str "C" size)
                                             :scope-id (str "compact-81-v1-C" size)
                                             :identity-protocol :freediving-compact-v1 :native-batch-size size)]
                           (try {:size size :config config :requests (providers/prepare-batches config (:cases dataset))}
                                (catch clojure.lang.ExceptionInfo e
                                  (if (= :invalid-config (:error (ex-data e))) {:size size :rejected :provider-limits} (throw e))))))
                       (range 8 0 -1))
        accepted (first (filter :requests attempts))
        _ (require! accepted "No supported compact batch size")
        config (:config accepted) requests (:requests accepted)
        projections (mapv (fn [c] (assoc (protocol/compact-projection (:input c)) :case-id (:case-id c))) (:cases dataset))
        old-bytes (mapv #(byte-count (json/write-str (:input %))) (:cases dataset))
        new-bytes (mapv #(byte-count (json/write-str (:input %))) projections)
        source-instances (reduce + (mapcat (fn [c] (map #(count (get-in c [:input % :sources])) [:left :right])) (:cases dataset)))
        retained-excerpts (reduce + (map #(count (get-in % [:input :sources])) projections))]
    {:dataset dataset :configs [config] :requests [requests]
     :projections projections
     :summary {:projection-version (:projection-version protocol/compact-descriptor)
               :case-count (count (:cases dataset)) :batch-size (:size accepted) :request-count (count requests)
               :run-id (run-identity dataset [config] [requests])
               :limits {:pairs 8 :questions 32 :body-bytes 49152 :state-plus-largest-question-bytes 24576}
               :capacity-trials (mapv #(if (:requests %) {:size (:size %) :request-count (count (:requests %))
                                                          :max-body-bytes (apply max (map (comp byte-count :body) (:requests %)))}
                                           (select-keys % [:size :rejected])) attempts)
               :original-evidence-bytes {:total (reduce + old-bytes) :max (apply max old-bytes)}
               :compact-evidence-bytes {:total (reduce + new-bytes) :max (apply max new-bytes)}
               :wire-bytes {:total (reduce + (map (comp byte-count :body) requests)) :max (apply max (map (comp byte-count :body) requests))}
               :content {:source-instances source-instances :retained-distinct-excerpts retained-excerpts
                         :deduplicated-excerpt-instances (- source-instances retained-excerpts)
                         :retained "All non-nil facts, publisher uniqueness contracts, uncertainties and exact raw lines/spacing; record-local excerpt order and dependence"
                         :removed "Audit hashes/IDs/locators, nil fields, empty uncertainty/identity fields and exact duplicate excerpts within dependence groups"
                         :raw-line-policy "No heuristic header, boilerplate or raw-line deletion"}
               :qualification "Regression on exposed cases, not fresh accuracy validation; changed grouping confounds compaction effects"}}))
(defn request-rows [requests]
  (mapv (fn [r] {:new-request-sha256 (sha (canonical r)) :body-sha256 (sha (:body r))
                 :body-bytes (byte-count (:body r)) :case-ids (:case-ids r)}) (mapcat identity requests)))
(defn prepare! [packet original-packet]
  (require! (.isDirectory (io/file packet)) "Packet directory must exist")
  (let [frozen (derive-packet (original/check! original-packet))]
    (save! packet "candidate-frozen.edn" (canonical frozen))
    (save! packet "requests.json" (json/write-str (request-rows (:requests frozen))))
    (save! packet "summary.json" (json/write-str (:summary frozen)))
    (prn (:summary frozen))))
(defn check! [packet original-packet run-id]
  (let [expected (derive-packet (original/check! original-packet))
        frozen (read-edn packet "candidate-frozen.edn")
        rows (json/read-str (slurp (io/file packet "requests.json")) :key-fn keyword)]
    (require! (= 81 (count (get-in frozen [:dataset :cases]))) "Case count mismatch")
    (require! (= expected frozen) "Frozen compact projection, configuration, requests or mapping changed")
    (require! (= run-id (get-in frozen [:summary :run-id])) "Run identity mismatch")
    (require! (= rows (request-rows (:requests frozen))) "Wire manifest changed")
    frozen))
(defn execute! [mode packet root original-packet run-id]
  (let [{:keys [dataset configs requests summary]} (check! packet original-packet run-id)]
    (if (= mode "check")
      (prn {:status :checked :run-id run-id :prepared-objects (:request-count summary) :wire-hashes (:request-count summary) :original-cases 81})
      (let [live? (= mode "live")
            _ (when live?
                (require! (.isFile (io/file root "dispatcher-started.json")) "Missing durable launch gate")
                (require! (not (.exists (io/file root "store"))) "Existing store; live relaunch forbidden"))
            _ (when-not live?
                (require! (.isDirectory (io/file root "store")) "Missing completed replay store")
                (require! (= (read-edn root "report-live.edn")
                             (evaluation/inspect-run (str (io/file root "store")) run-id))
                          "Replay evidence incomplete or changed"))
            token (when live? (read-line))
            _ (when live? (require! (and (string? token) (seq token)) "Missing credential"))
            calls (atom 0)
            remaining (atom (vec (mapcat identity requests)))
            dispatch providers/execute!
            result (with-redefs [providers/execute!
                                 (fn [request runtime]
                                   (require! live? "Offline replay attempted dispatch")
                                   (require! (<= (swap! calls inc) (:request-count summary)) "Dispatch bound exceeded")
                                   (require! (= request (first @remaining)) "Repeated, skipped or unfrozen request")
                                   (swap! remaining subvec 1)
                                   (dispatch request runtime))]
                     (evaluation/run! (str (io/file root "store")) dataset configs
                                      {:providers (into {} (map (fn [c] [(:id c) {:bearer-token token}]) configs))}))
            report (evaluation/inspect-run (str (io/file root "store")) (:run-id result))]
        (require! (= run-id (:run-id result)) "Executed identity mismatch")
        (if live?
          (do (save! root "result-live.edn" (pr-str result))
              (save! root "report-live.edn" (pr-str report))
              (save! root "calls-live.edn" (pr-str {:calls @calls :mode mode}))
              (save! root "analysis-input.json"
                     (json/write-str {:providers (get-in report [:report :providers])
                                      :labels (into {} (map (fn [c] [(:case-id c) (get-in c [:label :outcome])]) (:cases dataset)))})))
          (do (require! (zero? @calls) "Replay dispatched")
              (require! (= result (read-edn root "result-live.edn")) "Replay result differs")
              (require! (= report (read-edn root "report-live.edn")) "Replay report differs")))
        (prn {:calls @calls :run-id (:run-id result) :mode mode})))))
(defn -main [& args]
  (try
    (if (= "prepare" (first args))
      (do (require! (= 3 (count args)) "Expected prepare packet original-packet")
          (prepare! (second args) (nth args 2)))
      (do (require! (= 5 (count args)) "Expected mode packet root original-packet run-id")
          (let [[mode packet root original-packet run-id] args]
            (require! (#{"check" "live" "replay"} mode) "Invalid mode")
            (execute! mode packet root original-packet run-id))))
    (shutdown-agents)
    (catch Throwable _
      (binding [*out* *err*] (prn {:status :failed :resend-forbidden true}))
      (System/exit 1))))
