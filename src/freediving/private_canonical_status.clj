(ns freediving.private-canonical-status
  "Sanitized status from verified current canonical scopes; failed scopes stay unknown."
  (:require [clojure.data.json :as json]
            [freediving.athlete-identity :as identity]
            [freediving.canonical-attempt-store :as attempt])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(defn- need! [condition]
  (when-not condition (throw (ex-info "Canonical scope is not verified" {}))))
(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str value)) "UTF-8"))))
(defn- checked-export [config scope]
  (let [{:keys [sha256 export]} (get-in config [:exports scope])]
    (need! (and (string? sha256) (re-matches #"[0-9a-f]{64}" sha256)
                (= (:snapshot_sha256 config) (:snapshot_sha256 export))
                (vector? (:proposals export))))
    {:sha sha256 :proposals (into {} (map (juxt #(get-in % [:canonical_binding :decision_id]) identity)
                                          (:proposals export)))}))
(defn- same-attempt [config]
  (let [{:keys [sha proposals]} (checked-export config :same_attempt)
        {:keys [database ledger projection evidence-sha256]} (attempt/private-readback (:jdbc_url config))
        events (filter #(= :same-attempt (:type %)) (:events ledger))
        snapshot (:snapshot_sha256 config)]
    (need! (= database (:database config)))
    (need! (seq (:observation-versions ledger)))
    (need! (every? #(= snapshot (get-in % [:observation-revision :snapshot_sha256]))
                   (vals (:observation-versions ledger))))
    (need! (seq events))
    (need! (= (count events) (count (:events ledger))))
    (doseq [event events]
      (let [{:keys [owner-event binding]} (:owner-request event)
            proposal (get-in owner-event [:proposal])]
        (need! (and (= (:id event) (:id owner-event)
                       (str "owner-store:" (:store_revision owner-event)))
                    (= snapshot (:snapshot_sha256 owner-event))
                    (= binding (:canonical_binding proposal))
                    (= proposal (get proposals (:decision_id binding)))))))
    {:revision (:revision projection)
     :owner_event_revision (reduce max 0 (map #(get-in % [:owner-request :owner-event :store_revision]) events))
     :accepted_count (get-in projection [:counts :accepted-attempts])
     :export_sha256 sha :evidence_sha256 evidence-sha256}))
(defn- identity-scope [config]
  (let [{:keys [sha proposals]} (checked-export config :identity)
        current (identity/private-canonical-readback (:jdbc_url config))
        events (filter :owner-binding (:events current))]
    (need! (and (= (:database current) (:database config))
                (= (:snapshot-sha256 current) (:snapshot_sha256 config))))
    (doseq [event events]
      (need! (= (:owner-binding event)
                (:canonical_binding (get proposals (get-in event [:owner-binding :decision_id]))))))
    {:revision (get-in current [:projection :revision])
     :owner_event_revision (reduce max 0 (map :owner-event-revision events))
     :accepted_count nil :export_sha256 sha :evidence_sha256 (digest current)}))
(defn read-status
  "Each scope independently requires current replay, materialization and frozen export binding."
  [config]
  {:schema "private-canonical-status-readback/v1"
   :snapshot_sha256 (:snapshot_sha256 config)
   :scopes (into {} (keep (fn [[scope reader]]
                            (when (get-in config [:exports scope])
                              (try [scope (reader config)] (catch Exception _ nil))))
                          [[:identity identity-scope] [:same_attempt same-attempt]]))})
(defn -main [& _]
  (try
    (let [config (json/read-str (slurp *in*) :key-fn keyword)]
      (println (json/write-str (read-status config))))
    (catch Exception _
      (binding [*out* *err*] (println "Private canonical readback unavailable"))
      (System/exit 1))))
