(ns freediving.ffessm-source-links-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2026 :as cwt-women]
            [freediving.ffessm-2026-test :as cwt-women-test]
            [freediving.ffessm-2026-final-categories :as final]
            [freediving.ffessm-source-links :as links]))

(defn fixture-pages [name]
  (let [parts (vec (str/split (slurp (io/resource (str "fixtures/ffessm-2026-b16/" name ".txt"))) #"\f" -1))]
    (if (= "" (last parts)) (pop parts) parts)))

(defn artifact [sha name]
  (assoc (final/parse-pages sha (fixture-pages name))
         :source-sha256 sha :job-id (apply str (repeat 64 "a"))))

(deftest linked-national-rows-cite-their-open-rows-in-one-pdf
  (let [a (artifact final/cnf-men-sha256 "sans-palmes-hommes")
        edges (links/source-links a)]
    (is (= 10 (count edges)))
    (is (= (vec (sort-by (juxt (comp :ordinal :left) (comp :ordinal :right)) edges)) edges))
    (is (every? #(= :same-attempt (:kind %)) edges))
    (doseq [{:keys [left right basis]} edges
            :let [open (nth (:candidates a) (:ordinal left))
                  subset (nth (:candidates a) (:ordinal right))]]
      (is (= (:job-id a) (:job-id left) (:job-id right)))
      (is (= :epreuve (:ranking-scope open)))
      (is (= :championnat-de-france (:ranking-scope subset)))
      (is (= [(select-keys (:coordinates open) [:page :line])] (:left-position basis)))
      (is (= [(select-keys (:coordinates subset) [:page :line])] (:right-position basis)))
      (is (= (get-in open [:raw :line]) (:left-text basis)))
      (is (= (get-in subset [:raw :line]) (:right-text basis)))
      (is (<= 3 (count (:match-fields basis))))
      (is (every? #(and (some? (get-in open [:parsed %]))
                        (= (get-in open [:parsed %]) (get-in subset [:parsed %])))
                  (:match-fields basis))))))

(deftest changed-or-ambiguous-evidence-is-not-linked
  (let [a (artifact final/cnf-men-sha256 "sans-palmes-hommes")
        subset-ordinal (first (keep-indexed (fn [n c] (when (get-in c [:parsed :same-result-as]) n)) (:candidates a)))
        changed (assoc-in a [:candidates subset-ordinal :raw :fields :depth-reached] "999 m")
        ambiguous (update a :candidates conj (first (:candidates a)))
        wrong-source (assoc a :source-sha256 (apply str (repeat 64 "0")))]
    (is (= 9 (count (links/source-links changed))))
    (is (< (count (links/source-links ambiguous)) 10))
    (is (empty? (links/source-links wrong-source)))))

(deftest source-without-two-rankings-has-no-link
  (is (empty? (links/source-links
               (artifact final/fim-women-sha256 "immersion-libre-femmes")))))

(deftest other-2026-parsers-preserve-the-same-evidence-rule
  (let [cwt (assoc (cwt-women/parse-pages [cwt-women-test/sample-page])
                   :source-sha256 cwt-women/source-sha256
                   :job-id (apply str (repeat 64 "b")))
        fim (artifact final/fim-men-sha256 "immersion-libre-hommes")]
    (is (= 4 (count (links/source-links cwt))))
    (is (= 9 (count (links/source-links fim))))
    (is (every? #(not (some #{:result-status} (get-in % [:basis :match-fields])))
                (links/source-links cwt)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-source-links-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
