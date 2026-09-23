(ns freediving.evaluation-cli
  "Local shadow evaluation entry point. Standard output contains opaque receipts only."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [freediving.evaluation :as evaluation])
  (:import [java.io PushbackReader ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.net URI]))

(defn local-configs!
  "The CLI permits local providers and numeric loopback HTTP stand-ins only."
  [configs]
  (when-not (and (vector? configs) (seq configs))
    (throw (ex-info "Expected provider configurations" {})))
  (doseq [config configs]
    (when-not
     (or (#{:rules :stub} (:provider config))
         (and (#{:jev :llm} (:provider config))
              (try
                (let [uri (URI. (:endpoint config))]
                  (and (= "http" (.getScheme uri))
                       (= "127.0.0.1" (.getHost uri))
                       (nil? (.getUserInfo uri))
                       (nil? (.getRawQuery uri))
                       (nil? (.getRawFragment uri))))
                (catch Exception _ false))))
      (throw (ex-info "CLI providers must use local stand-ins" {}))))
  configs)

(defn read-input
  "Read exactly one EDN value, at most 4 MiB, without tagged data or evaluation."
  [file]
  (with-open [input (io/input-stream file)]
    (let [limit (* 4 1024 1024)
          bytes (.readNBytes input (inc limit))]
      (when (> (alength bytes) limit)
        (throw (ex-info "Evaluation input exceeds limit" {})))
      (with-open [reader (PushbackReader.
                          (io/reader (ByteArrayInputStream. bytes)
                                     :encoding (.name StandardCharsets/UTF_8)))]
        (let [eof (Object.)
              opts {:eof eof :readers {} :default (fn [_ _] (throw (ex-info "Unsupported tag" {})))}
              value (edn/read opts reader)]
          (when (or (identical? eof value)
                    (not (identical? eof (edn/read opts reader))))
            (throw (ex-info "Expected exactly one EDN value" {})))
          value)))))

(defn command
  "Run locally. Reviewed runs take their authoritative DB URL only from the environment."
  [args]
  (when-not (and (= 4 (count args)) (#{"run" "run-reviewed"} (first args)))
    (throw (ex-info "Usage: run PRIVATE-ROOT DATASET.edn CONFIGS.edn or run-reviewed PRIVATE-ROOT EXPORT-OPTIONS.edn CONFIGS.edn" {})))
  (let [[operation root input config-file] args
        configs (local-configs! (read-input config-file))
        runtime {:providers (into {} (map (fn [config] [(:id config) {:bearer-token "local-fixture-only"}]) configs))}]
    (if (= "run-reviewed" operation)
      (let [db-url (System/getenv "FREEDIVING_DATABASE_URL")]
        (when-not (seq db-url) (throw (ex-info "Authoritative database URL required" {})))
        (select-keys
         (evaluation/run-verified! root db-url
                                   ((requiring-resolve 'freediving.evaluation-labels/export) db-url (read-input input))
                                   configs runtime)
         [:status :reason :verified-id :export-hash :run-id :report-hash]))
      (select-keys (evaluation/run! root (read-input input) configs runtime)
                   [:run-id :input-hash :report-hash]))))

(defn -main [& args]
  (try
    (println (pr-str (command args)))
    (catch Exception _
      (binding [*out* *err*] (println "Shadow evaluation failed; check private inputs and storage."))
      (System/exit 1))))
