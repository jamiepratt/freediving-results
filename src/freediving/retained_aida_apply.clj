(ns freediving.retained-aida-apply
  "Apply a verified private AIDA cohort through the canonical source identity ledger."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [freediving.athlete-identity :as identity])
  (:import [java.nio.file Files Paths StandardCopyOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.sql DriverManager]
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

(defn canonical-history
  "Export only the canonical event facts needed to plan a private incremental cohort."
  [reviewer-url expected-snapshot]
  (when-not (re-matches #"[0-9a-f]{64}" (or expected-snapshot ""))
    (fail! "Expected source snapshot SHA256 required"))
  (let [active (with-open [connection (DriverManager/getConnection reviewer-url)
                           statement (.prepareStatement connection
                                                        "SELECT snapshot_sha256 FROM freediving.source_identity_snapshot WHERE singleton=true")
                           result (.executeQuery statement)]
                 (when (.next result) (.getString result 1)))
        events (identity/private-history reviewer-url)]
    (when-not (or (= active expected-snapshot)
                  (and (nil? active) (empty? events)))
      (fail! "Canonical source snapshot differs from expected"))
    {:schema "retained-aida-canonical-history/v1"
     :revision (count events)
     :snapshot_sha256 expected-snapshot
     :events (mapv (fn [event]
                     (cond-> {:id (:id event) :action (name (:action event))
                              :actor_kind (name (:actor-kind event))}
                       (:pair event) (assoc :pair (:pair event))
                       (:event-id event) (assoc :event_id (:event-id event))))
                   events)}))

(defn canonical-readback
  "Verify a retained cohort and applied receipt against one current canonical read."
  [reviewer-url cohort applied]
  (let [state (identity/private-canonical-readback reviewer-url)
        registration (field cohort :registration)
        expected-snapshot (field registration :snapshot_sha256)
        rows (mapv source-row (field registration :rows))
        current-rows (into {} (map (juxt :observation-id identity) (:source-rows state)))
        history (:events state)
        binding (field cohort :binding)
        base (field binding :identity_revision)
        prior-ids (field binding :history_event_ids)
        proposed (field cohort :events)
        correction (or (last (keep #(when (= :human (:actor-kind %)) (:revision %))
                                   (map-indexed (fn [i event] (assoc event :revision (inc i))) history))) 0)
        projection (:projection state)]
    (when-not (= expected-snapshot (:snapshot-sha256 state))
      (fail! "Canonical source snapshot mismatch"))
    (when-not (and (= "retained-aida-cohort/v1" (field cohort :schema))
                   (= "retained-aida-canonical-receipt/v1" (field applied :schema))
                   (integer? base) (<= 0 base)
                   (vector? prior-ids) (= base (count prior-ids))
                   (vector? proposed)
                   (= (:revision projection) (count history))
                   (= (count history) (+ base (count proposed)))
                   (= (mapv :id (subvec history 0 base)) prior-ids)
                   (= (field applied :identity_revision) (:revision projection))
                   (= (field applied :human_correction_revision) correction)
                   (= (field applied :accepted_group_count) (:accepted-group-count projection))
                   (= (field applied :human_negative_pair_count) (count (:negative-pairs projection)))
                   (= 0 (field applied :provider_calls))
                   (zero? (:non-source-row-count state))
                   (= (set (map :observation-id rows)) (set (keys current-rows)))
                   (every? #(= % (current-rows (:observation-id %))) rows)
                   (every? #(= expected-snapshot (get-in % [:source-observation-ref :snapshot_sha256]))
                           (:source-rows state))
                   (every? true?
                           (map-indexed
                            (fn [index event]
                              (let [actual (nth history (+ base index))]
                                (and (= (field event :id) (:id actual))
                                     (= (source-event event (+ base index)) (:request actual)))))
                            proposed)))
      (fail! "Canonical readback stale or cohort mismatch"))
    (assoc state :schema "retained-aida-canonical-readback/v1"
           :human-correction-revision correction)))

(defn- bound-history [reviewer-url binding events]
  (let [revision (field binding :identity_revision)
        ids (field binding :history_event_ids)
        history (identity/private-history reviewer-url)
        current-ids (mapv :id history)
        proposed-ids (mapv #(field % :id) events)
        suffix (subvec current-ids (min (count current-ids) (if (integer? revision) revision 0)))]
    (when-not (and (integer? revision) (<= 0 revision)
                   (vector? ids) (= revision (count ids))
                   (= ids (subvec current-ids 0 (min revision (count current-ids))))
                   (<= revision (count current-ids))
                   (<= (count suffix) (count proposed-ids))
                   (= suffix (subvec proposed-ids 0 (count suffix)))
                   (empty? (set/intersection (set ids) (set proposed-ids))))
      (fail! "Stale retained AIDA canonical history"))
    (doseq [[index prior] (map-indexed vector (subvec history revision))]
      (let [request (source-event (nth events index) (+ revision index))]
        (when-not (= request (:request prior))
          (fail! "Conflicting retained AIDA event replay"))))
    (count suffix)))

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
        events (field cohort :events)
        binding (field cohort :binding)
        bound? (or (some? (field binding :identity_revision))
                   (some? (field binding :history_event_ids)))]
    (when-not (and (seq rows) (vector? events)
                   (= (set (map :observation-id rows)) (set (keys refs)))
                   (= (count events) (count (set (map #(field % :id) events)))))
      (fail! "Incomplete retained AIDA registration or duplicate events"))
    (when bound? (bound-history reviewer-url binding events))
    (identity/register-source-observations! reviewer-url registration)
    (doseq [[index event] (map-indexed vector events)]
      (when bound?
        (let [applied (bound-history reviewer-url binding events)]
          (when-not (<= index applied)
            (fail! "Stale retained AIDA canonical history"))))
      (let [history (identity/private-history reviewer-url)
            existing (some #(when (= (field event :id) (:id %)) %) history)
            request (source-event event (count history))]
        (if existing
          (when-not (= (:request existing)
                       (assoc request :base-revision (get-in existing [:request :base-revision])))
            (fail! "Conflicting retained AIDA event replay"))
          (identity/record-source-event! app-url request))))
    (when bound?
      (let [applied (bound-history reviewer-url binding events)]
        (when-not (= applied (count events))
          (fail! "Incomplete retained AIDA canonical replay"))))
    (let [result (receipt reviewer-url (identity/private-canonical-view reviewer-url))]
      (when (and bound?
                 (not= (:identity-revision result)
                       (+ (field binding :identity_revision) (count events))))
        (fail! "Stale retained AIDA canonical history"))
      result)))

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
  (let [target (Paths/get path (make-array String 0))
        parent (.getParent (.toAbsolutePath target))]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (let [temporary (Files/createTempFile
                     parent ".retained-aida-" ".json"
                     (into-array java.nio.file.attribute.FileAttribute
                                 [(PosixFilePermissions/asFileAttribute
                                   (PosixFilePermissions/fromString "rw-------"))]))]
      (try
        (spit (.toFile temporary) (str (json/write-str value) "\n"))
        (Files/move temporary target
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (finally (Files/deleteIfExists temporary))))
    value))

(defn -main [& args]
  (let [reviewer (System/getenv "FREEDIVING_REVIEW_URL")
        app (System/getenv "FREEDIVING_APP_URL")]
    (when-not (seq reviewer)
      (fail! "FREEDIVING_REVIEW_URL required"))
    (when (and (= "apply" (first args)) (not (seq app)))
      (fail! "FREEDIVING_APP_URL required for apply"))
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
      "history" (let [[_ expected path] args]
                  (when-not (= 3 (count args)) (fail! "Usage: history EXPECTED_SNAPSHOT_SHA HISTORY_JSON"))
                  (write-receipt! path (canonical-history reviewer expected)))
      "readback" (let [[_ cohort-path expected receipt-path output-path] args]
                   (when-not (and (= 5 (count args)) (re-matches #"[0-9a-f]{64}" expected)
                                  (= expected (sha256-file cohort-path)))
                     (fail! "Retained AIDA cohort hash mismatch"))
                   (let [applied (json/read-str (slurp receipt-path))]
                     (when-not (= expected (field applied :cohort_sha256))
                       (fail! "Canonical receipt cohort mismatch"))
                     (write-receipt! output-path
                                     (canonical-readback reviewer
                                                         (json/read-str (slurp cohort-path))
                                                         applied))))
      (fail! "Usage: apply COHORT_JSON SHA256 RECEIPT_JSON | reverse EVENT_ID REASON RECEIPT_JSON | history EXPECTED_SNAPSHOT_SHA HISTORY_JSON | readback COHORT_JSON SHA256 RECEIPT_JSON OUTPUT_JSON"))))
