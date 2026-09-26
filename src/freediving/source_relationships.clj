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
