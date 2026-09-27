(ns freediving.fipsas-classifica-2026
  "Source-bound positions from eight official 2026 FIPSAS classifica PDFs."
  (:require [clojure.string :as str]))

(def parser-version "fipsas-classifica-2026/2")

(def ^:private sources
  {"44f8dc155619d30190270979b11e4ae3d5c9a32d9bf6023bc690c3e070ff9a66"
   {:index 1 :title "3° Friuli Apnea Challenge" :calendar-date "2026-02-01" :printed-date "2026-02-01" :date-label "1 febbraio 2026" :expected 105}
   "55b997b8805c948a53f91dca08dba7715476fcd16a4a31c2f43862b3a1467909"
   {:index 2 :title "4° Trofeo One Wave" :calendar-date "2026-02-01" :printed-date "2026-02-01" :date-label "1 febbraio 2026" :expected 148}
   "88571d23a491961fe344d68591179b2aa383c014a9ac125260f920084c5335f6"
   {:index 3 :title "6° Trofeo Apnea Life Team" :calendar-date "2026-02-08" :printed-date "2026-02-15" :date-label "15 febbraio 2026" :expected 116}
   "2a6a18f7561823dffced7218e94449f819ac9f1c0b559e9705c2d0d8b7f6fe86"
   {:index 4 :title "Naonis Breath" :calendar-date "2026-02-15" :printed-date "2026-02-15" :date-label "15 febbraio 2026" :expected 144}
   "a01767a5d970df29b3005cf8225bd989147942e944e3837d389e7dc4411373a6"
   {:index 5 :title "3° Trofeo Blue World Freediving Apnea Indoor" :calendar-date "2026-02-15" :printed-date "2026-02-22" :date-label "22 febbraio 2026" :expected 50}
   "a4efaf7f3030c3ea8ff3a166fe96533509c359b808323410da7e78da0947a5cf"
   {:index 6 :title "16° Trofeo Apnea Romagna e San Marino" :calendar-date "2026-03-01" :printed-date "2026-03-01" :date-label "1 marzo 2026" :expected 116}
   "61206a55fd94c1f7291d7527014151671f052bfa5597d88923284af8f60a7786"
   {:index 7 :title "3° Trofeo Colapesce" :calendar-date "2026-03-01" :printed-date "2026-03-01" :date-label "1 marzo 2026" :expected 44}
   "7fb6f8cdf125aaba954b48848ab4a8be36eace5af30e6c4760e4e48cfe89a448"
   {:index 8 :title "16° Trofeo Veneto Apnea" :calendar-date "2026-03-08" :printed-date "2026-03-08" :date-label "8 marzo 2026" :expected 172}
   "f21d1ed286a97c55db8832fcee79fbdcf126822b3654d3f31d7914097a510998"
   {:index 9 :title "22° Campionato Regionale Toscano / 5° Memorial Marino Vannucchi"
    :calendar-date "2026-03-08" :printed-date "2026-03-08" :date-label "8 marzo 2026"
    :source-role :regional-supporting-ranking :expected 26}
   "5482b97c76f6e61b2414116af605c9b7c2902b842f89e9b7e9014ca18e7398cb"
   {:index 10 :title "22° Campionato Regionale Toscano / 5° Memorial Marino Vannucchi"
    :calendar-date "2026-03-08" :printed-date "2026-03-08" :date-label "8 marzo 2026" :expected 61}
   "c81c2a99ce8cf1a9ab2824d384d415791cba30d1cd372877a3a896a74f489e5c"
   {:index 11 :title "35° Trofeo Just Apnea" :calendar-date "2026-03-14"
    :printed-date "2026-03-14" :date-label "14 marzo 2026" :expected 45}
   "50aa6b9f3ddd674ac63f125b3fd1fd923da6aa89d26af2fb670c6fa2ac192f66"
   {:index 12 :title "MediTerraNea Cup Indoor 2026" :calendar-date "2026-03-15"
    :printed-date "2026-03-15" :date-label "15 marzo 2026" :expected 49}
   "d28e6de1b4c353b1d9a94e283ee310a597264dd8e6d85cc31594782b61749e88"
   {:index 13 :title "Campionati Italiani per Categorie Indoor" :calendar-date "2026-03-20"
    :printed-date "2026-03-20/2026-03-22" :date-label "20-22 marzo 2026" :expected 518}
   "d36a52145021ac469fcfad31d7d30ce9a4a80d4737c3d7cbe2ef7fe9f3d74d1b"
   {:index 14 :title "7° Trofeo Città di Sestri Levante" :calendar-date "2026-04-12"
    :printed-date "2026-04-12" :date-label "12 aprile 2026" :expected 171}})

(defn source-sha256 [index]
  (some (fn [[sha source]] (when (= index (:index source)) sha)) sources))

(defn supported? [sha] (contains? sources sha))

(defn parser-version-for [sha]
  (when-let [source (get sources sha)]
    (if (<= (:index source) 8) "fipsas-classifica-2026/1" parser-version)))

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

