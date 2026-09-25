(ns freediving.pipeline
  "Private same-host services for the bounded local acquisition/import graph."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.aida :as aida]
            [freediving.athens :as athens]
            [freediving.depth-2025 :as depth-2025]
            [freediving.depth-2026 :as depth-2026]
            [freediving.indoor-2026 :as indoor-2026]
            [freediving.depth :as depth]
            [freediving.novi-sad :as novi-sad]
            [freediving.observations :as observations]
            [freediving.candidates :as candidates])
  (:import [java.nio.file Files LinkOption]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def pipeline-version "local-pipeline/1")
(def ^:private max-record-bytes (* 1024 1024))
(defn- hash? [x] (and (string? x) (boolean (re-matches #"[0-9a-f]{64}" x))))
(defn- fail! [reason] (throw (ex-info "Private pipeline operation unavailable" {:pipeline-reason reason})))
(defn- canonical [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) x))
        (sequential? x) (mapv canonical x) :else x))
(defn- encoded [x] (binding [*print-length* nil *print-level* nil] (pr-str (canonical x))))
(defn- sha [bytes] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- digest [x] (sha (.getBytes (encoded x) "UTF-8")))
(defn- private-file! [file]
  (let [p (.toPath (io/file file)) nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])]
    (loop [p (.toAbsolutePath p)]
      (when p
        (when (Files/isSymbolicLink p) (fail! :invalid-private-reference))
        (recur (.getParent p))))
    (when-not (and (Files/isRegularFile p nofollow)
                   (<= (Files/size p) max-record-bytes)
                   (not-any? #(re-find #"GROUP|OTHERS" (str %)) (Files/getPosixFilePermissions p nofollow)))
      (fail! :invalid-private-reference))
    (archive/read-source-bytes file)))
(defn- read-one [bytes]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. (String. bytes "UTF-8")))]
    (let [eof (Object.) value (edn/read {:eof eof} reader)]
      (when (or (identical? value eof) (not (identical? eof (edn/read {:eof eof} reader))))
        (fail! :invalid-private-reference)) value)))
