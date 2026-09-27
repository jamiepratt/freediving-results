(ns freediving.fipsas-championship-2026
  "Source-bound positions from official June 2026 FIPSAS championships."
  (:require [clojure.string :as str]))

(def parser-version "fipsas-championship-2026/1")

(def ^:private sources
  {"a80317270e08d8b93b31778a88d12d77271cdd22b7cc50a77b945f835e5eae68"
   {:index 6 :title "Campionati Italiani Assoluti di Apnea Indoor"
    :athlete-group :open :calendar-date "2026-06-12"
    :printed-date "2026-06-12/2026-06-14" :date-label "12-14 giugno 2026" :expected 339 :expected-primary 330 :expected-pages 49}
   "6340ff918d44e6a7c9de8292a03e995251e98592fa56d1e9469f760503e1cc08"
   {:index 7 :title "Campionati Italiani Paralimpici di Apnea Indoor (atleti ﬁsici e sensoriali)"
    :athlete-group :physical-and-sensory :calendar-date "2026-06-12"
    :printed-date "2026-06-12/2026-06-14" :date-label "12-14 giugno 2026" :expected 42 :expected-primary 42 :expected-pages 33}
   "92bf1e849540fbf555b90043f5f66fe7c7da37c3e82fdbdf8ae1595cfd8ab7ba"
   {:index 8 :title "Campionati Italiani Paralimpici di Apnea Indoor (atleti int. - relaz.)"
    :athlete-group :intellectual-and-relational :calendar-date "2026-06-12"
    :printed-date "2026-06-12/2026-06-14" :date-label "12-14 giugno 2026" :expected 55 :expected-primary 55 :expected-pages 20}})

(defn source-sha256 [index]
  (some (fn [[sha source]] (when (= index (:index source)) sha)) sources))

(defn supported? [sha] (contains? sources sha))

(defn parser-version-for [sha]
  (when (supported? sha) parser-version))

