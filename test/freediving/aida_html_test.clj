(ns freediving.aida-html-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [clojure.edn :as edn]
            [freediving.aida-html :as html]))

(def headers ["Start" "Diver" "Nationality" "Gender" "Discipline" "OT" "AP" "RP" "Card" "Points" "Remarks"])
(defn document [cells]
  (str "<title>Synthetic AIDA event</title><ul><li class='active'><a class='days'>2025-06-28</a></li></ul><table><thead><tr>"
       (apply str (map #(str "<th>" % "</th>") headers))
       "</tr></thead><tbody><tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr></tbody></table>"))
(def cells ["1" "ÉXAMPLE  &amp; Person" "AIN" "Female" "DYNB" "10:00" "100 m" "0 m" "WHITE" "0" "Dqsp"])
(deftest attempts-retain-evidence-and-contradictions
  (let [source (document cells) result (html/parse-html source) c (first (:candidates result))]
    (is (= :needs-review (:status result)))
    (is (= {:table 1 :row 2} (:coordinates c)))
    (is (= "ÉXAMPLE  & Person" (get-in c [:parsed :source-name])))
    (is (= "2025-06-28" (get-in c [:parsed :event-date])))
    (is (= "0 m" (get-in c [:parsed :realised-performance])))
    (is (= "0" (get-in c [:parsed :points])))
    (is (= [:white-card-with-disqualification-remark] (:flags c)))
    (is (.contains source (get-in c [:raw :html])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest older-attempt-layout-preserves-line-and-official-top
  (let [source (str/replace (document (into (subvec cells 0 5) (cons "A" (subvec cells 5))))
                            "<th>OT</th>" "<th>Line</th><th>Official Top</th>")
        c (first (:candidates (html/parse-html source)))]
    (is (= "A" (get-in c [:parsed :line])))
    (is (= "10:00" (get-in c [:parsed :official-top])))))

(deftest missing-zero-and-malformed-rows-remain-distinct
  (let [missing (first (:candidates (html/parse-html (document (assoc cells 7 "" 8 "")))))
        zero (first (:candidates (html/parse-html (document cells))))
        malformed (first (:candidates (html/parse-html (document (pop cells)))))
        unsupported (html/parse-html (str/replace (document cells) "<th>RP</th>" "<th>Unrecognized</th>"))]
    (is (= :unknown (get-in missing [:fields :realised-performance :status])))
    (is (= :parsed (get-in zero [:fields :realised-performance :status])))
    (is (= "" (get-in missing [:raw :fields "RP"])))
    (is (= :unparsed (:parse-status malformed)))
    (is (= 10 (count (get-in malformed [:raw :cells]))))
    (is (= :unsupported-needs-parser (:status unsupported)))
    (is (= 1 (get-in unsupported [:reconciliation :unsupported-table-count])))))

(deftest rankings-are-supplemental-with-explicit-filter-context
  (let [source (str "<select id='discipline'><option selected>DYN</option></select>"
                    "<select id='gender'><option selected>Male</option></select>"
                    "<table><tr>" (apply str (map #(str "<th>" % "</th>") html/ranking-headers))
                    "</tr><tr><td></td><td>1</td><td>Synthetic Person</td><td>AIN</td>"
                    "<td>100 m</td><td>90 m</td><td>50</td><td>0</td></tr></table>")
        c (first (:candidates (html/parse-html source)))]
    (is (= :ranking (:source-family c)))
    (is (= "DYN" (get-in c [:parsed :discipline])))
    (is (= "Male" (get-in c [:parsed :gender])))
    (is (= "0" (get-in c [:parsed :penalty])))
    (is (nil? (get-in c [:parsed :card])))
    (is (nil? (get-in c [:parsed :event-date])))))

(defn register-html [root source-file source]
  (spit source-file source :encoding "UTF-8")
  (let [hash (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes source "UTF-8")))]
    (archive/register! root source-file (assoc fixture/manifest :sha256 hash :content-type "text/html; charset=UTF-8" :final-url "https://example.org/StartList/1"))
    hash))
(deftest extraction-replays-immutable-source-and-versions-changed-bytes
  (let [dir (fixture/workspace) root (str dir "/archive") path (str dir "/source.html")
        hash (register-html root path (document cells)) options {:actor "synthetic" :config {}}
        first-run (html/extract! root hash options)
        rerun (html/extract! root hash options)
        a (edn/read-string (slurp (:artifact-path first-run)))
        changed-hash (register-html root path (document (assoc cells 7 "1 m")))
        changed (html/extract! root changed-hash options)]
    (is (= :created (:run-status first-run)))
    (is (= :skipped (:run-status rerun)))
    (is (= (:job-id first-run) (:job-id rerun)))
    (is (not= (:job-id first-run) (:job-id changed)))
    (is (= 4 (:schema-version a)))
    (is (= (document cells) (:raw-html a)))
    (is (= a (html/validate-artifact! root a)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HTML source replay"
                          (html/validate-artifact! root (assoc-in a [:candidates 0 :parsed :points] "99"))))))

(deftest explicit-units-and-record-badges-are-parsed-without-inference
  (let [c (first (:candidates (html/parse-html (document (assoc cells 1 "\n  ÉXAMPLE  &amp; Person \n" 7 "232 m  NR")))))
        time (first (:candidates (html/parse-html (document (assoc cells 4 "STA" 6 "03:20" 7 "04:47")))))
        malformed (first (:candidates (html/parse-html (document (assoc cells 7 "unknown m")))))]
    (is (= "ÉXAMPLE  & Person" (get-in c [:parsed :source-name])))
    (is (= 232M (get-in c [:parsed :performance])))
    (is (= "m" (get-in c [:parsed :unit])))
    (is (= 100M (get-in c [:parsed :announced])))
    (is (= "m" (get-in c [:parsed :announced-unit])))
    (is (= "NR" (get-in c [:parsed :record-badge])))
    (is (= [4 47] (get-in time [:parsed :realized-time :components])))
    (is (nil? (get-in time [:parsed :unit])))
    (is (= :invalid (get-in malformed [:fields :performance :status])))
    (is (= "unknown m" (get-in malformed [:raw :fields "RP"])))))

(deftest structurally-ambiguous-rows-are-retained-for-review
  (doseq [source [(document (assoc cells 1 "<table><tr><td>Nested Person</td></tr></table>"))
                  (str/replace-first (document cells) "<td>" "<td rowspan='2'>")
                  (str/replace (document cells) "</td>" "")]]
    (let [c (first (:candidates (html/parse-html source)))]
      (is (= :unparsed (:parse-status c)))
      (is (= 11 (count (get-in c [:raw :cells])))))))
