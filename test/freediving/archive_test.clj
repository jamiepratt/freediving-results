(ns freediving.archive-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [freediving.archive :as archive])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn workspace []
  (str (.toRealPath (Files/createTempDirectory "archive-test-" (make-array FileAttribute 0))
                    (make-array java.nio.file.LinkOption 0))))

(def manifest
  {:sha256 "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
   :discovery-url "https://example.org/results"
   :final-url "https://example.org/results.pdf"
   :acquisition-method "direct HTTP download"
   :retrieved-at "2026-09-23T13:14:09Z"
   :content-type "application/pdf"
   :publisher "Example federation"
   :relationship :publisher
   :mirror-of nil})

(deftest registered-content-preserves-bytes-and-provenance
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")]
    (spit source "abc")
    (let [receipt (archive/register! root source manifest)
          result (archive/inspect root (:sha256 manifest))]
      (is (= (:sha256 manifest) (:sha256 receipt)))
      (is (= "abc" (when-let [path (:artifact-path result)] (slurp path))))
      (is (= [manifest] (mapv :manifest (:acquisitions result)))))))

(deftest malformed-and-mismatched-manifests-do-not-register
  (doseq [bad [(assoc manifest :sha256 (apply str (repeat 64 "0")))
               (dissoc manifest :acquisition-method)
               (assoc manifest :retrieved-at "yesterday")
               (assoc manifest :final-url "../private")
               (assoc manifest :final-url "https://example.org/a?token=private")
               (assoc manifest :discovery-url "https://example.org/a#private")
               (assoc manifest :relationship :mirror)
               (assoc manifest :content-type "")
               (assoc manifest :unexpected "silently lost")]]
    (let [dir (workspace) source (str dir "/source")]
      (spit source "abc")
      (is (thrown? clojure.lang.ExceptionInfo
                   (archive/register! (str dir "/archive") source bad))))))

(deftest duplicate-imports-share-content-and-preserve-distinct-acquisitions
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        mirror (assoc manifest :final-url "https://mirror.example.org/results.pdf"
                      :relationship :mirror :mirror-of "Example federation")]
    (spit source "abc")
    (let [first-receipt (archive/register! root source manifest)
          object (java.io.File. (:artifact-path (archive/inspect root (:sha256 manifest))))]
      (.setLastModified object 1000)
      (is (= first-receipt (archive/register! root source (into (sorted-map) manifest))))
      (is (= 1000 (.lastModified object)))
      (let [second-receipt (archive/register! root source mirror)
            result (archive/inspect root (:sha256 manifest))]
        (is (= (:sha256 first-receipt) (:sha256 second-receipt)))
        (is (not= (:acquisition-id first-receipt) (:acquisition-id second-receipt)))
        (is (= #{manifest mirror} (set (map :manifest (:acquisitions result)))))
        (is (= 1000 (.lastModified object)))))))

(deftest interrupted-registration-resumes-without-duplicating-provenance
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")]
    (spit source "abc")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"interrupted"
                          (archive/register! root source manifest
                                             {:on-progress (fn [_] (throw (ex-info "interrupted" {})))})))
    (is (= "abc" (slurp (:artifact-path (archive/inspect root (:sha256 manifest))))))
    (is (empty? (:acquisitions (archive/inspect root (:sha256 manifest)))))
    (archive/register! root source manifest)
    (archive/register! root source manifest)
    (is (= [manifest] (mapv :manifest (:acquisitions (archive/inspect root (:sha256 manifest))))))))

(deftest corruption-and-unknown-artifacts-fail-explicitly
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")]
    (spit source "abc")
    (archive/register! root source manifest)
    (is (thrown? clojure.lang.ExceptionInfo (archive/inspect root (apply str (repeat 64 "0")))))
    (spit (:artifact-path (archive/inspect root (:sha256 manifest))) "corrupt")
    (is (thrown? clojure.lang.ExceptionInfo (archive/inspect root (:sha256 manifest))))
    (is (thrown? clojure.lang.ExceptionInfo (archive/register! root source manifest)))))

