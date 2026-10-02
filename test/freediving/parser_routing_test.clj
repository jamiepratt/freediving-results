(ns freediving.parser-routing-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.parser-routing :as routing]))

(def source-hash (apply str (repeat 64 "a")))
(def other-hash (apply str (repeat 64 "b")))

(def html-document
  {:source-sha256 source-hash :format :html
   :positions [{:id "article#heading" :citation "https://example.invalid/article#heading"}
               {:id "article#names" :citation "https://example.invalid/article#names"}
               {:id "article#ranking" :citation "https://example.invalid/article#ranking"}
               {:id "article#footer" :citation "https://example.invalid/article#footer"}
               {:id "article#unchecked" :citation "https://example.invalid/article#unchecked" :examined? false}]})

(defn claim [parser supported claimed]
  {:parser-id parser :parser-version "1" :match-reason "synthetic section signature"
   :source-restriction {:sha256s #{source-hash} :formats #{:html}}
   :supported-positions supported :claimed-positions claimed})

(deftest routes-disjoint-html-sections-with-explicit-gaps
  (let [result (routing/route-document
                html-document
                [(claim "article" #{"article#heading" "article#names"} #{"article#names"})
                 (claim "ranking" #{"article#ranking"} #{"article#ranking"})])]
    (is (= ["article#names" "article#ranking"] (mapv :position-id (:routed result))))
    (is (= ["article" "ranking"] (mapv (comp :parser-id :claim) (:routed result))))
    (is (= source-hash (:source-sha256 result)))
    (is (= [[:unclaimed "article#heading"] [:unsupported "article#footer"]
            [:unexamined "article#unchecked"]]
           (mapv (juxt :status :position-id) (:gaps result))))
    (is (= "https://example.invalid/article#names" (:citation (first (:routed result)))))
    (is (= 5 (count (concat (:routed result) (:gaps result)))))))

(deftest quarantines-only-overlapping-source-positions
  (let [scan {:source-sha256 source-hash :format :image
              :positions [{:id "page1:top" :citation "page 1, top region"}
                          {:id "page1:middle" :citation "page 1, middle region"}
                          {:id "page1:bottom" :citation "page 1, bottom region"}]}
        mk (fn [id positions]
             {:parser-id id :parser-version "2" :match-reason "scan panel header"
              :source-restriction {:sha256s #{source-hash} :formats #{:image}}
              :supported-positions positions :claimed-positions positions})
        result (routing/route-document
                scan [(mk "right" #{"page1:middle" "page1:bottom"})
                      (mk "left" #{"page1:top" "page1:middle"})])]
    (is (= ["page1:top" "page1:bottom"] (mapv :position-id (:routed result))))
    (is (= :ambiguous (:status (first (:gaps result)))))
    (is (= ["left" "right"] (mapv :parser-id (:contenders (first (:gaps result))))))
    (is (= "page 1, middle region" (:citation (first (:gaps result)))))))

(deftest rejects-source-mismatch-and-malformed-or-false-positive-claims
  (let [wrong (assoc-in (claim "wrong-source" #{"article#names"} #{"article#names"})
                        [:source-restriction :sha256s] #{other-hash})
        unsupported (claim "out-of-inventory" #{"missing"} #{"missing"})
        missing-reason (assoc (claim "no-reason" #{"article#names"} #{"article#names"})
                              :match-reason "")
        result (routing/route-document html-document [wrong unsupported missing-reason])]
    (is (empty? (:routed result)))
    (is (= #{:source-mismatch :unknown-position :missing-match-reason}
           (set (map :reason (:rejected-claims result)))))
    (is (= :unsupported (:status (second (:gaps result)))))))

(deftest order-does-not-select-a-parser
  (let [a (claim "alpha" #{"article#names"} #{"article#names"})
        b (claim "beta" #{"article#names"} #{"article#names"})
        forward (routing/route-document html-document [a b])
        reverse (routing/route-document html-document [b a])]
    (is (= forward reverse))
    (is (empty? (:routed forward)))
    (is (= ["alpha" "beta"]
           (mapv :parser-id (:contenders (second (:gaps forward))))))
    (is (= #{source-hash}
           (get-in forward [:gaps 1 :contenders 0 :source-restriction :sha256s])))))

(deftest declines-format-mismatch-and-unexamined-claims
  (let [wrong-format (assoc-in (claim "pdf-only" #{"article#names"} #{"article#names"})
                               [:source-restriction :formats] #{:pdf})
        unchecked (claim "unchecked" #{"article#unchecked"} #{"article#unchecked"})
        result (routing/route-document html-document [wrong-format unchecked])]
    (is (= #{:format-mismatch :unexamined-position}
           (set (map :reason (:rejected-claims result)))))
    (is (= :unexamined (:status (last (:gaps result)))))))

(deftest unexamined-section-with-unknown-position-count-remains-a-gap
  (let [document (assoc html-document :sections
                        [{:id "article#table-section"
                          :citation "https://example.invalid/article#table"
                          :examined? false}])
        result (routing/route-document
                document [(claim "article" #{"article#names"} #{"article#names"})])]
    (is (= 1 (count (:routed result))))
    (is (= {:section-id "article#table-section"
            :citation "https://example.invalid/article#table"
            :status :unexamined}
           (last (:gaps result))))
    (is (= 6 (count (concat (:routed result) (:gaps result)))))
    (is (= [{:section-id "article#table-section"
             :citation "https://example.invalid/article#table"
             :status :unexamined}]
           (:gaps (routing/route-document (assoc document :positions []) []))))))

(deftest section-identifiers-cannot-collide-with-source-positions
  (let [section {:id "article#names" :citation "https://example.invalid/article#table"
                 :examined? false}]
    (doseq [sections [[section]
                      [(assoc section :id "unknown-section")
                       (assoc section :id "unknown-section")]]]
      (is (= :invalid-document
             (try (routing/route-document (assoc html-document :sections sections) [])
                  (catch clojure.lang.ExceptionInfo error
                    (:reason (ex-data error)))))))))

(deftest same-parser-version-claims-have-stable-contender-order
  (let [a (assoc (claim "shared" #{"article#names"} #{"article#names"})
                 :match-reason "article title")
        b (assoc a :match-reason "table heading")
        forward (routing/route-document html-document [a b])
        reverse (routing/route-document html-document [b a])]
    (is (= forward reverse))
    (is (= ["article title" "table heading"]
           (mapv :match-reason (:contenders (second (:gaps forward))))))))

(deftest duplicate-discovery-claim-does-not-create-ambiguity
  (let [a (claim "article" #{"article#names"} #{"article#names"})
        result (routing/route-document html-document [a a])]
    (is (= ["article#names"] (mapv :position-id (:routed result))))
    (is (not-any? #(= :ambiguous (:status %)) (:gaps result)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.parser-routing-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
