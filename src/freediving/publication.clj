(ns freediving.publication
  "Explicit private extraction validation. Reviewer DB capability is authority; actor is audit text."
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]
            [freediving.aida-html :as html]
            [freediving.html-evidence :as html-evidence])
  (:import [java.sql DriverManager Connection] [java.security MessageDigest] [java.util HexFormat]))
(defn- fail! [message] (throw (ex-info message {})))
(defn- encode [v] (binding [*print-length* nil *print-level* nil] (pr-str v)))
(defn- connect ^Connection [url] (DriverManager/getConnection url))
(defn- query [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)]
        (loop [rows []]
          (if (.next r)
            (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))]
                                         [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows))))))
(defn- execute! [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v)) (.executeUpdate s)))
(defn- transaction [url f]
  (with-open [c (connect url)]
    (.setAutoCommit c false)
    (try (let [v (f c)] (.commit c) v) (catch Exception e (.rollback c) (throw e)))))
(def current-policy "extraction-publication/1")
(def html-policy "extraction-publication/2")
(def supported-policies #{current-policy html-policy})
(def allowed-uncertainties
  #{:owner-review-required :reconciliation-unreviewed :event-date-not-evidenced
    :card-not-evidenced :penalty-unit-not-evidenced :category-not-evidenced
    :status-not-evidenced :source-status-not-explicit
    :time-unit-not-explicit :units-not-explicit})
