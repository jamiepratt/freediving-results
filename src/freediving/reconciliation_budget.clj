(ns freediving.reconciliation-budget
  "Private cumulative reservation ledger for a bounded Jev trial. Reservations are never refunded."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption Path Paths StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util HexFormat UUID]))

(def ledger-version "reconciliation-budget/1")
(def max-calls 15000)
(def max-usd 10M)
(def model "jev-1.13.0")
(def published-input-rate 0.042M)
(def published-token-limit 64000)
(def ^:private process-lock (Object.))

(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value) "UTF-8"))))

(defn- path-of [path]
  (let [path (Paths/get (str path) (make-array String 0))]
    (when-not (.isAbsolute path)
      (throw (ex-info "Budget path must be absolute" {})))
    path))

(defn- private! [^Path path]
  (try (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rw-------"))
       (catch UnsupportedOperationException _ nil)))

(defn load-ledger!
  "Read and verify the budget ledger. Missing baseline is an error."
  [path]
  (let [path (path-of path)]
    (when-not (Files/exists path (make-array LinkOption 0))
      (throw (ex-info "Provider budget baseline missing" {:reason :missing-budget-baseline})))
    (let [envelope (try (edn/read-string (Files/readString path))
                        (catch Exception error
                          (throw (ex-info "Unreadable provider budget" {} error))))
          ledger (:ledger envelope)]
      (when-not (and (= ledger-version (:version ledger))
                     (vector? (:events ledger))
                     (= (:sha256 envelope) (digest ledger)))
        (throw (ex-info "Provider budget integrity failure" {})))
      (let [receipts (into {} (map (juxt :reservation-id identity)
                                   (filter #(= :receipt (:type %)) (:events ledger))))]
        (assoc ledger :reservations
               (mapv (fn [event]
                       (cond-> event
                         (get receipts (:id event))
                         (assoc :reported-usage (:reported-usage (get receipts (:id event))))))
                     (filter #(= :reservation (:type %)) (:events ledger))))))))

(defn- write! [^Path path ledger]
  (let [parent (.getParent path)
        temp (Files/createTempFile parent ".budget-" ".edn"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (private! temp)
      (Files/writeString temp (pr-str {:sha256 (digest ledger) :ledger ledger})
                         (into-array StandardOpenOption [StandardOpenOption/WRITE
                                                         StandardOpenOption/TRUNCATE_EXISTING]))
      (Files/move temp path (into-array StandardCopyOption
                                        [StandardCopyOption/ATOMIC_MOVE
                                         StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists temp)))))

(defn- locked! [path f]
  (let [^Path path (path-of path)
        parent (.getParent path)]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (locking process-lock
      (let [lock-path (.resolve parent (str (.getFileName path) ".lock"))]
        (with-open [channel (FileChannel/open lock-path
                                              (into-array StandardOpenOption
                                                          [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                    _lock (.lock channel)]
          (private! lock-path)
          (f path))))))

(defn- valid-pricing? [pricing]
  (and (map? pricing) (string? (:version pricing)) (seq (:version pricing))
       (= model (:model pricing))
       (string? (:source pricing)) (seq (:source pricing))
       (every? #(and (number? %) (<= 0 %))
               ((juxt :input-usd-per-million :output-usd-per-million
                      :max-input-tokens :max-output-tokens) pricing))
       (integer? (:max-input-tokens pricing))
       (integer? (:max-output-tokens pricing))
       (<= published-input-rate (:input-usd-per-million pricing))
       (<= published-token-limit (:max-input-tokens pricing))
       (<= published-token-limit (:max-output-tokens pricing))))

(defn- valid-reservation? [entry]
  (and (= :reservation (:type entry)) (string? (:id entry))
       (string? (:request-hash entry))
       (number? (:reserved-usd entry)) (<= 0 (:reserved-usd entry))
       (or (not (:historical? entry))
           (and (string? (:source-sha256 entry))
                (boolean (re-matches #"[0-9a-f]{64}" (:source-sha256 entry)))
                (string? (:price-version entry))
                (pos? (:reserved-usd entry))))))

(defn initialize!
  "Create once from audited historical reservations. Never overwrites an existing ledger."
  [path historical]
  (locked! path
           (fn [path]
             (when (Files/exists path (make-array LinkOption 0))
               (throw (ex-info "Provider budget already initialized" {})))
             (when-not (and (vector? historical)
                            (every? valid-reservation? historical)
                            (every? :historical? historical)
                            (= (count historical) (count (set (map :id historical)))))
               (throw (ex-info "Invalid historical provider reservations" {})))
             (let [ledger {:version ledger-version :events historical}]
               (write! path ledger)
               (load-ledger! path)))))

(defn- estimate-usd [request pricing]
  (when-not (and (valid-pricing? pricing) (= (:model pricing) (get-in request [:config :model]))
                 (= :jev (:provider request)) (string? (:body request))
                 (string? (:request-hash request)))
    (throw (ex-info "Unpriced provider request" {:reason :unpriced-request})))
  ;; Reserve the full published request token limit, including provider framing
  ;; that is absent from the submitted JSON. Output receives its own full limit.
  (let [input (:max-input-tokens pricing)]
    (/ (+ (* (bigdec input) (bigdec (:input-usd-per-million pricing)))
          (* (bigdec (:max-output-tokens pricing))
             (bigdec (:output-usd-per-million pricing))))
       1000000M)))

(defn reserve!
  "Reserve one provider attempt atomically before dispatch. Equality with $10 stops."
  [path request pricing]
  (let [estimate (estimate-usd request pricing)]
    (locked! path
             (fn [path]
               (let [ledger (load-ledger! path)
                     reservations (:reservations ledger)
                     total (reduce + 0M (map :reserved-usd reservations))]
                 (when (or (>= (count reservations) max-calls)
                           (>= (+ total estimate) max-usd))
                   (throw (ex-info "Provider budget exhausted"
                                   {:reason :provider-budget-exhausted
                                    :calls (count reservations) :reserved-usd total
                                    :next-estimate-usd estimate})))
                 (let [entry {:type :reservation :id (str (UUID/randomUUID))
                              :request-hash (:request-hash request)
                              :model (get-in request [:config :model])
                              :pricing pricing :input-token-upper-bound
                              (:max-input-tokens pricing)
                              :output-token-upper-bound (:max-output-tokens pricing)
                              :reserved-usd estimate}
                       next-ledger (-> ledger (dissoc :reservations)
                                       (update :events conj entry))]
                   (write! path next-ledger)
                   entry))))))

(defn record-receipt!
  "Retain provider-reported usage after a response. Missing or failed responses stay uncertain."
  [path reservation response]
  (let [raw (when (string? (:raw-response response))
              (try (json/read-str (:raw-response response) :key-fn keyword)
                   (catch Exception _ nil)))
        usage (or (:usage response) (:usage raw))]
    (when (map? usage)
      (locked! path
               (fn [path]
                 (let [ledger (load-ledger! path)
                       id (:id reservation)]
                   (when-not (some #(= id (:id %)) (:reservations ledger))
                     (throw (ex-info "Unknown provider reservation" {})))
                   (when-not (some #(= id (:reservation-id %)) (:events ledger))
                     (write! path (-> ledger (dissoc :reservations)
                                      (update :events conj
                                              {:type :receipt :reservation-id id
                                               :reported-usage usage
                                               :http-status (:http-status response)})))))))))
  nil)
