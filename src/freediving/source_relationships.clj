(ns freediving.source-relationships
  "Conservative, pure relationships among immutable source observations."
  (:require [clojure.string :as str]))

(def ^:private kinds [:same-attempt :source-duplicate :parser-revision :unknown])

(defn- ref-key [{:keys [job-id ordinal]}]
  [(str job-id) ordinal])

(defn- pair-key [left right]
  (vec (sort-by ref-key [left right])))

(defn- citation? [observation]
  (and (string? (:source-sha256 observation))
       (not (str/blank? (:source-sha256 observation)))
       (seq (:position observation))
       (every? #(and (pos-int? (:page %)) (pos-int? (:line %))) (:position observation))
       (string? (:source-text observation))
       (not (str/blank? (:source-text observation)))))

(defn- context? [left right]
  (and (string? (:event-key left))
       (not (str/blank? (:event-key left)))
       (= (:event-key left) (:event-key right))
       (string? (:discipline left))
       (not (str/blank? (:discipline left)))
       (= (:discipline left) (:discipline right))
       (= (:day left) (:day right))))

(defn- evidence-valid? [left right basis]
  (let [fields (:match-fields basis)]
    (and (citation? left) (citation? right) (context? left right)
         (not= [(:source-sha256 left) (:position left)]
               [(:source-sha256 right) (:position right)])
         (= (:position left) (:left-position basis))
         (= (:position right) (:right-position basis))
         (= (:source-text left) (:left-text basis))
         (= (:source-text right) (:right-text basis))
         (vector? fields) (<= 3 (count fields))
         (= (count fields) (count (distinct fields)))
         (every? (fn [field]
                   (and (contains? (:parsed left) field)
                        (contains? (:parsed right) field)
                        (some? (get-in left [:parsed field]))
                        (= (get-in left [:parsed field]) (get-in right [:parsed field]))))
                 fields))))

(defn- automatic [left right]
  (cond
    (not (and (citation? left) (citation? right)
              (context? left right)
              (= (:source-text left) (:source-text right))))
    :unknown

    (not= (:parser-version left) (:parser-version right)) :parser-revision
    (= (:parsed left) (:parsed right)) :source-duplicate
    :else :unknown))

(defn- pair-edges [observations]
  (for [[_ group] (group-by (juxt :source-sha256 :position) (filter citation? observations))
        :when (> (count group) 1)
        [index left] (map-indexed vector group)
        right (drop (inc index) group)
        :let [[a b] (pair-key (:ref left) (:ref right))]]
    {:scope :observation :left a :right b :kind (automatic left right)
     :basis {:source-sha256 (:source-sha256 left)
             :position (:position left)
             :left-text (:source-text (if (= a (:ref left)) left right))
             :right-text (:source-text (if (= b (:ref right)) right left))}}))

(defn- explicit-edge [indexed evidence]
  (let [{:keys [left right basis]} evidence
        a (get indexed left) b (get indexed right)
        [first-ref second-ref] (pair-key left right)
        accepted? (and a b (not= left right) (= :same-attempt (:kind evidence))
                       (evidence-valid? a b basis))]
    {:scope :observation :left first-ref :right second-ref
     :kind (if accepted? :same-attempt :unknown)
     :basis (if (= left first-ref)
              basis
              (-> basis
                  (assoc :left-position (:right-position basis)
                         :right-position (:left-position basis)
                         :left-text (:right-text basis)
                         :right-text (:left-text basis))))
     :reason (when-not accepted? :insufficient-evidence)}))

(defn- source-route-edges [routes]
  (for [[sha group] (group-by :source-sha256 routes)
        :when (and (string? sha) (not (str/blank? sha)) (> (count group) 1))
        [index left] (map-indexed vector (sort-by :route-id group))
        right (drop (inc index) (sort-by :route-id group))]
    {:scope :source-route
     :left {:route-id (:route-id left)}
     :right {:route-id (:route-id right)}
     :kind :source-duplicate
     :basis {:source-sha256 sha :proof :identical-source-bytes}}))

(defn- source-candidate-edge [routes {:keys [left right reason]}]
  (let [indexed (into {} (map (juxt :route-id identity) routes))
        [a b] (sort [left right])]
    (when-not (and (string? left) (string? right) (not= left right)
                   (contains? indexed left) (contains? indexed right)
                   (keyword? reason))
      (throw (ex-info "Source candidate needs two known routes and a reason" {})))
    {:scope :source-route
     :left {:route-id a} :right {:route-id b}
     :kind :unknown :reason reason
     :basis {:left-source-sha256 (:source-sha256 (indexed a))
             :right-source-sha256 (:source-sha256 (indexed b))}}))

(defn- counts [edges]
  (merge (zipmap kinds (repeat 0)) (frequencies (map :kind edges))))

(defn classify
  "Classify proven source relationships. Unknown evidence becomes a review candidate.
   Observation refs are {:job-id ... :ordinal ...}; no athlete identity is inferred."
  [{:keys [observations routes source-candidates same-attempt-evidence candidate-evidence]}]
  (let [observations (vec (sort-by (comp ref-key :ref) observations))
        indexed (into {} (map (juxt :ref identity) observations))
        routes (vec (sort-by :route-id routes))]
    (when (or (some #(not (and (map? (:ref %))
                               (string? (get-in % [:ref :job-id]))
                               (nat-int? (get-in % [:ref :ordinal])))) observations)
              (not= (count indexed) (count observations)))
      (throw (ex-info "Observation refs must be unique job-id/ordinal pairs" {})))
    (when (or (some #(not (and (string? (:route-id %))
                               (not (str/blank? (:route-id %))))) routes)
              (not= (count routes) (count (set (map :route-id routes)))))
      (throw (ex-info "Source route IDs must be unique nonblank strings" {})))
    (let [source-edges (->> (source-route-edges routes)
                            (sort-by (juxt (comp :route-id :left) (comp :route-id :right)))
                            vec)
          source-candidates (->> source-candidates
                                 (map #(source-candidate-edge routes %))
                                 distinct
                                 (sort-by (juxt (comp :route-id :left)
                                                (comp :route-id :right)))
                                 vec)
          automatic-edges (pair-edges observations)
          supplied (map #(explicit-edge indexed %) (concat same-attempt-evidence candidate-evidence))
          edges (->> (concat automatic-edges supplied)
                     (group-by (juxt :left :right))
                     (map (fn [[_ alternatives]]
                            ;; Conflicting claims remain unresolved.
                            (if (= 1 (count (set (map :kind alternatives))))
                              (first (sort-by pr-str alternatives))
                              (assoc (first (sort-by pr-str alternatives))
                                     :kind :unknown :reason :conflicting-evidence))))
                     (sort-by (juxt (comp ref-key :left) (comp ref-key :right)))
                     vec)
          observation-counts (counts edges)
          source-counts (counts (concat source-edges source-candidates))]
      {:observations observations
       :routes routes
       :source-edges source-edges
       :source-candidates source-candidates
       :edges edges
       :candidates (vec (filter #(= :unknown (:kind %)) edges))
       :counts-by-scope {:observation observation-counts
                         :source-route source-counts}
       :counts (merge-with + observation-counts source-counts)})))

;; Attempt reconciliation is a separate versioned contract. `classify` above is
;; retained for source-row diagnostics and does not assert distinct dive counts.
(def attempt-rule-version "attempt-relationships/1")
(def ^:private attempt-scope-keys
  [:event :day :session :round :discipline :participant :attempt])

(defn- complete-scope? [scope]
  (every? #(let [v (get scope %)]
             (and (some? v) (not (and (string? v) (str/blank? v)))))
          attempt-scope-keys))

(defn- keyed-input [rows kind]
  (let [rows (vec rows) ids (map :id rows)]
    (when-not (and (every? #(and (string? %) (not (str/blank? %))) ids)
                   (= (count ids) (count (set ids))))
      (throw (ex-info "Unique nonblank evidence IDs required" {:kind kind})))
    (into {} (map (juxt :id identity) rows))))

(defn empty-attempt-ledger
  "Immutable v1 evidence snapshot. Positions cite source objects; observation versions
   cite positions and retain their original values. Events are appended separately."
  [{:keys [sources positions observation-versions]}]
  (let [sources (keyed-input sources :source)
        positions (keyed-input positions :position)
        versions (keyed-input observation-versions :observation-version)]
    (when-not (and (every? #(and (string? (:sha256 %))
                                 (not (str/blank? (:sha256 %)))) (vals sources))
                   (every? #(contains? sources (:source-id %)) (vals positions))
                   (every? #(and (contains? positions (:position-id %))
                                 (string? (:parser-version %))
                                 (map? (:values %))) (vals versions)))
      (throw (ex-info "Invalid attempt evidence references" {})))
    {:version attempt-rule-version :sources sources :positions positions
     :observation-versions versions :events []}))

(defn- ordered-pair [pair]
  (when-not (and (vector? pair) (= 2 (count pair))
                 (every? string? pair) (not= (first pair) (second pair)))
    (throw (ex-info "Distinct evidence pair required" {:pair pair})))
  (vec (sort pair)))

(defn- active-events [ledger]
  (let [events (:events ledger)
        undone (set (keep #(when (= :reverse (:action %)) (:event-id %)) events))]
    (filter #(and (= :accept (:action %))
                  (not (contains? undone (:id %)))
                  (not (contains? (:invalidated-events ledger) (:id %)))) events)))

(defn- event-subjects [ledger type pair]
  (if (= type :same-attempt)
    (mapv (fn [id]
            (let [version (get-in ledger [:observation-versions id])
                  position (get-in ledger [:positions (:position-id version)])]
              {:version version :position position
               :source (get-in ledger [:sources (:source-id position)])})) pair)
    (mapv #((:sources ledger) %) pair)))

(defn- scope-of [ledger version-id]
  (get-in ledger [:observation-versions version-id :scope]))

(defn- source-of [ledger version-id]
  (get-in ledger [:positions (get-in ledger [:observation-versions version-id :position-id]) :source-id]))

(defn- scope-field-bound? [row field binding]
  (let [{:keys [path value span text]} binding
        claimed (get (:scope row) field)]
    (and (= claimed value)
         (or (and (= [:raw field] path)
                  (map? (get-in row [:values :raw]))
                  (= value (get-in row [:values :raw field])))
             (and (= [:source-text] path)
                  (= text (:source-text row))
                  (string? text) (string? value)
                  (vector? span) (= 2 (count span))
                  (every? nat-int? span)
                  (let [[start end] span
                        prefix (str (name field) "=")]
                    (and (<= (count prefix) start end (count text))
                         (= value (subs text start end))
                         (= prefix (subs text (- start (count prefix)) start)))))))))

(defn- verified-scope? [ledger version-id]
  (let [row (get-in ledger [:observation-versions version-id])
        position (get-in ledger [:positions (:position-id row)])
        evidence (:scope-evidence row)]
    (and (= :individual-result (:role row))
         (complete-scope? (:scope row))
         (= (:position-id row) (:position-id evidence))
         (= (:source-id position) (:source-id evidence))
         (= (get-in ledger [:sources (:source-id position) :sha256])
            (:source-sha256 evidence))
         (= (:locator position) (:citation evidence))
         (some? (:citation evidence))
         (= (:scope row) (:fields evidence))
         (= (set attempt-scope-keys) (set (keys (:bindings evidence))))
         (every? #(scope-field-bound? row % (get-in evidence [:bindings %]))
                 attempt-scope-keys))))

(defn- automatic-attempt-links [ledger]
  (let [rows (filter #(verified-scope? ledger (:id %))
                     (vals (:observation-versions ledger)))
        reversed (set (keep #(when (= :reverse (:action %)) (:event-id %)) (:events ledger)))
        reversed-pairs (set (keep (fn [event]
                                    (when (and (= :reverse (:action event))
                                               (not (str/starts-with? (:event-id event) "auto-attempt:")))
                                      (:pair (some #(when (= (:event-id event) (:id %)) %)
                                                   (:events ledger))))) (:events ledger)))]
    (->> (for [[_ scoped] (group-by :scope rows)
               :let [representatives (->> scoped (group-by :position-id) vals
                                          (map #(first (sort-by :id %)))
                                          (sort-by :id) vec)]
               other (rest representatives)
               :let [pair (ordered-pair [(:id (first representatives)) (:id other)])
                     id (str "auto-attempt:" (pr-str pair))]
               :when (and (not (contains? reversed id))
                          (not (contains? reversed-pairs pair)))]
           {:id id :type :same-attempt :pair pair :rule-version attempt-rule-version
            :evidence {:kind :verified-scope :scope (:scope other)}})
         (sort-by :id) vec)))

(defn- publisher-citation? [ledger citation]
  (and (map? citation)
       (contains? (:sources ledger) (:source-id citation))
       (some? (:locator citation))
       (not (and (string? (:locator citation)) (str/blank? (:locator citation))))
       (string? (:text citation))
       (not (str/blank? (:text citation)))
       (some #(= citation %) (get-in ledger [:sources (:source-id citation)
                                             :publisher-citations]))))

(defn- automatic-source-equivalence-links [ledger]
  (->> (for [[sha sources] (group-by :sha256 (vals (:sources ledger)))
             :let [ids (sort (map :id sources))]
             other (rest ids)
             :let [pair [(first ids) other]]]
         {:id (str "auto-source-equivalent:" (pr-str pair))
          :type :source-equivalent :pair pair
          :evidence {:source-sha256 sha :proof :identical-source-bytes}
          :rule-version attempt-rule-version})
       (sort-by :id) vec))

(defn append-attempt-event
  "Append an accepted source or attempt relationship, or reverse a prior acceptance.
   Publisher evidence establishes revision direction; retrieval order never does."
  [ledger event]
  (let [{:keys [id action type pair evidence event-id]} event
        prior (some #(when (= event-id (:id %)) %) (:events ledger))
        pair (when (= :accept action) (ordered-pair pair))
        known? (if (= type :same-attempt) (:observation-versions ledger) (:sources ledger))]
    (when-not (= attempt-rule-version (:version ledger))
      (throw (ex-info "Attempt ledger version mismatch" {})))
    (when-not (and (string? id) (not (str/blank? id))
                   (not-any? #(= id (:id %)) (:events ledger))
                   (#{:accept :reverse} action))
      (throw (ex-info "Invalid or duplicate attempt event" {:id id})))
    (if (= action :reverse)
      (when-not (or (and prior (= :accept (:action prior))
                         (some #(= event-id (:id %)) (active-events ledger)))
                    (some #(= event-id (:id %)) (automatic-attempt-links ledger)))
        (throw (ex-info "Only active acceptance can be reversed" {:event-id event-id})))
      (do
        (when-not (and (#{:same-attempt :source-equivalent :source-dependent
                          :source-revision} type)
                       (every? known? pair))
          (throw (ex-info "Unknown relationship subjects" {:type type :pair pair})))
        (case type
          :same-attempt
          (when-not (and (= :verified-scope (:kind evidence))
                         (verified-scope? ledger (first pair))
                         (verified-scope? ledger (second pair))
                         (= (scope-of ledger (first pair)) (scope-of ledger (second pair))))
            (throw (ex-info "Same attempt needs complete, equal verified scope" {:pair pair})))
          :source-equivalent
          (when-not (= (get-in ledger [:sources (first pair) :sha256])
                       (get-in ledger [:sources (second pair) :sha256]))
            (throw (ex-info "Source equivalence needs identical bytes" {:pair pair})))
          :source-revision
          (let [{:keys [kind predecessor successor citation]} evidence]
            (when-not (and (#{:publisher-version :publisher-correction} kind)
                           (= (set pair) #{predecessor successor})
                           (publisher-citation? ledger citation)
                           (contains? (set pair) (:source-id citation))
                           (not-any? #(and (= :source-revision (:type %))
                                           (= pair (:pair %))
                                           (not= predecessor (get-in % [:evidence :predecessor])))
                                     (active-events ledger)))
              (throw (ex-info "Publisher revision direction missing or conflicting" {:pair pair}))))
          :source-dependent
          (when-not (and (#{:publisher-mirror :publisher-aggregate :shared-upstream}
                          (:kind evidence))
                         (= (set pair) #{(:dependent-source evidence)
                                         (:upstream-source evidence)})
                         (publisher-citation? ledger (:citation evidence))
                         (contains? (set pair) (get-in evidence [:citation :source-id])))
            (throw (ex-info "Source dependence needs provenance evidence" {:pair pair}))))))
    (update ledger :events conj (cond-> (assoc event :rule-version attempt-rule-version)
                                  pair (assoc :pair pair :evidence-snapshot
                                              (event-subjects ledger type pair))))))

(defn rebase-attempt-ledger
  "Retain all decision history against replacement evidence. Changed or missing
   endpoint versions invalidate dependent acceptances until explicitly redecided."
  [ledger replacement]
  (let [fresh (empty-attempt-ledger replacement)
        invalidated (set (for [{:keys [id action type pair evidence-snapshot]} (:events ledger)
                               :when (and (= :accept action)
                                          (not= evidence-snapshot
                                                (event-subjects fresh type pair)))] id))]
    (assoc fresh :events (:events ledger) :invalidated-events invalidated)))

(defn- join-groups [groups left right]
  (let [members (into (get groups left) (get groups right))]
    (reduce #(assoc %1 %2 members) groups members)))

(defn project-attempts
  "Current private count projection from retained evidence and append-only events.
   An accepted count needs complete scope. Each source position stays citable."
  [ledger]
  (let [versions (:observation-versions ledger)
        groups (into {} (map (fn [id] [id #{id}]) (keys versions)))
        groups (reduce (fn [groups [_ rows]]
                         (let [ids (sort (map :id rows))]
                           (reduce (fn [groups id]
                                     (if (and (verified-scope? ledger (first ids))
                                              (verified-scope? ledger id)
                                              (= (scope-of ledger (first ids))
                                                 (scope-of ledger id)))
                                       (join-groups groups (first ids) id) groups))
                                   groups (rest ids))))
                       groups (group-by :position-id (vals versions)))
        automatic-links (automatic-attempt-links ledger)
        groups (reduce (fn [groups {:keys [pair type]}]
                         (if (= type :same-attempt)
                           (join-groups groups (first pair) (second pair)) groups))
                       groups (concat automatic-links (active-events ledger)))
        dependent-sources (set (keep #(when (= :source-dependent (:type %))
                                        (get-in % [:evidence :dependent-source]))
                                     (active-events ledger)))
        source-equivalence-links (automatic-source-equivalence-links ledger)
        equivalent-sources (set (mapcat :pair source-equivalence-links))
        attempts (->> (distinct (vals groups))
                      (map (fn [members]
                             (let [ids (vec (sort members))
                                   scope (scope-of ledger (first ids))]
                               {:id (str "attempt:" (first ids))
                                :scope scope :observation-ids ids
                                :position-ids (vec (sort (set (map #(get-in ledger [:observation-versions % :position-id]) ids))))
                                :source-ids (vec (sort (set (map #(source-of ledger %) ids))))
                                :source-support (mapv (fn [source-id]
                                                        {:source-id source-id
                                                         :role (cond (dependent-sources source-id) :dependent
                                                                     (equivalent-sources source-id) :equivalent
                                                                     :else :unknown)})
                                                      (sort (set (map #(source-of ledger %) ids))))
                                :status (if (and (every? #(verified-scope? ledger %) ids)
                                                 (every? #(= scope (scope-of ledger %)) ids))
                                          :accepted :unresolved)})))
                      (sort-by :id) vec)]
    {:version attempt-rule-version :revision (count (:events ledger))
     :attempts attempts :automatic-links automatic-links
     :source-equivalence-links source-equivalence-links
     :source-relationships (vec (filter #(not= :same-attempt (:type %))
                                        (active-events ledger)))
     :counts {:sources (count (:sources ledger))
              :source-objects (count (set (map :sha256 (vals (:sources ledger)))))
              :positions (count (:positions ledger))
              :observation-versions (count versions)
              :accepted-attempts (count (filter #(= :accepted (:status %)) attempts))
              :unresolved-observations (reduce + (map #(if (= :unresolved (:status %))
                                                         (count (:observation-ids %)) 0) attempts))}}))
