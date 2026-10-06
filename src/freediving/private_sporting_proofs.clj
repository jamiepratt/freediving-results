(ns freediving.private-sporting-proofs
  "Restricted exact-row authority readback. Historical receipts never grant current facts."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [freediving.aida-html :as html]
            [freediving.html-evidence :as evidence]
            [freediving.publication :as publication]
            [freediving.event-selections :as selections]
            [freediving.revisions :as revisions]
            [freediving.canonical-attempt-store :as attempt])
  (:import [java.sql Connection DriverManager]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def tables
  ["extractions" "observations" "extraction_reviews" "pdf_extraction_reviews"
   "review_proposals" "review_decisions" "publication_decisions" "publication_policy_events"
   "revision_proposals" "revision_decisions" "event_selections"
   "canonical_attempt_evidence" "canonical_attempt_events" "canonical_attempt_state"])
(def reference-keys #{:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256 :parser-version})
(defn- need! [ok message] (when-not ok (throw (ex-info message {}))))
(defn- sha [^bytes bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
    (sequential? value) (mapv canonical value)
    (bytes? value) {:bytes-sha256 (sha value)}
    (instance? java.sql.Timestamp value) (str value)
    :else value))
(defn- digest [value]
  (sha (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str (canonical value))) "UTF-8")))
(defn- query [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)]
        (loop [rows []]
          (if (.next r)
            (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))]
                                         [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows))))))
(defn- snapshot [url f]
  (with-open [c (DriverManager/getConnection url)]
    (.setReadOnly c true)
    (.setTransactionIsolation c Connection/TRANSACTION_REPEATABLE_READ)
    (.setAutoCommit c false)
    (try (let [result (f c)] (.commit c) result)
         (catch Exception e (.rollback c) (throw e)))))
(defn- mode-tables [mode]
  (if (= mode "source") (take 11 tables)
      ["extractions" "observations" "canonical_attempt_evidence" "canonical_attempt_events" "canonical_attempt_state"]))