(deftest archive-paths-and-files-remain-private
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")]
    (spit source "abc")
    (is (thrown? clojure.lang.ExceptionInfo (archive/register! (str dir "/../escape") source manifest)))
    (archive/register! root source manifest)
    (doseq [file (file-seq (java.io.File. root))]
      (is (empty? (filter #(re-find #"GROUP|OTHERS" (str %))
                          (Files/getPosixFilePermissions (.toPath file) (make-array java.nio.file.LinkOption 0))))))
    (is (thrown? clojure.lang.ExceptionInfo (archive/inspect root "../../source")))
    (let [link (.toPath (java.io.File. dir "alias"))]
      (Files/createSymbolicLink link (.toPath (java.io.File. root)) (make-array FileAttribute 0))
      (is (thrown? clojure.lang.ExceptionInfo (archive/register! (str link) source manifest)))
      (is (thrown? clojure.lang.ExceptionInfo (archive/inspect (str link) (:sha256 manifest)))))))

(deftest stored-provenance-cannot-be-silently-replaced
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")]
    (spit source "abc")
    (let [receipt (archive/register! root source manifest)
          record (str root "/acquisitions/" (:acquisition-id receipt) ".edn")]
      (spit record (pr-str (assoc manifest :publisher "Changed")))
      (is (thrown? clojure.lang.ExceptionInfo (archive/inspect root (:sha256 manifest))))
      (is (thrown? clojure.lang.ExceptionInfo (archive/register! root source manifest))))))

(defn java [& args]
  (apply shell/sh (str (System/getProperty "java.home") "/bin/java")
         "-cp" (System/getProperty "java.class.path") "clojure.main" args))

(deftest cli-recovers-after-process-termination
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        manifest-file (str dir "/manifest.edn")]
    (spit source "abc")
    (spit manifest-file (pr-str manifest))
    (let [crashed (java "-e" (str "(require '[freediving.archive :as a])"
                                  "(a/register! " (pr-str root) " " (pr-str source) " "
                                  (pr-str manifest)
                                  " {:on-progress (fn [_] (.halt (Runtime/getRuntime) 23))})"))]
      (is (= 23 (:exit crashed))))
    (let [registered (java "-m" "freediving.archive" "import" root source manifest-file)
          inspected (java "-m" "freediving.archive" "inspect" root (:sha256 manifest))]
      (is (= 0 (:exit registered)) (:err registered))
      (is (= (:sha256 manifest) (:sha256 (edn/read-string (:out registered)))))
      (is (= 0 (:exit inspected)) (:err inspected))
      (is (= [manifest] (mapv :manifest (:acquisitions (edn/read-string (:out inspected)))))))))

(deftest symlinks-inside-archive-and-its-parent-are-rejected
  (doseq [entry ["objects" "acquisitions" "tmp" ".lock" :object :record :ancestor]]
    (let [dir (workspace) root (str dir "/archive") source (str dir "/source")]
      (spit source "abc")
      (let [receipt (archive/register! root source manifest)
            relative (case entry
                       :object (str "objects/" (:sha256 manifest))
                       :record (str "acquisitions/" (:acquisition-id receipt) ".edn")
                       :ancestor nil
                       entry)
            target (java.io.File. (if relative (str root "/" relative) dir))
            alias (java.io.File. dir "outside")]
        (if (= entry :ancestor)
          (Files/createSymbolicLink (.toPath alias) (.toPath target) (make-array FileAttribute 0))
          (do
            (Files/move (.toPath target) (.toPath alias) (make-array java.nio.file.CopyOption 0))
            (Files/createSymbolicLink (.toPath target) (.toPath alias) (make-array FileAttribute 0))))
        (let [checked-root (if (= entry :ancestor) (str alias "/archive") root)]
          (is (thrown? clojure.lang.ExceptionInfo (archive/register! checked-root source manifest)) (str entry))
          (is (thrown? clojure.lang.ExceptionInfo (archive/inspect checked-root (:sha256 manifest))) (str entry)))))))

(deftest concurrent-process-imports-are-duplicate-safe
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        manifest-file (str dir "/manifest.edn")]
    (spit source "abc")
    (spit manifest-file (pr-str manifest))
    (let [workers (doall (repeatedly 3 #(future (java "-m" "freediving.archive" "import" root source manifest-file))))
          results (mapv deref workers)]
      (is (= [0 0 0] (mapv :exit results)) (pr-str results))
      (is (= 1 (count (set (map :out results)))))
      (is (= [manifest] (mapv :manifest (:acquisitions (archive/inspect root (:sha256 manifest)))))))))

(deftest manifest-input-requires-exactly-one-form
  (let [dir (workspace) file (str dir "/manifest.edn")]
    (spit file (str (pr-str manifest) " :freediving.archive/eof {:extra true}"))
    (is (thrown? clojure.lang.ExceptionInfo (archive/read-manifest file)))))
