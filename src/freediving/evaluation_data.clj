(ns freediving.evaluation-data
  "Versioned private shadow datasets. Group attestations cannot prove unknown identities absent."
  (:require [clojure.string :as str]))

(defn- demand! [pred message]
  (when-not pred (throw (ex-info message {:type :invalid-evaluation-data}))))
(defn- text? [x] (and (string? x) (not (str/blank? x)) (<= (count x) 4096)))
(defn- ids? [xs] (and (vector? xs) (<= 1 (count xs) 100) (every? text? xs)))
(defn- digest? [x] (and (string? x) (boolean (re-matches #"[0-9a-f]{64}" x))))
(defn- instant? [x]
  (and (string? x) (try (java.time.Instant/parse x) true (catch Exception _ false))))
(defn- portable? [x]
  (cond
    (map? x) (and (every? #(or (keyword? %) (string? %)) (keys x)) (every? portable? (vals x)))
    (vector? x) (every? portable? x)
    (number? x) (Double/isFinite (double x))
    :else (or (nil? x) (string? x) (keyword? x) (boolean? x))))
(defn- validate-case! [c]
  (demand! (and (map? c) (text? (:case-id c))
                (#{:held-out :development} (:split c))
                (map? (:input c)) (seq (:input c))
                (ids? (:source-family-ids c)) (ids? (:person-group-ids c))
                (vector? (:evidence c)) (<= 1 (count (:evidence c)) 100)) "Invalid case or grouping")
  (doseq [e (:evidence c)]
    (demand! (and (text? (:evidence-id e)) (digest? (:source-sha256 e))
                  (digest? (:artifact-sha256 e)) (text? (:observation-id e))
                  (pos-int? (:page e)) (vector? (:lines e)) (= 2 (count (:lines e)))
                  (every? pos-int? (:lines e)) (apply <= (:lines e))) "Invalid exact evidence reference"))
  (let [label (:label c)]
    (when (some? label)
      (demand! (and (map? label) (#{:match :no-match} (:outcome label))) "Invalid label outcome")
      (case (:provenance label)
        :synthetic (demand! (text? (:fixture-id label)) "Synthetic label requires fixture provenance")
        :owner (demand! (and (text? (:review-id label)) (text? (:reviewer label))
                             (instant? (:reviewed-at label)) (digest? (:review-artifact-sha256 label))
                             (ids? (:evidence-ids label))
                             (every? (set (map :evidence-id (:evidence c))) (:evidence-ids label)))
                        "Owner label requires reviewer and exact reviewed evidence")
        (demand! false "Unknown label provenance"))))
  c)

(defn- grouping-keys [c]
  (concat (map #(vector :family %) (:source-family-ids c))
          (map #(vector :person %) (:person-group-ids c))
          (mapcat (fn [e] [[:source (:source-sha256 e)] [:artifact (:artifact-sha256 e)]
                           [:observation (:source-sha256 e) (:observation-id e)]
                           [:evidence (:evidence-id e)]]) (:evidence c))))

(defn validate-dataset!
  "Validate bounded cases and reject any cross-split grouping edge, including chains.
   Grouping completeness is an explicit author attestation, not measured evidence."
  [dataset]
  (demand! (and (= 1 (:schema-version dataset)) (text? (:dataset-id dataset))
                (text? (:rubric-version dataset))
                (every? #(text? (get-in dataset [:grouping %])) [:attested-by :method :limitations])
                (vector? (:cases dataset)) (<= 1 (count (:cases dataset)) 10000)
                (portable? dataset)
                (<= (count (binding [*print-length* nil *print-level* nil] (pr-str dataset))) 10000000)) "Invalid dataset version, bounds, or grouping attestation")
  (let [cases (:cases dataset)]
    (doseq [c cases] (validate-case! c))
    (reduce (fn [seen e]
              (let [id (:evidence-id e)]
                (demand! (or (not (contains? seen id)) (= e (get seen id))) "Conflicting evidence ID")
                (assoc seen id e))) {} (mapcat :evidence cases))
    (demand! (= (count cases) (count (set (map :case-id cases)))) "Duplicate case IDs")
    (reduce (fn [seen c]
              (reduce (fn [acc k]
                        (demand! (or (nil? (get acc k)) (= (get acc k) (:split c))) "Held-out grouping leakage")
                        (assoc acc k (:split c))) seen (grouping-keys c))) {} cases))
  dataset)

(defn- nonnegative-finite? [x]
  (and (number? x) (Double/isFinite (double x)) (not (neg? x))))
(defn- fraction [n d] {:count n :denominator d :rate (when (pos? d) (/ n d))})
(defn- stratum [pairs]
  (let [n (count pairs)
        count-where #(count (filter % pairs))
        outcomes (frequencies (map (comp :outcome second) pairs))
        negatives (count-where #(= :no-match (get-in % [0 :label :outcome])))
        positives (count-where #(= :match (get-in % [0 :label :outcome])))
        false-merges (count-where #(and (= :no-match (get-in % [0 :label :outcome]))
                                        (= :match (get-in % [1 :outcome]))))
        missed (count-where #(and (= :match (get-in % [0 :label :outcome]))
                                  (= :no-match (get-in % [1 :outcome]))))
        abstentions (get outcomes :abstain 0) errors (get outcomes :error 0)]
    {:case-count n :match-labels positives :no-match-labels negatives
     :unlabeled (- n positives negatives)
     :decisive-count (+ (get outcomes :match 0) (get outcomes :no-match 0))
     :false-merges (fraction false-merges negatives)
     :missed-matches (fraction missed positives)
     :abstentions abstentions :errors errors :review-volume (+ abstentions errors)
     :abstention-rate (fraction abstentions n) :error-rate (fraction errors n)
     :review-rate (fraction (+ abstentions errors) n)}))

(defn metrics
  "One result per supplied case, normally held-out only. Errors/abstentions remain
   in label denominators but are reported separately, never as correct decisions.
   Review volume is abstain+error, not an authorization to auto-merge other cases.
   Missing latency is unknown; costs sum only explicitly metered amounts."
  [cases results]
  (demand! (and (vector? cases) (vector? results) (<= (count cases) 10000)) "Metrics require bounded vectors")
  (doseq [c cases] (validate-case! c))
  (let [ids (map :case-id cases) result-ids (map :case-id results)]
    (demand! (and (= (count ids) (count (set ids)))
                  (= (count result-ids) (count (set result-ids)))
                  (= (set ids) (set result-ids))) "Missing, extra, or duplicate predictions"))
  (doseq [r results]
    (demand! (#{:match :no-match :abstain :error} (:outcome r)) "Invalid prediction outcome")
    (demand! (or (nil? (:latency-ms r)) (nonnegative-finite? (:latency-ms r))) "Invalid latency")
    (let [cost (:cost r)]
      (demand! (and (map? cost)
                    (case (:status cost)
                      :not-incurred (and (= {:status :not-incurred} cost)
                                         (= :not-dispatched (:dispatch-status r))
                                         (= :error (:outcome r)) (= :comparator-halted (:error r))
                                         (string? (:halted-by-case-id r))
                                         (= [] (:attempts r)) (nil? (:latency-ms r)))
                      :unknown (and (not (contains? cost :amount)) (not (contains? cost :currency)))
                      :metered (and (nonnegative-finite? (:amount cost))
                                    (string? (:currency cost)) (boolean (re-matches #"[A-Z]{3}" (:currency cost))))
                      false)) "Cost must be explicitly unknown, metered, or undispatched")))
  (let [by-id (into {} (map (juxt :case-id identity) results))
        pairs (mapv #(vector % (by-id (:case-id %))) cases)
        grouped (group-by #(or (get-in % [0 :label :provenance]) :unlabeled) pairs)
        latencies (keep :latency-ms results)
        costs (map :cost results)
        metered (filter #(= :metered (:status %)) costs)
        undispatched (count (filter #(= :not-incurred (:status %)) costs))]
    {:overall (stratum pairs)
     :synthetic (stratum (get grouped :synthetic []))
     :owner (stratum [])
     :asserted (stratum (get grouped :owner []))
     :unlabeled (stratum (get grouped :unlabeled []))
     :latency (cond-> {:measured-count (count latencies) :unknown-count (- (count results) (count latencies) undispatched)
                       :total-ms (when (seq latencies) (reduce + latencies))
                       :mean-ms (when (seq latencies) (/ (reduce + latencies) (count latencies)))}
                (pos? undispatched) (assoc :not-dispatched-count undispatched))
     :cost (cond-> {:metered-count (count metered) :unknown-count (- (count costs) (count metered) undispatched)
                    :totals-by-currency (reduce #(update %1 (:currency %2) (fnil + 0M) (bigdec (:amount %2))) {} metered)}
             (pos? undispatched) (assoc :not-incurred-count undispatched))
     :limitations ["File owner provenance is an unverified assertion; only live database verification authorizes owner metrics."
                   "Synthetic labels do not estimate real-world accuracy or calibrated safety thresholds."
                   "False-merge and missed-match denominators include abstentions and errors; inspect coverage separately."
                   "Grouping attestations cannot rule out unknown duplicate sources or repeated identities."
                   "Shadow outcomes have no merge or publication authority."]}))

(defn metrics-verified
  "Verify the entire receipt against the authoritative database on every call.
   Caller maps, flags, hashes and review-shaped file labels cannot grant authority."
  [db-url receipt results]
  (let [verified ((requiring-resolve 'freediving.evaluation-labels/verify!) db-url receipt)
        cases (filterv #(= :held-out (:split %)) (get-in verified [:dataset :cases]))
        m (metrics cases results)]
    (cond-> (assoc m :verification {:receipt-id (:receipt-id verified)
                                    :label-source (:label-source verified)
                                    :scope :database-snapshot-at-verification})
      (= :verified-owner-review (:label-source verified))
      (assoc :owner (:asserted m) :asserted (stratum [])))))
