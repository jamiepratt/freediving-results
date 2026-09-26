(ns freediving.reviews
  "Append-only local owner review. DB reviewer credentials are the authority; actor is audit text."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.aida-html :as html]
            [freediving.html-evidence :as html-evidence]
            [freediving.candidates :as candidates])
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
                 (execute! c (str "REVOKE ALL ON freediving.extractions,freediving.observations FROM " reviewer-role))
                 (doseq [role [ingest-role reviewer-role]]
                   (execute! c (str "REVOKE CREATE ON SCHEMA freediving FROM " role))
                   (execute! c (str "GRANT USAGE ON SCHEMA freediving TO " role))
                   (execute! c (str "REVOKE ALL ON freediving.review_proposals,freediving.review_decisions FROM " role))
                   (execute! c (str "REVOKE ALL ON freediving.extraction_reviews FROM " role))
                   (execute! c (str "GRANT SELECT ON freediving.extractions,freediving.observations,freediving.review_proposals,freediving.review_decisions TO " role))
                   (execute! c (str "GRANT SELECT ON freediving.extraction_reviews TO " role))
                   (execute! c (str "GRANT INSERT ON freediving.review_proposals TO " role)))
                 (execute! c (str "GRANT INSERT ON freediving.review_decisions TO " reviewer-role))
                 (execute! c (str "GRANT INSERT ON freediving.extraction_reviews TO " reviewer-role))
                 {:schema-version 2})))
(defn- target [c {:keys [job-id ordinal]}]
  (or (first (query c "SELECT o.*,e.artifact_bytes,e.artifact_sha256,e.source_sha256 FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE job_id=? AND ordinal=?" job-id ordinal))
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
        position (:row-index-zero-based ref)
        coordinate {:row-index-zero-based position}
        acquired-pages (set (map #(get-in % [:manifest :provenance :source-page-url])
                                 (:acquisitions artifact)))]
    (when-not (and (= 5 (:schema-version artifact))
                   (re-matches #"cmas-2025-indoor-json/[0-9]+" (:parser-version artifact))
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
                   (vector? (get source "data"))
                   (< position (count (get source "data")))
                   (= (:raw payload) (get-in source ["data" position]))
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
                         ("propose" "decide" "effective" "history" "accept-extraction" "revoke-extraction" "extraction-effective" "extraction-history")
                         (if (= 1 (count args)) (({"propose" propose! "decide" decide! "effective" effective "history" history
                                                   "accept-extraction" accept-extraction! "revoke-extraction" revoke-extraction!
                                                   "extraction-effective" extraction-effective "extraction-history" extraction-history} command) url (read-request (first args)))
                             (fail! "Expected one EDN request file"))
                         (fail! "Commands: migrate INGEST-ROLE REVIEWER-ROLE | propose|decide|effective|history|accept-extraction|revoke-extraction|extraction-effective|extraction-history REQUEST.edn")))))
    (catch Exception e (binding [*out* *err*] (println "Review operation failed:" (.getMessage e))) (System/exit 1))))
