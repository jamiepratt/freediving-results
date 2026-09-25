(ns freediving.revisions
  "Source-backed revision candidates and append-only authorized relationship reviews."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.source-scope :as source-scope])
  (:import [java.sql DriverManager Connection]
           [java.security MessageDigest]
           [java.util HexFormat]))
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
(defn migrate! [admin-url ingest-role reviewer-role]
  (doseq [role [ingest-role reviewer-role]]
    (when-not (and (string? role) (re-matches #"[a-z_][a-z0-9_]*" role)) (fail! "Invalid role")))
  (when (= ingest-role reviewer-role) (fail! "Separate reviewer role required"))
  (transaction admin-url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246914)")
                 (doseq [role [ingest-role reviewer-role]]
                   (let [r (first (query c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=?" role))]
                     (when (or (nil? r) (some true? (vals r))
                               (seq (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=?)" role))
                               (seq (query c "SELECT oid FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')" role))
                               (seq (query c "SELECT oid FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')" role))
                               (seq (query c "SELECT oid FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')" role)))
                       (fail! "Review roles must be restricted without ownership or memberships"))))
                 (let [sql (slurp (io/resource "migrations/008-revisions.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=8"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(8,?)" checksum))))
                 (execute! c (str "REVOKE ALL ON freediving.extractions,freediving.observations FROM " reviewer-role))
                 (doseq [role [ingest-role reviewer-role]]
                   (execute! c (str "REVOKE CREATE ON SCHEMA freediving FROM " role))
                   (execute! c (str "GRANT USAGE ON SCHEMA freediving TO " role))
                   (execute! c (str "REVOKE ALL ON freediving.revision_proposals,freediving.revision_decisions FROM " role))
                   (execute! c (str "GRANT SELECT ON freediving.extractions,freediving.observations,freediving.revision_proposals,freediving.revision_decisions TO " role))
                   (execute! c (str "GRANT INSERT ON freediving.revision_proposals TO " role)))
                 (execute! c (str "GRANT INSERT ON freediving.revision_decisions TO " reviewer-role))
                 {:schema-version 8})))
(defn- target [c {:keys [job-id ordinal]}]
  (or (first (query c "SELECT o.*,e.artifact_bytes,e.artifact_sha256,e.source_sha256 FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE job_id=? AND ordinal=?" job-id ordinal))
      (fail! "Unknown observation version")))
(defn- sha [^bytes bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- canonical [v]
  (cond (map? v) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) v))
        (sequential? v) (mapv canonical v) :else v))
(defn- candidate-id [artifact ordinal]
  (let [candidate (get (:candidates artifact) ordinal)
        position (if (= 4 (:schema-version artifact))
                   [(select-keys (:coordinates candidate) [:table :row])]
                   (or (when (seq (:source-lines candidate)) (mapv #(select-keys % [:page :line]) (:source-lines candidate)))
                       [(select-keys (:coordinates candidate) [:page :line])]))]
    (sha (.getBytes (encode (canonical [(:source-sha256 artifact) position])) "UTF-8"))))
(defn- envelope [row]
  {:job-id (:job_id row) :ordinal (:ordinal row) :candidate-id (:candidate_id row)
   :source-sha256 (:source_sha256 row) :artifact-sha256 (:artifact_sha256 row)})
(defn- verified-target [c ref]
  (let [row (target c ref) artifact (edn/read-string (String. ^bytes (:artifact_bytes row) "UTF-8"))]
    (when-not (and (= "result-row" (:kind row))
                   (= (:artifact_sha256 row) (sha (:artifact_bytes row)))
                   (= (:job_id row) (:job-id artifact))
                   (= (:candidate_id row) (candidate-id artifact (:ordinal row)))
                   (= (:source_sha256 row) (:source-sha256 artifact))
                   (= (edn/read-string (:payload_edn row)) (get (:candidates artifact) (:ordinal row))))
      (fail! "Observation provenance integrity mismatch"))
    {:reference (envelope row) :artifact artifact}))
(defn reference "Exact immutable source/version reference; validates stored artifact and row." [url target]
  (transaction url #(-> (verified-target % target) :reference)))
(defn- binding-value
  ([c binding] (binding-value c binding false))
  ([c binding revision-evidence?]
   (let [{:keys [reference path value]} binding
         target (verified-target c reference)
         html-scope? (and (= 2 (count path)) (= :html-scope (first path)))
         _ (when-not (or html-scope? (and (= :candidates (first path)) (= (:ordinal reference) (second path))
                                          (= :raw (nth path 2 nil)) (>= (count path) 4))
                         (and (= :pages (first path)) (nat-int? (second path))
                              (= :lines (nth path 2 nil)) (nat-int? (nth path 3 nil))
                              (= :text (nth path 4 nil)) (= 5 (count path)))
                         (and revision-evidence? (= 4 (count path))
                              (= :acquisitions (first path)) (nat-int? (second path))
                              (= [:manifest :final-url] (vec (drop 2 path)))))
             (fail! "Evidence path must address source text or the referenced source row"))
         absent (Object.) actual (if html-scope?
                                   (get (source-scope/html-values! (:artifact target) (:ordinal reference)) (second path) absent)
                                   (get-in (:artifact target) path absent))]
     (when-not (and (= #{:reference :path :value} (set (keys binding)))
                    (= reference (:reference target)) (vector? path) (seq path)
                    (not (identical? absent actual)) (= value actual))
       (fail! "Evidence provenance or path/value mismatch"))
     (when-not (and (or (string? value) (number? value)) (not (and (string? value) (str/blank? value))))
       (fail! "Source evidence values must be nonblank scalars")) value)))
(defn- descriptor [c d]
  (let [target (verified-target c (:reference d))
        html-values (when (= 4 (:schema-version (:artifact target)))
                      (source-scope/html-values! (:artifact target) (:ordinal (:reference d))))]
    (when-not (= (:reference d) (:reference target)) (fail! "Descriptor provenance mismatch"))
    (when-not (and (= (if (:scope-contract d) #{:reference :scope :scope-contract} #{:reference :scope}) (set (keys d)))
                   (or (not (contains? d :scope-contract)) (= :aida-date-view/v1 (:scope-contract d)))
                   (map? (:scope d))) (fail! "Invalid descriptor"))
    (when (:scope-contract d)
      (when-not (and html-values
                     (every? #(contains? (:scope d) %) source-scope/daily-fields)
                     (every? (set (concat source-scope/daily-fields [:source-name :event-name])) (keys (:scope d))))
        (fail! "Incomplete or unsupported daily scope"))
      (source-scope/daily-values! (:artifact target)))
    (into (if (:scope-contract d) {:scope-contract (:scope-contract d)} {})
          (for [[k b] (:scope d)]
            (do (when (and html-values
                           (not (or (= [:html-scope k] (:path b))
                                    (= [:candidates (:ordinal (:reference d)) :raw :fields
                                        ({:source-name "Diver" :discipline "Discipline" :category "Gender"} k)] (:path b)))))
                  (fail! "Unsupported typed HTML scope source"))
                (when (and (#{:bib :source-athlete-id :source-name :attempt} k) (not (or (= :candidates (first (:path b)))
                                                                                         (and (= :html-scope (first (:path b))) (= k (second (:path b)))))))
                  (fail! "Participant scope requires own-row source evidence"))
                (when (and (= :html-scope (first (:path b))) (not= k (second (:path b))))
                  (fail! "Typed scope field mismatch"))
                (when-not (= (:reference d) (:reference b)) (fail! "Scope provenance mismatch"))
                [k (let [v (binding-value c b)]
                     (when-not (and (or (string? v) (number? v)) (not (and (string? v) (str/blank? v))))
                       (fail! "Scope source values must be nonblank scalars")) v)])))))
(def event-fields [:federation :event-id :date :venue :discipline :category :round :session])
(def date-view-fields [:scope-contract :federation :event-id :date :discipline :category])
(defn scope-fields [scope]
  (if (= :aida-date-view/v1 (:scope-contract scope)) date-view-fields event-fields))
(defn event-scope [scope] (select-keys scope (scope-fields scope)))
(defn- present? [v] (and (some? v) (not (and (string? v) (str/blank? v)))))
(defn- match [a b]
  (let [fields (cond-> (scope-fields a) (or (contains? a :attempt) (contains? b :attempt)) (conj :attempt))
        scoped? (and (= (:scope-contract a) (:scope-contract b))
                     (every? #(and (present? (a %)) (= (a %) (b %))) fields))
        athlete? (some #(and (present? (a %)) (= (a %) (b %))) [:source-athlete-id :bib])
        conflicting? (some #(and (present? (a %)) (present? (b %)) (not= (a %) (b %))) [:source-athlete-id :bib])]
    (cond (and scoped? athlete? (not conflicting?)) :possible-revision
          (and scoped? (present? (:source-name a)) (= (:source-name a) (:source-name b))) :name-only
          :else :unmatched)))
(defn candidates
  "Read-only hints from explicit source bindings. Repeated scoped keys remain ambiguous. No ordering or changed-value inference."
  [url successor predecessors]
  (transaction url
               (fn [c]
                 (let [s (descriptor c successor)
                       rows (mapv (fn [p] {:predecessor p :successor successor :match (let [prior (descriptor c p)] (if (= (:reference p) (:reference successor)) :unmatched (match prior s)))}) predecessors)
                       ambiguous? (> (count (filter #(= :possible-revision (:match %)) rows)) 1)]
                   (mapv #(if (and ambiguous? (= :possible-revision (:match %))) (assoc % :match :ambiguous) %) rows)))))
(defn- nonblank? [s] (and (string? s) (not (str/blank? s))))
(defn- keys! [r allowed]
  (when-not (and (map? r) (every? allowed (keys r))) (fail! "Unexpected request fields")))
(defn- audit! [r]
  (when-not (and (every? nonblank? ((juxt :id :actor :reason) r)) (nat-int? (:base-revision r)))
    (fail! "ID actor reason and base-revision required")))
(defn- lock! [c] (query c "SELECT pg_advisory_xact_lock(781246918)"))
(defn- revision [c] (int (:revision (first (query c "SELECT COALESCE(MAX(revision),0) AS revision FROM freediving.revision_decisions")))))
(defn- body [row]
  (assoc (edn/read-string (:body_edn row)) :db-role (:db_role row) :recorded-at (str (:recorded_at row))))
(defn- stored [c table id]
  (first (query c (str "SELECT * FROM freediving." table " WHERE id=?") id)))
(declare checked-proposal state)
(defn- existing [c table r]
  (when-let [row (stored c table (:id r))]
    (if (= table "revision_proposals") (checked-proposal c row) (state c))
    (when-not (= r (:request (body row))) (fail! "Conflicting idempotency key")) (body row)))
(def proposal-keys #{:id :predecessor :successor :base-revision :actor :reason :mapping-rationale :revision-evidence})
(defn- validate-proposal! [c p]
  (keys! p proposal-keys) (audit! p)
  (when-not (nonblank? (:mapping-rationale p)) (fail! "Mapping rationale required"))
  (let [successor (descriptor c (:successor p)) predecessor (when (:predecessor p) (descriptor c (:predecessor p)))
        refs (set (keep :reference [(:successor p) (:predecessor p)]))]
    (when (= (:reference (:successor p)) (:reference (:predecessor p))) (fail! "Distinct observations required"))
    (when-not (and (vector? (:revision-evidence p)) (seq (:revision-evidence p))) (fail! "Revision evidence required"))
    (doseq [{:keys [kind binding] :as evidence} (:revision-evidence p)]
      (when-not (and (= #{:kind :binding} (set (keys evidence)))
                     (#{:report-id :version :revision-timestamp :correction-note :missing-source-notice} kind))
        (fail! "Typed revision evidence required"))
      (let [b binding]
        (when-not (contains? refs (:reference b)) (fail! "Revision evidence must reference relationship sources"))
        (binding-value c b true)))
    (if predecessor (match predecessor successor) :missing-predecessor)))
(defn propose! "Append an unapproved relationship; nil predecessor records unknown history." [url p]
  (transaction url (fn [c]
                     (lock! c) (audit! p)
                     (or (existing c "revision_proposals" p)
                         (let [status (validate-proposal! c p) s (get-in p [:successor :reference]) old (get-in p [:predecessor :reference])]
                           (when-not (= (:base-revision p) (revision c)) (fail! "Stale base revision"))
                           (when (= :unmatched status) (fail! "Unmatched event or attempt scope"))
                           (execute! c "INSERT INTO freediving.revision_proposals(id,successor_job,successor_ordinal,predecessor_job,predecessor_ordinal,body_edn) VALUES(?,?,?,?,?,?)"
                                     (:id p) (:job-id s) (:ordinal s) (:job-id old) (:ordinal old)
                                     (encode {:id (:id p) :request p :status (if (= status :missing-predecessor) :revised-only :possible-revision)
                                              :match status :previous-values (if old :not-inferred :unknown)}))
                           (existing c "revision_proposals" p))))))
(defn- checked-proposal [c row]
  (when-not row (fail! "Unknown proposal"))
  (let [b (body row) p (:request b) s (get-in p [:successor :reference]) old (get-in p [:predecessor :reference])
        match (validate-proposal! c p)]
    (when-not (and (= (:id row) (:id b) (:id p))
                   (= [(:successor_job row) (:successor_ordinal row)] [(:job-id s) (:ordinal s)])
                   (= [(:predecessor_job row) (:predecessor_ordinal row)] [(:job-id old) (:ordinal old)])
                   (= (:match b) match)
                   (= (:status b) (if old :possible-revision :revised-only))
                   (= (:previous-values b) (if old :not-inferred :unknown)))
      (fail! "Proposal envelope or provenance mismatch")) b))
(defn- decision-rows [c] (query c "SELECT * FROM freediving.revision_decisions ORDER BY revision"))
(defn- state [c]
  (let [ps (mapv #(checked-proposal c %) (query c "SELECT * FROM freediving.revision_proposals ORDER BY recorded_at,id"))
        by-id (into {} (map (juxt :id identity) ps))]
    {:revision (revision c)
     :relationships
     (->> (reduce (fn [m row]
                    (let [d (body row) p (:proposal_id row)]
                      (when-not (and (= (:id row) (:id d)) (= p (:proposal-id d))
                                     (= (:revision row) (:revision d)) (= (:action row) (name (:action d)))
                                     (= (:event_id row) (:event-id d))
                                     (= (:request d) (dissoc d :request :revision :db-role :recorded-at))) (fail! "Decision envelope mismatch"))
                      (assoc-in m [p :status] (case (:action d) :confirm :confirmed-replacement
                                                    :reject :rejected :reverse :reversed :acknowledge-missing :reviewed-missing-history))))
                  by-id (decision-rows c)) vals (sort-by :id) vec)}))
(defn diagnostics "Read-only snapshot. No publication authorization or implicit suppression; previous values are never fabricated." [url]
  (with-open [c (connect url)]
    (.setTransactionIsolation c Connection/TRANSACTION_REPEATABLE_READ)
    (.setAutoCommit c false)
    (let [s (state c)] (.commit c) s)))
(defn history [url id]
  (transaction url (fn [c]
                     (let [_ (state c) p (checked-proposal c (stored c "revision_proposals" id))]
                       (into [p] (map body (query c "SELECT * FROM freediving.revision_decisions WHERE proposal_id=? ORDER BY revision" id)))))))
(defn decide! "Explicit owner review. Global CAS serializes conflicting links; only active decisions can be reversed." [url r]
  (keys! r #{:id :proposal-id :event-id :base-revision :action :actor :reason}) (audit! r)
  (transaction url (fn [c]
                     (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.revision_decisions','INSERT') AS allowed")))
                       (fail! "Owner review database capability required"))
                     (lock! c)
                     (or (existing c "revision_decisions" r)
                         (let [p (checked-proposal c (stored c "revision_proposals" (:proposal-id r)))
                               s (state c) action (:action r)
                               ds (filter #(= (:proposal_id %) (:id p)) (decision-rows c))]
                           (when-not (= (:base-revision r) (:revision s)) (fail! "Stale base revision"))
                           (when-not (#{:confirm :reject :reverse :acknowledge-missing} action) (fail! "Invalid decision action"))
                           (if (= :reverse action)
                             (when-not (and (= 1 (count ds)) (= (:event-id r) (:id (first ds)))
                                            (#{"confirm" "reject" "acknowledge-missing"} (:action (first ds))))
                               (fail! "Only active unreversed decisions can be reversed"))
                             (do (when (:event-id r) (fail! "Unexpected event-id"))
                                 (when (seq ds) (fail! "Proposal already decided"))))
                           (when (= :confirm action)
                             (when-not (= :possible-revision (:match p)) (fail! "Confirmation requires scoped predecessor; name-only and missing history cannot confirm"))
                             (let [request (:request p) refs (set (map :reference [(:predecessor request) (:successor request)]))]
                               (when (some (fn [other]
                                             (and (= :confirmed-replacement (:status other))
                                                  (some refs (map :reference [(get-in other [:request :predecessor]) (get-in other [:request :successor])]))))
                                           (:relationships s))
                                 (fail! "Conflicting active replacement relationship"))))
                           (when (and (= :acknowledge-missing action) (not= :missing-predecessor (:match p))) (fail! "Missing predecessor required"))
                           (let [record (assoc r :request r :revision (inc (:revision s)))]
                             (execute! c "INSERT INTO freediving.revision_decisions(id,proposal_id,revision,action,event_id,body_edn) VALUES(?,?,?,?,?,?)"
                                       (:id r) (:proposal-id r) (:revision record) (name action) (:event-id r) (encode record))
                             (existing c "revision_decisions" r)))))))

(defn descriptor-values [connection d] (descriptor connection d))
(defn snapshot-on [connection] (state connection))
