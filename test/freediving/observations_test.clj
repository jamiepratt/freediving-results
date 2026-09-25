(ns freediving.observations-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [freediving.aida-html :as html]
            [freediving.aida-html-test :as html-fixture]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.extraction-test :as extraction-fixture]
            [freediving.archive-test :as fixture]
            [freediving.observations :as observations])
  (:import [java.sql DriverManager]
           [java.security MessageDigest]
           [java.util HexFormat]))
(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))
(defn sql! [url sql]
  (with-open [c (DriverManager/getConnection url) s (.createStatement c)] (.execute s sql)))
(use-fixtures :each (fn [f]
                      (when-not (and admin app) (throw (ex-info "Run scripts/test-postgres.sh; isolated PostgreSQL required" {})))
                      (sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! admin "observations_app") (f)))
(defn canonical [v]
  (cond (map? v) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) v))
        (sequential? v) (mapv canonical v) :else v))
(defn hash-value [v]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (pr-str (canonical v)) "UTF-8"))))
(defn synthetic [schema parser]
  ;; Authority fixtures intentionally construct versioned legacy artifacts. Register a
  ;; valid synthetic PDF so the import boundary can inspect its source family.
  (let [[root digest] (extraction-fixture/registered-pdf)
        identity {:source-sha256 digest
                  :acquisitions (:acquisitions (archive/inspect root digest))
                  :evidence-sha256 [] :actor "synthetic-test" :config {:layout true}
                  :parser-version parser :schema-version schema :pdfinfo-version "test"
                  :tool {:name "pdftotext" :version "test" :arguments ["-layout"]}}
        lines ["  Éxample  001  " "  Éxample  002  "]
        candidates (mapv (fn [n line] {:coordinates {:page 1 :line (inc n) :column-start 1 :column-end (inc (count line))}
                                       :raw {:line line :fields {:source-name "Éxample" :performance (format "%03d" (inc n))}}
                                       :parsed {:source-name "Éxample" :performance (inc n) :unit nil}
                                       :parse-status :parsed :review-status :unreviewed
                                       :future/unknown {:value 1/3 :tokens [nil "  " :x/y]}}) (range) lines)]
    {:root root :artifact (merge identity {:job-id (hash-value identity) :processed-at "2026-09-23T12:00:00Z"
                                           :pages [{:page 1 :text (str/join "\n" lines)
                                                    :lines (mapv (fn [n line] {:line (inc n) :text line}) (range) lines)}]
                                           :pdf-page-count 1 :raw-text (str/join "\n" lines)
                                           :candidates candidates :publication {:status :blocked}})}))
(defn publish! [{:keys [root artifact]}]
  (archive/derive! root (:job-id artifact) (constantly artifact) nil))
(deftest immutable-versioned-observations-retain-exact-evidence
  (let [{:keys [root artifact] :as fixture} (synthetic 1 "cmas-cwt-men/1") receipt (publish! fixture)
        job (:job-id artifact)]
    (is (= :created (:status (observations/import! app root job))))
    (is (= :skipped (:status (observations/import! app root job))))
    (is (= artifact (:artifact (observations/inspect app job))))
    (is (= (seq (archive/read-source-bytes (:artifact-path receipt)))
           (seq (:artifact-bytes (observations/inspect app job)))))
    (is (= (:candidates artifact) (mapv :payload (:observations (observations/inspect app job)))))
    (is (= {:sources 1 :versions 1 :observations 2 :candidates 2 :result-rows 2 :fragments 0 :unclassified 0} (observations/counts app)))
    (let [version (assoc artifact :parser-version "cmas-cwt-men/2")
          version (assoc version :job-id (hash-value (select-keys version observations/identity-keys)))]
      (publish! {:root root :artifact version})
      (is (= :created (:status (observations/import! app root (:job-id version)))))
      (is (= 2 (:candidates (observations/counts app))))
      (is (= 4 (:observations (observations/counts app))))
      (is (= 2 (count (observations/list-extractions app)))))
    (doseq [table ["extractions" "observations"] op ["UPDATE %s SET job_id=job_id" "DELETE FROM %s" "TRUNCATE %s"]]
      (is (thrown? java.sql.SQLException (sql! app (format op (str "freediving." table))))))
    (is (= 4 (:observations (observations/counts app))))))
