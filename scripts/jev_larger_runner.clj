(ns jev-larger-runner
  "Hash-bound /12 preparation check and one-shot execution entrypoint."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-providers :as providers]
            [jev-frozen-runner :as original :refer [canonical sha require! read-edn save!]]))

(defn check! [packet original-packet run-id]
  (let [prior (original/check! original-packet)
        {:keys [dataset configs requests] :as frozen} (read-edn packet "candidate-frozen.edn")
        prepared (mapv #(providers/prepare-batches % (:cases dataset)) configs)
        rows (json/read-str (slurp (io/file packet "requests.json")) :key-fn keyword)
        identity {:schema-version 1 :harness-version "shadow-runner/6" :dataset dataset :requests prepared
                  :configurations (mapv #(select-keys % [:id :max-attempts :retry-delay-ms :scope-id :stop-on-terminal-error?]) configs)}]
    (require! (= (:dataset prior) dataset) "Original dataset changed")
    (require! (= [(assoc (first (:configs prior)) :id "L5" :scope-id "heldout-81-b8-20260926-L5" :native-batch-size 5)] configs) "Frozen L5 config differs")
    (require! (= [17] (mapv count prepared)) "Request count mismatch")
    (require! (= (conj (vec (repeat 16 5)) 1) (mapv #(count (:case-ids %)) (first prepared))) "Batch sizes differ")
    (let [decode #(json/read-str (:body %))
          singles (mapv decode (first (:requests prior)))
          groups (mapv decode (first prepared))]
      (require! (= (mapv #(get-in % ["questions" "identity_0"]) singles)
                   (vec (mapcat #(vals (into (sorted-map) (get % "questions"))) groups)))
                "Original question content/order changed")
      (require! (every? #(= (select-keys (first singles) ["model" "state"]) (select-keys % ["model" "state"])) groups)
                "Original model/state changed"))
    (require! (= 81 (count (:cases dataset))) "Case count mismatch")
    (require! (= requests prepared) "Frozen requests changed")
    (require! (= run-id (sha (canonical identity))) "Run identity mismatch")
    (require! (= 17 (count rows)) "Request manifest count mismatch")
    (doseq [[request row] (map vector (mapcat clojure.core/identity prepared) rows)]
      (require! (= (:new-request-sha256 row) (sha (canonical request))) "Prepared object hash mismatch")
      (require! (= (:body-sha256 row) (sha (:body request))) "Wire hash mismatch")
      (require! (= (:body-bytes row) (alength (.getBytes ^String (:body request) "UTF-8"))) "Wire size mismatch")
      (require! (= (:case-ids row) (:case-ids request)) "Case order mismatch"))
    frozen))
(defn execute! [mode packet root original-packet run-id]
  (let [{:keys [dataset configs requests]} (check! packet original-packet run-id)]
    (if (= mode "check")
      (prn {:status :checked :run-id run-id :prepared-objects 17 :wire-hashes 17 :original-questions 81})
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
                                   (require! (<= (swap! calls inc) 17) "Dispatch bound exceeded")
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
    (require! (= 5 (count args)) "Expected mode packet root original-packet run-id")
    (let [[mode packet root original-packet run-id] args]
      (require! (#{"check" "live" "replay"} mode) "Invalid mode")
      (execute! mode packet root original-packet run-id))
    (shutdown-agents)
    (catch Throwable _
      (binding [*out* *err*] (prn {:status :failed :resend-forbidden true}))
      (System/exit 1))))
