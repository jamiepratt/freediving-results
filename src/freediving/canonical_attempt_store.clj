(ns freediving.canonical-attempt-store
  "Private PostgreSQL authority for cited attempt evidence, events and derived counts."
  (:require [clojure.edn :as edn]
            [freediving.source-relationships :as relationships])
  (:import [java.sql Connection DriverManager]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- fail! [message] (throw (ex-info message {})))
(defn- query [^Connection connection sql & args]
  (with-open [statement (.prepareStatement connection sql)]
    (doseq [[index value] (map-indexed vector args)]
      (.setObject statement (inc index) value))
    (with-open [result (.executeQuery statement)]
      (let [metadata (.getMetaData result)]
        (loop [rows []]
          (if (.next result)
            (recur (conj rows (into {} (for [index (range 1 (inc (.getColumnCount metadata)))]
                                         [(keyword (.getColumnLabel metadata index))
                                          (.getObject result index)]))))
            rows))))))
(defn- execute! [^Connection connection sql & args]
  (with-open [statement (.prepareStatement connection sql)]
    (doseq [[index value] (map-indexed vector args)]
      (.setObject statement (inc index) value))
    (.executeUpdate statement)))
(defn- evidence [ledger]
  {:sources (vec (sort-by :id (vals (:sources ledger))))
   :positions (vec (sort-by :id (vals (:positions ledger))))
   :observation-versions (vec (sort-by :id (vals (:observation-versions ledger))))})
(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value) "UTF-8"))))
(defn- normalize [ledger]
  (if (empty? (:invalidated-events ledger))
    (dissoc ledger :invalidated-events)
    ledger))
(defn- read-current [^Connection connection]
  (when-let [state (first (query connection
                                 "SELECT evidence_digest,revision,projection_edn FROM freediving.canonical_attempt_state WHERE singleton=true"))]
    (let [stored (first (query connection
                               "SELECT body_edn FROM freediving.canonical_attempt_evidence WHERE digest=?"
                               (:evidence_digest state)))
          events (mapv (comp edn/read-string :body_edn)
                       (query connection "SELECT body_edn FROM freediving.canonical_attempt_events ORDER BY revision"))
          base (relationships/empty-attempt-ledger (edn/read-string (:body_edn stored)))
          ledger (normalize (relationships/rebase-attempt-ledger
                             (assoc base :events events) (evidence base)))]
      (when-not (= (:revision state) (count events))
        (fail! "Canonical attempt event revision mismatch"))
      (when-not (= (:evidence_digest state) (digest (evidence ledger)))
        (fail! "Canonical attempt evidence digest mismatch"))
      {:state state :ledger ledger})))
(defn- check-view! [{:keys [state ledger] :as current}]
  (when-not (= (relationships/project-attempts ledger)
               (edn/read-string (:projection_edn state)))
    (fail! "Canonical attempt view requires rebuild"))
  current)
(defn- write-view! [^Connection connection ledger evidence-digest]
  (let [projection (relationships/project-attempts ledger)]
    (execute! connection
              (str "INSERT INTO freediving.canonical_attempt_state"
                   "(singleton,evidence_digest,revision,projection_edn) VALUES(true,?,?,?)"
                   " ON CONFLICT(singleton) DO UPDATE SET"
                   " evidence_digest=EXCLUDED.evidence_digest,revision=EXCLUDED.revision,"
                   " projection_edn=EXCLUDED.projection_edn,rebuilt_at=clock_timestamp()")
              evidence-digest (:revision projection) (pr-str projection))
    projection))
(defn- transaction [url f]
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setTransactionIsolation connection Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (query connection "SELECT pg_advisory_xact_lock(781246920)")
      (let [result (f connection)] (.commit connection) result)
      (catch Exception error (.rollback connection) (throw error)))))

