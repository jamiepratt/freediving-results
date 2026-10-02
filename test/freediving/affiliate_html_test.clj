(ns freediving.affiliate-html-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.affiliate-html :as affiliate]
            [freediving.name-evidence :as names])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def url "https://aida-japan.com/info/6495.html")
(def html (str "<html><head><link rel='canonical' href='http://aida-japan.com'><link rel='canonical' href='" url "'></head><body>"
               "<div id='post-6495'><h2 id='single_title'>34th AIDA World Championship 2025 Wakayama</h2>"
               "<h4>代表選手種目別一覧</h4><blockquote>"
               "<p>【女子】<br>《DYNB》<br>山田花子（WC）<br>佐藤雪<br>※WC→説明</p>"
               "<p>【男子】<br>《DNF》<br>鈴木太郎<br>未判読?</p>"
               "</blockquote></div></body></html>"))
(defn sha [source]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") (.getBytes source "UTF-8"))))

(deftest cited-roster-lines-are-name-only-and-partial
  (let [result (affiliate/parse-team-roster {:html html :source-sha256 (sha html) :url url})
        assertions (:name-assertions result)
        replay (names/import-name-evidence {:attempts [{:id "existing"}]} (:name-sources result))]
    (is (= ["山田花子" "佐藤雪" "鈴木太郎"] (mapv :original-name assertions)))
    (is (= ["female" "female" "male"] (mapv :roster-category (:roster-entries result))))
    (is (= ["DYNB" "DYNB" "DNF"] (mapv :discipline (:roster-entries result))))
    (is (= [3 4 3] (mapv #(get-in % [:source-position :line]) assertions)))
    (is (= [1 1 2] (mapv #(get-in % [:source-position :paragraph]) assertions)))
    (is (every? #(nil? (:publisher-romanization %)) assertions))
    (is (= [:unsupported :unsupported] (mapv :status (filter :position-id (get-in result [:routing :gaps])))))
    (is (= :unexamined (:status (last (get-in result [:routing :gaps])))))
    (is (= [{:id "existing"}] (:attempts replay)))
    (is (= replay (names/import-name-evidence replay (:name-sources result))))))

(deftest rejects-mismatched-or-incompatible-page
  (is (thrown? clojure.lang.ExceptionInfo
               (affiliate/parse-team-roster {:html html :source-sha256 (apply str (repeat 64 "a")) :url url})))
  (is (thrown? clojure.lang.ExceptionInfo
               (affiliate/parse-team-roster {:html "<html>other page</html>"
                                             :source-sha256 (sha "<html>other page</html>") :url url}))))

(deftest overlapping-claim-quarantines-position
  (let [hash (sha html)
        competing {:parser-id "other" :parser-version "1" :match-reason "synthetic overlap"
                   :source-restriction {:sha256s #{hash} :formats #{:html}}
                   :supported-positions #{"p1:l3"} :claimed-positions #{"p1:l3"}}
        result (affiliate/parse-team-roster {:html html :source-sha256 hash :url url
                                             :additional-claims [competing]})]
    (is (= ["佐藤雪" "鈴木太郎"] (mapv :original-name (:name-assertions result))))
    (is (some #(and (= :ambiguous (:status %)) (= "p1:l3" (:position-id %)))
              (get-in result [:routing :gaps])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.affiliate-html-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
