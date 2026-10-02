(ns freediving.batch-evidence-db
  "Validated, immutable PostgreSQL role for retained per-position batch evidence.
   It is separate from legacy extraction observations and grants no review authority."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.archive :as archive]
            [freediving.parser-batch-import :as replay])
  (:import [java.security MessageDigest]
           [java.sql Connection DriverManager]
           [java.util HexFormat]))

(defn- fail! [reason] (throw (ex-info (name reason) {:reason reason})))
(defn- sha [^bytes bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- encode [x] (binding [*print-length* nil *print-level* nil] (pr-str x)))
(defn- digest [x] (sha (.getBytes (encode x) "UTF-8")))
(defn- canonical [value]
  (cond
    (map? value) [:map (->> value (map (fn [[k v]] [(canonical k) (canonical v)]))
                            (sort-by (comp pr-str first)) vec)]
    (set? value) [:set (->> value (map canonical) (sort-by pr-str) vec)]
    (sequential? value) [:sequence (mapv canonical value)]
    :else value))
(defn- replay-job-id [record]
  (digest (canonical (select-keys record [:kind :source-sha256 :parser-id
                                          :parser-version :position-id :section-id
                                          :status :reason :route-fingerprint
                                          :exception-fingerprint]))))
(defn- candidate-position [format candidate]
  (or (:id candidate)
      (let [{:keys [table row page line column-start column-end]} (:coordinates candidate)]
        (case format
          :html (str "table=" table "&row=" row)
          :pdf (str "page=" page "&line=" line "&column=" column-start "-" column-end)
          nil))))
(defn- hash? [x] (and (string? x) (boolean (re-matches #"[0-9a-f]{64}" x))))
(defn- text? [x] (and (string? x) (not (str/blank? x))))
(defn- exec! [^Connection c sql & values]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[n v] (map-indexed vector values)] (.setObject s (inc n) v))
    (.executeUpdate s)))
(defn- rows [^Connection c sql & values]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[n v] (map-indexed vector values)] (.setObject s (inc n) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)]
        (loop [out []]
          (if (.next r)
            (recur (conj out (into {} (for [n (range 1 (inc (.getColumnCount m)))]
                                        [(keyword (.getColumnLabel m n)) (.getObject r n)]))))
            out))))))

