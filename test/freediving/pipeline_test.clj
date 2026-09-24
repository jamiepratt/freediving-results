(ns freediving.pipeline-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [freediving.archive-test :as fixture]
            [freediving.pipeline :as pipeline]
            [freediving.depth :as depth]
            [freediving.novi-sad :as novi-sad]
            [freediving.extraction-test :as pdf]
            [freediving.archive :as archive]
            [freediving.observations :as observations]
            [freediving.candidates :as candidates]
            [freediving.observations-test :as db]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def roots (atom []))
(defn workspace [] (let [root (fixture/workspace)] (swap! roots conj root) root))
(use-fixtures :each
  (fn [test]
    (try (test)
         (finally
           (doseq [root @roots file (reverse (file-seq (io/file root)))] (io/delete-file file true))
           (reset! roots [])))))

(deftest private-registration-is-deterministic-and-bounded
  (let [root (str (workspace) "/registry")
        job {:source "/unavailable/source.pdf" :manifest nil :options {:actor "test" :config {}}}
        registered (pipeline/register-job! root job)]
    (is (re-matches #"[0-9a-f]{64}" (:job-id registered)))
    (is (= registered (pipeline/register-job! root job)))
    (is (not= registered (pipeline/register-job! root (assoc-in job [:options :config :revision] 2))))
    (is (thrown? Exception (pipeline/register-job! root (assoc job :url "https://example.org"))))
    (is (= :missing-provenance (:reason (pipeline/stage! {:registry-root root} :archive (:job-id registered)))))
    (is (= :invalid-job-reference (:reason (pipeline/stage! {:registry-root root} :archive "../secret"))))))

(defn setup
  ([] (setup (pdf/synthetic-pdf)))
  ([text]
   (let [dir (workspace) source (str dir "/source.pdf")
         h (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8")))
         job {:source source :manifest (assoc fixture/manifest :sha256 h) :options {:actor "synthetic-test" :config {}}}
         config {:registry-root (str dir "/registry") :archive-root (str dir "/archive")
                 :database-url (System/getenv "FREEDIVING_TEST_URL")}]
     (spit source text)
     {:job job :config config :job-id (:job-id (pipeline/register-job! (:registry-root config) job))})))

(deftest archive-and-extraction-are-explicit-and-preserve-exact-bytes
  (let [{:keys [job config job-id]} (setup)]
    (is (= :blocked (:status (pipeline/stage! config :extraction job-id))))
    (let [archived (pipeline/stage! config :archive job-id)]
      (is (= :ready (:status archived)))
      (is (= archived (pipeline/stage! config :archive job-id)))
      (is (= (slurp (:source job)) (slurp (:artifact-path (archive/inspect (:archive-root config) (get-in job [:manifest :sha256])))))))
    (let [extracted (pipeline/stage! config :extraction job-id)]
      (is (= :unsupported (:status extracted)))
      (is (= :unsupported-needs-parser (:reason extracted)))
      (is (= extracted (pipeline/stage! config :extraction job-id)))
      (is (= :blocked (:status (pipeline/stage! config :ingestion job-id)))))
    (spit (:source job) "tampered")
    (is (= :source-hash-mismatch (:reason (pipeline/stage! config :archive job-id))))
    (is (= :source-hash-mismatch (:reason (pipeline/stage! config :extraction job-id))))))

(def supported-pdf
  (pdf/synthetic-pdf (str "BT /F1 8 Tf 20 750 Td "
                          "(AIDA | 34th AIDA FREEDIVING WORLD CHAMPIONSHIP WAKAYAMA 2025) Tj 0 -20 Td "
                          "(Medals # Name Nationality Result Announced Points Penalties) Tj 0 -20 Td "
                          "(DYN) Tj 0 -20 Td (Female) Tj 0 -20 Td "
                          "(1. Private Synthetic AIN 100 m 1m 50 0) Tj ET")))
(defn prepare-db! []
  (db/sql! db/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
  (observations/migrate! db/admin "observations_app"))
(defn through-extraction! [{:keys [config job-id]}]
  (is (= :ready (:status (pipeline/stage! config :archive job-id))))
  (let [result (pipeline/stage! config :extraction job-id)]
    (is (= :ready (:status result))) result))

(deftest actual-postgres-recovers-commit-before-receipt-without-duplicates
  (prepare-db!)
  (let [{:keys [job config job-id] :as fixture} (setup supported-pdf)
        extracted (through-extraction! fixture)
        code (str "(require '[freediving.pipeline :as p]) (p/stage! "
                  (pr-str (assoc config :on-progress nil))
                  " :ingestion " (pr-str job-id) ")")
        ;; The trusted local hook is after COMMIT, before the durable stage receipt.
        code (str/replace code ":on-progress nil" ":on-progress (fn [_] (.halt (Runtime/getRuntime) 23))")
        died (shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" code)]
    (is (= 23 (:exit died)))
    (is (= 1 (:observations (observations/counts db/app))))
    (let [ingested (pipeline/stage! config :ingestion job-id)
          ready (pipeline/stage! config :readiness job-id)]
      (is (= :ready (:status ingested)))
      (is (= ingested (pipeline/stage! config :ingestion job-id)))
      (is (= :ready (:status ready)))
      (is (= :unreviewed (:review-status ready)))
      (is (= :blocked (:publication-status ready)))
      (is (= ready (pipeline/stage! config :readiness job-id)))
      (is (= 1 (:observations (observations/counts db/app))))
      (is (= (slurp (:source job)) (slurp (:artifact-path (archive/inspect (:archive-root config) (get-in job [:manifest :sha256]))))))
      (is (not (re-find #"Private Synthetic|jdbc:|source.pdf|private-operator" (pr-str [ingested ready])))))
    ;; Missing extraction references cannot be recreated during a downstream retry.
    (let [file (io/file (:archive-root config) "derivations" (str (:extraction-id extracted) ".edn"))]
      (io/delete-file file)
      (is (not= :ready (:status (pipeline/stage! config :ingestion job-id))))
      (is (not (.exists file))))))

(deftest stale-provenance-execution-and-bad-database-fail-closed
  (prepare-db!)
  (let [{:keys [job config job-id] :as fixture} (setup supported-pdf)
        extracted (through-extraction! fixture)]
    (is (= :stale-execution (:reason (pipeline/stage! config :ingestion job-id (apply str (repeat 64 "0"))))))
    (archive/register! (:archive-root config) (:source job) (assoc (:manifest job) :publisher "Changed publisher"))
    (is (= :stale-execution (:reason (pipeline/stage! config :ingestion job-id (:execution-id extracted)))))
    (is (= :missing-or-corrupt-prerequisite (:reason (pipeline/stage! config :ingestion job-id))))
    (is (zero? (:observations (observations/counts db/app)))))
  (let [{:keys [config job-id]} (setup supported-pdf)
        config (assoc config :database-url "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1")]
    (through-extraction! {:config config :job-id job-id})
    (is (= :database-error (:reason (pipeline/stage! config :ingestion job-id))))))

(deftest invalid-provenance-unavailable-and-private-reference-corruption
  (let [{:keys [job config]} (setup)
        invalid (pipeline/register-job! (:registry-root config) (assoc-in job [:manifest :publisher] nil))]
    (is (= :invalid-provenance (:reason (pipeline/stage! config :archive (:job-id invalid))))))
  (let [{:keys [job config job-id]} (setup)]
    (io/delete-file (:source job))
    (is (= :source-unavailable (:reason (pipeline/stage! config :archive job-id)))))
  (let [{:keys [config job-id]} (setup)
        file (io/file (:registry-root config) "derivations" (str job-id ".edn"))
        record (edn/read-string (slurp file))
        object (io/file (:registry-root config) "derived-objects" (:artifact-sha256 record))]
    (spit object "corrupt")
    (is (= :invalid-private-reference (:reason (pipeline/stage! config :archive job-id))))))

(deftest completed-references-are-not-recreated-and-config-is-versioned
  (prepare-db!)
  (let [{:keys [job config job-id] :as fixture} (setup supported-pdf)
        first-extraction (through-extraction! fixture)
        _ (pipeline/stage! config :ingestion job-id)
        ready (pipeline/stage! config :readiness job-id)
        ready-file (io/file (:registry-root config) "derivations" (str (:receipt-id ready) ".edn"))
        ready-bytes (slurp ready-file)
        next-id (:job-id (pipeline/register-job! (:registry-root config) (assoc-in job [:options :config :revision] 2)))
        next-extraction (through-extraction! {:config config :job-id next-id})]
    (is (not= job-id next-id))
    (is (not= (:execution-id first-extraction) (:execution-id next-extraction)))
    (is (not= (:extraction-id first-extraction) (:extraction-id next-extraction)))
    (is (= :ready (:status (pipeline/stage! config :ingestion next-id))))
    (is (= 2 (:observations (observations/counts db/app))))
    (is (= :stale-stage-receipt (:reason (pipeline/stage! config :readiness job-id))))
    (is (= ready-bytes (slurp ready-file)))
    (let [derived (io/file (:archive-root config) "derivations" (str (:extraction-id first-extraction) ".edn"))]
      (io/delete-file derived)
      (is (not= :ready (:status (pipeline/stage! config :extraction job-id))))
      (is (not (.exists derived))))
    (let [object (io/file (:archive-root config) "objects" (get-in job [:manifest :sha256]))]
      (io/delete-file object)
      (is (not= :ready (:status (pipeline/stage! config :archive job-id))))
      (is (not (.exists object))))))

(deftest completed-ingestion-missing-database-evidence-fails-closed
  (prepare-db!)
  (let [{:keys [config job-id] :as fixture} (setup supported-pdf)]
    (through-extraction! fixture)
    (is (= :ready (:status (pipeline/stage! config :ingestion job-id))))
    (db/sql! db/admin "ALTER TABLE freediving.observations DISABLE TRIGGER ALL")
    (db/sql! db/admin "ALTER TABLE freediving.extractions DISABLE TRIGGER ALL")
    (db/sql! db/admin "TRUNCATE freediving.observations,freediving.extractions")
    (is (= :missing-ingestion-reference (:reason (pipeline/stage! config :ingestion job-id))))
    (is (zero? (:observations (observations/counts db/app))))))

(deftest candidate-version-change-requires-new-registration
  (let [{:keys [job config job-id]} (setup)]
    (with-redefs [candidates/packet-version "private-candidates/test-next"]
      (is (= :stale-job-version (:reason (pipeline/stage! config :archive job-id))))
      (is (not= job-id (:job-id (pipeline/register-job! (:registry-root config) job)))))))

(deftest partially-supported-and-unparsed-pdfs-remain-distinct-from-worker-failure
  (doseq [[text status reason]
          [[(pdf/synthetic-pdf "BT /F1 8 Tf 20 750 Td (2025 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR, GREECE) Tj ET")
            :unsupported :partial-unsupported-needs-parser]
           [(str/replace supported-pdf "100 m 1m 50 0" "BAD m 1m 50 0") :unresolved :parser-unresolved]]]
    (let [{:keys [config job-id]} (setup text)]
      (is (= :ready (:status (pipeline/stage! config :archive job-id))))
      (let [result (pipeline/stage! config :extraction job-id)]
        (is (= status (:status result)))
        (is (= reason (:reason result)))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.pipeline-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

(deftest depth-parser-version-change-requires-new-registration
  (let [{:keys [job config job-id]} (setup)]
    (with-redefs [depth/parser-version "cmas-women-depth/test-next"]
      (is (= :stale-job-version (:reason (pipeline/stage! config :archive job-id))))
      (is (not= job-id (:job-id (pipeline/register-job! (:registry-root config) job)))))))

(deftest novi-parser-version-change-requires-new-registration
  (let [{:keys [job config job-id]} (setup)]
    (with-redefs [novi-sad/parser-version "cmas-novi-sad-dnf-juniors/test-next"]
      (is (= :stale-job-version (:reason (pipeline/stage! config :archive job-id))))
      (is (not= job-id (:job-id (pipeline/register-job! (:registry-root config) job)))))))
