(ns freediving.pipeline-rama-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.rpl.rama.test :as rtest]
            [com.rpl.rama :as rama]
            [com.rpl.rama.path :refer [keypath]]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.agent-o-rama.impl.pobjects :as po]
            [com.rpl.agent-o-rama.impl.types :as types]
            [freediving.pipeline :as pipeline]
            [freediving.archive :as archive]
            [freediving.archive-test :as archive-fixture]
            [freediving.extraction-test :as pdf]
            [freediving.observations :as observations]
            [freediving.observations-test :as db]
            [freediving.pipeline-rama :as boundary]))

(deftest opaque-reference-required
  (is (thrown-with-msg? Exception #"Invalid job reference"
                        (boundary/invoke! nil {:source "/private/source.pdf"})))
  (is (thrown-with-msg? Exception #"qualified"
                        (boundary/pipeline-module {:database-url "private"}))))

(def configuration (atom {}))
(def roots (atom []))
(defn worker-config [] @configuration)

(use-fixtures :each
  (fn [test]
    (db/sql! db/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! db/admin "observations_app")
    (try (test)
         (finally
           (doseq [root @roots file (reverse (file-seq (io/file root)))]
             (io/delete-file file true))
           (reset! roots [])))))

(defn fixture
  ([] (fixture (pdf/synthetic-pdf
                (str "BT /F1 8 Tf 20 750 Td "
                     "(AIDA | 34th AIDA FREEDIVING WORLD CHAMPIONSHIP WAKAYAMA 2025) Tj 0 -20 Td "
                     "(Medals # Name Nationality Result Announced Points Penalties) Tj 0 -20 Td "
                     "(DYN) Tj 0 -20 Td (Female) Tj 0 -20 Td "
                     "(1. Private Synthetic AIN 100 m 1m 50 0) Tj ET"))))
  ([bytes]
   (let [root (archive-fixture/workspace)
         source (str root "/private.pdf")
         sha (.formatHex (java.util.HexFormat/of)
                         (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                  (.getBytes bytes "UTF-8")))
         config {:registry-root (str root "/registry")
                 :archive-root (str root "/archive") :database-url db/app}
         job {:source source :manifest (assoc archive-fixture/manifest :sha256 sha)
              :options {:actor "private-operator" :config {}}}]
     (swap! roots conj root)
     (spit source bytes)
     (reset! configuration config)
     (merge {:root root :bytes bytes :sha sha :job job :config config}
            (pipeline/register-job! (:registry-root config) job)))))