(defn- current-owner-binding? [ledger {:keys [event binding subject-snapshot]}]
  (let [{:keys [type pair]} event
        entries (:evidence_bindings binding)
        versions (:observation-versions ledger)
        source-of (fn [version]
                    (get-in ledger [:positions (:position-id version) :source-id]))]
    (and (= subject-snapshot (relationships/attempt-subjects ledger type pair))
         (vector? entries) (= 2 (count entries))
         (= (set pair) (set (map :evidence_id entries)))
         (every?
          (fn [{:keys [evidence_id observation_revision]}]
            (and (map? observation_revision)
                 (some (fn [version]
                         (and (if (= type :same-attempt)
                                (= evidence_id (:id version))
                                (= evidence_id (source-of version)))
                              (= observation_revision (:observation-revision version))
                              (= (:source_sha256 observation_revision)
                                 (get-in ledger [:sources (source-of version) :sha256]))))
                       (vals versions))))
          entries))))

(defn- positive-owner-option [{:keys [type pair evidence]}]
  (case type
    :same-attempt "same_attempt"
    :source-revision (cond
                       (= [(:predecessor evidence) (:successor evidence)] pair)
                       "right_revises_left"
                       (= [(:successor evidence) (:predecessor evidence)] pair)
                       "left_revises_right")
    nil))

