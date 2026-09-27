(ns freediving.fipsas-monsub-2026
  "Individual source positions in two official 2026 Monsub PDFs."
  (:require [clojure.string :as str]))

(def dnf-source-sha256 "8034f643347834b2362111cdf2637d28f6e989e6fc84be889ffeb07ddadbd824")
(def dyn-source-sha256 "884bd9272ee7446f47809e9fec6d9391a7062c1b94caaa32b479e1d20e37172c")
(def parser-version "fipsas-monsub-2026/1")
(def ^:private title "22° Trofeo Monsub / Memorial Luigino Ceppi")
(def ^:private sources {dnf-source-sha256 {:pages 7 :expected 21 :by-page {1 2, 2 1, 3 4, 4 3, 5 3, 6 4, 7 4}}
                        dyn-source-sha256 {:pages 14 :expected 49 :by-page {1 1, 2 1, 3 1, 4 5, 5 1, 6 1, 7 1, 8 7, 9 4, 10 11, 11 3, 12 2, 13 6, 14 5}}})

(defn supported? [sha] (contains? sources sha))
(defn parser-version-for [sha] (when (supported? sha) parser-version))

(def ^:private heading-pattern #"Classiﬁca (\S+) - (DNF|DYNB|DYN)")
(def ^:private row-pattern #"^\s*(?:\d+\s+)?\S.+\s{2,}(?:19|20)\d{2}\s{2,}.+$")
(def ^:private time-pattern #"\d+:\d{2}\.\d{2}")
(def ^:private distance-pattern #"\d+,\d{2}")
(def ^:private points-pattern #"\d+\.\d+")

(defn- distance [s]
  (when (re-matches distance-pattern (or s ""))
    (bigdec (str/replace s "," "."))))

(defn- page-lines [index text]
  {:page (inc index) :text text
   :lines (mapv (fn [n s] {:page (inc index) :line (inc n) :text s})
                (range) (str/split text #"\n" -1))})

(defn- field [v] {:status (if (nil? v) :unknown :parsed) :value v})

(defn- status-near [lines line]
  (let [prior (->> lines (filter #(< (:line %) (:line line))) (take-last 2))
        code-line (last (filter #(contains? #{"BO" "DQ"} (str/upper-case (str/trim (:text %)))) prior))
        note-line (when code-line
                    (first (filter #(and (> (:line %) (:line line))
                                         (<= (:line %) (+ 3 (:line line)))
                                         (not (str/blank? (:text %)))) lines)))]
    {:code (some-> code-line :text str/trim str/upper-case)
     :note (some-> note-line :text str/trim)
     :source-lines (vec (remove nil? [code-line note-line]))}))

(defn- result-fields [category tokens ranked?]
  (let [elite? (contains? #{"EF" "EM"} category)
        [declared-time declared-distance values]
        (if elite? [nil (first tokens) (rest tokens)] [(first tokens) nil (rest tokens)])
        realized-time (first values)
        realized-distance (second values)
        approved-time (nth values 2 nil)
        approved-distance (nth values 3 nil)
        tail (drop 4 values)
        difference (when (and (not elite?) (re-matches time-pattern (or (first tail) ""))) (first tail))
        points (if difference (second tail) (first tail))
        valid? (if ranked?
                 (and (re-matches (if elite? distance-pattern time-pattern)
                                  (or (if elite? declared-distance declared-time) ""))
                      (re-matches time-pattern (or realized-time ""))
                      (re-matches distance-pattern (or realized-distance ""))
                      (= realized-time approved-time)
                      (= realized-distance approved-distance)
                      (or elite? (re-matches time-pattern (or difference "")))
                      (or (nil? points) (re-matches points-pattern points))
                      (<= (count tokens) (if elite? 6 7)))
                 (and (re-matches (if elite? distance-pattern time-pattern)
                                  (or (if elite? declared-distance declared-time) ""))
                      (or (= 1 (count tokens))
                          (and (= 3 (count tokens))
                               (re-matches time-pattern (or realized-time ""))
                               (re-matches distance-pattern (or realized-distance ""))))))]
    {:valid? valid?
     :raw {:declared-time declared-time :declared-distance declared-distance
           :realized-time realized-time :realized-distance realized-distance
           :approved-time approved-time :approved-distance approved-distance
           :time-difference difference :points points :tokens (vec tokens)}
     :parsed {:declared-time declared-time :declared-distance (distance declared-distance)
              :realized-time realized-time :realized-distance (distance realized-distance)
              :approved-time approved-time :approved-distance (distance approved-distance)
              :time-difference difference :points (some-> points bigdec)
              :final-performance (when ranked? (distance approved-distance)) :unit "m"}}))

(defn- parse-row [line section lines]
  (let [chunks (str/split (str/trim (:text line)) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first chunks)))
        [rank surname given club year & tokens] (if ranked? chunks (cons nil chunks))
        status (status-near lines line)
        result (result-fields (:category section) tokens ranked?)
        valid? (and (:valid? result) (or ranked? (contains? #{"BO" "DQ"} (:code status)))
                    (seq surname) (seq given) (seq club)
                    (re-matches #"(?:19|20)\d\d" (or year "")))
        parsed (when valid?
                 (merge (:parsed result)
                        {:federation "FIPSAS" :event title
                         :event-date "2026-04-19" :event-date-source :official-calendar
                         :calendar-event-date "2026-04-19" :printed-event-date nil
                         :category (:category section) :discipline (:discipline section)
                         :source-name (str surname " " given) :surname surname :given-name given
                         :club club :birth-year (parse-long year) :rank (some-> rank parse-long)
                         :ranked? ranked? :status (if ranked? :ranked (case (:code status)
                                                                        "BO" :blackout "DQ" :disqualified :unknown))}))]
    {:coordinates (select-keys line [:page :line])
     :source-lines (vec (concat [line (:heading-line section)] (:source-lines status)))
     :raw {:line (:text line)
           :fields (merge {:rank rank :surname surname :given-name given :club club
                           :birth-year year :status (:code status) :status-note (:note status)}
                          (:raw result))}
     :parse-status (if valid? :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required :pdf-event-date-unprinted]
                           (not valid?) (conj :unparsed-source-line)
                           (and ranked? (nil? (get-in result [:raw :points])))
                           (conj :printed-points-absent))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pages [sha pages]
  (let [source (get sources sha)]
    (when-not source
      (throw (ex-info "Monsub parser is bound to two exact source PDFs" {:sha256 sha})))
    (let [page-data (mapv page-lines (range) pages)
          sections (mapv (fn [page]
                           (when-let [line (first (filter #(re-find heading-pattern (:text %)) (:lines page)))]
                             (let [[_ category discipline] (re-find heading-pattern (:text line))]
                               {:category category :discipline discipline :heading-line line}))) page-data)
          candidates (vec (mapcat (fn [page section]
                                    (when section
                                      (map #(parse-row % section (:lines page))
                                           (filter #(re-matches row-pattern (:text %)) (:lines page)))))
                                  page-data sections))
          by-page (frequencies (map #(get-in % [:coordinates :page]) candidates))
          parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
          status-count (count (filter #(contains? #{:blackout :disqualified}
                                                  (get-in % [:parsed :status])) candidates))
          complete? (and (= (:pages source) (count pages))
                         (= (:pages source) (count (remove nil? sections)))
                         (every? #(str/includes? (:text %) title) page-data)
                         (= (:by-page source) by-page)
                         (= (:expected source) parsed-count))]
      {:schema-version 3 :parser-version parser-version :source-sha256 sha
       :status (if complete? :needs-review :partial-unsupported-needs-review)
       :pages page-data :candidates candidates
       :reconciliation {:page-count (count pages) :individual-table-count (count (remove nil? sections))
                        :candidate-count (count candidates) :expected-position-count (:expected source)
                        :parsed-count parsed-count :unparsed-count (- (count candidates) parsed-count)
                        :ranked-count (- parsed-count status-count) :status-count status-count
                        :positions-by-page by-page :coverage (if complete? :complete :partial)}
       :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}})))
