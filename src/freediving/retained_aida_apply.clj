(ns freediving.retained-aida-apply
  "Apply a verified private AIDA cohort through the canonical source identity ledger."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.athlete-identity :as identity])
  (:import [java.nio.file Files Paths]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- fail! [message]
  (throw (ex-info message {})))

(defn- keywordize [value]
  (cond
    (map? value) (into {} (map (fn [[key item]] [(keyword key) (keywordize item)]) value))
    (vector? value) (mapv keywordize value)
    :else value))

(defn- field [value snake]
  (or (get value snake)
      (get value (keyword (.replace (name snake) "_" "-")))
      (get value (name snake))
      (get value (.replace (name snake) "_" "-"))))

(defn- sha256-file [path]
  (with-open [input (io/input-stream path)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 65536)]
      (loop []
        (let [n (.read input buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur))))
      (.formatHex (HexFormat/of) (.digest digest)))))

(defn- source-row [row]
  {:observation-id (field row :observation_id)
   :source-name (field row :source_name)
   :parse-status (keyword (field row :parse_status))
   :citation (keywordize (field row :citation))
   :source-observation-ref (keywordize (field row :source_observation_ref))
   :publisher-scope (field row :publisher_scope)
   :publisher-athlete-id (field row :publisher_athlete_id)
   :publisher-id-kind (keyword (field row :publisher_id_kind))})

(defn- source-event [event revision]
  (let [binding (field event :source_binding)]
    {:id (field event :id) :action (keyword (field event :action))
     :actor-kind (keyword (field event :actor_kind))
     :pair (field event :pair) :rule-version (field event :rule_version)
     :base-revision revision
     :source-binding {:snapshot-sha256 (field binding :snapshot_sha256)
                      :refs (into {} (map (fn [[id ref]] [id (keywordize ref)])
                                          (field binding :refs)))}}))

(defn- receipt [reviewer-url projection]
  {:schema "retained-aida-canonical-receipt/v1"
   :identity-revision (:revision projection)
   :human-correction-revision (or (last (keep #(when (= :human (:actor-kind %)) (:revision %))
                                              (identity/private-history reviewer-url))) 0)
   :provisional-record-count (:provisional-record-count projection)
   :accepted-group-count (:accepted-group-count projection)
   :human-negative-pair-count (count (:negative-pairs projection))
   :global-distinctness "unknown"
   :provider-calls 0})

(defn apply-cohort!
  "Register exact refs and resume deterministic edges. Existing identical events are unchanged."
  [reviewer-url app-url cohort]
  (when-not (= "retained-aida-cohort/v1" (field cohort :schema))
    (fail! "Unknown retained AIDA cohort schema"))
  (let [registration (field cohort :registration)
        rows (mapv source-row (field registration :rows))
        refs (into {} (map (fn [[id ref]] [id (keywordize ref)])
                           (field registration :verified_refs)))
        registration {:snapshot-sha256 (field registration :snapshot_sha256)
                      :rows rows :verified-refs refs}
        events (field cohort :events)]
    (when-not (and (seq rows) (vector? events)
                   (= (set (map :observation-id rows)) (set (keys refs)))
                   (= (count events) (count (set (map #(field % :id) events)))))
      (fail! "Incomplete retained AIDA registration or duplicate events"))
    (identity/register-source-observations! reviewer-url registration)
    (doseq [event events]
      (let [history (identity/private-history reviewer-url)
            existing (some #(when (= (field event :id) (:id %)) %) history)
            request (source-event event (count history))]
        (if existing
          (when-not (= (:request existing)
                       (assoc request :base-revision (get-in existing [:request :base-revision])))
            (fail! "Conflicting retained AIDA event replay"))
          (identity/record-source-event! app-url request))))
    (receipt reviewer-url (identity/private-canonical-view reviewer-url))))

(defn reverse-source-event!
  "Persist an explicit human reversal; its negative pair blocks later automatic relinks."
  [reviewer-url event-id reason]
  (let [history (identity/private-history reviewer-url)
        prior (some #(when (= event-id (:id %)) %) history)]
    (when-not (and prior (= :accept (:action prior))
                   (= :automatic (:actor-kind prior))
                   (string? reason) (not (str/blank? reason)))
      (fail! "Active automatic source event and human reason required"))
    (let [reverse-id (str "retained-human-reverse:" event-id)
          existing (some #(when (= reverse-id (:id %)) %) history)]
      (when-not existing
        (identity/record-source-event!
         reviewer-url {:id reverse-id :action :reverse :actor-kind :human
                       :event-id event-id :base-revision (count history)
                       :reason reason :source-binding (get-in prior [:request :source-binding])}))
      (receipt reviewer-url (identity/private-canonical-view reviewer-url)))))

(defn- write-receipt! [path value]
  (let [target (Paths/get path (make-array String 0))]
    (when-let [parent (.getParent target)] (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
    (spit path (str (json/write-str value) "\n"))
    value))

(defn -main [& args]
  (let [reviewer (System/getenv "FREEDIVING_REVIEW_URL")
        app (System/getenv "FREEDIVING_APP_URL")]
    (when-not (and (seq reviewer) (seq app))
      (fail! "FREEDIVING_REVIEW_URL and FREEDIVING_APP_URL required"))
    (case (first args)
      "apply" (let [[_ path expected receipt-path] args]
                (when-not (and (= 4 (count args)) (re-matches #"[0-9a-f]{64}" expected)
                               (= expected (sha256-file path)))
                  (fail! "Retained AIDA cohort hash mismatch"))
                (write-receipt! receipt-path
                                (assoc (apply-cohort! reviewer app (json/read-str (slurp path)))
                                       :cohort-sha256 expected)))
      "reverse" (let [[_ event-id reason receipt-path] args]
                  (when-not (= 4 (count args)) (fail! "Usage: reverse EVENT_ID REASON RECEIPT_JSON"))
                  (write-receipt! receipt-path (reverse-source-event! reviewer event-id reason)))
      (fail! "Usage: apply COHORT_JSON SHA256 RECEIPT_JSON | reverse EVENT_ID REASON RECEIPT_JSON"))))
