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
(deftest acquisition-context-keeps-retained-filters-and-rejects-contradictions
  (let [a (assoc (artifact (fixture/document fixture/cells)) :acquisitions
                 [{:acquisition-id "snapshot" :manifest {:provenance {:browser-state {:selected-date "2025-06-29" :filters {:discipline :dynb} :representation :rendered-dom :rendered-sha256 "retained-dom"}}}}])
        ctx (evidence/context a)]
    (is (= "2025-06-29" (get-in ctx [:acquisition-context 0 :selected-date])))
    (is (= {:discipline :dynb} (get-in ctx [:acquisition-context 0 :filters])))
    (is (= [:acquisition-date-conflict] (:context-errors ctx)))))
(deftest explicitly-hidden-headings-never-supply-event-evidence
  (doseq [wrapper ["<div hidden>%s</div>" "<div aria-hidden='true'>%s</div>" "<div style='display: none'>%s</div>"
                   "<div style='visibility: hidden'>%s</div>" "<template>%s</template>" "<noscript>%s</noscript>"]
          heading ["<h1>Hidden championship</h1>" "<div class='site-header__branding'><img alt='Hidden championship'></div>"]]
    (is (nil? (:event-name (evidence/context (artifact (str (format wrapper heading) (fixture/document fixture/cells)))))))))

(deftest scoped-replay-reuses-context-without-transferring-acquisition-or-row-trust
  (let [a (artifact (str "<h1>Synthetic championship</h1>" (fixture/document fixture/cells)))
        payload (first (:candidates a))
        read-row #(evidence/bound-context! % payload 0 (:source-sha256 a))
        scoped (requiring-resolve 'freediving.html-evidence/with-verified-replay-cache)]
    (is (= (read-row a) (scoped #(do (read-row a) (read-row a)))))
    (scoped
     #(do
        (read-row a)
        ;; Even an unselected candidate is replay-checked when the same job/source is presented.
        (is (thrown? Exception
                     (read-row (update a :candidates conj (assoc payload :coordinates {:table 1 :row 99})))))
        (is (thrown? Exception (read-row (assoc a :raw-html "changed source"))))
        (is (thrown? Exception (read-row (assoc a :parser-version "aida-html/2"))))
        (let [changed (assoc-in (assoc a :acquisitions [{:acquisition-id "new"}])
                                [:acquisitions 0 :manifest :provenance :browser-state :selected-date]
                                "2025-06-29")]
          (is (= [:acquisition-date-conflict] (:context-errors (read-row changed)))))
        (is (thrown? Exception (evidence/bound-context! a (assoc-in payload [:parsed :points] "forged") 0 (:source-sha256 a))))))
    ;; A later request receives its own scope and repeats provenance validation.
    (is (thrown? Exception (scoped #(read-row (assoc a :source-sha256 "changed binding")))))))
