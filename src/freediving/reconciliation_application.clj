(ns freediving.reconciliation-application
  "Run private reconciliation and materialize supported decisions in canonical ledgers."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [freediving.athlete-identity :as identity]
            [freediving.reconciliation-flow :as flow]
            [freediving.reviews :as reviews]
            [freediving.source-relationships :as relationships])
  (:import [java.security MessageDigest]
           [java.util HexFormat]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private model-origins #{:jev :cached-jev :retained})
(def ^:private correction-statuses #{:rejected :reversed})
(def ^:private satisfied-statuses #{:materialized :no-link})

(defn- authenticated-feed [envelope secret]
  (let [{:keys [payload_json signature]} envelope]
    (when-not (and (string? secret) (<= 24 (count secret) 256)
                   (string? payload_json) (<= (count payload_json) 2097152)
                   (string? signature) (re-matches #"[0-9a-f]{64}" signature))
      (throw (ex-info "Invalid owner transport envelope" {})))
    (let [mac (Mac/getInstance "HmacSHA256")
          _ (.init mac (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256"))
          actual (.doFinal mac (.getBytes payload_json "UTF-8"))
          expected (.parseHex (HexFormat/of) signature)]
      (when-not (MessageDigest/isEqual actual expected)
        (throw (ex-info "Owner transport authentication failed" {})))
      (try (json/read-str payload_json :key-fn keyword)
           (catch Exception _ (throw (ex-info "Invalid owner transport payload" {})))))))

(defn import-remote-review-events
  "Import signed owner events only when exact exported evidence bindings still match.
   The returned ledger must be persisted before canonical routing."
  [ledger decisions envelope {:keys [import-token current-bindings active-snapshot-sha256
                                     verified-snapshot-bindings]}]
  (when-not (and (vector? decisions) (map? current-bindings)
                 (string? active-snapshot-sha256)
                 (= flow/ledger-version (:version ledger)))
    (throw (ex-info "Authenticated remote review import required" {})))
  (let [{:keys [events next_revision store_revision]} (authenticated-feed envelope import-token)
        by-id (into {} (map (juxt :id identity) decisions))]
    (when-not (and (vector? events) (nat-int? next_revision) (nat-int? store_revision)
                   (<= next_revision store_revision))
      (throw (ex-info "Invalid owner event feed" {})))
    (reduce
     (fn [current {:keys [id decision_id store_revision snapshot_sha256
                          binding_revision action actor reason proposal correction] :as event}]
       (let [decision (get by-id decision_id)
             event-id (str "owner-store:" store_revision)
             prior (some #(when (= event-id (:id %)) %) (:events current))
             last-revision (reduce max 0 (keep :remote-store-revision (:events current)))
             status ({"approve" :approved "correct" :approved
                      "reject" :rejected "reverse" :reversed} action)
             selected-action (if (= action "correct")
                               (some-> (:action correction) keyword)
                               (:action decision))]
         (when-not (and (= id event-id) (string? decision_id) decision
                        (pos-int? store_revision) (pos-int? binding_revision)
                        (or (= snapshot_sha256 active-snapshot-sha256)
                            (= (get-in proposal [:canonical_binding])
                               (get-in verified-snapshot-bindings [snapshot_sha256 decision_id])))
                        (string? actor) (seq actor) (string? reason)
                        status (map? (get-in proposal [:canonical_binding]))
                        (= decision_id (get-in proposal [:canonical_binding :decision_id]))
                        (= (get-in proposal [:canonical_binding])
                           (get current-bindings decision_id))
                        (or (not= action "correct")
                            (and (map? correction) (keyword? selected-action)
                                 (contains? (set (:choices decision)) selected-action))))
           (throw (ex-info "Unverified or stale remote review event"
                           {:decision-id decision_id :store-revision store_revision})))
         (if prior
           (if (= event (:remote-event prior)) current
               (throw (ex-info "Conflicting remote review replay"
                               {:store-revision store_revision})))
           (do
             (when (<= store_revision last-revision)
               (throw (ex-info "Out-of-order remote review event"
                               {:store-revision store_revision :last-revision last-revision})))
             (flow/append-human-event current
                                      {:id event-id :decision-id decision_id
                                       :status status :action selected-action
                                       :actor actor :reason reason :correction correction
                                       :remote-store-revision store_revision
                                       :remote-binding-revision binding_revision
                                       :remote-snapshot-sha256 snapshot_sha256
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
