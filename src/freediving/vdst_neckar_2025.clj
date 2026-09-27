(ns freediving.vdst-neckar-2025
  "Source-bound Cup entries from the official Neckar Apnoe Cup 2025 protocol."
  (:require [clojure.string :as str]))

(def source-sha256 "5bd837330543ca196202dae29534cdb2d1e7843781e73e949e9e8241cb5ebb12")
(def parser-version "vdst-neckar-apnoe-cup-2025/1")
(def source-url "https://www.vdst.de/wp-content/uploads/AP_NAPC2025-Protokoll.pdf")
(def event-name "1. Neckar Apnoe Cup")
(def event-date "2025-09-06")
(def expected-cup-counts [17 14 13 0])
(def expected-championship-repeat-counts [7 9 4 7])

(def ^:private title "1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft - Freibad Edition")
(def ^:private competition #"^(?:noch )?Wettkampf (\d+) - (.+)$")
(def ^:private cup-heading #"^Neckar Apnoe Cup: (.+)$")
(def ^:private championship-heading #"^(?:noch )?Landesmeisterschaft Baden-Württemberg: (.+)$")
(def ^:private printed-position #"^\s*(\d+)\.\s+(.+?)\s+(\d{4})\s{2,}(.+?)\s{2,}(\S+)\s*$")
(def ^:private championship-winner #"^\s+(.+?)\s+(\d{4})\s{2,}(.+?)\s{2,}(\S+)\s*$")
(def ^:private printed-status-person #"^\s+(.+?)\s+(\d{4})\s{2,}(.+?)\s*$")
(def ^:private distance #"(\d+(?:,\d+)?)m")
(def ^:private hundredths #"(\d{2}):([0-5]\d),(\d{2})")

(defn- line-records [page text]
  (mapv (fn [i s] {:page page :line (inc i) :text s})
        (range) (str/split text #"\n" -1)))

(defn- distance-value [printed]
  (when-let [[_ number] (re-matches distance printed)]
    (bigdec (str/replace number "," "."))))

(defn- time-value [printed]
  (when-let [[_ minutes seconds centiseconds] (re-matches hundredths printed)]
    {:components [(parse-long minutes) (parse-long seconds) (parse-long centiseconds)]
     :fraction (parse-long centiseconds) :fraction-digits 2
     :notation :colon-separated}))

(defn- discipline [competition-number]
  ({"1" "DNF" "2" "DBF" "3" "DYN" "4" "Speed-Apnoe"} competition-number))

(defn- fields [parsed]
  (into {} (map (fn [[key value]] [key {:status (if (nil? value) :unknown :parsed) :value value}]) parsed)))

(defn- candidate [line source-lines raw parsed]
  {:coordinates {:page (:page line) :line (:line line)
                 :column-start 1 :column-end (inc (count (:text line)))}
   :source-lines source-lines
   :raw (assoc raw :line (:text line))
   :parse-status (if parsed :parsed :unparsed)
   :parsed parsed
   :fields (fields parsed)
   :review-status :unreviewed
   :unresolved-reasons (cond-> [:owner-review-required]
                         (nil? parsed) (conj :unparsed-source-line))})

(defn- positioned [line context]
  (when-let [[_ rank name year club result] (re-matches printed-position (:text line))]
    (let [disc (discipline (:competition context))
          speed? (= disc "Speed-Apnoe")
          value (if speed? (time-value result) (distance-value result))
          parsed (when (and disc value)
                   (cond-> {:federation "VDST" :event-name event-name
                            :event-date event-date :discipline disc
                            :category (:category context) :gender (:gender context)
                            :source-name name :birth-year (parse-long year)
                            :club club :rank (parse-long rank)
                            :realised-performance result :unit (when-not speed? "m")}
                     speed? (assoc :realized-time value)
                     (not speed?) (assoc :performance value)))]
      (candidate line [line] {:rank rank :source-name name :birth-year year
                              :club club :result result} parsed))))

(defn- status-entry [lines i context]
  (let [line (nth lines i)
        header (:status-header context)
        active-status (:status-mode context)]
    (when-let [[_ name year club] (and active-status
                                       (re-matches printed-status-person (:text line)))]
      (let [red? (= active-status "rote Karte")
            code-line (when (< (inc i) (count lines)) (nth lines (inc i)))
            notice-line (when (< (+ i 2) (count lines)) (nth lines (+ i 2)))
            code (when red? (some-> code-line :text str/trim))
            announcement (when red? (second (re-matches #"Uhrzeit der Bekanntgabe: (\d{2}:\d{2})"
                                                        (or (some-> notice-line :text str/trim) ""))))
            status (if red? "rote Karte" "nicht am Start")
            parsed (when (and (discipline (:competition context))
                              (or (not red?) (and (#{"SP" "BO" "DQ"} code) announcement)))
                     {:federation "VDST" :event-name event-name
                      :event-date event-date :discipline (discipline (:competition context))
                      :category (:category context) :gender (:gender context)
                      :source-name name :birth-year (parse-long year) :club club
                      :rank nil :realised-performance nil :performance nil :unit nil
                      :status status :status-code code :announcement-time announcement})
            source-lines (cond-> [header line]
                           red? (conj code-line notice-line))]
        (candidate line source-lines {:source-name name :birth-year year
                                      :club club :status status :status-code code
                                      :announcement-time announcement} parsed)))))

(defn- gender [category]
  (cond (or (str/includes? category "Damen") (str/includes? category "weibliche")) "F"
        (or (str/includes? category "Herren") (str/includes? category "männliche")) "M"))

(defn- scan-page [page text start-context]
  (let [lines (line-records page text)]
    (loop [i 0 context start-context candidates [] repeats []]
      (if (= i (count lines))
        {:page {:page page :text text :lines lines :status :needs-review}
         :context context :candidates candidates :repeats repeats}
        (let [line (nth lines i)
              s (str/trim (:text line))
              match-competition (re-matches competition s)
              status-heading? (#{"nicht am Start" "rote Karte"} s)
              match-cup (re-matches cup-heading s)
              match-championship (re-matches championship-heading s)
              context (cond match-competition (assoc context :competition (nth match-competition 1) :view nil :category nil :gender nil :status-mode nil :status-header nil)
                            match-cup (assoc context :view :cup :category (nth match-cup 1)
                                             :gender (gender (nth match-cup 1))
                                             :status-mode nil :status-header nil)
                            match-championship (assoc context :view :championship :category (nth match-championship 1)
                                                      :status-mode nil :status-header nil)
                            status-heading? (assoc context :status-mode s :status-header line)
                            :else context)
              position (when (= :cup (:view context)) (positioned line context))
              status (when (and (= :cup (:view context)) (nil? position))
                       (status-entry lines i context))
              repeat (when (and (= :championship (:view context))
                                (or (re-matches printed-position (:text line))
                                    (and (pos? i)
                                         (str/starts-with? (str/trim (:text (nth lines (dec i)))) "1. und Baden-Württemberg")
                                         (re-matches championship-winner (:text line)))))
                       line)]
          (recur (inc i) context
                 (cond-> candidates position (conj position) status (conj status))
                 (cond-> repeats repeat (conj repeat))))))))

(defn parse-pages
  "Parse exact pdftotext -layout page strings from the official four-page PDF.
   Coordinates are 1-based PDF page and text line. Cup status entries are retained;
   championship standings are counted as repeat views, not candidates."
  [sha256 pages]
  (let [whole (str/join "\n" pages)
        supported? (and (= source-sha256 sha256)
                        (= 4 (count pages))
                        (every? #(str/includes? % title) pages)
                        (str/includes? whole "Abschnitt 1 - Samstag 06.09.2025")
                        (every? true? (map-indexed
                                       (fn [i page]
                                         (str/includes? page (str "Seite " (+ 10 i)))) pages)))
        scanned (when supported?
                  (loop [i 0 context {} result []]
                    (if (= i (count pages)) result
                        (let [x (scan-page (inc i) (nth pages i) context)]
                          (recur (inc i) (:context x) (conj result x))))))
        candidates (vec (mapcat :candidates scanned))
        repeats (vec (mapcat :repeats scanned))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        ranked-count (count (filter #(some? (get-in % [:parsed :rank])) candidates))
        status-count (count (filter #(some? (get-in % [:parsed :status])) candidates))
        unparsed-count (- (count candidates) parsed-count)
        complete? (and supported?
                       (zero? unparsed-count)
                       (= (reduce + expected-cup-counts) (count candidates))
                       (= (reduce + expected-championship-repeat-counts) (count repeats))
                       (= expected-cup-counts (mapv #(count (:candidates %)) scanned))
                       (= expected-championship-repeat-counts (mapv #(count (:repeats %)) scanned)))]
    {:schema-version 3 :parser-version parser-version :source-sha256 sha256
     :source-url source-url
     :status (cond (not supported?) :unsupported-needs-parser
                   complete? :needs-review
                   :else :partial-unsupported-needs-parser)
     :pages (mapv :page scanned) :candidates candidates
     :reconciliation {:page-count (count pages)
                      :coverage (if complete? :complete :partial)
                      :cup-entry-count (count candidates)
                      :candidate-count (count candidates)
                      :ranked-count ranked-count :status-count status-count
                      :parsed-count parsed-count :unparsed-count unparsed-count
                      :unresolved-count (count candidates)
                      :championship-repeat-count (count repeats)
                      :championship-repeat-lines repeats
                      :per-page (mapv (fn [item]
                                        {:page (get-in item [:page :page])
                                         :cup-entry-count (count (:candidates item))
                                         :championship-repeat-count (count (:repeats item))}) scanned)
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons [:owner-review-required :reconciliation-unreviewed]}}))
