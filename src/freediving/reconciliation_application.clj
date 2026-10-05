(ns freediving.reconciliation-application
  "Run private reconciliation and materialize supported decisions in canonical ledgers."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [freediving.athlete-identity :as identity]
            [freediving.canonical-attempt-store :as attempt-store]
            [freediving.owner-identity-route :as owner-identity]
            [freediving.reconciliation-flow :as flow]
            [freediving.reviews :as reviews]
            [freediving.source-relationships :as relationships])
  (:import [java.security MessageDigest]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util HexFormat]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private model-origins #{:jev :cached-jev :retained})
(def ^:private correction-statuses #{:rejected :reversed})
(def ^:private satisfied-statuses #{:materialized :no-link})
(def ^:private delivered-statuses #{:materialized :no-link :reversed})

(defn fetch-owner-review-events
  "Fetch the private signed feed through Access service identity. The caller
   passes the returned envelope to import-remote-review-events for HMAC and
   immutable-binding verification. HTTP is permitted only for local synthetic tests."
  [base-url after-revision {:keys [access-client-id access-client-secret import-token
                                   allow-loopback-http?]}]
  (let [base (try (URI. base-url) (catch Exception _ nil))
        scheme (some-> base .getScheme)
        host (some-> base .getHost)
        safe? (or (and (= scheme "https") (= host "poc.alphacompose.com"))
                  (and allow-loopback-http? (= scheme "http") (= host "127.0.0.1")))]
    (when-not (and safe? (nat-int? after-revision) (seq access-client-id)
                   (seq access-client-secret) (seq import-token)
                   (nil? (.getUserInfo base)) (nil? (.getQuery base))
                   (nil? (.getFragment base)) (#{"" "/"} (.getPath base))
                   (or (= host "127.0.0.1") (= -1 (.getPort base))))
      (throw (ex-info "Invalid owner event transport configuration" {})))
    (let [url (URI. (str (if (= "/" (.getPath base))
                           (subs base-url 0 (dec (count base-url))) base-url)
                         "/owner-evidence/api/decision-events?after_revision=" after-revision))
          client (.build (.followRedirects (HttpClient/newBuilder) HttpClient$Redirect/NEVER))
          request (.build (-> (HttpRequest/newBuilder url)
                              (.timeout (Duration/ofSeconds 20))
                              (.header "CF-Access-Client-Id" access-client-id)
                              (.header "CF-Access-Client-Secret" access-client-secret)
                              (.header "X-Freediving-Import-Token" import-token)
                              (.header "Accept" "application/json")
                              (.GET)))
          response (.send client request (HttpResponse$BodyHandlers/ofByteArray))
          bytes (.body response)]
      (when-not (and (= 200 (.statusCode response)) (<= (alength bytes) 2097152))
        (throw (ex-info "Owner event transport failed" {:status (.statusCode response)})))
      (try (json/read-str (String. bytes "UTF-8") :key-fn keyword)
           (catch Exception _ (throw (ex-info "Invalid owner event transport response" {})))))))

(defn ack-owner-review-event!
  "Acknowledge one target only after its durable callback returns a receipt."
  [target event receipt {:keys [base-url access-client-id access-client-secret import-token
                                allow-loopback-http?]}]
  (when-not (and (#{:flow-ledger :postgresql} target)
                 (map? event) (string? receipt) (seq receipt) (<= (count receipt) 512))
    (throw (ex-info "Invalid owner event acknowledgement" {})))
  (let [base (try (URI. base-url) (catch Exception _ nil))
        scheme (some-> base .getScheme)
        host (some-> base .getHost)
        safe? (or (and (= scheme "https") (= host "poc.alphacompose.com"))
                  (and allow-loopback-http? (= scheme "http") (= host "127.0.0.1")))
        _ (when-not (and safe? (pos-int? (:store_revision event))
                         (seq access-client-id) (seq access-client-secret) (seq import-token)
                         (nil? (.getUserInfo base)) (nil? (.getQuery base))
                         (nil? (.getFragment base)) (#{"" "/"} (.getPath base))
                         (or (= host "127.0.0.1") (= -1 (.getPort base))))
            (throw (ex-info "Invalid owner event transport configuration" {})))
        url (URI. (str (if (= "/" (.getPath base))
                         (subs base-url 0 (dec (count base-url))) base-url)
                       "/owner-evidence/api/decision-events/ack"))
        body (json/write-str {:target (name target) :event event :receipt receipt})
        client (.build (.followRedirects (HttpClient/newBuilder) HttpClient$Redirect/NEVER))
        request (.build (-> (HttpRequest/newBuilder url)
                            (.timeout (Duration/ofSeconds 20))
                            (.header "CF-Access-Client-Id" access-client-id)
                            (.header "CF-Access-Client-Secret" access-client-secret)
                            (.header "X-Freediving-Import-Token" import-token)
                            (.header "Content-Type" "application/json")
                            (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))))
        response (.send client request (HttpResponse$BodyHandlers/ofByteArray))
        bytes (.body response)]
    (when-not (and (= 200 (.statusCode response)) (<= (alength bytes) 4096))
      (throw (ex-info "Owner event acknowledgement failed" {:status (.statusCode response)})))
    (let [result (json/read-str (String. bytes "UTF-8") :key-fn keyword)]
      (when-not (and (= (name target) (:target result))
                     (= (:id event) (:event_id result))
                     (nat-int? (get-in result [:checkpoints target]))
                     (<= (:store_revision event) (get-in result [:checkpoints target])))
        (throw (ex-info "Invalid owner event acknowledgement receipt" {})))
      result)))

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
                                     active-binding-revision verified-snapshot-bindings
                                     through-event-id]}]
  (when-not (and (vector? decisions) (map? current-bindings)
                 (string? active-snapshot-sha256)
                 (pos-int? active-binding-revision)
                 (= flow/ledger-version (:version ledger)))
    (throw (ex-info "Authenticated remote review import required" {})))
  (let [{:keys [events next_revision store_revision] :as feed}
        (authenticated-feed envelope import-token)
        by-id (into {} (map (juxt :id identity) decisions))
        selected (if through-event-id
                   (let [prefix (vec (take-while #(not= through-event-id (:id %)) events))
                         target (nth events (count prefix) nil)]
                     (when-not (= through-event-id (:id target))
                       (throw (ex-info "Requested owner event absent from signed feed"
                                       {:event-id through-event-id})))
                     (conj prefix target))
                   events)]
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
             selected-action (case action
                               "approve" (some-> (get proposal :selected_option)
                                                 (str/replace "_" "-") keyword)
                               "correct" (some-> (:action correction) (str/replace "_" "-") keyword)
                               "reject" (case (:family decision)
                                          :identity :different-person
                                          :same-attempt :distinct-attempts
                                          :source-revision :unrelated
                                          (:action decision))
                               (:action decision))]
         (when-not (and (= id event-id) (string? decision_id) decision
                        (pos-int? store_revision) (pos-int? binding_revision)
                        (<= store_revision (:store_revision feed))
                        (<= store_revision next_revision)
                        (or (and (= active-binding-revision binding_revision)
                                 (= snapshot_sha256 active-snapshot-sha256))
                            (and (not= snapshot_sha256 active-snapshot-sha256)
                                 (<= binding_revision active-binding-revision)
                                 (= (get-in proposal [:canonical_binding])
                                    (get-in verified-snapshot-bindings
                                            [snapshot_sha256 decision_id]))))
                        (string? actor) (seq actor) (string? reason)
                        status (map? (get-in proposal [:canonical_binding]))
                        (= decision_id (get-in proposal [:canonical_binding :decision_id]))
                        (= (get-in proposal [:canonical_binding])
                           (get current-bindings decision_id))
                        (or (not= action "approve")
                            (and (keyword? selected-action)
                                 (contains? (set (:choices decision)) selected-action)))
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
     ledger selected)))

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
  (let [id (:id decision)
        human (last (filter #(and (= :human (:origin %))
                                  (= id (:decision-id %))) (:events ledger)))]
    (cond
      (and (= :human (:origin view))
           (#{:approved :rejected :reversed} (:status view))
           (:remote-event human))
      (if (and (:reviewer-url opts)
               (get-in opts [:current-bindings id])
               (nat-int? (get-in opts [:identity-revisions id])))
        {:status (if (= :reversed (:status view)) :reversed :materialized)
         :projection (if (:source-registration opts)
                       (owner-identity/record-imported-decision!
                        (:reviewer-url opts) ledger decision
                        (get-in opts [:current-bindings id])
                        (get-in opts [:identity-revisions id])
                        (:source-registration opts))
                       (owner-identity/record-imported-decision!
                        (:reviewer-url opts) ledger decision
                        (get-in opts [:current-bindings id])
                        (get-in opts [:identity-revisions id])))}
        (unresolved :missing-owner-identity-target))

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

(defn- route-imported-attempt! [flow-ledger decision view opts]
  (let [id (:id decision)
        human (last (filter #(and (= :human (:origin %))
                                  (= id (:decision-id %))) (:events flow-ledger)))
        owner (:remote-event human)
        prior (last (filter #(and (= :human (:origin %))
                                  (= id (:decision-id %))
                                  (not= (:id human) (:id %))) (:events flow-ledger)))
        action (:action owner)
        family (:family decision)
        pair (:candidates decision)
        choice (:action view)
        positive? (case family
                    :same-attempt (= :same-attempt choice)
                    :source-revision (#{:left-revises-right :right-revises-left} choice)
                    false)
        prior-positive? (and (= :approved (:status prior))
                             (case family
                               :same-attempt (= :same-attempt (:action prior))
                               :source-revision
                               (#{:left-revises-right :right-revises-left} (:action prior))
                               false))
        canonical-action (cond
                           (= action "reverse") (when prior-positive? :reverse)
                           (and (#{"correct" "reject"} action) (not positive?))
                           (when prior-positive? :reverse)
                           (and prior-positive? positive?) nil
                           (and (#{"approve" "correct"} action) positive?) :accept)
        evidence (case family
                   :same-attempt (when (= :same-attempt choice)
                                   {:kind :verified-scope})
                   :source-revision (let [publisher (:publisher-evidence decision)
                                          [left right] pair
                                          direction (case choice
                                                      :left-revises-right [right left]
                                                      :right-revises-left [left right]
                                                      nil)]
                                      (when (and direction
                                                 (#{:publisher-version :publisher-correction}
                                                  (:kind publisher))
                                                 (= direction [(:predecessor publisher)
                                                               (:successor publisher)]))
                                        publisher))
                   nil)]
    (if-not (and (#{"approve" "correct" "reject" "reverse"} action)
                 (#{:same-attempt :source-revision} family)
                 (vector? pair) (= 2 (count pair))
                 (or (not= canonical-action :accept) evidence)
                 (or (not= canonical-action :reverse)
                     (and prior-positive?
                          (= (get-in prior [:remote-event :proposal :canonical_binding])
                             (get-in owner [:proposal :canonical_binding]))))
                 (or (not= action "correct")
                     (contains? (set (:choices decision)) choice))
                 (:attempt-url opts)
                 (nat-int? (get-in opts [:attempt-revisions id]))
                 (= (get-in owner [:proposal :canonical_binding])
                    (get-in opts [:current-bindings id])))
      (unresolved :unsupported-or-stale-owner-attempt-decision)
      (if-not canonical-action
        (if (and (#{"correct" "reject" "reverse"} action)
                 (not positive?))
          {:status :no-link :reason :human-negative-attempt-decision}
          (unresolved :contradictory-owner-attempt-decision))
        (let [current (attempt-store/private-ledger (:attempt-url opts))
              event (cond-> {:id (:id human) :action canonical-action
                             :type family :pair pair}
                      (= canonical-action :accept) (assoc :evidence evidence)
                      (= canonical-action :reverse) (assoc :event-id (:id prior)))
              request {:event event :owner-event owner
                       :binding (get-in opts [:current-bindings id])
                       :subject-snapshot (relationships/attempt-subjects
                                          current family pair)
                       :expected-revision (get-in opts [:attempt-revisions id])}]
          {:status (if (= canonical-action :reverse) :reversed :materialized)
           :projection (attempt-store/record-owner-decision!
                        (:attempt-url opts) request)})))))

(defn- field-target [opts decision]
  (get-in opts [:field-targets (:id decision)]))

(defn- route-imported-field! [flow-ledger decision view opts]
  (let [id (:id decision)
        human (last (filter #(and (= :human (:origin %))
                                  (= id (:decision-id %))) (:events flow-ledger)))
        owner (:remote-event human)
        action (:action owner)
        {:keys [target decision-type base-revision]} (field-target opts decision)
        binding (get-in opts [:current-bindings id])
        current (when (and (:reviewer-url opts) target
                           (#{"reject" "reverse"} action))
                  (reviews/dive-fields (:reviewer-url opts) target))
        history (when current
                  (reviews/dive-decision-history (:reviewer-url opts) target))
        replay (some #(when (= (:id human) (:id %)) %) history)
        event-id (or (get-in replay [:request :event-id])
                     (when current (get-in current [decision-type :decision-id])))
        active-event (when event-id
                       (first (filter #(= event-id (:id %)) history)))
        proposed (case action
                   "approve" (get-in owner [:proposal :proposed :value])
                   "correct" (get-in owner [:correction :value])
                   nil)]
    (if-not (and (#{"approve" "correct" "reject" "reverse"} action)
                 (#{:category :representation} decision-type)
                 (or (= decision-type (:family decision))
                     (= :category-representation (:family decision)))
                 (or (#{"reject" "reverse"} action)
                     (= decision-type (:action view)))
                 (or (not (#{"approve" "correct"} action))
                     (if (= decision-type :category)
                       (and (vector? proposed) (seq proposed)
                            (every? string? proposed))
                       (and (map? proposed) (keyword? (:kind proposed))
                            (string? (:code proposed)))))
                 (:reviewer-url opts) (map? target) (nat-int? base-revision)
                 (= binding (get-in owner [:proposal :canonical_binding]))
                 (or (not (#{"reject" "reverse"} action))
                     (and (= base-revision (:revision current))
                          (string? event-id)
                          (= id (or (:decision-id active-event)
                                    (get-in active-event
                                            [:request :binding :decision_id]))))))
      (unresolved :unsupported-or-stale-owner-field-decision)
      {:status (if (#{"reject" "reverse"} action) :reversed :materialized)
       :event (reviews/import-owner-dive-field!
               (:reviewer-url opts)
               {:id (:id human) :owner-event owner :binding binding
                :target target :decision-type decision-type
                :base-revision base-revision :proposed proposed
                :event-id event-id})})))

(defn- route-field! [flow-ledger decision view opts]
  (let [{:keys [target decision-type dictionary base-revision]} (field-target opts decision)
        request {:target target :decision-id (:id decision) :decision-type decision-type
                 :dictionary dictionary :policy (:policy opts) :base-revision base-revision}]
    (cond
      (and (= :human (:origin view))
           (:remote-event (last (filter #(and (= :human (:origin %))
                                              (= (:id decision) (:decision-id %)))
                                        (:events flow-ledger)))))
      (route-imported-field! flow-ledger decision view opts)

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
    (if (and (= :human (:origin view)) (:remote-event
                                        (last (filter #(and (= :human (:origin %))
                                                            (= (:id decision) (:decision-id %)))
                                                      (:events flow-ledger)))))
      [attempt-ledger (route-imported-attempt! flow-ledger decision view opts)]
      (if attempt-ledger
        (route-attempt attempt-ledger flow-ledger decision view decisions opts)
        [attempt-ledger (unresolved :missing-attempt-ledger)]))
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
                                 retained-answers deterministic-results attempt-ledger
                                 imported-only?]
                          :as opts}]
  (when-not (and (vector? decisions) (map? config) (map? policy)
                 (fn? persist-flow!)
                 (or (:attempt-url opts)
                     (not-any? #(#{:same-attempt :source-revision} (:family %)) decisions)
                     (fn? persist-attempt!)))
    (throw (ex-info "Versioned decisions, config, policy and private persistence required" {})))
  (let [flow-ledger (if imported-only? flow-ledger
                        (flow/run! flow-ledger decisions
                                   {:config config :policy policy :execute! execute!
                                    :provider-budget-path (:provider-budget-path opts)
                                    :provider-pricing (:provider-pricing opts)
                                    :checkpoint! (fn [pending]
                                                   (persist-flow! pending)
                                                   (when checkpoint! (checkpoint! pending)))
                                    :retained-answers retained-answers
                                    :deterministic-results deterministic-results}))
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
                                   (or (correction-statuses (:status view))
                                       (and (= :rejected (:status view))
                                            (:remote-event
                                             (last (filter #(and (= :human (:origin %))
                                                                 (= id (:decision-id %)))
                                                           (:events flow-ledger))))))))
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

(declare live-target-revisions)

(defn run-imported!
  "Verify a signed owner feed, persist and route each event in order.
   Unroutable families remain explicit for later recovery."
  [ledger decisions envelope {:keys [persist-flow!] :as opts}]
  (when-not (fn? persist-flow!)
    (throw (ex-info "Private flow persistence required" {})))
  (let [events (:events (authenticated-feed envelope (:import-token opts)))
        ids (if (seq events) (mapv :id events) [nil])]
    (reduce (fn [{:keys [flow-ledger]} event-id]
              (let [imported (import-remote-review-events
                              flow-ledger decisions envelope
                              (assoc opts :through-event-id event-id))
                    _ (persist-flow! imported)
                    decision-id (some #(when (= event-id (:id %)) (:decision-id %))
                                      (:events imported))
                    decision (some #(when (= decision-id (:id %)) %) decisions)
                    route-opts (if decision
                                 (live-target-revisions opts decision event-id)
                                 opts)
                    result (run! imported decisions
                                 (assoc route-opts :imported-only? true))
                    status (get-in result [:results decision-id :status])]
                (if (and event-id (not (delivered-statuses status)))
                  (reduced (assoc result :blocked-event-id event-id))
                  result)))
            {:flow-ledger ledger} ids)))

(defn- live-target-revisions [opts decision event-id]
  (let [id (:id decision)]
    (case (:family decision)
      :identity
      (if-let [url (:reviewer-url opts)]
        (let [existing (when event-id
                         (some #(when (= event-id (:id %)) %)
                               (identity/private-history url)))
              binding (get-in opts [:current-bindings id])
              stored-revision (when (and (= :human (:actor-kind existing))
                                         (= binding (:owner-binding existing))
                                         (= (parse-long (subs event-id (count "owner-store:")))
                                            (:owner-event-revision existing)))
                                (get-in existing [:request :base-revision]))]
          (assoc-in opts [:identity-revisions id]
                    (or stored-revision (:revision (identity/private-projection url)))))
        opts)

      (:same-attempt :source-revision)
      (if-let [url (:attempt-url opts)]
        (assoc-in opts [:attempt-revisions id]
                  (count (:events (attempt-store/private-ledger url))))
        opts)

      (:category :representation :category-representation)
      (if-let [{:keys [target] :as field} (field-target opts decision)]
        (if (and (:reviewer-url opts) target)
          (assoc-in opts [:field-targets id]
                    (assoc field :base-revision
                           (:revision (reviews/dive-fields (:reviewer-url opts) target))))
          opts)
        opts)

      opts)))

(defn deliver-owner-event!
  "Complete one durable target of the owner event outbox. Flow delivery fetches
   and verifies the signed feed. PostgreSQL delivery uses the persisted event and
   returns a receipt only after its canonical projection succeeds."
  [target event-id decisions {:keys [flow-path base-url expected-event] :as opts}]
  (when-not (and (#{:flow-ledger :postgresql} target)
                 (string? event-id) (re-matches #"owner-store:[1-9][0-9]*" event-id)
                 flow-path (vector? decisions))
    (throw (ex-info "Invalid owner event delivery request" {})))
  (let [revision (parse-long (subs event-id (count "owner-store:")))
        ledger (flow/load-ledger! flow-path)
        existing (some #(when (= event-id (:id %)) %) (:events ledger))]
    (when (and expected-event existing
               (not= expected-event (:remote-event existing)))
      (throw (ex-info "Owner outbox event differs from durable flow event"
                      {:event-id event-id})))
    (case target
      :flow-ledger
      (do
        (when-not existing
          (let [envelope (fetch-owner-review-events base-url (dec revision) opts)
                imported (import-remote-review-events
                          ledger decisions envelope
                          (assoc opts :through-event-id event-id))
                recorded (some #(when (= event-id (:id %)) %) (:events imported))]
            (when (and expected-event
                       (not= expected-event (:remote-event recorded)))
              (throw (ex-info "Owner outbox event differs from signed feed"
                              {:event-id event-id})))
            (flow/save-ledger! flow-path imported)))
        (str "flow-ledger:" event-id))

      :postgresql
      (let [_ (when-not existing
                (throw (ex-info "Owner event has no durable flow checkpoint"
                                {:event-id event-id})))
            index (.indexOf ^java.util.List (:events ledger) existing)
            prefix (assoc ledger :events (subvec (:events ledger) 0 (inc index)))
            decision (some #(when (= (:decision-id existing) (:id %)) %) decisions)
            _ (when-not decision
                (throw (ex-info "Owner event decision unavailable"
                                {:event-id event-id})))
            live-opts (live-target-revisions opts decision event-id)
            result (run! prefix decisions
                         (assoc live-opts :imported-only? true
                                :persist-flow! (fn [_] (flow/save-ledger! flow-path ledger))))
            status (get-in result [:results (:decision-id existing) :status])]
        (when-not (delivered-statuses status)
          (throw (ex-info "Owner event canonical projection incomplete"
                          {:event-id event-id :status status
                           :result (get-in result [:results (:decision-id existing)])})))
        (str "postgresql:" event-id)))))