(deftest failed-transactions-retry-and-concurrent-reruns
  (let [{:keys [root artifact] :as fixture} (synthetic 2 "aida-test/1") job (:job-id artifact)]
    (publish! fixture)
    (is (thrown-with-msg? Exception #"interrupt" (observations/import! app root job {:on-progress (fn [_] (throw (ex-info "interrupt" {})))})))
    (is (= 0 (:versions (observations/counts app))))
    (is (= 0 (:observations (observations/counts app))))
    (let [gate (promise) workers (doall (repeatedly 5 #(future @gate (observations/import! app root job))))]
      (deliver gate true)
      (is (= {:created 1 :skipped 4} (frequencies (mapv (comp :status deref) workers)))))
    (is (= 2 (:observations (observations/counts app))))))
(deftest invalid-artifacts-never-leave-partial-observations
  (doseq [[change error] [[#(assoc % :schema-version 99) #"Unsupported"]
                          [#(assoc % :job-id (apply str (repeat 64 "0"))) #"identity"]
                          [#(assoc-in % [:candidates 1 :coordinates :page] 2) #"page"]]]
    (let [{:keys [root artifact]} (synthetic 1 "cmas-test/1") artifact (change artifact)]
      (publish! {:root root :artifact artifact})
      (is (thrown-with-msg? Exception error (observations/import! app root (:job-id artifact))))
      (is (= 0 (:observations (observations/counts app)))))))
(deftest abrupt-process-death-rolls-back
  (let [{:keys [root artifact] :as fixture} (synthetic 3 "cmas-athens-pool/6") job (:job-id artifact)]
    (publish! fixture)
    (let [code (str "(require '[freediving.observations :as o]) (o/import! " (pr-str app) " " (pr-str root) " " (pr-str job)
                    " {:on-progress (fn [_] (.halt (Runtime/getRuntime) 23))})")
          result (shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" code)]
      (is (= 23 (:exit result))))
    (is (= 0 (:observations (observations/counts app))))
    (is (= 0 (:versions (observations/counts app))))
    (is (= :created (:status (observations/import! app root job))))))
(deftest conflicts-and-tampering-rejected
  (let [{:keys [root artifact] :as fixture} (synthetic 1 "cmas-test/1")
        receipt (publish! fixture) job (:job-id artifact)]
    (observations/import! app root job)
    ;; Application role can append but forged/incomplete writes must never count as an idempotent import.
    (sql! admin "ALTER TABLE freediving.observations DISABLE TRIGGER immutable_observations")
    (sql! admin "DELETE FROM freediving.observations WHERE ordinal=1")
    (is (thrown-with-msg? Exception #"Conflicting" (observations/import! app root job)))
    (spit (:artifact-path receipt) "corrupt")
    (is (thrown-with-msg? Exception #"integrity" (observations/import! app root job)))))
(deftest concurrent-versions-and-fragment-provenance
  (let [{:keys [root artifact]} (synthetic 3 "cmas-athens-pool/6")
        lines ["fi" "unknown header"]
        a (-> artifact
              (assoc :raw-text (str/join "\n" lines)
                     :pages [{:page 1 :text (str/join "\n" lines) :lines [{:line 1 :text "fi"} {:line 2 :text "unknown header"}]}])
              (assoc :candidates (mapv (fn [n text] {:coordinates {:page 1 :line (inc n)} :raw {:line text :fields nil}
                                                     :parsed nil :parse-status :unparsed :review-status :unreviewed
                                                     :unresolved-reasons [:owner-review-required :unparsed-source-line]}) (range) lines)))
        b (assoc a :parser-version "cmas-athens-pool/5")
        b (assoc b :job-id (hash-value (select-keys b observations/identity-keys)))
        gate (promise)]
    (publish! {:root root :artifact a}) (publish! {:root root :artifact b})
    (let [workers (mapv (fn [v] (future @gate (observations/import! app root (:job-id v)))) [a b])]
      (deliver gate true) (is (= [:created :created] (mapv (comp :status deref) workers))))
    (is (= {:sources 1 :versions 2 :observations 4 :candidates 2 :result-rows 0 :fragments 2 :unclassified 2} (observations/counts app)))
    (is (= (:candidates a) (mapv :payload (:observations (observations/inspect app (:job-id a))))))))
(deftest same-job-different-output-conflicts
  (let [{:keys [root artifact] :as fixture} (synthetic 1 "cmas-test/1") receipt (publish! fixture)]
    (observations/import! app root (:job-id artifact))
    ;; Retain both content-addressed artifacts but point completed receipt to changed output under the same job.
    (let [changed (assoc artifact :processed-at "2026-09-23T13:00:00Z") bytes (.getBytes (pr-str changed) "UTF-8")
          sha (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))]
      (spit (str root "/derived-objects/" sha) (pr-str changed))
      (spit (str root "/derivations/" (:job-id artifact) ".edn") (pr-str (assoc (dissoc receipt :artifact-path :run-status) :artifact-sha256 sha)))
      (is (thrown-with-msg? Exception #"Conflicting" (observations/import! app root (:job-id artifact))))
      (is (= 1 (:versions (observations/counts app)))))))
(deftest real-parser-multiline-artifacts-retain-evidence
  (doseq [[schema text] [[2 (str extraction-fixture/aida-header "     Éva\n1.   Example-   AIN   150 m   1m   75.5   0\n     Test\n")]
                         [3 (str (str/replace extraction-fixture/dyn-header "# Name & surname Country Realized Final Notes" "# Name & surname Country     Realized     Final        Notes")
                                 (apply str (repeat 60 " ")) "GOLD MEDAL,\n1 Synthetic NAME GBR          210,5       210,5\n"
                                 (apply str (repeat 60 " ")) "WORLD RECORD SENIORS\n")]]]
    (let [parsed (extraction/parse-pages [text])
          {:keys [root artifact]} (synthetic schema (:parser-version parsed))
          a (merge artifact parsed {:raw-text text})]
      (publish! {:root root :artifact a})
      (is (= :created (:status (observations/import! app root (:job-id a)))))
      (is (= (:candidates a) (mapv :payload (:observations (observations/inspect app (:job-id a)))))))))
(deftest malformed-processing-provenance-rejected
  (doseq [change [#(dissoc % :processed-at) #(update % :tool dissoc :version)
                  #(assoc % :processed-at "yesterday") #(assoc-in % [:tool :arguments] [1])
                  #(assoc-in % [:publication :status] :approved)]]
    (let [{:keys [root artifact]} (synthetic 1 "cmas-test/1")
          a (change artifact) a (assoc a :job-id (hash-value (select-keys a observations/identity-keys)))]
      (publish! {:root root :artifact a})
      (is (thrown-with-msg? Exception #"Malformed" (observations/import! app root (:job-id a))))
      (is (= 0 (:versions (observations/counts app)))))))
(deftest application-table-owner-cannot-migrate
  (sql! admin "ALTER TABLE freediving.observations OWNER TO observations_app")
  (is (thrown-with-msg? Exception #"restricted" (observations/migrate! admin "observations_app"))))
(deftest unsafe-application-role-rejected
  (is (thrown-with-msg? Exception #"restricted" (observations/migrate! admin (with-open [c (DriverManager/getConnection admin) s (.createStatement c) r (.executeQuery s "SELECT current_user")] (.next r) (.getString r 1))))))
(defn -main [& _]
  (let [result (clojure.test/run-tests 'freediving.observations-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

(deftest html-import-is-replay-validated-idempotent-and-versioned
  (let [dir (fixture/workspace) root (str dir "/archive") file (str dir "/source.html")
        hash (html-fixture/register-html root file (html-fixture/document html-fixture/cells))
        receipt (html/extract! root hash {:actor "synthetic" :config {}})
        job (:job-id receipt)]
    (is (= :created (:status (observations/import! app root job))))
    (is (= :skipped (:status (observations/import! app root job))))
    (is (= {:table 1 :row 2} (get-in (observations/inspect app job) [:observations 0 :payload :coordinates])))
    (let [hash2 (html-fixture/register-html root file (html-fixture/document (assoc html-fixture/cells 7 "1 m")))
          job2 (:job-id (html/extract! root hash2 {:actor "synthetic" :config {}}))]
      (is (= :created (:status (observations/import! app root job2))))
      (is (= 2 (:sources (observations/counts app))))
      (is (= 2 (:observations (observations/counts app))))
      (is (= "0 m" (get-in (observations/inspect app job) [:observations 0 :payload :parsed :realised-performance]))))
    (let [a (:artifact (observations/inspect app job))
          bad (-> a (assoc :actor "altered") (assoc-in [:candidates 0 :parsed :points] "99"))
          bad (assoc bad :job-id (html/digest (select-keys bad html/identity-keys)))]
      (archive/derive! root (:job-id bad) (constantly bad) nil)
      (is (thrown-with-msg? Exception #"HTML source replay" (observations/import! app root (:job-id bad))))
      (is (= 2 (:observations (observations/counts app)))))))

(deftest html-migration-upgrades-original-constraint-without-changing-pdf-replay
  (let [{:keys [root artifact]} (synthetic 1 "cmas-test/1")
        artifact (assoc-in artifact [:candidates 0 :coordinates :table] 99)
        artifact (assoc-in artifact [:candidates 0 :coordinates :row] 99)]
    (publish! {:root root :artifact artifact})
    (observations/import! app root (:job-id artifact))
    (let [before (observations/inspect app (:job-id artifact))]
      (sql! admin "DELETE FROM freediving.schema_migrations WHERE version=7")
      (sql! admin "ALTER TABLE freediving.extractions DROP CONSTRAINT extractions_schema_version_check; ALTER TABLE freediving.extractions ADD CONSTRAINT extractions_schema_version_check CHECK(schema_version IN(1,2,3))")
      (observations/migrate! admin "observations_app")
      (observations/migrate! admin "observations_app")
      (is (= :skipped (:status (observations/import! app root (:job-id artifact)))))
      (is (= (:observations before) (:observations (observations/inspect app (:job-id artifact)))))
      (is (= (hash-value [(:source-sha256 artifact) [{:page 1 :line 1}]])
             (get-in before [:observations 0 :candidate_id]))))))
