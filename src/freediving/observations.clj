(ns freediving.observations
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.depth-2025 :as depth-2025]
            [freediving.aida-html :as html])
  (:import [java.sql DriverManager Connection]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- fail! [s] (throw (ex-info s {})))
(defn- encoded [v] (binding [*print-length* nil *print-level* nil] (pr-str v)))
(defn- canonical [v]
  (cond (map? v) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) v))
        (sequential? v) (mapv canonical v) :else v))
(defn- sha [^bytes b] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") b)))
(defn- digest [v] (sha (.getBytes (encoded (canonical v)) "UTF-8")))
(defn- hash? [v] (and (string? v) (re-matches #"[0-9a-f]{64}" v)))
(defn- read-edn [^bytes b]
  (with-open [r (java.io.PushbackReader. (java.io.StringReader. (String. b "UTF-8")))]
    (let [eof (Object.) x (edn/read {:eof eof} r)]
      (when (or (identical? eof x) (not (identical? eof (edn/read {:eof eof} r))))
        (fail! "Expected one EDN artifact")) x)))
(defn- connect ^Connection [url] (when-not url (fail! "Database URL required")) (DriverManager/getConnection url))
(defn- execute! [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (.executeUpdate s)))
(defn- query [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r) n (.getColumnCount m)]
        (loop [rows []]
          (if (.next r)
            (recur (conj rows (into {} (for [i (range 1 (inc n))]
                                         [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows))))))
(defn migrate!
  "Apply checked-in migration as database owner and grant INSERT/SELECT to an existing restricted role."
  [admin-url app-role]
  (when-not (re-matches #"[a-z_][a-z0-9_]*" app-role) (fail! "Invalid application role"))
  (let [sql (slurp (io/resource "migrations/001-observations.sql")) checksum (sha (.getBytes sql "UTF-8"))]
    (with-open [c (connect admin-url)]
      (.setAutoCommit c false)
      (try
        (query c "SELECT pg_advisory_xact_lock(781246914)")
        (let [role (first (query c "SELECT rolname,rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=?" app-role))
              ownership (query c "SELECT nspname FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')" app-role)
              database-owner (query c "SELECT datname FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')" app-role)
              table-owner (query c "SELECT relname FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')" app-role)
              memberships (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=?)" app-role)]
          (when (or (nil? role) (:rolsuper role) (:rolcreaterole role) (:rolcreatedb role) (:rolbypassrls role)
                    (seq ownership) (seq memberships) (seq database-owner) (seq table-owner)
                    (= app-role (:current_user (first (query c "SELECT current_user")))))
            (fail! "Application role must be separate, restricted and without role memberships or schema ownership")))
        (execute! c "CREATE SCHEMA IF NOT EXISTS freediving")
        (execute! c "CREATE TABLE IF NOT EXISTS freediving.schema_migrations (version integer PRIMARY KEY, sha256 text NOT NULL)")
        (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=1"))]
          (when-not (= checksum (:sha256 old)) (fail! "Migration checksum conflict"))
          (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES (1, ?)" checksum)))
        (let [html-sql (slurp (io/resource "migrations/007-html-extractions.sql"))
              html-checksum (sha (.getBytes html-sql "UTF-8"))]
          (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=7"))]
            (when-not (= html-checksum (:sha256 old)) (fail! "HTML migration checksum conflict"))
            (do (execute! c html-sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES (7, ?)" html-checksum))))
        (execute! c "REVOKE ALL ON SCHEMA freediving FROM PUBLIC")
        (execute! c (str "GRANT USAGE ON SCHEMA freediving TO " app-role))
        (execute! c (str "REVOKE ALL ON ALL TABLES IN SCHEMA freediving FROM " app-role))
        (execute! c (str "GRANT SELECT, INSERT ON freediving.extractions, freediving.observations TO " app-role))
        (.commit c) {:schema-version 1}
        (catch Exception e (.rollback c) (throw e))))))

(def identity-keys [:source-sha256 :acquisitions :evidence-sha256 :actor :config :parser-version :schema-version :pdfinfo-version :tool])
(defn- validate-pages! [a]
  (let [pages (:pages a)
        lines (into {} (for [p pages l (:lines p)] [[(:page p) (:line l)] (:text l)]))]
    (when-not (and (= (mapv :page pages) (vec (range 1 (inc (count pages)))))
                   (= (:pdf-page-count a) (count pages))) (fail! "Invalid page coverage"))
    (when-not (and (string? (:raw-text a))
                   (let [raw (:raw-text a) texts (mapv :text pages)]
                     (or (= raw (str/join "\f" texts)) (= raw (str (str/join "\f" texts) "\f")))))
      (fail! "Raw text differs from page evidence"))
    (doseq [p pages]
      (when-not (and (string? (:text p))
                     (= (mapv :line (:lines p)) (vec (range 1 (inc (count (:lines p))))))
                     (= (str/split (:text p) #"\n" -1) (mapv :text (:lines p))))
        (fail! "Invalid page line evidence")))
    (doseq [c (:candidates a)]
      (when-not (and (map? c) (map? (:raw c)) (#{:parsed :unparsed} (:parse-status c))
                     (= :unreviewed (:review-status c))
                     (if (= :parsed (:parse-status c)) (map? (:parsed c)) (nil? (:parsed c))))
        (fail! "Malformed candidate status or parsed payload"))
      (let [coordinate (select-keys (:coordinates c) [:page :line])
            source-lines (or (seq (:source-lines c)) [(assoc coordinate :text (get lines ((juxt :page :line) coordinate)))])
            texts (mapv :text source-lines)]
        (when-not (and (= coordinate (select-keys (first source-lines) [:page :line]))
                       (every? (fn [l] (and (every? pos-int? ((juxt :page :line) l))
                                            (string? (:text l)) (= (:text l) (get lines ((juxt :page :line) l))))) source-lines))
          (fail! "Invalid candidate page or source-lines evidence"))
        (when-not (or (= texts (get-in c [:raw :lines]))
                      (= (str/join "\n" texts) (get-in c [:raw :line])))
          (fail! "Candidate raw text differs from page evidence"))))) a)
(defn- verified [root job-id]
  (when-not (hash? job-id) (fail! "Invalid job hash"))
  (let [receipt (read-edn (archive/read-source-bytes (str (io/file root "derivations" (str job-id ".edn")))))
        h (:artifact-sha256 receipt)]
    (when-not (and (= job-id (:job-id receipt)) (hash? h)) (fail! "Malformed derivation receipt"))
    (let [bytes (archive/read-source-bytes (str (io/file root "derived-objects" h)))
          _ (when-not (= h (sha bytes)) (fail! "Artifact integrity mismatch"))
          a (read-edn bytes)]
      (when-not (#{1 2 3 4} (:schema-version a)) (fail! "Unsupported extraction schema"))
      (when-not (and (= job-id (:job-id a)) (= job-id (digest (select-keys a (if (= 4 (:schema-version a)) html/identity-keys identity-keys)))))
        (fail! "Extraction job identity mismatch"))
      (when-not (and (vector? (:candidates a)) (or (= 4 (:schema-version a)) (vector? (:pages a))) (seq (:acquisitions a))
                     (string? (:parser-version a)) (not (str/blank? (:parser-version a))) (map? (:config a))
                     (string? (:actor a)) (not (str/blank? (:actor a)))
                     (vector? (:evidence-sha256 a)) (every? hash? (:evidence-sha256 a))
                     (vector? (:acquisitions a)) (map? (:tool a))
                     (or (= 4 (:schema-version a)) (string? (:pdfinfo-version a)))
                     (string? (:processed-at a))
                     (try (java.time.OffsetDateTime/parse (:processed-at a)) (catch Exception _ false))
                     (every? #(and (string? %) (not (str/blank? %))) ((juxt :name :version) (:tool a)))
                     (vector? (get-in a [:tool :arguments])) (every? string? (get-in a [:tool :arguments]))
                     (map? (:publication a)) (= :blocked (get-in a [:publication :status]))) (fail! "Malformed extraction artifact"))
      (let [source (archive/inspect root (:source-sha256 a)) evidence (set (archive/extraction-evidence root))]
        (when-not (every? (set (:acquisitions source)) (:acquisitions a)) (fail! "Acquisition provenance mismatch"))
        (when-not (every? evidence (:evidence-sha256 a)) (fail! "Missing extraction evidence")))
      {:artifact (if (= 4 (:schema-version a)) (html/validate-artifact! root a)
                     (cond-> (validate-pages! a)
                       (= depth-2025/geometry-parser-version (:parser-version a)) (->> (extraction/validate-geometry-artifact! root)))) :bytes bytes :hash h})))
(defn- position [artifact candidate]
  (if (= 4 (:schema-version artifact))
    (let [p (select-keys (:coordinates candidate) [:table :row])]
      (when-not (every? pos-int? ((juxt :table :row) p)) (fail! "Invalid HTML candidate coordinates"))
      [p])
    (let [p (select-keys (:coordinates candidate) [:page :line])]
      (when-not (every? pos-int? ((juxt :page :line) p)) (fail! "Invalid candidate coordinates"))
      (or (when (seq (:source-lines candidate)) (mapv #(select-keys % [:page :line]) (:source-lines candidate))) [p]))))
(defn- classification [a candidate]
  (cond
    (and (= 3 (:schema-version a)) (#{"cmas-athens-pool/4" "cmas-athens-pool/5" "cmas-athens-pool/6"} (:parser-version a))
         (= "fi" (some-> candidate :raw :line str/trim)) (nil? (get-in candidate [:raw :fields]))
         (nil? (:parsed candidate)) (= :unparsed (:parse-status candidate)))
    ["fragment" "athens-detached-fi-token"]
    (or (= :parsed (:parse-status candidate)) (seq (get-in candidate [:raw :fields])))
    ["result-row" "parsed-or-explicit-result-fields"]
    :else ["unclassified" "no-explicit-result-or-fragment-evidence"]))
(defn- observation-rows [a]
  (mapv (fn [ordinal candidate]
          (let [[kind reason] (classification a candidate)]
            {:ordinal ordinal :candidate_id (digest [(:source-sha256 a) (position a candidate)])
             :kind kind :classification_reason reason :payload_edn (encoded candidate)}))
        (range) (:candidates a)))
(defn import!
  "Verify completed archive derivation, atomically append immutable observations. Optional progress hook runs inside transaction."
  ([url root job-id] (import! url root job-id {}))
  ([url root job-id {:keys [on-progress]}]
   (let [{:keys [artifact bytes hash]} (verified root job-id)]
     (with-open [c (connect url)]
       (.setAutoCommit c false)
       (try
         ;; One job lock spans conflict check and all rows, including concurrent reruns.
         (query c "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))" job-id)
         (if-let [existing (first (query c "SELECT artifact_sha256,artifact_bytes,source_sha256,parser_version,schema_version FROM freediving.extractions WHERE job_id=?" job-id))]
           (do (when-not (and (= hash (:artifact_sha256 existing))
                              (= (seq bytes) (seq (:artifact_bytes existing)))
                              (= (:source-sha256 artifact) (:source_sha256 existing))
                              (= (:parser-version artifact) (:parser_version existing))
                              (= (:schema-version artifact) (:schema_version existing))
                              (= (observation-rows artifact)
                                 (query c "SELECT ordinal,candidate_id,kind,classification_reason,payload_edn FROM freediving.observations WHERE job_id=? ORDER BY ordinal" job-id))) (fail! "Conflicting artifact for existing extraction job"))
               (.commit c) {:status :skipped :job-id job-id :observations (count (:candidates artifact))})
           (do
             (execute! c "INSERT INTO freediving.extractions(job_id,artifact_sha256,source_sha256,parser_version,schema_version,artifact_bytes) VALUES (?,?,?,?,?,?)"
                       job-id hash (:source-sha256 artifact) (:parser-version artifact) (:schema-version artifact) bytes)
             (doseq [[ordinal candidate] (map-indexed vector (:candidates artifact))]
               (let [[kind reason] (classification artifact candidate)
                     candidate-id (digest [(:source-sha256 artifact) (position artifact candidate)])]
                 (execute! c "INSERT INTO freediving.observations(job_id,ordinal,candidate_id,kind,classification_reason,payload_edn) VALUES (?,?,?,?,?,?)"
                           job-id ordinal candidate-id kind reason (encoded candidate))
                 (when on-progress (on-progress {:phase :observation-inserted :ordinal ordinal :job-id job-id}))))
             (.commit c) {:status :created :job-id job-id :observations (count (:candidates artifact))}))
         (catch Exception e (.rollback c) (throw e)))))))
(defn list-extractions [url]
  (with-open [c (connect url)] (query c "SELECT job_id, artifact_sha256, source_sha256, parser_version, schema_version FROM freediving.extractions ORDER BY job_id")))
(defn inspect [url job-id]
  (with-open [c (connect url)]
    (when-let [row (first (query c "SELECT artifact_bytes FROM freediving.extractions WHERE job_id=?" job-id))]
      {:artifact (read-edn (:artifact_bytes row)) :artifact-bytes (:artifact_bytes row)
       :observations (mapv #(-> % (assoc :payload (edn/read-string (:payload_edn %))) (dissoc :payload_edn))
                           (query c "SELECT ordinal,candidate_id,kind,classification_reason,payload_edn FROM freediving.observations WHERE job_id=? ORDER BY ordinal" job-id))})))
(defn counts [url]
  (with-open [c (connect url)]
    ;; A single PostgreSQL statement observes one MVCC snapshot during concurrent commits.
    (let [row (first (query c "SELECT e.*,o.* FROM (SELECT count(*) AS versions,count(DISTINCT source_sha256) AS sources FROM freediving.extractions) e CROSS JOIN (SELECT count(*) AS observations,count(DISTINCT candidate_id) AS candidates,count(*) FILTER (WHERE kind='result-row') AS result_rows,count(*) FILTER (WHERE kind='fragment') AS fragments,count(*) FILTER (WHERE kind='unclassified') AS unclassified FROM freediving.observations) o"))]
      (-> row (assoc :result-rows (:result_rows row)) (dissoc :result_rows)))))
(defn -main [& [command & args]]
  (try
    (let [url (System/getenv "FREEDIVING_DATABASE_URL")]
      (prn (case command
             "migrate" (if (= 1 (count args)) (migrate! url (first args)) (fail! "migrate ROLE"))
             "import" (if (= 2 (count args)) (apply import! url args) (fail! "import ARCHIVE JOB-ID"))
             "inspect" (if (= 1 (count args)) (dissoc (inspect url (first args)) :artifact-bytes) (fail! "inspect JOB-ID"))
             "list" (list-extractions url)
             "count" (counts url)
             (fail! "Commands: migrate ROLE | import ARCHIVE JOB-ID | inspect JOB-ID | list | count"))))
    (catch Exception e (binding [*out* *err*] (println "Observation operation failed:" (.getMessage e))) (System/exit 1))))
