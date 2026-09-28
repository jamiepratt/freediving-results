(ns freediving.comparison-score
  "Private policy for explicitly reviewed sporting attempts. The caller supplies source
   review and conversion evidence; this namespace never infers it from parsed fields.")

(def policy :aida-baseline-v1)

(def ^:private disciplines
  {:sta {:unit :s :increment 0.2M}
   :dynamic {:unit :m :increment 0.5M}
   :depth {:unit :m :increment 1M}
   :speed {:unit :s}})

(defn- exact-decimal [value]
  (when (or (instance? java.math.BigDecimal value) (integer? value))
    (bigdec value)))

(defn- at-precision? [value places]
  (and (int? places) (not (neg? places))
       (try
         (.setScale ^java.math.BigDecimal value places java.math.RoundingMode/UNNECESSARY)
         true
         (catch ArithmeticException _ false))))

(defn- reasons [row]
  (let [final (:official-final row)
        value (exact-decimal (:value final))
        discipline (:discipline row)
        expected-unit (get-in disciplines [discipline :unit])
        unit (:unit final)]
    (cond-> []
      (not= :verified (:review row)) (conj :unverified-review)
      (not= :distinct (:attempt-relationship row)) (conj :unresolved-attempt-relationship)
      (not (#{:finally-valid :finally-valid-penalized} (:status row))) (conj :unverified-valid-status)
      (not (contains? disciplines discipline)) (conj :unsupported-discipline)
      (not (and (map? (:citation row)) (seq (:citation row)))) (conj :missing-citation)
      (not (map? final)) (conj :missing-verified-final)
      (and (map? final) (not= :verified-publisher-post-penalty (:basis final)))
      (conj :unverified-final-basis)
      (and (map? final) (not= :verified (:conversion final)))
      (conj :unverified-conversion)
      (and (map? final) (not (and value (pos? value)))) (conj :ambiguous-final-value)
      (and (map? final) value (not (at-precision? value (:decimal-places final))))
      (conj :ambiguous-precision)
      (and (map? final) expected-unit (not (or (= expected-unit unit)
                                               (and (not= :speed discipline) (= :points unit)))))
      (conj :unsupported-unit)
      (and (map? final) (= :points unit)
           (not (and (= policy (get-in final [:comparable-basis :policy]))
                     (= :verified (get-in final [:comparable-basis :review]))
                     (seq (get-in final [:comparable-basis :citation])))))
      (conj :unverified-points-basis))))

(defn- floor-increment [value increment]
  (let [increment (bigdec increment)]
    (.multiply (.divideToIntegralValue ^java.math.BigDecimal value increment) increment)))

(defn- score [row]
  (let [final (:official-final row)
        value (exact-decimal (:value final))
        increment (get-in disciplines [(:discipline row) :increment])]
    (floor-increment (if (= :points (:unit final)) value (.multiply value increment)) increment)))

(defn- assign-ranks [rows value-key rank-key descending?]
  (let [ordered (sort-by (juxt (fn [row] (let [v (value-key row)] (if descending? (- v) v)))
                               (comp str :id)) rows)]
    (loop [remaining ordered index 1 previous nil previous-rank nil result {}]
      (if-let [row (first remaining)]
        (let [value (value-key row)
              rank (if (= value previous) previous-rank index)]
          (recur (rest remaining) (inc index) value rank
                 (assoc result (:id row) {rank-key rank})))
        result))))

(defn compare-verified
  "Compare supplied attempts under :aida-baseline-v1. Inputs need unique :id, reviewed
   distinct finally valid status, citation and verified publisher post-penalty final with
   exact decimal :value, :unit and :decimal-places. Accepted finals: STA seconds,
   dynamic/depth metres, or points with explicit verified comparable policy/basis.
   Preserves all caller fields. Speed seconds receive only a within-discipline rank.
   Withheld rows never enter either denominator; no DQ hypothetical is inferred."
  [attempts]
  (let [attempts (vec attempts)
        duplicates (->> attempts (group-by :id) (filter (fn [[_ rows]] (> (count rows) 1)))
                        (map key) set)
        classified (mapv (fn [input]
                           (let [row (dissoc input :comparison-score :comparison-rank
                                             :discipline-rank :comparison-status :reasons)
                                 why (cond-> (reasons row)
                                       (nil? (:id row)) (conj :missing-id)
                                       (contains? duplicates (:id row)) (conj :duplicate-id))]
                             (cond
                               (seq why) (assoc row :comparison-status :withheld :reasons why)
                               (= :speed (:discipline row))
                               (assoc row :comparison-status :speed-only :reasons [:speed-no-common-score])
                               :else (assoc row :comparison-status :ranked :reasons []
                                            :comparison-score (score row))))) attempts)
        scored (filter #(= :ranked (:comparison-status %)) classified)
        speed (filter #(= :speed-only (:comparison-status %)) classified)
        by-discipline (group-by :discipline scored)
        mixed-units (->> by-discipline
                         (keep (fn [[discipline rows]]
                                 (when (> (count (set (map #(get-in % [:official-final :unit]) rows))) 1)
                                   discipline)))
                         set)
        common-ranks (assign-ranks scored :comparison-score :comparison-rank true)
        discipline-ranks (reduce (fn [acc [_ rows]]
                                   (if (contains? mixed-units (:discipline (first rows)))
                                     acc
                                     (merge acc (assign-ranks rows
                                                              #(exact-decimal (get-in % [:official-final :value]))
                                                              :discipline-rank true))))
                                 {} by-discipline)
        speed-ranks (assign-ranks speed
                                  #(exact-decimal (get-in % [:official-final :value]))
                                  :discipline-rank false)]
    {:policy policy
     :rows (mapv #(cond-> (merge % (get common-ranks (:id %))
                                 (get discipline-ranks (:id %)) (get speed-ranks (:id %)))
                    (and (= :ranked (:comparison-status %))
                         (contains? mixed-units (:discipline %)))
                    (update :reasons conj :mixed-final-units)) classified)
     :coverage {:provided (count attempts) :ranked (count scored)
                :speed-only (count speed)
                :withheld (count (filter #(= :withheld (:comparison-status %)) classified))}}))
