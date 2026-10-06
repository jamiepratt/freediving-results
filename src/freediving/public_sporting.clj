(ns freediving.public-sporting
  "Public sporting authority is separate from source publication. No public write capability."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.attempt-comparison :as attempts]
            [freediving.comparison-score :as scores]
            [freediving.peer-scope :as peers]
            [freediving.sporting-authority :as authority])
  (:import [java.sql DriverManager Connection]
           [java.security MessageDigest]
           [java.util HexFormat]))
(def target "2026-pool-dnf-women")
(def scope {:year 2026 :environment :pool :discipline "DNF" :gender :women :category :seniors})
(defn sha [value]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256")
                                      (.getBytes (str value) "UTF-8"))))
(defn- fail! [status] (throw (ex-info "Public comparison unavailable" {:status status})))
(defn- query [c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)]
        (loop [rows []]
          (if (.next r)
            (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))]
                                         [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows))))))
(defn migrate!
  "Install the empty projection as database owner. This grants no authority writer capability."
  [url public-role]
  (when-not (re-matches #"[a-z_][a-z0-9_]*" public-role) (fail! 400))
  (with-open [c (DriverManager/getConnection url)]
    (.setAutoCommit c false)
    (try
      (query c "SELECT pg_advisory_xact_lock(781246914)")
      (doseq [[version resource] [[22 "migrations/022-public-sporting-comparison.sql"]
                                  [23 "migrations/023-sporting-authority-bridge.sql"]]]
        (let [sql (slurp (io/resource resource)) digest (sha sql)
              prior (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=?" version))]
          (if prior
            (when-not (= digest (:sha256 prior)) (fail! 503))
            (do (with-open [s (.createStatement c)] (.execute s sql))
                (with-open [s (.prepareStatement c "INSERT INTO freediving.schema_migrations VALUES(?,?)")]
                  (.setInt s 1 version) (.setString s 2 digest) (.executeUpdate s))))))
      (with-open [s (.createStatement c)]
        (.execute s (str "GRANT SELECT ON freediving.public_sporting_comparison TO " public-role)))
      (.commit c)
      (catch Exception e (.rollback c) (throw e)))))
(defn- filters! [params]
  (when (seq (remove #{"comparison" "federation" "representation" "sanction_scope" "listing_filter"} (keys params))) (fail! 400))
  (when (and (get params "comparison") (not= target (get params "comparison"))) (fail! 400))
  (when (and (get params "federation") (not (#{"CMAS" "AIDA"} (get params "federation")))) (fail! 400))
  (when (and (get params "representation") (not (re-matches #"[A-Z]{3}" (get params "representation")))) (fail! 400))
  (when-not (#{"default" "broad"} (get params "sanction_scope" "default")) (fail! 400))
  (when-not (#{"all" "international" "national-local-only"} (get params "listing_filter" "all")) (fail! 400))
  (merge {"comparison" target "sanction_scope" "default" "listing_filter" "all"} params))
(defn- empty-response [filters]
  {:target target :scope scope :status :withheld :policy scores/policy :filters filters :rows []
   :projection-read-at (str (java.time.Instant/now)) :evidence-coverage-cutoff nil
   :coverage {:provided 0 :eligible-comparison-peers 0 :withheld 0 :federations ["CMAS" "AIDA"]
              :source-positions nil :source-versions nil :distinct-sporting-attempts nil}
   :scope-gaps ["Current finality, final post-penalty values, source authority, same-attempt and exact selected cohort evidence are unavailable."]})
(defn- keys! [m allowed]
  (when-not (and (map? m) (every? allowed (keys m))) (fail! 400)) m)
(defn- safe-url? [url]
  (try (let [u (java.net.URI. url)]
         (and (= "https" (.getScheme u)) (.getHost u) (nil? (.getUserInfo u))
              (nil? (.getFragment u)) (<= (count url) 500))) (catch Exception _ false)))
(defn- citation! [citation]
  (keys! citation #{:url :source-sha256 :artifact-sha256 :page :line :table :row})
  (when-not (and (safe-url? (:url citation))
                 (every? #(or (nil? %) (and (int? %) (pos? %))) ((juxt :page :line :table :row) citation))
                 (every? #(or (nil? %) (re-matches #"[0-9a-f]{64}" %)) ((juxt :source-sha256 :artifact-sha256) citation))) (fail! 400))
  citation)
(defn- reference! [reference]
  (when-not (and (= #{:result-id :observation-id :ordinal :source-sha256 :artifact-sha256} (set (keys reference)))
                 (nat-int? (:ordinal reference))
                 (every? #(and (string? %) (re-matches #"[0-9a-f]{64}" %))
                         ((juxt :result-id :observation-id :source-sha256 :artifact-sha256) reference))) (fail! 400))
  reference)
(def fact-keys #{:source-view :source-authority :finality :sanction :review :outcome :same-attempt :source-conflict
                 :final :scoring-policy :source-gender :comparable-category :represented-country
                 :listing :international-sanction :official-event-placing :source-selection})
(defn- fact! [fact reference]
  (when-not (= #{:value :binding :policy :citation} (set (keys fact))) (fail! 400))
  (when-not (and (= reference (:binding fact)) (= scores/policy (:policy fact))
                 (= (:source-sha256 reference) (get-in fact [:citation :source-sha256]))
                 (= (:artifact-sha256 reference) (get-in fact [:citation :artifact-sha256]))) (fail! 400))
  (citation! (:citation fact)) fact)
(defn- final! [value]
  (when-not (= #{:value :unit :basis :decimal-places :conversion} (set (keys value))) (fail! 400))
  (when-not (and (or (nil? (:value value)) (instance? java.math.BigDecimal (:value value)) (integer? (:value value)))
                 (= "m" (:unit value)) (#{:verified-post-penalty :verified-source-achieved :unknown} (:basis value))
                 (nat-int? (:decimal-places value)) (<= (:decimal-places value) 6)
                 (#{:verified :unknown} (:conversion value))) (fail! 400)) value)
(defn- facts! [facts reference]
  (keys! facts fact-keys)
  (when-not (every? #(contains? facts %) (disj fact-keys :source-selection)) (fail! 400))
  (doseq [[_ fact] facts] (fact! fact reference))
  (doseq [[k allowed] {:source-authority #{:official-results :unknown} :finality #{:verified-final :unknown}
                       :sanction #{:eligible :unknown} :review #{:verified :unknown}
                       :outcome #{:finally-valid :finally-valid-penalized :disqualified :unknown}
                       :same-attempt #{:distinct :unknown} :source-conflict #{:resolved :unresolved :selected-provisional}
                       :source-gender #{nil "Women" "Men" :unknown} :scoring-policy #{scores/policy}}]
    (when-not (contains? allowed (get-in facts [k :value])) (fail! 400)))
  (let [view (get-in facts [:source-view :value])]
    (when-not (= #{:federation :event-id :view-id :kind :environment} (set (keys view))) (fail! 400))
    (when-not (and (#{"CMAS" "AIDA"} (:federation view))
                   (#{:attempts :results :totals :unknown} (:kind view)) (#{:pool :unknown} (:environment view))
                   (every? #(and (string? %) (re-matches #"[a-z0-9-]{1,100}" %)) ((juxt :event-id :view-id) view))) (fail! 400)))
  (final! (get-in facts [:final :value]))
  (let [category (get-in facts [:comparable-category :value]) country (get-in facts [:represented-country :value])
        listing (get-in facts [:listing :value]) sanction (get-in facts [:international-sanction :value])
        placing (get-in facts [:official-event-placing :value])]
    (keys! category #{:group :para-class :age-class :age-equivalence})
    (when-not (and (#{:women :men :unknown} (:group category)) (#{:non-para :para :unknown} (:para-class category))
                   (#{:seniors :juniors :unknown} (:age-class category)) (#{:verified :unknown} (:age-equivalence category))
                   (or (nil? country) (and (string? country) (re-matches #"[A-Z]{3}" country)))
                   (or (nil? placing) (and (integer? placing) (pos? placing)))) (fail! 400))
    (keys! listing #{:publisher :kind}) (keys! sanction #{:authority :level :status})
    (when-not (and (#{:CMAS :AIDA :local-organizer :unknown} (:publisher listing))
                   (#{:archive :calendar :national-archive :local-results :unknown} (:kind listing))
                   (#{:CMAS :AIDA :national-federation :unknown} (:authority sanction))
                   (#{:international :national :unknown} (:level sanction))
                   (#{:verified :unsanctioned :unknown} (:status sanction))) (fail! 400)))
  (when-let [selection (get-in facts [:source-selection :value])]
    (keys! selection #{:selected-reference :conflicting-references :basis :authority-citation :selection-citation :equal-authority :tie-break-rule})
    (reference! (:selected-reference selection))
    (doseq [ref (:conflicting-references selection)] (reference! ref))
    (citation! (:authority-citation selection)) (citation! (:selection-citation selection))
    (when-not (and (= :source-authority (:basis selection))
                   (or (nil? (:equal-authority selection)) (boolean? (:equal-authority selection)))
                   (or (nil? (:tie-break-rule selection)) (= :exact-reference-lexical-v1 (:tie-break-rule selection)))) (fail! 400)))
  facts)
(defn- payload! [payload]
  (when-not (= #{:schema :rows :cutoff :cohort} (set (keys payload))) (fail! 400))
  (when-not (and (= "public-sporting/v1" (:schema payload)) (vector? (:rows payload)) (<= 2 (count (:rows payload)) 1000)) (fail! 400))
  (try (java.time.Instant/parse (:cutoff payload)) (catch Exception _ (fail! 400)))
  (doseq [row (:rows payload)]
    (keys! row #{:result-id :reference :source :facts :hypothetical})
    (reference! (:reference row))
    (when-not (= (:result-id row) (get-in row [:reference :result-id])) (fail! 400))
    (when-not (= #{:federation :event-id :view-id} (set (keys (:source row)))) (fail! 400))
    (when-not (and (#{"CMAS" "AIDA"} (get-in row [:source :federation]))
                   (every? #(and (string? %) (re-matches #"[a-z0-9-]{1,100}" %))
                           ((juxt :event-id :view-id) (:source row)))) (fail! 400))
    (facts! (:facts row) (:reference row))
    (when-not (= (:source row) (select-keys (get-in row [:facts :source-view :value]) [:federation :event-id :view-id])) (fail! 400))
    (when-let [h (:hypothetical row)] (fact! h (:reference row)) (final! (:value h))))
  (let [cohort (:cohort payload) binding (:binding cohort)]
    (when-not (= #{:binding :value :citation} (set (keys cohort))) (fail! 400))
    (when-not (= #{:cohort-id :policy :source-versions} (set (keys binding))) (fail! 400))
    (when-not (and (= scores/policy (:policy binding)) (re-matches #"[0-9a-f]{64}" (:cohort-id binding))
                   (= (:value cohort) (vec (sort (map :result-id (:rows payload)))))
                   (= (get binding :source-versions) (vec (sort (set (map #(get-in % [:reference :artifact-sha256]) (:rows payload))))))) (fail! 400))
    (citation! (:citation cohort)))
  payload)
(defn- execute! [c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i value] (map-indexed vector args)] (.setObject s (inc i) value)) (.executeUpdate s)))
(defn- local-state [c payload]
  {:policy (first (query c "SELECT freediving.public_sporting_local_snapshot() AS snapshot,(SELECT max(revision) FROM freediving.publication_policy_events) AS publication_policy,(SELECT max(revision) FROM freediving.public_sporting_policy_events) AS sporting_policy"))
   :sources (mapv (fn [row] (first (query c "SELECT p.body_edn,d.source_sha256,d.artifact_sha256,d.ordinal,encode(sha256(convert_to(row(d.job_id,d.ordinal,d.candidate_id,d.artifact_sha256)::text,'UTF8')),'hex') AS observation_id FROM freediving.public_results p JOIN freediving.public_projection_cache c USING(result_id) JOIN freediving.publication_decisions d ON d.id=c.validation_id WHERE p.result_id=?" (:result-id row)))) (:rows payload))})
(defn publish-derived!
  "Trusted database-owner preparation boundary for safe cited facts. No CLI or HTTP writer.
   This checks exact local public/source bindings, not the truth of owner sporting decisions.
   Unbridged preparation is withheld. Live signed owner receipts and fresh per-read authority are required."
  ([url payload] (publish-derived! url payload nil))
  ([url payload receipt]
   (payload! payload)
   (with-open [c (DriverManager/getConnection url)]
     (.setAutoCommit c false)
     (.setTransactionIsolation c Connection/TRANSACTION_READ_COMMITTED)
     (try
       (when-not (:allowed (first (query c "SELECT pg_has_role(current_user,relowner,'MEMBER') AS allowed FROM pg_class WHERE oid='freediving.public_sporting_authority_events'::regclass"))) (fail! 403))
       (query c "SELECT pg_advisory_xact_lock(781246916)")
       (let [state-before (local-state c payload)]
         (when receipt
           (let [latest (first (query c "SELECT * FROM freediving.public_sporting_bridge_receipts ORDER BY revision DESC LIMIT 1"))]
             (when-not (and (= (:revision receipt) (:revision latest)) (= (:head_sha256 receipt) (:head_sha256 latest))
                            (= "approve" (:action latest))
                            (= (:publication_sha256 latest) (authority/sha (authority/canonical-json payload)))) (fail! 409))))
         (let [cohort-urls (atom #{})]
           (doseq [row (:rows payload)]
             (let [source (first (query c "SELECT p.body_edn,d.source_sha256,d.artifact_sha256,d.ordinal,encode(sha256(convert_to(row(d.job_id,d.ordinal,d.candidate_id,d.artifact_sha256)::text,'UTF8')),'hex') AS observation_id FROM freediving.public_results p JOIN freediving.public_projection_cache c USING(result_id) JOIN freediving.publication_decisions d ON d.id=c.validation_id WHERE p.result_id=?" (:result-id row)))
                   reference {:result-id (:result-id row) :source-sha256 (:source_sha256 source) :artifact-sha256 (:artifact_sha256 source)
                              :ordinal (:ordinal source) :observation-id (:observation_id source)}
                   public (some-> (:body_edn source) edn/read-string)
                   urls (set (mapcat (juxt :final-url :discovery-url :mirror-of) (:citations public)))]
               (let [position (select-keys (:source-position public) [:page :line :table :row])]
                 (when-not (seq position) (fail! 400))
                 (doseq [k [:review :final :official-event-placing :source-gender :represented-country :outcome]]
                   (when-not (= position (select-keys (get-in row [:facts k :citation]) [:page :line :table :row])) (fail! 400))))
               (swap! cohort-urls into urls)
               (when-not (= reference (:reference row)) (fail! 409))
               (doseq [fact (cond-> (vec (vals (:facts row))) (:hypothetical row) (conj (:hypothetical row)))]
                 (when-not (contains? urls (get-in fact [:citation :url])) (fail! 400)))))
           (when-not (contains? @cohort-urls (get-in payload [:cohort :citation :url])) (fail! 400))
           (doseq [row (:rows payload) k [:authority-citation :selection-citation]
                   :let [citation (get-in row [:facts :source-selection :value k])] :when citation]
             (when-not (contains? @cohort-urls (:url citation)) (fail! 400))))
         (let [prior (when receipt (first (query c "SELECT revision,cohort_id,body_edn FROM freediving.public_sporting_authority_events WHERE receipt_revision=?" (:revision receipt))))]
           (when (and prior (not= [(get-in payload [:cohort :binding :cohort-id]) (authority/sha (authority/canonical-json payload))]
                                  [(:cohort_id prior) (authority/sha (authority/canonical-json (edn/read-string (:body_edn prior))))])) (fail! 409))
           (if prior
             (do (when-not (= state-before (local-state c payload)) (fail! 409))
                 (.commit c) (select-keys prior [:revision]))
             (let [event (first (query c "INSERT INTO freediving.public_sporting_authority_events(cohort_id,action,policy_version,expected_members,body_edn,receipt_revision) VALUES(?,'publish',?,?,?,?) RETURNING revision"
                                       (get-in payload [:cohort :binding :cohort-id]) (name scores/policy)
                                       (count (:rows payload)) (pr-str payload) (:revision receipt)))]
               (doseq [row (:rows payload)]
                 (execute! c "INSERT INTO freediving.public_sporting_members(event_revision,result_id) VALUES(?,?)" (:revision event) (:result-id row)))
               (when-not (= state-before (local-state c payload)) (fail! 409))
               (.commit c) event))))
       (catch Exception e (.rollback c) (throw e))))))
(defn deliver-current!
  "Database-owner delivery from the authenticated live private interface, never from an unverified payload.
   Ordered receipts are committed before preparation; interruptions leave public reads withheld."
  [url]
  (let [current (authority/fetch-current!) events (:events current) key-id (:key_id current)]
    (with-open [c (DriverManager/getConnection url)]
      (.setAutoCommit c false)
      (try
        (when-not (:allowed (first (query c "SELECT pg_has_role(current_user,relowner,'MEMBER') AS allowed FROM pg_class WHERE oid='freediving.public_sporting_bridge_receipts'::regclass"))) (fail! 403))
        (query c "SELECT pg_advisory_xact_lock(781246916)")
        (let [existing (query c "SELECT revision,previous_sha256,head_sha256,action,publication_sha256,binding_sha256,decision_sha256,policy,key_id FROM freediving.public_sporting_bridge_receipts ORDER BY revision")]
          (when (> (count existing) (count events)) (fail! 409))
          (doseq [[old event] (map vector existing events)]
            (when-not (= old (assoc event :key_id key-id)) (fail! 409)))
          (doseq [event (drop (count existing) events)]
            (execute! c "INSERT INTO freediving.public_sporting_bridge_receipts(revision,previous_sha256,head_sha256,action,publication_sha256,binding_sha256,decision_sha256,policy,key_id) VALUES(?,?,?,?,?,?,?,?,?)"
                      (:revision event) (:previous_sha256 event) (:head_sha256 event) (:action event)
                      (:publication_sha256 event) (:binding_sha256 event) (:decision_sha256 event) (:policy event) key-id)))
        (.commit c)
        (catch Exception e (.rollback c) (throw e))))
    (if (and (= "current" (:status current)) (:publication current))
      (let [payload (authority/decode-publication (:publication current))
            already (with-open [c (DriverManager/getConnection url)]
                      (first (query c "SELECT revision FROM freediving.public_sporting_comparison WHERE bridge_revision=?" (:revision current))))]
        (or already (publish-derived! url payload (peek events))))
      {:status :withheld :authority-revision (:revision current)})))

(defn- internal-reference [reference]
  {:job-id (:observation-id reference) :candidate-id (:result-id reference) :ordinal (:ordinal reference)
   :artifact-sha256 (:artifact-sha256 reference) :source-sha256 (:source-sha256 reference)})
(defn- classify [event]
  (let [payload (payload! (edn/read-string (:body_edn event)))
        bindings (json/read-str (:bindings_json event) :key-fn keyword)
        bound (into {} (map (juxt :result-id identity) bindings))
        rows (:rows payload)
        _ (when-not (and (= (set (map :result-id rows)) (set (keys bound)))
                         (= (:cohort_id event) (get-in payload [:cohort :binding :cohort-id]))) (fail! 503))
        observations
        (mapv (fn [row]
                (let [reference (:reference row) current (get bound (:result-id row))
                      _ (when-not (= reference (dissoc current :source-body)) (fail! 503))
                      public (edn/read-string (:source-body current)) parsed (:effective public)
                      facts (:facts row) value #(get-in facts [% :value])
                      parsed (assoc parsed :gender (value :source-gender))
                      _ (when-not (and (= (:federation parsed) (get-in row [:source :federation]))
                                       (= (:rank parsed) (value :official-event-placing))
                                       (= (:representation parsed) (value :represented-country))) (fail! 503))
                      selection (value :source-selection)
                      selection (when selection (-> selection
                                                    (update :selected-reference internal-reference)
                                                    (update :conflicting-references #(mapv internal-reference %))))]
                  {:reference (internal-reference reference)
                   :source (merge (:source row) {:environment (:environment (value :source-view)) :authority (value :source-authority)
                                                 :finality (value :finality) :sanction (value :sanction)})
                   :candidate {:parse-status :parsed :source-family (:kind (value :source-view)) :parsed parsed}
                   :evidence (cond-> {:review (value :review) :outcome (value :outcome)
                                      :attempt-relationship (value :same-attempt) :source-conflict (value :source-conflict)
                                      :final (value :final)}
                               selection (assoc :source-selection selection)
                               (:hypothetical row) (assoc :hypothetical {:final (get-in row [:hypothetical :value])
                                                                         :citation (get-in row [:hypothetical :citation])}))
                   :safe-source public :safe-facts facts :safe-reference reference})) rows)
        request (merge scope {:federations #{"CMAS" "AIDA"}
                              :views (set (map #(assoc (select-keys (:source %) [:federation :event-id :view-id])
                                                       :source-sha256 (get-in % [:reference :source-sha256])) observations))
                              :representations (set (map #(get-in % [:candidate :parsed :representation]) observations))})
        _ (when-not (= #{"CMAS" "AIDA"} (set (map #(get-in % [:source :federation]) observations))) (fail! 503))
        classified (attempts/compare-attempts request observations)]
    {:payload payload :comparison classified :event event}))
(defn- sporting-row [row]
  (let [facts (:safe-facts row) value #(get-in facts [% :value])
        category (value :comparable-category)]
    {:id (get-in row [:reference :candidate-id]) :discipline :dynamic
     :review (if (and (= :ranked (:comparison-status row)) (= :non-para (:para-class category))) :verified :unknown)
     :attempt-relationship (get-in row [:evidence :attempt-relationship])
     :status (get-in row [:evidence :outcome]) :citation (get-in facts [:final :citation])
     :official-final (assoc (get-in row [:evidence :final]) :unit :m :basis :verified-publisher-post-penalty)
     :source-authority (get-in row [:source :authority]) :source-finality (get-in row [:source :finality])
     :publication :approved :comparable-category {:group (:group category) :para-class (when (= :para (:para-class category)) :para)
                                                  :citation (get-in facts [:comparable-category :citation])}
     :represented-country (value :represented-country)
     :selected-source-conflict? (= :selected-provisional (get-in row [:evidence :source-conflict]))
     :event {:listing-evidence [(assoc (value :listing) :citation (get-in facts [:listing :citation]))]
             :sanction-evidence [(assoc (value :international-sanction) :citation (get-in facts [:international-sanction :citation]))]}}))
(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
        (set? value) (vec (sort (map canonical value)))
        (sequential? value) (mapv canonical value) :else value))
(defn- query-string [filters]
  (str/join "&" (map (fn [[k v]] (str k "=" (java.net.URLEncoder/encode v "UTF-8"))) (sort filters))))
(defn- projection [classified filters]
  (let [{:keys [payload comparison event]} classified
        positions (mapv (fn [row] [(get-in row [:reference :source-sha256]) (select-keys (get-in row [:safe-source :source-position]) [:page :line :table :row])]) (:rows comparison))
        _ (when-not (and (every? #(and (map? (second %)) (seq (second %))) positions)
                         (= (count positions) (count (set positions)))) (fail! 503))
        underlying (peers/compare-peers {} (mapv sporting-row (:rows comparison)))
        _ (when-not (= #{"CMAS" "AIDA"}
                       (set (map #(get-in % [:source :federation])
                                 (filter #(contains? (set (:peer-list underlying)) (get-in % [:reference :candidate-id])) (:rows comparison))))) (fail! 503))
        source-rows (filterv (fn [row]
                               (and (or (nil? (get filters "federation")) (= (get filters "federation") (get-in row [:source :federation])))
                                    (or (nil? (get filters "representation")) (= (get filters "representation") (get-in row [:candidate :parsed :representation]))))) (:rows comparison))
        sporting (mapv sporting-row source-rows)
        scored (:rows (scores/compare-verified sporting))
        by-score (into {} (map (juxt :id identity) scored))
        authority-token (sha (pr-str (canonical [(:cohort_id event) (:revision event) payload filters])))
        peer-request (fn [geography country]
                       (cond-> {:geography geography :category :women
                                :sanction-scope (keyword (get filters "sanction_scope"))
                                :listing-filter (keyword (get filters "listing_filter"))}
                         (not= geography :international) (assoc :anchor-represented-country country)))
        peer-for (memoize (fn [geography country]
                            (if (and (not= geography :international) (nil? country)) nil
                                (peers/compare-peers (peer-request geography country) sporting))))
        international (peer-for :international nil)
        token-for (fn [descriptor] (sha (pr-str (canonical [(:cohort_id event) (:revision event) payload filters descriptor]))))
        links (atom {})
        output
        (mapv (fn [row]
                (let [id (get-in row [:reference :candidate-id]) facts (:safe-facts row) value #(get-in facts [% :value])
                      country (value :represented-country) category (value :comparable-category)
                      peer (first (filter #(= id (:id %)) (:rows international))) score (get by-score id)
                      ranks (mapv (fn [geography]
                                    (let [peers (peer-for geography (when-not (= geography :international) country))
                                          descriptor (:descriptor peers)
                                          unknown-geography? (and (not= geography :international) (nil? (:anchor-sports-continent descriptor)))
                                          ranked (first (filter #(= id (:id %)) (:rows peers)))
                                          token (when (:rank ranked) (token-for descriptor))
                                          query (query-string filters)]
                                      (when token (swap! links assoc token {:descriptor descriptor :ids (:peer-list peers)}))
                                      (cond-> {:scope geography :status (if unknown-geography? :withheld (or (:peer-status ranked) :withheld))
                                               :rank (:rank ranked) :eligible-peer-denominator (when-not unknown-geography? (:denominator descriptor))
                                               :provisional (boolean (:provisional descriptor)) :descriptor descriptor}
                                        unknown-geography? (assoc :reason "Represented country has no verified sports-continent mapping; geographic denominator is unknown.")
                                        token (assoc :url (str "/comparison/peers/" token "?" query)
                                                     :api-url (str "/api/comparison/peers/" token "?" query)))))
                                  [:national :continental :international])
                      hypothesis (:hypothetical row)
                      hypothetical (when hypothesis
                                     (let [hypo (-> (sporting-row row) (assoc :review :verified :status :finally-valid)
                                                    (assoc :official-final (assoc (get-in row [:evidence :hypothetical :final])
                                                                                  :unit :m :basis :verified-publisher-post-penalty)))
                                           hscore (get-in (scores/compare-verified [hypo]) [:rows 0 :comparison-score])
                                           hpeer (first (:rows (peers/compare-peers (peer-request :international nil) [hypo])))
                                           eligible? (and hscore (= :ranked (:peer-status hpeer)))
                                           peer-scores (map #(get-in by-score [% :comparison-score]) (:peer-list international))]
                                       (cond-> (select-keys hypothesis [:value :unit :basis :status :citation])
                                         eligible? (assoc :score {:value hscore :policy scores/policy}
                                                          :rank (inc (count (filter #(> % hscore) peer-scores)))
                                                          :eligible-peer-denominator (count peer-scores)
                                                          :meaning "Hypothetical achieved-value score excluding disqualification; not a final valid result."))))]
                  (cond-> {:id id :result-id id :source-name (get-in row [:safe-source :effective :source-name])
                           :federation (get-in row [:source :federation])
                           :official-event-placing (value :official-event-placing)
                           :source-category (get-in row [:safe-source :effective :category]) :source-gender (value :source-gender)
                           :comparable-category (:group category) :para-class (:para-class category)
                           :age-class (if (= :verified (:age-equivalence category)) (:age-class category) :unknown) :age-equivalence (:age-equivalence category)
                           :represented-country country :status (:peer-status peer)
                           :final (value :final) :source-authority (value :source-authority) :finality (value :finality)
                           :event-listing (get-in peer [:event-classification :listing]) :event-sanction (get-in peer [:event-classification :sanction])
                           :provisional (boolean (some :provisional ranks))
                           :citations (into {} (map (fn [[k v]] [k (:citation v)]) facts))
                           :provenance (:safe-reference row)
                           :detail-url (str "/comparison/attempts/" id "?" (query-string filters) "&authority=" authority-token)
                           :detail-api-url (str "/api/comparison/attempts/" id "?" (query-string filters) "&authority=" authority-token)
                           :ranks ranks :reasons (vec (distinct (concat (:reasons row) (:reasons score))))}
                    (:comparison-score score) (assoc :score {:value (:comparison-score score) :policy scores/policy})
                    hypothetical (assoc :hypothetical hypothetical)))) source-rows)
        by-id (into {} (map (juxt :id identity) output))
        denominator (get-in international [:coverage :denominator])
        response {:target target :scope scope :status (if (pos? denominator) :ranked :withheld)
                  :policy scores/policy :filters filters :rows output :scope-gaps []
                  :evidence-coverage-cutoff (:cutoff payload) :projection-read-at (str (java.time.Instant/now))
                  :authority {:cohort-id (:cohort_id event) :revision (:revision event) :ledger :public-sporting-authority-v1}
                  :coverage {:provided (count source-rows) :eligible-comparison-peers denominator
                             :withheld (- (count source-rows) denominator) :federations ["CMAS" "AIDA"]
                             :source-positions (count (set positions))
                             :source-versions (count (set (map #(get-in % [:reference :artifact-sha256]) (:rows payload))))
                             :distinct-sporting-attempts (when (every? #(= :distinct (get-in % [:evidence :attempt-relationship])) (:rows comparison))
                                                           (count (set (map :reference (:rows comparison)))))}}]
    {:response response :by-id by-id :links @links :authority-token authority-token}))
(defn read-response [url path params]
  (let [filters (filters! (dissoc params "authority"))]
    (when (and (get params "authority") (not (re-matches #"[0-9a-f]{64}" (get params "authority")))) (fail! 400))
    (when (and (get params "authority") (not (str/starts-with? path "/api/comparison/attempts/"))) (fail! 400))
    (with-open [c (DriverManager/getConnection url)]
      (.setTransactionIsolation c Connection/TRANSACTION_REPEATABLE_READ)
      (.setReadOnly c true)
      (.setAutoCommit c false)
      (let [installed? (:installed (first (query c "SELECT to_regclass('freediving.public_sporting_comparison') IS NOT NULL AS installed")))
            events (when installed? (query c "SELECT * FROM freediving.public_sporting_comparison ORDER BY revision DESC LIMIT 2"))
            ;; Multiple independently selected cohorts need an explicit authority choice.
            data (when (and (= 1 (count events)) (authority/current-for? (first events)))
                   (try (projection (classify (first events)) filters) (catch Exception _ nil)))
            response (or (:response data) (empty-response filters))
            [_ kind id] (re-matches #"/api/comparison/(attempts|peers)/([0-9a-f]{64})" path)
            response (case kind
                       "attempts" (do (when-not (= (get params "authority") (:authority-token data)) (fail! 404))
                                      (if-let [row (get-in data [:by-id id])] (assoc (dissoc response :rows) :attempt row) (fail! 404)))
                       "peers" (if-let [link (get-in data [:links id])]
                                 (assoc response :rows (mapv (:by-id data) (:ids link)) :descriptor (:descriptor link)) (fail! 404))
                       (if (= path "/api/comparison") response (fail! 404)))]
        (.commit c) response))))

(defn -main [& _]
  (let [url (System/getenv "FREEDIVING_SPORTING_DELIVERY_URL")]
    (when-not url (throw (ex-info "Dedicated owner delivery URL required" {})))
    (let [result (deliver-current! url)]
      (println (if (:revision result) "Current signed sporting authority delivered." "Sporting authority withheld.")))))