(defn- capability! [c mode]
  (let [role (first (query c "SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolinherit,rolreplication,rolbypassrls,rolcanlogin FROM pg_roles WHERE rolname=current_user"))
        privileges (query c "SELECT n.nspname AS schema,c.relname AS name,p.privilege FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN (VALUES('SELECT'),('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE'),('REFERENCES'),('TRIGGER')) p(privilege) WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%' AND c.relkind IN ('r','p','v','m','f') AND has_table_privilege(current_user,c.oid,p.privilege)")
        unsafe (first (query c "SELECT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user) OR roleid=(SELECT oid FROM pg_roles WHERE rolname=current_user)) OR EXISTS(SELECT 1 FROM pg_class c WHERE c.relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user)) OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspowner=(SELECT oid FROM pg_roles WHERE rolname=current_user)) OR EXISTS(SELECT 1 FROM pg_proc WHERE proowner=(SELECT oid FROM pg_roles WHERE rolname=current_user)) OR EXISTS(SELECT 1 FROM pg_database WHERE datdba=(SELECT oid FROM pg_roles WHERE rolname=current_user)) OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('pg_catalog','information_schema') AND nspname NOT LIKE 'pg_toast%' AND nspname NOT LIKE 'pg_temp%' AND has_schema_privilege(current_user,oid,'CREATE')) OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND c.relkind='S' AND (has_sequence_privilege(current_user,c.oid,'SELECT') OR has_sequence_privilege(current_user,c.oid,'UPDATE') OR has_sequence_privilege(current_user,c.oid,'USAGE'))) OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE p.prosecdef AND n.nspname NOT IN ('pg_catalog','information_schema') AND has_function_privilege(current_user,p.oid,'EXECUTE')) OR EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN LATERAL aclexplode(a.attacl) x WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND x.grantee IN (0,(SELECT oid FROM pg_roles WHERE rolname=current_user)) AND (x.privilege_type<>'SELECT' OR NOT has_table_privilege(current_user,a.attrelid,'SELECT'))) OR EXISTS(SELECT 1 FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) x WHERE x.grantee IN (0,(SELECT oid FROM pg_roles WHERE rolname=current_user))) AS unsafe"))]
    (when (>= (parse-long (:version (first (query c "SELECT current_setting('server_version_num') AS version")))) 170000)
      (need! (empty? (query c "SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND c.relkind IN ('r','p','v','m','f') AND has_table_privilege(current_user,c.oid,'MAINTAIN')"))
             "Sporting proof reader capability changed"))
    (need! (and (:rolcanlogin role)
                (not-any? true? ((juxt :rolsuper :rolcreatedb :rolcreaterole :rolinherit :rolreplication :rolbypassrls) role))
                (not (:unsafe unsafe))
                (= "on" (:value (first (query c "SELECT current_setting('default_transaction_read_only') AS value"))))
                (= (set (map #(hash-map :schema "freediving" :name % :privilege "SELECT") (mode-tables mode))) (set privileges)))
           "Sporting proof reader capability changed")))
(defn- authority [c mode]
  (into {} (map (fn [table] [(keyword table) (query c (str "SELECT * FROM freediving." table))]) (mode-tables mode))))
(defn- fingerprint
  "V2 pin: PostgreSQL hashes every raw row column, including bytes and timestamps.
   Sorted per-table hash vectors preserve duplicate multiplicity without exposing
   private artifacts to pin-only readers. No persisted or cross-request cache."
  [c mode]
  (digest {:method "postgresql-row-json-sha256/v2"
           :tables (into {} (map (fn [table]
                                   (let [hashes (mapv :row_sha
                                                      (query c (str "SELECT pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(pg_catalog.row_to_json(t)::text,'UTF8')),'hex') AS row_sha FROM freediving."
                                                                    table " t")))]
                                     (need! (every? #(and (string? %) (re-matches #"[0-9a-f]{64}" %)) hashes)
                                            "Sporting proof raw authority hash invalid")
                                     [(keyword table) (vec (sort hashes))]))
                                 (mode-tables mode)))}))
(defn- row-request! [{:keys [reference coordinates] :as row}]
  (need! (and (= #{:reference :coordinates} (set (keys row)))
              (= reference-keys (set (keys reference)))
              (nat-int? (:ordinal reference))
              (every? #(and (string? %) (re-matches #"[0-9a-f]{64}" %))
                      ((juxt :job-id :candidate-id :source-sha256 :artifact-sha256) reference))
              (string? (:parser-version reference)) (seq (:parser-version reference))
              (or (and (= #{:page :line} (set (keys coordinates))) (every? pos-int? (vals coordinates)))
                  (and (= #{:table :row} (set (keys coordinates))) (every? nat-int? (vals coordinates)))
                  (and (= #{:page :line :column-start :column-end} (set (keys coordinates)))
                       (every? pos-int? (vals coordinates)) (< (:column-start coordinates) (:column-end coordinates)))))
         "Invalid exact sporting row request"))
(defn- gap [row state reason]
  (assoc row :upstream {} :diagnostics {:mapping {:state state :reasons [reason]}
                                        :review {:state "unknown"}
                                        :same-attempt {:state "unknown" :reasons ["exact-distinctness-authority-required"]}
                                        :source-conflict {:state "unresolved" :reasons ["exact-source-conflict-authority-required"]}
                                        :source-selection {:state "unknown"}
                                        :publication {:state "unknown"}}))
(defn- base-reference [row extraction]
  {:job-id (:job_id row) :ordinal (:ordinal row) :candidate-id (:candidate_id row)
   :source-sha256 (:source_sha256 extraction) :artifact-sha256 (:artifact_sha256 extraction)
   :parser-version (:parser_version extraction)})
(def ^:dynamic *decoded-artifacts* nil)
(defn- artifact-binding [extraction]
  (let [artifact (edn/read-string (String. ^bytes (:artifact_bytes extraction) "UTF-8"))
        identity-keys (if (= 4 (:schema-version artifact)) html/identity-keys
                          [:source-sha256 :acquisitions :evidence-sha256 :actor :config
                           :parser-version :schema-version :pdfinfo-version :tool])]
    {:artifact artifact :artifact-sha256 (sha (:artifact_bytes extraction))
     :job-id (html/digest (select-keys artifact identity-keys))}))
(defn- exact-target [state {:keys [reference coordinates]}]
  (when-let [row (some #(when (= ((juxt :job-id :ordinal) reference) ((juxt :job_id :ordinal) %)) %) (:observations state))]
    (let [extraction (some #(when (= (:job-id reference) (:job_id %)) %) (:extractions state))
          binding (or (when *decoded-artifacts* (get @*decoded-artifacts* (:job-id reference)))
                      (let [value (artifact-binding extraction)]
                        (when *decoded-artifacts* (swap! *decoded-artifacts* assoc (:job-id reference) value)) value))
          artifact (:artifact binding)
          payload (edn/read-string (:payload_edn row))
          actual (base-reference row extraction)]
      (need! (and (= actual reference)
                  (= (:artifact-sha256 reference) (:artifact-sha256 binding))
                  (= (:job-id reference) (:job-id binding))
                  (= payload (get (:candidates artifact) (:ordinal reference)))
                  (= "result-row" (:kind row))
                  (= (:candidate-id reference)
                     (html/digest [(:source-sha256 reference)
                                   (or (when (seq (:source-lines payload))
                                         (mapv #(select-keys % [:page :line]) (:source-lines payload)))
                                       [(evidence/coordinates artifact payload)])]))
                  (= coordinates (:coordinates payload))
                  (seq (:acquisitions artifact))
                  (every? #(= (:source-sha256 reference) (get-in % [:manifest :sha256])) (:acquisitions artifact)))
             "Exact row source/artifact/parser/coordinate mismatch")
      (when (evidence/html? artifact)
        (evidence/bound-context! artifact payload (:ordinal reference) (:source-sha256 reference)))
      {:row row :artifact artifact :payload payload})))
(defn- target-events [state table reference]
  (vec (sort-by :revision (filter #(= ((juxt :job-id :ordinal) reference) ((juxt :job_id :ordinal) %)) (get state table)))))
(defn- extraction-proof [state reference artifact coordinates]
  (let [table (if (= 4 (:schema-version artifact)) :extraction_reviews :pdf_extraction_reviews)
        rows (target-events state table reference)
        events (mapv #(edn/read-string (:body_edn %)) rows)
        last-row (last rows) event (last events)]
    (doseq [[index [row receipt]] (map-indexed vector (map vector rows events))]
      (need! (and (= (inc index) (:revision row))
                  (= index (:base-revision receipt))
                  (#{:accept :revoke} (:action receipt))
                  (= [(:job_id row) (:ordinal row)] ((juxt :job-id :ordinal) reference))
                  (= (:event_id row) (:event-id receipt))
                  (= [(:id row) (:revision row) (:action row)]
                     [(:id receipt) (:revision receipt) (name (:action receipt))])
                  (= (:request receipt) (dissoc receipt :request :action :revision))
                  (= reference (select-keys (:evidence receipt) reference-keys))
                  (= (select-keys coordinates [:page :line])
                     (select-keys (:evidence receipt) [:page :line]))
                  (= :pdf (get-in receipt [:evidence :source-kind]))
                  (= (:schema-version artifact) (get-in receipt [:evidence :schema-version]))
                  (= (str "local-observation:" (:job-id reference) ":" (:ordinal reference))
                     (get-in receipt [:evidence :observation-id]))
                  (some #(and (= (:acquisition-id %) (get-in receipt [:evidence :acquisition-id]))
                              (= (:source-sha256 reference) (get-in % [:manifest :sha256]))
                              (= "application/pdf" (get-in % [:manifest :content-type]))) (:acquisitions artifact)))
             "Extraction receipt scope changed"))
    (reduce (fn [active [row receipt]]
              (case (:action receipt)
                :accept (do (need! (and (nil? active) (nil? (:event-id receipt))) "Extraction acceptance predecessor changed") (:id receipt))
                :revoke (do (need! (and active (= active (:event-id receipt) (:event_id row))) "Extraction revocation predecessor changed") nil)))
            nil (map vector rows events))
    {:state (cond (nil? event) "unreviewed" (= :accept (:action event)) "accepted" :else "revoked")
     :revision (or (:revision event) 0)
     :event_sha256 (when event (digest last-row))
     :history_sha256 (mapv digest rows)
     :authority "exact-extraction-accuracy-review"
     :current_event (when event {:id (:id event) :revision (:revision event) :action (name (:action event))
                                 :reason (subs (:reason event) 0 (min 500 (count (:reason event))))
                                 :acquisition_id (get-in event [:evidence :acquisition-id])})
     :proof (when (= :accept (:action event)) {:value "verified" :event_sha256 (digest last-row)})}))
(defn- revision-diagnostic [revision-state reference]
  (let [rows (filter #(some #{(dissoc reference :parser-version)}
                            [(get-in % [:request :predecessor :reference]) (get-in % [:request :successor :reference])])
                     (:relationships revision-state))]
    {:state (if (seq rows) "reviewed-relationships" "unknown")
     :revision (:revision revision-state)
     :relationships (mapv #(assoc (select-keys % [:id :status :match :previous-values])
                                  :role (if (= (dissoc reference :parser-version) (get-in % [:request :predecessor :reference]))
                                          "predecessor" "successor")) rows)
     :history_sha256 (mapv digest rows)}))
(defn- relationship-diagnostic [current reference coordinates]
  (let [ledger (:ledger current)
        revision {:job_id (:job-id reference) :ordinal (:ordinal reference)
                  :candidate_id (:candidate-id reference) :source_sha256 (:source-sha256 reference)
                  :artifact_sha256 (:artifact-sha256 reference) :parser_version (:parser-version reference)}
        versions (filter #(= revision (select-keys (:observation-revision %) (keys revision)))
                         (vals (:observation-versions ledger)))
        ids (set (map :id versions))
        groups (filter #(some ids (:observation-ids %)) (get-in current [:projection :attempts]))
        events (filter #(some ids (:pair %)) (:events ledger))
        reversed (set (keep #(when (= :reverse (:action %)) (:event-id %)) (:events ledger)))
        endpoint (fn [id]
                   (let [v (get-in ledger [:observation-versions id :observation-revision])]
                     (when (every? #(contains? v %) [:job_id :ordinal :candidate_id :source_sha256 :artifact_sha256 :parser_version])
                       {:job-id (:job_id v) :ordinal (:ordinal v) :candidate-id (:candidate_id v)
                        :source-sha256 (:source_sha256 v) :artifact-sha256 (:artifact_sha256 v)
                        :parser-version (:parser_version v)})))]
    {:state (cond (nil? current) "uninitialized" (empty? ids) "endpoint-unmapped" :else "current-exact-endpoint")
     :revision (get-in current [:projection :revision])
     :coordinates coordinates
     :groups (mapv #(select-keys % [:id :status :origin :observation-ids]) groups)
     :exact_relationships (mapv (fn [event]
                                  {:type (name (:type event)) :action (name (:action event))
                                   :current (boolean (and (= :accept (:action event))
                                                          (not (reversed (:id event)))
                                                          (not (contains? (:invalidated-events ledger) (:id event)))))
                                   :pair (mapv endpoint (:pair event)) :event_sha256 (digest event)}) events)
     :history_sha256 (mapv digest events)
     :reasons ["group-membership-does-not-prove-cohort-distinctness"]}))
(defn- checked-validations! [validations reference coordinates]
  (doseq [[index row] (map-indexed vector validations)]
    (let [record (edn/read-string (:body_edn row)) request (:request record)]
      (need! (and (= (inc index) (:revision row))
                  (= index (:base-revision record))
                  (#{:validate :revoke} (:action record))
                  (= [(:job_id row) (:ordinal row)] ((juxt :job-id :ordinal) reference))
                  (= [(:id row) (:revision row) (:review_revision row) (:policy_version row) (:action row)]
                     [(:id record) (:revision record) (:review-revision record) (:policy-version record) (name (:action record))])
                  (= request (dissoc record :request :revision))
                  (= ((juxt :job-id :ordinal) reference) ((juxt :job-id :ordinal) record))
                  (= (dissoc reference :parser-version) (:observation record))
                  (= [(:candidate_id row) (:artifact_sha256 row) (:source_sha256 row)]
                     ((juxt :candidate-id :artifact-sha256 :source-sha256) reference))
                  (some #{(select-keys coordinates (if (contains? coordinates :table) [:table :row] [:page :line]))} (:evidence record))
                  (or (= :revoke (:action record))
                      (= {:source-visual-accuracy true :no-unresolved-substantive-errors true} (:attestations record))))
             "Publication receipt scope or attestations changed"))))
(defn- public-reference [c reference]
  (merge (select-keys reference [:ordinal :source-sha256 :artifact-sha256])
         {:result-id (sha (.getBytes (str (:job-id reference) "/" (:ordinal reference)) "UTF-8"))
          :observation-id (:id (first (query c "SELECT encode(sha256(convert_to(row(?,?,?,?)::text,'UTF8')),'hex') AS id"
                                             (:job-id reference) (:ordinal reference) (:candidate-id reference) (:artifact-sha256 reference))))}))
(defn- mapped-proof [c state mode relationship-state revision-state publication-state plan row]
  (try
    (if-let [{:keys [artifact]} (exact-target state row)]
      (let [reference (:reference row) coordinates (:coordinates row)
            mapped (assoc-in (gap row "mapped" "full-exact-source-binding-verified")
                             [:diagnostics :mapping :source_view_sha256]
                             (digest (select-keys artifact [:acquisitions :context :source-page-url :view-url])))]
        (if (= mode "relationships")
          (assoc-in mapped [:diagnostics :same-attempt]
                    (relationship-diagnostic relationship-state reference coordinates))
          (let [review (extraction-proof state reference artifact coordinates)
                diagnosis (get publication-state ((juxt :job-id :ordinal) reference))
                validations (target-events state :publication_decisions reference)
                latest (last validations)
                _ (checked-validations! validations reference coordinates)
                review (if (and (evidence/html? artifact) (:eligible? diagnosis))
                         {:state "verified-by-current-html-source-validation" :revision (:revision diagnosis)
                          :event_sha256 (digest latest) :history_sha256 (mapv digest validations)
                          :authority "current-html-source-visual-validation"
                          :current_event (let [record (edn/read-string (:body_edn latest))]
                                           {:id (:id latest) :revision (:revision latest) :action (:action latest)
                                            :reason (subs (:reason record) 0 (min 500 (count (:reason record))))})
                          :proof {:value "verified" :event_sha256 (digest latest)}} review)
                eligible (and (:eligible? diagnosis) (some #(= (:id latest) (:id %)) (:validations plan)))
                public-ref (when eligible (public-reference c reference))
                proof (when eligible {:value "approved" :event_sha256 (digest {:validation latest :policy (:publication_policy_events state)
                                                                               :selection (:metadata plan)}) :reference public-ref})]
            (cond-> (-> mapped
                        (assoc-in [:diagnostics :review] (dissoc review :proof))
                        (assoc-in [:diagnostics :source-revision] (revision-diagnostic revision-state reference))
                        (assoc-in [:diagnostics :source-selection]
                                  {:state (if eligible "currently-permitted" "not-currently-permitted")
                                   :reasons ["event-selection-does-not-establish-source-authority"]
                                   :revision (count (:event_selections state))
                                   :event_sha256 (digest (:metadata plan))})
                        (assoc-in [:diagnostics :publication]
                                  {:state (if eligible "approved" "not-approved")
                                   :revision (:revision diagnosis) :review_revision (:review-revision diagnosis)
                                   :policy_version (:policy-version diagnosis)
                                   :active_policy_version (:active-policy-version diagnosis)
                                   :reasons (cond-> (:reasons diagnosis) (and (:eligible? diagnosis) (not eligible))
                                                    (conj :current-selection-required))
                                   :authority "exact-source-publication-validation"
                                   :current_event (when latest (let [record (edn/read-string (:body_edn latest))]
                                                                 {:id (:id latest) :revision (:revision latest) :action (:action latest)
                                                                  :reason (subs (:reason record) 0 (min 500 (count (:reason record))))}))
                                   :history_sha256 (mapv digest validations)}))
              (:proof review) (assoc-in [:upstream :review] (:proof review))
              proof (assoc-in [:upstream :publication] proof)
              proof (assoc :public_reference public-ref)))))
      (cond-> (gap row "not-imported" "exact-canonical-import-missing")
        (= mode "relationships") (assoc-in [:diagnostics :same-attempt]
                                           (relationship-diagnostic relationship-state (:reference row) (:coordinates row)))))
    (catch Exception _ (gap row "scope-mismatch" "source-parser-row-or-current-authority-integrity-failed"))))
(defn read-proofs
  "One global authority pin independent of requested rows. Recheck after all owned
   API reads; concurrent mutation refuses the entire result, including old links."
  [{:keys [jdbc_url database rows mode] :as config}]
  (need! (and (= #{:jdbc_url :database :rows :mode} (set (keys config)))
              (#{"source" "relationships"} mode) (string? jdbc_url) (string? database) (vector? rows) (<= (count rows) 400))
         "Invalid sporting proof reader configuration")
  (doseq [row rows] (row-request! row))
  (need! (= (count rows) (count (set (map :reference rows)))) "Duplicate exact rows")
  (let [result (snapshot jdbc_url
                         (fn [c]
                           (need! (= database (:database (first (query c "SELECT current_database() AS database"))))
                                  "Sporting proof database changed")
                           (capability! c mode)
                           (let [pin (fingerprint c mode) state (when (seq rows) (authority c mode))
                                 relationship-state (when (and (seq rows) (= mode "relationships") (seq (:canonical_attempt_state state)))
                                                      (attempt/private-readback jdbc_url))
                                 revision-state (when (and (seq rows) (= mode "source")) (revisions/diagnostics jdbc_url))
                                 targets (filterv (fn [row] (some #(= ((juxt :job-id :ordinal) (:reference row))
                                                                      ((juxt :job_id :ordinal) %)) (:observations state))) rows)
                                 publication-state (when (and (seq rows) (= mode "source"))
                                                     (into {} (map (juxt (juxt :job-id :ordinal) identity)
                                                                   (publication/diagnose-many jdbc_url (mapv :reference targets)))))
                                 eligible-targets (when (= mode "source")
                                                    (keep (fn [row]
                                                            (let [reference (:reference row)]
                                                              (when (:eligible? (get publication-state ((juxt :job-id :ordinal) reference)))
                                                                (last (target-events state :publication_decisions reference))))) targets))
                                 plan (when (and (= mode "source") (seq targets))
                                        (revisions/with-verified-snapshot-cache
                                          c #(selections/projection-plan c (vec eligible-targets))))]
                             {:schema "private-sporting-proofs/v1" :database database :binding_sha256 pin
                              :rows (binding [publication/*artifacts* (atom {}) *decoded-artifacts* (atom {})]
                                      (mapv #(mapped-proof c state mode relationship-state revision-state publication-state plan %) rows))})))]
    (need! (= (:binding_sha256 result) (snapshot jdbc_url (fn [c] (capability! c mode) (fingerprint c mode))))
           "Canonical sporting authority changed during read")
    result))
(defn -main [& _]
  (try (println (json/write-str (read-proofs (json/read-str (slurp *in*) :key-fn keyword))))
       (catch Exception _
         (binding [*out* *err*] (println "Private sporting proof readback unavailable"))
         (System/exit 1))))
