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

;; Synthetic acquisition context, never copied from a real athlete table.
(deftest structured-provenance-is-preserved-and-order-independent
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        provenance {:publisher-url "https://example.org/"
                    :redirect-chain ["https://example.org/results.pdf"]}
        contextual (assoc manifest :provenance provenance)]
    (spit source "abc")
    (let [receipt (archive/register! root source contextual)]
      (is (= receipt (archive/register! root source
                                        (assoc contextual :provenance (into (sorted-map) provenance)))))
      (is (= [contextual] (mapv :manifest (:acquisitions (archive/inspect root (:sha256 manifest)))))))))

(deftest browser-context-is-bound-to-verified-retained-evidence
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        evidence (archive/retain-evidence! root (.getBytes "synthetic rendered DOM" "UTF-8"))
        browser {:selected-date "2025-06-28" :filters {} :representation :rendered-dom
                 :rendered-sha256 (:sha256 evidence)}
        contextual (assoc manifest :provenance
                          {:publisher-url "https://example.org/"
                           :redirect-chain [(:final-url manifest)]
                           :browser-state browser})]
    (spit source "abc")
    (let [first-receipt (archive/register! root source contextual)
          different-date (assoc-in contextual [:provenance :browser-state :selected-date] "2025-07-02")]
      (is (= first-receipt (archive/register! root source contextual)))
      (is (not= (:acquisition-id first-receipt)
                (:acquisition-id (archive/register! root source different-date))))
      (is (= 2 (count (:acquisitions (archive/inspect root (:sha256 manifest)))))))
    (spit (:path evidence) "tampered DOM")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SHA-256 mismatch"
                          (archive/inspect root (:sha256 manifest))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SHA-256 mismatch"
                          (archive/register! root source contextual)))))

(deftest official-timing-route-context-is-preserved
  (doseq [url ["https://results-ws.microplustimingservices.com/CMAS/Results/#/2/schedule-bydate"
               "https://cmas.microplustimingservices.com/#/competition-schedule/3"
               "https://cmas.microplustimingservices.com/#/competition-schedule/30"]]
    (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
          contextual (assoc manifest :final-url url :provenance
                            {:publisher-url "https://www.cmas.org/"
                             :redirect-chain ["https://www.cmas.org/document/download.html" url]})]
      (spit source "abc")
      (archive/register! root source contextual)
      (is (= [contextual] (mapv :manifest (:acquisitions (archive/inspect root (:sha256 manifest)))))))))

(deftest unsafe-or-unsupported-provenance-is-rejected
  (let [provenance {:publisher-url "https://example.org/" :redirect-chain [(:final-url manifest)]}
        browser {:selected-date "2025-06-28" :filters {} :representation :rendered-dom
                 :rendered-sha256 (:sha256 manifest)}]
    (doseq [bad [(assoc provenance :secret "credential")
                 (assoc provenance :publisher-url "https://user:password@example.org/")
                 (assoc provenance :redirect-chain [])
                 (assoc provenance :redirect-chain ["https://example.org/other"])
                 (assoc provenance :redirect-chain ["https://example.org/?token=secret" (:final-url manifest)])
                 (assoc provenance :browser-state (assoc browser :cookies "secret"))
                 (assoc provenance :browser-state (assoc browser :selected-date "2025-02-30"))
                 (assoc provenance :browser-state (assoc browser :filters {:session "secret"}))
                 (assoc provenance :browser-state (assoc browser :filters {:discipline "secret"}))
                 (assoc provenance :browser-state (assoc browser :representation :response-html))
                 (assoc provenance :browser-state (assoc browser :rendered-sha256 "missing"))
                 nil]]
      (let [dir (workspace) source (str dir "/source")]
        (spit source "abc")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed manifest"
                              (archive/register! (str dir "/archive") source (assoc manifest :provenance bad))))))
    (doseq [url ["https://cmas.microplustimingservices.com/#/competition-schedule/3?token=secret"
                 "https://cmas.microplustimingservices.com/?token=secret#/competition-schedule/3"
                 "https://cmas.microplustimingservices.com/#/competition-schedule/secret"
                 "https://cmas.microplustimingservices.com:8443/#/competition-schedule/3"
                 "https://cmas.microplustimingservices.com.evil.org/#/competition-schedule/3"
                 "https://cmas.microplustimingservices.com/other#/competition-schedule/3"
                 "http://cmas.microplustimingservices.com/#/competition-schedule/3"
                 "https://results-ws.microplustimingservices.com/CMAS/Results/#/2/schedule-bydate/secret"]]
      (let [dir (workspace) source (str dir "/source")]
        (spit source "abc")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed manifest"
                              (archive/register! (str dir "/archive") source (assoc manifest :final-url url))))))))