(def ^:private heading-pattern #"Classiﬁca (?:regionale )?(.+?) - (DNF|DYNB|DYN|STA|END4|END8|SPEED)\s*$")
(def ^:private row-pattern #"^\s*(?:\d+\s+)?\S.+\s{2,}(?:19|20)\d{2}\s{2,}.+$")
(def ^:private time-pattern #"\d+:\d{2}\.\d{2}")
(def ^:private distance-pattern #"\d+,\d{2}")
(def ^:private points-pattern #"\d+\.\d+")

(defn- page-lines [index text]
  {:page (inc index) :text text
   :lines (mapv (fn [n s] {:page (inc index) :line (inc n) :text s})
                (range) (str/split text #"\n" -1))})

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- decimal-distance [s]
  (when (and s (re-matches distance-pattern s))
    (bigdec (str/replace s "," "."))))

(defn- result-fields [source discipline category tokens ranked?]
  (let [penalty (when (= 1 (count (filter #{"PG"} tokens))) "PG")
        new-source? (> (:index source) 14)
        normalized-tokens (if penalty (remove #{"PG"} tokens) tokens)
        distance-only? (re-matches distance-pattern (or (first normalized-tokens) ""))
        time-only? (or (= "STA" discipline)
                       (and (contains? #{"SPEED" "END4" "END8"} discipline)
                            (<= (count normalized-tokens) 4)))
        speed? (contains? #{"SPEED" "END4" "END8"} discipline)
        elite? (and (contains? #{"EF" "EM"} (first (str/split category #" ")))
                    (not time-only?) (not speed?))
        declaration-absent? (and (= 7 (:index source))
                                 (= "EM (1)" category)
                                 (= 5 (count normalized-tokens))
                                 (re-matches time-pattern (or (first normalized-tokens) ""))
                                 (re-matches distance-pattern (or (second normalized-tokens) ""))
                                 (= (first normalized-tokens) (nth normalized-tokens 2 nil))
                                 (= (second normalized-tokens) (nth normalized-tokens 3 nil)))
        two-declarations? (and (not time-only?) (not declaration-absent?)
                               (re-matches time-pattern (or (first normalized-tokens) ""))
                               (re-matches distance-pattern (or (second normalized-tokens) "")))
        [declared-time declared-distance values]
        (cond declaration-absent? [nil nil normalized-tokens]
              two-declarations? [(first normalized-tokens) (second normalized-tokens) (drop 2 normalized-tokens)]
              distance-only? [nil (first normalized-tokens) (rest normalized-tokens)]
              (and new-source? elite? (re-matches time-pattern (or (first normalized-tokens) "")))
              [(first normalized-tokens) nil (rest normalized-tokens)]
              elite? [nil (first normalized-tokens) (rest normalized-tokens)]
              :else [(first normalized-tokens) nil (rest normalized-tokens)])
        realized-time (first values)
        realized-distance (when-not time-only? (second values))
        remainder (drop (if time-only? 1 2) values)
        approved-time (first remainder)
        approved-distance (when-not time-only? (second remainder))
        tail (drop (if time-only? 1 2) remainder)
        difference (when (and (not elite?) (not time-only?) (not speed?)
                              (re-matches time-pattern (or (first tail) ""))) (first tail))
        points (if difference (second tail) (first tail))
        valid? (and ranked?
                    (or declaration-absent?
                        (re-matches time-pattern (or declared-time ""))
                        (re-matches distance-pattern (or declared-distance "")))
                    (re-matches time-pattern (or realized-time ""))
                    (or time-only? (re-matches distance-pattern (or realized-distance "")))
                    (= realized-time approved-time)
                    (or time-only? (re-matches distance-pattern (or approved-distance "")))
                    (or (nil? points) (re-matches points-pattern points))
                    (<= (count normalized-tokens) (if two-declarations? 8 (if time-only? 4 (if elite? 6 7)))))
        performance (if (or time-only? speed?) approved-time (decimal-distance approved-distance))]
    {:valid? valid?
     :raw (cond-> {:declared-time declared-time :declared-distance declared-distance
                   :realized-time realized-time :realized-distance realized-distance
                   :approved-time approved-time :approved-distance approved-distance
                   :time-difference difference :points points :tokens (vec tokens)}
            penalty (assoc :penalty penalty))
     :parsed (cond-> {:declared-time declared-time :declared-distance (decimal-distance declared-distance)
                      :realized-time realized-time :realized-distance (decimal-distance realized-distance)
                      :approved-time approved-time :approved-distance (decimal-distance approved-distance)
                      :time-difference difference :points (when (and points (re-matches points-pattern points))
                                                            (bigdec points))
                      :final-performance performance :unit (if (or time-only? speed?) "min:sec.centisec" "m")}
               penalty (assoc :penalty penalty))}))

(defn- nearby-status [source lines line ranked?]
  (let [preceding (->> lines (filter #(< (:line %) (:line line))) (take-last 2) reverse)
        code (some #(when (contains? #{"BO" "DQ"} (str/upper-case (str/trim (:text %)))) %) preceding)
        absent (when (and (= 15 (:index source)) (not ranked?))
                 (first (filter #(and (> (:line %) (:line line))
                                      (<= (:line %) (+ 2 (:line line)))
                                      (= "Assente" (str/trim (:text %)))) lines)))
        inline (last (str/split (str/trim (:text line)) #"\s{2,}"))
        status (or (some-> code :text str/trim str/upper-case)
                   (when absent "Assente")
                   (when (contains? #{"BO" "DQ"} inline) inline))
        note (when code (first (filter #(and (> (:line %) (:line line))
                                             (<= (:line %) (+ 3 (:line line)))
                                             (not (str/blank? (:text %)))) lines)))]
    {:status status :source-lines (vec (remove nil? [code absent note]))
     :note (some-> note :text str/trim)}))

(defn- parse-row [source line section lines]
  (let [chunks (str/split (str/trim (:text line)) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first chunks)))
        [rank surname given club birth-year & tokens]
        (if ranked? chunks (cons nil chunks))
        status-data (nearby-status source lines line ranked?)
        status (:status status-data)
        results (result-fields source (:discipline section) (:category section) tokens ranked?)
        annotations (->> lines
                         (filter #(and (> (:line %) (:line line))
                                       (<= (:line %) (+ 3 (:line line)))
                                       (contains? #{"PG" "record italiano" "PARTENZA DOPO ZERO"}
                                                  (str/trim (:text %)))))
                         vec)
        printed-penalty (when (some #(= "PG" (str/trim (:text %))) annotations) "PG")
        identity-valid? (and (seq surname) (seq given) (seq club)
                             (re-matches #"(?:19|20)\d\d" (or birth-year "")))
        valid? (and identity-valid? (or (:valid? results)
                                        (and (not ranked?) (contains? #{"BO" "DQ" "Assente"} status))
                                        (and (not ranked?) (nil? status)
                                             (re-matches time-pattern (or (get-in results [:raw :realized-time]) ""))
                                             (= (get-in results [:raw :realized-time])
                                                (get-in results [:raw :approved-time])))))
        date-range? (str/includes? (:printed-date source) "/")
        conflict? (and (not date-range?)
                       (not= (:calendar-date source) (:printed-date source)))
        parsed (when valid?
                 (merge (:parsed results)
                        (when printed-penalty {:penalty printed-penalty})
                        {:federation "FIPSAS" :event (:title source)
                         :event-date (when-not (or conflict? date-range?) (:printed-date source))
                         :event-date-source (when-not (or conflict? date-range?) :official-calendar-and-pdf)
                         :calendar-event-date (:calendar-date source)
                         :printed-event-date (:printed-date source)
                         :category (:category section) :discipline (:discipline section)
                         :athlete-group (:athlete-group source)
                         :source-name (str surname " " given) :surname surname
                         :given-name given :club club :birth-year (parse-long birth-year)
                         :rank (some-> rank parse-long) :ranked? ranked?
                         :status (if ranked? :ranked (case status "BO" :blackout "DQ" :disqualified
                                                           "Assente" :absent :unknown))
                         :final-performance (when (or ranked? (nil? status))
                                              (get-in results [:parsed :final-performance]))}
                        {:source-role (or (when (str/starts-with? (:category section) "Junior ")
                                            :junior-supporting-ranking)
                                          :individual-ranking)}))]
    {:coordinates (select-keys line [:page :line])
     :source-lines (vec (concat [line (:heading-line section)] (:source-lines status-data) annotations))
     :raw {:line (:text line)
           :fields (merge {:rank rank :surname surname :given-name given :club club
                           :birth-year birth-year :status status :status-note (:note status-data)
                           :annotations (mapv (comp str/trim :text) annotations)}
                          (when printed-penalty {:penalty printed-penalty})
                          (:raw results))}
     :parse-status (if valid? :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           conflict? (conj :event-date-conflict)
                           date-range? (conj :event-date-range)
                           (not valid?) (conj :unparsed-source-line)
                           (and valid? (nil? (get-in results [:raw :declared-time]))
                                (nil? (get-in results [:raw :declared-distance])))
                           (conj :declaration-not-printed)
                           (and (not ranked?) (nil? status)) (conj :unprinted-status)
                           (and valid? ranked? (nil? (get-in results [:raw :points])))
                           (conj :printed-points-absent))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(def ^:private matching-fields
  [:source-name :birth-year :discipline :final-performance :status
   :printed-event-date :athlete-group])

(defn- matching-key [row]
  (select-keys (:parsed row) matching-fields))

(defn- partition-supporting-positions [rows]
  (let [primary (remove #(= :junior-supporting-ranking
                            (get-in % [:parsed :source-role])) rows)
        primary-by-key (group-by matching-key primary)
        supporting (->> rows
                        (filter #(= :junior-supporting-ranking
                                    (get-in % [:parsed :source-role])))
                        (keep (fn [row]
                                (when-let [match (when (= 1 (count (get primary-by-key (matching-key row))))
                                                   (first (get primary-by-key (matching-key row))))]
                                  (assoc row :primary-coordinates (:coordinates match)
                                         :matching-fields (matching-key row)))))
                        vec)
        supporting-coordinates (set (map :coordinates supporting))
        candidates (mapv (fn [row]
                           (if (and (= :junior-supporting-ranking
                                       (get-in row [:parsed :source-role]))
                                    (not (contains? supporting-coordinates (:coordinates row))))
                             (-> row
                                 (assoc-in [:parsed :source-role] :unverified-junior-ranking)
                                 (update :unresolved-reasons conj :supporting-link-unverified))
                             row))
                         (remove #(contains? supporting-coordinates (:coordinates %)) rows))]
    {:candidates candidates :supporting-positions supporting}))

(defn parse-pages [sha256 pages]
  (let [source (get sources sha256)]
    (when-not source
      (throw (ex-info "FIPSAS championship parser is bound to exact source PDFs" {:sha256 sha256})))
    (when-not (and (seq pages) (str/includes? (first pages) (:title source))
                   (str/includes? (first pages) (:date-label source)))
      (throw (ex-info "FIPSAS title or printed date does not match bound source" {:sha256 sha256})))
    (let [page-data (mapv page-lines (range) pages)
          [printed-positions sections]
          (reduce (fn [[candidates sections] page]
                    (let [heading-line (first (filter #(re-find heading-pattern (:text %)) (:lines page)))
                          [_ category discipline] (when heading-line (re-find heading-pattern (:text heading-line)))
                          section (if heading-line
                                    {:category category :discipline discipline :heading-line heading-line}
                                    (last sections))
                          rows (if section
                                 (mapv #(parse-row source % section (:lines page))
                                       (filter #(re-matches row-pattern (:text %)) (:lines page))) [])]
                      [(into candidates rows) (cond-> sections heading-line (conj section))]))
                  [[] []] page-data)
          {:keys [candidates supporting-positions]}
          (partition-supporting-positions printed-positions)
          parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
          positions-by-page (frequencies (map #(get-in % [:coordinates :page]) candidates))
          complete? (and (= (:expected-pages source) (count pages))
                         (= (:expected source) (count printed-positions))
                         (= (:expected-primary source) (count candidates))
                         (= parsed-count (count candidates))
                         (every? #(= :parsed (:parse-status %)) supporting-positions))
          rec {:page-count (count pages) :expected-page-count (:expected-pages source) :individual-table-count (count sections)
               :printed-position-count (count printed-positions)
               :candidate-count (count candidates) :expected-position-count (:expected source)
               :expected-primary-count (:expected-primary source)
               :supporting-position-count (count supporting-positions)
               :supporting-parsed-count (count (filter #(= :parsed (:parse-status %)) supporting-positions))
               :parsed-count parsed-count :unparsed-count (- (count candidates) parsed-count)
               :ranked-count (count (filter #(= :ranked (get-in % [:parsed :status])) candidates))
               :status-count (count (remove #(= :ranked (get-in % [:parsed :status])) candidates))
               :unknown-status-count (count (filter #(= :unknown (get-in % [:parsed :status])) candidates))
               :positions-by-page positions-by-page
               :printed-positions-by-page (frequencies (map #(get-in % [:coordinates :page]) printed-positions))
               :coverage (if complete? :complete :partial)}]
      {:schema-version 3 :parser-version (parser-version-for sha256) :source-sha256 sha256
       :status (if complete? :needs-review :partial-unsupported-needs-review)
       :pages page-data :candidates candidates :supporting-positions supporting-positions
       :reconciliation rec
       :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}})))