(defn with-module
  ([f] (with-module 'freediving.pipeline-rama-test/worker-config f))
  ([loader f]
   (with-open [ipc (rtest/create-ipc)]
     (let [module (boundary/pipeline-module loader)
           _ (rtest/launch-module! ipc module {:tasks 1 :threads 1})
           name (rama/get-module-name module)
           client (aor/agent-client (aor/agent-manager ipc name) "import")]
       (f {:ipc ipc :name name :client client})))))

(defn observe [{:keys [ipc name client]} job-id]
  (let [invoke (aor/agent-initiate client job-id)
        result (.get (aor/agent-result-async client invoke) 60 java.util.concurrent.TimeUnit/SECONDS)
        {:keys [task-id agent-invoke-id]} invoke
        roots (rama/foreign-pstate ipc name (po/agent-root-task-global-name "import"))
        root-id (rama/foreign-select-one [(keypath agent-invoke-id) :root-invoke-id]
                                         roots {:pkey task-id})
        query (:tracing-query (types/underlying-objects client))]
    {:result result :trace (rama/foreign-invoke-query query task-id [[task-id root-id]] 10000)}))

(defn snapshot [root]
  (into {} (for [file (file-seq (io/file root)) :when (.isFile file)]
             [(.getCanonicalPath file) [(.lastModified file) (slurp file)]])))

(deftest actual-graph-imports-and-retries-after-committed-ingestion
  (let [{:keys [root bytes sha config job-id]} (fixture)]
    (with-module
      (fn [{:keys [client] :as context}]
        (let [real-emit! aor/emit! attempts (atom 0)
              run (with-redefs [aor/emit! (fn [node destination & args]
                                            (when (and (= "readiness" destination)
                                                       (= 1 (swap! attempts inc)))
                                              (throw (ex-info "Synthetic post-commit retry" {})))
                                            (apply real-emit! node destination args))]
                    (observe context job-id))
              files (snapshot root)]
          (is (= :ready (get-in run [:result :status])))
          (is (= :readiness (get-in run [:result :stage])))
          (is (= :unreviewed (get-in run [:result :review-status])))
          (is (= :blocked (get-in run [:result :publication-status])))
          (is (= 2 @attempts))
          (is (str/includes? (pr-str (:trace run)) "Synthetic post-commit retry"))
          (is (= 1 (:observations (observations/counts db/app))))
          (is (= (:result run) (boundary/invoke! client job-id)))
          (is (= files (snapshot root)))
          (is (= bytes (slurp (:artifact-path (archive/inspect (:archive-root config) sha)))))
          (doseq [stage [:archive :extraction :ingestion :readiness]]
            (is (str/includes? (pr-str (:trace run)) (name stage))))
          (doseq [private [root db/app "Private Synthetic" "private-operator" "%PDF-1.4"]]
            (is (not (str/includes? (pr-str (:trace run)) private)))))))))

(deftest unsupported-is-an-explicit-extraction-outcome
  (let [{:keys [job-id]} (fixture (pdf/synthetic-pdf))]
    (with-module
      (fn [{:keys [client]}]
        (let [result (boundary/invoke! client job-id)]
          (is (= :extraction (:stage result)))
          (is (= :unsupported (:status result)))
          (is (= 0 (:observations (observations/counts db/app)))))))))

(deftest malformed-worker-outcomes-never-enter-traces
  (fixture)
  (with-module
    (fn [context]
      (doseq [outcome [{:status "PRIVATE-STATUS" :reason :private-person}
                       {:status :failed :reason :PRIVATE-REASON}]]
        (with-redefs [pipeline/stage! (fn [& _] outcome)]
          (let [run (observe context (apply str (repeat 64 "a")))]
            (is (= {:stage :archive :status :failed :reason :worker-unavailable}
                   (:result run)))
            (is (not (str/includes? (pr-str (:trace run)) "PRIVATE")))))))))

(deftest acquisition-change-between-nodes-cannot-mix-executions
  (let [{:keys [job config job-id]} (fixture)]
    (with-module
      (fn [context]
        (let [real-emit! aor/emit!
              run (with-redefs [aor/emit! (fn [node destination & args]
                                            (when (= "ingestion" destination)
                                              (archive/register! (:archive-root config) (:source job)
                                                                 (assoc (:manifest job) :retrieved-at "2026-09-24T13:14:09Z")))
                                            (apply real-emit! node destination args))]
                    (observe context job-id))]
          (is (= :ingestion (get-in run [:result :stage])))
          (is (= :blocked (get-in run [:result :status])))
          (is (= :stale-execution (get-in run [:result :reason])))
          (is (= 0 (:observations (observations/counts db/app)))))))))

(defn failing-config []
  (throw (ex-info "PRIVATE-CONFIGURATION" {:credential "PRIVATE-CREDENTIAL"}
                  (Exception. "PRIVATE-CAUSE"))))

(deftest initialization-failure-is-sanitized
  (let [failure (try
                  (with-module 'freediving.pipeline-rama-test/failing-config
                    (fn [{:keys [client]}]
                      (let [invoke (aor/agent-initiate client (apply str (repeat 64 "a")))]
                        (.get (aor/agent-result-async client invoke) 30 java.util.concurrent.TimeUnit/SECONDS))))
                  nil
                  (catch Throwable error error))
        messages (loop [error failure result []]
                   (if error
                     (recur (.getCause error) (conj result [(.getMessage error) (ex-data error)]))
                     (pr-str result)))]
    (is (some? failure))
    (is (str/includes? messages "Pipeline worker initialization failed"))
    (is (not (str/includes? messages "PRIVATE")))))

(defn -main [& _]
  (let [r (run-tests 'freediving.pipeline-rama-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ (:fail r) (:error r))) 1 0))))
