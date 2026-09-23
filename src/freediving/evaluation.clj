(ns freediving.evaluation
  "Private, local shadow evaluations. No identity or publication authority."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [freediving.evaluation-data :as data]
            [freediving.evaluation-providers :as providers])
  (:import [java.nio.file Files Paths LinkOption StandardOpenOption StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.nio.channels FileChannel]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private monitor (Object.))
(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(defn- fail! [s] (throw (ex-info s {})))
(defn- path [p] (Paths/get (str p) (make-array String 0)))
(defn- attrs [s] (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString s))]))
(defn- safe! [p]
  (let [p (.toAbsolutePath (path p))]
    (when (some #(= ".." (str %)) (iterator-seq (.iterator p))) (fail! "Parent traversal forbidden"))
    (loop [q p] (when q (when (Files/isSymbolicLink q) (fail! "Symlink forbidden")) (recur (.getParent q))))
    p))
(defn- exists? [p] (Files/exists (safe! p) nofollow))
(defn- private! [p dir?]
  (let [p (safe! p)]
    (when-not (if dir? (Files/isDirectory p nofollow) (Files/isRegularFile p nofollow)) (fail! "Invalid storage entry"))
    (when (some #(re-find #"GROUP|OTHERS" (str %)) (Files/getPosixFilePermissions p nofollow)) (fail! "Evaluation storage must be private"))))
(defn- directory! [p]
  (when-not (exists? p) (Files/createDirectory (safe! p) (attrs "rwx------")))
  (private! p true))
(defn- canonical [x]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (walk/postwalk #(cond (map? %) (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b)))) %)
                                  (set? %) (into (sorted-set-by (fn [a b] (compare (pr-str a) (pr-str b)))) %)
                                  :else %) x))))
(defn- digest [s] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))))
(defn- valid-id! [id] (when-not (and (string? id) (re-matches #"[0-9a-f]{64}" id)) (fail! "Invalid content identity")) id)
(defn- write-once! [root target text]
  (safe! target)
  (if (exists? target)
    (do (private! target false) (when-not (= text (slurp target)) (fail! "Immutable record mismatch")))
    (let [tmp (Files/createTempFile (path (io/file root "tmp")) "pending-" ".edn" (attrs "rw-------"))]
      (try
        (spit (.toFile tmp) text :encoding "UTF-8")
        (with-open [c (FileChannel/open tmp (into-array StandardOpenOption [StandardOpenOption/WRITE]))] (.force c true))
        (Files/move tmp (safe! target) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
        (finally (Files/deleteIfExists tmp))))))
(defn- put! [root x]
  (let [s (canonical x) id (digest s)] (write-once! root (io/file root "objects" id) s) id))
(defn- object! [root id]
  (valid-id! id)
  (let [f (io/file root "objects" id)]
    (private! f false)
    (let [s (slurp f)] (when-not (= id (digest s)) (fail! "Content hash mismatch")) (edn/read-string s))))
(defn- verify-graph! [root x]
  (when-not (or (:harness-version x) (:adapter-version x))
    (walk/postwalk (fn [v]
                     (when (map? v)
                       (doseq [k [:input-hash :report-hash :request-hash :start-hash :trace-hash]
                               :when (contains? v k)]
                         (verify-graph! root (object! root (get v k)))))
                     v) x))
  x)
(defn- record! [root key x]
  (let [id (put! root x)] (write-once! root (io/file root "records" key) id) x))
(defn- read-record [root key]
  (let [p (io/file root "records" key)]
    (when (exists? p) (private! p false) (object! root (slurp p)))))
(defn- with-store [root f]
  (locking monitor
    (directory! root)
    (let [lock (io/file root ".lock")]
      (safe! lock)
      (when (exists? lock) (private! lock false))
      (with-open [channel (FileChannel/open (path lock) #{StandardOpenOption/CREATE StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS} (attrs "rw-------"))
                  _lock (.lock channel)]
        (doseq [d ["objects" "records" "tmp"]] (directory! (io/file root d)))
        (doseq [file (.listFiles (io/file root "tmp"))]
          (private! file false)
          (when-not (re-matches #"pending-.*\.edn" (.getName file)) (fail! "Unknown staging entry"))
          (Files/delete (path file)))
        (f root)))))
(defn- progress! [runtime event] (when-let [f (:on-progress runtime)] (f event)))
(defn- attempt! [root key number prepared runtime]
  (let [prefix (str key "-" number) finished (read-record root (str prefix "-done"))]
    (or finished
        (if-let [started (read-record root (str prefix "-start"))]
          (record! root (str prefix "-done")
                   {:attempt number :request-hash (:request-hash started) :start-hash (put! root started) :latency-ms nil
                    :result {:outcome :error :error :interrupted-attempt :retryable? false
                             :external-outcome :unknown :model-version nil :cost {:status :unknown}}})
          (let [request-hash (put! root prepared)
                started {:attempt number :request-hash request-hash :started-at (str (java.time.Instant/now))}]
            (record! root (str prefix "-start") started)
            (progress! runtime {:phase :attempt-started :attempt number})
            (let [t (System/nanoTime)
                  result (try (providers/execute! prepared runtime)
                              (catch Exception _ {:outcome :error :error :adapter-exception :retryable? false
                                                  :external-outcome :unknown :model-version nil :cost {:status :unknown}}))
                  receipt {:attempt number :request-hash request-hash :start-hash (put! root started) :completed-at (str (java.time.Instant/now))
                           :latency-ms (/ (double (- (System/nanoTime) t)) 1000000.0) :result result}]
              (progress! runtime {:phase :attempt-returned :attempt number})
              (record! root (str prefix "-done") receipt)))))))
(defn- total-cost [attempts]
  (let [costs (map #(get-in % [:result :cost]) attempts)]
    (if (and (every? #(= :metered (:status %)) costs)
             (= 1 (count (set (map :currency costs)))))
      {:status :metered :amount (reduce + (map :amount costs)) :currency (:currency (first costs))}
      {:status :unknown})))
(defn- evaluate! [root run-id config case prepared runtime]
  (let [key (digest (canonical [run-id (:id config) (:case-id case)]))]
    (loop [n 1 attempts []]
      (let [attempt (attempt! root key n prepared runtime) attempts (conj attempts (assoc attempt :trace-hash (put! root attempt))) result (:result attempt)]
        (if (and (= :error (:outcome result)) (:retryable? result) (< n (:max-attempts config)))
          (do (Thread/sleep (long (min 2000 (* (:retry-delay-ms config) (bit-shift-left 1 (dec n)))))) (recur (inc n) attempts))
          (assoc result :case-id (:case-id case) :attempts attempts
                 :cost (total-cost attempts) :latency-ms (when (every? #(number? (:latency-ms %)) attempts) (reduce + (map :latency-ms attempts)))))))))

(defn run!
  "Evaluate only held-out cases with each configuration. Runtime secrets stay outside
   content identities. Pending attempts become unknown, never automatically resent.
   :on-progress may interrupt after durable start or provider return to test recovery."
  ([root dataset configs] (run! root dataset configs {}))
  ([root dataset configs runtime]
   (data/validate-dataset! dataset)
   (when-not (and (vector? configs) (<= 1 (count configs) 10) (= (count configs) (count (set (map :id configs))))) (fail! "Provider configurations need unique IDs"))
   (let [cases (filterv #(= :held-out (:split %)) (:cases dataset))
         configs (mapv #(merge {:max-attempts 1 :retry-delay-ms 100} %) configs)]
     (when (empty? cases) (fail! "At least one held-out case required"))
     (doseq [c configs]
       (when-not (and (string? (:id c)) (seq (:id c)) (integer? (:max-attempts c)) (<= 1 (:max-attempts c) 3) (integer? (:retry-delay-ms c)) (<= 0 (:retry-delay-ms c) 2000)) (fail! "Invalid provider ID or attempt bound")))
     (let [prepared (mapv (fn [c] (mapv #(providers/prepare-request c %) cases)) configs)
           ;; Persist only validated provider requests/configuration. Never runtime credentials.
           identity {:schema-version 1 :harness-version "shadow-runner/1" :dataset dataset :requests prepared :configurations (mapv #(select-keys % [:id :max-attempts :retry-delay-ms :scope-id]) configs)}
           run-id (digest (canonical identity))]
       (with-store root
         (fn [root]
           (put! root identity)
           (or (some->> (read-record root (str run-id "-manifest")) (verify-graph! root))
               (let [reports (into {} (map (fn [c requests]
                                             (let [results (mapv #(evaluate! root run-id c %1 %2 runtime) cases requests)]
                                               [(:id c) {:results results :metrics (data/metrics cases results)}])) configs prepared))
                     report {:schema-version 1 :run-id run-id :dataset-id (:dataset-id dataset) :providers reports}
                     report-hash (put! root report)
                     manifest {:run-id run-id :input-hash run-id :report-hash report-hash}]
                 (record! root (str run-id "-manifest") manifest)))))))))
(defn inspect-run "Read and verify the private report and input by content identity." [root run-id]
  (valid-id! run-id)
  (with-store root (fn [root]
                     (let [manifest (or (some->> (read-record root (str run-id "-manifest")) (verify-graph! root)) (fail! "Unknown run"))]
                       (assoc manifest :input (object! root (:input-hash manifest)) :report (object! root (:report-hash manifest)))))))
