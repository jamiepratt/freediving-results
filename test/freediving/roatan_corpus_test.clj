(ns freediving.roatan-corpus-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [freediving.cmas-2026-roatan-json-test :as fixture]
            [freediving.roatan-corpus :as corpus])
  (:import [java.nio.file Files]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- digest [^bytes b]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") b)))
(defn- tempdir [] (str (Files/createTempDirectory "roatan-corpus-test" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- packet! [root]
  (.mkdirs (io/file root "objects"))
  (let [routes (mapv (fn [[unit n]]
                       (let [bytes (.getBytes (json/write-str
                                               (mapv #(assoc (fixture/row unit)
                                                             "ResID" (+ unit %)
                                                             "ResRnk" (inc %)
                                                             "ParYearBirthDate" "1997") (range n))) "UTF-8")
                             sha (digest bytes)
                             urls (fixture/routes unit)]
                         (with-open [out (io/output-stream (io/file root "objects" sha))] (.write out bytes))
                         {:route {:requested_url (:json-url urls) :final_url (:json-url urls)
                                  :redirects [] :status 200 :content_type "application/json; charset=utf-8"
                                  :byte_length (alength bytes) :sha256 sha :object (str "objects/" sha)
                                  :transport_rows n}
                          :browser {:view_url (:view-url urls) :label "OFFICIAL" :rows n
                                    :row_key_check (str "All " n " visible rank/name/representation/birth-year tuples matched the corresponding source API rows in order.")
                                    :observed_window_utc "2026-09-28 08:30-08:33 UTC"}}))
                     [[3551 7] [3559 24]])
        manifest {:schema "roatan-cwt-men-source-check/v1"
                  :discovery_url "https://www.cmas.org/document/freediving/example"
                  :event_page_url "https://www.cmas.org/freediving-events/example"
                  :publisher "CMAS-linked Microplus"
                  :routes (mapv :route routes)
                  :browser_observations (mapv :browser routes)}]
    (spit (io/file root "manifest.json") (json/write-str manifest))
    routes))

(deftest exact-visible-row-citations-replay
  (let [packet (tempdir) root (tempdir) routes (packet! packet)
        first-run (corpus/import-packet! packet root)
        second-run (corpus/import-packet! packet root)
        replay (corpus/replay root)]
    (is (= {:imported 31 :quarantined 0} (:census first-run)))
    (is (= :unchanged (:status second-run)))
    (is (= [7 24] (mapv :source-row-count (:units replay))))
    (is (= 31 (count (mapcat :rows (:units replay)))))
    (is (= (mapv #(get-in % [:route :sha256]) routes)
           (mapv :source-sha256 (:units replay))))
    (is (every? #(= :unreviewed (:review-status %)) (mapcat :rows (:units replay))))
    (is (= "1997" (get-in replay [:units 0 :rows 0 :citation :visible-tuple :birth-year])))
    (is (= (get-in routes [0 :browser :view_url])
           (get-in replay [:units 0 :rows 0 :citation :view-url])))))

(deftest tamper-and-changed-source-version
  (let [packet (tempdir) root (tempdir) routes (packet! packet)
        _ (corpus/import-packet! packet root)
        object (io/file packet (get-in routes [0 :route :object]))]
    (spit object "tampered")
    (is (thrown? Exception (corpus/import-packet! packet root)))
    (let [original (io/file root "objects" (get-in routes [0 :route :sha256]))]
      (spit original "tampered")
      (is (thrown? Exception (corpus/replay root))))))

(defn- revise-source! [packet unit change-row]
  (let [manifest-file (io/file packet "manifest.json")
        manifest (json/read-str (slurp manifest-file))
        route-index (first (keep-indexed (fn [i route]
                                           (when (.endsWith (get route "requested_url")
                                                            (str "/" unit "/results")) i))
                                         (get manifest "routes")))
        route (get-in manifest ["routes" route-index])
        rows (json/read-str (slurp (io/file packet (get route "object"))))
        changed (assoc rows 0 (change-row (first rows)))
        source (.getBytes (json/write-str changed) "UTF-8")
        sha (digest source)
        new-route (assoc route "sha256" sha "object" (str "objects/" sha)
                         "byte_length" (alength source))]
    (with-open [out (io/output-stream (io/file packet "objects" sha))] (.write out source))
    (spit manifest-file (json/write-str (assoc-in manifest ["routes" route-index] new-route)))
    sha))

(deftest changed-bytes-are-a-separate-unreviewed-version
  (let [packet (tempdir) root (tempdir) _ (packet! packet)
        _ (corpus/import-packet! packet root)
        revised (revise-source! packet 3551 #(assoc % "ResResult" "101"))
        _ (corpus/import-packet! packet root)
        replay (corpus/replay root)
        versions (filter #(= 3551 (:unit %)) (:units replay))]
    (is (= 2 (count versions)))
    (is (= 2 (count (set (map :source-sha256 versions)))))
    (is (some #(= revised (:source-sha256 %)) versions))
    (is (every? #(= :unknown (get-in % [:rows 0 :candidate :revision-status])) versions))
    (is (= 62 (count (mapcat :rows (:units replay)))))
    (is (= 3 (count (set (map :source-sha256 (:units replay))))))))

(deftest invalid-result-row-is-cited-and-quarantined
  (let [packet (tempdir) root (tempdir) _ (packet! packet)
        _ (revise-source! packet 3551 #(dissoc % "ResID"))
        result (corpus/import-packet! packet root)
        row (get-in (corpus/replay root) [:units 0 :rows 0])]
    (is (= {:imported 30 :quarantined 1} (:census result)))
    (is (= :quarantined (:status row)))
    (is (= :unsupported-result-row (:reason row)))
    (is (= "1997" (get-in row [:citation :visible-tuple :birth-year])))))

(deftest missing-unit-entry-fails-replay
  (let [packet (tempdir) root (tempdir) _ (packet! packet)
        _ (corpus/import-packet! packet root)
        one-entry (first (.listFiles (io/file root "entries")))]
    (Files/delete (.toPath one-entry))
    (is (thrown? Exception (corpus/replay root)))))

(defn -main []
  (let [result (clojure.test/run-tests 'freediving.roatan-corpus-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
