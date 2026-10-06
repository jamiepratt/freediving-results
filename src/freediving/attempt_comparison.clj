(ns freediving.attempt-comparison
  "Private, pure comparison of exact source observations. Callers supply reviewed sporting
   semantics; parser success, acquisition and this projection grant no review authority."
  (:require [clojure.string :as str]))

(defn- sha? [s] (boolean (and (string? s) (re-matches #"[0-9a-f]{64}" s))))
(defn- observation-key [row]
  (select-keys (:reference row) [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256]))
(defn- source-key [row]
  (select-keys (:source row) [:federation :event-id :view-id]))
(defn- selected-view? [request row]
  (contains? (:views request) (assoc (source-key row) :source-sha256 (get-in row [:reference :source-sha256]))))
(defn- result-value [row]
  (get-in row [:evidence :final :value]))
(defn- positive-decimal? [v]
  (and (number? v) (pos? v)))
(defn- verified-precision? [final]
  (let [value (:value final)
        places (:decimal-places final)]
    (and (or (instance? java.math.BigDecimal value) (integer? value))
         (int? places) (not (neg? places))
         (try
           (.setScale (bigdec value) places java.math.RoundingMode/UNNECESSARY)
           true
           (catch ArithmeticException _ false)))))
(defn- exact-reference? [ref]
  (and (map? ref) (every? #(contains? ref %) [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256])
       (every? #(and (string? %) (not (str/blank? %))) ((juxt :job-id :candidate-id) ref))
       (nat-int? (:ordinal ref)) (sha? (:source-sha256 ref)) (sha? (:artifact-sha256 ref))))

(defn- selected-conflict? [row]
  (let [selection (get-in row [:evidence :source-selection])]
    (and (= :selected-provisional (get-in row [:evidence :source-conflict]))
         (= (:reference row) (:selected-reference selection))
         (seq (:conflicting-references selection))
         (every? #(and (exact-reference? %) (not= (:reference row) %)) (:conflicting-references selection))
         (map? (:authority-citation selection)) (seq (:authority-citation selection))
         (map? (:selection-citation selection)) (seq (:selection-citation selection))
         (or (= :source-authority (:basis selection))
             (and (= :administrative-tie-break (:basis selection))
                  (= true (:equal-authority selection))
                  (= :exact-reference-lexical-v1 (:tie-break-rule selection))
                  (= (:reference row)
                     (first (sort-by (juxt :source-sha256 :ordinal :candidate-id :job-id :artifact-sha256)
                                     (cons (:reference row) (:conflicting-references selection))))))))))

(defn- source-conflict? [row]
  (or (not (or (= :resolved (get-in row [:evidence :source-conflict]))
               (selected-conflict? row)))
      (and (= "CMAS" (get-in row [:source :federation]))
           (not= (get-in row [:candidate :parsed :final-distance]) (result-value row)))
      (and (= "AIDA" (get-in row [:source :federation]))
           (or (= "RED" (some-> (get-in row [:candidate :parsed :card]) str/upper-case))
               (some-> (get-in row [:candidate :parsed :remarks]) str/upper-case (str/includes? "DQ"))))))
(defn- reasons [request row]
  (let [ref (:reference row)
        source (:source row)
        parsed (get-in row [:candidate :parsed])
        evidence (:evidence row)
        final (:final evidence)
        federation (:federation source)]
    (cond-> []
      (not (and (map? ref) (every? #(contains? ref %) [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256])
                (every? #(and (string? %) (not (str/blank? %))) ((juxt :job-id :candidate-id) ref))
                (nat-int? (:ordinal ref)) (sha? (:source-sha256 ref)) (sha? (:artifact-sha256 ref))))
      (conj :missing-exact-reference)
      (not (selected-view? request row)) (conj :unselected-source-view)
      (or (not= federation (:federation parsed))
          (and (= federation "AIDA") (not= (:view-id source) (:event-date parsed)))
          (and (= federation "CMAS") (not= "2026-06-11" (:event-date parsed))))
      (conj :source-context-conflict)
      (not (contains? (:federations request) federation)) (conj :federation-filter)
      (not= :pool (:environment source)) (conj :environment-filter)
      (not= "DNF" (:discipline parsed)) (conj :discipline-filter)
      (not= "2026" (some-> (:event-date parsed) (subs 0 (min 4 (count (:event-date parsed)))))) (conj :year-filter)
      (not (if (= federation "CMAS") (boolean (and (string? (:category parsed))
                                                   (re-matches #"SENIORS [\u2013\u2014-] WOMEN" (:category parsed))))
               (and (= "AIDA" federation) (= "Women" (:gender parsed))))) (conj :category-filter)
      (not (contains? (:representations request) (:representation parsed))) (conj :representation-filter)
      (not= :parsed (get-in row [:candidate :parse-status])) (conj :unparsed)
      (not= :attempts (if (= federation "AIDA") (get-in row [:candidate :source-family]) :attempts))
      (conj :not-attempt-view)
      (not= :official-results (:authority source)) (conj :unverified-source-authority)
      (not= :verified-final (:finality source)) (conj :unverified-source-finality)
      (not= :eligible (:sanction source)) (conj :unverified-sanction)
      (not= :verified (:review evidence)) (conj :unverified-review)
      (not (#{:finally-valid :finally-valid-penalized} (:outcome evidence))) (conj :unverified-outcome)
      (not= :distinct (:attempt-relationship evidence)) (conj :unresolved-attempt-relationship)
      (not (and (positive-decimal? (:value final)) (= "m" (:unit final))
                (= :verified-post-penalty (:basis final)))) (conj :unverified-final-distance)
      (not (verified-precision? final)) (conj :unverified-final-precision)
      (not= :verified (:conversion final)) (conj :unverified-final-conversion)
      (some? (:status parsed)) (conj :source-status-conflict)
      (source-conflict? row) (conj :source-conflict))))

(def ^:private filters
  #{:unselected-source-view :federation-filter :environment-filter :discipline-filter
    :year-filter :category-filter :representation-filter})

(defn compare-attempts
  "Return one account for each provided observation, with competition ranks for eligible rows.
   A withheld row has no rank. :coverage counts only this supplied snapshot, not a census.
   The input must name exact retained views; real review evidence must be supplied by the caller."
  [request observations]
  (when-not (and (= 2026 (:year request)) (= "DNF" (:discipline request))
                 (= :pool (:environment request)) (= :women (:gender request))
                 (= :seniors (:category request))
                 (= #{"CMAS" "AIDA"} (:federations request))
                 (set? (:views request)) (= 2 (count (:views request)))
                 (every? #(and (sha? (:source-sha256 %))
                               (every? (fn [k] (string? (get % k))) [:federation :event-id :view-id])) (:views request))
                 (set? (:representations request)) (seq (:representations request)))
    (throw (ex-info "Expected exact 2026 women DNF pool comparison scope" {})))
  (let [observations (vec observations)
        duplicates (->> observations (group-by observation-key) (filter (fn [[_ xs]] (> (count xs) 1))) (map key) set)
        unselected (set (mapcat #(get-in % [:evidence :source-selection :conflicting-references])
                                (filter selected-conflict? observations)))
        classified (mapv (fn [input]
                           (let [row (dissoc input :rank :comparison-status :reasons :hypothetical :provisional-rank?)
                                 why (cond-> (reasons request row)
                                       (contains? duplicates (observation-key row)) (conj :duplicate-reference)
                                       (contains? unselected (:reference row)) (conj :non-selected-source-claim))]
                             (assoc row :reasons why
                                    :comparison-status (cond
                                                         (some filters why) :excluded
                                                         (seq why) :withheld
                                                         :else :ranked)))) observations)
        ranked (sort-by (juxt (comp - result-value) (comp :candidate-id :reference)
                              (comp :job-id :reference) (comp :ordinal :reference))
                        (filter #(= :ranked (:comparison-status %)) classified))
        with-ranks (loop [rows ranked index 1 prior nil prior-rank nil out []]
                     (if-let [row (first rows)]
                       (let [value (result-value row)
                             rank (if (= value prior) prior-rank index)]
                         (recur (rest rows) (inc index) value rank
                                (conj out (assoc row :rank rank))))
                       out))
        provisional? (boolean (some selected-conflict? ranked))
        hypothetical (fn [row]
                       (let [evidence (get-in row [:evidence :hypothetical])
                             final (:final evidence)
                             checked (assoc-in row [:evidence :final] (assoc final :basis :verified-post-penalty))
                             why (remove #{:unverified-outcome :source-status-conflict :source-conflict}
                                         (reasons request checked))]
                         (when (and (= :verified-source-achieved (:basis final))
                                    (= (:value final) (get-in row [:candidate :parsed (if (= "CMAS" (get-in row [:source :federation])) :realized-distance :performance)]))
                                    (= :disqualified (get-in row [:evidence :outcome]))
                                    (= :resolved (get-in row [:evidence :source-conflict]))
                                    (map? (:citation evidence)) (seq (:citation evidence)) (empty? why)
                                    (not (contains? duplicates (observation-key row)))
                                    (not (contains? unselected (:reference row))))
                           {:rank (inc (count (filter #(> (result-value %) (:value final)) ranked)))
                            :eligible-peer-denominator (count ranked) :value (:value final) :unit (:unit final)
                            :status :disqualified :basis :verified-source-achieved :citation (:citation evidence)})))
        others (sort-by (juxt (comp str :comparison-status) (comp :candidate-id :reference)
                              (comp :job-id :reference) (comp :ordinal :reference))
                        (remove #(= :ranked (:comparison-status %)) classified))]
    {:scope request :rows (vec (concat (map #(assoc % :provisional-rank? provisional?) with-ranks)
                                       (map (fn [row] (if-let [h (hypothetical row)] (assoc row :hypothetical h) row)) others)))
     :coverage {:provided (count observations)
                :in-scope (count (remove #(= :excluded (:comparison-status %)) classified))
                :ranked (count ranked)
                :withheld (count (filter #(= :withheld (:comparison-status %)) classified))
                :excluded (count (filter #(= :excluded (:comparison-status %)) classified))}
     :provisional? (or provisional? (boolean (some #(= :withheld (:comparison-status %)) classified)))}))
