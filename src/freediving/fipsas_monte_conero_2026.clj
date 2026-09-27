(ns freediving.fipsas-monte-conero-2026
  "Source-bound individual positions from the official 2nd Centro Sub Monte Conero PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "5a78e213c19ec2b05543da837db8b25126b275efdb9944f61ec697e748462177")
(def parser-version "fipsas-monte-conero-2026/1")

(def ^:private title "2° Trofeo Centro Sub Monte Conero")
(def ^:private heading-pattern #"Classiﬁca (\S+) - (DYNB|DYN|DNF)")
(def ^:private row-pattern #"^\s*(?:\d+\s+)?\S.+\s{2,}(?:19|20)\d{2}\s{2,}.+$")
(def ^:private time-pattern #"\d+:\d{2}\.\d{2}")
(def ^:private distance-pattern #"\d+,\d{2}")
(def ^:private points-pattern #"\d+\.\d+")
(def ^:private expected-by-page
  {1 1, 2 5, 3 2, 4 1, 5 3, 6 6, 7 10, 8 1,
   9 5, 10 5, 11 1, 12 2, 13 3, 14 2, 15 1, 16 4})

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- distance [s]
  (when (and s (re-matches distance-pattern s))
    (bigdec (str/replace s "," "."))))

(defn- page-lines [index text]
  {:page (inc index) :text text
   :lines (mapv (fn [n s] {:page (inc index) :line (inc n) :text s})
                (range) (str/split text #"\n" -1))})

(defn- result-fields [category tokens ranked?]
  (let [elite? (contains? #{"EF" "EM"} category)
        [declared realized-time realized-distance approved-time approved-distance difference points]
        (if-not ranked?
          [(first tokens) nil nil nil nil nil nil]
          (if elite?
            [(nth tokens 0 nil) (nth tokens 1 nil) (nth tokens 2 nil)
             (nth tokens 3 nil) (nth tokens 4 nil) nil (nth tokens 5 nil)]
            [(nth tokens 0 nil) (nth tokens 1 nil) (nth tokens 2 nil)
             (nth tokens 3 nil) (nth tokens 4 nil) (nth tokens 5 nil)
             (nth tokens 6 nil)]))
        valid? (if ranked?
                 (and (contains? (if elite? #{5 6} #{6 7}) (count tokens))
                      (re-matches (if elite? distance-pattern time-pattern) (or declared ""))
                      (re-matches time-pattern (or realized-time ""))
                      (re-matches distance-pattern (or realized-distance ""))
                      (= realized-time approved-time)
                      (re-matches distance-pattern (or approved-distance ""))
                      (or elite? (re-matches time-pattern (or difference "")))
                      (or (nil? points) (re-matches points-pattern points)))
                 (and (= 1 (count tokens))
                      (re-matches (if elite? distance-pattern time-pattern) (or declared ""))))]
    {:valid? valid?
     :raw {:declared declared :realized-time realized-time
           :realized-distance realized-distance :approved-time approved-time
           :approved-distance approved-distance :time-difference difference
           :points points :tokens (vec tokens)}
     :parsed {:status (if ranked? :ranked :blackout) :ranked? ranked?
              :declared-time (when-not elite? declared)
              :declared-distance (when elite? (distance declared))
              :realized-time realized-time :realized-distance (distance realized-distance)
              :approved-time approved-time :approved-distance (distance approved-distance)
              :time-difference difference :points (some-> points bigdec)
              :final-performance (distance approved-distance) :unit "m"}}))

(defn- parse-row [line section status-lines]
  (let [chunks (str/split (str/trim (:text line)) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first chunks)))
        [rank surname given club year & tokens] (if ranked? chunks (cons nil chunks))
        blackout? (and (not ranked?) (= ["BO" "SUPERFICIE"] (mapv #(str/trim (:text %)) status-lines)))
        result (result-fields (:category section) tokens ranked?)
        valid? (and (:valid? result) (or ranked? blackout?)
                    (seq surname) (seq given) (seq club)
                    (re-matches #"(?:19|20)\d\d" (or year "")))
        parsed (when valid?
                 (merge (:parsed result)
                        {:federation "FIPSAS" :event title
                         ;; This date comes from the official EventON calendar, not the PDF.
                         :event-date "2026-02-01" :event-date-source :official-calendar
                         :category (:category section) :discipline (:discipline section)
                         :source-name (str surname " " given) :surname surname
                         :given-name given :club club :birth-year (parse-long year)
                         :rank (some-> rank parse-long)}))]
    {:coordinates (select-keys line [:page :line])
     :source-lines (vec (concat [line (:heading-line section)] status-lines))
     :raw {:line (:text line)
           :fields (merge {:rank rank :surname surname :given-name given
                           :club club :birth-year year} (:raw result))
           :status (when blackout? "BO SUPERFICIE")}
     :parse-status (if valid? :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required :pdf-event-date-unprinted]
                           (not valid?) (conj :unparsed-source-line)
                           (and valid? ranked? (nil? (get-in result [:raw :points])))
                           (conj :printed-points-absent))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pages
  "Parse pdftotext -layout pages with exact PDF identity and 1-based line citations."
  [sha256 pages]
  (when-not (= source-sha256 sha256)
    (throw (ex-info "Monte Conero parser is bound to a different source PDF" {:sha256 sha256})))
  (let [page-data (mapv page-lines (range) pages)
        sections (mapv (fn [page]
                         (when-let [line (first (filter #(re-find heading-pattern (:text %)) (:lines page)))]
                           (let [[_ category discipline] (re-find heading-pattern (:text line))]
                             {:category category :discipline discipline :heading-line line}))) page-data)
        candidates (vec
                    (mapcat (fn [page section]
                              (when section
                                (let [status-lines (vec (filter #(contains? #{"BO" "SUPERFICIE"}
                                                                            (str/trim (:text %))) (:lines page)))]
                                  (map #(parse-row % section status-lines)
                                       (filter #(re-matches row-pattern (:text %)) (:lines page))))))
                            page-data sections))
        positions-by-page (frequencies (map #(get-in % [:coordinates :page]) candidates))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        blackout-count (count (filter #(= :blackout (get-in % [:parsed :status])) candidates))
        complete? (and (= 16 (count pages)) (= 16 (count (remove nil? sections)))
                       (every? #(str/includes? (:text %) title) page-data)
                       (= expected-by-page positions-by-page)
                       (= 52 (count candidates)) (= 52 parsed-count) (= 2 blackout-count))
        reconciliation {:page-count (count pages)
                        :individual-table-count (count (remove nil? sections))
                        :candidate-count (count candidates) :parsed-count parsed-count
                        :unparsed-count (- (count candidates) parsed-count)
                        :ranked-count (- parsed-count blackout-count)
                        :status-count blackout-count :positions-by-page positions-by-page
                        :coverage (if complete? :complete :partial)}]
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :status (if complete? :needs-review :partial-unsupported-needs-review)
     :pages page-data :candidates candidates :reconciliation reconciliation
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
