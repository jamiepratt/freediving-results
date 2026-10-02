(ns freediving.parser-adapters-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.aida-html-test :as html-fixture]
            [freediving.parser-adapters :as adapters]
            [freediving.parser-routing :as routing]
            [freediving.vdst-neckar-2025 :as neckar]
            [freediving.vdst-neckar-2025-test :as neckar-fixture]))

(defn sha256 [text]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes text "UTF-8"))))

(defn position [hash format coordinates]
  (let [location (case format
                   :html (str "table=" (:table coordinates) "&row=" (:row coordinates))
                   :pdf (str "page=" (:page coordinates) "&line=" (:line coordinates)
                             "&column=" (:column-start coordinates) "-" (:column-end coordinates)))
        citation (str "sha256:" hash "#" location)]
    {:id location :citation citation :coordinates coordinates}))

(deftest retained-html-candidate-routes-with-exact-source-citation
  (let [source (html-fixture/document html-fixture/cells)
        hash (sha256 source)
        candidate (position hash :html {:table 1 :row 2})
        document {:source-sha256 hash :format :html
                  :positions [candidate
                              (position hash :html {:table 1 :row 3})]
                  :sections [{:id "other-table" :citation (str "sha256:" hash "#table=2")
                              :examined? false}]}
        result (adapters/claims-for-document document {:html source})
        routed (routing/route-document document (:claims result))]
    (is (= 1 (count (:claims result))))
    (is (= "aida-html/1" (:parser-version (first (:claims result)))))
    (is (= #{(:id candidate)} (:claimed-positions (first (:claims result)))))
    (is (= (:citation candidate) (:citation (first (:routed routed)))))
    (is (= [:unsupported :unexamined] (mapv :status (:gaps routed))))
    (is (= :needs-review (get-in result [:extraction :status])))))

(deftest false-positive-html-and-source-mismatch-do-not-claim
  (let [source (html-fixture/document html-fixture/cells)
        hash (sha256 source)
        unsupported-source (.replace source "<th>RP</th>" "<th>Other</th>")
        unsupported-hash (sha256 unsupported-source)
        document {:source-sha256 hash :format :html
                  :positions [(position hash :html {:table 1 :row 2})]}
        false-header (adapters/claims-for-document
                      {:source-sha256 unsupported-hash :format :html
                       :positions [(position unsupported-hash :html {:table 1 :row 2})]}
                      {:html unsupported-source})
        false-positive (adapters/claims-for-document document
                                                     {:html unsupported-source})
        mismatch (adapters/claims-for-document (assoc document :source-sha256 (apply str (repeat 64 "0")))
                                               {:html source})
        forged-citation (adapters/claims-for-document
                         (assoc-in document [:positions 0 :citation] "https://example.invalid/forged")
                         {:html source})]
    (is (empty? (:claims false-header)))
    (is (= :unsupported-needs-parser (get-in false-header [:extraction :status])))
    (is (empty? (:claims false-positive)))
    (is (some #{:source-hash-mismatch} (:unsupported-reasons false-positive)))
    (is (empty? (:claims mismatch)))
    (is (some #{:source-hash-mismatch} (:unsupported-reasons mismatch)))
    (is (empty? (:claims forged-citation)))))

(deftest source-bound-pdf-replay-claims-only-observed-cup-positions
  (let [hash neckar/source-sha256
        candidate (position hash :pdf {:page 1 :line 6 :column-start 1 :column-end 102})
        document {:source-sha256 hash :format :pdf
                  :positions [candidate
                              (position hash :pdf {:page 1 :line 10 :column-start 1 :column-end 50})]}
        result (adapters/claims-for-document document
                                             {:source-sha256 hash :pages neckar-fixture/pages
                                              :trusted-extraction? true})
        routed (routing/route-document document (:claims result))]
    (is (= 1 (count (:routed routed))))
    (is (= (:citation candidate) (:citation (first (:routed routed)))))
    (is (= :unsupported (:status (first (:gaps routed)))))
    (is (= neckar/parser-version (:parser-version (first (:claims result)))))
    (is (= :upstream-verified-required (:source-verification result)))
    (is (empty? (:claims (adapters/claims-for-document document
                                                       {:source-sha256 hash
                                                        :pages neckar-fixture/pages}))))))

(deftest unsupported-format-families-remain-explicit-gaps
  (doseq [format [:json :image :workbook]]
    (let [hash (apply str (repeat 64 "a"))
          document {:source-sha256 hash :format format
                    :positions [{:id "source:1" :citation (str "sha256:" hash "#source:1")}]}
          result (adapters/claims-for-document document {})]
      (is (empty? (:claims result)))
      (is (= [format] (:unsupported-formats result)))
      (is (= [:unsupported]
             (mapv :status (:gaps (routing/route-document document (:claims result)))))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.parser-adapters-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
