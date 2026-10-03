(ns freediving.owner-event-delivery
  "Local callback from the owner's durable two-target delivery queue."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-policy :as policy])
  (:import [java.nio.file Files Path]))

(defn- parse-args [args]
  (when-not (and (#{6 7} (count args))
                 (= "--config" (nth args 0))
                 (= "--target" (nth args 2))
                 (= "--event-id" (nth args 4))
                 (or (= 6 (count args))
                     (= "--expected-event-stdin" (nth args 6))))
    (throw (ex-info "Expected --config PATH --target TARGET --event-id ID" {})))
  {:config-path (nth args 1)
   :target (case (nth args 3)
             "flow-ledger" :flow-ledger
             "postgresql" :postgresql
             (throw (ex-info "Unsupported owner event target" {})))
   :event-id (nth args 5)
   :expected-event-stdin? (= 7 (count args))})

(defn deliver!
  "Read a private EDN configuration and complete one callback. Returns a
   machine-readable receipt; command line callers print exactly this object."
  [args]
  (let [{:keys [config-path target event-id expected-event-stdin?]} (parse-args args)
        config (edn/read-string (Files/readString (Path/of config-path (make-array String 0))))
        decisions (:decisions config)
        opts (-> config
                 (dissoc :decisions)
                 (update :policy #(or % policy/default-policy))
                 (cond-> expected-event-stdin?
                   (assoc :expected-event
                          (json/read-str (slurp *in*) :key-fn keyword))))
        receipt (application/deliver-owner-event! target event-id decisions opts)]
    {:target (name target) :event_id event-id :receipt receipt}))

(defn -main [& args]
  (try
    (println (json/write-str (deliver! args)))
    (catch Exception error
      (binding [*out* *err*]
        (println (or (.getMessage error) "Owner event delivery failed")))
      (System/exit 1))))
