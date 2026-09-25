(ns freediving.candidates-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.candidates :as c]))
(defn row [job ordinal source name]
  {:kind "result-row" :job-id job :ordinal ordinal :candidate-id (str job ordinal) :source-sha256 source
   :artifact-sha256 (str "artifact-" job) :acquisitions [{:url "private:test"}]
   :payload {:parse-status :parsed :parsed {:source-name name :representation "AIN"}}})
(deftest unicode-variants-are-only-candidates
  (let [a (row "a" 0 "s1" "Zsófia Törőcsik") b (row "b" 0 "s2" "TÖRÖCSIK ZSOFIA")
        p (c/packet [a b] {:job-id "a" :ordinal 0} {})]
    (is (= :candidate (:outcome p)))
    (is (= "Zsófia Törőcsik" (get-in p [:target :payload :parsed :source-name])))
    (is (= [:token-order] (get-in p [:candidates 0 :signals])))
    (is (= :unreviewed (:review-status p)))
    (is (= p (c/packet [b a] {:job-id "a" :ordinal 0} {})))))
(deftest anchors-retain-full-source-reference
  (let [a (assoc (row "a" 0 "s1" "Amy Smith") :source-lines [{:page 2 :line 4 :text "Amy Smith"}])
        p (c/packet [a] {:job-id "a" :ordinal 0} {})]
    (is (= "local-observation:a:0" (get-in p [:local-identity-anchor :identity-id])))
    (is (= {:job-id "a" :ordinal 0 :candidate-id "a0" :source-sha256 "s1" :artifact-sha256 "artifact-a" :page 2 :line 4}
           (get-in p [:local-identity-anchor :reference])))))
(deftest identifiers-ignore-print-bindings
  (let [rows [(row "a" 0 "s1" "Amy Smith")]]
    (is (= (c/packets rows {}) (binding [*print-length* 1 *print-level* 1] (c/packets rows {}))))))
(deftest duplicate-listings-and-mirrors-are-one-case
  (let [rows [(row "a" 0 "s1" "Amy Smith") (row "a" 1 "s1" "AMY SMITH")
              (row "mirror" 0 "s1" "Smith Amy") (row "b" 0 "s2" "Amy Smith")]
        result (c/packets rows {}) p (first (:packets result))]
    (is (= 2 (:total result)))
    (is (= 3 (count (:target-observations p))))
    (is (= 1 (:candidate-group-count p)))
    (is (= 2 (:distinct-source-document-count p)))
    (is (= :not-established (:independent-corroboration p)))
    (is (= result (c/packets (reverse rows) {})))))
(deftest abstention-and-weak-signals
  (let [rows [(row "a" 0 "s1" "Zso a TÖRÖCSIK") (row "b" 0 "s2" "Zsófia Törőcsik")
              (row "c" 0 "s3" "M Lee") (row "d" 0 "s4" "M Li")
              (row "e" 0 "s5" nil) (assoc (row "f" 0 "s6" "M Lee") :kind "fragment")]]
    (is (= :ambiguous (:outcome (c/packet rows {:job-id "a" :ordinal 0} {}))))
    (is (= :no-candidate (:outcome (c/packet rows {:job-id "c" :ordinal 0} {}))))
    (is (= :unknown (:outcome (c/packet rows {:job-id "e" :ordinal 0} {}))))
    (is (nil? (:local-identity-anchor (c/packet rows {:job-id "e" :ordinal 0} {}))))
    (is (= :unknown (:outcome (c/packet rows {:job-id "f" :ordinal 0} {}))))))
(deftest explicit-bounds-and-pagination
  (let [rows [(row "a" 0 "s1" "Amy Smith") (row "b" 0 "s2" "Amy Smith") (row "c" 0 "s3" "Amy Smith")]
        first-page (c/packets rows {:limit 1}) second-page (c/packets rows {:offset 1 :limit 2})]
    (is (= 3 (:total first-page)))
    (is (:has-more? first-page))
    (is (false? (:has-more? second-page)))
    (is (= (:packets (c/packets rows {})) (vec (concat (:packets first-page) (:packets second-page)))))
    (is (= 2 (:candidate-group-count (first (:packets first-page)))))
    (is (= :ambiguous (:outcome (first (:packets first-page)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds" (c/packets rows {:max-observations 2})))
    (is (thrown? clojure.lang.ExceptionInfo (c/packets rows {:limit 0})))
    (is (thrown? clojure.lang.ExceptionInfo (c/packets rows {:comparison-version "other"})))
    (is (thrown? clojure.lang.ExceptionInfo (c/packet rows {:job-id "missing" :ordinal 0} {})))))

(deftest html-anchors-retain-table-row-not-pdf-coordinates
  (let [a (assoc-in (assoc (row "a" 0 "s1" "Synthetic Person") :source-format :html) [:payload :coordinates] {:table 2 :row 3})
        p (c/packet [a] {:job-id "a" :ordinal 0} {})]
    (is (= {:job-id "a" :ordinal 0 :candidate-id "a0" :source-sha256 "s1" :artifact-sha256 "artifact-a" :table 2 :row 3}
           (get-in p [:local-identity-anchor :reference])))))

(deftest pdf-table-hints-are-not-html-evidence
  (let [a (assoc-in (row "a" 0 "s1" "Synthetic Person") [:payload :coordinates] {:page 2 :line 3 :table 99 :row 99})
        p (c/packet [a] {:job-id "a" :ordinal 0} {})]
    (is (= {:job-id "a" :ordinal 0 :candidate-id "a0" :source-sha256 "s1" :artifact-sha256 "artifact-a" :page 2 :line 3}
           (get-in p [:local-identity-anchor :reference])))))

(deftest json-anchors-retain-source-row-and-html-url
  (let [url "https://results.microplustimingservices.com/CMAS/Results/#/2/dynamic-result-json/MAM/011/007/001"
        a (-> (row "a" 0 "s1" "BECHTEL Timothy")
              (assoc :source-format :json :source-page-url url)
              (assoc-in [:payload :coordinates] {:row-index-zero-based 7}))
        p (c/packet [a] {:job-id "a" :ordinal 0} {})]
    (is (= {:job-id "a" :ordinal 0 :candidate-id "a0" :source-sha256 "s1"
            :artifact-sha256 "artifact-a" :row-index-zero-based 7 :source-page-url url}
           (get-in p [:local-identity-anchor :reference])))))
