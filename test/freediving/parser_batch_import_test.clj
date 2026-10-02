(ns freediving.parser-batch-import-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.archive :as archive]
            [freediving.archive-test :as archive-fixture]
            [freediving.aida-html-test :as html-fixture]
            [freediving.parser-batch-import :as batch-import]
            [freediving.retained-pdf-test :as pdf-fixture]
            [freediving.retained-json-test :as json-fixture])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- store-root []
  (str (.toRealPath (Files/createTempDirectory "parser-batch-import-"
                                               (make-array FileAttribute 0))
                    (make-array java.nio.file.LinkOption 0))))

(defn- register-source! [root source]
  (let [path (str root "/original.json")
        bytes (get-in source [:retained-input :bytes])]
    (Files/write (.toPath (java.io.File. path)) bytes
                 (make-array java.nio.file.OpenOption 0))
    (archive/register! root path
                       (assoc archive-fixture/manifest
                              :sha256 (:hash source)
                              :discovery-url "https://cmas-api.microplustimingservices.com/api/units/3533/results"
                              :final-url "https://cmas-api.microplustimingservices.com/api/units/3533/results"
                              :content-type "application/json"))))

(defn- sha256 [bytes]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))

(defn- register-html! [root source]
  (let [bytes (.getBytes source "UTF-8")
        hash (sha256 bytes)
        path (str root "/original.html")]
    (Files/write (.toPath (java.io.File. path)) bytes
                 (make-array java.nio.file.OpenOption 0))
    (archive/register! root path
                       (assoc archive-fixture/manifest :sha256 hash
                              :final-url "https://example.org/results.html"
                              :content-type "text/html; charset=UTF-8"))
    hash))

(deftest verified-json-replay-imports-exact-evidence-once
  (let [root (store-root)
        source (json-fixture/fixture [(json-fixture/result-row 101)
                                      (json-fixture/result-row 102)])
        entry (select-keys source [:document :retained-input])
        _ (register-source! root source)
        first-run (batch-import/import-registered-batch! root [entry entry])
        second-run (batch-import/import-registered-batch! root [entry])
        snapshot (batch-import/inspect root)]
    (is (= 2 (get-in first-run [:metrics :imported-observations])))
    (is (= 0 (get-in second-run [:metrics :imported-observations])))
    (is (= 2 (count (:observations snapshot))))
    (is (= #{"json-pointer=/0" "json-pointer=/1"}
           (set (map :position-id (:observations snapshot)))))
    (is (= (set (map :citation (get-in entry [:document :positions])))
           (set (map :citation (:observations snapshot)))))
    (is (every? #(= (:source-sha256 (:document entry)) (:source-sha256 %))
                (:observations snapshot)))
    (is (every? #(= :unreviewed (:status %))
                (:observations snapshot)))
    (is (= 0 (get-in first-run [:metrics :requests])))
    (is (= 0 (get-in first-run [:metrics :cache-reuses])))
    (is (= 2 (get-in first-run [:metrics :coverage :routed])))
    (is (= 0 (get-in first-run [:metrics :llm-exceptions])))))

(deftest missing-archived-source-remains-an-exception
  (let [root (store-root)
        source (json-fixture/fixture [(json-fixture/result-row 101)])
        result (batch-import/import-registered-batch!
                root [(select-keys source [:document :retained-input])])
        snapshot (batch-import/inspect root)]
    (is (zero? (get-in result [:metrics :imported-observations])))
    (is (empty? (:observations snapshot)))
    (is (some #(= :missing-or-invalid-archived-source (:reason %))
              (:unresolved-exceptions snapshot)))))

(deftest html-replay-retains-native-name-and-exact-source-row
  (let [root (store-root)
        source (html-fixture/document html-fixture/cells)
        hash (register-html! root source)
        row-id "table=1&row=2"
        document {:source-sha256 hash :format :html
                  :positions [{:id row-id :citation (str "sha256:" hash "#" row-id)
                               :coordinates {:table 1 :row 2}}]}
        result (batch-import/import-registered-batch!
                root [{:document document :retained-input {:html source}}])
        observation (first (:observations (batch-import/inspect root)))]
    (is (= 1 (get-in result [:metrics :imported-observations])))
    (is (= "ÉXAMPLE  & Person" (get-in observation [:candidate :parsed :source-name])))
    (is (= :result-row (:evidence-role observation)))
    (is (= (get-in document [:positions 0 :citation]) (:citation observation)))))

(deftest interrupted-position-derivation-resumes-without-duplicate
  (let [root (store-root)
        source (json-fixture/fixture [(json-fixture/result-row 101)])
        entry (select-keys source [:document :retained-input])]
    (register-source! root source)
    (is (thrown? Exception
                 (batch-import/import-registered-batch!
                  root [entry]
                  {:on-progress (fn [{:keys [phase]}]
                                  (when (= :extraction-artifact-ready phase)
                                    (throw (ex-info "interrupted" {}))))})))
    (let [again (batch-import/import-registered-batch! root [entry])]
      (is (= 1 (get-in again [:metrics :imported-observations])))
      (is (= 1 (count (:observations (batch-import/inspect root))))))))

(deftest changed-source-version-coexists
  (let [root (store-root)
        first-source (json-fixture/fixture [(json-fixture/result-row 101)])
        second-source (json-fixture/fixture [(json-fixture/result-row 102)])
        first-entry (select-keys first-source [:document :retained-input])
        second-entry (select-keys second-source [:document :retained-input])]
    (register-source! root first-source)
    (batch-import/import-registered-batch! root [first-entry])
    (register-source! root second-source)
    (batch-import/import-registered-batch! root [second-entry])
    (is (= #{(:hash first-source) (:hash second-source)}
           (set (map :source-sha256 (:observations (batch-import/inspect root))))))))

(deftest archived-pdf-without-supported-signature-retains-explicit-gap
  (let [{:keys [root hash]} (pdf-fixture/fixture)
        result (batch-import/import-registered-batch!
                root [{:document {:source-sha256 hash :format :pdf :positions []}
                       :archive-root root}])]
    (is (zero? (get-in result [:metrics :imported-observations])))
    (is (empty? (:observations (batch-import/inspect root))))
    (is (some #(= :unsupported-or-unverified-pdf-source (:reason %))
              (:unresolved-exceptions (batch-import/inspect root))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.parser-batch-import-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
