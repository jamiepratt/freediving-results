(ns jev-frozen-runner
  "Hash-bound /11 preparation check and one-shot execution entrypoint."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-providers :as providers]))

(def run-id "b437d63afdbfde706dbea25a7e0e3721dfdb9b02ef8adb71836fab36669608aa")
(defn canonical [x]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (walk/postwalk #(cond (map? %) (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b)))) %)
                                  (set? %) (into (sorted-set-by (fn [a b] (compare (pr-str a) (pr-str b)))) %)
                                  :else %) x))))
(defn sha [s]
  (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))))
(defn require! [condition message] (when-not condition (throw (ex-info message {}))))
(defn read-edn [root name] (edn/read-string (slurp (io/file root name))))
(defn save! [root name text]
  (with-open [ch (java.nio.channels.FileChannel/open (.toPath (io/file root name))
                                                     (java.util.HashSet. [java.nio.file.StandardOpenOption/CREATE_NEW java.nio.file.StandardOpenOption/WRITE])
                                                     (into-array java.nio.file.attribute.FileAttribute [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))]))]
    (let [buf (java.nio.ByteBuffer/wrap (.getBytes ^String text "UTF-8"))]
      (while (.hasRemaining buf) (.write ch buf)))
    (.force ch true)))
(defn check! [packet]
  (let [{:keys [dataset configs requests] :as frozen} (read-edn packet "candidate-frozen.edn")
        prepared (mapv #(providers/prepare-batches % (:cases dataset)) configs)
        rows (json/read-str (slurp (io/file packet "requests.json")) :key-fn keyword)
        identity {:schema-version 1 :harness-version "shadow-runner/6" :dataset dataset :requests prepared
                  :configurations (mapv #(select-keys % [:id :max-attempts :retry-delay-ms :scope-id :stop-on-terminal-error?]) configs)}]
    (require! (= ["S1" "B1"] (mapv :id configs)) "Arm order mismatch")
    (require! (= [81 41] (mapv count prepared)) "Request count mismatch")
    (require! (= 81 (count (:cases dataset))) "Case count mismatch")
    (require! (= requests prepared) "Frozen requests changed")
    (require! (= run-id (sha (canonical identity))) "Run identity mismatch")
    (require! (= 122 (count rows)) "Request manifest count mismatch")
    (doseq [[request row] (map vector (mapcat clojure.core/identity prepared) rows)]
      (require! (= (:new-request-sha256 row) (sha (canonical request))) "Prepared object hash mismatch")
      (require! (= (:body-sha256 row) (sha (:body request))) "Wire hash mismatch")
      (require! (= (:body-bytes row) (alength (.getBytes ^String (:body request) "UTF-8"))) "Wire size mismatch")
      (require! (= (:case-ids row) (:case-ids request)) "Case order mismatch"))
    frozen))
(defn execute! [mode packet root]
  (let [{:keys [dataset configs requests]} (check! packet)]
    (if (= mode "check")
      (prn {:status :checked :run-id run-id :prepared-objects 122 :wire-hashes 122})
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
                                   (require! (<= (swap! calls inc) 122) "Dispatch bound exceeded")
                                  ;; Terminal stopping may skip the rest of S1 before B1 begins.
                                   (let [tail (drop-while #(not= % request) @remaining)]
                                     (require! (seq tail) "Repeated or unfrozen request")
                                     (reset! remaining (vec (rest tail))))
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
    (require! (= 3 (count args)) "Expected mode packet root")
    (let [[mode packet root] args]
      (require! (#{"check" "live" "replay"} mode) "Invalid mode")
      (execute! mode packet root))
    (shutdown-agents)
    (catch Throwable _
      (binding [*out* *err*] (prn {:status :failed :resend-forbidden true}))
      (System/exit 1))))
