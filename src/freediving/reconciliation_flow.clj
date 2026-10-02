(ns freediving.reconciliation-flow
  "Private, append-only reconciliation dispatch. It records proposed links, not publication approval."
  (:refer-clojure :exclude [run!])
  (:require [freediving.reconciliation-jev :as jev]
            [freediving.reconciliation-policy :as policy])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def ledger-version "reconciliation-flow/1")

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (canonical item)]) value))
    (vector? value) (mapv canonical value)
    (set? value) (vec (sort-by pr-str (map canonical value)))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical value)) "UTF-8"))))

(defn empty-ledger [] {:version ledger-version :events []})

(defn append-human-event
  "Record a durable owner correction, including rejection or reversal."
  [ledger {:keys [id decision-id status] :as event}]
  (when-not (and (= ledger-version (:version ledger)) (string? id) (string? decision-id)
                 (#{:approved :rejected :reversed} status)
                 (not-any? #(= id (:id %)) (:events ledger)))
    (throw (ex-info "Invalid human reconciliation event" {:event event})))
  (update ledger :events conj (assoc event :origin :human)))

(defn- evidence-key [decision]
  (digest (select-keys decision [:id :family :action :choices :evidence :candidates
                                 :dependencies :conflicts :stale? :evidence-adequate?])))

(defn- decision-key [decision config]
  (digest [(evidence-key decision) config jev/template-version]))

(defn- human-event [ledger decision-id]
  (last (filter #(and (= :human (:origin %)) (= decision-id (:decision-id %))) (:events ledger))))

(defn- current-event [ledger decision config]
  (let [key (decision-key decision config)
        version (:version config)]
    (last (filter #(and (= (:id decision) (:decision-id %))
                        (= key (:decision-key %))
                        (= version (:config-version %))) (:events ledger)))))

(defn- result-view [decision event]
  {:status (:status event) :origin (:origin event) :answer (:answer event)
   :alternatives (when-let [probs (get-in event [:answer :probabilities])]
                   (dissoc probs (get-in event [:answer :outcome])))
   :evidence (:evidence decision) :candidates (:candidates decision)
   :dependencies (:dependencies decision) :policy (:assessment event)
   :policy-version (:policy-version event) :reason (:reason event)
   :request-hash (:request-hash event) :result-hash (:result-hash event)
   :receipt (:receipt event)})

(defn inspect
  "Private current view keyed by decision ID. Pass config to detect changed model/config."
  ([ledger decisions] (inspect ledger decisions nil))
  ([ledger decisions config]
   (into {}
         (for [decision decisions
               :let [event (or (human-event ledger (:id decision))
                               (when config (current-event ledger decision config))
                               (when-not config
                                 (last (filter #(and (= (:id decision) (:decision-id %))
                                                     (= (evidence-key decision) (:evidence-key %)))
                                               (:events ledger)))))]]
           [(:id decision) (if event (result-view decision event)
                               {:status :unresolved :origin nil :evidence (:evidence decision)})]))))

(defn- append-result [ledger decision config payload]
  (let [event (merge {:id (digest [(:id decision) (decision-key decision config)
                                   (:policy-version payload) (:status payload) (:result-hash payload)])
                      :decision-id (:id decision) :family (:family decision)
                      :action (:action decision) :origin :jev
                      :evidence-key (evidence-key decision)
                      :decision-key (decision-key decision config)
                      :config-version (:version config)
                      :evidence (:evidence decision) :candidates (:candidates decision)
                      :dependencies (:dependencies decision)} payload)]
    (if (some #(= (:id event) (:id %)) (:events ledger)) ledger
        (update ledger :events conj event))))

(defn- classify-answer [decision answer policy-config]
  (let [assessment (policy/assess policy-config decision answer)]
    {:status (if (= :approve (:status assessment)) :approved :unresolved)
     :reason (:reason assessment) :assessment assessment
     :policy-version (:policy-version assessment) :answer answer
     :result-hash (:result-hash answer) :receipt (:receipt answer)}))

(defn- dispatch-batch [ledger decisions request config policy-config execute!]
  (try
    (let [response (execute! request)
          _ (when (:error response)
              (throw (ex-info "Reconciliation transport error" {:error (:error response)})))
          parsed (jev/parse-batch request response)]
      (reduce (fn [ledger decision]
                (let [answer (get-in parsed [:answers (:id decision)])
                      payload (if answer (classify-answer decision answer policy-config)
                                  {:status :invalid-response :reason :missing-answer
                                   :policy-version (:version policy-config)})]
                  (append-result ledger decision config
                                 (assoc payload :request-hash (:request-hash request)
                                        :template-version (:template-version request)))))
              ledger decisions))
    (catch Exception error
      (let [reason (or (:error (ex-data error)) :provider-error)
            status (case reason :timeout :timeout :interrupted :interrupted :provider-error)]
        (reduce (fn [ledger decision]
                  (append-result ledger decision config
                                 {:status status :reason reason
                                  :policy-version (:version policy-config)
                                  :request-hash (:request-hash request)
                                  :template-version (:template-version request)}))
                ledger decisions)))))

(defn- retained-valid? [answer decision config]
  (and (= (decision-key decision config) (:decision-key answer))
       (= jev/template-version (:template-version answer))
       (= (:model config) (:model-version answer))
       (map? (:receipt answer))))

(defn- ready? [decision known]
  (every? #(= :approved (get known %)) (:dependencies decision)))

(defn run!
  "Resolve a bounded set of decisions. execute! is the only external boundary.
   Retained answers and deterministic outcomes bypass HTTP. Independent ready
   decisions batch together; dependencies advance only after acceptance."
  [ledger decisions {:keys [config policy execute! retained-answers deterministic-results]}]
  (when-not (and (= ledger-version (:version ledger)) (vector? decisions)
                 (= (count decisions) (count (set (map :id decisions))))
                 (every? (comp string? :id) decisions) (map? config) (map? policy))
    (throw (ex-info "Invalid reconciliation run" {})))
  (let [execute! (or execute! (fn [_] (throw (ex-info "Missing reconciliation transport" {:error :missing-transport}))))]
    (loop [ledger ledger remaining decisions known {}]
      (if (empty? remaining) ledger
          (let [ready (filterv #(every? (set (keys known)) (:dependencies %)) remaining)]
            (if (empty? ready)
              (reduce (fn [ledger decision]
                        (append-result ledger decision config
                                       {:status :dependency-blocked :reason :missing-or-cyclic-dependency
                                        :policy-version (:version policy)})) ledger remaining)
              (let [ledger
                    (reduce (fn [ledger decision]
                              (let [id (:id decision)
                                    human (human-event ledger id)
                                    prior (current-event ledger decision config)
                                    deterministic (get deterministic-results id)
                                    retained (get retained-answers id)]
                                (cond
                                  human ledger
                                  (not (ready? decision known))
                                  (append-result ledger decision config
                                                 {:status :dependency-blocked :reason :dependency-unapproved
                                                  :policy-version (:version policy)})
                                  deterministic
                                  (append-result ledger decision config
                                                 {:status (if (and (= :approve (:status deterministic))
                                                                   (:rule-version deterministic)) :approved :unresolved)
                                                  :origin :deterministic :reason (:reason deterministic)
                                                  :rule-version (:rule-version deterministic)
                                                  :policy-version (:version policy)})
                                  (and prior (:answer prior) (not= (:version policy) (:policy-version prior)))
                                  (append-result ledger decision config
                                                 (assoc (classify-answer decision (:answer prior) policy)
                                                        :origin :cached-jev
                                                        :request-hash (:request-hash prior)
                                                        :template-version (:template-version prior)))
                                  (and retained (retained-valid? retained decision config))
                                  (append-result ledger decision config
                                                 (assoc (classify-answer decision retained policy)
                                                        :origin :retained))
                                  (and prior (= (:version policy) (:policy-version prior))
                                       (not (#{:timeout :provider-error :interrupted :invalid-response} (:status prior)))) ledger
                                  :else ledger)))
                            ledger ready)
                    pending (filterv (fn [decision]
                                       (let [event (or (human-event ledger (:id decision))
                                                       (current-event ledger decision config))]
                                         (and (ready? decision known)
                                              (or (nil? event)
                                                  (#{:timeout :provider-error :interrupted :invalid-response} (:status event))))))
                                     ready)
                    ledger (if (seq pending)
                             (reduce (fn [ledger request]
                                       (let [ids (set (:decision-ids request))
                                             members (filterv #(ids (:id %)) pending)]
                                         (dispatch-batch ledger members request config policy execute!)))
                                     ledger (jev/prepare-batches config pending))
                             ledger)
                    known (reduce (fn [known decision]
                                    (assoc known (:id decision)
                                           (:status (or (human-event ledger (:id decision))
                                                        (current-event ledger decision config)))))
                                  known ready)
                    remaining (filterv (complement (set ready)) remaining)]
                (recur ledger remaining known))))))))
