(ns freediving.candidates
  "Private candidate signals, never identity decisions. All source values remain immutable."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.text Normalizer Normalizer$Form]
           [java.util Locale HexFormat]
           [java.security MessageDigest]
           [java.sql DriverManager]))

(def packet-version "private-candidates/1")
(def default-config {:comparison-version "unicode-nfd-token/1" :max-observations 10000})
(defn- fail! [message] (throw (ex-info message {})))
(defn- canonical [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) x))
        (set? x) (vec (sort (map canonical x)))
        (sequential? x) (mapv canonical x) :else x))
(defn- digest [x]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256")
                                      (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str (canonical x))) "UTF-8"))))
(defn- config! [config]
  (let [c (merge default-config (dissoc config :offset :limit))]
    (when-not (and (= (:comparison-version c) (:comparison-version default-config))
                   (pos-int? (:max-observations c)) (<= (:max-observations c) 100000)
                   (= (set (keys c)) (set (keys default-config))))
      (fail! "Unsupported comparison configuration or corpus bound")) c))
(defn comparison-keys
  "Derived comparison signals only. Does not change the source name or infer nationality."
  [name]
  (when (and (string? name) (not (str/blank? name)))
    (let [folded (-> (Normalizer/normalize name Normalizer$Form/NFD)
                     (str/replace #"\p{M}" "") (.toLowerCase Locale/ROOT)
                     (str/replace #"[^\p{L}]+" " ") str/trim)
          tokens (vec (remove str/blank? (str/split folded #"\s+")))]
      (when (seq tokens) {:version (:comparison-version default-config)
                          :folded (str/join " " tokens) :tokens tokens
                          :token-order (vec (sort tokens))}))))
(defn- reference [row] (select-keys row [:job-id :ordinal]))
(defn- anchor [row]
  {:identity-id (str "local-observation:" (:job-id row) ":" (:ordinal row))
   :reference (merge (select-keys row [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256])
                     (select-keys (or (first (:source-lines row)) (get-in row [:payload :coordinates])) [:page :line]))})
(defn- name-keys [row]
  (when (and (= "result-row" (:kind row)) (= :parsed (get-in row [:payload :parse-status])))
    (comparison-keys (get-in row [:payload :parsed :source-name]))))
(defn- row-order [row] [(:job-id row) (:ordinal row)])
(defn- group-key [row]
  [(:source-sha256 row) (or (:token-order (name-keys row)) (reference row))])
(defn- signals [a b]
  (when (and a b)
    (cond
      (= (:folded a) (:folded b)) [:case-diacritic-spacing]
      (= (:token-order a) (:token-order b)) [:token-order]
      (and (>= (count (:tokens a)) 2) (>= (count (:tokens b)) 2)
           (some (set (filter #(>= (count %) 4) (:tokens a))) (:tokens b))) [:shared-long-token]
      :else nil)))
(defn- corpus! [corpus config]
  (let [rows (vec (take (inc (:max-observations config)) corpus))]
    (when (> (count rows) (:max-observations config)) (fail! "Corpus exceeds max-observations; no partial retrieval produced"))
    (when-not (= (count rows) (count (set (map reference rows)))) (fail! "Duplicate observation version reference"))
    (vec (sort-by row-order rows))))
(defn- groups [rows]
  (->> rows (group-by group-key) vals (map #(vec (sort-by row-order %)))
       (sort-by #(row-order (first %))) vec))
(defn- packet* [rows all-groups target-group config corpus-id]
  (let [target (first target-group)
        keys (name-keys target)
        candidates (->> all-groups
                        (remove #(= (group-key target) (group-key (first %))))
                        (keep (fn [g]
                                (when-let [s (signals keys (name-keys (first g)))]
                                  {:group-id (digest [packet-version (group-key (first g))])
                                   :source-sha256 (:source-sha256 (first g))
                                   :comparison-keys (name-keys (first g)) :signals s
                                   :local-identity-anchor (anchor (first g))
                                   :observations g :observation-count (count g)}))) vec)
        outcome (cond (nil? keys) :unknown (empty? candidates) :no-candidate
                      (or (> (count candidates) 1) (= [:shared-long-token] (:signals (first candidates)))) :ambiguous
                      :else :candidate)]
    {:packet-version packet-version :packet-id (digest [packet-version config corpus-id (group-key target)])
     :config config :corpus-id corpus-id :corpus-observation-count (count rows)
     :target-group-id (digest [packet-version (group-key target)])
     :grouping-status :comparison-bucket-not-person
     :review-options [:approve :reject :no-match :needs-more-evidence]
     :target target :target-observations target-group :comparison-keys keys
     :local-identity-anchor (when keys (anchor target)) :outcome outcome :review-status :unreviewed
     :candidates candidates :candidate-group-count (count candidates)
     :distinct-source-document-count (count (set (map :source-sha256 (cons target (mapcat :observations candidates)))))
     :independent-corroboration :not-established
     :uncertainties [:retrieval-signals-are-not-identity-evidence :matching-names-may-denote-different-people
                     :distinct-documents-may-share-upstream-source :representation-is-not-nationality
                     :source-values-and-parser-uncertainties-retained]}))
(defn packet [corpus target-ref config]
  (let [config (config! config) rows (corpus! corpus config) gs (groups rows)
        group (some #(when (some (fn [row] (= target-ref (reference row))) %) %) gs)]
    (when-not group (fail! "Unknown target observation"))
    (packet* rows gs group config (digest rows))))
(defn packets
  "One case per source document/comparison name group. Pagination never truncates candidates inside a case."
  [corpus {:keys [offset limit] :or {offset 0 limit 50} :as options}]
  (when-not (and (nat-int? offset) (pos-int? limit) (<= limit 1000)) (fail! "offset must be nonnegative and limit 1..1000"))
  (let [config (config! options) rows (corpus! corpus config) gs (groups rows) id (digest rows)]
    {:packet-version packet-version :config config :corpus-id id :total (count gs)
     :observation-count (count rows) :offset offset :limit limit :has-more? (< (+ offset limit) (count gs))
     :packets (mapv #(packet* rows gs % config id) (take limit (drop offset gs)))}))
(defn- artifact-for [connection job]
  (with-open [statement (.prepareStatement connection "SELECT artifact_bytes FROM freediving.extractions WHERE job_id=?")]
    (.setString statement 1 job)
    (with-open [result (.executeQuery statement)]
      (when (.next result)
        (edn/read-string (String. (.getBytes result 1) "UTF-8"))))))
(defn load-corpus
  "Read one bounded immutable corpus in a single repeatable-read, read-only transaction."
  [url config]
  (let [config (config! config)]
    (with-open [connection (DriverManager/getConnection url)]
      (.setReadOnly connection true)
      (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
      (.setAutoCommit connection false)
      (with-open [statement (.prepareStatement connection "SELECT e.job_id,e.artifact_sha256,e.source_sha256,e.parser_version,e.schema_version,o.ordinal,o.candidate_id,o.kind,o.classification_reason,o.payload_edn FROM freediving.extractions e JOIN freediving.observations o USING(job_id) ORDER BY e.job_id,o.ordinal LIMIT ?")]
        (.setInt statement 1 (inc (:max-observations config)))
        (let [rows (with-open [result (.executeQuery statement)]
                     (loop [rows []]
                       (if (.next result)
                         (recur (conj rows {:job-id (.getString result "job_id") :ordinal (.getInt result "ordinal")
                                            :candidate-id (.getString result "candidate_id")
                                            :artifact-sha256 (.getString result "artifact_sha256")
                                            :source-sha256 (.getString result "source_sha256")
                                            :parser-version (.getString result "parser_version")
                                            :schema-version (.getInt result "schema_version")
                                            :kind (.getString result "kind") :classification-reason (.getString result "classification_reason")
                                            :payload (edn/read-string (.getString result "payload_edn"))}))
                         (corpus! rows config))))
              artifacts (into {} (map (fn [job] [job (artifact-for connection job)]) (distinct (map :job-id rows))))]
          (mapv (fn [row]
                  (let [artifact (get artifacts (:job-id row)) payload (:payload row) coords (:coordinates payload)
                        lines (or (seq (:source-lines payload))
                                  (for [p (:pages artifact) :when (= (:page p) (:page coords))
                                        l (:lines p) :when (= (:line l) (:line coords))]
                                    (assoc l :page (:page p))))]
                    (assoc row :acquisitions (:acquisitions artifact) :evidence-sha256 (:evidence-sha256 artifact)
                           :extraction-provenance (select-keys artifact [:config :actor :tool :processed-at :pdfinfo-version])
                           :source-lines (vec lines)))) rows))))))
