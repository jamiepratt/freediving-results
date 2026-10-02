(ns freediving.reviews
  "Append-only local owner review. DB reviewer credentials are the authority; actor is audit text."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.aida-html :as html]
            [freediving.html-evidence :as html-evidence]
            [freediving.candidates :as candidates]
            [freediving.reconciliation-flow :as reconciliation-flow]
            [freediving.reconciliation-policy :as reconciliation-policy]
            [freediving.reconciliation-jev :as reconciliation-jev])
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
                 (let [sql (slurp (io/resource "migrations/002-reviews.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=2"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(2,?)" checksum))))
                 (let [sql (slurp (io/resource "migrations/012-extraction-reviews.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=12"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Extraction review migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(12,?)" checksum))))
                 (let [sql (slurp (io/resource "migrations/013-pdf-extraction-reviews.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=13"))]
                     (when-not (= checksum (:sha256 old)) (fail! "PDF extraction review migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(13,?)" checksum))))
                 (let [sql (slurp (io/resource "migrations/014-dive-field-decisions.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=14"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Dive field migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(14,?)" checksum))))
                 (let [sql (slurp (io/resource "migrations/015-athlete-identity.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=15"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Athlete identity migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(15,?)" checksum))))
                 (let [sql (slurp (io/resource "migrations/016-reconciliation-model-events.sql"))
                       checksum (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes sql "UTF-8")))]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=16"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Model reconciliation migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(16,?)" checksum))))
                 (execute! c "DROP TRIGGER stamp_dive_field_decisions ON freediving.dive_field_decisions")
                 (execute! c (str "CREATE TRIGGER stamp_dive_field_decisions BEFORE INSERT ON freediving.dive_field_decisions FOR EACH ROW EXECUTE FUNCTION freediving.stamp_dive_field_decision('" ingest-role "','" reviewer-role "')"))
                 (execute! c "DROP TRIGGER stamp_athlete_identity_event ON freediving.athlete_identity_events")
                 (execute! c (str "CREATE TRIGGER stamp_athlete_identity_event BEFORE INSERT ON freediving.athlete_identity_events FOR EACH ROW EXECUTE FUNCTION freediving.stamp_athlete_identity_event('" ingest-role "','" reviewer-role "')"))
                 (execute! c (str "REVOKE ALL ON freediving.extractions,freediving.observations FROM " reviewer-role))
                 (doseq [role [ingest-role reviewer-role]]
                   (execute! c (str "REVOKE CREATE ON SCHEMA freediving FROM " role))
                   (execute! c (str "GRANT USAGE ON SCHEMA freediving TO " role))
                   (execute! c (str "REVOKE ALL ON freediving.review_proposals,freediving.review_decisions FROM " role))
                   (execute! c (str "REVOKE ALL ON freediving.extraction_reviews FROM " role))
                   (execute! c (str "REVOKE ALL ON freediving.pdf_extraction_reviews FROM " role))
                   (execute! c (str "REVOKE ALL ON freediving.dive_field_decisions FROM " role))
                   (execute! c (str "REVOKE ALL ON freediving.athlete_identity_events FROM " role))
                   (execute! c (str "GRANT SELECT ON freediving.extractions,freediving.observations,freediving.review_proposals,freediving.review_decisions TO " role))
                   (execute! c (str "GRANT SELECT ON freediving.extraction_reviews TO " role))
                   (execute! c (str "GRANT SELECT ON freediving.pdf_extraction_reviews TO " role))
                   (execute! c (str "GRANT SELECT,INSERT ON freediving.dive_field_decisions TO " role))
                   (execute! c (str "GRANT SELECT,INSERT ON freediving.athlete_identity_events TO " role))
                   (execute! c (str "GRANT INSERT ON freediving.review_proposals TO " role)))
                 (execute! c (str "GRANT INSERT ON freediving.review_decisions TO " reviewer-role))
                 (execute! c (str "GRANT INSERT ON freediving.extraction_reviews TO " reviewer-role))
                 (execute! c (str "GRANT INSERT ON freediving.pdf_extraction_reviews TO " reviewer-role))
                 {:schema-version 2})))
(defn- target [c {:keys [job-id ordinal]}]
  (or (first (query c "SELECT o.*,e.artifact_bytes,e.artifact_sha256,e.source_sha256,e.parser_version FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE job_id=? AND ordinal=?" job-id ordinal))
      (fail! "Unknown observation version")))
(defn- body [row]
  (assoc (edn/read-string (:body_edn row)) :id (:id row) :job-id (:job_id row) :ordinal (:ordinal row) :db-role (:db_role row) :recorded-at (str (:recorded_at row))))
(defn- proposals [c {:keys [job-id ordinal]}]
  (mapv body (query c "SELECT * FROM freediving.review_proposals WHERE job_id=? AND ordinal=? ORDER BY recorded_at,id" job-id ordinal)))
(defn- decisions [c {:keys [job-id ordinal]}]
  (mapv body (query c "SELECT * FROM freediving.review_decisions WHERE job_id=? AND ordinal=? ORDER BY revision" job-id ordinal)))
(defn- snapshot [c t]
  (let [o (target c t) original (:parsed (edn/read-string (:payload_edn o)))
        ps (into {} (map (juxt :id identity) (proposals c t)))
        ds (decisions c t)]
    (reduce (fn [s d]
              (let [s (assoc s :revision (:revision d))]
                (case (:action d)
                  :approve (let [p (ps (:proposal-id d)) f (:field p)]
                             (-> s (assoc-in [:active f] (:id d))
                                 (assoc-in (if (= f :identity) [:identity] [:fields f]) (:after p))))
                  :reverse (let [approved (first (filter #(= (:event-id d) (:id %)) ds))
                                 p (ps (:proposal-id approved)) f (:field p)]
                             (-> s (assoc-in [:active f] (:prior-event approved))
                                 (assoc-in (if (= f :identity) [:identity] [:fields f]) (:before p))))
                  :reject s)))
            {:revision 0 :identity {:outcome :unknown} :fields original :active {}} ds)))
(defn- read-snapshot [url f]
  (with-open [c (connect url)]
    (.setTransactionIsolation c Connection/TRANSACTION_REPEATABLE_READ)
    (.setAutoCommit c false)
    (let [result (f c)] (.commit c) result)))
(defn effective [url t] (read-snapshot url #(snapshot % t)))
(defn history [url t]
  (read-snapshot url (fn [c] (target c t) (vec (concat (proposals c t) (decisions c t))))))
(defn- nonblank? [v] (and (string? v) (not (str/blank? v))))
(def ^:private json-reference-keys
  #{:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256
    :parser-version :source-page-url :row-index-zero-based})
(defn- sha256 [^bytes bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- json-reference! [c ref]
  (when-not (and (map? ref) (= json-reference-keys (set (keys ref)))
                 (every? nonblank? ((juxt :job-id :candidate-id :source-sha256
                                          :artifact-sha256 :parser-version :source-page-url) ref))
                 (nat-int? (:ordinal ref)) (nat-int? (:row-index-zero-based ref)))
    (fail! "Invalid JSON evidence reference"))
  (let [o (target c ref)
        bytes ^bytes (:artifact_bytes o)
        artifact (edn/read-string (String. bytes "UTF-8"))
        candidate (get (:candidates artifact) (:ordinal ref))
        payload (edn/read-string (:payload_edn o))
        raw-json (:raw-json artifact)
        source (when (string? raw-json)
                 (try (json/read-str raw-json) (catch Exception _ nil)))
        source-rows (case (:parser-version artifact)
                      "cmas-2026-roatan-json/1" (when (vector? source) source)
                      "cmas-2026-roatan-json/2" (when (vector? source) source)
                      (when (map? source) (get source "data")))
        position (:row-index-zero-based ref)
        coordinate {:row-index-zero-based position}
        acquired-pages (set (map #(get-in % [:manifest :provenance :source-page-url])
                                 (:acquisitions artifact)))]
    (when-not (and (= 5 (:schema-version artifact))
                   (or (re-matches #"cmas-2025-indoor-json/[0-9]+" (:parser-version artifact))
                       (#{"cmas-2026-roatan-json/1" "cmas-2026-roatan-json/2"} (:parser-version artifact)))
                   (= (:parser-version ref) (:parser-version artifact))
                   (= (:job-id ref) (:job-id artifact))
                   (= (:job-id ref) (html/digest (select-keys artifact
                                                              [:source-sha256 :acquisitions :evidence-sha256
                                                               :actor :config :parser-version :schema-version :tool])))
                   (= (:artifact-sha256 ref) (:artifact_sha256 o) (sha256 bytes))
                   (= (:source-sha256 ref) (:source_sha256 o) (:source-sha256 artifact)
                      (when raw-json (sha256 (.getBytes ^String raw-json "UTF-8"))))
                   (= (:candidate-id ref) (:candidate_id o)
                      (html/digest [(:source-sha256 ref) [coordinate]]))
                   (= #{(:source-page-url ref)} acquired-pages)
                   (= (:source-page-url ref) (:view-url artifact) (:source-page-url artifact)
                      (:source-page-url candidate) (:source-page-url payload))
                   (= coordinate (select-keys (:coordinates candidate) [:row-index-zero-based])
                      (select-keys (:coordinates payload) [:row-index-zero-based]))
                   (= candidate payload)
                   (vector? source-rows)
                   (< position (count source-rows))
                   (= (:raw payload) (get source-rows position))
                   (= "result-row" (:kind o)))
      (fail! "JSON evidence provenance or source position mismatch"))
    o))
(defn- audit! [r]
  (when-not (and (every? nonblank? ((juxt :id :actor :reason) r)) (nat-int? (:base-revision r)))
    (fail! "ID, actor, reason and base-revision required")))
(defn- current-value [s field] (if (= field :identity) (:identity s) (get-in s [:fields field])))
(defn- identity-value? [v]
  (or (= v {:outcome :unknown}) (= v {:outcome :no-match})
      (and (= #{:outcome :identity-id} (set (keys v))) (= :matched (:outcome v)) (nonblank? (:identity-id v)))))
(def reference-keys #{:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256 :page :line})
(defn- page-lines [o]
  (let [bytes (:artifact_bytes o)
        a (edn/read-string (String. ^bytes bytes "UTF-8"))]
    (if (html-evidence/html? a)
      (let [payload (edn/read-string (:payload_edn o))
            context (html-evidence/bound-context! a payload (:ordinal o) (:source_sha256 o))]
        (when-not (and (= (:artifact_sha256 o) (html-evidence/sha256 bytes))
                       (= (:job_id o) (:job-id a) (html/digest (select-keys a html/identity-keys)))
                       (= (:candidate_id o) (html/digest [(:source_sha256 o) [(:coordinates context)]])))
          (fail! "HTML observation envelope mismatch"))
        #{(:coordinates context)})
      (set (for [page (:pages a) line (:lines page)] {:page (:page page) :line (:line line)})))))
(defn- registered-reference! [c ref]
  (if (= json-reference-keys (set (keys ref)))
    (json-reference! c ref)
    (do
      (when-not (and (map? ref) (or (= reference-keys (set (keys ref)))
                                    (= (into (disj reference-keys :page :line) [:table :row]) (set (keys ref))))
                     (every? nonblank? ((juxt :job-id :candidate-id :source-sha256 :artifact-sha256) ref))
                     (nat-int? (:ordinal ref)) (every? pos-int? (if (contains? ref :table) ((juxt :table :row) ref) ((juxt :page :line) ref))))
        (fail! "Invalid registered evidence reference"))
      (let [o (target c ref) payload (edn/read-string (:payload_edn o))
            pages (set (conj (mapv :page (:source-lines payload)) (get-in payload [:coordinates :page])))]
        (when-not (and (= (:candidate-id ref) (:candidate_id o))
                       (= (:source-sha256 ref) (:source_sha256 o))
                       (= (:artifact-sha256 ref) (:artifact_sha256 o))
                       (or (contains? ref :table) (contains? pages (:page ref)))
                       (contains? (page-lines o) (select-keys ref (if (contains? ref :table) [:table :row] [:page :line]))))
          (fail! "Registered evidence provenance or coordinates mismatch"))
        o))))
(defn- validate-proposal! [c p s]
  (audit! p)
  (let [o (target c p) refs (page-lines o)
        f (:field p)]
    (when-not (= "result-row" (:kind o)) (fail! "Review target must be a result-row"))
    (when-not (and (vector? (:evidence p)) (seq (:evidence p))) (fail! "Invalid evidence references"))
    (doseq [ref (:evidence p)]
      (if (and (map? ref) (#{#{:page :line} #{:table :row}} (set (keys ref))))
        (when-not (contains? refs ref) (fail! "Invalid evidence references"))
        (registered-reference! c ref)))
    (when (and (= f :identity) (= :matched (get-in p [:after :outcome]))
               (string? (get-in p [:after :identity-id]))
               (str/starts-with? (get-in p [:after :identity-id]) "local-observation:")
               (not (contains? p :identity-target)))
      (fail! "Registered identity target required for local anchor"))
    (when (contains? p :identity-target)
      (let [ref (:identity-target p) anchor (registered-reference! c ref)
            name (get-in (edn/read-string (:payload_edn anchor)) [:parsed :source-name])]
        (when-not (and (= f :identity) (= "result-row" (:kind anchor)) (nonblank? name)
                       (some #{ref} (:evidence p))
                       (= :matched (get-in p [:after :outcome]))
                       (= (str "local-observation:" (:job-id ref) ":" (:ordinal ref))
                          (get-in p [:after :identity-id])))
          (fail! "Invalid identity target anchor"))))
    (when-not (= (:base-revision p) (:revision s)) (fail! "Stale base revision"))
    (when-not (and (contains? p :before) (= (:before p) (current-value s f))) (fail! "Before value differs from effective state"))
    (when-not (and (contains? p :after)
                   (or (not= (:before p) (:after p))
                       (and (= f :identity) (:jev-score p)
                            (= {:outcome :unknown} (:before p) (:after p)))))
      (fail! "Changed after value required"))
    (when-not (if (= f :identity)
                (and (= :identity-matching (:category p)) (identity-value? (:after p)))
                (and (keyword? f) (contains? (:fields s) f)
                     (#{:name-normalization :extraction-repair :substantive-correction} (:category p))
                     (or (not= :name-normalization (:category p)) (= :source-name f))
                     (not (coll? (:before p))) (not (coll? (:after p)))))
      (fail! "Invalid category, scalar field or identity outcome"))))
(defn- lock! [c t] (query c "SELECT pg_advisory_xact_lock(hashtextextended(?, 11))" (str (:job-id t) "/" (:ordinal t))))
(defn- existing [c table id request]
  (when-let [r (first (query c (str "SELECT * FROM freediving." table " WHERE id=?") id))]
    (when-not (= request (:request (edn/read-string (:body_edn r)))) (fail! "Conflicting idempotency key")) (body r)))
(defn- keys! [request allowed]
  (when-not (and (map? request) (every? allowed (keys request))) (fail! "Unexpected request fields")))
(def proposal-keys #{:id :job-id :ordinal :base-revision :category :field :before :after :evidence :reason :actor :identity-target :jev-score :inspection})
(def ^:dynamic *scored-root* nil)
(def ^:private score-selector-keys #{:run-id :provider-id :case-id :result-hash})
(def ^:private inspection-acknowledgement
  {:both-versions-reviewed true :contrary-evidence-reviewed true :source-dependence-reviewed true})
(declare propose!)
(defn- current-source-version? [c ref]
  (= (:job-id ref)
     (:job_id (first (query c
                            "SELECT job_id FROM freediving.extractions WHERE source_sha256=? ORDER BY imported_at DESC, job_id DESC LIMIT 1"
                            (:source-sha256 ref))))))
(defn- inbound-approved-link? [c t]
  (let [anchor (str "local-observation:" (:job-id t) ":" (:ordinal t))]
    (boolean
     (some (fn [row]
             (let [source {:job-id (:job_id row) :ordinal (:ordinal row)}]
               (and (not= source t)
                    (= anchor (get-in (snapshot c source) [:identity :identity-id])))))
           (query c "SELECT DISTINCT job_id,ordinal FROM freediving.review_proposals")))))
(defn- score-binding! [c root url p]
  (let [selector (:jev-score p)
        _ (when-not (and (nonblank? root) (map? selector)
                         (= score-selector-keys (set (keys selector)))
                         (every? nonblank? (vals selector))
                         (= inspection-acknowledgement (:inspection p))
                         (= :identity (:field p)) (= :identity-matching (:category p)))
            (fail! "Exact Jev score and owner inspection acknowledgement required"))
        refs (:evidence p)
        _ (when-not (and (vector? refs) (= 2 (count refs))
                         (every? map? refs) (every? :job-id refs)
                         (= 2 (count (set (map #(select-keys % [:job-id :ordinal]) refs)))))
            (fail! "Both registered observation references required"))
        target-ref (some #(when (= (select-keys p [:job-id :ordinal])
                                   (select-keys % [:job-id :ordinal])) %) refs)
        other-ref (first (remove #(= target-ref %) refs))
        _ (when-not (and target-ref other-ref) (fail! "Score target and candidate references required"))
        _ (doseq [ref refs] (registered-reference! c ref))
        _ (when-not (every? #(current-source-version? c %) refs)
            (fail! "Newer observation version requires score reinspection"))
        _ (when-not (or (and (= :matched (get-in p [:after :outcome]))
                             (= other-ref (:identity-target p)))
                        (and (#{:no-match :unknown} (get-in p [:after :outcome]))
                             (not (contains? p :identity-target))))
            (fail! "Identity target must be the scored candidate anchor"))
        rows (candidates/load-corpus url {})
        score-view (requiring-resolve 'freediving.spelling-normalization/score-view)
        case-builder (requiring-resolve 'freediving.jev-candidates/candidate-case)
        scores (score-view root (:run-id selector) (:provider-id selector)
                           (select-keys p [:job-id :ordinal]) rows)
        score (first (filter #(= (:case-id selector) (:case-id %)) scores))
        left (:target-reference score) right (:candidate-reference score)
        _ (when-not (and score (= :complete (:score-status score))
                         (number? (get-in score [:identity :confidence]))
                         (= #{:match :no_match :abstain}
                            (set (keys (get-in score [:identity :probabilities]))))
                         (every? number? (vals (get-in score [:identity :probabilities])))
                         (= (:result-hash selector) (:result-hash score))
                         (= #{target-ref other-ref} #{left right})
                         (= (:case-id selector)
                            (:case-id (case-builder url rows
                                                    (select-keys left [:job-id :ordinal])
                                                    (select-keys right [:job-id :ordinal])))))
            (fail! "Stale or mismatched stored Jev score"))
        candidate (snapshot c (select-keys other-ref [:job-id :ordinal]))
        _ (when (inbound-approved-link? c (select-keys p [:job-id :ordinal]))
            (fail! "Conflicting identity merge: target is an approved anchor"))
        _ (when (and (= :matched (get-in p [:after :outcome]))
                     (= :matched (get-in candidate [:identity :outcome]))
                     (not= (get-in candidate [:identity :identity-id])
                           (get-in p [:after :identity-id])))
            (fail! "Conflicting identity merge"))]
    {:root root :selector selector :score-status :complete :score score
     :target-reference target-ref :candidate-reference other-ref
     :candidate-revision (:revision candidate) :candidate-identity (:identity candidate)}))
(defn propose-scored-identity! [root url p]
  (when-not (nonblank? root) (fail! "Configured Jev run root required"))
  (binding [*scored-root* root] (propose! url p)))
(defn propose! [url p]
  (keys! p proposal-keys)
  (when (and (:jev-score p) (not *scored-root*)) (fail! "Configured Jev score verifier required"))
  (when (and (not (:jev-score p)) (contains? p :inspection)) (fail! "Unexpected inspection acknowledgement"))
  (transaction url
               (fn [c]
                 (audit! p) (lock! c p)
                 (page-lines (target c p))
                 (doseq [ref (:evidence p) :when (contains? ref :job-id)] (registered-reference! c ref))
                 (or (existing c "review_proposals" (:id p) p)
                     (let [s (snapshot c p) _ (validate-proposal! c p s) o (target c p)
                           binding (when (:jev-score p) (score-binding! c *scored-root* url p))
                           record (assoc p :action :propose :request p
                                         :observation {:job-id (:job-id p) :ordinal (:ordinal p)
                                                       :candidate-id (:candidate_id o)
                                                       :source-sha256 (:source_sha256 o)
                                                       :artifact-sha256 (:artifact_sha256 o)})
                           record (cond-> record binding (assoc :score-binding binding))]
                       (execute! c "INSERT INTO freediving.review_proposals(id,job_id,ordinal,body_edn) VALUES(?,?,?,?)"
                                 (:id p) (:job-id p) (:ordinal p) (encode record))
                       (existing c "review_proposals" (:id p) p))))))
(defn decide! [url request]
  (keys! request (conj #{:id :base-revision :action :actor :reason}
                       (if (= :reverse (:action request)) :event-id :proposal-id)))
  (transaction url
               (fn [c]
                 (audit! request)
      ;; Permission check happens even for idempotent retries through a read-only/ingest connection.
                 (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.review_decisions','INSERT') AS allowed")))
                   (fail! "Owner review database capability required"))
                 (let [action (:action request)
                       row (first (case action
                                    (:approve :reject) (query c "SELECT * FROM freediving.review_proposals WHERE id=?" (:proposal-id request))
                                    :reverse (query c "SELECT * FROM freediving.review_decisions WHERE id=?" (:event-id request))
                                    (fail! "Invalid decision action")))
                       _ (when-not row (fail! "Unknown proposal or event"))
                       t {:job-id (:job_id row) :ordinal (:ordinal row)}
                       raw (edn/read-string (:body_edn row))
                       _ (when-not (= (select-keys raw [:id :job-id :ordinal]) (assoc t :id (:id row)))
                           (fail! "Proposal or event envelope mismatch"))]
                   (lock! c t)
                   ;; Serialize identity changes that may touch different observation rows.
                   (query c "SELECT pg_advisory_xact_lock(781246918)")
                   (page-lines (target c t))
                   (or (existing c "review_decisions" (:id request) request)
                       (let [s (snapshot c t) subject (body row) ds (decisions c t)
                             _ (when-not (= (:base-revision request) (:revision s)) (fail! "Stale base revision"))
                             p (if (= action :reverse)
                                 (first (filter #(= (:proposal-id subject) (:id %)) (proposals c t))) subject)
                             field (:field p)]
                         (when (and (= action :approve) (:jev-score p) (not (:score-binding p)))
                           (fail! "Scored proposal binding missing"))
                         (when (and (= action :approve) (:score-binding p))
                           (let [binding (:score-binding p)
                                 current (score-binding! c (:root binding) url p)]
                             (when-not (= (dissoc current :root) (dissoc binding :root))
                               (fail! "Stale scored proposal; inspect both observation versions again"))))
                         (if (= action :reverse)
                           (when-not (and (= :approve (:action subject)) (= (:id subject) (get-in s [:active field]))
                                          (not-any? #(= (:id subject) (:event-id %)) ds))
                             (fail! "Only an active unreversed approval can be reversed"))
                           (do
                             (when (some #(= (:id p) (:proposal-id %)) ds) (fail! "Proposal already decided"))
                             (when (= action :approve)
                               (let [o (target c t)
                                     provenance {:job-id (:job-id t) :ordinal (:ordinal t)
                                                 :candidate-id (:candidate_id o)
                                                 :source-sha256 (:source_sha256 o)
                                                 :artifact-sha256 (:artifact_sha256 o)}]
                                 (when-not (= provenance (:observation p)) (fail! "Proposal provenance mismatch")))
                               (validate-proposal! c p s))))
                         (let [record (merge request t {:revision (inc (:revision s)) :request request
                                                        :field field
                                                        :before (if (= action :reverse) (:after p) (current-value s field))
                                                        :after (case action
                                                                 :approve (:after p)
                                                                 :reverse (:before p)
                                                                 :reject (current-value s field))
                                                        :evidence (:evidence p)}
                                             (when (= action :approve) {:prior-event (get-in s [:active field])}))]
                           (execute! c "INSERT INTO freediving.review_decisions(id,job_id,ordinal,revision,action,proposal_id,event_id,body_edn) VALUES(?,?,?,?,?,?,?,?)"
                                     (:id request) (:job-id t) (:ordinal t) (:revision record) (name action)
                                     (when (not= action :reverse) (:proposal-id request)) (when (= action :reverse) (:event-id request)) (encode record))
                           (existing c "review_decisions" (:id request) request))))))))
(defn- extraction-events [c t]
  (mapv body (query c "SELECT * FROM freediving.extraction_reviews WHERE job_id=? AND ordinal=? ORDER BY revision"
                    (:job-id t) (:ordinal t))))
(defn- extraction-state [c t]
  (target c t)
  (reduce (fn [_ event]
            (case (:action event)
              :accept {:revision (:revision event) :status :accepted :active-event (:id event)}
              :revoke {:revision (:revision event) :status :unreviewed :active-event nil}))
          {:revision 0 :status :unreviewed :active-event nil}
          (extraction-events c t)))
(defn extraction-effective [url t]
  (read-snapshot url #(extraction-state % t)))
(defn extraction-history [url t]
  (read-snapshot url (fn [c] (target c t) (extraction-events c t))))
(defn- extraction-capability! [c]
  (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.extraction_reviews','INSERT') AS allowed")))
    (fail! "Owner extraction review database capability required")))
(defn- extraction-request! [request]
  (when-not (and (map? request)
                 (= #{:id :job-id :ordinal :base-revision :evidence :owner-receipt-sha256
                      :owner-response :actor :reason}
                    (set (keys request)))
                 (every? nonblank? ((juxt :id :job-id :actor :reason) request))
                 (nat-int? (:ordinal request)) (nat-int? (:base-revision request))
                 (string? (:owner-receipt-sha256 request))
                 (re-matches #"[0-9a-f]{64}" (:owner-receipt-sha256 request))
                 (= #{:task-id :user-message-id :response-annotation-index :selected-text}
                    (set (keys (:owner-response request))))
                 (every? nonblank? ((juxt :task-id :user-message-id :selected-text)
                                    (:owner-response request)))
                 (nat-int? (get-in request [:owner-response :response-annotation-index]))
                 (= (select-keys request [:job-id :ordinal])
                    (select-keys (:evidence request) [:job-id :ordinal]))
                 (when-let [[_ from to] (re-matches #"Accept ([0-9]+)-([0-9]+)"
                                                    (get-in request [:owner-response :selected-text]))]
                   (<= (parse-long from) (get-in request [:evidence :row-index-zero-based])
                       (parse-long to))))
    (fail! "Invalid owner extraction acceptance request")))
(defn accept-extraction! [url request]
  (extraction-request! request)
  (transaction url
               (fn [c]
                 (extraction-capability! c)
                 (lock! c request)
                 (json-reference! c (:evidence request))
                 (or (existing c "extraction_reviews" (:id request) request)
                     (let [state (extraction-state c request)]
                       (when-not (= (:base-revision request) (:revision state))
                         (fail! "Stale extraction review revision"))
                       (when (= :accepted (:status state))
                         (fail! "Extraction position already accepted"))
                       (let [record (assoc request :action :accept :revision (inc (:revision state))
                                           :request request)]
                         (execute! c "INSERT INTO freediving.extraction_reviews(id,job_id,ordinal,revision,action,event_id,body_edn) VALUES(?,?,?,?,?,?,?)"
                                   (:id request) (:job-id request) (:ordinal request) (:revision record)
                                   "accept" nil (encode record))
                         (existing c "extraction_reviews" (:id request) request)))))))
(defn revoke-extraction! [url request]
  (when-not (and (map? request)
                 (= #{:id :job-id :ordinal :base-revision :event-id :actor :reason}
                    (set (keys request)))
                 (every? nonblank? ((juxt :id :job-id :event-id :actor :reason) request))
                 (nat-int? (:ordinal request)) (nat-int? (:base-revision request)))
    (fail! "Invalid extraction revocation request"))
  (transaction url
               (fn [c]
                 (extraction-capability! c)
                 (lock! c request)
                 (or (existing c "extraction_reviews" (:id request) request)
                     (let [state (extraction-state c request)]
                       (when-not (= (:base-revision request) (:revision state))
                         (fail! "Stale extraction review revision"))
                       (when-not (= (:event-id request) (:active-event state))
                         (fail! "Only the active extraction acceptance can be revoked"))
                       (let [record (assoc request :action :revoke :revision (inc (:revision state))
                                           :request request)]
                         (execute! c "INSERT INTO freediving.extraction_reviews(id,job_id,ordinal,revision,action,event_id,body_edn) VALUES(?,?,?,?,?,?,?)"
                                   (:id request) (:job-id request) (:ordinal request) (:revision record)
                                   "revoke" (:event-id request) (encode record))
                         (existing c "extraction_reviews" (:id request) request)))))))

(def ^:private pdf-reference-keys
  #{:source-kind :schema-version :job-id :ordinal :acquisition-id :source-sha256
    :artifact-sha256 :parser-version :candidate-id :observation-id :page :line})
(defn- pdf-reference! [c ref]
  (when-not (and (map? ref) (= pdf-reference-keys (set (keys ref)))
                 (= :pdf (:source-kind ref)) (#{1 2 3} (:schema-version ref))
                 (every? nonblank? ((juxt :job-id :acquisition-id :source-sha256
                                          :artifact-sha256 :parser-version :candidate-id :observation-id) ref))
                 (nat-int? (:ordinal ref)) (every? pos-int? ((juxt :page :line) ref)))
    (fail! "Invalid PDF evidence reference"))
  (let [o (target c ref)
        bytes ^bytes (:artifact_bytes o)
        artifact (edn/read-string (String. bytes "UTF-8"))
        candidate (get (:candidates artifact) (:ordinal ref))
        payload (edn/read-string (:payload_edn o))
        position (select-keys (:coordinates candidate) [:page :line])
        source-position (or (when (seq (:source-lines candidate))
                              (mapv #(select-keys % [:page :line]) (:source-lines candidate)))
                            [position])
        acquisition (some #(when (= (:acquisition-id ref) (:acquisition-id %)) %)
                          (:acquisitions artifact))]
    (when-not (and (vector? (:candidates artifact))
                   (= (:schema-version ref) (:schema-version artifact))
                   (= (:parser-version ref) (:parser-version artifact))
                   (= (:job-id ref) (:job-id artifact)
                      (html/digest (select-keys artifact
                                                [:source-sha256 :acquisitions :evidence-sha256
                                                 :actor :config :parser-version :schema-version
                                                 :pdfinfo-version :tool])))
                   (= (:artifact-sha256 ref) (:artifact_sha256 o) (sha256 bytes))
                   (= (:source-sha256 ref) (:source_sha256 o) (:source-sha256 artifact)
                      (get-in acquisition [:manifest :sha256]))
                   (= "application/pdf" (get-in acquisition [:manifest :content-type]))
                   (= (:candidate-id ref) (:candidate_id o)
                      (html/digest [(:source-sha256 ref) source-position]))
                   (= (:observation-id ref)
                      (str "local-observation:" (:job-id ref) ":" (:ordinal ref)))
                   (= candidate payload)
                   (= position (select-keys ref [:page :line]))
                   (some #(= (:line ref) (:line %))
                         (get-in artifact [:pages (dec (:page ref)) :lines]))
                   (= "result-row" (:kind o)))
      (fail! "PDF evidence provenance or source position mismatch"))
    o))
(defn- pdf-events [c t]
  (mapv body (query c "SELECT * FROM freediving.pdf_extraction_reviews WHERE job_id=? AND ordinal=? ORDER BY revision"
                    (:job-id t) (:ordinal t))))
(defn- pdf-state [c t]
  (target c t)
  (reduce (fn [_ event]
            (case (:action event)
              :accept {:revision (:revision event) :status :accepted :active-event (:id event)}
              :revoke {:revision (:revision event) :status :unreviewed :active-event nil}))
          {:revision 0 :status :unreviewed :active-event nil}
          (pdf-events c t)))
(defn pdf-extraction-effective [url t]
  (read-snapshot url #(pdf-state % t)))
(defn pdf-extraction-history [url t]
  (read-snapshot url (fn [c] (target c t) (pdf-events c t))))
(defn- pdf-capability! [c]
  (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.pdf_extraction_reviews','INSERT') AS allowed")))
    (fail! "Owner PDF extraction review database capability required")))
(defn- pdf-accept-request! [request]
  (when-not (and (map? request)
                 (= #{:id :job-id :ordinal :base-revision :evidence :owner-receipt-sha256
                      :owner-response :actor :reason} (set (keys request)))
                 (every? nonblank? ((juxt :id :job-id :actor :reason) request))
                 (nat-int? (:ordinal request)) (nat-int? (:base-revision request))
                 (string? (:owner-receipt-sha256 request))
                 (re-matches #"[0-9a-f]{64}" (:owner-receipt-sha256 request))
                 (= #{:task-id :user-message-id :response-annotation-index :selected-text}
                    (set (keys (:owner-response request))))
                 (every? nonblank? ((juxt :task-id :user-message-id :selected-text)
                                    (:owner-response request)))
                 (nat-int? (get-in request [:owner-response :response-annotation-index]))
                 (= (select-keys request [:job-id :ordinal])
                    (select-keys (:evidence request) [:job-id :ordinal]))
                 (when-let [[_ from to] (re-matches #"Accept extraction ([0-9]+)-([0-9]+)"
                                                    (get-in request [:owner-response :selected-text]))]
                   (<= (parse-long from) (:ordinal request) (parse-long to))))
    (fail! "Invalid owner PDF extraction acceptance request")))
(defn accept-pdf-extraction! [url request]
  (pdf-accept-request! request)
  (transaction url
               (fn [c]
                 (pdf-capability! c)
                 (lock! c request)
                 (pdf-reference! c (:evidence request))
                 (or (existing c "pdf_extraction_reviews" (:id request) request)
                     (let [state (pdf-state c request)]
                       (when-not (= (:base-revision request) (:revision state))
                         (fail! "Stale PDF extraction review revision"))
                       (when (= :accepted (:status state))
                         (fail! "PDF extraction position already accepted"))
                       (let [record (assoc request :action :accept :revision (inc (:revision state))
                                           :request request)]
                         (execute! c "INSERT INTO freediving.pdf_extraction_reviews(id,job_id,ordinal,revision,action,event_id,body_edn) VALUES(?,?,?,?,?,?,?)"
                                   (:id request) (:job-id request) (:ordinal request) (:revision record)
                                   "accept" nil (encode record))
                         (existing c "pdf_extraction_reviews" (:id request) request)))))))
(defn revoke-pdf-extraction! [url request]
  (when-not (and (map? request)
                 (= #{:id :job-id :ordinal :base-revision :event-id :evidence :actor :reason}
                    (set (keys request)))
                 (every? nonblank? ((juxt :id :job-id :event-id :actor :reason) request))
                 (nat-int? (:ordinal request)) (nat-int? (:base-revision request))
                 (= (select-keys request [:job-id :ordinal])
                    (select-keys (:evidence request) [:job-id :ordinal])))
    (fail! "Invalid PDF extraction revocation request"))
  (transaction url
               (fn [c]
                 (pdf-capability! c)
                 (lock! c request)
                 (pdf-reference! c (:evidence request))
                 (or (existing c "pdf_extraction_reviews" (:id request) request)
                     (let [state (pdf-state c request)
                           accepted (some #(when (= (:event-id request) (:id %)) %) (pdf-events c request))]
                       (when-not (= (:base-revision request) (:revision state))
                         (fail! "Stale PDF extraction review revision"))
                       (when-not (and (= (:event-id request) (:active-event state))
                                      (= (:evidence request) (:evidence accepted)))
                         (fail! "Only the active source-bound PDF acceptance can be revoked"))
                       (let [record (assoc request :action :revoke :revision (inc (:revision state))
                                           :request request)]
                         (execute! c "INSERT INTO freediving.pdf_extraction_reviews(id,job_id,ordinal,revision,action,event_id,body_edn) VALUES(?,?,?,?,?,?,?)"
                                   (:id request) (:job-id request) (:ordinal request) (:revision record)
                                   "revoke" (:event-id request) (encode record))
                         (existing c "pdf_extraction_reviews" (:id request) request)))))))
(defn- field-events [c source-position-id]
  (mapv (fn [row]
          (merge (edn/read-string (:body_edn row))
                 {:id (:id row) :revision (:revision row)
                  :db-role (:db_role row) :recorded-at (str (:recorded_at row))}))
        (query c "SELECT * FROM freediving.dive_field_decisions WHERE source_position_id=? ORDER BY revision"
               source-position-id)))

(defn- field-row [c t]
  (let [o (target c t) payload (edn/read-string (:payload_edn o))]
    (when-not (= "result-row" (:kind o)) (fail! "Dive field target must be a result-row"))
    {:job-id (:job-id t) :ordinal (:ordinal t)
     :source-position-id (:source-position-id t)
     :source-sha256 (:source_sha256 o) :artifact-sha256 (:artifact_sha256 o)
     :parser-version (:parser_version o) :payload payload}))

(defn- raw-field-entry [payload field]
  (let [raw (:raw payload)
        fields (if (map? (:fields raw)) (:fields raw) raw)]
    (first (filter (comp nonblank? second)
                   (map (fn [key] [key (get fields key)])
                        (case field
                          :category [:category "category" "Category" "PlaCat" "PlaCatEff"
                                     "AGCodeDescr" "Gender"]
                          :representation [:representation "representation" "Representation"
                                           :country "Country" "PlaNat" "ParOrgCode" "Nationality"]))))))

(defn- raw-field [payload field] (second (raw-field-entry payload field)))

(defn- citation [row]
  (merge {:source-position-id (:source-position-id row)
          :job-id (:job-id row) :ordinal (:ordinal row)
          :source-sha256 (:source-sha256 row)
          :artifact-sha256 (:artifact-sha256 row)
          :parser-version (:parser-version row)}
         (select-keys (get-in row [:payload :coordinates])
                      [:page :line :column-start :column-end :table :row :row-index-zero-based])
         (select-keys (:payload row) [:source-page-url])))

(defn- field-state [row events field]
  (let [original (raw-field (:payload row) field)
        relevant (filter #(= field (:decision-type %)) events)
        state (reduce (fn [s e]
                        (case (:action e)
                          :assert (if (and (not= :suppressed (:status e))
                                           (or (= :human (:actor-kind e))
                                               (= (:job-id row) (:job-id e))))
                                    {:accepted (:proposed e) :status (:status e)
                                     :actor-kind (:actor-kind e) :decision-id (:id e)
                                     :citation (first (:supporting-evidence e))}
                                    s)
                          :reverse {:accepted nil :status :reversed :actor-kind (:actor-kind e)
                                    :decision-id (:id e) :citation (:citation s)}
                          s))
                      {:accepted nil :status :unresolved :actor-kind nil
                       :decision-id nil :citation (citation row)} relevant)]
    (assoc state :raw original)))

(defn dive-fields [url t]
  (when-not (and (string? (:source-position-id t)) (not (str/blank? (:source-position-id t))))
    (fail! "Source position ID required"))
  (read-snapshot url
                 (fn [c]
                   (let [row (field-row c t) events (field-events c (:source-position-id t))]
                     {:source-position-id (:source-position-id t)
                      :job-id (:job-id row) :ordinal (:ordinal row)
                      :source-sha256 (:source-sha256 row)
                      :artifact-sha256 (:artifact-sha256 row)
                      :parser-version (:parser-version row)
                      :revision (or (:revision (last events)) 0)
                      :category (field-state row events :category)
                      :representation (field-state row events :representation)}))))

(defn dive-decision-history [url t]
  (read-snapshot url (fn [c] (field-row c t) (field-events c (:source-position-id t)))))

(defn- append-field! [c row event]
  (execute! c "INSERT INTO freediving.dive_field_decisions(id,source_position_id,job_id,ordinal,decision_type,revision,action,actor_kind,decision_key,event_id,body_edn) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
            (:id event) (:source-position-id row) (:job-id row) (:ordinal row)
            (name (:decision-type event)) (:revision event) (name (:action event))
            (name (:actor-kind event)) (:decision-key event) (:event-id event) (encode event))
  (last (field-events c (:source-position-id row))))

(defn- dictionary-entry [dictionary field raw]
  (get (if (= field :category) (:categories dictionary) (:representations dictionary)) raw))

(defn- field-binding [row dictionary field]
  (let [[source-key raw] (raw-field-entry (:payload row) field)]
    {:source-position (citation row) :source-key source-key :raw-label raw
     :normalized-value (dictionary-entry dictionary field raw)
     :dictionary dictionary}))

(defn- field-proposal [row dictionary field]
  (let [raw (raw-field (:payload row) field)
        heading (get dictionary (if (= field :category) :category-heading :representation-heading))
        heading-label (:label heading)
        from-row (dictionary-entry dictionary field raw)
        from-heading (dictionary-entry dictionary field heading-label)
        conflict? (and raw heading-label (not= raw heading-label))
        chosen (if raw from-row from-heading)
        valid? (if (= field :category)
                 (and (vector? chosen) (seq chosen) (every? #(and (string? %) (not (str/blank? %))) chosen))
                 (and (map? chosen) (#{:country :federation :neutral :organization} (:kind chosen))
                      (string? (:code chosen)) (not (str/blank? (:code chosen)))))
        status (if (and (not conflict?) valid?) :accepted :unresolved)
        source-key (first (raw-field-entry (:payload row) field))
        semantic-binding? (or (not= field :representation)
                              (not (#{"Nationality" "PlaNat"} source-key))
                              (= :per-dive-representation
                                 (get-in dictionary [:representation-cell-semantics source-key])))
        evidence (cond-> []
                   raw (conj (assoc (citation row) :raw-label raw :source :row))
                   heading (conj (merge (citation row) (:citation heading)
                                        {:raw-label heading-label :source :heading})))
        evidence (if (seq evidence) evidence [(assoc (citation row) :raw-label nil :source :row)])
        contrary (if conflict? [(last evidence)] [])]
    {:original raw
     :status (if semantic-binding? status :unresolved)
     :proposed (when (and semantic-binding? (= status :accepted)) chosen)
     :supporting-evidence (if conflict? [(first evidence)] evidence)
     :conflicting-evidence contrary
     :rule-evidence {:row-label raw :heading-label heading-label
                     :dictionary-value chosen :dictionary-version (:version dictionary)
                     :rule (cond conflict? :row-heading-conflict
                                 (not semantic-binding?) :unsupported-column-semantics
                                 (nil? (or raw heading-label)) :missing-label
                                 (not valid?) :unmapped-label
                                 :else :explicit-dictionary-map)}}))

(defn reconcile-dive-fields!
  "Append dictionary decisions for an imported row. The caller binds source-position-id
  to the exact retained packet position ID; snapshot projection verifies that binding."
  [url t dictionary]
  (when-not (and (string? (:source-position-id t)) (not (str/blank? (:source-position-id t)))
                 (map? dictionary) (nonblank? (:version dictionary))
                 (nonblank? (:federation dictionary)) (nonblank? (:event-id dictionary)))
    (fail! "Versioned source-position dictionary required"))
  (transaction url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(hashtext(?))" (:source-position-id t))
                 (let [row (field-row c t)]
                   (reduce (fn [result field]
                             (let [events (field-events c (:source-position-id t))
                                   prior (filter #(= field (:decision-type %)) events)
                                   latest-human (last (filter #(= :human (:actor-kind %)) prior))
                                   proposal (cond-> (field-proposal row dictionary field)
                                              latest-human (assoc :status :suppressed :proposed nil))
                                   key (sha256 (.getBytes (encode [(:source-position-id t) (:job-id t)
                                                                   (:ordinal t) field (:version dictionary)
                                                                   (:federation dictionary) (:event-id dictionary)
                                                                   proposal]) "UTF-8"))
                                   old (first (filter #(= key (:decision-key %)) prior))]
                               (assoc result field
                                      (or old
                                          (append-field! c row
                                                         (merge proposal
                                                                {:id (str "auto:" key) :decision-key key
                                                                 :source-position-id (:source-position-id t)
                                                                 :job-id (:job-id t) :ordinal (:ordinal t)
                                                                 :decision-type field :action :assert
                                                                 :revision (inc (or (:revision (last events)) 0))
                                                                 :actor-kind :automatic :actor "deterministic-rule"
                                                                 :rule-version "dive-fields/1"
                                                                 :policy-version "deterministic-auto/1"
                                                                 :federation (:federation dictionary)
                                                                 :event-context-id (:event-id dictionary)
                                                                 :source-position (citation row)
                                                                 :observation-version (select-keys row [:job-id :ordinal :source-sha256
                                                                                                        :artifact-sha256 :parser-version])
                                                                 :dependencies (if latest-human [(:id latest-human)] [])
                                                                 :model-confidence nil}))))))
                           {} [:category :representation])))))

(defn build-dive-field-jev-decision
  "Build a private Jev question only for a mapped native label with unresolved semantics.
   Missing mappings and conflicting headings stay explicitly unresolved."
  [url t dictionary field]
  (when-not (and (#{:category :representation} field)
                 (nonblank? (:source-position-id t))
                 (map? dictionary) (nonblank? (:version dictionary))
                 (nonblank? (:federation dictionary)) (nonblank? (:event-id dictionary)))
    (fail! "Versioned source-position dictionary required"))
  (read-snapshot url
                 (fn [c]
                   (let [row (field-row c t)
                         [source-key raw] (raw-field-entry (:payload row) field)
                         binding (field-binding row dictionary field)
                         proposal (field-proposal row dictionary field)
                         mapped (dictionary-entry dictionary field raw)
                         valid? (if (= field :category)
                                  (and (vector? mapped) (seq mapped) (every? nonblank? mapped))
                                  (and (map? mapped)
                                       (#{:country :federation :neutral :organization} (:kind mapped))
                                       (nonblank? (:code mapped))))
                         reason (cond
                                  (not (nonblank? raw)) :missing-label
                                  (not valid?) :unmapped-label
                                  (seq (:conflicting-evidence proposal)) :conflicting-heading
                                  (= :accepted (:status proposal)) :deterministic
                                  :else nil)]
                     (if reason
                       {:status (if (= reason :deterministic) :deterministic :unresolved)
                        :reason reason :source-position (citation row)
                        :field-binding binding}
                       (let [evidence {:evidence-id (str "row:" (:source-position-id t))
                                       :citation (citation row)
                                       :exact-excerpt raw
                                       :source-label (name source-key)
                                       :source-meaning (str "Native source column " (name source-key)
                                                            "; category and representation are distinct")
                                       :uncertainty (name (get-in proposal [:rule-evidence :rule]))}
                             id (sha256 (.getBytes (encode [(:source-position-id t) (:job-id row)
                                                            (:ordinal row) (:source-sha256 row)
                                                            (:artifact-sha256 row) (:parser-version row)
                                                            field (:version dictionary) raw mapped]) "UTF-8"))]
                         {:status :ready :field-binding binding
                          :decision {:id id :family :category-representation
                                     :action field
                                     :subject {:source-position-id (:source-position-id t)
                                               :job-id (:job-id row) :ordinal (:ordinal row)
                                               :source-label (name source-key) :raw-label raw
                                               :federation (:federation dictionary)
                                               :event-id (:event-id dictionary)}
                                     :candidates [{:field (name field) :raw-label raw
                                                   :normalized-value (pr-str mapped)
                                                   :dictionary-hash (sha256 (.getBytes (encode dictionary) "UTF-8"))}]
                                     :field-binding binding
                                     :evidence [evidence]
                                     :uncertainties [(get-in proposal [:rule-evidence :rule])]
                                     :contradictions [] :dependencies []
                                     :evidence-adequate? true}}))))))

(defn approve-jev-dive-field!
  "Apply one approved source-label classification to a canonical dive field.
   The dictionary supplies the normalized value; Jev only classifies its meaning."
  [url ledger decision config {:keys [target decision-id decision-type dictionary policy base-revision] :as request}]
  (when-not (and (= decision-id (:id decision))
                 (= :category-representation (:family decision))
                 (#{:category :representation} decision-type)
                 (nat-int? base-revision)
                 (map? dictionary) (nonblank? (:version dictionary))
                 (nonblank? (:federation dictionary)) (nonblank? (:event-id dictionary))
                 (map? policy) (nonblank? (:version policy)))
    (fail! "Invalid Jev dive field request"))
  (let [view (get (reconciliation-flow/inspect ledger [decision] config) decision-id)
        event (last (filter #(and (= decision-id (:decision-id %))
                                  (= (:request-hash view) (:request-hash %))
                                  (= (:result-hash view) (:result-hash %))
                                  (= :approved (:status %))) (:events ledger)))
        answer (:answer view)
        request-hash (or (:request-hash event) (:request-hash answer))
        result-hash (or (:result-hash event) (:result-hash answer))
        template-version (or (:template-version event) (:template-version answer))
        choice (:outcome answer)
        assessment (reconciliation-policy/assess policy (assoc decision :action choice) answer)]
    (when-not (and (= :approved (:status view))
                   (#{:jev :cached-jev :retained} (:origin view))
                   (= :approved (:status event))
                   (= (:origin view) (:origin event))
                   (= (:evidence decision) (:evidence event))
                   (= (:candidates decision) (:candidates event))
                   (= (:dependencies decision) (:dependencies event))
                   (= (:version policy) (:policy-version event))
                   (= :approve (:status assessment))
                   (= reconciliation-jev/template-version template-version)
                   (= (:model config) (:model-version answer) (:actual-model answer))
                   (nonblank? request-hash) (nonblank? result-hash)
                   (map? (:receipt answer))
                   (= request-hash (get-in answer [:receipt :request-hash]))
                   (= result-hash (get-in answer [:receipt :result-hash]))
                   (map? (:raw-answer answer)) (map? (:raw-probabilities answer))
                   (= 5 (count (:raw-probabilities answer)))
                   (or (= choice :both) (= choice decision-type)))
      (fail! "Jev field approval is stale or incomplete"))
    (transaction url
                 (fn [c]
                   (let [subject (:source-position-id target)]
                     (when-not (nonblank? subject) (fail! "Source position ID required"))
                     (query c "SELECT pg_advisory_xact_lock(hashtext(?))" subject)
                     (let [row (field-row c target)
                           events (field-events c subject)
                           field-events (filter #(= decision-type (:decision-type %)) events)
                           raw (raw-field (:payload row) decision-type)
                           heading (get dictionary (if (= decision-type :category)
                                                     :category-heading :representation-heading))
                           proposed (dictionary-entry dictionary decision-type raw)
                           current-binding (field-binding row dictionary decision-type)
                           cited? (some (fn [e]
                                          (let [ref (:citation e)]
                                            (and (map? ref)
                                                 (= subject (:source-position-id ref))
                                                 (= (:job-id row) (:job-id ref))
                                                 (= (:ordinal row) (:ordinal ref))
                                                 (= (:source-sha256 row) (:source-sha256 ref))
                                                 (= (:artifact-sha256 row) (:artifact-sha256 ref))
                                                 (= (:parser-version row) (:parser-version ref))
                                                 (nonblank? raw)
                                                 (string? (:exact-excerpt e))
                                                 (str/includes? (:exact-excerpt e) raw))))
                                        (:evidence decision))
                           valid-value? (if (= decision-type :category)
                                          (and (vector? proposed) (seq proposed) (every? nonblank? proposed))
                                          (and (map? proposed)
                                               (#{:country :federation :neutral :organization} (:kind proposed))
                                               (nonblank? (:code proposed))))
                           key (sha256 (.getBytes (encode [decision-id decision-type (citation row)
                                                           (:version dictionary) proposed
                                                           request-hash result-hash
                                                           (:version policy)]) "UTF-8"))
                           old (first (filter #(= key (:decision-key %)) field-events))]
                       (when (some #(= :human (:actor-kind %)) field-events)
                         (fail! "Human dive field correction blocks model approval"))
                       (when-not (= current-binding (:field-binding decision))
                         (fail! "Jev dive field binding changed"))
                       (when (and old (some #(= (:id old) (:event-id %)) field-events))
                         (fail! "Model dive field approval was reversed"))
                       (when (and (not old)
                                  (= :model (:actor-kind (last field-events)))
                                  (= :assert (:action (last field-events))))
                         (fail! "Prior model dive field approval requires invalidation"))
                       (when-not (and (nonblank? raw) cited? valid-value?
                                      (or (nil? heading) (= raw (:label heading)))
                                      (= (:job-id row) (:job-id target)))
                         (fail! "Jev field value or source citation is unsupported"))
                       (if old old
                           (do
                             (when-not (= base-revision (or (:revision (last events)) 0))
                               (fail! "Stale dive decision revision"))
                             (append-field! c row
                                            {:id (str "model:" key) :decision-key key
                                             :source-position-id subject :job-id (:job-id row)
                                             :ordinal (:ordinal row) :decision-type decision-type
                                             :action :assert :revision (inc base-revision)
                                             :actor-kind :model :actor :jev :status :accepted
                                             :original raw :proposed proposed
                                             :supporting-evidence (:evidence decision)
                                             :conflicting-evidence (or (:contradictions decision) [])
                                             :dependencies (:dependencies decision)
                                             :source-position (citation row)
                                             :observation-version (select-keys row [:job-id :ordinal :source-sha256
                                                                                    :artifact-sha256 :parser-version])
                                             :federation (:federation dictionary)
                                             :event-context-id (:event-id dictionary)
                                             :dictionary-version (:version dictionary)
                                             :model-version (:model-version answer)
                                             :model-decision decision
                                             :model-config-hash (sha256 (.getBytes (encode config) "UTF-8"))
                                             :template-version template-version
                                             :policy-version (:version policy)
                                             :decision-id decision-id
                                             :model-confidence (:confidence answer)
                                             :answer answer :receipt (:receipt answer)
                                             :request-hash request-hash
                                             :result-hash result-hash
                                             :request request})))))))))

(defn invalidate-jev-dive-field!
  "Reverse an active model field assertion after evidence, configuration or dependency changes."
  [url decision config {:keys [target decision-type dictionary base-revision dependency-statuses
                               canonical-dependency-statuses current-flow-status] :as request}]
  (when-not (and (= :category-representation (:family decision))
                 (#{:category :representation} decision-type)
                 (nonblank? (:id decision)) (nat-int? base-revision)
                 (map? dictionary) (nonblank? (:version dictionary))
                 (map? config) (nonblank? (:version config)))
    (fail! "Invalid model dive field invalidation"))
  (transaction url
               (fn [c]
                 (let [subject (:source-position-id target)]
                   (when-not (nonblank? subject) (fail! "Source position ID required"))
                   (query c "SELECT pg_advisory_xact_lock(hashtext(?))" subject)
                   (let [row (field-row c target)
                         events (field-events c subject)
                         relevant (filter #(= decision-type (:decision-type %)) events)
                         previous (last (filter #(and (= :model (:actor-kind %))
                                                      (= :assert (:action %))) relevant))
                         key (sha256 (.getBytes (encode [(:id previous) decision config
                                                         (:version dictionary) (citation row)]) "UTF-8"))
                         old (first (filter #(and (= key (:decision-key %))
                                                  (= :reverse (:action %))) relevant))
                         cited? (some (fn [e]
                                        (let [ref (:citation e)]
                                          (and (map? ref)
                                               (= subject (:source-position-id ref))
                                               (= (:job-id row) (:job-id ref))
                                               (= (:ordinal row) (:ordinal ref))
                                               (= (:source-sha256 row) (:source-sha256 ref))
                                               (= (:artifact-sha256 row) (:artifact-sha256 ref))
                                               (= (:parser-version row) (:parser-version ref)))))
                                      (:evidence decision))]
                     (when (some #(= :human (:actor-kind %)) relevant)
                       (fail! "Human dive field correction blocks model invalidation"))
                     (when-not (and previous cited?
                                    (or (not= (:model-decision previous) decision)
                                        (not= (:model-config-hash previous)
                                              (sha256 (.getBytes (encode config) "UTF-8")))
                                        (not= (:dictionary-version previous) (:version dictionary))
                                        (not= (:source-position previous) (citation row))
                                        (and (map? dependency-statuses)
                                             (seq (:dependencies previous))
                                             (some #(not= :approved (get dependency-statuses % :unresolved))
                                                   (:dependencies previous)))
                                        (and (contains? request :current-flow-status)
                                             (not= :approved current-flow-status))
                                        (and (map? canonical-dependency-statuses)
                                             (seq (:dependencies previous))
                                             (some #(not= :approved (get canonical-dependency-statuses % :unresolved))
                                                   (:dependencies previous)))))
                       (fail! "No changed model dive field evidence"))
                     (if old old
                         (do
                           (when-not (and (= base-revision (or (:revision (last events)) 0))
                                          (= :assert (:action (last relevant)))
                                          (= (:id previous) (:id (last relevant))))
                             (fail! "Stale dive decision revision"))
                           (append-field! c row
                                          {:id (str "model-reverse:" key) :decision-key key
                                           :source-position-id subject :job-id (:job-id row)
                                           :ordinal (:ordinal row) :decision-type decision-type
                                           :action :reverse :event-id (:id previous)
                                           :revision (inc base-revision) :actor-kind :model
                                           :actor :jev :status :reversed
                                           :original (raw-field (:payload row) decision-type)
                                           :proposed nil :source-position (citation row)
                                           :supporting-evidence (:evidence decision)
                                           :prior-decision-id (:decision-id previous)
                                           :reason (cond
                                                     (and (contains? request :current-flow-status)
                                                          (not= :approved current-flow-status)) :flow-approval-stale
                                                     (or (and (map? dependency-statuses)
                                                              (some #(not= :approved (get dependency-statuses % :unresolved))
                                                                    (:dependencies previous)))
                                                         (and (map? canonical-dependency-statuses)
                                                              (some #(not= :approved (get canonical-dependency-statuses % :unresolved))
                                                                    (:dependencies previous)))) :dependency-unapproved
                                                     :else :changed-model-evidence)
                                           :request request}))))))))

(defn- human-decision! [url request action]
  (when-not (and (nonblank? (:id request)) (nonblank? (:actor request))
                 (nonblank? (:reason request)) (nat-int? (:base-revision request)))
    (fail! "Human decision requires id, actor, reason and base revision"))
  (transaction url
               (fn [c]
                 (let [existing (first (query c "SELECT * FROM freediving.dive_field_decisions WHERE id=?" (:id request)))]
                   (if existing
                     (let [record (edn/read-string (:body_edn existing))]
                       (if (= (:request record) request) record (fail! "Conflicting decision ID")))
                     (let [event (when (= action :reverse)
                                   (first (query c "SELECT * FROM freediving.dive_field_decisions WHERE id=?" (:event-id request))))
                           subject (if event (:source_position_id event) (:source-position-id request))]
                       (when-not (nonblank? subject) (fail! "Unknown source position"))
                       (query c "SELECT pg_advisory_xact_lock(hashtext(?))" subject)
                       (let [events (field-events c subject) revision (or (:revision (last events)) 0)
                             base (:base-revision request)]
                         (when-not (= base revision) (fail! "Stale dive decision revision"))
                         (when (and (= action :reverse)
                                    (or (nil? event) (not= "assert" (:action event))
                                        (some #(= (:event-id request) (:event-id %)) events)))
                           (fail! "Decision cannot be reversed"))
                         (let [t (if event {:job-id (:job_id event) :ordinal (:ordinal event)
                                            :source-position-id subject} request)
                               row (field-row c t)
                               field (if event (keyword (:decision_type event)) (:decision-type request))
                               proposed (when (= action :assert) (:proposed request))]
                           (when-not (#{:category :representation} field) (fail! "Invalid dive decision type"))
                           (when (and (= action :reverse)
                                      (not= (:event-id request) (:decision-id (field-state row events field))))
                             (fail! "Decision is no longer active"))
                           (when (and (= action :assert)
                                      (not (if (= field :category)
                                             (and (vector? proposed) (seq proposed) (every? nonblank? proposed))
                                             (and (map? proposed)
                                                  (#{:country :federation :neutral :organization} (:kind proposed))
                                                  (nonblank? (:code proposed))))))
                             (fail! "Invalid proposed dive field value"))
                           (let [record {:id (:id request) :source-position-id subject
                                         :job-id (:job-id row) :ordinal (:ordinal row)
                                         :source-position (citation row)
                                         :observation-version (select-keys row [:job-id :ordinal :source-sha256
                                                                                :artifact-sha256 :parser-version])
                                         :decision-type field :action action
                                         :event-id (:event-id request) :revision (inc revision)
                                         :actor-kind :human :actor (:actor request)
                                         :status (if (= action :reverse) :reversed :accepted)
                                         :original (raw-field (:payload row) field)
                                         :proposed proposed
                                         :supporting-evidence (or (:supporting-evidence request) [(citation row)])
                                         :conflicting-evidence (or (:conflicting-evidence request) [])
                                         :dependencies (or (:dependencies request) [])
                                         :rule-version nil :policy-version "human-review/1"
                                         :model-confidence nil :reason (:reason request)
                                         :request request}]
                             (append-field! c row record))))))))))

(defn reverse-dive-decision! [url request] (human-decision! url request :reverse))
(defn assert-dive-field! [url request] (human-decision! url request :assert))

(defn sync-human-jev-dive-field!
  "Materialize an explicit private owner rejection or reversal as a reviewer reversal."
  [reviewer-url ledger decision config {:keys [target decision-type base-revision]}]
  (let [human (last (filter #(and (= :human (:origin %))
                                  (= (:id decision) (:decision-id %))) (:events ledger)))
        view (get (reconciliation-flow/inspect ledger [decision] config) (:id decision))]
    (when-not (and human (= :human (:origin view))
                   (#{:rejected :reversed} (:status human))
                   (= (:status human) (:status view))
                   (nonblank? (:actor human)) (nonblank? (:reason human))
                   (#{:category :representation} decision-type)
                   (nat-int? base-revision))
      (fail! "Explicit human Jev field correction required"))
    (let [prior (read-snapshot reviewer-url
                               (fn [c]
                                 (field-row c target)
                                 (last (filter #(and (= decision-type (:decision-type %))
                                                     (= :model (:actor-kind %))
                                                     (= :assert (:action %))
                                                     (= (:id decision) (:decision-id %)))
                                               (field-events c (:source-position-id target))))))]
      (when-not prior (fail! "No model dive field decision to correct"))
      (let [id (str "flow-human:" (sha256 (.getBytes (encode [(:id human) (:id prior)]) "UTF-8")))
            existing (read-snapshot reviewer-url
                                    (fn [c]
                                      (first (filter #(= id (:id %))
                                                     (field-events c (:source-position-id target))))))
            stable {:event-id (:id prior) :actor (:actor human) :reason (:reason human)
                    :flow-event-id (:id human) :flow-status (:status human)}]
        (if existing
          (if (and (= :human (:actor-kind existing)) (= :reverse (:action existing))
                   (= stable (select-keys (:request existing) (keys stable))))
            existing
            (fail! "Conflicting human Jev field correction"))
          (reverse-dive-decision! reviewer-url
                                  (assoc stable :id id :base-revision base-revision)))))))

(defn export-dive-fields [url]
  (read-snapshot url
                 (fn [c]
                   (let [subjects (query c "SELECT DISTINCT ON (source_position_id) source_position_id,job_id,ordinal FROM freediving.dive_field_decisions ORDER BY source_position_id,revision DESC")]
                     (json/write-str
                      {:schema "dive-field-decisions/v1"
                       :positions (mapv (fn [subject]
                                          (let [t {:source-position-id (:source_position_id subject)
                                                   :job-id (:job_id subject) :ordinal (:ordinal subject)}
                                                row (field-row c t) events (field-events c (:source-position-id t))
                                                category (field-state row events :category)
                                                representation (field-state row events :representation)]
                                            {:source_position_id (:source-position-id t)
                                             :job_id (:job-id row) :ordinal (:ordinal row)
                                             :source_sha256 (:source-sha256 row)
                                             :artifact_sha256 (:artifact-sha256 row)
                                             :parser_version (:parser-version row)
                                             :raw_category (:raw category)
                                             :raw_representation (:raw representation)
                                             :accepted_categories (:accepted category)
                                             :accepted_representation (:accepted representation)
                                             :category_status (if (:accepted category) (name (:actor-kind category)) "unresolved")
                                             :representation_status (if (:accepted representation) (name (:actor-kind representation)) "unresolved")
                                             :category_actor_kind (some-> (:actor-kind category) name)
                                             :representation_actor_kind (some-> (:actor-kind representation) name)
                                             :decision_revision (:revision (last events))
                                             :category_citation (:citation category)
                                             :representation_citation (:citation representation)
                                             :category_decision_id (:decision-id category)
                                             :representation_decision_id (:decision-id representation)})) subjects)})))))

(defn- read-request [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (let [eof (Object.) request (edn/read {:eof eof} r)]
      (when (or (identical? eof request) (not (identical? eof (edn/read {:eof eof} r))))
        (fail! "Expected one EDN request"))
      request)))
(defn -main [& [command & args]]
  (try
    (let [url (System/getenv "FREEDIVING_DATABASE_URL")]
      (println (encode (case command
                         "migrate" (if (= 2 (count args)) (apply migrate! url args) (fail! "migrate INGEST-ROLE REVIEWER-ROLE"))
                         "export-dive-fields" (if (= 1 (count args)) (do (spit (first args) (export-dive-fields url)) {:output (first args)})
                                                  (fail! "export-dive-fields OUTPUT.json"))
                         ("propose" "decide" "effective" "history" "accept-extraction" "revoke-extraction" "extraction-effective" "extraction-history"
                                    "accept-pdf-extraction" "revoke-pdf-extraction" "pdf-extraction-effective" "pdf-extraction-history"
                                    "dive-fields" "dive-decision-history" "reverse-dive-decision" "assert-dive-field")
                         (if (= 1 (count args)) (({"propose" propose! "decide" decide! "effective" effective "history" history
                                                   "accept-extraction" accept-extraction! "revoke-extraction" revoke-extraction!
                                                   "extraction-effective" extraction-effective "extraction-history" extraction-history
                                                   "accept-pdf-extraction" accept-pdf-extraction! "revoke-pdf-extraction" revoke-pdf-extraction!
                                                   "pdf-extraction-effective" pdf-extraction-effective "pdf-extraction-history" pdf-extraction-history
                                                   "dive-fields" dive-fields "dive-decision-history" dive-decision-history
                                                   "reverse-dive-decision" reverse-dive-decision! "assert-dive-field" assert-dive-field!} command) url (read-request (first args)))
                             (fail! "Expected one EDN request file"))
                         "reconcile-dive-fields" (if (= 1 (count args)) (let [{:keys [target dictionary]} (read-request (first args))]
                                                                          (reconcile-dive-fields! url target dictionary))
                                                     (fail! "reconcile-dive-fields REQUEST.edn"))
                         (fail! "Commands: migrate INGEST-ROLE REVIEWER-ROLE | propose|decide|effective|history|accept-extraction|revoke-extraction|extraction-effective|extraction-history|accept-pdf-extraction|revoke-pdf-extraction|pdf-extraction-effective|pdf-extraction-history REQUEST.edn")))))
    (catch Exception e (binding [*out* *err*] (println "Review operation failed:" (.getMessage e))) (System/exit 1))))
