(ns freediving.public-results
  "Read-only public projection. Refresh requires a trusted reviewer database capability."
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]
            [freediving.publication :as publication])
  (:import [java.sql DriverManager Connection] [java.security MessageDigest] [java.util HexFormat]))
(def public-field-keys
  #{:source-name :representation :federation :event-name :event-date :discipline :category
    :rank :performance :unit :announced :announced-unit :points :penalty :penalty-unit
    :declared-depth :attempted-depth :final-depth :final-distance :realized-distance :duration :source-name-fragments
    :status :card :notes :final-time :realized-time :final-duration :realized-duration :split-time})
(defn- scalar? [v] (or (nil? v) (string? v) (number? v) (boolean? v) (keyword? v)))
(defn public-fields [fields]
  (into {} (keep (fn [[k v]]
                   (when (public-field-keys k)
                     (cond
                       (scalar? v) [k v]
                       (and (= :source-name-fragments k) (vector? v) (every? string? v)) [k v]
                       (and (#{:final-time :realized-time :split-time} k) (map? v))
                       [k (cond-> (into {} (filter (fn [[_ value]] (scalar? value))
                                                   (select-keys v [:raw :minutes :seconds :centiseconds :hundredths :milliseconds :notation :unit :value :fraction :fraction-digits])))
                            (and (vector? (:components v)) (every? number? (:components v))) (assoc :components (:components v)))]))) fields)))
(defn- fail! [s] (throw (ex-info s {})))
(defn- sha [s] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))))
(defn- result-id [job ordinal] (sha (str job "/" ordinal)))
(defn- query [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)] (loop [rows []]
                                  (if (.next r) (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))]
                                                                             [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows))))))
(defn- execute! [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v)) (.executeUpdate s)))
(defn- transaction [url f]
  (with-open [c (DriverManager/getConnection url)]
    (.setTransactionIsolation c Connection/TRANSACTION_REPEATABLE_READ) (.setAutoCommit c false)
    (try (let [v (f c)] (.commit c) v) (catch Exception e (.rollback c) (throw e)))))
