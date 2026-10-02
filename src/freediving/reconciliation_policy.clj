(ns freediving.reconciliation-policy
  "Pure, provisional approval gates for source-bound reconciliation answers.")

(def ^:private actions
  {:identity [:same-person :different-person]
   :same-attempt [:same-attempt :distinct-attempts]
   :source-revision [:left-revises-right :right-revises-left :same-source :unrelated]
   :category-representation [:category :representation :both :neither]
   :row-semantics [:attempt :aggregate :summary :not-result]})

(def default-policy
  {:version "reconciliation-approval-v1"
   :thresholds
   (into {} (map (fn [[family choices]]
                   [family (into {} (map (fn [choice]
                                           [choice {:min-confidence 0.98
                                                    :min-probability 0.97
                                                    :min-margin 0.30}]) choices))]) actions))})

(defn- unit-number? [value]
  (and (number? value) (Double/isFinite (double value)) (<= 0 value 1)))

(defn- valid-distribution? [probabilities family action]
  (and (map? probabilities)
       (= (set (conj (get actions family) :unknown)) (set (keys probabilities)))
       (contains? probabilities action)
       (every? keyword? (keys probabilities))
       (every? unit-number? (vals probabilities))
       (< (Math/abs (- 1.0 (double (reduce + (vals probabilities))))) 0.00001)))

(defn- valid-thresholds? [thresholds]
  (and (map? thresholds)
       (every? unit-number? (map thresholds [:min-confidence :min-probability :min-margin]))))

(defn assess
  "Return a fail-closed approval outcome. Probabilities remain exactly as given.
  Decision evidence and candidate checks must be supplied by the caller."
  [policy decision answer]
  (let [action (:action decision)
        thresholds (get-in policy [:thresholds (:family decision) action])
        probabilities (:probabilities answer)
        selected (get probabilities action)
        alternatives (vals (dissoc probabilities action))
        reason (cond
                 (not (and (string? (:version policy)) (seq (:version policy)))) :invalid-policy
                 (not (valid-thresholds? thresholds)) :unsupported-action
                 (or (:stale? decision) (:stale? answer)) :stale-evidence
                 (or (:error answer) (= :error (:outcome answer))) :provider-error
                 (or (= :unknown action) (= :unknown (:outcome answer))) :unknown-answer
                 (not (:evidence-adequate? decision)) :inadequate-evidence
                 (seq (:conflicts decision)) :conflicting-evidence
                 (not= action (:outcome answer)) :unexpected-answer
                 (not (and (unit-number? (:confidence answer))
                           (valid-distribution? probabilities (:family decision) action))) :invalid-answer
                 (< (:confidence answer) (:min-confidence thresholds)) :low-confidence
                 (< selected (:min-probability thresholds)) :low-probability
                 (< (- selected (apply max alternatives)) (:min-margin thresholds)) :close-alternative
                 :else nil)]
    {:status (if reason :unresolved :approve)
     :reason (or reason :thresholds-met)
     :policy-version (:version policy)}))

(defn review-queue
  "Pending scored work in increasing confidence order, then scoreless work.
  Equal scores retain input order; each scoreless entry carries its own reason."
  [entries]
  (->> entries
       (map-indexed (fn [index entry]
                      (let [confidence (or (:confidence entry) (get-in entry [:answer :confidence]))]
                        (assoc entry ::index index
                               ::score (when (unit-number? confidence) confidence)))))
       (sort-by (fn [entry] [(if (some? (::score entry)) 0 1)
                             (or (::score entry) 0)
                             (::index entry)]))
       (mapv #(-> % (dissoc ::index ::score)
                  (cond-> (nil? (::score %))
                    (assoc :scoreless-reason
                           (or (:error %) (:reason %)
                               (if (or (some? (:confidence %))
                                       (some? (get-in % [:answer :confidence])))
                                 :invalid-score :no-score))))))))
