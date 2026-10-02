(ns freediving.parser-batch-import
  "Private, deterministic position replay into archive derivations. These records
   are isolated evidence, not PostgreSQL observations or publication approval."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [freediving.archive :as archive]
            [freediving.parser-adapters :as adapters]
            [freediving.parser-batch :as batch]
            [freediving.retained-pdf :as retained-pdf])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(defn- canonical [value]
  (cond
    (map? value) [:map (->> value
                            (map (fn [[k v]] [(canonical k) (canonical v)]))
                            (sort-by (comp pr-str first)) vec)]
    (set? value) [:set (->> value (map canonical) (sort-by pr-str) vec)]
    (sequential? value) [:sequence (mapv canonical value)]
    :else value))

(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical value)) "UTF-8"))))

(defn- source-input [{:keys [document retained-input]} root]
  (let [sha (:source-sha256 document)]
    (try
      (let [source (archive/inspect root sha)
            _ (when-not (seq (:acquisitions source))
                (throw (ex-info "Archived source has no verified acquisition" {})))
            bytes (archive/read-source-bytes (:artifact-path source))
            format (:format document)
            input (if (= :pdf format)
                    (retained-pdf/verified-input root document)
                    retained-input)
            input-bytes (case format
                          :html (some-> (:html input) (.getBytes "UTF-8"))
                          (:json :workbook) (:bytes input)
                          nil)]
        (if (and input-bytes (not= (seq bytes) (seq input-bytes)))
          {:claims [] :unsupported-reasons [:archived-input-mismatch]}
          (adapters/claims-for-document document input)))
      (catch Exception _
        {:claims [] :unsupported-reasons [:missing-or-invalid-archived-source]}))))

(defn- candidate-position [format candidate]
  (or (:id candidate)
      (let [{:keys [table row page line column-start column-end]} (:coordinates candidate)]
        (case format
          :html (str "table=" table "&row=" row)
          :pdf (str "page=" page "&line=" line "&column=" column-start "-" column-end)
          nil))))

(defn- candidate-citation [sha candidate position-id]
  (or (:citation candidate) (str "sha256:" sha "#" position-id)))

(defn- evidence-for [route extractions]
  (let [sha (:source-sha256 route)
        format (:format route)
        parser (get-in route [:claim :parser-id])
        version (get-in route [:claim :parser-version])
        matching (for [{:keys [claims extraction source-verification]} extractions
                       :when (and extraction
                                  (some #(and (= parser (:parser-id %))
                                              (= version (:parser-version %))) claims))
                       candidate (:candidates extraction)
                       :let [position-id (candidate-position format candidate)]
                       :when (and (= position-id (:position-id route))
                                  (= (:coordinates candidate) (:coordinates route))
                                  (= (:citation route)
                                     (candidate-citation sha candidate position-id)))]
                   {:candidate candidate :source-verification source-verification})
        distinct-matches (distinct matching)]
    (when (= 1 (count distinct-matches)) (first distinct-matches))))

(defn- derive-record! [root record on-progress]
  (let [job-id (digest (select-keys record [:kind :source-sha256 :parser-id
                                            :parser-version :position-id :section-id
                                            :status :reason :route-fingerprint
                                            :exception-fingerprint]))
        record (assoc record :job-id job-id)
        result (archive/derive! root job-id (constantly record) on-progress)
        stored (edn/read-string (slurp (:artifact-path result) :encoding "UTF-8"))]
    (when-not (= record stored)
      (throw (ex-info "Conflicting replay record" {:reason :conflicting-replay-record
                                                   :job-id job-id})))
    (:run-status result)))