(deftest missing-browser-evidence-and-context-tampering-are-rejected
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        contextual (assoc manifest :provenance
                          {:publisher-url "https://example.org/"
                           :redirect-chain [(:final-url manifest)]
                           :browser-state {:selected-date "2025-06-28" :filters {}
                                           :representation :rendered-dom
                                           :rendered-sha256 (:sha256 manifest)}})]
    (spit source "abc")
    (is (thrown? clojure.lang.ExceptionInfo (archive/register! root source contextual)))
    (archive/retain-evidence! root (.getBytes "abc" "UTF-8"))
    (let [receipt (archive/register! root source contextual)
          record (str root "/acquisitions/" (:acquisition-id receipt) ".edn")]
      (spit record (pr-str (assoc-in contextual [:provenance :browser-state :selected-date] "2025-07-02")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity mismatch"
                            (archive/inspect root (:sha256 manifest)))))))

(deftest changed-source-retains-both-versions-and-original-manifest-identity
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        changed (assoc manifest :sha256 "cb8379ac2098aa165029e3938a51da0bcecfc008fd6795f401178647f96c5b34")
        old-id (.formatHex (java.util.HexFormat/of)
                           (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                    (.getBytes (pr-str (into (sorted-map) manifest)) "UTF-8")))]
    (spit source "abc")
    (is (= old-id (:acquisition-id (archive/register! root source manifest))))
    (spit source "def")
    (archive/register! root source changed)
    (is (= "abc" (slurp (:artifact-path (archive/inspect root (:sha256 manifest))))))
    (is (= "def" (slurp (:artifact-path (archive/inspect root (:sha256 changed))))))
    (is (= :skipped (:status (archive/register! root source changed {:report-status true}))))))

(deftest observed-aida-session-links-preserve-context
  (doseq [url ["https://www.aidainternational.org/StartList/4350#start"
               "https://www.aidainternational.org/StartList/4350?day_index=3"
               "https://www.aidainternational.org/StartList/4350?day_index=12"]]
    (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
          contextual (assoc manifest :discovery-url url :final-url url :provenance
                            {:publisher-url "https://example.org/" :redirect-chain [url]})]
      (spit source "abc")
      (let [receipt (archive/register! root source contextual)]
        (is (= receipt (archive/register! root source contextual)))
        (is (= [contextual] (mapv :manifest (:acquisitions (archive/inspect root (:sha256 manifest))))))))))

(deftest observed-cmas-result-link-preserves-context
  (let [dir (workspace) root (str dir "/archive") source (str dir "/source")
        url "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/655/588/3559/result"
        contextual (assoc manifest :final-url url :provenance
                          {:publisher-url "https://www.cmas.org/" :redirect-chain [url]})]
    (spit source "abc")
    (let [receipt (archive/register! root source contextual)]
      (is (= receipt (archive/register! root source contextual)))
      (is (= [contextual] (mapv :manifest (:acquisitions (archive/inspect root (:sha256 manifest)))))))))

(deftest session-and-result-route-allowlists-reject-unobserved-context
  (doseq [url (concat
               (map #(str "https://www.aidainternational.org/StartList/4350" %)
                    ["?day_index=3&token=secret" "?day_index=3&day_index=4"
                     "?%64ay_index=3" "?day_index=%33" "?day_index=-3"
                     "?day_index=3#start" "?day_index=3;token=secret"
                     "?day_index=" "?token=3" "#secret" "#%73tart"])
               ["https://www.aidainternational.org:8443/StartList/4350?day_index=3"
                "http://www.aidainternational.org/StartList/4350?day_index=3"
                "https://www.aidainternational.org.evil.org/StartList/4350?day_index=3"
                "https://user:secret@www.aidainternational.org/StartList/4350?day_index=3"
                "https://www.aidainternational.org/EventPage/4350?day_index=3"
                "https://www.aidainternational.org/StartList/%34%33%35%30?day_index=3"
                "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/655/588/result"
                "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/655/588/3559/4/result"
                "https://cmas.microplustimingservices.com/#/event-detail/OTHER/30/110/655/588/3559/result"
                "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/655/588/token/result"
                "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/655/588/3559/result?token=secret"
                "https://cmas.microplustimingservices.com/?token=secret#/event-detail/FRD/30/110/655/588/3559/result"])]
    (let [dir (workspace) source (str dir "/source")]
      (spit source "abc")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed manifest"
                            (archive/register! (str dir "/archive") source (assoc manifest :final-url url)))
          url))))
