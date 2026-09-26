(ns freediving.vestico-2025
  "Source-bound extraction of the official 17th Submania Kup DYN HTML view."
  (:require [clojure.string :as str])
  (:import [org.jsoup.parser Parser]
           [org.jsoup.nodes Element]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "vestico-2025-dyn-html/1")
(def source-sha256 "238dd1a1e5792f9c0ce5deb6be24263470271c4f2396f17399db27fc639ab09b")
(def headers ["Rank" "OT" "Lane" "Competitor" "M/F" "Club" "Result" "Card" "IRM"])
(def categories {"Rezultati žene / Results female" "Results female"
                 "Rezultati muškarci / Results male" "Results male"})

(defn- sha256 [source]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String source "UTF-8"))))

(defn- children [^Element row tag]
  (filter #(= tag (.tagName ^Element %)) (.children row)))

(defn- exact-html [source ^Element element]
  (let [start (.. element sourceRange start pos)
        end (.. element endSourceRange end pos)]
    (when (and (<= 0 start) (<= start end (count source)))
      (subs source start end))))

(defn- value [s]
  (when-not (str/blank? s) (str/trim s)))

(defn- date-time [printed]
  (when-let [[_ day month year time]
             (when printed (re-matches #"(\d{2})\.(\d{2})\.(\d{4})\. (\d{2}:\d{2})" printed))]
    (try
      {:event-date (str (java.time.LocalDate/of (parse-long year) (parse-long month) (parse-long day)))
       :official-time time}
      (catch Exception _ nil))))

(defn- candidate [source table-index row-index category ^Element row]
  (let [elements (vec (children row "td"))
        cells (mapv #(.wholeText ^Element %) elements)
        raw-fields (zipmap headers cells)
        getv #(value (get raw-fields %))
        printed-time (getv "OT")
        timestamp (date-time printed-time)
        result (getv "Result")
        number (when (and result (re-matches #"\d+(?:,\d+)?" result))
                 (bigdec (str/replace result "," ".")))
        raw-row (exact-html source row)
        complete? (and (= (count headers) (count cells))
                       (string? raw-row)
                       (not (.. row endSourceRange isImplicit))
                       (every? #(and (string? (exact-html source %))
                                     (not (.. ^Element % endSourceRange isImplicit))
                                     (not (.hasAttr ^Element % "colspan"))
                                     (not (.hasAttr ^Element % "rowspan"))
                                     (empty? (.select ^Element % "table"))) elements))
        parsed (when (and complete? timestamp number
                          (getv "Competitor") (re-matches #"\d+" (or (getv "Rank") ""))
                          (= (if (= category "Results female") "F" "M") (getv "M/F")))
                 {:federation nil :source-name (getv "Competitor")
                  :event-name "17. Submania Kup" :event-date (:event-date timestamp)
                  :discipline "DYN" :category category :gender (getv "M/F")
                  :rank (getv "Rank") :official-time (:official-time timestamp)
                  :printed-official-time printed-time :lane (getv "Lane")
                  :club (getv "Club") :realised-performance result
                  :performance number :unit nil :card (getv "Card") :irm (getv "IRM")})]
    {:coordinates {:table table-index :row row-index}
     :raw {:html raw-row :cells cells :cell-html (mapv #(exact-html source %) elements)
           :fields raw-fields}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (cond-> {:unit {:status :unknown :value nil}}
               (and result (nil? number))
               (assoc :performance {:status :invalid :value nil :reason :unrecognized-result}))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-row))}))

(defn parse-html
  "Extract live DYN positions only. Exact row bytes and every printed cell remain in evidence."
  [source]
  (let [doc (.parseInput (.setTrackPosition (Parser/htmlParser) true) source "")
        title (some-> (.selectFirst doc "title") .wholeText str/trim)
        active (vec (.select doc "ul.discipline a.nav-link.active"))
        active-link (first active)
        dyn? (and (= "17. Submania Kup" title) (= 1 (count active))
                  (= "index.php?comp=6" (.attr ^Element active-link "href"))
                  (= "DYN - Dinamika s perajom" (value (.wholeText ^Element active-link))))
        tables (vec (.select doc "table.rezultati"))
        table-data
        (mapv (fn [i ^Element table]
                (let [card (.closest table "div.card")
                      heading (some-> card (.selectFirst "h5.card-header") .wholeText str/trim)
                      category (categories heading)
                      rows (vec (filter #(identical? table (.closest ^Element % "table"))
                                        (.select table "tr")))
                      header-row (first (keep-indexed (fn [j row]
                                                        (when (seq (children row "th")) j)) rows))
                      printed-headers (when header-row
                                        (mapv #(str/trim (.wholeText ^Element %))
                                              (children (nth rows header-row) "th")))
                      data-rows (vec (keep-indexed (fn [j row]
                                                     (when (seq (children row "td")) [j row])) rows))
                      supported? (and dyn? category (= headers printed-headers))]
                  {:table (inc i) :category category :headers printed-headers
                   :data-row-count (count data-rows) :supported? (boolean supported?)
                   :candidates (if supported?
                                 (mapv (fn [[j row]] (candidate source (inc i) (inc j) category row)) data-rows)
                                 [])}))
              (range) tables)
        complete? (and dyn? (= 2 (count table-data))
                       (= ["Results female" "Results male"] (mapv :category table-data))
                       (every? :supported? table-data))
        candidates (if complete? (vec (mapcat :candidates table-data)) [])
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :source-sha256 (sha256 source) :raw-html source
     :status (if complete? :needs-review :unsupported-needs-parser)
     :tables (mapv #(dissoc % :candidates) table-data)
     :candidates candidates
     :reconciliation {:table-count (count tables)
                      :printed-count (reduce + (map :data-row-count table-data))
                      :candidate-count (count candidates)
                      :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates)
                      :unsupported-table-count (count (remove :supported? table-data))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons [:owner-review-required :reconciliation-unreviewed
                             :units-not-explicit]}}))

(defn validate-artifact!
  "Replay a stored extraction against its exact UTF-8 HTML representation."
  [artifact]
  (let [source (:raw-html artifact)]
    (when-not (and (string? source)
                   (= (parse-html source) artifact))
      (throw (ex-info "Vestico HTML source replay mismatch" {})))
    artifact))
