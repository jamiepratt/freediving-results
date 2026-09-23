(ns freediving.legacy-test
  (:require [clojure.string]
            [clojure.edn]
            [clojure.data.json]
            [clojure.test :refer [deftest is]]
            [freediving.legacy :as legacy]
            [freediving.archive-test :as fixtures]))

(def entry {"file" "source" "requested_url" "https://example.org/results"
            "resolved_url" "https://example.org/results.pdf" "fetched_at" "2026-09-23T13:14:09Z"
            "content_type" "application/pdf" "sha256" (:sha256 fixtures/manifest) "bytes" 3})
(def supplement {:values {:acquisition-method "direct HTTP download" :publisher "Example federation"
                          :relationship :publisher :mirror-of nil}
                 :rationale "Synthetic acquisition log identifies publisher and method."})

(deftest normalization-retains-transformation-and-reports-missing-facts
  (let [result (legacy/normalize-entry entry supplement)]
    (is (= :eligible (:status result)))
    (is (= fixtures/manifest (:manifest result)))
    (is (= entry (:original result)))
    (is (= supplement (:supplement result)))
    (is (= "resolved_url" (get-in result [:mapping :final-url]))))
  (let [result (legacy/normalize-entry {"source_url" "https://example.org/results"} nil)]
    (is (= :missing-fields (:status result)))
    (is (some #{:final-url} (:missing-fields result)))
    (is (some #{:content-type} (:missing-fields result)))
    (is (nil? (get-in result [:manifest :final-url])))))

(deftest import-preserves-exact-evidence-and-is-idempotent
  (let [dir (fixtures/workspace) root (str dir "/archive") manifest (str dir "/manifest.json")
        config (str dir "/config.edn")]
    (spit (str dir "/source") "abc")
    (spit manifest (str "[" (clojure.data.json/write-str entry) "]\n"))
    (spit config (pr-str {:version 1 :entries {0 supplement}}))
    (let [first-run (legacy/import! root dir manifest config)
          second-run (legacy/import! root dir manifest config)]
      (is (= {:imported 1 :skipped 0 :rejected 0 :missing-fields 0} (:counts first-run)))
      (is (= {:imported 0 :skipped 1 :rejected 0 :missing-fields 0} (:counts second-run)))
      (is (= (slurp manifest) (slurp (:manifest-path first-run))))
      (is (= (mapv :lineage-id (:entries first-run)) (mapv :lineage-id (:entries second-run)))))
    (spit config (pr-str {:version 1 :entries {0 (assoc supplement :rationale "Additional synthetic evidence.")}}))
    (is (= 1 (get-in (legacy/import! root dir manifest config) [:counts :skipped])))))

(deftest rejected-entries-are-not-dropped-or-repaired
  (let [dir (fixtures/workspace) root (str dir "/archive") manifest (str dir "/manifest.json")
        config (str dir "/config.edn")
        entries [entry (assoc entry "file" "../source") (assoc entry "bytes" 7)
                 (assoc entry "sha256" (apply str (repeat 64 "0")))
                 {"source_url" "https://example.org/a" "archived_at" "2026-09-23T13:14:09Z"}
                 "not an object" (assoc entry "resolved_url" "https://example.org/a?secret=x")]]
    (spit (str dir "/source") "abc")
    (spit manifest (clojure.data.json/write-str entries))
    (spit config (pr-str {:version 1 :entries (into {} (for [i [0 1 2 3 6]] [i supplement]))}))
    (let [result (legacy/import! root dir manifest config)]
      (is (= 7 (count (:entries result))))
      (is (= [:imported :rejected :rejected :rejected :missing-fields :rejected :rejected]
             (mapv :status (:entries result))))
      (is (some #{:retrieved-at} (get-in result [:entries 4 :missing-fields])))
      (is (= :unsafe-source-path (get-in result [:entries 1 :reason])))
      (is (= :source-size-mismatch (get-in result [:entries 2 :reason])))))
  (is (= :rejected (:status (legacy/normalize-entry entry {:values "bad" :rationale "evidence"}))))
  (is (= :rejected (:status (legacy/normalize-entry entry
                                                    (assoc-in supplement [:values :final-url] "https://other.org"))))))

(deftest lineage-is-complete-private-and-integrity-checked
  (let [dir (fixtures/workspace) root (str dir "/archive") manifest (str dir "/manifest.json")
        config (str dir "/config.edn")]
    (spit (str dir "/source") "abc")
    (spit manifest (clojure.data.json/write-str [entry]))
    (spit config (pr-str {:version 1 :entries {0 supplement}}))
    (let [first-run (binding [*print-length* 1 *print-level* 1]
                      (legacy/import! root dir manifest config))
          lineage (clojure.edn/read-string (slurp (get-in first-run [:entries 0 :lineage-path])))]
      (is (= entry (get-in lineage [:transformation :original])))
      (is (= fixtures/manifest (get-in lineage [:transformation :manifest])))
      (spit config (pr-str {:version 1 :entries {0 (assoc supplement :rationale "Another recorded basis.")}}))
      (let [second-run (legacy/import! root dir manifest config)]
        (is (not= (get-in first-run [:entries 0 :lineage-id]) (get-in second-run [:entries 0 :lineage-id])))
        (is (= (get-in first-run [:entries 0 :acquisition-id]) (get-in second-run [:entries 0 :acquisition-id]))))
      (doseq [file (file-seq (java.io.File. root))]
        (is (empty? (filter #(re-find #"GROUP|OTHERS" (str %))
                            (java.nio.file.Files/getPosixFilePermissions (.toPath file) (make-array java.nio.file.LinkOption 0))))))
      (spit (:manifest-path first-run) "tampered")
      (is (thrown? clojure.lang.ExceptionInfo (legacy/import! root dir manifest config))))))

(deftest cli-rejects-trailing-json-without-echoing-evidence
  (let [dir (fixtures/workspace) manifest (str dir "/manifest.json") config (str dir "/config.edn")]
    (spit manifest "[] [\"private trailing content\"]")
    (spit config "{:version 1 :entries {}}")
    (let [result (fixtures/java "-m" "freediving.legacy" (str dir "/archive") dir manifest config)]
      (is (= 1 (:exit result)))
      (is (= {:status :failed :reason :invalid-json} (clojure.edn/read-string (:err result))))
      (is (not (clojure.string/includes? (str (:out result) (:err result)) "private trailing content"))))))