(defn- owner-canonical-action [owner-event event]
  (let [action (:action owner-event)
        choice (if (= action "correct")
                 (get-in owner-event [:correction :action])
                 (get-in owner-event [:proposal :selected_option]))]
    (case action
      "approve" (when (= choice (positive-owner-option event)) :accept)
      "correct" (cond
                  (= choice (positive-owner-option event)) :accept
                  (and (= :same-attempt (:type event))
                       (= choice "distinct_attempts")) :reverse
                  (and (= :source-revision (:type event))
                       (#{"same_source" "unrelated"} choice)) :reverse)
      "reject" :reverse
      "reverse" :reverse
      nil)))

(defn record-owner-decision!
  "CAS import one already authenticated owner approval or reversal, then project
   in the same PostgreSQL transaction. Unsupported owner actions fail closed.
   Exact retries return the existing projection; stale writes leave no event."
  [url {:keys [event owner-event binding expected-revision] :as request}]
  (transaction
   url
   (fn [connection]
     (let [{:keys [state ledger]} (or (some-> (read-current connection) check-view!)
                                      (fail! "Canonical attempt store is uninitialized"))
           prior (some #(when (= (:id event) (:id %)) %) (:events ledger))
           canonical-action (owner-canonical-action owner-event event)]
       (when-not (and (map? event) (map? owner-event) (map? binding)
                      (= (:id event) (:id owner-event))
                      (= (:id event) (str "owner-store:" (:store_revision owner-event)))
                      (= (:decision_id owner-event) (:decision_id binding))
                      (= binding (get-in owner-event [:proposal :canonical_binding]))
                      (= canonical-action (:action event))
                      (#{:same-attempt :source-revision} (:type event))
                      (nat-int? expected-revision)
                      (current-owner-binding? ledger request))
         (fail! "Owner attempt event lacks exact current signed binding"))
       (if prior
         (do
           (when-not (= (dissoc request :expected-revision)
                        (dissoc (:owner-request prior) :expected-revision))
             (fail! "Conflicting owner attempt event replay"))
           (relationships/project-attempts ledger))
         (do
           (when-not (= expected-revision (count (:events ledger)))
             (fail! "Stale owner attempt revision"))
           (when (and (= :reverse canonical-action)
                      (not= (:decision_id binding)
                            (get-in (some #(when (= (:event-id event) (:id %)) %)
                                          (:events ledger)) [:owner-request :binding :decision_id])))
             (fail! "Owner reversal targets another decision"))
           (let [updated (relationships/append-attempt-event
                          ledger (assoc event :owner-request request))
                 stored (last (:events updated))]
             (execute! connection
                       "INSERT INTO freediving.canonical_attempt_events(revision,id,body_edn) VALUES(?,?,?)"
                       (count (:events updated)) (:id stored) (pr-str stored))
             (write-view! connection updated (:evidence_digest state)))))))))

(defn persist!
  "CAS append events or rebase retained evidence, then update private counts in one transaction.
   The first write must be an empty event ledger. Retry of the exact ledger is idempotent."
  [url candidate]
  (transaction
   url
   (fn [connection]
     (let [candidate (normalize candidate)
           current (some-> (read-current connection) check-view!)
           previous (:ledger current)
           new-evidence (evidence candidate)
           evidence-digest (digest new-evidence)
           original (relationships/empty-attempt-ledger new-evidence)
           previous-events (vec (:events previous))
           candidate-events (vec (:events candidate))]
       (when-not (= relationships/attempt-rule-version (:version candidate))
         (fail! "Unsupported canonical attempt ledger"))
       (cond
         (nil? current)
         (when-not (= candidate original)
           (fail! "Initialize canonical attempt store with cited evidence and no events"))

         (not= (evidence previous) new-evidence)
         (when-not (= candidate (normalize (relationships/rebase-attempt-ledger
                                            previous new-evidence)))
           (fail! "Evidence replacement must rebase the retained event history"))

         :else
         (let [expected (reduce relationships/append-attempt-event previous
                                (drop (count previous-events) candidate-events))]
           (when-not (and (= previous-events (subvec candidate-events 0
                                                     (min (count previous-events)
                                                          (count candidate-events))))
                          (= candidate expected))
             (fail! "Stale or conflicting canonical attempt ledger"))))
       (execute! connection
                 "INSERT INTO freediving.canonical_attempt_evidence(digest,body_edn) VALUES(?,?) ON CONFLICT(digest) DO NOTHING"
                 evidence-digest (pr-str new-evidence))
       (doseq [[index event] (map-indexed vector (drop (count previous-events) candidate-events))]
         (execute! connection
                   "INSERT INTO freediving.canonical_attempt_events(revision,id,body_edn) VALUES(?,?,?)"
                   (+ (count previous-events) index 1) (:id event) (pr-str event)))
       (write-view! connection candidate evidence-digest)))))

(defn private-ledger [url]
  (transaction url (fn [connection]
                     (or (:ledger (some-> (read-current connection) check-view!))
                         (fail! "Canonical attempt store is uninitialized")))))
(defn private-projection
  "Fail closed if the materialized private count or group view is stale or altered."
  [url]
  (transaction url (fn [connection]
                     (let [{:keys [ledger]} (or (some-> (read-current connection) check-view!)
                                                (fail! "Canonical attempt store is uninitialized"))
                           projection (relationships/project-attempts ledger)]
                       projection))))
(defn private-readback
  "Verify immutable evidence, replayed events and materialization in one read-only
   repeatable-read transaction. A SELECT-only capability needs no advisory lock."
  [url]
  (with-open [connection (DriverManager/getConnection url)]
    (.setAutoCommit connection false)
    (.setReadOnly connection true)
    (.setTransactionIsolation connection Connection/TRANSACTION_REPEATABLE_READ)
    (try
      (let [{:keys [state ledger]} (or (some-> (read-current connection) check-view!)
                                       (fail! "Canonical attempt store is uninitialized"))
            database (:database (first (query connection "SELECT current_database() AS database")))
            raw-events (query connection "SELECT revision,id,body_edn FROM freediving.canonical_attempt_events ORDER BY revision")
            raw-evidence (first (query connection "SELECT body_edn FROM freediving.canonical_attempt_evidence WHERE digest=?"
                                       (:evidence_digest state)))
            result {:database database :ledger ledger
                    :projection (relationships/project-attempts ledger)
                    :evidence-sha256 (digest {:evidence (:body_edn raw-evidence)
                                              :events raw-events :state state})}]
        (.commit connection) result)
      (catch Exception error (.rollback connection) (throw error)))))
(defn rebuild!
  "Recover a partial/stale private projection from immutable evidence and events."
  [url]
  (transaction url (fn [connection]
                     (let [{:keys [state ledger]} (or (read-current connection)
                                                      (fail! "Canonical attempt store is uninitialized"))]
                       (write-view! connection ledger (:evidence_digest state))))))