(defn import-registered-batch!
  "Replay retained inputs into a private source archive. The source object must
   already be registered there. Each route/gap is an atomic, resumable derivation.
   Optional :only-positions is a set of [source-sha256 position-id] pairs for a
   corrected-position retry. :on-progress receives archive stage checkpoints."
  ([root entries] (import-registered-batch! root entries {}))
  ([root entries {:keys [only-positions on-progress]}]
   (let [prepared (mapv (fn [entry]
                          (let [adapted (source-input entry root)]
                            {:document (:document entry) :adapter adapted})) entries)
         replay (batch/replay-batch
                 (mapv (fn [{:keys [document adapter]}]
                         {:document document :claims (:claims adapter)
                          :unsupported-reasons (:unsupported-reasons adapter)
                          :extraction-status (get-in adapter [:extraction :status])}) prepared))
         extractions (group-by (comp :source-sha256 :document) prepared)
         imported (atom 0)
         imported-evidence (atom 0)
         gaps (atom 0)]
     (doseq [document (:documents replay)]
       (let [sha (:source-sha256 document)]
         (derive-record! root {:kind :batch-stage :source-sha256 sha :status :routing-complete
                               :route-fingerprint (digest (select-keys document
                                                                       [:routed :gaps :unsupported-reasons]))
                               :coverage {:routed (count (:routed document))
                                          :gaps (count (:gaps document))}}
                         on-progress)
         (doseq [route (:routed document)
                 :when (or (nil? only-positions)
                           (contains? only-positions [sha (:position-id route)]))]
           (if-let [{:keys [candidate source-verification]}
                    (evidence-for (assoc route :source-sha256 sha :format (:format document))
                                  (map :adapter (get extractions sha)))]
             (let [claim (:claim route)
                   role (or (get-in candidate [:coordinates :evidence-role])
                            (case (:source-family candidate)
                              :attempts :result-row
                              :ranking :event-ranking
                              :result-row))
                   observation? (#{:result-row :individual-result} role)
                   record {:kind (if observation? :batch-observation :batch-evidence)
                           :source-sha256 sha
                           :format (:format document) :position-id (:position-id route)
                           :citation (:citation route) :coordinates (:coordinates route)
                           :parser-id (:parser-id claim) :parser-version (:parser-version claim)
                           :source-verification source-verification
                           :evidence-role role
                           :candidate candidate :status :unreviewed}]
               (when (= :created (derive-record! root record on-progress))
                 (swap! imported-evidence inc)
                 (when observation?
                   (swap! imported inc))))
             (when (= :created
                      (derive-record! root {:kind :batch-exception :source-sha256 sha
                                            :position-id (:position-id route)
                                            :citation (:citation route)
                                            :parser-id (get-in route [:claim :parser-id])
                                            :parser-version (get-in route [:claim :parser-version])
                                            :exception-fingerprint (digest (:claim route))
                                            :status :missing-verified-candidate-payload
                                            :reason :missing-verified-candidate-payload}
                                      on-progress))
               (swap! gaps inc))))
         (doseq [gap (concat (:gaps document)
                             (map (fn [reason] {:status :unsupported-document :reason reason})
                                  (:unsupported-reasons document)))
                 :when (or (nil? only-positions)
                           (contains? only-positions [sha (or (:position-id gap)
                                                              (:section-id gap))])
                           (and (= :unsupported-document (:status gap))
                                (contains? only-positions [sha nil])))]
           (when (= :created
                    (derive-record! root (merge {:kind :batch-exception :source-sha256 sha
                                                 :exception-fingerprint (digest gap)}
                                                (select-keys gap [:position-id :section-id :citation
                                                                  :status :reason :contenders]))
                                    on-progress))
             (swap! gaps inc)))))
     (assoc replay :metrics (-> (:metrics replay)
                                (assoc :imported-observations @imported
                                       :imported-evidence @imported-evidence
                                       :durable-exceptions @gaps
                                       :llm-exceptions 0))))))

(defn inspect
  "Read durable replay derivations. This does not inspect PostgreSQL observations."
  [root]
  (let [dir (io/file root "derivations")
        records (if (.isDirectory dir)
                  (->> (.listFiles dir)
                       (filter #(re-matches #"[0-9a-f]{64}\.edn" (.getName %)))
                       (map (fn [file]
                              (let [receipt (edn/read-string (slurp file :encoding "UTF-8"))
                                    filename-job-id (subs (.getName file) 0 64)
                                    _ (when-not (= filename-job-id (:job-id receipt))
                                        (throw (ex-info "Replay receipt job ID mismatch" {})))
                                    artifact (io/file root "derived-objects" (:artifact-sha256 receipt))
                                    bytes (archive/read-source-bytes (str artifact))]
                                (when-not (= (:artifact-sha256 receipt)
                                             (.formatHex (HexFormat/of)
                                                         (.digest (MessageDigest/getInstance "SHA-256") bytes)))
                                  (throw (ex-info "Replay artifact hash mismatch" {})))
                                (let [record (edn/read-string (String. bytes "UTF-8"))]
                                  (when-not (= filename-job-id (:job-id record))
                                    (throw (ex-info "Replay artifact job ID mismatch" {})))
                                  record))))
                       vec)
                  [])
        observations (->> records (filter #(= :batch-observation (:kind %)))
                          (sort-by (juxt :source-sha256 :position-id :parser-version)) vec)
        evidence (->> records (filter #(= :batch-evidence (:kind %)))
                      (sort-by (juxt :source-sha256 :position-id :parser-version)) vec)
        exceptions (->> records (filter #(= :batch-exception (:kind %)))
                        (sort-by (juxt :source-sha256 :position-id :status)) vec)
        resolved (set (map (juxt :source-sha256 :position-id :parser-id :parser-version)
                           observations))]
    {:observations observations
     :evidence evidence
     :exceptions exceptions
     :unresolved-exceptions (->> exceptions
                                 (remove #(and (:position-id %) (:parser-version %)
                                               (contains? resolved [(:source-sha256 %)
                                                                    (:position-id %)
                                                                    (:parser-id %)
                                                                    (:parser-version %)])))
                                 vec)
     :stages (->> records (filter #(= :batch-stage (:kind %))) vec)}))
