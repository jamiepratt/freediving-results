(ns freediving.retained-aida-flow
  "Hash-pinned, owner-only source decision and flow export for a retained AIDA preflight."
  (:require [clojure.data.json :as json]
            [freediving.athlete-identity :as identity]
            [freediving.reconciliation-flow :as flow])
  (:import [java.nio.file Files Paths StandardCopyOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- require! [truth message]
  (when-not truth (throw (ex-info message {}))))

(defn- sha256 [bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn- keywordize [value]
  (cond
    (map? value) (into {} (map (fn [[key item]] [(keyword key) (keywordize item)]) value))
    (vector? value) (mapv keywordize value)
    :else value))

(defn- source-row [row]
  {:observation-id (:observation-id row)
   :source-name (:source-name row)
   :parse-status (keyword (:parse-status row))
   :citation (keywordize (:citation row))
   :source-observation-ref (keywordize (:source-observation-ref row))
   :publisher-scope (:publisher-scope row)
   :publisher-athlete-id (:publisher-athlete-id row)
   :publisher-id-kind (keyword (:publisher-id-kind row))})

(defn build-export [preflight input-sha]
  (let [rows (:source_rows preflight)
        replay (:canonical_replay preflight)
        intents (get-in preflight [:owner :candidate_intents])
        source (into {} (map (juxt :observation-id identity) rows))
        accepted (into {} (for [event replay :when (= "accept" (:action event))]
                            [(:id event) event]))
        reversed (set (for [event replay :when (= "reverse" (:action event))]
                        (:event-id event)))
        blocked-people (set (mapcat (fn [event]
                                      (map #(get-in source [% :publisher-athlete-id])
                                           (:pair (accepted (:event-id event)))))
                                    (filter #(= "reverse" (:action %)) replay)))
        ledger (identity/empty-ledger (mapv source-row rows))]
    (require! (and (= "retained-aida-promotion-preflight/v1" (:schema preflight))
                   (string? (:snapshot_sha256 preflight))
                   (vector? rows) (seq rows)
                   (= (count rows) (count source))
                   (vector? replay) (vector? intents)
                   (= (count intents) (get-in preflight [:counts :owner_candidate_intents]))
                   (= (count intents) (- (count accepted) (count reversed)))
                   (= 0 (:expected_production_revision preflight)))
              "Incomplete or stale retained preflight")
    (let [{:keys [decisions unresolved]}
          (reduce (fn [{:keys [decisions unresolved] :as state} intent]
                    (let [event (accepted (:source_event_id intent))
                          pair (:pair intent)
                          refs (into {} (map (fn [[id ref]] [(name id) ref])
                                             (get-in event [:source-binding :refs])))]
                      (require! (and event (not (reversed (:id event)))
                                     (= pair (:pair event))
                                     (= "pending" (:status intent))
                                     (= (:source_binding intent) (:source-binding event))
                                     (= (:snapshot_sha256 preflight)
                                        (get-in event [:source-binding :snapshot-sha256]))
                                     (= refs (into {} (map (fn [id]
                                                             [id (:source-observation-ref (source id))]) pair))))
                                "Active canonical intent differs from registered source")
                      (let [person (get-in source [(first pair) :publisher-athlete-id])]
                        (if (contains? blocked-people person)
                          (assoc state :unresolved (conj unresolved {:source_event_id (:id event)
                                                                     :reason "human-correction"}))
                          (try
                            (let [decision (identity/jev-decision ledger (first pair) (second pair))]
                              (assoc state :decisions (conj decisions decision)))
                            (catch clojure.lang.ExceptionInfo error
                              (assoc state :unresolved
                                     (conj unresolved {:source_event_id (:id event)
                                                       :reason (ex-message error)}))))))))
                  {:decisions [] :unresolved []} intents)
          config {:version "retained-aida-flow/1" :provider :jev :model "no-provider"}
          policy {:version "reconciliation-approval-v2" :thresholds {}}
          result (flow/run! (flow/empty-ledger) decisions
                            {:config config :policy policy
                             :execute! (fn [_] (throw (ex-info "Provider call forbidden" {})))})]
      (require! (= (count decisions) (count (:events result)))
                "Source flow did not cover every supported decision")
      {:schema "retained-aida-flow-export/v1"
       :preflight_sha256 input-sha
       :snapshot_sha256 (:snapshot_sha256 preflight)
       :counts {:active_intents (count intents) :supported (count decisions)
                :unresolved (count unresolved)}
       :decisions decisions :flow result :unresolved unresolved})))

(defn -main [preflight-path expected-sha output-path]
  (let [bytes (Files/readAllBytes (Paths/get preflight-path (make-array String 0)))
        actual (sha256 bytes)
        output (.toAbsolutePath (Paths/get output-path (make-array String 0)))
        repo (.toAbsolutePath (Paths/get "." (make-array String 0)))]
    (require! (and (re-matches #"[0-9a-f]{64}" (or expected-sha ""))
                   (= expected-sha actual)) "Preflight hash changed")
    (require! (not (.startsWith output repo)) "Private output must be outside repository")
    (require! (not (Files/isSymbolicLink output)) "Private output symlink forbidden")
    (let [parent (.getParent output)]
      (require! (not (Files/isSymbolicLink parent)) "Private output directory symlink forbidden")
      (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
      (require! (not-any? #(contains? (Files/getPosixFilePermissions parent (make-array java.nio.file.LinkOption 0)) %)
                          [java.nio.file.attribute.PosixFilePermission/GROUP_READ
                           java.nio.file.attribute.PosixFilePermission/GROUP_WRITE
                           java.nio.file.attribute.PosixFilePermission/GROUP_EXECUTE
                           java.nio.file.attribute.PosixFilePermission/OTHERS_READ
                           java.nio.file.attribute.PosixFilePermission/OTHERS_WRITE
                           java.nio.file.attribute.PosixFilePermission/OTHERS_EXECUTE])
                "Private output directory must be owner-only")
      (let [data (build-export (json/read-str (String. bytes "UTF-8") :key-fn keyword) actual)
            encoded (.getBytes (str (json/write-str data) "\n") "UTF-8")
            temp (Files/createTempFile parent "retained-aida-flow-" ".tmp"
                                       (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (Files/setPosixFilePermissions temp (PosixFilePermissions/fromString "rw-------"))
          (Files/write temp encoded (make-array java.nio.file.OpenOption 0))
          (if (Files/exists output (make-array java.nio.file.LinkOption 0))
            (require! (= (seq encoded) (seq (Files/readAllBytes output)))
                      "Existing private output differs")
            (Files/move temp output (into-array java.nio.file.CopyOption
                                                [StandardCopyOption/ATOMIC_MOVE])))
          (println (json/write-str {:counts (:counts data) :sha256 (sha256 encoded)}))
          (finally (Files/deleteIfExists temp)))))))
