(ns jev-table-runner
  "Additive Italian table evidence preparation, hash-bound checking and one-shot execution."
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
  "Freeze all cases with additive table facts, original guidance and fixed groups
  of five. Labels are never consulted by extraction. Preserve baseline evidence."
  [prior]
  (let [dataset (:dataset prior)
        base (assoc (first (:configs prior)) :id "T5" :scope-id "table-81-v1-T5"
                    :identity-protocol :freediving-compact-v1 :native-batch-size 5
                    :identity-guidance :original-v1)
        config (assoc base :identity-extraction :italian-table-v1)
        baseline (providers/prepare-batches base (:cases dataset))
        requests (providers/prepare-batches config (:cases dataset))
        projections (vec (mapcat :projections requests))
        extractions (mapcat #(vals (get-in % [:mapping :table-extractions])) projections)]
    (require! (= 17 (count requests)) "Expected 81 cases in 17 requests")
    (doseq [[a b] (map vector baseline requests)]
      (let [old (json/read-str (:body a) :key-fn keyword) new (json/read-str (:body b) :key-fn keyword)]
        (require! (= (:state old) (:instruction protocol/question-local-descriptor) (:state new)) "Guidance changed")
        (require! (= (dissoc old :questions) (dissoc new :questions)) "Envelope changed")
        (require! (= (set (keys (:questions old))) (set (keys (:questions new)))) "Question order changed")
        (doseq [id (keys (:questions old))]
          (let [before (get-in old [:questions id]) after (get-in new [:questions id])]
            (require! (= (dissoc before :instructions) (dissoc after :instructions)) "Criteria changed")
            (require! (= (dissoc (:instructions before) :left :right) (dissoc (:instructions after) :left :right)) "Raw excerpts changed")
            (doseq [side [:left :right]]
              (let [x (get-in before [:instructions side]) y (get-in after [:instructions side])]
                (require! (= (dissoc x :fields) (dissoc y :fields)) "Uncertainty or source order changed")
                (require! (= (:fields x) (select-keys (:fields y) (keys (:fields x)))) "Original fields changed")
                (require! (every? #{:birth-year :societa :discipline} (remove (set (keys (:fields x))) (keys (:fields y)))) "Unexpected added field")))))))
    {:dataset dataset :configs [config] :requests [requests]
     :summary {:case-count 81 :question-count 81 :request-count 17 :batch-size 5
               :run-id (run-identity dataset [config] [requests])
               :extraction-statuses (frequencies (map :status extractions))
               :added-fields (frequencies (mapcat (comp keys :fields) extractions))
               :wire-total-bytes (reduce + (map (comp byte-count :body) requests))
               :wire-max-bytes (apply max (map (comp byte-count :body) requests))
               :qualification "Exposed-case regression; previous G5 comparison is not contemporaneous; no repeated controls"}}))
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
