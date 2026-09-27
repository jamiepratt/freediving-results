(ns freediving.fipsas-just-2025
  "Source-bound positions from the official 34th Just Apnea trophy PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "8d3847686bd6a1978018f3df3a6104c501659770a7df1a212c8fe9a45eef65a8")
(def parser-version "fipsas-just-2025/1")

(def ^:private time-pattern #"\d+:\d{2}\.\d{2}")
(def ^:private distance-pattern #"\d+,\d{2}")
(def ^:private row-pattern #"^\s*(?:\d+\s+)?\S.+\s{2,}(?:19|20)\d{2}\s{2,}.+$")
(def ^:private heading-pattern #"Classiﬁca (.+?) - (DYNB|DYN|DNF|STA)")
(def ^:private expected-positions-by-page
  {3 1, 4 3, 5 5, 6 1, 7 1, 8 1, 9 1, 10 1, 11 3, 12 1, 13 1,
   14 5, 15 7, 16 5, 17 1, 18 1, 19 5, 20 1, 21 1, 22 3, 23 1})

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- distance [s]
  (when (and s (re-matches distance-pattern s))
    (bigdec (str/replace s "," "."))))

(defn- page-lines [index text]
  {:page (inc index) :text text
   :lines (mapv (fn [n s] {:page (inc index) :line (inc n) :text s})
                (range) (str/split text #"\n" -1))})

(defn- result-fields [discipline tokens ranked?]
  (let [static? (= "STA" discipline)
        elite? (and (not static?) (re-matches distance-pattern (first tokens)))
        declared (first tokens)
        realized-time (second tokens)
        realized-distance (when-not static? (nth tokens 2 nil))
        rest-tokens (drop (if static? 2 3) tokens)
        status-token (when-not ranked? (first rest-tokens))
        penalty (when (= "PG" (first rest-tokens)) "PG")
        result-tokens (if penalty (rest rest-tokens) rest-tokens)
        [approved-time approved-distance difference points]
        (if (not ranked?) [nil nil nil nil]
            (if static? [(first result-tokens) nil nil (second result-tokens)]
                (if elite?
                  [(first result-tokens) (second result-tokens) nil (nth result-tokens 2 nil)]
                  [(first result-tokens) (second result-tokens)
                   (nth result-tokens 2 nil) (nth result-tokens 3 nil)])))
        valid? (and (if elite? (re-matches distance-pattern declared)
                        (re-matches time-pattern declared))
                    (re-matches time-pattern realized-time)
                    (or static? (re-matches distance-pattern realized-distance))
                    (if ranked?
                      (and (re-matches time-pattern approved-time)
                           (or static? (re-matches distance-pattern approved-distance))
                           (or (nil? points) (re-matches #"\d+\.\d+" points)))
                      (contains? #{"BO" "DQ"} status-token)))
        status (if ranked? :ranked (case status-token "BO" :blackout "DQ" :disqualified :unknown))]
    {:valid? valid?
     :raw {:declared (first tokens) :realized-time realized-time
           :realized-distance realized-distance :penalty penalty
           :approved-time approved-time :approved-distance approved-distance
           :time-difference difference :points points :status status-token
           :tokens (vec tokens)}
     :parsed {:status status :ranked? ranked? :declared declared
              :realized-time realized-time :realized-distance (distance realized-distance)
              :penalty penalty :approved-time approved-time
              :approved-distance (distance approved-distance)
              :time-difference difference :points (some-> points bigdec)
              :final-performance (if static? approved-time (distance approved-distance))
              :unit (if static? "min:sec.centisec" "m")}}))

(defn- parse-row [line section heading-line]
  (let [chunks (str/split (str/trim (:text line)) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first chunks)))
        [rank surname given club year & tokens]
        (if ranked? chunks (cons nil chunks))
        result (result-fields (:discipline section) tokens ranked?)
        valid? (and (:category section) (:discipline section)
                    (seq surname) (seq given) (seq club)
                    (re-matches #"(?:19|20)\d\d" (or year ""))
                    (:valid? result))
        parsed (when valid?
                 (merge (:parsed result)
                        {:federation "FIPSAS" :event "34° Trofeo Just Apnea"
                         :event-date "2025-12-13" :event-date-end "2025-12-14"
                         :category (:category section) :discipline (:discipline section)
                         :source-name (str surname " " given) :surname surname
                         :given-name given :club club :birth-year (parse-long year)
                         :rank (some-> rank parse-long)}))]
    {:coordinates (select-keys line [:page :line])
     :source-lines (vec (remove nil? [line heading-line]))
     :raw {:line (:text line)
           :fields (merge {:rank rank :surname surname :given-name given
                           :club club :birth-year year} (:raw result))}
     :parse-status (if valid? :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required :event-day-unresolved]
                           (not valid?) (conj :unparsed-source-line)
                           (and valid? ranked? (nil? (get-in result [:raw :points])))
                           (conj :printed-points-absent))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pages
  "Parse pdftotext -layout pages, retaining every individual position and 1-based citation."
  [sha256 pages]
  (when-not (= source-sha256 sha256)
    (throw (ex-info "Just Apnea parser is bound to a different source PDF" {:sha256 sha256})))
  (let [page-data (mapv page-lines (range) pages)
        whole (str/join "\n" pages)
        title? (and (str/includes? whole "34° Trofeo Just Apnea")
                    (str/includes? whole "13-14 dicembre 2025"))
        sections (mapv (fn [page]
                         (when-let [line (first (filter #(re-find heading-pattern (:text %)) (:lines page)))]
                           (let [[_ category discipline] (re-find heading-pattern (:text line))]
                             {:category category :discipline discipline :heading-line line}))) page-data)
        note-lines (vec (for [page page-data line (:lines page)
                              :when (= "R.I." (str/trim (:text line)))] line))
        candidates (->> (mapcat (fn [page section]
                                  (when section
                                    (map #(parse-row % section (:heading-line section))
                                         (filter #(re-matches row-pattern (:text %)) (:lines page)))))
                                page-data sections)
                        (mapv (fn [candidate]
                                (if-let [note (first (filter #(and (= (:page %) (get-in candidate [:coordinates :page]))
                                                                   (> (:line %) (get-in candidate [:coordinates :line])))
                                                             note-lines))]
                                  (-> candidate
                                      (update :source-lines conj note)
                                      (assoc-in [:raw :penalty-note] "R.I.")
                                      (cond-> (some? (:parsed candidate))
                                        (assoc-in [:parsed :penalty-note] "R.I.")
                                        (some? (:parsed candidate))
                                        (assoc-in [:fields :penalty-note] (field "R.I.")))
                                      (update :unresolved-reasons conj :penalty-note-meaning-unreviewed))
                                  candidate))))
        aggregates (vec (mapcat (fn [page]
                                  (when (<= (:page page) 2)
                                    (for [line (:lines page)
                                          :when (re-matches #"^\s*.+?\s{2,}.+?\s{2,}.+?\s{2,}\d+(?:\.\d+)?\s*$"
                                                            (:text line))]
                                      (select-keys line [:page :line :text])))) page-data))
        counts-by-page (frequencies (map #(get-in % [:coordinates :page]) candidates))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and title? (= 23 (count pages)) (= 21 (count (remove nil? sections)))
                       (= 49 (count candidates)) (= 49 parsed-count)
                       (= 9 (count aggregates)) (= 1 (count note-lines))
                       (= expected-positions-by-page counts-by-page))
        rec {:page-count (count pages) :individual-table-count (count (remove nil? sections))
             :club-aggregate-count (count aggregates)
             :penalty-continuation-count (count note-lines)
             :candidate-count (count candidates)
             :parsed-count parsed-count :unparsed-count (- (count candidates) parsed-count)
             :ranked-count (count (filter #(= :ranked (get-in % [:parsed :status])) candidates))
             :status-count (count (remove #(= :ranked (get-in % [:parsed :status])) candidates))
             :positions-by-page counts-by-page :coverage (if complete? :complete :partial)}]
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :status (if complete? :needs-review :partial-unsupported-needs-review)
     :pages page-data :candidates candidates
     :supporting-aggregate-lines aggregates :reconciliation rec
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