(defn migrate! [url reviewer-role public-role]
  (doseq [role [reviewer-role public-role]]
    (when-not (and (string? role) (re-matches #"[a-z_][a-z0-9_]*" role)) (fail! "Invalid role")))
  (when (= reviewer-role public-role) (fail! "Separate public role required"))
  (transaction url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246914)")
                 (doseq [role [reviewer-role public-role]]
                   (let [r (first (query c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=?" role))]
                     (when (or (nil? r) (some true? (vals r))
                               (seq (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=?)" role))
                               (seq (query c "SELECT oid FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')" role))
                               (seq (query c "SELECT oid FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')" role))
                               (seq (query c "SELECT oid FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')" role)))
                       (fail! "Projection roles must be restricted without ownership or memberships"))))
                 (let [sql (slurp (io/resource "migrations/004-public-results.sql")) checksum (sha sql)]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=4"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(4,?)" checksum))))
                 (execute! c (str "REVOKE ALL ON ALL TABLES IN SCHEMA freediving FROM " public-role ",PUBLIC"))
                 (execute! c (str "REVOKE CREATE ON SCHEMA freediving FROM " public-role ",PUBLIC"))
                 (execute! c (str "GRANT USAGE ON SCHEMA freediving TO " public-role))
                 (execute! c (str "GRANT SELECT ON freediving.public_results TO " public-role))
                 (execute! c (str "GRANT SELECT,INSERT,UPDATE,DELETE ON freediving.public_projection_cache TO " reviewer-role))
                 {:schema-version 4})))
(defn- eligible-rows [c]
  (query c "SELECT p.* FROM freediving.publication_decisions p WHERE p.action='validate' AND p.policy_version=? AND p.policy_version=(SELECT policy_version FROM freediving.publication_policy_events ORDER BY revision DESC LIMIT 1) AND NOT EXISTS(SELECT 1 FROM freediving.publication_decisions n WHERE n.job_id=p.job_id AND n.ordinal=p.ordinal AND n.revision>p.revision) AND p.review_revision=COALESCE((SELECT max(r.revision) FROM freediving.review_decisions r WHERE r.job_id=p.job_id AND r.ordinal=p.ordinal),0)" publication/current-policy))
(defn- safe-identity [identity eligible]
  (when (= :matched (:outcome identity))
    (let [id (:identity-id identity)]
      (when (string? id)
        (if (str/starts-with? id "local-observation:")
          (when (contains? eligible (subs id (count "local-observation:"))) (sha id))
          (sha (str "identity:" id)))))))
(defn- evidence [refs job ordinal eligible]
  (vec (keep (fn [ref]
               (let [local? (not (contains? ref :job-id))
                     target (str (:job-id ref) ":" (:ordinal ref))]
                 (when (or local? (contains? eligible target))
                   (merge (select-keys ref [:page :line])
                          {:result-id (if local? (result-id job ordinal) (result-id (:job-id ref) (:ordinal ref)))})))) refs)))
(defn- projection [c validation eligible]
  (let [{:keys [job_id ordinal]} validation
        o (first (query c "SELECT o.payload_edn,e.artifact_bytes,e.source_sha256 FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE o.job_id=? AND ordinal=?" job_id ordinal))
        payload (edn/read-string (:payload_edn o))
        artifact (edn/read-string (String. ^bytes (:artifact_bytes o) "UTF-8"))
        ps (into {} (map (fn [r] [(:id r) (edn/read-string (:body_edn r))])
                         (query c "SELECT id,body_edn FROM freediving.review_proposals WHERE job_id=? AND ordinal=?" job_id ordinal)))
        ds (mapv #(assoc (edn/read-string (:body_edn %)) :recorded-at (str (:recorded_at %))) (query c "SELECT body_edn,recorded_at FROM freediving.review_decisions WHERE job_id=? AND ordinal=? ORDER BY revision" job_id ordinal))
        by-id (into {} (map (juxt :id identity) ds))
        state (reduce (fn [s d]
                        (let [p (ps (if (= :reverse (:action d)) (:proposal-id (by-id (:event-id d))) (:proposal-id d))) f (:field p)]
                          (case (:action d)
                            :approve (-> s (assoc-in [:fields f] (:after p)) (assoc-in [:active f] (:id d)))
                            :reverse (-> s (assoc-in [:fields f] (:before p)) (assoc-in [:active f] (:prior-event (by-id (:event-id d)))))
                            s))) {:fields (assoc (:parsed payload) :identity {:outcome :unknown}) :active {}} ds)
        identity-id (safe-identity (get-in state [:fields :identity]) eligible)
        audit (vec (keep (fn [d]
                           (let [p (ps (if (= :reverse (:action d)) (:proposal-id (by-id (:event-id d))) (:proposal-id d)))
                                 field (:field p) refs (evidence (:evidence p) job_id ordinal eligible)]
                             (when (and (#{:approve :reverse} (:action d)) (public-field-keys field) (seq refs))
                               {:action (:action d) :field field
                                :event-id (sha (:id d)) :reverses (when (= :reverse (:action d)) (sha (:event-id d))) :recorded-at (:recorded-at d)
                                :before (get (public-fields {field (if (= :reverse (:action d)) (:after p) (:before p))}) field)
                                :after (get (public-fields {field (if (= :reverse (:action d)) (:before p) (:after p))}) field)
                                :reason (:reason d) :correction-reason (:reason p) :evidence refs
                                :effective? (= (:id d) (get-in state [:active field]))}))) ds))
        original (public-fields (:parsed payload))
        supported? (every? (fn [[field event-id]]
                             (or (nil? event-id) (not (public-field-keys field))
                                 (seq (evidence (:evidence (ps (:proposal-id (by-id event-id)))) job_id ordinal eligible))))
                           (:active state))]
    (when supported?
      {:result-id (result-id job_id ordinal)
       :citations (mapv #(merge (select-keys (:manifest %) [:discovery-url :final-url :publisher :relationship :mirror-of])
                                {:source-sha256 (:source_sha256 o)}) (:acquisitions artifact))
       :source-position (select-keys (:coordinates payload) [:page :line])
       :original original :raw-values (public-fields (get-in payload [:raw :fields]))
       :effective (public-fields (:fields state)) :correction-audit audit
       :identity (if identity-id {:status :approved :id identity-id} {:status :unresolved})
       :unknown-fields (vec (sort (filter #(nil? (get (:fields state) %)) public-field-keys)))
       :coverage {:scope :pilot :completeness :partial}})))
(defn refresh! [url]
  (transaction url
               (fn [c]
      ;; One snapshot for all records and dependencies. Changes after this snapshot invalidate the view.
                 (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.public_projection_cache','INSERT') AND has_table_privilege(current_user,'freediving.publication_decisions','INSERT') AS allowed")))
                   (fail! "Projection reviewer capability required"))
                 (query c "SELECT pg_advisory_xact_lock(781246915)")
                 (let [validations (eligible-rows c)
                       projected (loop [vs validations]
                                   (let [eligible (set (map #(str (:job_id %) ":" (:ordinal %)) vs))
                                         pairs (vec (keep (fn [v] (when-let [p (projection c v eligible)] [v p])) vs))]
                                     (if (= (count pairs) (count vs)) pairs (recur (mapv first pairs)))))
                       review-count (:n (first (query c "SELECT count(*) AS n FROM freediving.review_decisions")))
                       validation-count (:n (first (query c "SELECT count(*) AS n FROM freediving.publication_decisions")))]
                   (execute! c "DELETE FROM freediving.public_projection_cache")
                   (doseq [[v p] projected]
                     (execute! c "INSERT INTO freediving.public_projection_cache(result_id,job_id,ordinal,validation_id,policy_version,review_count,validation_count,source_name,identity_id,body_edn) VALUES(?,?,?,?,?,?,?,?,?,?)"
                               (:result-id p) (:job_id v) (:ordinal v) (:id v) publication/current-policy review-count validation-count
                               (get-in p [:original :source-name]) (get-in p [:identity :id]) (binding [*print-length* nil *print-level* nil] (pr-str p))))
                   {:refreshed (count projected)}))))
(defn results [url]
  (transaction url #(mapv (comp edn/read-string :body_edn) (query % "SELECT body_edn FROM freediving.public_results ORDER BY result_id"))))
(defn search-source-name [url exact-name]
  (transaction url #(mapv (comp edn/read-string :body_edn) (query % "SELECT body_edn FROM freediving.public_results WHERE source_name=? ORDER BY result_id" exact-name))))
(defn result [url id]
  (transaction url #(some-> (first (query % "SELECT body_edn FROM freediving.public_results WHERE result_id=?" id)) :body_edn edn/read-string)))
(defn athlete-history [url id]
  (transaction url #(mapv (comp edn/read-string :body_edn) (query % "SELECT body_edn FROM freediving.public_results WHERE identity_id=? ORDER BY result_id" id))))
(defn coverage [url]
  (transaction url #(assoc (first (query % "SELECT count(*) AS results,count(DISTINCT identity_id) AS approved_identities FROM freediving.public_results")) :scope :pilot :completeness :partial)))
(defn -main [& [command & args]]
  (try
    (let [url (System/getenv "FREEDIVING_DATABASE_URL")]
      (println (pr-str
                (case command
                  "migrate" (if (= 2 (count args)) (apply migrate! url args) (fail! "Expected reviewer and public roles"))
                  "refresh" (if (empty? args) (refresh! url) (fail! "Unexpected arguments"))
                  "list" (if (empty? args) (results url) (fail! "Unexpected arguments"))
                  "coverage" (if (empty? args) (coverage url) (fail! "Unexpected arguments"))
                  ("search-source-name" "result" "athlete-history")
                  (if (= 1 (count args)) (({"search-source-name" search-source-name "result" result "athlete-history" athlete-history} command) url (first args))
                      (fail! "Expected one argument"))
                  (fail! "Unknown public results command")))))
    ;; Database errors can contain private SQL parameters; never echo them to public callers.
    (catch Exception _ (binding [*out* *err*] (println "Public results operation failed")) (System/exit 1))))
