(ns freediving.athlete-identity
  "Private, deterministic athlete retrieval and reversible relationship projection."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [freediving.candidates :as candidates]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy])
  (:import [java.sql DriverManager Connection]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def rule-version "athlete-identity/1")
(def ^:dynamic *replaying-events?* false)
(def default-ambiguous-names #{"amy smith" "john smith" "maria silva"})
(defn- fail! [message data] (throw (ex-info message data)))
(defn- name-key [name]
  (when-let [keys (candidates/comparison-keys name)]
    (:token-order keys)))
(defn- folded [name] (:folded (candidates/comparison-keys name)))
(defn- person-id [row]
  (when (and (= :person (:publisher-id-kind row))
             (not (str/blank? (:publisher-scope row)))
             (not (str/blank? (:publisher-athlete-id row))))
    [(:publisher-scope row) (:publisher-athlete-id row)]))
(defn- aida-source-person? [row]
  (let [ref (:source-observation-ref row)
        person (:publisher_person ref)
        id (:id person)
        href (:href person)]
    (and (= "aida-snapshot-observation/3" (:adapter_version ref))
         (= "AIDA" (:scope person) (:publisher-scope row))
         (= "person" (:id_kind person))
         (= :person (:publisher-id-kind row))
         (= id (:publisher-athlete-id row))
         (string? id)
         (boolean (re-matches #"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}" id))
         (string? href)
         (boolean (re-matches (re-pattern (str "(?:https://www\\.aidainternational\\.org)?/Athletes/Profile-" id)) href)))))
(defn- usable? [row] (= :parsed (:parse-status row)))
(defn- aliases [row]
  (filter #(and (string? (:name %)) (#{:publisher :accepted :generated} (:origin %)))
          (:aliases row)))
(defn- index-add [index route key id]
  (if key (update-in index [route key] (fnil conj #{}) id) index))
(defn build-index
  "Index every parsed observation. Publisher IDs are indexed only when explicitly verified as person IDs."
  [rows]
  (let [rows (vec rows) ids (map :observation-id rows)]
    (when-not (and (every? #(and (string? %) (not (str/blank? %))) ids)
                   (= (count ids) (count (set ids))))
      (fail! "Unique observation IDs required" {}))
    (let [bridge (reduce (fn [m row]
                           (if (usable? row)
                             (reduce (fn [m alias]
                                       (if (#{:publisher :accepted} (:origin alias))
                                         (update m (name-key (:name alias)) (fnil into #{})
                                                 #{(name-key (:source-name row))}) m))
                                     m (aliases row)) m)) {} rows)
          bridge (reduce-kv (fn [m alias keys]
                              (reduce (fn [m key] (update m key (fnil conj #{}) alias)) m keys))
                            bridge bridge)
          index (reduce (fn [index row]
                          (if-not (usable? row) index
                                  (let [id (:observation-id row)
                                        index (index-add index :publisher-id (person-id row) id)
                                        index (index-add index :native-name (name-key (:source-name row)) id)]
                                    (reduce (fn [idx alias]
                                              (index-add idx (case (:origin alias)
                                                               :generated :generated-transliteration
                                                               :accepted :accepted-alias
                                                               :publisher :publisher-alias)
                                                         (name-key (:name alias)) id))
                                            index (aliases row))))) {} rows)]
      {:version rule-version :rows (into {} (map (juxt :observation-id identity) rows))
       :indexes index :romanization-bridge bridge
       :unsupported (mapv :observation-id (remove usable? rows))})))

(defn retrieve
  "Return all indexed candidates and explicit exclusions. There is no implicit top-N cutoff."
  [index target]
  (let [target-keys (set (keep name-key (cons (:source-name target) (map :name (aliases target)))))
        bridged (set/difference (set (mapcat #(get (:romanization-bridge index) % #{}) target-keys)) target-keys)
        matches (concat (for [id (get-in index [:indexes :publisher-id (person-id target)] #{})]
                          [id :publisher-id])
                        (for [key target-keys
                              id (get-in index [:indexes :native-name key] #{})] [id :native-name])
                        (mapcat (fn [route]
                                  (for [key target-keys
                                        id (get-in index [:indexes route key] #{})] [id route]))
                                [:publisher-alias :accepted-alias :generated-transliteration])
                        (for [bridge-key bridged
                              id (get-in index [:indexes :native-name bridge-key] #{})]
                          [id :romanization]))
        by-id (reduce (fn [m [id route]] (update m id (fnil conj #{}) route)) {} matches)
        by-id (dissoc by-id (:observation-id target))
        unsupported (remove #{(:observation-id target)} (:unsupported index))
        possible? (fn [id]
                    (let [row (get (:rows index) id)
                          keys (set (keep name-key (cons (:source-name row) (map :name (aliases row)))))]
                      (or (empty? keys)
                          (seq (set/intersection keys target-keys))
                          (and (person-id target) (= (person-id target) (person-id row))))))
        omitted (filter possible? unsupported)
        excluded (remove possible? unsupported)]
    {:index-version (:version index) :target-id (:observation-id target)
     :candidates (mapv (fn [[id routes]]
                         (assoc (select-keys (get (:rows index) id)
                                             [:observation-id :source-name :publisher-scope :publisher-athlete-id
                                              :publisher-id-kind :context :citation :aliases])
                                :routes (vec (sort routes))))
                       (sort-by first by-id))
     :candidate-count (count by-id)
     :unsupported-count (count unsupported)
     :omitted (mapv (fn [id] {:observation-id id :reason :unsupported-possibly-matching}) omitted)
     :excluded (mapv (fn [id] {:observation-id id :reason :unsupported-different-known-name}) excluded)
     :retrieval-complete? (empty? omitted)}))

(defn jev-decision
  "Build one identity question from the current indexed corpus. Include every retrieved candidate."
  ([ledger target-id candidate-id]
   (jev-decision ledger target-id candidate-id {}))
  ([ledger target-id candidate-id {:keys [dependencies id]}]
   (let [rows (:rows ledger)
         target (rows target-id)
         retrieval (when target (retrieve (build-index (vals rows)) target))
         found (mapv :observation-id (:candidates retrieval))
         scope (vec (cons target-id found))]
     (when-not (and (string? target-id) (string? candidate-id)
                    (some #{candidate-id} found)
                    (empty? (:omitted retrieval))
                    (vector? (vec dependencies))
                    (every? string? dependencies)
                    (or (nil? id) (and (string? id)
                                       (re-matches #"[A-Za-z0-9._-]{1,100}" id)))
                    (every? #(and (= :parsed (:parse-status (rows %)))
                                  (map? (:citation (rows %)))
                                  (string? (or (get-in (rows %) [:citation :source-sha256])
                                               (get-in (rows %) [:citation :source_sha256])))
                                  (string? (:source-name (rows %)))) scope))
       (fail! "Identity question lacks complete current source evidence"
              {:target-id target-id :candidate-id candidate-id}))
     {:id (or id (str "identity-" (format "%064x" (java.math.BigInteger. 1
                                                                         (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                                                                  (.getBytes (pr-str [target-id candidate-id]) "UTF-8"))))))
      :family :identity :action :same-person
      :choices [:same-person :different-person :unknown]
      :subject {:pair [target-id candidate-id] :target-id target-id
                :observation-versions (into {} (map (fn [id] [id (:citation (rows id))]) scope))}
      :candidates scope
      :evidence (mapv (fn [id]
                        {:evidence-id (str "identity-" id)
                         :citation (:citation (rows id))
                         :fact (str "Source name: " (:source-name (rows id))
                                    "; publisher person ID: " (or (:publisher-athlete-id (rows id)) "unknown"))
                         :source-meaning "Retained result-row athlete identity evidence"}) scope)
      :dependencies (vec dependencies) :evidence-adequate? true})))

(defn- compatible? [a b]
  (and (not (and (person-id a) (person-id b)
                 (= (first (person-id a)) (first (person-id b)))
                 (not= (person-id a) (person-id b))))
       (not (and (get-in a [:context :sex]) (get-in b [:context :sex])
                 (not= (get-in a [:context :sex]) (get-in b [:context :sex]))))))
(defn decide
  "Propose a conservative exact-name link. No match never proves global distinctness."
  [index target {:keys [ambiguous-names] :or {ambiguous-names default-ambiguous-names}}]
  (let [retrieval (retrieve index target)
        candidates (:candidates retrieval)
        plausible (filter #(compatible? target %) candidates)
        exact-keys (set (keep name-key (cons (:source-name target)
                                             (map :name (filter (comp #{:publisher :accepted} :origin)
                                                                (aliases target))))))
        exact (filter #(some exact-keys
                             (keep name-key (cons (:source-name %)
                                                  (map :name (filter (comp #{:publisher :accepted} :origin)
                                                                     (aliases %)))))) plausible)
        id-matches (filter #(some #{:publisher-id} (:routes %)) plausible)
        winner (cond (= 1 (count id-matches)) (first id-matches)
                     (= 1 (count exact)) (first exact))
        reason (cond
                 (not (usable? target)) :damaged-reading
                 (nil? (name-key (:source-name target))) :missing-name
                 (seq (:omitted retrieval)) :unsupported-possible-candidate
                 (= 1 (count id-matches)) :verified-publisher-id
                 (contains? ambiguous-names (folded (:source-name target))) :configured-ambiguous-name
                 (< (count (name-key (:source-name target))) 2) :insufficient-name
                 (> (count plausible) 1) :competing-candidates
                 (and winner (not (compatible? target winner))) :contradiction
                 (nil? winner) :no-distinctive-exact-match
                 :else :distinctive-exact-name)]
    {:rule-version rule-version :status (if (#{:verified-publisher-id :distinctive-exact-name} reason) :approve :unresolved)
     :reason reason :target-id (:observation-id target)
     :candidate-id (when (#{:verified-publisher-id :distinctive-exact-name} reason) (:observation-id winner))
     :retrieval retrieval :support (when winner (select-keys winner [:citation :routes :aliases]))
     :confidence nil}))

(defn empty-ledger [rows]
  (let [index (build-index rows)]
    {:version rule-version :rows (:rows index) :events []}))

(declare pair project dependency-reversal-event append-event)

(defn- complete-model-answer? [answer receipt config]
  (let [probabilities (:probabilities answer)
        raw (:raw-probabilities answer)]
    (and (= :same-person (:outcome answer))
         (not (:error answer))
         (not (:stale? answer))
         (= (:model config) (:model-version answer) (:actual-model answer) (:model receipt))
         (= (:request-hash answer) (:request-hash receipt))
         (= (:result-hash answer) (:result-hash receipt))
         (every? #(and (string? %) (re-matches #"[0-9a-f]{64}" %))
                 [(:request-hash answer) (:result-hash answer)])
         (map? (:usage answer)) (= (:usage answer) (:usage receipt))
         (number? (:confidence answer))
         (map? probabilities) (= #{:same-person :different-person :unknown} (set (keys probabilities)))
         (= #{"same_person" "different_person" "unknown"} (set (keys raw)))
         (= "same_person" (get-in answer [:raw-answer "choice"]))
         (= (set (keys raw)) (set (keys (get-in answer [:raw-answer "probabilities"]))))
         (every? (fn [[k v]] (and (number? v)
                                  (= (double v)
                                     (double (get-in answer [:raw-answer "probabilities" k]))))) raw)
         (every? #(and (number? %) (Double/isFinite (double %)) (<= 0 % 1)) (vals probabilities))
         (every? (fn [[k v]] (and (number? v)
                                  (= (double v)
                                     (double (get probabilities (keyword (str/replace k "_" "-"))))))) raw)
         (< (Math/abs (- 1.0 (double (reduce + (vals probabilities))))) 0.00001))))

(defn model-event
  "Build a source-bound canonical identity event from a current private Jev approval.
   The caller supplies the current identity ledger and its expected revision."
  [ledger flow-ledger decision config approval-policy expected-revision]
  (let [pair* (get-in decision [:subject :pair])
        refs (when (and (vector? pair*) (= 2 (count pair*))) (pair pair*))
        rows (:rows ledger)
        target-id (get-in decision [:subject :target-id])
        selected-id (first (remove #{target-id} refs))
        canonical-decision (when (and target-id selected-id)
                             (try (jev-decision ledger target-id selected-id
                                                {:dependencies (:dependencies decision)
                                                 :id (:id decision)})
                                  (catch clojure.lang.ExceptionInfo _ nil)))
        retrieval (when (rows target-id) (retrieve (build-index (vals rows)) (rows target-id)))
        current-scope (when retrieval (vec (cons target-id (map :observation-id (:candidates retrieval)))))
        versions (get-in decision [:subject :observation-versions])
        view (get (flow/inspect flow-ledger [decision] config) (:id decision))
        source-event (last (filter #(and (= (:id decision) (:decision-id %))
                                         (= (:request-hash view) (:request-hash %))
                                         (= (:result-hash view) (:result-hash %))
                                         (= (:policy-version view) (:policy-version %)))
                                   (:events flow-ledger)))
        dependency-events (mapv (fn [id]
                                  (last (filter #(= id (:decision-id %)) (:events flow-ledger))))
                                (:dependencies decision))
        reassessed (policy/assess approval-policy (assoc decision :action :same-person)
                                  (:answer source-event))
        evidence (:evidence decision)
        citations (set (map :citation evidence))]
    (when-not (and (= :identity (:family decision)) (= :same-person (:action view))
                   (= :approved (:status view))
                   (#{:jev :cached-jev :retained} (:origin view))
                   (#{:jev :cached-jev :retained} (:origin source-event))
                   (= expected-revision (count (:events ledger)))
                   refs (every? rows refs)
                   (some #{target-id} refs)
                   (every? (set current-scope) refs)
                   (= current-scope (:candidates decision))
                   (= canonical-decision decision)
                   (empty? (:omitted retrieval))
                   (= (set current-scope) (set (keys versions)))
                   (every? #(= (get versions %) (:citation (rows %))) current-scope)
                   (every? #(contains? citations (get versions %)) current-scope)
                   (vector? evidence) (seq evidence)
                   (not (seq (:conflicts decision))) (not (seq (:contradictions decision)))
                   (:evidence-adequate? decision)
                   (= :approve (get-in source-event [:assessment :status]))
                   (= :thresholds-met (:reason source-event))
                   (= :approve (:status reassessed))
                   (= (:assessment source-event) reassessed)
                   (= (:version approval-policy) (:policy-version source-event))
                   (every? #(and (= :approved (:status %))
                                 (not= :human (:origin %))) dependency-events)
                   (= (:policy-version view) (get-in source-event [:assessment :policy-version]))
                   (= (:answer view) (:answer source-event))
                   (complete-model-answer? (:answer source-event) (:receipt source-event) config)
                   (= (:request-hash source-event) (get-in source-event [:receipt :request-hash]))
                   (= (:result-hash source-event) (get-in source-event [:receipt :result-hash]))
                   (= (:template-version source-event) (get-in source-event [:answer :template-version]))
                   (string? (:policy-version source-event))
                   (string? (:template-version source-event))
                   (not (:rule-version source-event)))
      (fail! "Identity model approval is stale or insufficiently bound" {:decision-id (:id decision)}))
    {:id (str "jev-identity:" (:id source-event))
     :action :accept :actor-kind :model :pair refs
     :base-revision expected-revision
     :model-decision-id (:id decision)
     :model-event-id (:id source-event)
     :model-proof {:decision decision :flow-event source-event
                   :dependency-events dependency-events
                   :config config :approval-policy approval-policy
                   :receipt (:receipt source-event)}
     :evidence {:observations (mapv #(select-keys (rows %)
                                                  [:observation-id :source-name :citation :aliases]) refs)
                :cited-facts evidence}
     :dependencies (:dependencies decision)
     :original-value (mapv #(get-in (project ledger) [:athletes % :group-id]) refs)
     :proposed-value :same-athlete}))
(defn- pair [ids]
  (when-not (and (vector? ids) (= 2 (count ids)) (every? string? ids)
                 (not= (first ids) (second ids)))
    (fail! "Distinct observation pair required" {:pair ids}))
  (vec (sort ids)))
(defn- model-current? [rows event]
  (let [decision (get-in event [:model-proof :decision])
        target-id (get-in decision [:subject :target-id])
        versions (get-in decision [:subject :observation-versions])
        target (rows target-id)
        retrieval (when target (retrieve (build-index (vals rows)) target))
        scope (when retrieval (vec (cons target-id (map :observation-id (:candidates retrieval)))))]
    (and (= scope (:candidates decision))
         (empty? (:omitted retrieval))
         (= (set scope) (set (keys versions)))
         (every? #(= (versions %) (:citation (rows %))) scope)
         (= decision
            (try (jev-decision {:rows rows} target-id
                               (first (remove #{target-id} (get-in decision [:subject :pair])))
                               {:id (:id decision) :dependencies (:dependencies decision)})
                 (catch clojure.lang.ExceptionInfo _ nil))))))

(defn- dependency-events [flow-ledger ids]
  (let [events (:events flow-ledger)]
    (into {}
          (map (fn [id]
                 [id (or (last (filter #(and (= id (:decision-id %))
                                             (= :human (:origin %))) events))
                         (last (filter #(= id (:decision-id %)) events)))]) ids))))
(defn- active-edges [events rows]
  (let [reversed (set (keep #(when (= :reverse (:action %)) (:event-id %)) events))]
    (filter #(and (= :accept (:action %)) (not (reversed (:id %)))
                  (or (not= :model (:actor-kind %)) (model-current? rows %))) events)))
(defn- automatic-invalidation [rows event]
  (let [[a b] (:pair event)
        target (rows a)
        other (rows b)
        retrieval (when target (retrieve (build-index (vals rows)) target))
        candidates (:candidates retrieval)
        old (get-in event [:evidence :alternatives])
        old-ids (set (map :observation-id old))
        current-ids (set (map :observation-id candidates))
        old-versions (into {} (map (juxt :observation-id :citation)
                                   (get-in event [:evidence :observations])))
        current-versions (into {} (map (fn [id] [id (:citation (rows id))]) [a b]))
        contradiction (some (fn [candidate]
                              (let [row (rows (:observation-id candidate))]
                                (and (some? (person-id row))
                                     (some (fn [linked]
                                             (and (person-id linked)
                                                  (= (first (person-id row)) (first (person-id linked)))
                                                  (not= (person-id row) (person-id linked))))
                                           [target other])))) candidates)
        still-approved (if (:source-binding event)
                         (and (aida-source-person? target) (aida-source-person? other)
                              (= (person-id target) (person-id other))
                              (= (name-key (:source-name target)) (name-key (:source-name other))))
                         (let [decision (decide (build-index (vals (dissoc rows a))) target {})]
                           (and (= :approve (:status decision)) (= b (:candidate-id decision)))))
        reason (cond contradiction :publisher-person-contradiction
                     (not= old-versions current-versions) :evidence-version-changed
                     (not= old-ids current-ids) :candidate-set-changed
                     (not still-approved) :automatic-rule-no-longer-approves)]
    (when reason
      {:id (str "automatic-invalidation:" (:id event))
       :action :reverse :actor-kind :automatic :event-id (:id event)
       :rule-version rule-version :reason reason
       :source-binding (:source-binding event)
       :prior-decision-revision (:approval-revision event)
       :prior-evidence (:evidence event)
       :current-evidence {:observation-versions current-versions
                          :candidates candidates :omitted (:omitted retrieval)
                          :candidate-count (:candidate-count retrieval)
                          :rule-version rule-version}
       :pair (:pair event)})))
(defn recheck-automatic-links
  "Append a cited invalidation for each affected automatic approval after an import."
  [ledger]
  (reduce (fn [current [index event]]
            (if (and (= :automatic (:actor-kind event)) (= :accept (:action event))
                     (some #{(:id event)} (map :id (active-edges (:events current) (:rows current)))))
              (if-let [invalidation (automatic-invalidation (:rows current)
                                                            (assoc event :approval-revision (inc index)))]
                (append-event current invalidation) current)
              current)) ledger (map-indexed vector (:events ledger))))
(defn- components [ids edges]
  (reduce (fn [groups {:keys [pair]}]
            (let [[a b] pair ga (get groups a) gb (get groups b) joined (set/union ga gb)]
              (reduce #(assoc %1 %2 joined) groups joined)))
          (into {} (map (fn [id] [id #{id}]) ids)) edges))
(defn- negative-pairs [events]
  (let [by-id (into {} (map (juxt :id identity) events))
        superseded (set (keep :supersedes events))]
    (->> events
         (keep (fn [event]
                 (when (and (= :human (:actor-kind event))
                            (#{:reject :reverse} (:action event))
                            (not (superseded (:id event))))
                   (let [pair (if (= :reject (:action event))
                                (:pair event) (:pair (by-id (:event-id event))))]
                     (when pair
                       {:pair pair :event-id (:id event) :actor-kind :human
                        :reason (:reason event)})))))
         (sort-by (juxt :pair :event-id)) vec)))
(defn project [ledger]
  (let [rows (:rows ledger) edges (vec (active-edges (:events ledger) rows))
        groups (components (keys rows) edges)
        group-id (fn [members] (str "athlete:" (first (sort members))))
        linked (set (mapcat :pair edges))
        index (build-index (vals rows))
        ids (into {} (map (fn [[id members]]
                            [id {:observation-id id :provisional-id (str "athlete:" id)
                                 :group-id (group-id members) :origin (if (linked id) :accepted-link :provisional)
                                 :decision-origin (vec (distinct (map :actor-kind (filter #(some #{id} (:pair %)) edges))))
                                 :unresolved-candidates (mapv :observation-id
                                                              (remove #(contains? members (:observation-id %))
                                                                      (:candidates (retrieve index (rows id)))))
                                 :source-name (:source-name (rows id)) :citation (:citation (rows id))}]) groups))]
    {:revision (count (:events ledger)) :athletes ids
     :pending-automatic-review
     (mapv #(select-keys % [:id :event-id :pair :reason :prior-decision-revision
                            :prior-evidence :current-evidence])
           (filter #(and (= :automatic (:actor-kind %)) (= :reverse (:action %)))
                   (:events ledger)))
     :groups (into {} (map (fn [members] [(group-id members) (vec (sort members))]) (distinct (vals groups))))
     :negative-pairs (negative-pairs (:events ledger))
     :accepted-group-count (count (filter #(> (count %) 1) (distinct (vals groups))))
     :provisional-record-count (count rows)
     :unresolved-count (count (filter (comp seq :unresolved-candidates val) ids))
     :scope :retained-observations :global-distinctness :unknown}))
(defn append-event [ledger event]
  (let [events (:events ledger)
        rows (:rows ledger)
        existing (some #(when (= (:id event) (:id %)) %) events)]
    (when-not (and (string? (:id event)) (#{:automatic :human :model} (:actor-kind event))
                   (#{:accept :reverse :reject} (:action event)))
      (fail! "Invalid identity event" {:event event}))
    (if existing
      (if (or (= existing event)
              (= (:request existing) event)
              (and (= :reverse (:action event)) (= (dissoc existing :pair) event)))
        ledger (fail! "Conflicting identity event ID" {:id (:id event)}))
      (let [pair (when (#{:accept :reject} (:action event)) (pair (:pair event)))
            prior (when (= :reverse (:action event))
                    (some #(when (= (:event-id event) (:id %)) %) events))
            bound-pair (or pair (:pair prior))
            superseded-event (when-let [id (:supersedes event)]
                               (some #(when (= id (:id %)) %) events))
            superseded (cond-> (set (keep :supersedes events))
                         (:supersedes event) (conj (:supersedes event)))
            blocked (set (concat (for [e events :when (and (= :reject (:action e))
                                                           (= :human (:actor-kind e))
                                                           (not (superseded (:id e))))] (:pair e))
                                 (for [e events :when (and (= :reverse (:action e))
                                                           (= :human (:actor-kind e))
                                                           (not (superseded (:id e))))]
                                   (:pair (some #(when (= (:event-id e) (:id %)) %) events)))))
            groups (:groups (project ledger))]
        (when (some #(str/starts-with? % "source-observation:") bound-pair)
          (let [binding (:source-binding event)
                refs (into {} (map (fn [id] [id (:citation (rows id))]) bound-pair))]
            (when-not (and (or (= :human (:actor-kind event))
                               (and (= :automatic (:actor-kind event))
                                    (= :reverse (:action event))
                                    (= (:source-binding prior) binding))
                               (and (= :automatic (:actor-kind event))
                                    (= :accept (:action event))
                                    (every? #(aida-source-person? (rows %)) bound-pair)
                                    (apply = (map #(person-id (rows %)) bound-pair))
                                    (apply = (map #(name-key (:source-name (rows %))) bound-pair))))
                           (every? #(str/starts-with? % "source-observation:") bound-pair)
                           (every? rows bound-pair)
                           (= refs (:refs binding))
                           (= 1 (count (set (map :snapshot_sha256 (vals refs)))))
                           (= (:snapshot-sha256 binding)
                              (:snapshot_sha256 (first (vals refs)))))
              (fail! "Source identity event lacks exact current refs" {:id (:id event)}))))
        (when (and (= :model (:actor-kind event)) (= :accept (:action event))
                   (not (and (:request event) (not (model-current? rows event)))))
          (let [{:keys [decision flow-event dependency-events config approval-policy receipt]} (:model-proof event)
                recreated (when (and (= :accept (:action event)) (map? decision)
                                     (map? flow-event) (map? config) (= receipt (:receipt flow-event)))
                            (model-event ledger {:version flow/ledger-version
                                                 :events (conj (vec dependency-events) flow-event)}
                                         decision config approval-policy (:base-revision event)))]
            (when-not (= recreated (select-keys event (keys recreated)))
              (fail! "Invalid or stale model identity event" {:id (:id event)}))))
        (when (:supersedes event)
          (when-not (and (= :human (:actor-kind event)) (= :accept (:action event))
                         (= :human (:actor-kind superseded-event))
                         (#{:reject :reverse} (:action superseded-event)))
            (fail! "Human acceptance must supersede a human rejection or split" {:event event})))
        (when (and (= :automatic (:actor-kind event))
                   (or (not= rule-version (:rule-version event))
                       (and pair (blocked pair))))
          (fail! "Automatic identity rule stale or human-blocked" {:event event}))
        (when (and (= :automatic (:actor-kind event)) (= :accept (:action event))
                   (:source-binding event))
          (let [id (person-id (rows (first pair)))
                group-members (set (for [[observation-id row] rows
                                         :when (= id (person-id row))] observation-id))]
            (when (or (some #(set/subset? (set %) group-members) blocked)
                      (= (get-in (project ledger) [:athletes (first pair) :group-id])
                         (get-in (project ledger) [:athletes (second pair) :group-id])))
              (fail! "Source publisher group has a human correction or redundant edge"
                     {:pair pair}))))
        (when (and (= :model (:actor-kind event))
                   (some #(and (= :accept (:action %))
                               (= (:model-decision-id event) (:model-decision-id %))
                               (not= (:id event) (:id %)))
                         (active-edges events rows)))
          (fail! "Changed model evidence requires reversal before relinking" {:id (:id event)}))
        (when (= :reverse (:action event))
          (when-not (and (#{:human :model :automatic} (:actor-kind event)) (= :accept (:action prior))
                         (not-any? #(= (:event-id event) (:event-id %)) events))
            (fail! "Only active accepted links can be reversed" {:event event})))
        (when (and (not *replaying-events?*)
                   (= :automatic (:actor-kind event)) (= :reverse (:action event))
                   (not= event (automatic-invalidation rows
                                                       (assoc prior :approval-revision
                                                              (inc (.indexOf events prior))))))
          (fail! "Automatic invalidation lacks current changed evidence" {:id (:id event)}))
        (when (and (= :model (:actor-kind event)) (= :reverse (:action event)))
          (let [proof (:model-dependency-proof event)
                recreated (when (and prior (not (:request event)))
                            (dependency-reversal-event
                             ledger {:version flow/ledger-version :events (:flow-events proof)}
                             (:decision proof) (:config proof) (:canonical-statuses proof)
                             (:base-revision event)))]
            (when-not (and (= :model (:actor-kind prior))
                           (or (:request event) (= recreated event))
                           (= (count events) (:base-revision event)))
              (fail! "Model reversal lacks a current failed approval" {:id (:id event)}))))
        (when pair
          (when-not (every? rows pair) (fail! "Unknown observation" {:pair pair}))
          (when (and (= :human (:actor-kind event)) (= :reject (:action event))
                     (= (get-in (project ledger) [:athletes (first pair) :group-id])
                        (get-in (project ledger) [:athletes (second pair) :group-id])))
            (fail! "Human rejection requires reversing the active identity link first"
                   {:pair pair}))
          (when (and (= :accept (:action event))
                     (not (and (= :model (:actor-kind event)) (:request event)
                               (not (model-current? rows event)))))
            (let [members (set/union (set (get groups (get-in (project ledger) [:athletes (first pair) :group-id])))
                                     (set (get groups (get-in (project ledger) [:athletes (second pair) :group-id]))))]
              (when (and superseded-event
                         (not (set/subset? (set (:pair superseded-event)) members)))
                (fail! "Superseded correction is outside proposed group" {:event event}))
              (when (some #(set/subset? (set %) members) blocked)
                (fail! "Human rejected pair in proposed group" {:pair pair}))
              (when (some (fn [a] (some #(not (compatible? (rows a) (rows %))) members)) members)
                (fail! "Conflicting athlete group" {:pair pair})))))
        (update ledger :events conj (cond-> (assoc event :pair (or pair (:pair prior)))
                                      (= :model (:actor-kind event))
                                      (assoc :request (or (:request event) event))))))))
(defn replay [rows events]
  (binding [*replaying-events?* true]
    (reduce append-event (empty-ledger rows) events)))

(defn dependency-reversal-event
  "Build an auditable model reversal when its approval or dependency is no longer current."
  ([ledger flow-ledger decision expected-revision]
   (dependency-reversal-event ledger flow-ledger decision nil {} expected-revision))
  ([ledger flow-ledger decision config canonical-statuses expected-revision]
   (let [prior (last (filter #(and (= :model (:actor-kind %)) (= :accept (:action %))
                                   (= (:id decision) (:model-decision-id %))
                                   (some #{(:id %)} (map :id (active-edges (:events ledger) (:rows ledger)))))
                             (:events ledger)))
         ids (get-in prior [:model-proof :decision :dependencies])
         config (or config (get-in prior [:model-proof :config]))
         sources (filterv #(some #{(:decision-id %)} (conj (vec ids) (:id decision)))
                          (:events flow-ledger))
         statuses (into {} (map (fn [[id e]] [id (:status e)])
                                (dependency-events {:events sources} ids)))
         view (get (flow/inspect {:version flow/ledger-version :events sources}
                                 [decision] config) (:id decision))
         prior-answer (get-in prior [:model-proof :flow-event :answer])
         own-stale? (or (not= :approved (:status view))
                        (not (#{:jev :cached-jev :retained} (:origin view)))
                        (not= prior-answer (:answer view))
                        (not= (get-in prior [:model-proof :flow-event :policy-version])
                              (:policy-version view)))
         failed? (or own-stale?
                     (some #(not= :approved %) (vals statuses))
                     (some #(not= :approved %) (vals canonical-statuses)))]
     (when-not (and (= expected-revision (count (:events ledger)))
                    prior (map? canonical-statuses)
                    (every? (set ids) (keys canonical-statuses))
                    (= (set ids) (set (keys statuses))) failed?)
       (fail! "No active model link with a failed approval" {:decision-id (:id decision)}))
     {:id (str "jev-identity-dependency-reverse:" (:id prior) ":"
               (hash [(:status view) (:request-hash view) (:result-hash view)
                      statuses canonical-statuses]))
      :action :reverse :actor-kind :model :event-id (:id prior)
      :base-revision expected-revision
      :reason (if own-stale? :current-approval-unavailable :dependency-unapproved)
      :model-dependency-proof {:decision-id (:id decision)
                               :decision decision :config config
                               :flow-events sources :statuses statuses
                               :canonical-statuses canonical-statuses}})))

(defn- query [^Connection connection sql & args]
  (with-open [statement (.prepareStatement connection sql)]
    (doseq [[i value] (map-indexed vector args)]
      (.setObject statement (inc i) value))
    (with-open [result (.executeQuery statement)]
      (let [metadata (.getMetaData result)]
        (loop [rows []]
          (if (.next result)
            (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount metadata)))]
                                         [(keyword (.getColumnLabel metadata i)) (.getObject result i)]))))
            rows))))))

(defn- observation-rows [^Connection connection]
  (let [limit 100001
        raw (query connection
                   "SELECT o.job_id,o.ordinal,o.payload_edn,e.source_sha256,e.artifact_sha256 FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE o.kind='result-row' ORDER BY o.job_id,o.ordinal LIMIT ?"
                   limit)]
    (when (= limit (count raw))
      (fail! "Athlete corpus exceeds bound; no partial identity projection" {:limit (dec limit)}))
    (mapv (fn [row]
            (let [payload (edn/read-string (:payload_edn row))
                  parsed (:parsed payload)]
              {:observation-id (str "local-observation:" (:job_id row) ":" (:ordinal row))
               :source-name (:source-name parsed) :parse-status (:parse-status payload)
               :publisher-scope (:publisher-person-scope parsed)
               :publisher-athlete-id (:publisher-person-id parsed)
               :publisher-id-kind (when (= :verified-person (:publisher-id-semantics parsed)) :person)
               :aliases (filterv #(and (#{:publisher :accepted} (:origin %)) (:citation %))
                                 (:name-aliases parsed))
               :context (select-keys parsed [:sex :event-id])
               :citation {:job-id (:job_id row) :ordinal (:ordinal row)
                          :source-sha256 (:source_sha256 row)
                          :artifact-sha256 (:artifact_sha256 row)
                          :coordinates (:coordinates payload)}})) raw)))

(defn- source-rows [^Connection connection]
  (mapv (comp edn/read-string :body_edn)
        (query connection "SELECT body_edn FROM freediving.source_identity_observations ORDER BY observation_id")))

(declare write-canonical-view! reconcile-import!)

(defn- read-ledger [^Connection connection]
  (let [rows (into (observation-rows connection) (source-rows connection))
        events (mapv (comp edn/read-string :body_edn)
                     (query connection "SELECT body_edn FROM freediving.athlete_identity_events ORDER BY revision"))]
    (replay rows events)))

(defn- sha256? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- valid-source-row? [snapshot row verified-ref]
  (let [ref (:citation row)
        publisher? (some #(contains? row %) [:publisher-scope :publisher-athlete-id :publisher-id-kind])]
    (and (map? row) (= :parsed (:parse-status row))
         (string? (:source-name row)) (not (str/blank? (:source-name row)))
         (map? ref) (= ref verified-ref (:source-observation-ref row))
         (= "source-derived" (:kind ref))
         (= snapshot (:snapshot_sha256 ref))
         (sha256? (:snapshot_record_id ref))
         (= (str "source-observation:" (:snapshot_record_id ref)) (:observation-id row))
         (sha256? (:source_sha256 ref)) (sha256? (:packet_sha256 ref))
         (sha256? (:observation_version ref))
         (string? (:source_name ref)) (seq (:source_name ref))
         (string? (:adapter_version ref)) (seq (:adapter_version ref))
         (map? (:citation ref)) (seq (:citation ref))
         (if publisher? (aida-source-person? row)
             (not (contains? ref :publisher_person))))))

(defn register-source-observations!
  "Register an immutable, caller-verified source snapshot in the private canonical ledger.
   Caller must independently verify every supplied ref against the retained source.
   An optional expected-existing-row-ids set guards a prior exact target read."
  [url {:keys [snapshot-sha256 rows verified-refs expected-existing-row-ids] :as request}]
  (when-not (and (sha256? snapshot-sha256) (vector? rows) (seq rows)
                 (<= (count rows) 100000) (map? verified-refs)
                 (or (not (contains? request :expected-existing-row-ids))
                     (and (set? expected-existing-row-ids)
                          (every? string? expected-existing-row-ids)))
                 (= (set (map :observation-id rows)) (set (keys verified-refs)))
                 (= (count rows) (count (set (map :observation-id rows))))
                 (every? #(valid-source-row? snapshot-sha256 %
                                             (get verified-refs (:observation-id %))) rows))
    (fail! "Source observations lack exact verified immutable refs" {}))
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    ;; The first read must see writes committed while this transaction waited
    ;; for the advisory lock. REPEATABLE_READ would pin an older snapshot.
    (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_READ_COMMITTED)
    (try
      (query connection "SELECT pg_advisory_xact_lock(781246919)")
      (let [active (:snapshot_sha256 (first (query connection
                                                   "SELECT snapshot_sha256 FROM freediving.source_identity_snapshot WHERE singleton=true")))
            existing (into {} (map (juxt :observation_id :body_edn)
                                   (query connection "SELECT observation_id,body_edn FROM freediving.source_identity_observations")))]
        (when (and (contains? request :expected-existing-row-ids)
                   (not= expected-existing-row-ids (set (keys existing))))
          (fail! "Source identity source row set changed" {}))
        (when (and active (not= active snapshot-sha256))
          (fail! "Source identity snapshot changed" {}))
        (when-not active
          (with-open [statement (.prepareStatement connection
                                                   "INSERT INTO freediving.source_identity_snapshot(singleton,snapshot_sha256) VALUES(true,?)")]
            (.setString statement 1 snapshot-sha256)
            (.executeUpdate statement)))
        (doseq [row rows]
          (let [id (:observation-id row)
                canonical (select-keys row [:observation-id :source-name :parse-status :citation
                                            :source-observation-ref :publisher-scope
                                            :publisher-athlete-id :publisher-id-kind])
                body (binding [*print-length* nil *print-level* nil] (pr-str canonical))]
            (if-let [prior (get existing id)]
              (when-not (= canonical (edn/read-string prior))
                (fail! "Conflicting source observation" {:observation-id id}))
              (with-open [statement (.prepareStatement connection
                                                       "INSERT INTO freediving.source_identity_observations(observation_id,snapshot_sha256,body_edn) VALUES(?,?,?)")]
                (.setString statement 1 id)
                (.setString statement 2 snapshot-sha256)
                (.setString statement 3 body)
                (.executeUpdate statement)))))
        (let [result (reconcile-import! connection)]
          (.commit connection) result))
      (catch Exception error (.rollback connection) (throw error)))))

(defn- evidence-digest [ledger]
  (let [ordered (mapv (fn [id] [id (get-in ledger [:rows id])]) (sort (keys (:rows ledger))))]
    (.formatHex (HexFormat/of)
                (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (pr-str ordered) "UTF-8")))))

(defn- write-canonical-view! [^Connection connection ledger]
  (let [projection (project ledger)]
    (with-open [statement (.prepareStatement connection
                                             (str "INSERT INTO freediving.canonical_identity_view"
                                                  "(singleton,identity_revision,evidence_sha256,body_edn)"
                                                  " VALUES(true,?,?,?) ON CONFLICT(singleton) DO UPDATE SET"
                                                  " identity_revision=EXCLUDED.identity_revision,"
                                                  " evidence_sha256=EXCLUDED.evidence_sha256,"
                                                  " body_edn=EXCLUDED.body_edn,rebuilt_at=clock_timestamp()"))]
      (.setInt statement 1 (:revision projection))
      (.setString statement 2 (evidence-digest ledger))
      (.setString statement 3 (binding [*print-length* nil *print-level* nil] (pr-str projection)))
      (.executeUpdate statement))
    projection))

(defn reconcile-import!
  "Recheck automatic edges and update the private view in the caller's transaction."
  [^Connection connection]
  (when (:present (first (query connection
                                "SELECT to_regclass('freediving.athlete_identity_events') IS NOT NULL AS present")))
    (query connection "SELECT pg_advisory_xact_lock(781246919)")
    (let [ledger (read-ledger connection)
          checked (recheck-automatic-links ledger)]
      (doseq [[index event] (map-indexed vector (drop (count (:events ledger)) (:events checked)))]
        (with-open [statement (.prepareStatement connection
                                                 "INSERT INTO freediving.athlete_identity_events(revision,id,action,actor_kind,body_edn) VALUES(?,?,?,?,?)")]
          (.setInt statement 1 (+ (count (:events ledger)) index 1))
          (.setString statement 2 (:id event))
          (.setString statement 3 (name (:action event)))
          (.setString statement 4 (name (:actor-kind event)))
          (.setString statement 5 (binding [*print-length* nil *print-level* nil] (pr-str event)))
          (.executeUpdate statement)))
      (write-canonical-view! connection checked))))

(defn rebuild-private-canonical-view!
  "Atomically recover the private identity view from immutable observations and events."
  [url]
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (query connection "SELECT pg_advisory_xact_lock(781246919)")
      (let [projection (reconcile-import! connection)]
        (.commit connection) projection)
      (catch Exception error (.rollback connection) (throw error)))))

(defn private-canonical-view
  "Read only a current, internally consistent private view; stale materializations fail closed."
  [url]
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setReadOnly connection true)
    (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (let [ledger (read-ledger connection)
            stored (first (query connection
                                 "SELECT identity_revision,evidence_sha256,body_edn FROM freediving.canonical_identity_view WHERE singleton=true"))
            current (project ledger)]
        (when-not (and stored
                       (= (:revision current) (:identity_revision stored))
                       (= (evidence-digest ledger) (:evidence_sha256 stored))
                       (= current (edn/read-string (:body_edn stored))))
          (fail! "Private canonical identity view requires rebuild" {}))
        (.commit connection) current)
      (catch Exception error (.rollback connection) (throw error)))))

(defn private-canonical-readback
  "Read the source snapshot, immutable rows, events and checked view in one transaction."
  [url]
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setReadOnly connection true)
    (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (let [ledger (read-ledger connection)
            stored (first (query connection
                                 "SELECT identity_revision,evidence_sha256,body_edn FROM freediving.canonical_identity_view WHERE singleton=true"))
            current (project ledger)
            snapshot (:snapshot_sha256 (first (query connection
                                                     "SELECT snapshot_sha256 FROM freediving.source_identity_snapshot WHERE singleton=true")))
            source (source-rows connection)
            database (:database (first (query connection "SELECT current_database() AS database")))]
        (when-not (and stored snapshot
                       (= (:revision current) (:identity_revision stored))
                       (= (evidence-digest ledger) (:evidence_sha256 stored))
                       (= current (edn/read-string (:body_edn stored))))
          (fail! "Private canonical identity view requires rebuild" {}))
        (.commit connection)
        {:database database :snapshot-sha256 snapshot :source-rows source
         :non-source-row-count (- (count (:rows ledger)) (count source))
         :events (:events ledger) :projection current})
      (catch Exception error (.rollback connection) (throw error)))))

(defn private-canonical-target-state
  "Read one target snapshot, all canonical rows, events and view in one transaction.
   A truly empty target may have no source snapshot or materialized view yet."
  [url expected-snapshot]
  (when-not (sha256? expected-snapshot)
    (fail! "Expected source snapshot SHA256 required" {}))
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setReadOnly connection true)
    (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (let [ledger (read-ledger connection)
            stored (first (query connection
                                 "SELECT identity_revision,evidence_sha256,body_edn FROM freediving.canonical_identity_view WHERE singleton=true"))
            snapshot (:snapshot_sha256 (first (query connection
                                                     "SELECT snapshot_sha256 FROM freediving.source_identity_snapshot WHERE singleton=true")))
            source (source-rows connection)
            observation-count (:total (first (query connection
                                                    "SELECT COUNT(*) AS total FROM freediving.observations")))
            empty? (and (nil? snapshot) (nil? stored)
                        (empty? (:rows ledger)) (empty? (:events ledger))
                        (empty? source) (zero? observation-count))
            current (when-not empty? (project ledger))]
        (when-not (or empty?
                      (and (= expected-snapshot snapshot) stored
                           (= (:revision current) (:identity_revision stored))
                           (= (evidence-digest ledger) (:evidence_sha256 stored))
                           (= current (edn/read-string (:body_edn stored)))))
          (fail! "Target canonical state or source snapshot changed" {}))
        (.commit connection)
        {:snapshot_sha256 expected-snapshot
         :revision (count (:events ledger))
         :events (mapv #(select-keys % [:id :request]) (:events ledger))
         :source_rows source
         :non_source_row_count (- (count (:rows ledger)) (count source))})
      (catch Exception error (.rollback connection) (throw error)))))

(defn private-projection
  "Read the current private grouping from retained observations and the append-only ledger."
  [url]
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setReadOnly connection true)
    (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (let [result (project (read-ledger connection))]
        (.commit connection) result)
      (catch Exception e (.rollback connection) (throw e)))))

(defn private-jev-decision
  "Construct a cited identity question from a consistent database snapshot."
  ([url target-id candidate-id]
   (private-jev-decision url target-id candidate-id {}))
  ([url target-id candidate-id opts]
   (with-open [connection (DriverManager/getConnection url)]
     (.setAutoCommit connection false)
     (.setReadOnly connection true)
     (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
     (try
       (let [result (jev-decision (read-ledger connection) target-id candidate-id opts)]
         (.commit connection) result)
       (catch Exception error (.rollback connection) (throw error))))))

(defn private-source-decision
  "Construct a cited identity question scoped to the registered source snapshot."
  ([url target-id candidate-id]
   (private-source-decision url target-id candidate-id {}))
  ([url target-id candidate-id opts]
   (with-open [connection (DriverManager/getConnection url)]
     (.setAutoCommit connection false)
     (.setReadOnly connection true)
     (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
     (try
       (let [rows (source-rows connection)
             result (jev-decision (empty-ledger rows) target-id candidate-id opts)]
         (.commit connection) result)
       (catch Exception error (.rollback connection) (throw error))))))

(defn private-history
  "Return the inspectable append-only identity history, including database actor and time."
  [url]
  (with-open [connection (DriverManager/getConnection url)]
    (mapv (fn [row]
            (assoc (edn/read-string (:body_edn row))
                   :revision (:revision row)
                   :db-role (:db_role row)
                   :recorded-at (str (:recorded_at row))))
          (query connection
                 "SELECT revision,body_edn,db_role,recorded_at FROM freediving.athlete_identity_events ORDER BY revision"))))

(defn- automatic-event! [ledger event]
  (let [[a b] (:pair event)
        rows (:rows ledger)
        target (rows a)
        other (rows b)
        index (build-index (vals (dissoc rows a b)))
        candidate-index (build-index (conj (vec (vals (dissoc rows a b))) other))
        decision (if (:source-binding event)
                   {:status (if (and (aida-source-person? target)
                                     (aida-source-person? other)
                                     (= (person-id target) (person-id other))
                                     (= (name-key (:source-name target))
                                        (name-key (:source-name other)))) :approve :unresolved)
                    :reason :verified-publisher-id :candidate-id b
                    :retrieval (retrieve (build-index (vals rows)) target)}
                   (decide candidate-index target {}))]
    (when-not (and (= :approve (:status decision))
                   (= b (:candidate-id decision))
                   (empty? (:omitted (:retrieval decision)))
                   (= (:version index) rule-version))
      (fail! "Automatic athlete link lacks unique complete deterministic evidence"
             {:pair (:pair event) :decision (select-keys decision [:status :reason :candidate-id])}))
    decision))

(defn record-event!
  "Atomically validate and append one identity event. Automatic links are recomputed from the full retained corpus."
  [url event]
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (query connection "SELECT pg_advisory_xact_lock(781246919)")
      (let [capability (first (query connection
                                     "SELECT has_table_privilege(current_user,'freediving.athlete_identity_events','INSERT') AS allowed"))]
        (when-not (:allowed capability)
          (fail! "Athlete identity write capability required" {})))
      (let [ledger (read-ledger connection)
            existing (some #(when (= (:id %) (:id event)) %) (:events ledger))]
        (when (and (nil? existing) (contains? event :base-revision)
                   (not= (:base-revision event) (count (:events ledger))))
          (fail! "Stale identity ledger revision" {:expected (:base-revision event)
                                                   :current (count (:events ledger))}))
        (when (and (nil? existing) (:owner-binding event))
          (let [[a b] (:pair event)
                expected (:owner-canonical-decision event)
                current (try (jev-decision (if (:source-binding event)
                                             (empty-ledger (source-rows connection)) ledger) a b
                                           {:id (:id expected)
                                            :dependencies (:dependencies expected)})
                             (catch clojure.lang.ExceptionInfo _ nil))]
            (when-not (= current expected)
              (fail! "Owner identity pair or candidate evidence changed" {:id (:id event)}))))
        (when (and existing (not= event (:request existing)))
          (fail! "Conflicting identity event ID" {:id (:id event)}))
        (when (and existing (= :model (:actor-kind event))
                   (not= (:db_role (first (query connection
                                                 "SELECT db_role FROM freediving.athlete_identity_events WHERE id=?"
                                                 (:id event))))
                         (:session_user (first (query connection "SELECT session_user AS session_user")))))
          (fail! "Model identity replay requires original database role" {:id (:id event)}))
        (when (and existing (= :model (:actor-kind event)) (= :accept (:action event))
                   (not (model-current? (:rows ledger) existing)))
          (fail! "Model identity replay has stale source or candidate evidence" {:id (:id event)}))
        (when (and (= :model (:actor-kind event)) (nil? existing))
          (let [recreated (if (= :accept (:action event))
                            (let [{:keys [decision flow-event dependency-events config approval-policy]}
                                  (:model-proof event)]
                              (model-event ledger {:version flow/ledger-version
                                                   :events (conj (vec dependency-events) flow-event)}
                                           decision config approval-policy (:base-revision event)))
                            (dependency-reversal-event
                             ledger {:version flow/ledger-version
                                     :events (get-in event [:model-dependency-proof :flow-events])}
                             (get-in event [:model-dependency-proof :decision])
                             (get-in event [:model-dependency-proof :config])
                             (get-in event [:model-dependency-proof :canonical-statuses])
                             (:base-revision event)))]
            (when-not (= recreated event)
              (fail! "Invalid model identity submission" {:id (:id event)}))))
        (let [decision (when (and (= :automatic (:actor-kind event)) (nil? existing)
                                  (= :accept (:action event)))
                         (automatic-event! ledger event))
              refs (or (:pair event)
                       (:pair (some #(when (= (:event-id event) (:id %)) %) (:events ledger))))
              evidence (mapv (fn [id] (select-keys ((:rows ledger) id)
                                                   [:observation-id :source-name :citation :publisher-scope
                                                    :publisher-athlete-id :publisher-id-kind :aliases])) refs)
              event* (if existing existing
                         (if (= :model (:actor-kind event))
                           (assoc event :request event)
                           (assoc event :request event
                                  :evidence {:observations evidence
                                             :candidate-count (get-in decision [:retrieval :candidate-count])
                                             :alternatives (get-in decision [:retrieval :candidates])
                                             :omitted (get-in decision [:retrieval :omitted])
                                             :support (:support decision)
                                             :rule-version (or (:rule-version decision) (:rule-version event))}
                                  :dependencies (mapv :id (active-edges (:events ledger) (:rows ledger)))
                                  :original-value (mapv #(get-in (project ledger) [:athletes % :group-id]) refs)
                                  :proposed-value (case (:action event)
                                                    :accept :same-athlete
                                                    :reject :different-athletes
                                                    :reverse :unlinked)
                                  :decision (select-keys decision [:status :reason :candidate-id :confidence]))))
              updated (append-event ledger event*)]
          (when-not existing
            (let [stored (last (:events updated))]
              (with-open [statement (.prepareStatement connection
                                                       "INSERT INTO freediving.athlete_identity_events(revision,id,action,actor_kind,body_edn) VALUES(?,?,?,?,?)")]
                (.setInt statement 1 (count (:events updated)))
                (.setString statement 2 (:id stored))
                (.setString statement 3 (name (:action stored)))
                (.setString statement 4 (name (:actor-kind stored)))
                (.setString statement 5 (binding [*print-length* nil *print-level* nil] (pr-str stored)))
                (.executeUpdate statement))))
          (let [result (if existing (project updated)
                           (write-canonical-view! connection updated))]
            (.commit connection) result)))
      (catch Exception e (.rollback connection) (throw e)))))

(defn record-source-event!
  "Append a human action or cited AIDA profile approval against registered exact refs."
  [url event]
  (when-not (and (or (= :human (:actor-kind event))
                     (and (= :automatic (:actor-kind event)) (= :accept (:action event))))
                 (map? (:source-binding event))
                 (sha256? (get-in event [:source-binding :snapshot-sha256]))
                 (map? (get-in event [:source-binding :refs]))
                 (= 2 (count (get-in event [:source-binding :refs])))
                 (every? #(and (string? %) (str/starts-with? % "source-observation:"))
                         (keys (get-in event [:source-binding :refs])))
                 (or (= :reverse (:action event))
                     (= (set (:pair event))
                        (set (keys (get-in event [:source-binding :refs])))))
                 (contains? event :base-revision))
    (fail! "Source identity action requires a current human binding" {}))
  (record-event! url event))

(defn record-model-event!
  "Append one current model-backed identity approval through the canonical DB ledger.
   Replays of the same approval return the existing projection."
  [url flow-ledger decision config approval-policy expected-revision]
  (let [ledger (with-open [connection (DriverManager/getConnection url)]
                 (.setAutoCommit connection false)
                 (.setReadOnly connection true)
                 (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
                 (try
                   (let [result (read-ledger connection)] (.commit connection) result)
                   (catch Exception error (.rollback connection) (throw error))))
        existing (last (filter #(and (= :model (:actor-kind %))
                                     (= (:id decision) (:model-decision-id %)))
                               (:events ledger)))
        view (get (flow/inspect flow-ledger [decision] config) (:id decision))]
    (if (and existing
             (model-current? (:rows ledger) existing)
             (= decision (get-in existing [:model-proof :decision]))
             (= config (get-in existing [:model-proof :config]))
             (= approval-policy (get-in existing [:model-proof :approval-policy]))
             (= :approved (:status view))
             (= (:request-hash view) (get-in existing [:model-proof :flow-event :request-hash]))
             (= (:result-hash view) (get-in existing [:model-proof :flow-event :result-hash]))
             (= (:policy-version view) (get-in existing [:model-proof :flow-event :policy-version])))
      (record-event! url (:request existing))
      (record-event! url (model-event ledger flow-ledger decision config approval-policy expected-revision)))))

(defn sync-model-correction!
  "Apply a later private owner rejection or reversal to a materialized model link.
   Call with the reviewer role after appending the flow human event."
  [url flow-ledger decision config]
  (let [view (get (flow/inspect flow-ledger [decision] config) (:id decision))
        human (last (filter #(and (= :human (:origin %))
                                  (= (:id decision) (:decision-id %)))
                            (:events flow-ledger)))]
    (when-not (and (= :human (:origin view))
                   (#{:rejected :reversed} (:status view))
                   (= (:status view) (:status human))
                   (string? (:id human)))
      (fail! "Current human identity correction required" {:decision-id (:id decision)}))
    (let [ledger (with-open [connection (DriverManager/getConnection url)]
                   (.setAutoCommit connection false)
                   (.setReadOnly connection true)
                   (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
                   (try
                     (let [result (read-ledger connection)] (.commit connection) result)
                     (catch Exception error (.rollback connection) (throw error))))
          model (last (filter #(and (= :model (:actor-kind %))
                                    (= :accept (:action %))
                                    (= (:id decision) (:model-decision-id %)))
                              (:events ledger)))
          reverse-id (str "flow-identity-reversal:" (:id human))
          existing (some #(when (= reverse-id (:id %)) %) (:events ledger))]
      (cond
        existing (record-event! url (:request existing))
        (nil? model) (project ledger)
        (some #(= (:id model) (:event-id %)) (:events ledger)) (project ledger)
        :else (record-event! url {:id reverse-id :action :reverse :actor-kind :human
                                  :event-id (:id model)
                                  :reason (or (:reason human) "Private identity correction")
                                  :flow-human-event-id (:id human)})))))

(defn invalidate-model-dependency!
  "Reverse a model identity link after its approval or dependency becomes invalid."
  ([url flow-ledger decision expected-revision]
   (invalidate-model-dependency! url flow-ledger decision nil {} expected-revision))
  ([url flow-ledger decision config canonical-statuses expected-revision]
   (let [ledger (with-open [connection (DriverManager/getConnection url)]
                  (.setAutoCommit connection false)
                  (.setReadOnly connection true)
                  (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
                  (try
                    (let [result (read-ledger connection)] (.commit connection) result)
                    (catch Exception error (.rollback connection) (throw error))))
         old (last (filter #(and (= :model (:actor-kind %)) (= :reverse (:action %))
                                 (= (:id decision)
                                    (get-in % [:model-dependency-proof :decision-id])))
                           (:events ledger)))
         active (last (filter #(and (= :model (:actor-kind %)) (= :accept (:action %))
                                    (= (:id decision) (:model-decision-id %)))
                              (active-edges (:events ledger) (:rows ledger))))]
     (cond
       active (record-event! url (dependency-reversal-event ledger flow-ledger decision
                                                            config canonical-statuses expected-revision))
       old (record-event! url (:request old))
       :else (fail! "No model identity event to invalidate" {:decision-id (:id decision)})))))
