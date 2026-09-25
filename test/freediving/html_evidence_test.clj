(ns freediving.html-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.aida-html :as html]
            [freediving.aida-html-test :as fixture]
            [freediving.html-evidence :as evidence]))
(defn artifact [source]
  (merge (html/parse-html source) {:schema-version 4 :source-sha256 (evidence/sha256 (.getBytes source "UTF-8"))}))
(deftest retained-source-replay-binds-exact-row-and-event-context
  (let [a (artifact (str "<h1>Synthetic championship</h1>" (fixture/document (assoc fixture/cells 10 ""))))
        p (first (:candidates a)) ctx (evidence/bound-context! a p 0 (:source-sha256 a))]
    (is (= {:table 1 :row 2} (:coordinates ctx)))
    (is (= "Synthetic championship" (:event-name ctx)))
    (is (= "2025-06-28" (:event-date ctx)))
    (is (= (get-in p [:raw :html]) (:raw-row ctx)))
    (doseq [[a p ordinal hash] [[(assoc a :raw-html "changed") p 0 (:source-sha256 a)]
                                [a (assoc-in p [:raw :cells 1] "forged") 0 (:source-sha256 a)]
                                [a p 1 (:source-sha256 a)] [a p 0 "wrong"]
                                [(assoc a :parser-version "aida-html/2") p 0 (:source-sha256 a)]]]
      (is (thrown? Exception (evidence/bound-context! a p ordinal hash))))))
(deftest actual-branding-is-required-and-title-is-not-event-evidence
  (let [source (fixture/document fixture/cells)]
    (is (nil? (:event-name (evidence/context (artifact source)))))
    (is (= "Synthetic retained championship"
           (:event-name (evidence/context (artifact (str "<!-- <h1>Stale event</h1> --><div class='site-header__branding2'><img alt='Synthetic retained championship'></div>" source))))))
    (is (nil? (:event-name (evidence/context (artifact (str "<h1>A</h1><h1>B</h1>" source))))))))
