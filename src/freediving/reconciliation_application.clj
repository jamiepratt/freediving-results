(ns freediving.reconciliation-application
  "Run private reconciliation and materialize supported decisions in canonical ledgers."
  (:refer-clojure :exclude [run!])
  (:require [freediving.athlete-identity :as identity]
            [freediving.reconciliation-flow :as flow]
            [freediving.reviews :as reviews]
            [freediving.source-relationships :as relationships]))

(def ^:private model-origins #{:jev :cached-jev :retained})
(def ^:private correction-statuses #{:rejected :reversed})
(def ^:private satisfied-statuses #{:materialized :no-link})

(defn import-remote-review-events
  "Import authenticated owner rejections/reversals into the private flow ledger.
   verify-event! must authenticate each complete event against the persistent
   owner decision store. This function deliberately has no publisher-data path.
   A caller must persist the returned ledger before running canonical routing."
  [ledger decisions events {:keys [verify-event!]}]
  (when-not (and (fn? verify-event!) (vector? decisions) (vector? events)
                 (= flow/ledger-version (:version ledger)))
    (throw (ex-info "Authenticated remote review import required" {})))
  (let [by-id (into {} (map (juxt :id identity) decisions))]
    (reduce
     (fn [current {:keys [id decision-id store-revision snapshot-sha256
                          action actor reason decision] :as event}]
       (let [event-id (str "owner-store:" store-revision)
             prior (some #(when (= event-id (:id %)) %) (:events current))
             last-revision (reduce max 0 (keep :remote-store-revision (:events current)))
             status ({:reject :rejected :reverse :reversed} action)]
         (when-not (and (string? id) (seq id) (string? decision-id)
                        (pos-int? store-revision)
                        (string? snapshot-sha256)
                        (re-matches #"[0-9a-f]{64}" snapshot-sha256)
                        (string? actor) (seq actor) (string? reason)
                        status (= decision (get by-id decision-id))
                        (true? (verify-event! event)))
           (throw (ex-info "Unverified or stale remote review event"
                           {:decision-id decision-id :store-revision store-revision})))
         (if prior
           (if (= event (:remote-event prior)) current
               (throw (ex-info "Conflicting remote review replay"
                               {:store-revision store-revision})))
           (do
             (when (<= store-revision last-revision)
               (throw (ex-info "Out-of-order remote review event"
                               {:store-revision store-revision :last-revision last-revision})))
             (flow/append-human-event current
                                      {:id event-id :decision-id decision-id
                                       :status status :actor actor :reason reason
                                       :remote-store-revision store-revision
                                       :remote-snapshot-sha256 snapshot-sha256
                                       :remote-event event})))))
     ledger events)))

(defn- dependency-order [decisions]
  (loop [remaining decisions done #{} ordered []]
    (if (empty? remaining) ordered
        (if-let [ready (first (filter #(every? done (:dependencies %)) remaining))]
          (recur (filterv #(not= (:id %) (:id ready)) remaining)
                 (conj done (:id ready)) (conj ordered ready))
          (into ordered remaining)))))

(defn- unresolved [reason]
  {:status :unresolved :reason reason})

(defn- route-identity! [ledger decision view opts]
  (let [id (:id decision)]
    (cond
      (and (= :human (:origin view)) (correction-statuses (:status view)))
      (if-let [url (:reviewer-url opts)]
        {:status :reversed
         :projection (identity/sync-model-correction! url ledger decision (:config opts))}
        (unresolved :missing-reviewer-url))

      (and (= :approved (:status view)) (model-origins (:origin view)))
      (case (:action view)
        :same-person
        (if (and (:identity-url opts) (nat-int? (get-in opts [:identity-revisions id])))
          {:status :materialized
           :projection (identity/record-model-event! (:identity-url opts) ledger decision
                                                     (:config opts) (:policy opts)
                                                     (get-in opts [:identity-revisions id]))}
          (unresolved :missing-identity-target))
        :different-person {:status :no-link :reason :different-person}
        (unresolved :unsupported-identity-answer))

      :else (unresolved :identity-approval-not-model-owned))))

(defn- route-attempt [attempt-ledger flow-ledger decision view decisions opts]
  (let [revision (count (:events attempt-ledger))
        result (cond
                 (and (= :human (:origin view)) (correction-statuses (:status view)))
                 (relationships/synchronize-human-attempt-correction
                  attempt-ledger flow-ledger (:id decision) revision)

                 (and (= :approved (:status view)) (model-origins (:origin view)))
                 (relationships/apply-approved-jev-attempt-decision
                  attempt-ledger flow-ledger decision (:config opts) (:policy opts) revision decisions)

                 :else {:status :unresolved :reason :attempt-approval-not-model-owned
                        :ledger attempt-ledger})]
    [(:ledger result) (select-keys result [:status :reason :event :events :projection])]))

(defn- field-target [opts decision]
  (get-in opts [:field-targets (:id decision)]))

(defn- route-field! [flow-ledger decision view opts]
  (let [{:keys [target decision-type dictionary base-revision]} (field-target opts decision)
        request {:target target :decision-id (:id decision) :decision-type decision-type
                 :dictionary dictionary :policy (:policy opts) :base-revision base-revision}]
    (cond
      (and (= :human (:origin view)) (correction-statuses (:status view)))
      (if (and (:reviewer-url opts) target decision-type)
        (let [current (reviews/dive-fields (:reviewer-url opts) target)]
          {:status :reversed
           :event (reviews/sync-human-jev-dive-field!
                   (:reviewer-url opts) flow-ledger decision (:config opts)
                   (assoc request :base-revision (:revision current)))})
        (unresolved :missing-field-correction-target))

      (and (= :approved (:status view)) (model-origins (:origin view)))
      (cond
        (= :neither (:action view)) {:status :no-link :reason :neither-field}
        (= :both (:action view)) (unresolved :both-requires-separate-source-bound-fields)
        (not= decision-type (:action view)) (unresolved :field-meaning-mismatch)
        (not (and (:field-url opts) target dictionary (nat-int? base-revision)))
        (unresolved :missing-field-target)
        :else {:status :materialized
               :event (reviews/approve-jev-dive-field! (:field-url opts) flow-ledger decision
                                                       (:config opts) request)})

      :else (unresolved :field-approval-not-model-owned))))

(defn- route! [attempt-ledger flow-ledger decision view decisions opts]
  (case (:family decision)
    :identity [attempt-ledger (route-identity! flow-ledger decision view opts)]
    (:same-attempt :source-revision)
    (if attempt-ledger
      (route-attempt attempt-ledger flow-ledger decision view decisions opts)
      [attempt-ledger (unresolved :missing-attempt-ledger)])
    (:category :representation :category-representation)
    [attempt-ledger (route-field! flow-ledger decision view opts)]
    :row-semantics [attempt-ledger (unresolved :no-canonical-role-ledger)]
    [attempt-ledger (unresolved :unsupported-family)]))

(defn- attempt-model-decision-id [event]
  (when (and (= :accept (:action event)) (:approval-proof event))
    (or (get-in event [:evidence :decision-id])
        (get-in event [:evidence :jev :decision-id]))))

(defn- invalidate-dependent! [attempt-ledger flow-ledger decision view
                              dependency-statuses canonical-statuses opts]
  (case (:family decision)
    :identity
    (if-let [url (:identity-url opts)]
      (let [revision (:revision (identity/private-projection url))]
        [attempt-ledger {:status :reversed
                         :projection (identity/invalidate-model-dependency!
                                      url flow-ledger decision (:config opts)
                                      canonical-statuses revision)}])
      [attempt-ledger (unresolved :missing-identity-target)])

    (:same-attempt :source-revision)
    (if attempt-ledger
      (let [reversed (set (keep #(when (= :reverse (:action %)) (:event-id %))
                                (:events attempt-ledger)))
            active (filter #(and (= (:id decision) (attempt-model-decision-id %))
                                 (not (reversed (:id %)))) (:events attempt-ledger))]
        (if (seq active)
          (let [updated (reduce (fn [ledger event]
                                  (relationships/append-attempt-event
                                   ledger {:id (str "dependency-reverse:" (:id event))
                                           :action :reverse :event-id (:id event)}))
                                attempt-ledger active)]
            [updated {:status :reversed :reason :dependency-unapproved}])
          [attempt-ledger (unresolved :no-active-dependent-model-event)]))
      [attempt-ledger (unresolved :missing-attempt-ledger)])

    (:category :representation :category-representation)
    (let [{:keys [target decision-type dictionary]} (field-target opts decision)]
      (if (and (:field-url opts) target decision-type dictionary)
        (let [current (reviews/dive-fields (:field-url opts) target)]
          [attempt-ledger {:status :reversed
                           :event (reviews/invalidate-jev-dive-field!
                                   (:field-url opts) decision (:config opts)
                                   {:target target :decision-type decision-type
                                    :dictionary dictionary :base-revision (:revision current)
                                    :dependency-statuses dependency-statuses
                                    :canonical-dependency-statuses canonical-statuses
                                    :current-flow-status (:status view)})}])
        [attempt-ledger (unresolved :missing-field-target)]))

    [attempt-ledger (unresolved :canonical-dependency-unresolved)]))

(defn run!
  "Persist the private flow before canonical writes, then route current decisions.
   Canonical effects are idempotent but separate stores are not one transaction.
   A failed route remains explicit and can be retried with fresh revisions."
  [flow-ledger decisions {:keys [config policy persist-flow! persist-attempt!
                                 execute! checkpoint!
                                 retained-answers deterministic-results attempt-ledger]
                          :as opts}]
  (when-not (and (vector? decisions) (map? config) (map? policy)
                 (fn? persist-flow!)
                 (or (not-any? #(#{:same-attempt :source-revision} (:family %)) decisions)
                     (fn? persist-attempt!)))
    (throw (ex-info "Versioned decisions, config, policy and private persistence required" {})))
  (let [flow-ledger (flow/run! flow-ledger decisions
                               {:config config :policy policy :execute! execute!
                                :checkpoint! (fn [pending]
                                               (persist-flow! pending)
                                               (when checkpoint! (checkpoint! pending)))
                                :retained-answers retained-answers
                                :deterministic-results deterministic-results})
        _ (persist-flow! flow-ledger)
        views (flow/inspect flow-ledger decisions config)]
    (reduce (fn [{:keys [attempt-ledger results] :as state} decision]
              (let [id (:id decision)
                    view (get views id)
                    dependency-statuses (into {} (map (fn [dep]
                                                        [dep (get-in views [dep :status])])
                                                      (:dependencies decision)))
                    canonical-statuses (into {} (map (fn [dep]
                                                       [dep (if (satisfied-statuses
                                                                 (get-in results [dep :status]))
                                                              :approved :unresolved)])
                                                     (:dependencies decision)))
                    failed-flow-dependency? (some #(not= :approved %) (vals dependency-statuses))
                    blocked (some #(not (satisfied-statuses (get-in results [% :status])))
                                  (:dependencies decision))]
                (if (or blocked failed-flow-dependency?)
                  (try
                    (let [[next-ledger result]
                          (invalidate-dependent! attempt-ledger flow-ledger decision view
                                                 dependency-statuses canonical-statuses opts)
                          _ (when (and (not= next-ledger attempt-ledger) persist-attempt!)
                              (persist-attempt! next-ledger))]
                      (-> state (assoc :attempt-ledger next-ledger)
                          (assoc-in [:results id] result)))
                    (catch Exception error
                      (assoc-in state [:results id]
                                (assoc (unresolved :dependency-invalidation-failed)
                                       :detail (.getMessage error)))))
                  (if-not (or (= :approved (:status view))
                              (and (= :human (:origin view))
                                   (correction-statuses (:status view))))
                    (if (#{:identity :same-attempt :source-revision
                           :category :representation :category-representation}
                         (:family decision))
                      (try
                        (let [[next-ledger result]
                              (invalidate-dependent! attempt-ledger flow-ledger decision view
                                                     {} {} opts)
                              _ (when (and (not= next-ledger attempt-ledger) persist-attempt!)
                                  (persist-attempt! next-ledger))]
                          (-> state (assoc :attempt-ledger next-ledger)
                              (assoc-in [:results id] result)))
                        (catch Exception error
                          (assoc-in state [:results id]
                                    {:status :unresolved
                                     :reason (or (:reason view) :private-decision-unresolved)
                                     :flow-status (:status view)
                                     :invalidation-detail (.getMessage error)})))
                      (assoc-in state [:results id]
                                (merge (unresolved (or (:reason view) :private-decision-unresolved))
                                       {:flow-status (:status view)})))
                    (try
                      (let [[next-ledger result]
                            (route! attempt-ledger flow-ledger decision view decisions opts)
                            _ (when (and (not= next-ledger attempt-ledger) persist-attempt!)
                                (persist-attempt! next-ledger))]
                        (-> state
                            (assoc :attempt-ledger next-ledger)
                            (assoc-in [:results id] result)))
                      (catch Exception error
                        (assoc-in state [:results id]
                                  (assoc (unresolved :canonical-route-failed)
                                         :detail (or (:reason (ex-data error))
                                                     (.getMessage error))))))))))
            {:flow-ledger flow-ledger :attempt-ledger attempt-ledger :results {}}
            (dependency-order decisions))))
