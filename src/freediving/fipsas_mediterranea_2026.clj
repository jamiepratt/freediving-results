(ns freediving.fipsas-mediterranea-2026
  "Individual positions in the official 2026 Mediterranea Cup outdoor PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "b426fc5ef56b31d3246ffc51de327c639d2fc6a4ba682dd631afb5164522f531")
(def parser-version "fipsas-mediterranea-2026/1")
(def ^:private title "Mediterranea Cup 2026")
(def ^:private printed-date "2026-07-11/2026-07-12")
(def ^:private expected-by-page {3 1, 4 3, 5 4, 6 2, 7 3, 8 6, 9 12, 10 10})
(def ^:private heading-pattern #"Classiﬁca (J14M|OPEN F|OPEN M) - (CWTB|CWT|CNF|FIM) (PRO|OPEN)")
(def ^:private row-pattern #"^\s*(?:\d+\s+)?\S.+\s{2,}(?:19|20)\d{2}\s{2,}.+$")
(def ^:private time-pattern #"\d+:\d{2}\.\d{2}")
(def ^:private points-pattern #"\d+\.\d+")
(def ^:private aggregate-row-pattern #"^\s*\S.+\s{2,}\S.+\s{2,}\S.+\s{2,}\d+(?:\.\d+)?\s*$")

(defn supported? [sha] (= source-sha256 sha))
(defn parser-version-for [sha] (when (supported? sha) parser-version))

(defn- page-lines [index text]
  {:page (inc index) :text text
   :lines (mapv (fn [n s] {:page (inc index) :line (inc n) :text s})
                (range) (str/split text #"\n" -1))})

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- nearby [lines line predicate direction]
  (let [neighbors (if (= direction :before)
                    (->> lines (filter #(< (:line %) (:line line))) (take-last 2) reverse)
                    (->> lines (filter #(> (:line %) (:line line))) (take 2)))]
    (first (filter #(predicate (str/trim (:text %))) neighbors))))

(defn- parse-row [section lines line]
  (let [chunks (str/split (str/trim (:text line)) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first chunks)))
        [rank surname given club year & values] (if ranked? chunks (cons nil chunks))
        [declared-time declared-depth realized-time realized-depth approved-time approved-depth
         difference points] values
        status-code (when-not ranked? (nearby lines line #{"DQ"} :before))
        status-note (when status-code (nearby lines line #{"SP"} :after))
        penalty-code (when ranked? (nearby lines line #{"PG"} :before))
        penalty-note (when penalty-code (nearby lines line #{"PG no tag"} :after))
        valid? (and (seq surname) (seq given) (seq club)
                    (re-matches #"(?:19|20)\d{2}" (or year ""))
                    (re-matches time-pattern (or declared-time ""))
                    (re-matches #"\d+" (or declared-depth ""))
                    (if ranked?
                      (and (<= 7 (count values) 8)
                           (every? #(re-matches time-pattern (or % ""))
                                   [realized-time approved-time difference])
                           (every? #(re-matches #"\d+" (or % ""))
                                   [realized-depth approved-depth])
                           (or (nil? points) (re-matches points-pattern points)))
                      (and (= 2 (count values)) status-code)))
        parsed (when valid?
                 {:federation "FIPSAS" :event title
                  :event-date "2026-07-11" :event-date-source :official-calendar
                  :calendar-event-date "2026-07-11" :printed-event-date printed-date
                  :category (:category section) :discipline (:discipline section)
                  :source-name (str surname " " given) :surname surname :given-name given
                  :club club :birth-year (parse-long year)
                  :rank (some-> rank parse-long) :ranked? ranked?
                  :status (if ranked? :ranked :disqualified)
                  :declared-time declared-time :declared-depth (parse-long declared-depth)
                  :realized-time realized-time :realized-depth (some-> realized-depth parse-long)
                  :approved-time approved-time :approved-depth (some-> approved-depth parse-long)
                  :time-difference difference :points (some-> points bigdec)
                  :penalty (when penalty-code "PG")
                  :final-performance (when ranked? (parse-long approved-depth))
                  :unit "m" :source-role :individual-ranking})]
    {:coordinates (select-keys line [:page :line])
     :source-lines (vec (remove nil? [line (:heading-line section) status-code status-note
                                      penalty-code penalty-note]))
     :raw {:line (:text line)
           :fields {:rank rank :surname surname :given-name given :club club :birth-year year
                    :declared-time declared-time :declared-depth declared-depth
                    :realized-time realized-time :realized-depth realized-depth
                    :approved-time approved-time :approved-depth approved-depth
                    :time-difference difference :points points
                    :status (some-> status-code :text str/trim)
                    :status-note (some-> status-note :text str/trim)
                    :penalty (some-> penalty-code :text str/trim)
                    :penalty-note (some-> penalty-note :text str/trim)
                    :tokens (vec chunks)}}
     :parse-status (if valid? :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required :individual-day-unprinted]
                           (not valid?) (conj :unparsed-source-line)
                           (and ranked? (nil? points)) (conj :printed-points-absent))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pages
  "Parse exact pdftotext -layout pages with 1-based source line citations."
  [sha pages]
  (when-not (supported? sha)
    (throw (ex-info "Mediterranea Cup parser is bound to one exact PDF" {:sha256 sha})))
  (let [page-data (mapv page-lines (range) pages)
        supporting-tables (vec
                           (keep (fn [page]
                                   (let [kind (case (:page page)
                                                1 :club-standings
                                                2 :club-out-of-competition
                                                nil)]
                                     (when kind
                                       {:kind kind :page (:page page)
                                        :rows (vec (filter #(re-matches aggregate-row-pattern (:text %))
                                                           (:lines page)))})))
                                 page-data))
        sections (mapv (fn [page]
                         (when-let [line (first (filter #(re-find heading-pattern (:text %)) (:lines page)))]
                           (let [[_ category discipline tier] (re-find heading-pattern (:text line))]
                             {:category (str category " - " tier) :discipline discipline
                              :heading-line line}))) page-data)
        candidates (vec (mapcat (fn [page section]
                                  (when section
                                    (map #(parse-row section (:lines page) %)
                                         (filter #(re-matches row-pattern (:text %)) (:lines page)))))
                                page-data sections))
        positions-by-page (frequencies (map #(get-in % [:coordinates :page]) candidates))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        status-count (count (filter #(= :disqualified (get-in % [:parsed :status])) candidates))
        complete? (and (= 10 (count pages))
                       (= 8 (count (remove nil? sections)))
                       (= expected-by-page positions-by-page)
                       (= [12 1] (mapv (comp count :rows) supporting-tables))
                       (= 41 parsed-count)
                       (= 1 status-count)
                       (every? #(str/includes? (:text %) title) page-data)
                       (str/includes? (:text (first page-data)) "11-12 luglio 2026"))]
    {:schema-version 3 :parser-version parser-version :source-sha256 sha
     :status (if complete? :needs-review :partial-unsupported-needs-review)
     :pages page-data :supporting-tables supporting-tables :candidates candidates
     :reconciliation {:page-count (count pages)
                      :individual-table-count (count (remove nil? sections))
                      :supporting-table-count (count supporting-tables)
                      :supporting-row-count (reduce + (map (comp count :rows) supporting-tables))
                      :candidate-count (count candidates) :expected-position-count 41
                      :parsed-count parsed-count :unparsed-count (- (count candidates) parsed-count)
                      :ranked-count (- parsed-count status-count) :status-count status-count
                      :positions-by-page positions-by-page
                      :coverage (if complete? :complete :partial)}
     :publication {:status :blocked
                   :reasons [:owner-review-required :reconciliation-unreviewed]}}))
