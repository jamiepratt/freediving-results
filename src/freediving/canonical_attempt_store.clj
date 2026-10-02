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
      {:state state :ledger ledger})))
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

(defn persist!
  "CAS append events or rebase retained evidence, then update private counts in one transaction.
   The first write must be an empty event ledger. Retry of the exact ledger is idempotent."
  [url candidate]
  (transaction
   url
   (fn [connection]
     (let [candidate (normalize candidate)
           current (read-current connection)
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
                     (or (:ledger (read-current connection))
                         (fail! "Canonical attempt store is uninitialized")))))
(defn private-projection
  "Fail closed if the materialized private count or group view is stale or altered."
  [url]
  (transaction url (fn [connection]
                     (let [{:keys [state ledger]} (or (read-current connection)
                                                      (fail! "Canonical attempt store is uninitialized"))
                           projection (relationships/project-attempts ledger)]
                       (when-not (and (= (:evidence_digest state) (digest (evidence ledger)))
                                      (= projection (edn/read-string (:projection_edn state))))
                         (fail! "Canonical attempt view requires rebuild"))
                       projection))))
(defn rebuild!
  "Recover a partial/stale private projection from immutable evidence and events."
  [url]
  (transaction url (fn [connection]
                     (let [{:keys [state ledger]} (or (read-current connection)
                                                      (fail! "Canonical attempt store is uninitialized"))]
                       (write-view! connection ledger (:evidence_digest state))))))
