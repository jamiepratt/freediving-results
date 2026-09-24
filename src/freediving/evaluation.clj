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
                  result (try (providers/execute! prepared (get-in runtime [:providers (get-in prepared [:config :id])] {}))
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
(defn- terminal-error? [config result]
  (and (:stop-on-terminal-error? config) (= :error (:outcome result))
       (or (#{400 401 402 403 404 422 429} (:http-status result))
           (#{:invalid-response :missing-credential} (:error result)))))

(defn- evaluate! [root run-id config case prepared runtime]
  (let [key (digest (canonical [run-id (:id config) (:case-id case)]))]
    (loop [n 1 attempts []]
      (let [attempt (attempt! root key n prepared runtime) attempts (conj attempts (assoc attempt :trace-hash (put! root attempt))) result (:result attempt)
            unknown-count (count (filter #(= :unknown (get-in % [:result :external-outcome])) attempts))]
        (if (and (= :error (:outcome result)) (:retryable? result) (not (terminal-error? config result)) (< n (:max-attempts config)))
          (do (Thread/sleep (long (min 2000 (* (:retry-delay-ms config) (bit-shift-left 1 (dec n)))))) (recur (inc n) attempts))
          (assoc result :case-id (:case-id case) :attempts attempts
                 :unknown-external-attempt-count unknown-count
                 :warnings (if (and (pos? unknown-count) (> (count attempts) 1)) [:possible-duplicate-external-work] [])
                 :cost (total-cost attempts) :latency-ms (when (every? #(number? (:latency-ms %)) attempts) (reduce + (map :latency-ms attempts)))))))))

(defn- comparator! [root run-id config cases requests runtime]
  (loop [pending (map vector cases requests) results [] halted-by nil]
    (if-let [[case prepared] (first pending)]
      (let [result (if halted-by
                     {:case-id (:case-id case) :outcome :error :error :comparator-halted
                      :dispatch-status :not-dispatched :halted-by-case-id halted-by
                      :attempts [] :latency-ms nil :cost {:status :not-incurred}
                      :unknown-external-attempt-count 0 :warnings []}
                     (evaluate! root run-id config case prepared runtime))]
        (recur (next pending) (conj results result)
               (or halted-by (when (terminal-error? config result) (:case-id case)))))
      (let [undispatched (count (filter #(= :not-dispatched (:dispatch-status %)) results))]
        {:results results :metrics (data/metrics cases results)
         :dispatch {:evaluated-case-count (- (count results) undispatched)
                    :undispatched-case-count undispatched
                    :attempt-count (reduce + (map #(count (:attempts %)) results))}}))))

(defn run!
  "Evaluate only held-out cases with each configuration. Runtime secrets stay outside
   content identities. Provider runtime is scoped as :providers {config-id options};
   root credentials are never forwarded. Hooks remain at the outer runtime level.
   Pending attempts become unknown, never automatically resent.
   Admission caps planned responses plus 4 KiB/attempt at 32 MiB and canonical
   dataset/request identity at 32 MiB. Copies/EDN escaping add storage overhead.
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
     (let [samples (mapv #(providers/prepare-request % (first cases)) configs)
           response-budget (* (count cases)
                              (reduce + (map (fn [request config]
                                               (* (:max-attempts config)
                                                  (+ 4096 (get-in request [:config :max-response-bytes]))))
                                             samples configs)))]
       (when (> response-budget (* 32 1024 1024)) (fail! "Planned response budget exceeds 32 MiB")))
     (let [prepared (mapv (fn [c] (mapv #(providers/prepare-request c %) cases)) configs)
           ;; Persist only validated provider requests/configuration. Never runtime credentials.
           identity {:schema-version 1 :harness-version (if (some :stop-on-terminal-error? configs) "shadow-runner/5" "shadow-runner/4") :dataset dataset :requests prepared :configurations (mapv #(select-keys % [:id :max-attempts :retry-delay-ms :scope-id :stop-on-terminal-error?]) configs)}
           identity-text (canonical identity)
           _ (when (> (alength (.getBytes ^String identity-text "UTF-8")) (* 32 1024 1024))
               (fail! "Input and request budget exceeds 32 MiB"))
           run-id (digest identity-text)]
       (with-store root
         (fn [root]
           (put! root identity)
           (or (some->> (read-record root (str run-id "-manifest")) (verify-graph! root))
               (let [reports (into {} (map (fn [c requests]
                                             [(:id c) (comparator! root run-id c cases requests runtime)]) configs prepared))
                     report {:schema-version 1 :run-id run-id :dataset-id (:dataset-id dataset) :providers reports}
                     report-hash (put! root report)
                     manifest {:run-id run-id :input-hash run-id :report-hash report-hash}]
                 (record! root (str run-id "-manifest") manifest)))))))))
(defn inspect-run
  "Verify stored bytes and return a metrics view recomputed without DB authority.
   :stored-report-hash identifies stored bytes; :report-view marks the derived view,
   including legacy reports whose file assertions once appeared as owner metrics."
  [root run-id]
  (valid-id! run-id)
  (with-store root
    (fn [root]
      (let [manifest (or (some->> (read-record root (str run-id "-manifest")) (verify-graph! root)) (fail! "Unknown run"))
            input (object! root (:input-hash manifest))
            report (object! root (:report-hash manifest))
            cases (filterv #(= :held-out (:split %)) (get-in input [:dataset :cases]))
            view (update report :providers
                         (fn [reports]
                           (into {} (map (fn [[id r]] [id (assoc r :metrics (data/metrics cases (:results r)))]) reports))))]
        (assoc manifest :input input :report view :stored-report-hash (:report-hash manifest)
               :report-view :recomputed-unverified-assertions)))))

(defn run-verified!
  "Evaluate a receipt only after live authoritative DB verification. Recheck after
   provider work, including replay. Persist immutable, private export and report
   receipts; DB credentials never enter content identities. No-label exports are
   blocked before provider dispatch. Synthetic corpora remain synthetic."
  ([root db-url receipt configs] (run-verified! root db-url receipt configs {}))
  ([root db-url receipt configs runtime]
   (let [verify! (requiring-resolve 'freediving.evaluation-labels/verify!)
         verified (verify! db-url receipt)
         cases (filterv #(= :held-out (:split %)) (get-in verified [:dataset :cases]))
         eligible (filterv :label cases)
         export-hash (with-store root #(put! % receipt))]
     (if (empty? eligible)
       {:status :blocked :reason :no-eligible-reviewed-labels :export-hash export-hash}
       (let [raw (run! root (:dataset verified) configs runtime)
             raw-report (:report (inspect-run root (:run-id raw)))
             reports (into {} (map (fn [[id report]]
                                     [id (assoc report :metrics
                                                (data/metrics-verified db-url receipt (:results report)))])
                                   (:providers raw-report)))
             report {:schema-version 1 :status :evaluated :export-hash export-hash
                     :verification-scope :database-snapshot-at-verification
                     :label-source (:label-source verified) :raw-run raw :providers reports}]
         (verify! db-url receipt)
         (with-store root
           (fn [root]
             (let [report-hash (put! root report)
                   id (digest (canonical ["verified-shadow/1" export-hash (:run-id raw)]))
                   manifest {:status :evaluated :verified-id id :export-hash export-hash
                             :run-id (:run-id raw) :report-hash report-hash}]
               (record! root (str id "-verified-manifest") manifest)))))))))

(defn inspect-verified-run
  "Inspect verified metrics only while their original export still matches the DB.
   Recompute metrics using verified labels, never trust metrics copied into files."
  [root db-url verified-id]
  (valid-id! verified-id)
  (let [manifest (with-store root
                   (fn [root]
                     (let [manifest (or (read-record root (str verified-id "-verified-manifest"))
                                        (fail! "Unknown verified run"))]
                       (assoc manifest :export (object! root (:export-hash manifest))
                              :report (verify-graph! root (object! root (:report-hash manifest)))))))
        receipt (:export manifest)
        verified ((requiring-resolve 'freediving.evaluation-labels/verify!) db-url receipt)
        raw (inspect-run root (:run-id manifest))
        expected-id (digest (canonical ["verified-shadow/1" (:export-hash manifest) (:run-id manifest)]))]
    (when-not (and (= verified-id expected-id (:verified-id manifest))
                   (= (:dataset verified) (get-in raw [:input :dataset])))
      (fail! "Verified run does not match authoritative export"))
    (let [reports (into {} (map (fn [[id report]]
                                  [id (assoc report :metrics
                                             (data/metrics-verified db-url receipt (:results report)))])
                                (get-in raw [:report :providers])))
          expected {:schema-version 1 :status :evaluated :export-hash (:export-hash manifest)
                    :verification-scope :database-snapshot-at-verification
                    :label-source (:label-source verified)
                    :raw-run (select-keys raw [:run-id :input-hash :report-hash]) :providers reports}]
      (when-not (= expected (:report manifest)) (fail! "Verified report differs from authoritative labels and raw results"))
      manifest)))
