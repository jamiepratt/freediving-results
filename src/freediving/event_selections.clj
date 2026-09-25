(ns freediving.event-selections
  "Explicit source-bound event cutovers. Selection never grants extraction or identity approval."
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]
            [freediving.revisions :as revisions])
  (:import [java.sql Connection DriverManager] [java.security MessageDigest] [java.util HexFormat]))
(defn- fail! [message] (throw (ex-info message {})))
(defn- encode [x] (binding [*print-length* nil *print-level* nil] (pr-str x)))
(defn- sha [s] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))))
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
    (try (let [v (f c)] (.commit c) v) (catch Exception e (.rollback c) (throw e)))))
(defn installed? [c] (some? (:table_name (first (query c "SELECT to_regclass('freediving.event_selections') AS table_name")))))
(defn migrate! [url reviewer]
  (when-not (and (string? reviewer) (re-matches #"[a-z_][a-z0-9_]*" reviewer)) (fail! "Invalid role"))
  (transaction url
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246914)")
                 (when-not (:allowed (first (query c "SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolbypassrls AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) AND NOT EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')) AND NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')) AND NOT EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')) AS allowed FROM pg_roles WHERE rolname=?" reviewer reviewer reviewer reviewer)))
                   (fail! "Restricted reviewer role required"))
                 (let [sql (slurp (io/resource "migrations/010-event-selections.sql")) checksum (sha sql)]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=10"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Migration checksum conflict"))
                     (do (execute! c sql) (execute! c "INSERT INTO freediving.schema_migrations VALUES(10,?)" checksum))))
                 (execute! c "REVOKE ALL ON FUNCTION freediving.lock_selection_authority(),freediving.lock_revision_projection() FROM PUBLIC")
                 (execute! c "REVOKE ALL ON freediving.event_selections,freediving.event_coverage_cache FROM PUBLIC")
                 (execute! c (str "REVOKE ALL ON freediving.event_selections FROM " reviewer))
                 (execute! c (str "GRANT SELECT,INSERT ON freediving.event_selections TO " reviewer))
                 (execute! c (str "GRANT EXECUTE ON FUNCTION freediving.lock_selection_authority() TO " reviewer))
                 (execute! c (str "GRANT SELECT,INSERT,UPDATE,DELETE ON freediving.event_coverage_cache TO " reviewer))
                 (doseq [row (query c "SELECT grantee FROM information_schema.role_table_grants WHERE table_schema='freediving' AND table_name='public_results' AND privilege_type='SELECT' AND grantee<>'PUBLIC'")]
                   (let [role (:grantee row)]
                     (when-not (re-matches #"[a-z_][a-z0-9_]*" role) (fail! "Invalid projection role"))
                     (execute! c (str "GRANT SELECT ON freediving.public_event_coverage TO " role))))
                 {:schema-version 10})))
(defn snapshot-on [c]
  (into {} (for [[k sql] [[:selections "SELECT count(*) AS n FROM freediving.event_selections"]
                          [:relationships "SELECT count(*) AS n FROM freediving.revision_decisions"]
                          [:proposals "SELECT count(*) AS n FROM freediving.revision_proposals"]
                          [:reviews "SELECT count(*) AS n FROM freediving.review_decisions"]
                          [:validations "SELECT count(*) AS n FROM freediving.publication_decisions"]
                          [:policy "SELECT max(revision) AS n FROM freediving.publication_policy_events"]]]
             [k (:n (first (query c sql)))])))
(defn snapshot [url] (transaction url snapshot-on))
(defn- event-key [scope] (sha (encode (mapv scope revisions/event-fields))))
(declare scope!)
(defn- records [c]
  (mapv (fn [row]
          (let [b (edn/read-string (:body_edn row))]
            (when-not (= [(:id row) (:revision row) (:event_key row)] [(:id b) (:revision b) (event-key (:event-scope b))])
              (fail! "Selection envelope mismatch"))
            (doseq [d (concat (:members b) (map :descriptor (:retained b)))] (scope! c d))
            (assoc b :db-role (:db_role row) :recorded-at (str (:recorded_at row)))))
        (query c "SELECT * FROM freediving.event_selections ORDER BY revision")))
