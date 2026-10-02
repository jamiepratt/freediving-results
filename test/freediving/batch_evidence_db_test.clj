(ns freediving.batch-evidence-db-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [freediving.aida-html :as aida-html]
            [freediving.archive :as archive]
            [freediving.archive-test :as archive-fixture]
            [freediving.batch-evidence-db :as db]
            [freediving.parser-batch-import :as replay]
            [freediving.retained-json-test :as json-fixture])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.sql DriverManager]))

(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))
(defn sql! [url sql]
  (with-open [c (DriverManager/getConnection url) s (.createStatement c)] (.execute s sql)))
(use-fixtures :each
  (fn [f]
    (when-not (and admin app) (throw (ex-info "Run scripts/test-postgres.sh" {})))
    (sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (db/migrate! admin "observations_app")
    (f)))

(defn fixture! [rows]
  (let [root (str (.toRealPath (Files/createTempDirectory "batch-evidence-db-" (make-array FileAttribute 0))
                               (make-array java.nio.file.LinkOption 0)))
        source (json-fixture/fixture rows)
        path (str root "/original.json")]
    (Files/write (.toPath (java.io.File. path)) (get-in source [:retained-input :bytes])
                 (make-array java.nio.file.OpenOption 0))
    (archive/register! root path
                       (assoc archive-fixture/manifest :sha256 (:hash source)
                              :discovery-url "https://cmas-api.microplustimingservices.com/api/units/3533/results"
                              :final-url "https://cmas-api.microplustimingservices.com/api/units/3533/results"
                              :content-type "application/json"))
    (replay/import-registered-batch! root [(select-keys source [:document :retained-input])])
    root))

(deftest imports-cited-position-once-and-preserves-acquisition
  (let [root (fixture! [(json-fixture/result-row 101)])
        source (first (:observations (replay/inspect root)))]
    (is (= {:created 1 :skipped 0} (select-keys (db/import! app root) [:created :skipped])))
    (is (= {:created 0 :skipped 1} (select-keys (db/import! app root) [:created :skipped])))
    (let [item (first (db/inspect app))]
      (is (= (:source-sha256 source) (:source-sha256 item)))
      (is (= (:citation source) (:citation item)))
      (is (= (:position-id source) (:position-id item)))
      (is (= (:parser-version source) (:parser-version item)))
      (is (= :result-row (:evidence-role item)))
      (is (= 1 (count (:acquisitions item)))))))

(deftest failed-transaction-rolls-back-and-resumes
  (let [root (fixture! [(json-fixture/result-row 101) (json-fixture/result-row 102)])]
    (is (thrown-with-msg? Exception #"interrupt"
                          (db/import! app root {:on-progress (fn [_] (throw (ex-info "interrupt" {})))})))
    (is (empty? (db/inspect app)))
    (is (= 2 (:created (db/import! app root))))
    (is (= 2 (:skipped (db/import! app root))))))

(deftest later-acquisition-of-same-bytes-does-not-revise-position
  (let [root (fixture! [(json-fixture/result-row 101)])
        source (first (:observations (replay/inspect root)))
        manifest (assoc archive-fixture/manifest
                        :sha256 (:source-sha256 source)
                        :discovery-url "https://cmas-api.microplustimingservices.com/api/units/3533/results"
                        :final-url "https://cmas-api.microplustimingservices.com/api/units/3533/results"
                        :content-type "application/json"
                        :retrieved-at "2026-09-24T13:14:09Z")]
    (is (= 1 (:created (db/import! app root))))
    (archive/register! root (str root "/original.json") manifest)
    (is (= 1 (:skipped (db/import! app root))))
    (is (= 1 (count (:acquisitions (first (db/inspect app))))))))

(deftest forged-but-hash-valid-derivation-fails-closed
  (let [root (fixture! [(json-fixture/result-row 101)])
        original (first (:observations (replay/inspect root)))
        forged (assoc original :job-id (apply str (repeat 64 "f")))]
    (archive/derive! root (:job-id forged) (constantly forged) nil)
    (is (thrown-with-msg? Exception #"invalid-batch-evidence" (db/import! app root)))
    (is (empty? (db/inspect app)))))

(deftest restricted-role-cannot-change-imported-evidence
  (let [root (fixture! [(json-fixture/result-row 101)])]
    (db/import! app root)
    (doseq [statement ["UPDATE freediving.batch_position_evidence SET revision=revision"
                       "DELETE FROM freediving.batch_position_evidence"
                       "TRUNCATE freediving.batch_position_evidence"]]
      (is (thrown? java.sql.SQLException (sql! app statement))))
    (is (= 1 (count (db/inspect app))))))

(deftest rankings-stay-evidence-and-new-source-bytes-stay-distinct
  (let [root (fixture! [(json-fixture/result-row 101)])
        html (str "<select id='discipline'><option selected>DYN</option></select>"
                  "<select id='gender'><option selected>Male</option></select>"
                  "<table><tr>" (apply str (map #(str "<th>" % "</th>") aida-html/ranking-headers))
                  "</tr><tr><td></td><td>1</td><td>Synthetic Person</td><td>AIN</td>"
                  "<td>100 m</td><td>90 m</td><td>50</td><td>0</td></tr></table>")
        bytes (.getBytes html "UTF-8")
        hash (.formatHex (java.util.HexFormat/of)
                         (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))
        path (str root "/ranking.html")
        id "table=1&row=2"
        document {:source-sha256 hash :format :html
                  :positions [{:id id :citation (str "sha256:" hash "#" id)
                               :coordinates {:table 1 :row 2}}]}]
    (Files/write (.toPath (java.io.File. path)) bytes (make-array java.nio.file.OpenOption 0))
    (archive/register! root path
                       (assoc archive-fixture/manifest :sha256 hash
                              :final-url "https://example.org/results.html"
                              :content-type "text/html; charset=UTF-8"))
    (replay/import-registered-batch! root [{:document document :retained-input {:html html}}])
    (is (= 2 (:created (db/import! app root))))
    (is (= #{:batch-observation :batch-evidence} (set (map :kind (db/inspect app)))))
    (is (= :event-ranking (:evidence-role (first (filter #(= :batch-evidence (:kind %))
                                                         (db/inspect app))))))
    (is (= 2 (count (set (map :source-sha256 (db/inspect app))))))
    (is (= 2 (:skipped (db/import! app root))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.batch-evidence-db-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