(defn- resolve-record! [root id]
  (when-not (hash? id) (fail! :invalid-job-reference))
  (doseq [dir [root (io/file root "derivations") (io/file root "derived-objects")]]
    (let [p (.toPath (io/file dir))]
      (when (or (Files/isSymbolicLink p)
                (some #(re-find #"GROUP|OTHERS" (str %))
                      (Files/getPosixFilePermissions p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))
        (fail! :invalid-private-reference))))
  (let [receipt (read-one (private-file! (io/file root "derivations" (str id ".edn"))))
        h (:artifact-sha256 receipt)]
    (when-not (and (= id (:job-id receipt)) (hash? h)) (fail! :invalid-private-reference))
    (let [bytes (private-file! (io/file root "derived-objects" h)) value (read-one bytes)]
      (when-not (and (= h (sha bytes)) (= id (:job-id value))) (fail! :invalid-private-reference))
      value)))
(defn- versions []
  {:pipeline pipeline-version :parsers [extraction/parser-version aida/parser-version athens/parser-version depth/parser-version depth-2025/parser-version depth-2025/geometry-parser-version depth-2026/parser-version indoor-2026/parser-version novi-sad/parser-version]
   :candidates {:version candidates/packet-version :config candidates/default-config}
   :tools (mapv (fn [tool]
                  (let [r (shell/sh tool "-v")]
                    (when-not (zero? (:exit r)) (fail! :pdf-tool-unavailable))
                    [tool (str/trim (:err r))])) ["pdftotext" "pdfinfo"])})
(defn register-job!
  "Trusted local registration only. Persist bounded private configuration; expose its
   opaque hash. Re-register after parser/tool/config changes. No provider acquisition."
  [registry-root job]
  (when-not (and (map? job) (= #{:source :manifest :options} (set (keys job)))
                 (string? (:source job)) (.isAbsolute (io/file (:source job)))
                 (or (nil? (:manifest job)) (map? (:manifest job)))
                 (= #{:actor :config} (set (keys (:options job))))
                 (string? (get-in job [:options :actor])) (not (str/blank? (get-in job [:options :actor])))
                 (map? (get-in job [:options :config])))
    (fail! :invalid-job))
  (let [identity {:kind :registered-pipeline-job :job job :versions (versions)}
        id (digest identity) record (assoc identity :job-id id)]
    (when (> (count (.getBytes (encoded record) "UTF-8")) max-record-bytes) (fail! :job-too-large))
    (archive/derive! registry-root id (constantly record) nil)
    (when-not (= record (resolve-record! registry-root id)) (fail! :invalid-private-reference))
    {:job-id id}))

(defn- registered! [root id]
  (let [record (resolve-record! root id)]
    (when-not (and (= :registered-pipeline-job (:kind record))
                   (= id (digest (dissoc record :job-id)))) (fail! :invalid-private-reference))
    (when-not (= (:versions record) (versions)) (fail! :stale-job-version))
    (:job record)))
(defn- source! [{:keys [source manifest]}]
  (when-not manifest (fail! :missing-provenance))
  (when-not (.exists (io/file source)) (fail! :source-unavailable))
  (let [bytes (try (archive/read-source-bytes source)
                   (catch Exception _ (fail! :invalid-source-reference)))]
    (when-not (= (:sha256 manifest) (sha bytes)) (fail! :source-hash-mismatch))))
(defn- execution-id [config job-id snapshot]
  (digest {:job-id job-id :pipeline pipeline-version
           :worker (select-keys config [:archive-root :database-url])
           :acquisitions (:acquisitions snapshot)
           :evidence (archive/extraction-evidence (:archive-root config))}))
(defn- receipt-key [execution stage] (digest [:pipeline-stage execution stage]))
(defn- prior! [root execution stage]
  (let [id (receipt-key execution stage)
        record (try (resolve-record! root id)
                    (catch Exception _ (fail! :missing-or-corrupt-prerequisite)))]
    (when-not (and (= execution (:execution-id record)) (= stage (:stage record))
                   (= :ready (:status record))) (fail! :prerequisite-not-ready))
    record))
(defn- persist! [root execution stage result]
  (let [id (receipt-key execution stage)
        expected (merge result {:job-id id :execution-id execution :stage stage})]
    (archive/derive! root id (constantly expected) nil)
    (when-not (= expected (resolve-record! root id)) (fail! :stale-stage-receipt))
    (assoc (dissoc expected :job-id) :receipt-id id)))
(defn- extracted! [config job]
  (extraction/extract! (:archive-root config) (get-in job [:manifest :sha256]) (:options job)))
(defn- extraction-result [receipt]
  (let [artifact (read-one (archive/read-source-bytes (:artifact-path receipt)))
        status (:status artifact)
        unparsed (get-in artifact [:reconciliation :unparsed-count])]
    (merge {:extraction-id (:job-id receipt) :artifact-sha256 (:artifact-sha256 receipt)}
           (cond (#{:unsupported-needs-parser :partial-unsupported-needs-parser} status) {:status :unsupported :reason status}
                 (= :needs-OCR status) {:status :unresolved :reason :needs-ocr}
                 (pos? (or unparsed 0)) {:status :unresolved :reason :parser-unresolved}
                 :else {:status :ready}))))

(defn- current-extraction! [config job previous]
  ;; Downstream verification must not recreate deleted extraction references.
  (let [id (:extraction-id previous)
        receipt (read-one (private-file! (io/file (:archive-root config) "derivations" (str id ".edn"))))
        artifact-path (str (io/file (:archive-root config) "derived-objects" (:artifact-sha256 previous)))
        bytes (archive/read-source-bytes artifact-path)]
    (when-not (and (= id (:job-id receipt))
                   (= (:artifact-sha256 previous) (:artifact-sha256 receipt) (sha bytes)))
      (fail! :invalid-extraction-reference))
    (let [current (extracted! config job)
          expected (extraction-result current)]
      (when-not (= expected (select-keys previous (keys expected)))
        (fail! :stale-extraction-receipt))
      current)))
(defn- record-exists? [root id]
  (Files/exists (.toPath (io/file root "derivations" (str id ".edn")))
                (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
(defn- register-archive! [config job job-id]
  (let [root (:registry-root config)
        id (digest [:archive-completion job-id (:archive-root config)])
        expected {:job-id id :kind :archive-completion :source-sha256 (get-in job [:manifest :sha256])}]
    (if (record-exists? root id)
      (do
        (when-not (= expected (resolve-record! root id)) (fail! :invalid-archive-reference))
        (let [snapshot (archive/inspect (:archive-root config) (:source-sha256 expected))]
          (when-not (some #(= (:manifest job) (:manifest %)) (:acquisitions snapshot))
            (fail! :missing-provenance))))
      (do
        (archive/register! (:archive-root config) (:source job) (:manifest job))
        (archive/derive! root id (constantly expected) nil)))))

(defn stage!
  "Validate current private inputs on every invocation. Durable stage receipts never
   substitute for archive integrity or transactional ingestion verification. Config
   stays inside the worker: registry/archive roots, database URL, optional trusted
   :on-progress callback. Only fixed stages and opaque job hashes are graph inputs."
  ([config stage job-id] (stage! config stage job-id nil))
  ([{:keys [registry-root archive-root database-url on-progress] :as config} stage job-id expected-execution-id]
   (let [phase (atom :validation)]
     (try
       (when-not (hash? job-id) (fail! :invalid-job-reference))
       (when-not (#{:archive :extraction :ingestion :readiness} stage) (fail! :invalid-stage))
       (let [job (registered! registry-root job-id)]
         (source! job)
         (when (= :archive stage)
           (reset! phase :provenance)
           (register-archive! config job job-id))
         (reset! phase :archive-integrity)
         (let [snapshot (archive/inspect archive-root (get-in job [:manifest :sha256]))
               _ (when-not (some #(= (:manifest job) (:manifest %)) (:acquisitions snapshot))
                   (fail! :missing-provenance))
               execution (execution-id config job-id snapshot)
               _ (when (and expected-execution-id (not= expected-execution-id execution))
                   (fail! :stale-execution))
               result
               (case stage
                 :archive {:status :ready :source-sha256 (:sha256 snapshot)}
                 :extraction
                 (do (prior! registry-root execution :archive)
                     (reset! phase :extraction)
                     (let [id (receipt-key execution :extraction)]
                       (extraction-result
                        (if (record-exists? registry-root id)
                          (current-extraction! config job (resolve-record! registry-root id))
                          (extracted! config job)))))
                 (:ingestion :readiness)
                 (let [previous (prior! registry-root execution :extraction)]
                   (prior! registry-root execution :archive)
                   (when (= :readiness stage) (prior! registry-root execution :ingestion))
                   (reset! phase :extraction)
                   (let [receipt (current-extraction! config job previous)]
                     (reset! phase :ingestion)
                     (when (record-exists? registry-root (receipt-key execution :ingestion))
                       (prior! registry-root execution :ingestion)
                       (when-not (observations/inspect database-url (:job-id receipt))
                         (fail! :missing-ingestion-reference)))
                    ;; Verify existing committed rows too: interrupted receipt publication
                    ;; must recover through the importer's own transaction/idempotency.
                     (observations/import! database-url archive-root (:job-id receipt))
                     (when on-progress
                       (reset! phase :worker)
                       (on-progress {:phase :ingestion-committed :job-id job-id :execution-id execution}))
                     (if (= :ingestion stage)
                       (merge {:status :ready} (select-keys previous [:extraction-id :artifact-sha256]))
                       (do
                         (reset! phase :readiness)
                         (let [packets (candidates/packets (candidates/load-corpus database-url candidates/default-config)
                                                           (assoc candidates/default-config :limit 1))]
                           {:status :ready :extraction-id (:job-id receipt)
                            :corpus-id (:corpus-id packets) :review-status :unreviewed
                            :publication-status :blocked :candidate-count (:total packets)}))))))]
           (reset! phase :receipt)
           (assoc (persist! registry-root execution stage result) :job-id job-id)))
       (catch Exception e
         (let [reason (or (:pipeline-reason (ex-data e))
                          (case @phase
                            :provenance :invalid-provenance
                            :archive-integrity :invalid-archive-reference
                            :extraction :extraction-failed
                            :ingestion (if (instance? java.sql.SQLException e) :database-error :ingestion-integrity-error)
                            :readiness :readiness-unavailable
                            :worker :worker-failure
                            :receipt :invalid-stage-receipt
                            :invalid-private-reference))]
           (cond-> {:stage (if (#{:archive :extraction :ingestion :readiness} stage) stage :invalid)
                    :status (if (#{:database-error :extraction-failed :worker-failure :readiness-unavailable} reason) :failed :blocked)
                    :reason reason}
             (hash? job-id) (assoc :job-id job-id))))))))
