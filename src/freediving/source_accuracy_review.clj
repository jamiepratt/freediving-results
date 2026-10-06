(ns freediving.source-accuracy-review
  "Authenticated exact source visual accuracy, independent of publication."
  (:require [clojure.string :as str]
            [freediving.aida-html :as html]
            [freediving.private-sporting-proofs :as proofs]
            [freediving.reviews :as reviews])
  (:import [java.sql Connection DriverManager]))
(defn- need! [ok message]
  (when-not ok (throw (ex-info message {:status (if (re-find #"Stale|replay" message) 409 400)}))))
(defn- execute-command! [{:keys [jdbc_url database request replay_only] :as config}]
  (need! (and (= #{:jdbc_url :database :request} (set (keys (dissoc config :replay_only))))
              (or (not (contains? config :replay_only)) (boolean? replay_only))) "Invalid source accuracy command")
  (let [{:keys [id action reference coordinates expected_source_binding_sha256 base_revision event_id actor reason source_visual_accuracy]} request]
    (need! (and (= #{:id :action :reference :coordinates :expected_source_binding_sha256 :base_revision :event_id :actor :reason :source_visual_accuracy} (set (keys request)))
                (#{"accept" "revoke"} action) (true? source_visual_accuracy)
                (every? #(and (string? %) (not (str/blank? %))) [id actor reason])
                (nat-int? base_revision)
                (or (and (= "accept" action) (nil? event_id)) (and (= "revoke" action) (string? event_id) (seq event_id)))
                (string? expected_source_binding_sha256) (re-matches #"[0-9a-f]{64}" expected_source_binding_sha256))
           "Invalid exact source accuracy request")
    (with-open [c (DriverManager/getConnection jdbc_url)]
      ;; Acquire before SERIALIZABLE creates any snapshot, including after waiting.
      (with-open [s (.prepareStatement c "SELECT pg_advisory_lock(781246935)")]
        (.execute s))
      (.setTransactionIsolation c Connection/TRANSACTION_SERIALIZABLE)
      (.setAutoCommit c false)
      (try
        (proofs/source-review-capability! c)
        (with-open [s (.prepareStatement c "SELECT current_database()") r (.executeQuery s)]
          (.next r) (need! (= database (.getString r 1)) "Source accuracy database changed"))
        (let [row {:reference reference :coordinates coordinates}
              {:keys [artifact context]} (proofs/source-review-target c row)
              evidence (proofs/review-evidence row artifact context)
              receipt (cond-> {:id id :job-id (:job-id reference) :ordinal (:ordinal reference) :base-revision base_revision
                               :evidence evidence :actor actor :reason reason :attestations {:source-visual-accuracy true}
                               :source-binding-sha256 expected_source_binding_sha256
                               :owner-response {:type :authenticated-owner-http :request-id id :owner actor}}
                        (= "revoke" action) (assoc :event-id event_id))
              receipt (assoc receipt :owner-receipt-sha256 (html/digest receipt))
              replayed (atom false)
              record (binding [reviews/*source-accuracy-guard*
                               (fn [connection replay?]
                                 (reset! replayed replay?)
                                 (need! (or (not replay_only) replay?) "Unknown receipt replay")
                                 (proofs/source-review-capability! connection)
                                 (proofs/source-review-target connection row)
                                 (when-not replay?
                                   (need! (= expected_source_binding_sha256 (proofs/source-binding connection)) "Stale source authority binding")))]
                       ((if (= :html (:source-kind evidence))
                          (if (= "accept" action) reviews/accept-extraction! reviews/revoke-extraction!)
                          (if (= "accept" action) reviews/accept-pdf-extraction! reviews/revoke-pdf-extraction!)) c receipt))
              binding (proofs/source-binding c)]
          (.commit c)
          {:schema "private-sporting-proofs/v1" :database database :binding_sha256 binding :replayed @replayed
           :receipt (select-keys record [:id :job-id :ordinal :revision :action :event-id :owner-receipt-sha256])})
        (catch Exception e (.rollback c) (throw e))
        (finally
          (.setAutoCommit c true)
          (with-open [s (.prepareStatement c "SELECT pg_advisory_unlock(781246935)")]
            (.execute s)))))))

(defn execute! [config]
  (try (execute-command! config)
       (catch Exception e
         (let [status (or (:status (ex-data e))
                          (when (instance? java.sql.SQLException e)
                            (if (#{"40001" "40P01" "23505"} (.getSQLState ^java.sql.SQLException e)) 409 503))
                          (cond
                            (re-find #"Stale|Conflicting|already accepted|Only the active" (or (.getMessage e) "")) 409
                            (re-find #"capability|database changed" (or (.getMessage e) "")) 503
                            (instance? clojure.lang.ExceptionInfo e) 400
                            :else 503))]
           (throw (ex-info "Source accuracy review unavailable" {:status status} e))))))