(defn- result-fields [discipline category tokens ranked?]
  (let [penalty (when (= 1 (count (filter #{"PG"} tokens))) "PG")
        normalized-tokens (if penalty (remove #{"PG"} tokens) tokens)
        distance-only? (re-matches distance-pattern (or (first normalized-tokens) ""))
        time-only? (or (= "STA" discipline)
                       (and (contains? #{"SPEED" "END4" "END8"} discipline)
                            (<= (count normalized-tokens) 4)))
        speed? (contains? #{"SPEED" "END4" "END8"} discipline)
        elite? (and (contains? #{"EF" "EM"} (first (str/split category #" ")))
                    (not time-only?) (not speed?))
        two-declarations? (and elite? (re-matches time-pattern (or (first normalized-tokens) "")))
        [declared-time declared-distance values]
        (cond two-declarations? [(first normalized-tokens) (second normalized-tokens) (drop 2 normalized-tokens)]
              distance-only? [nil (first normalized-tokens) (rest normalized-tokens)]
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
                    (or (re-matches time-pattern (or declared-time ""))
                        (re-matches distance-pattern (or declared-distance "")))
                    (re-matches time-pattern (or realized-time ""))
                    (or time-only? (re-matches distance-pattern (or realized-distance "")))
                    (= realized-time approved-time)
                    (or time-only? (re-matches distance-pattern (or approved-distance "")))
                    (or (nil? points) (re-matches points-pattern points))
                    (<= (count normalized-tokens) (if two-declarations? 7 (if time-only? 4 (if elite? 6 7)))))
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

(defn- nearby-status [lines line]
  (let [preceding (->> lines (filter #(< (:line %) (:line line))) (take-last 2) reverse)
        code (some #(when (contains? #{"BO" "DQ"} (str/upper-case (str/trim (:text %)))) %) preceding)
        inline (last (str/split (str/trim (:text line)) #"\s{2,}"))
        status (or (some-> code :text str/trim str/upper-case)
                   (when (contains? #{"BO" "DQ"} inline) inline))
        note (when code (first (filter #(and (> (:line %) (:line line))
                                             (<= (:line %) (+ 3 (:line line)))
                                             (not (str/blank? (:text %)))) lines)))]
    {:status status :source-lines (vec (remove nil? [code note]))
     :note (some-> note :text str/trim)}))

(defn- parse-row [source line section lines]
  (let [chunks (str/split (str/trim (:text line)) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first chunks)))
        [rank surname given club birth-year & tokens]
        (if ranked? chunks (cons nil chunks))
        status-data (nearby-status lines line)
        status (:status status-data)
        results (result-fields (:discipline section) (:category section) tokens ranked?)
        identity-valid? (and (seq surname) (seq given) (seq club)
                             (re-matches #"(?:19|20)\d\d" (or birth-year "")))
        valid? (and identity-valid? (or (:valid? results)
                                        (and (not ranked?) (contains? #{"BO" "DQ"} status))
                                        (and (not ranked?) (nil? status)
                                             (re-matches time-pattern (or (get-in results [:raw :realized-time]) ""))
                                             (= (get-in results [:raw :realized-time])
                                                (get-in results [:raw :approved-time])))))
        conflict? (not= (:calendar-date source) (:printed-date source))
        parsed (when valid?
                 (merge (:parsed results)
                        {:federation "FIPSAS" :event (:title source)
                         :event-date (when-not conflict? (:printed-date source))
                         :event-date-source (when-not conflict? :official-calendar-and-pdf)
                         :calendar-event-date (:calendar-date source)
                         :printed-event-date (:printed-date source)
                         :category (:category section) :discipline (:discipline section)
                         :source-name (str surname " " given) :surname surname
                         :given-name given :club club :birth-year (parse-long birth-year)
                         :rank (some-> rank parse-long) :ranked? ranked?
                         :status (if ranked? :ranked (case status "BO" :blackout "DQ" :disqualified :unknown))
                         :final-performance (when (or ranked? (nil? status))
                                              (get-in results [:parsed :final-performance]))}
                        (when (> (:index source) 8)
                          {:source-role (or (:source-role source)
                                            (when (str/starts-with? (:category section) "Junior ")
                                              :junior-supporting-ranking)
                                            :individual-ranking)})))]
    {:coordinates (select-keys line [:page :line])
     :source-lines (vec (concat [line (:heading-line section)] (:source-lines status-data)))
     :raw {:line (:text line)
           :fields (merge {:rank rank :surname surname :given-name given :club club
                           :birth-year birth-year :status status :status-note (:note status-data)}
                          (:raw results))}
     :parse-status (if valid? :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           conflict? (conj :event-date-conflict)
                           (not valid?) (conj :unparsed-source-line)
                           (and (not ranked?) (nil? status)) (conj :unprinted-status)
                           (and valid? ranked? (nil? (get-in results [:raw :points])))
                           (conj :printed-points-absent))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pages [sha256 pages]
  (let [source (get sources sha256)]
    (when-not source
      (throw (ex-info "FIPSAS classifica parser is bound to eight exact source PDFs" {:sha256 sha256})))
    (when-not (and (seq pages) (str/includes? (first pages) (:title source))
                   (str/includes? (first pages) (:date-label source)))
      (throw (ex-info "FIPSAS title or printed date does not match bound source" {:sha256 sha256})))
    (let [page-data (mapv page-lines (range) pages)
          [candidates sections]
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
          parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
          positions-by-page (frequencies (map #(get-in % [:coordinates :page]) candidates))
          complete? (and (= (:expected source) (count candidates))
                         (= parsed-count (count candidates)))
          rec {:page-count (count pages) :individual-table-count (count sections)
               :candidate-count (count candidates) :expected-position-count (:expected source)
               :parsed-count parsed-count :unparsed-count (- (count candidates) parsed-count)
               :ranked-count (count (filter #(= :ranked (get-in % [:parsed :status])) candidates))
               :status-count (count (remove #(= :ranked (get-in % [:parsed :status])) candidates))
               :unknown-status-count (count (filter #(= :unknown (get-in % [:parsed :status])) candidates))
               :positions-by-page positions-by-page
               :coverage (if complete? :complete :partial)}]
      {:schema-version 3 :parser-version (parser-version-for sha256) :source-sha256 sha256
       :status (if complete? :needs-review :partial-unsupported-needs-review)
       :pages page-data :candidates candidates :reconciliation rec
       :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}})))
