(ns freediving.legacy
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [freediving.archive :as archive]))

(def version 1)
(def mapping {:sha256 "sha256" :discovery-url "requested_url" :final-url "resolved_url"
              :retrieved-at "fetched_at" :content-type "content_type"})
(def required #{:sha256 :discovery-url :final-url :retrieved-at :content-type
                :acquisition-method :publisher :relationship :mirror-of})

(defn normalize-entry
  "Normalize observed fields, preserving the original entry and explicit supplemental rationale."
  [entry supplement]
  (if (or (not (map? entry))
          (and (some? supplement)
               (or (not (map? supplement)) (not (map? (:values supplement))))))
    {:version version :original entry :supplement supplement :mapping mapping
     :status :rejected :reason :invalid-supplement-or-entry}
    (let [mapping (if (contains? entry "requested_url") mapping
                      (assoc mapping :discovery-url "source_url"))
          observed (into {} (for [[k field] mapping :when (contains? entry field)] [k (get entry field)]))
          values (:values supplement)
          manifest (merge observed values)
          missing (->> required (filter #(or (not (contains? manifest %))
                                             (and (not= % :mirror-of)
                                                  (or (nil? (get manifest %))
                                                      (= "" (get manifest %)))))) sort vec)
          invalid? (or (not (map? entry))
                       (and supplement
                            (or (not= #{:values :rationale} (set (keys supplement)))
                                (not (map? values))
                                (not (string? (:rationale supplement)))
                                (str/blank? (:rationale supplement))
                                (some #(not (required %)) (keys values))
                                (some #(contains? observed %) (keys values)))))]
      (cond-> {:version version :original entry :mapping mapping :supplement supplement :manifest manifest
               :status (cond invalid? :rejected (seq missing) :missing-fields :else :eligible)}
        invalid? (assoc :reason :invalid-supplement-or-entry)
        (seq missing) (assoc :missing-fields missing)))))

(defn- canonical [v]
  (cond (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[k value]] [k (canonical value)])) v)
        (sequential? v) (mapv canonical v)
        :else v))
(defn- retain-value! [root value]
  (binding [*print-length* nil *print-level* nil]
    (archive/retain-evidence! root (.getBytes (pr-str (canonical value)) "UTF-8"))))
(defn- fail! [reason] (throw (ex-info "Legacy import rejected" {:reason reason})))
(defn- read-config [bytes]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. (String. bytes "UTF-8")))]
    (let [eof (Object.) config (edn/read {:eof eof} reader)]
      (when-not (and (identical? eof (edn/read {:eof eof} reader))
                     (= #{:version :entries} (set (keys config)))
                     (= version (:version config)) (map? (:entries config)))
        (fail! :invalid-config))
      config)))
(defn- source-path [directory entry]
  (let [name (get entry "file")]
    (when-not (and (string? name) (not (str/blank? name))) (fail! :missing-source-path))
    (let [relative (.toPath (io/file name))]
      (when (or (.isAbsolute relative) (some #(= ".." (str %)) (iterator-seq (.iterator relative))))
        (fail! :unsafe-source-path))
      (str (io/file directory name)))))

(defn import!
  "Import a legacy JSON array and explicit EDN config. Return safe per-entry receipts;
   originals and complete deterministic transformation lineage remain private."
  [root directory manifest-file config-file]
  (let [manifest-bytes (archive/read-source-bytes manifest-file)
        config-bytes (archive/read-source-bytes config-file)
        manifest-ref (archive/retain-evidence! root manifest-bytes)
        config-ref (archive/retain-evidence! root config-bytes)
        entries (try (with-open [reader (java.io.PushbackReader. (java.io.StringReader. (String. manifest-bytes "UTF-8")) 64)]
                       (let [value (json/read reader)]
                         (loop [ch (.read reader)]
                           (when (not= -1 ch)
                             (when-not (#{32 9 10 13} ch) (fail! :invalid-json))
                             (recur (.read reader))))
                         value))
                     (catch Exception _ (fail! :invalid-json)))
        config (try (read-config config-bytes) (catch Exception _ (fail! :invalid-config)))]
    (when-not (vector? entries) (fail! :expected-entry-array))
    (when-not (every? #(and (integer? %) (<= 0 %) (< % (count entries))) (keys (:entries config)))
      (fail! :unknown-config-entry))
    (let [outcomes
          (mapv (fn [index entry]
                  (let [normalized (normalize-entry entry (get-in config [:entries index]))
                        lineage (retain-value! root {:manifest-sha256 (:sha256 manifest-ref)
                                                     :config-sha256 (:sha256 config-ref)
                                                     :entry-index index :transformation normalized})
                        receipt {:entry-index index :lineage-id (:sha256 lineage)
                                 :lineage-path (:path lineage)}]
                    (merge receipt
                           (if (= :eligible (:status normalized))
                             (try
                               (let [source (source-path directory entry)
                                     bytes (archive/read-source-bytes source)]
                                 (when (and (contains? entry "bytes")
                                            (not= (get entry "bytes") (alength bytes)))
                                   (fail! :source-size-mismatch))
                                 (archive/register! root source (:manifest normalized) {:report-status true}))
                               (catch Exception error
                                 {:status :rejected :reason (or (:reason (ex-data error)) :invalid-source-or-manifest)}))
                             (select-keys normalized [:status :reason :missing-fields])))))
                (range) entries)
          result {:version version :manifest-sha256 (:sha256 manifest-ref) :manifest-path (:path manifest-ref)
                  :config-sha256 (:sha256 config-ref) :config-path (:path config-ref)
                  :counts (merge {:imported 0 :skipped 0 :rejected 0 :missing-fields 0}
                                 (frequencies (map :status outcomes))) :entries outcomes}
          report (retain-value! root result)]
      (assoc result :report-path (:path report) :report-id (:sha256 report)))))

(defn -main [& args]
  (try
    (when-not (= 4 (count args)) (fail! :usage))
    (prn (apply import! args))
    (catch Exception error
      (binding [*out* *err*] (prn {:status :failed :reason (or (:reason (ex-data error)) :invalid-input-or-storage)}))
      (System/exit 1))))