(defn history [url scope] (transaction url #(filterv (fn [r] (= scope (:event-scope r))) (records %))))
(defn- latest [rs] (vals (reduce #(assoc %1 (event-key (:event-scope %2)) %2) {} rs)))
(defn- ref-key [ref] [(:job-id ref) (:ordinal ref)])
(defn- eligible [c]
  (query c "SELECT p.* FROM freediving.publication_decisions p WHERE p.action='validate' AND p.policy_version IN ('extraction-publication/1','extraction-publication/2') AND p.policy_version=(SELECT policy_version FROM freediving.publication_policy_events ORDER BY revision DESC LIMIT 1) AND NOT EXISTS(SELECT 1 FROM freediving.publication_decisions n WHERE n.job_id=p.job_id AND n.ordinal=p.ordinal AND n.revision>p.revision) AND p.review_revision=COALESCE((SELECT max(r.revision) FROM freediving.review_decisions r WHERE r.job_id=p.job_id AND r.ordinal=p.ordinal),0)"))
(defn- validation! [c {:keys [reference validation-id]}]
  (when-not (some #(and (= validation-id (:id %)) (= (ref-key reference) [(:job_id %) (:ordinal %)])) (eligible c))
    (fail! "Selected observation requires its exact current extraction validation")))
(defn- nonblank? [s] (and (string? s) (not (str/blank? s))))
(defn- scope! [c d]
  (let [scope (revisions/descriptor-values c d)
        payload (edn/read-string (:payload_edn (first (query c "SELECT payload_edn FROM freediving.observations WHERE job_id=? AND ordinal=?" (get-in d [:reference :job-id]) (get-in d [:reference :ordinal])))))]
    (when (= :ranking (:source-family payload)) (fail! "Ranking records are not sporting attempts"))
    (when-not (and (every? #(nonblank? (str (get scope % ""))) revisions/event-fields)
                   (some #(nonblank? (str (get scope % ""))) [:bib :source-athlete-id]))
      (fail! "Complete event and own-row athlete scope required")) scope))
(defn- equivalent? [c a b]
  ;; Equal scoped row text/position and original source bytes, never name/hash alone.
  (and (= (:source-sha256 a) (:source-sha256 b))
       (= (:candidate-id a) (:candidate-id b))
       (= (:payload_edn (first (query c "SELECT payload_edn FROM freediving.observations WHERE job_id=? AND ordinal=?" (:job-id a) (:ordinal a))))
          (:payload_edn (first (query c "SELECT payload_edn FROM freediving.observations WHERE job_id=? AND ordinal=?" (:job-id b) (:ordinal b)))))))
(defn- relationships [c] (:relationships (revisions/snapshot-on c)))
(defn- selected-history! [rels entry]
  (let [ref (:reference entry)
        incoming (filter #(= ref (get-in % [:request :successor :reference])) rels)
        named (some #(when (= (:relationship-id entry) (:id %)) %) incoming)]
    (when (and (:relationship-id entry) (nil? named)) (fail! "Relationship does not bind selected successor"))
    (when (seq incoming)
      (when-not (and named (#{:confirmed-replacement :reviewed-missing-history} (:status named)))
        (fail! "Unreviewed or rejected successor cannot be selected")))
    (when named
      {:status (if (= :confirmed-replacement (:status named)) :confirmed-correction :history-unavailable)
       :previous-values :unknown})))
(defn- same-attempt? [a b]
  (and (or (nil? (:attempt a)) (nil? (:attempt b)) (= (:attempt a) (:attempt b)))
       (some #(and (some? (a %)) (= (a %) (b %))) [:bib :source-athlete-id])))
(defn- check! [c r rs]
  (let [members (:members r) selected (:selected r) scope (:event-scope r)
        values (mapv #(scope! c %) members)
        refs (set (map :reference members)) rels (relationships c)
        previous (some #(when (= scope (:event-scope %)) %) (latest rs))]
    (when-not (and (map? scope) (= (set revisions/event-fields) (set (keys scope)))
                   (vector? members) (seq members) (vector? selected)
                   (= (count refs) (count members))
                   (every? #(= scope (select-keys % revisions/event-fields)) values))
      (fail! "Exact scoped event inventory required"))
    (when-not (and (#{:partial :complete} (get-in r [:coverage :completeness]))
                   (= #{:completeness :gaps} (set (keys (:coverage r))))
                   (vector? (get-in r [:coverage :gaps]))
                   (every? nonblank? (get-in r [:coverage :gaps]))
                   (if (= :partial (get-in r [:coverage :completeness])) (seq (get-in r [:coverage :gaps])) (empty? (get-in r [:coverage :gaps]))))
      (fail! "Explicit scoped coverage and gaps required"))
    (doseq [other (latest rs) :when (not= scope (:event-scope other))]
      (when (some refs (map :reference (:members other))) (fail! "Observation already belongs to another event scope")))
    (doseq [{:keys [descriptor]} (:retained (first rs))
            :when (refs (:reference descriptor))]
      (when-not (= scope (select-keys (scope! c descriptor) revisions/event-fields))
        (fail! "Observation retained under another event scope")))
    (doseq [{:keys [descriptor]} (:retained (first rs))
            :when (= scope (select-keys (scope! c descriptor) revisions/event-fields))]
      (when-not (refs (:reference descriptor)) (fail! "Previous retained event inventory cannot be omitted")))
    (when-not (every? refs (map :reference (:members previous))) (fail! "Previous event inventory cannot be silently omitted"))
    (when-not (= (count selected) (count (set (map :reference selected)))) (fail! "Duplicate selected observation"))
    (doseq [entry selected]
      (when-not (and (every? #{:reference :validation-id :relationship-id} (keys entry)) (refs (:reference entry)))
        (fail! "Selected reference must be an exact inventoried version"))
      (validation! c entry) (selected-history! rels entry))
    (let [pairs (map vector members values)
          groups (map (fn [[_ v]] (filter #(same-attempt? v (second %)) pairs)) pairs)]
      (doseq [a values b values :when (same-attempt? a b)]
        (when (not= (contains? a :attempt) (contains? b :attempt))
          (fail! "Ambiguous sporting attempt discriminator"))
        (when (some #(and (some? (a %)) (some? (b %)) (not= (a %) (b %))) [:bib :source-athlete-id])
          (fail! "Conflicting sporting attempt identifiers")))
      (doseq [group groups]
        (let [group-refs (set (map (comp :reference first) group)) choices (filter #(group-refs (:reference %)) selected)]
          (when (and (= :complete (get-in r [:coverage :completeness])) (empty? choices)) (fail! "Complete coverage requires every known sporting attempt"))
          (when (> (count choices) 1) (fail! "One sporting attempt cannot select multiple versions"))
          (when-let [choice (first choices)]
            (doseq [other (disj group-refs (:reference choice))]
              (when-not (or (equivalent? c other (:reference choice))
                            (some #(and (= :confirmed-replacement (:status %))
                                        (= #{other (:reference choice)} (set (map :reference ((juxt :predecessor :successor) (:request %)))))) rels)
                            ;; An explicit first/current predecessor remains public while a successor is unresolved.
                            (some #(and (= other (get-in % [:request :successor :reference]))
                                        (= (:reference choice) (get-in % [:request :predecessor :reference]))) rels))
                (fail! "Excluded duplicate requires source equivalence or explicit revision relationship")))))))
    (when (empty? rs)
      (let [retained (:retained r []) retained-refs (set (map (comp :reference :descriptor) retained))
            existing (set (map #(vector (:job_id %) (:ordinal %)) (eligible c)))
            classified (set (map ref-key (concat refs retained-refs)))]
        (when-not (and (vector? retained) (every? #(= #{:descriptor :validation-id} (set (keys %))) retained)
                       (= (count retained) (count retained-refs)) (not-any? refs retained-refs))
          (fail! "Invalid or contradictory retained inventory"))
        (when-not (every? classified existing) (fail! "Initial cutover must retain or explicitly inventory every eligible observation"))
        (doseq [{:keys [descriptor validation-id]} retained]
          (when (= scope (select-keys (scope! c descriptor) revisions/event-fields)) (fail! "Same event cannot be retained outside inventory"))
          (validation! c {:reference (:reference descriptor) :validation-id validation-id}))))
    (when (and (seq rs) (contains? r :retained)) (fail! "Retained baseline is fixed at initial cutover"))))
(defn projection-plan [c validations]
  (if-not (installed? c) {:validations validations :metadata {}}
          (let [rs (records c)]
            (if (empty? rs) {:validations validations :metadata {}}
                (let [active (latest rs) rels (relationships c)
                      claimed (set (map :event-scope active))
                      retained (for [{:keys [descriptor validation-id]} (:retained (first rs))
                                     :when (not (claimed (select-keys (scope! c descriptor) revisions/event-fields)))]
                                 {:reference (:reference descriptor) :validation-id validation-id})
                      entries (concat (map #(vector % nil) retained)
                                      (for [r active entry (:selected r)] [entry r]))
                      allowed (into {} (keep (fn [[entry r]]
                                               (try
                                                 (validation! c entry)
                                                 (let [h (selected-history! rels entry)]
                                                   [(ref-key (:reference entry))
                                                    (cond-> {:validation-id (:validation-id entry)}
                                                      r (assoc :event-selection {:id (sha (:id r)) :revision (:revision r)}
                                                               :coverage (assoc (:coverage r) :scope :event))
                                                      h (assoc :revision-history h))])
                                                 (catch Exception _ nil))) entries))]
                  {:validations (filterv #(= (:id %) (:validation-id (allowed [(:job_id %) (:ordinal %)]))) validations)
                   :metadata allowed
                   :expected (into {} (map (fn [r] [(sha (:id r)) (count (:selected r))]) active))})))))
(defn refresh-coverage! [c]
  (when (installed? c)
    (let [snapshot (snapshot-on c) rels (relationships c)]
      (execute! c "DELETE FROM freediving.event_coverage_cache")
      (doseq [r (latest (records c))
              :when (every? (fn [entry] (try (validation! c entry) (selected-history! rels entry)
                                             (boolean (seq (query c "SELECT result_id FROM freediving.public_projection_cache WHERE job_id=? AND ordinal=? AND validation_id=?" (get-in entry [:reference :job-id]) (get-in entry [:reference :ordinal]) (:validation-id entry)))) (catch Exception _ false))) (:selected r))]
        (execute! c "INSERT INTO freediving.event_coverage_cache VALUES(?,?,?,?,?,?,?,?)"
                  (event-key (:event-scope r))
                  (encode (assoc (:coverage r) :scope :event :event (:event-scope r) :selection-id (sha (:id r))))
                  (:selections snapshot) (:relationships snapshot) (:proposals snapshot)
                  (:reviews snapshot) (:validations snapshot) (:policy snapshot))))))
(defn event-coverage [c]
  (when (installed? c)
    (mapv (comp edn/read-string :body_edn) (query c "SELECT body_edn FROM freediving.public_event_coverage"))))
(defn- append! [url request rollback?]
  (when-not (every? (if rollback? #{:id :actor :reason :base :selection-id}
                        #{:id :actor :reason :base :event-scope :members :selected :coverage :retained}) (keys request))
    (fail! "Unexpected selection request fields"))
  (transaction url
               (fn [c]
                 (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.event_selections','INSERT') AND has_table_privilege(current_user,'freediving.publication_decisions','INSERT') AS allowed")))
                   (fail! "Selection reviewer capability required"))
                 (query c "SELECT pg_advisory_xact_lock(781246915)")
                 (query c "SELECT pg_advisory_xact_lock(781246918)")
      ;; Block concurrent authority writes through commit, including direct SQL writers.
                 (query c "SELECT freediving.lock_selection_authority()")
                 (when-not (and (every? nonblank? ((juxt :id :actor :reason) request)) (map? (:base request))) (fail! "Selection audit and snapshot required"))
                 (let [rs (records c) old (some #(when (= (:id request) (:id %)) %) rs)]
                   (if old
                     (do (when-not (= request (:request old)) (fail! "Conflicting idempotency key")) old)
                     (let [_ (when-not (= (:base request) (snapshot-on c)) (fail! "Stale selection authority snapshot"))
                           prior (when rollback? (some #(when (= (:selection-id request) (:id %)) %) rs))
                           _ (when (and rollback? (nil? prior)) (fail! "Unknown rollback selection"))
                           r (if rollback? (merge (select-keys prior [:event-scope :members :selected :coverage]) request) request)
                ;; Keep full known event inventory on rollback; approval IDs remain exactly historical.
                           r (if rollback? (assoc r :members (:members (last (filter #(= (:event-scope r) (:event-scope %)) rs)))) r)]
                       (check! c r rs)
                       (let [record (assoc r :request request :revision (inc (count rs)) :action (if rollback? :rollback :select))]
                         (execute! c "INSERT INTO freediving.event_selections(id,revision,event_key,body_edn) VALUES(?,?,?,?)" (:id r) (:revision record) (event-key (:event-scope r)) (encode record))
                         ((requiring-resolve 'freediving.public-results/refresh-on!) c)
                         (doseq [entry (:selected record)]
                           (when-not (seq (query c "SELECT result_id FROM freediving.public_projection_cache WHERE job_id=? AND ordinal=? AND validation_id=?" (get-in entry [:reference :job-id]) (get-in entry [:reference :ordinal]) (:validation-id entry)))
                             (fail! "Selected observation cannot produce an eligible public projection")))
                         (doseq [{:keys [descriptor validation-id]} (:retained record)]
                           (when-not (seq (query c "SELECT result_id FROM freediving.public_projection_cache WHERE job_id=? AND ordinal=? AND validation_id=?" (get-in descriptor [:reference :job-id]) (get-in descriptor [:reference :ordinal]) validation-id))
                             (fail! "Retained baseline cannot survive unchanged; explicit source review required")))
                         (last (records c)))))))))
(defn select! [url request] (append! url request false))
(defn rollback! [url request] (append! url request true))