(defn migrate!
  "Apply checksum-guarded migration as owner; grant only SELECT/INSERT to role."
  [admin-url app-role]
  (when-not (and (text? admin-url) (re-matches #"[a-z_][a-z0-9_]*" app-role))
    (fail! :invalid-database-role))
  (let [sql (slurp (io/resource "migrations/019-batch-evidence.sql"))
        checksum (sha (.getBytes sql "UTF-8"))]
    (with-open [c (DriverManager/getConnection admin-url)]
      (.setAutoCommit c false)
      (try
        (rows c "SELECT pg_advisory_xact_lock(781246914)")
        (let [role (first (rows c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=?" app-role))]
          (when (or (nil? role) (some true? (vals role))
                    (seq (rows c "SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=?)" app-role))
                    (seq (rows c "SELECT 1 FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')" app-role))
                    (seq (rows c "SELECT 1 FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')" app-role))
                    (seq (rows c "SELECT 1 FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')" app-role))
                    (= app-role (:current_user (first (rows c "SELECT current_user")))))
            (fail! :unrestricted-database-role)))
        (exec! c "CREATE SCHEMA IF NOT EXISTS freediving")
        (exec! c "CREATE TABLE IF NOT EXISTS freediving.schema_migrations (version integer PRIMARY KEY, sha256 text NOT NULL)")
        (if-let [prior (first (rows c "SELECT sha256 FROM freediving.schema_migrations WHERE version=19"))]
          (when-not (= checksum (:sha256 prior)) (fail! :migration-checksum-conflict))
          (do (exec! c sql)
              (exec! c "INSERT INTO freediving.schema_migrations(version,sha256) VALUES (19,?)" checksum)))
        (exec! c "REVOKE ALL ON SCHEMA freediving FROM PUBLIC")
        (exec! c (str "GRANT USAGE ON SCHEMA freediving TO " app-role))
        (exec! c (str "GRANT SELECT,INSERT ON freediving.batch_position_evidence TO " app-role))
        (.commit c)
        {:schema-version 19}
        (catch Exception e (.rollback c) (throw e))))))

(defn- validated [root record]
  (let [{:keys [kind job-id source-sha256 position-id citation parser-id parser-version
                evidence-role candidate coordinates source-verification format]} record
        evidence? (#{:batch-observation :batch-evidence} kind)
        observation? (= :batch-observation kind)
        result? (boolean (#{:result-row :individual-result} evidence-role))
        acquisitions (:acquisitions (archive/inspect root source-sha256))]
    (when-not (and (hash? job-id) (= job-id (replay-job-id record))
                   (hash? source-sha256) (seq acquisitions)
                   (#{:batch-observation :batch-evidence :batch-exception} kind)
                   (= observation? result?)
                   (or (not evidence?)
                       (and (text? position-id) (text? citation)
                            (text? parser-id) (text? parser-version)
                            (#{:result-row :individual-result :event-ranking :aggregate :placeholder} evidence-role)
                            (map? candidate)
                            (= coordinates (:coordinates candidate))
                            (= position-id (candidate-position format candidate))
                            (= citation (str "sha256:" source-sha256 "#" position-id))
                            (= citation (or (:citation candidate)
                                            (str "sha256:" source-sha256 "#" position-id)))
                            (some? source-verification)
                            (not (#{:failed :unverified} source-verification)))))
      (fail! :invalid-batch-evidence))
    {:job-id job-id :record-sha256 (digest record) :source-sha256 source-sha256
     :position-id position-id :parser-id parser-id :parser-version parser-version
     :record-kind (name kind) :evidence-role (some-> evidence-role name)
     :revision job-id :acquisitions-edn (encode acquisitions) :record-edn (encode record)}))

(defn import!
  "Verify retained derivations and acquisitions, then atomically import positions.
   A failed callback or invalid record rolls back the whole replay."
  ([app-url root] (import! app-url root {}))
  ([app-url root {:keys [on-progress]}]
   (let [snapshot (replay/inspect root)
         records (concat (:observations snapshot) (:evidence snapshot) (:exceptions snapshot))
         prepared (mapv #(validated root %) records)]
     (with-open [c (DriverManager/getConnection app-url)]
       (.setAutoCommit c false)
       (try
         (let [counts (reduce
                       (fn [acc record]
                         (let [prior (first (rows c "SELECT record_sha256,source_sha256,acquisitions_edn,record_edn FROM freediving.batch_position_evidence WHERE job_id=?" (:job-id record)))]
                           (if prior
                             (do (when-not (and (= (select-keys prior [:record_sha256 :source_sha256 :record_edn])
                                                   {:record_sha256 (:record-sha256 record)
                                                    :source_sha256 (:source-sha256 record)
                                                    :record_edn (:record-edn record)})
                                                (every? (set (edn/read-string (:acquisitions-edn record)))
                                                        (edn/read-string (:acquisitions_edn prior))))
                                   (fail! :conflicting-batch-evidence))
                                 (update acc :skipped inc))
                             (do (exec! c "INSERT INTO freediving.batch_position_evidence(job_id,record_sha256,source_sha256,position_id,parser_id,parser_version,record_kind,evidence_role,revision,acquisitions_edn,record_edn) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                                        (:job-id record) (:record-sha256 record) (:source-sha256 record)
                                        (:position-id record) (:parser-id record) (:parser-version record)
                                        (:record-kind record) (:evidence-role record) (:revision record)
                                        (:acquisitions-edn record) (:record-edn record))
                                 (when on-progress (on-progress {:job-id (:job-id record) :status :created}))
                                 (update acc :created inc)))))
                       {:created 0 :skipped 0} prepared)]
           (.commit c)
           counts)
         (catch Exception e (.rollback c) (throw e)))))))

(defn inspect
  "Read imported position evidence; original record and acquisition receipt remain intact."
  [app-url]
  (with-open [c (DriverManager/getConnection app-url)]
    (mapv (fn [row]
            (assoc (edn/read-string (:record_edn row))
                   :revision (:revision row)
                   :acquisitions (edn/read-string (:acquisitions_edn row))))
          (rows c "SELECT record_edn,acquisitions_edn,revision FROM freediving.batch_position_evidence ORDER BY source_sha256,position_id,job_id"))))