(defn migrate! [admin-url reviewer-role]
  (when-not (and (string? reviewer-role) (re-matches #"[a-z_][a-z0-9_]*" reviewer-role)) (fail! "Invalid role"))
  (transaction admin-url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246914)")
                 (let [r (first (query c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=?" reviewer-role))]
                   (when (or (nil? r) (some true? (vals r))
                             (not (:allowed (first (query c "SELECT has_table_privilege(?,'freediving.review_decisions','INSERT') AS allowed" reviewer-role))))
                             (:allowed (first (query c "SELECT has_table_privilege(?,'freediving.observations','INSERT') AS allowed" reviewer-role)))
                             (seq (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=?)" reviewer-role))
                             (seq (query c "SELECT oid FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')" reviewer-role))
                             (seq (query c "SELECT oid FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')" reviewer-role))
                             (seq (query c "SELECT oid FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')" reviewer-role)))
                     (fail! "Publication reviewer must be restricted without ownership or memberships")))
                 (let [sql (slurp (io/resource "migrations/003-publication.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=3"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(3,?)" checksum))))
                 (execute! c (str "REVOKE CREATE ON SCHEMA freediving FROM " reviewer-role))
                 (execute! c (str "GRANT USAGE ON SCHEMA freediving TO " reviewer-role))
                 (execute! c (str "REVOKE ALL ON freediving.publication_decisions FROM " reviewer-role))
                 (execute! c (str "GRANT SELECT,INSERT ON freediving.publication_decisions TO " reviewer-role))
                 (execute! c (str "REVOKE ALL ON freediving.publication_policy_events FROM " reviewer-role))
                 (execute! c (str "GRANT SELECT ON freediving.publication_policy_events TO " reviewer-role))
                 {:schema-version 3})))
(defn- nonblank? [x] (and (string? x) (not (str/blank? x))))
(defn- target [c {:keys [job-id ordinal]}]
  (or (first (query c "SELECT o.*,e.artifact_bytes,e.artifact_sha256,e.source_sha256 FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE job_id=? AND ordinal=?" job-id ordinal))
      (fail! "Unknown observation version")))
(defn- provenance [o]
  {:job-id (:job_id o) :ordinal (:ordinal o) :candidate-id (:candidate_id o)
   :source-sha256 (:source_sha256 o) :artifact-sha256 (:artifact_sha256 o)})
(defn- rows [c table t]
  (query c (str "SELECT * FROM freediving." table " WHERE job_id=? AND ordinal=? ORDER BY revision") (:job-id t) (:ordinal t)))
(defn- body [r]
  (assoc (edn/read-string (:body_edn r)) :db-role (:db_role r) :recorded-at (str (:recorded_at r))))
(def ^:dynamic *artifacts* nil)
(defn- artifact [c job]
  (or (when *artifacts* (get @*artifacts* job))
      (let [a (edn/read-string (String. ^bytes (:artifact_bytes (first (query c "SELECT artifact_bytes FROM freediving.extractions WHERE job_id=?" job))) "UTF-8"))]
        (when *artifacts* (swap! *artifacts* assoc job a)) a)))
(defn- state [c t]
  (let [o (target c t) payload (edn/read-string (:payload_edn o))
        artifact (artifact c (:job-id t))
        ds (rows c "review_decisions" t)
        ps (into {} (map (fn [r] [(:id r) (edn/read-string (:body_edn r))])
                         (query c "SELECT * FROM freediving.review_proposals WHERE job_id=? AND ordinal=?" (:job-id t) (:ordinal t))))
        fields (reduce (fn [fields r]
                         (let [d (edn/read-string (:body_edn r))
                               approved (when (= "reverse" (:action r)) (first (filter #(= (:event_id r) (:id %)) ds)))
                               p (ps (or (:proposal_id r) (:proposal_id approved)))]
                           (if (or (= :identity (:field p)) (= "reject" (:action r))) fields
                               (assoc fields (:field p) (if (= :reverse (:action d)) (:before p) (:after p))))))
                       (:parsed payload) ds)]
    {:observation o :payload payload :artifact artifact :fields fields
     :review-revision (or (:revision (last ds)) 0)}))
(defn- http-url? [x]
  (try (let [u (java.net.URI. x)] (and (#{"https" "http"} (.getScheme u)) (nonblank? (.getHost u)))) (catch Exception _ false)))
(defn- evidence-lines [artifact]
  (set (for [p (:pages artifact) l (:lines p)] {:page (:page p) :line (:line l)})))
(defn- finite-number? [x]
  (and (number? x) (Double/isFinite (double x))))
(defn- time-notation? [x]
  (and (map? x) (= #{:components :fraction :fraction-digits :notation} (set (keys x)))
       (vector? (:components x)) (<= 1 (count (:components x)) 2)
       (every? nat-int? (:components x))
       (or (and (= :colon-separated (:notation x)) (= 2 (count (:components x))))
           (and (= :decimal (:notation x)) (= 1 (count (:components x)))))
       (or (nil? (:fraction x)) (and (string? (:fraction x)) (re-matches #"[0-9]+" (:fraction x))))
       (= (count (:fraction x)) (:fraction-digits x))))
(defn- html-context! [{:keys [observation payload artifact]}]
  (let [context (html-evidence/bound-context! artifact payload (:ordinal observation) (:source_sha256 observation))]
    (when-not (and (= (:artifact_sha256 observation) (html-evidence/sha256 (:artifact_bytes observation)))
                   (= (:job_id observation) (:job-id artifact) (html/digest (select-keys artifact html/identity-keys)))
                   (= (:candidate_id observation) (html/digest [(:source_sha256 observation) [(:coordinates context)]])))
      (fail! "HTML observation envelope mismatch")) context))
(defn- blockers [{:keys [observation payload artifact fields] :as s} policy]
  (let [html? (html-evidence/html? artifact)
        context (when html? (try (html-context! s) (catch Exception _ nil)))
        refs (if html? (if context #{(:coordinates context)} #{}) (evidence-lines artifact))
        coord (html-evidence/coordinates artifact payload)
        uncertainties (concat (:unresolved-reasons payload) (get-in artifact [:publication :reasons]))
        invalid-fields (for [[k v] (:fields payload)
                             :when (and (#{:invalid :ambiguous} (:status v))
                                        (not (and (= k :unit) (= :time-unit-not-explicit (:reason v)))))] k)]
    (vec (distinct (concat
                    (when-not (= "result-row" (:kind observation)) [:not-result-row])
                    (when-not (= :parsed (:parse-status payload)) [:unparsed])
                    (when-not (nonblank? (:discipline fields)) [:missing-discipline-context])
                    (when-not (and (map? fields) (nonblank? (:source-name fields))) [:missing-source-name])
                    (when-not (or (some finite-number? ((juxt :performance :final-depth :final-distance :realized-distance) fields))
                                  (some time-notation? ((juxt :final-time :realized-time) fields))
                                  (#{"DNS" "DQ" "DSQ" "DNF"} (:status fields))) [:missing-performance-or-status])
                    (when-not (contains? refs coord) [:missing-source-context])
                    (when-not (some (fn [a] (let [m (:manifest a)]
                                              (and (http-url? (:final-url m)) (nonblank? (:publisher m))
                                                   (= (:source_sha256 observation) (:sha256 m))))) (:acquisitions artifact)) [:missing-source-citation])
                    (when (and html? (not= html-policy policy)) [:html-policy-required])
                    (when (and html? (not (nonblank? (:event-name context)))) [:missing-event-heading])
                    (when (and html? (not (nonblank? (:event-date context)))) [:missing-event-date])
                    (when (and html? (pos? (get-in artifact [:reconciliation :unsupported-table-count] 0))) [:unsupported-html-layout])
                    (when html? (concat (:context-errors context) (map #(vector :substantive-source-flag %) (:flags payload))))
                    (map (fn [x] [:unresolved-extraction-error x])
                         (remove (cond-> allowed-uncertainties
                                   (and html? (= html-policy policy)) (into #{:html-review-not-supported :coverage-not-established})) uncertainties))
                    (map (fn [x] [:invalid-field x]) invalid-fields))))))
(defn- active-policy [c]
  (:policy_version (first (query c "SELECT policy_version FROM freediving.publication_policy_events ORDER BY revision DESC LIMIT 1"))))
(defn activate-policy! [admin-url version reason]
  (when-not (and (nonblank? version) (nonblank? reason)) (fail! "Policy version and reason required"))
  (transaction admin-url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246915)")
                 (execute! c "INSERT INTO freediving.publication_policy_events(policy_version,reason) VALUES(?,?)" version reason)
                 {:policy-version (active-policy c)})))
(defn- diagnosis [c t]
  (let [s (state c t) active (active-policy c) reasons (blockers s active) last-decision (last (rows c "publication_decisions" t))
        policy (if (supported-policies active) active current-policy)
        eligible (and (contains? supported-policies active) (empty? reasons) (= "validate" (:action last-decision))
                      (= policy (:policy_version last-decision))
                      (= (:review-revision s) (:review_revision last-decision)))]
    {:policy-version policy :revision (or (:revision last-decision) 0)
     :review-revision (:review-revision s) :observation (provenance (:observation s))
     :ready? (and (contains? supported-policies active) (empty? reasons)) :active-policy-version active :eligible? (boolean eligible)
     :reasons (vec (concat reasons (when (not (contains? supported-policies active)) [:policy-inactive]) (cond (nil? last-decision) [:validation-required]
                                                                                                               (= "revoke" (:action last-decision)) [:validation-revoked]
                                                                                                               (not= policy (:policy_version last-decision)) [:policy-version-changed]
                                                                                                               (not= (:review-revision s) (:review_revision last-decision)) [:review-revision-changed])))}))
(defn- read-snapshot [url f]
  (with-open [c (connect url)]
    (.setTransactionIsolation c Connection/TRANSACTION_REPEATABLE_READ) (.setAutoCommit c false)
    (let [r (f c)] (.commit c) r)))
(defn diagnose [url t] (read-snapshot url #(diagnosis % t)))
(defn diagnose-many
  "Private read-only diagnostics in one snapshot; artifact decoding cached per job."
  [url targets]
  (binding [*artifacts* (atom {})]
    (read-snapshot url (fn [c] (mapv #(merge (select-keys % [:job-id :ordinal]) (diagnosis c %)) targets)))))
(defn history [url t] (read-snapshot url (fn [c] (target c t) (mapv body (rows c "publication_decisions" t)))))
(def request-keys #{:id :job-id :ordinal :base-revision :review-revision :policy-version :observation :action :actor :reason :evidence :attestations})
(defn decide! [url r]
  (when-not (and (map? r) (= request-keys (set (keys r)))
                 (every? nonblank? ((juxt :id :job-id :actor :reason :policy-version) r))
                 (every? nat-int? ((juxt :ordinal :base-revision :review-revision) r))
                 (#{:validate :revoke} (:action r))) (fail! "Invalid publication request"))
  (transaction url
               (fn [c]
                 (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.publication_decisions','INSERT') AS allowed")))
                   (fail! "Publication reviewer database capability required"))
                 (query c "SELECT pg_advisory_xact_lock(781246915)")
                 (query c "SELECT pg_advisory_xact_lock(hashtextextended(?,11))" (str (:job-id r) "/" (:ordinal r)))
                 (let [s (state c r)]
                   (when (html-evidence/html? (:artifact s)) (html-context! s)))
                 (if-let [old (first (query c "SELECT * FROM freediving.publication_decisions WHERE id=?" (:id r)))]
                   (do (when-not (= r (:request (body old))) (fail! "Conflicting idempotency key")) (body old))
                   (let [d (diagnosis c r) s (state c r)
                         html? (html-evidence/html? (:artifact s))
                         coord (html-evidence/coordinates (:artifact s) (:payload s))
                         refs (if html? #{(:coordinates (html-context! s))} (evidence-lines (:artifact s)))
                         page (get-in s [:payload :coordinates :page])]
                     (when-not (= (:base-revision r) (:revision d)) (fail! "Stale publication revision"))
                     (when-not (= (:review-revision r) (:review-revision d)) (fail! "Stale review revision"))
                     (when-not (= (:policy-version d) (:policy-version r)) (fail! "Stale policy version"))
                     (when-not (= (:observation r) (:observation d)) (fail! "Observation provenance mismatch"))
                     (when-not (and (vector? (:evidence r)) (seq (:evidence r))
                                    (some #{coord} (:evidence r))
                                    (every? #(and (= (if html? #{:table :row} #{:page :line}) (set (keys %)))
                                                  (or html? (= page (:page %))) (contains? refs %)) (:evidence r)))
                       (fail! "Invalid evidence references"))
                     (when (and (= :validate (:action r))
                                (not= {:source-visual-accuracy true :no-unresolved-substantive-errors true} (:attestations r)))
                       (fail! "Explicit source visual accuracy and substantive error attestations required"))
                     (when (and (= :validate (:action r)) (not (:ready? d)))
                       (throw (ex-info "Extraction not eligible for validation" {:reasons (:reasons d)})))
                     (let [record (assoc r :revision (inc (:revision d)) :request r)
                           o (:observation d)]
                       (execute! c "INSERT INTO freediving.publication_decisions(id,job_id,ordinal,revision,review_revision,policy_version,action,candidate_id,artifact_sha256,source_sha256,body_edn) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
                                 (:id r) (:job-id r) (:ordinal r) (:revision record) (:review-revision r) (:policy-version r) (name (:action r))
                                 (:candidate-id o) (:artifact-sha256 o) (:source-sha256 o) (encode record))
                       (body (first (query c "SELECT * FROM freediving.publication_decisions WHERE id=?" (:id r))))))))))

(defn- read-request [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (let [eof (Object.) value (edn/read {:eof eof} r)]
      (when (or (identical? eof value) (not (identical? eof (edn/read {:eof eof} r))))
        (fail! "Expected one EDN request")) value)))
(defn -main [& [command & args]]
  (try
    (let [url (System/getenv "FREEDIVING_DATABASE_URL")]
      (println (encode
                (case command
                  "migrate" (if (= 1 (count args)) (migrate! url (first args)) (fail! "migrate REVIEWER-ROLE"))
                  "activate-policy" (if (= 2 (count args)) (apply activate-policy! url args) (fail! "activate-policy VERSION REASON"))
                  ("diagnose" "decide" "history")
                  (if (= 1 (count args)) (({"diagnose" diagnose "decide" decide! "history" history} command) url (read-request (first args)))
                      (fail! "Expected one EDN request file"))
                  (fail! "Commands: migrate | activate-policy | diagnose | decide | history")))))
    (catch Exception e (binding [*out* *err*] (println "Publication operation failed:" (.getMessage e))) (System/exit 1))))
