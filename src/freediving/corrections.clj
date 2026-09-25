(ns freediving.corrections
  "Private immutable visitor requests. Submissions never grant review or publication authority."
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str])
  (:import [java.sql Connection DriverManager] [java.util UUID HexFormat] [java.security MessageDigest] [java.net URI]))
(defn- fail! [status] (throw (ex-info (name status) {:status status})))
(defn- query [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)]
        (loop [rows []]
          (if (.next r) (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))]
                                                     [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows))))))
(defn- execute! [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v)) (.executeUpdate s)))
(defn- transaction [url f]
  (with-open [c (DriverManager/getConnection url)]
    (.setAutoCommit c false)
    (execute! c "SET LOCAL lock_timeout = '2s'")
    (execute! c "SET LOCAL statement_timeout = '5s'")
    (try (let [v (f c)] (.commit c) v) (catch Exception e (.rollback c) (throw e)))))
(defn- restricted-role! [c role]
  (let [r (first (query c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls,rolreplication FROM pg_roles WHERE rolname=?" role))]
    (when (or (nil? r) (some true? (vals r))
              (seq (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=?)" role))
              (seq (query c "SELECT oid FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')" role))
              (seq (query c "SELECT oid FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')" role))
              (seq (query c "SELECT oid FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')" role)))
      (fail! :unsafe-role))))
(defn- assert-role! [c role]
  (restricted-role! c role)
  (doseq [[sql args]
          [["SELECT oid FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname<>'information_schema' AND (pg_has_role(?,nspowner,'MEMBER') OR has_schema_privilege(?,oid,'CREATE'))" [role role]]
           ["SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema' AND c.relkind IN ('r','v','m','p','f') AND (pg_has_role(?,c.relowner,'MEMBER') OR has_table_privilege(?,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(?,c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))" [role role role]]
           ["SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema' AND CASE WHEN c.relkind='S' THEN has_sequence_privilege(?,c.oid,'USAGE,SELECT,UPDATE') ELSE false END" [role]]
           ["SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema' AND p.prosecdef AND p.oid NOT IN ('freediving.submit_correction(uuid,text,text,text,text,text,text)'::regprocedure,'freediving.correction_target_version(text)'::regprocedure) AND has_function_privilege(?,p.oid,'EXECUTE')" [role]]]]
    (when (seq (apply query c sql args)) (fail! :unsafe-role)))
  (when (or (:allowed (first (query c "SELECT has_database_privilege(?,current_database(),'CREATE') AS allowed" role)))
            (not (:allowed (first (query c "SELECT has_function_privilege(?,'freediving.submit_correction(uuid,text,text,text,text,text,text)','EXECUTE') AND has_function_privilege(?,'freediving.correction_target_version(text)','EXECUTE') AS allowed" role role)))))
    (fail! :unsafe-role)) true)
(defn assert-submitter! [url]
  (when-not (and (string? url)
                 (re-matches #"jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+(?:\?(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)(?:&(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)*|jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+" url)) (fail! :invalid))
  (transaction url #(assert-role! % (:role (first (query % "SELECT current_user AS role"))))))
(defn migrate! [admin-url reviewer-role submit-role]
  (when-not (and (not= reviewer-role submit-role) (every? #(and (string? %) (re-matches #"[a-z_][a-z0-9_]*" %)) [reviewer-role submit-role])) (fail! :invalid))
  (transaction admin-url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246914)")
                 (doseq [role [reviewer-role submit-role]] (restricted-role! c role))
                 (when-not (:allowed (first (query c "SELECT has_table_privilege(?,'freediving.review_decisions','INSERT') AS allowed" reviewer-role))) (fail! :unsafe-role))
                 (let [sql (slurp (io/resource "migrations/005-corrections.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=5"))]
                     (when-not (= checksum (:sha256 old)) (fail! :checksum-conflict))
                     (do
                       (execute! c sql)
                       (execute! c "INSERT INTO freediving.schema_migrations VALUES(5,?)" checksum)
                       ;; Historical migration 5 replaces the public view. Restore
                       ;; the newest installed, checksummed definition only.
                       (when-let [newer (first (query c "SELECT version,sha256 FROM freediving.schema_migrations WHERE version IN (9,10) ORDER BY version DESC LIMIT 1"))]
                         (let [resource (if (= 10 (:version newer)) "migrations/010-event-selections.sql" "migrations/009-html-public-results.sql")
                               sql (slurp (io/resource resource))
                               checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))
                               view-sql (re-find #"(?s)CREATE OR REPLACE VIEW freediving\.public_results\b.*?;" sql)]
                           (when-not (= checksum (:sha256 newer)) (fail! :checksum-conflict))
                           (when-not view-sql (fail! :missing-public-view))
                           (execute! c view-sql))))))
                 ;; Restoring without ACLs restores default PUBLIC EXECUTE even when
                 ;; the migration checksum is present. Reapply its function boundary.
                 (execute! c "REVOKE ALL ON FUNCTION freediving.correction_target_version(text),freediving.submit_correction(uuid,text,text,text,text,text,text),freediving.stamp_correction_triage() FROM PUBLIC")
                 (execute! c (str "REVOKE ALL ON ALL TABLES IN SCHEMA freediving FROM " submit-role))
                 (execute! c (str "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA freediving FROM " submit-role))
                 (execute! c (str "REVOKE ALL ON SCHEMA freediving FROM " submit-role))
                 (execute! c (str "GRANT USAGE ON SCHEMA freediving TO " submit-role))
                 (execute! c (str "GRANT EXECUTE ON FUNCTION freediving.correction_target_version(text),freediving.submit_correction(uuid,text,text,text,text,text,text) TO " submit-role))
                 (execute! c (str "GRANT SELECT ON freediving.correction_requests,freediving.correction_triage TO " reviewer-role))
                 (execute! c (str "GRANT INSERT ON freediving.correction_triage TO " reviewer-role))
                 (assert-role! c submit-role)
                 {:schema-version 5})))
(defn- bounded-text? [v n]
  (and (string? v) (not (str/blank? v)) (not (re-find #"\p{Cs}" v)) (<= (count v) n) (<= (alength (.getBytes ^String v "UTF-8")) (* 4 n))
       (not (re-find #"[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]" v))))
(defn- uuid [s]
  (try (when (and (string? s) (re-matches #"[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}" s)) (UUID/fromString s)) (catch Exception _ nil)))
(defn- hex? [s] (and (string? s) (boolean (re-matches #"[a-f0-9]{64}" s))))
(defn- evidence? [s]
  (and (bounded-text? s 2000)
       (every? (fn [token]
                 (try (let [u (URI. token)]
                        (and (#{"http" "https"} (.getScheme u)) (.getHost u) (nil? (.getUserInfo u))
                             (nil? (.getRawQuery u))
                             (or (nil? (.getRawFragment u)) (re-matches #"page=[0-9]{1,6}" (.getRawFragment u)))))
                      (catch Exception _ false)))
               (re-seq #"(?i)[a-z][a-z0-9+.-]*:[^\s<>\"]+" s))))
(defn target-version [url result-id]
  (when (hex? result-id)
    (transaction url #(:version (first (query % "SELECT freediving.correction_target_version(?) AS version" result-id))))))
(defn submit! [url r client-key]
  (when-not (and (map? r) (= #{:id :result-id :version :suggestion :reason :evidence} (set (keys r)))
                 (uuid (:id r)) (hex? (:result-id r)) (hex? (:version r)) (hex? client-key)
                 (bounded-text? (:suggestion r) 1000) (bounded-text? (:reason r) 2000) (evidence? (:evidence r))) (fail! :invalid))
  (let [row (transaction url #(first (query % "SELECT * FROM freediving.submit_correction(?,?,?,?,?,?,?)"
                                            (uuid (:id r)) (:result-id r) (:version r) (:suggestion r) (:reason r) (:evidence r) client-key)))]
    (if (= "pending" (:status row)) {:status :pending :id (str (:receipt row)) :duplicate (:duplicate row)} (fail! (keyword (:status row))))))
(defn- friendly [r]
  (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) (if (or (instance? UUID v) (instance? java.sql.Timestamp v)) (str v) v)]) r)))
(defn list-requests [url {:keys [limit offset] :or {limit 50 offset 0}}]
  (when-not (and (int? limit) (<= 1 limit 100) (nat-int? offset) (<= offset 10000)) (fail! :invalid))
  (transaction url
               (fn [c]
                 {:requests (mapv (fn [r]
                                    (let [events (mapv friendly (query c "SELECT * FROM freediving.correction_triage WHERE request_id=? ORDER BY revision" (:id r)))]
                                      (assoc (friendly (dissoc r :client_key)) :history events :revision (or (:revision (last events)) 0))))
                                  (query c "SELECT * FROM freediving.correction_requests ORDER BY recorded_at DESC,id LIMIT ? OFFSET ?" limit offset))})))
(defn triage! [url r]
  (when-not (and (= #{:id :request-id :base-revision :action :proposal-id :actor :reason} (set (keys r)))
                 (uuid (:id r)) (uuid (:request-id r)) (nat-int? (:base-revision r))
                 (#{:dismiss :link-proposal} (:action r)) (bounded-text? (:actor r) 200) (bounded-text? (:reason r) 2000)
                 (if (= :dismiss (:action r)) (nil? (:proposal-id r)) (bounded-text? (:proposal-id r) 2000))) (fail! :invalid))
  (transaction url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246917)")
                 (let [target (first (query c "SELECT * FROM freediving.correction_requests WHERE id=?" (uuid (:request-id r))))
                       old (first (query c "SELECT * FROM freediving.correction_triage WHERE id=?" (uuid (:id r))))
                       revision (or (:revision (first (query c "SELECT max(revision) AS revision FROM freediving.correction_triage WHERE request_id=?" (uuid (:request-id r))))) 0)]
                   (when-not target (fail! :unavailable))
                   (if old
                     (do (when-not (= [(:request-id r) (inc (:base-revision r)) (name (:action r)) (:proposal-id r) (:actor r) (:reason r)]
                                      [(str (:request_id old)) (:revision old) (:action old) (:proposal_id old) (:actor old) (:reason old)]) (fail! :conflict)) (friendly old))
                     (do
                       (when-not (= revision (:base-revision r)) (fail! :conflict))
                       (when (>= revision 100) (fail! :capacity))
                       (when (= :link-proposal (:action r))
                         (when-not (seq (query c "SELECT id FROM freediving.review_proposals WHERE id=? AND job_id=? AND ordinal=?" (:proposal-id r) (:job_id target) (:ordinal target))) (fail! :invalid)))
                       (execute! c "INSERT INTO freediving.correction_triage(id,request_id,revision,action,proposal_id,actor,reason) VALUES(?,?,?,?,?,?,?)"
                                 (uuid (:id r)) (uuid (:request-id r)) (inc revision) (name (:action r)) (:proposal-id r) (:actor r) (:reason r))
                       (friendly (first (query c "SELECT * FROM freediving.correction_triage WHERE id=?" (uuid (:id r)))))))))))

(defn public-detail [url result-id]
  (when (hex? result-id)
    (transaction url
                 (fn [c]
                   (when-let [r (first (query c "SELECT body_edn,correction_version FROM freediving.public_results WHERE result_id=?" result-id))]
                     {:result (edn/read-string (:body_edn r)) :correction {:version (:correction_version r)}})))))
