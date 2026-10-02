(ns freediving.name-evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [freediving.name-evidence :as names]))

(def source-sha (apply str (repeat 64 "a")))
(def evidence
  {:source-sha256 source-sha
   :parser-version "synthetic-affiliate-html/1"
   :source-position {:format :html :selector "article table" :row 3}
   :publisher "Synthetic Federation"
   :source-family :annual-ranking
   :original-name "山田 花"
   :publisher-romanization "Hana Yamada"
   :person-id {:authority "Synthetic Federation" :kind :member-number :value "A-17"}
   :ranking {:period "2025" :category "women" :rules-reference "rules/2025" :printed-points "47.50"}})

(deftest cited-name-evidence-is-separate-from-derived-comparison
  (let [assertion (names/name-assertion evidence)]
    (is (= "山田 花" (:original-name assertion)))
    (is (= "Hana Yamada" (:publisher-romanization assertion)))
    (is (= (:person-id evidence) (:person-id assertion)))
    (is (= (:ranking evidence) (:ranking assertion)))
    (is (= {:format :html :selector "article table" :row 3} (:source-position assertion)))
    (is (= "hana yamada" (get-in assertion [:derived :comparison-name])))
    (is (nil? (:attempt assertion)))
    (is (string? (:evidence-key assertion)))))

(deftest ranking-replay-does-not-create-attempts-or-duplicate-evidence
  (let [first-pass (names/import-name-evidence {:name-assertions [] :attempts [{:id "existing"}]} [evidence])
        repeat-pass (names/import-name-evidence first-pass [evidence evidence])
        changed-parser (names/import-name-evidence repeat-pass [(assoc evidence :parser-version "synthetic-affiliate-html/2")])
        changed-source (names/import-name-evidence changed-parser [(assoc evidence :source-sha256 (apply str (repeat 64 "b")))])]
    (is (= 1 (count (:name-assertions repeat-pass))))
    (is (= [{:id "existing"}] (:attempts repeat-pass)))
    (is (= 3 (count (:name-assertions changed-source))))
    (is (= #{"synthetic-affiliate-html/1" "synthetic-affiliate-html/2"}
           (set (map :parser-version (:name-assertions changed-source)))))))

(deftest invalid-citations-and-unscoped-ids-are-rejected
  (testing "every assertion needs exact replay provenance"
    (is (thrown? Exception (names/name-assertion (dissoc evidence :source-position))))
    (is (thrown? Exception (names/name-assertion (assoc-in evidence [:person-id :authority] nil))))))
