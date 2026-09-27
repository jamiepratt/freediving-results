(ns freediving.vdst-rhein-main-2025
  "Source-bound transcription of the official 22nd Rhein-Main Cup protocol."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "vdst-rhein-main-cup-2025/1")
(def source-sha256 "4b9044484682e1a0d96633a9e3993fd8942f90bc6872bd7a32877c4303e49c19")
(def source-url "https://www.vdst.de/wp-content/uploads/AP_RMC2025-Protokoll.pdf")
(def event-date "2025-09-27")
(def result-pattern
  #"^\s*(?:(\d+)\.\s+)?(.+?)\s{2,}(\d{4})\s+(.+?)(?:\s{2,}(\d{2}:\d{2},\d{2}|\d+,\dm))?\s*$")
(def hessian-pattern
  #"^\s*(?:(\d+)\.\s+)?(.+?)\s{2,}(\d{4})\s+(.+?)\s{2,}(\d{2}:\d{2},\d{2})\s*$")

(defn- fail! [message data] (throw (ex-info message data)))

(defn- sha256-file [path]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
    (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn supported? [pages]
  (let [whole (str/join "\n" pages)]
    (and (= 7 (count pages))
         (str/includes? whole "22. Rhein-Main Cup 2025")
         (str/includes? whole "Hessische Statik Meisterschaft")
         (str/includes? whole "Protokollende: 15:55 Uhr"))))

(defn- lines-for [pages]
  (mapv (fn [page-index page]
          (mapv (fn [line-index text]
                  {:page (inc page-index) :line (inc line-index) :text text})
                (range) (str/split page #"\n" -1)))
        (range) pages))

(defn- discipline [text]
  (cond
    (str/includes? text "Wettkampf 1 - Statik") "STA"
    (str/includes? text "Wettkampf 2 - 2 x 25m") "Speed-Apnea 2 x 25m"
    (str/includes? text "Wettkampf 3 - DBF") "DBF"
    (str/includes? text "Wettkampf 4 - DYN") "DYN"
    (str/includes? text "Wettkampf 5 - DNF") "DNF"
    (str/includes? text "Wettkampf 6 - 4 x 25m") "Speed-Apnea 4 x 25m"))

(defn- value [printed]
  (when printed
    (if (str/ends-with? printed "m")
      {:distance (Double/parseDouble (str/replace (subs printed 0 (dec (count printed))) "," "."))
       :unit "m"}
      (let [[_ minutes seconds centiseconds] (re-matches #"(\d{2}):(\d{2}),(\d{2})" printed)]
        {:time-seconds (+ (* 60 (parse-long minutes)) (parse-long seconds)
                          (/ (parse-long centiseconds) 100.0))
         :unit "min:sec,centisec"}))))

(defn- source-lines [lines i status-line has-code?]
  (cond-> (if status-line [status-line (nth lines i)] [(nth lines i)])
    has-code? (conj (nth lines (inc i)))))

(defn- candidate [lines i {:keys [discipline category pending-status
                                  pending-status-line discipline-line category-line date-line]}]
  (let [line (nth lines i)
        [_ rank name year club result] (re-matches result-pattern (:text line))
        next-line (some-> (get lines (inc i)) :text str/trim)
        code (when (and next-line (re-matches #"(?:DQ|BQ)\s+[A-Z ]+" next-line)) next-line)
        status (cond rank :ranked
                     code :disqualified
                     (= pending-status "abgemeldet") :withdrawn
                     (= pending-status "nicht am Start") :did-not-start
                     (= pending-status "rote Karte") :disqualified
                     (= pending-status "außer Konkurrenz") :outside-competition
                     :else :unknown)
        printed (some-> result str/trim)
        parsed (merge {:federation "VDST" :event "22. Rhein-Main Cup 2025"
                       :event-date event-date :discipline discipline :category category
                       :gender (cond (str/includes? category "Damen") "Female"
                                     (or (str/includes? category "Herren")
                                         (str/includes? category "männliche")) "Male")
                       :source-name (str/trim name) :birth-year (parse-long year)
                       :club (str/trim club) :rank (some-> rank parse-long)
                       :status status :status-code code :result printed}
                      (value printed))]
    {:coordinates {:page (:page line) :line (:line line)}
     :source-lines (source-lines lines i (when-not rank pending-status-line) (boolean code))
     :metadata-evidence [date-line discipline-line category-line]
     :raw {:line (:text line)
           :fields {:rank rank :source-name (str/trim name) :birth-year year
                    :club (str/trim club) :result printed :status-heading pending-status
                    :status-code code}}
     :parse-status (if (= status :unknown) :unparsed :parsed)
     :parsed (when-not (= status :unknown) parsed)
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (= status :unknown) (conj :unrecognized-status))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn- page-rows [lines initial]
  (loop [i 0 state initial rows [] repeats []]
    (if (= i (count lines))
      {:state state :rows rows :repeats repeats}
      (let [line (nth lines i)
            text (str/trim (:text line))
            event (discipline text)
            hessian? (str/starts-with? text "Hessische Statik Meisterschaft:")
            rmc? (or (str/starts-with? text "RMC:") (str/starts-with? text "noch RMC:"))
            state (cond-> state
                    event (assoc :discipline event :discipline-line line
                                 :category nil :category-line nil :view nil
                                 :pending-status nil :pending-status-line nil)
                    hessian? (assoc :view :hessian :category text :pending-status nil)
                    rmc? (assoc :view :rmc :category (str/replace text #"^noch " "")
                                :category-line line :pending-status nil :pending-status-line nil)
                    (contains? #{"abgemeldet" "nicht am Start" "rote Karte" "außer Konkurrenz"} text)
                    (assoc :pending-status text :pending-status-line line))
            result (when (and (= :rmc (:view state)) (:discipline state)
                              (:category state) (re-matches result-pattern (:text line)))
                     (candidate lines i state))
            repeat-row (when (and (= :hessian (:view state))
                                  (re-matches hessian-pattern (:text line)))
                         (let [[_ rank name year club printed] (re-matches hessian-pattern (:text line))]
                           {:coordinates {:page (:page line) :line (:line line)}
                            :source-lines [line] :scope "Hessische Statik Meisterschaft"
                            :category (:category state) :rank (some-> rank parse-long)
                            :source-name (str/trim name) :birth-year (parse-long year)
                            :club (str/trim club) :result printed}))]
        (recur (inc i) state (cond-> rows result (conj result))
               (cond-> repeats repeat-row (conj repeat-row)))))))

(defn parse-pages [sha256 pages]
  (when-not (= source-sha256 sha256)
    (fail! "Rhein-Main Cup parser is bound to a different source PDF" {:sha256 sha256}))
  (when-not (supported? pages)
    (fail! "Rhein-Main Cup source text has changed or is incomplete" {:page-count (count pages)}))
  (let [page-lines (lines-for pages)
        date-line (first (filter #(str/includes? (:text %) "Abschnitt 1 - Samstag 27.09.2025")
                                 (mapcat identity page-lines)))
        cover-created-line (first (filter #(str/includes? (:text %) "erstellt am: 15.09.2025")
                                          (first page-lines)))
        outputs (reduce (fn [{:keys [state rows repeats per-page]} lines]
                          (let [part (page-rows lines state)]
                            {:state (:state part) :rows (into rows (:rows part))
                             :repeats (into repeats (:repeats part))
                             :per-page (conj per-page {:page (:page (first lines))
                                                       :printed-count (count (:rows part))
                                                       :repeated-hessian-count (count (:repeats part))})}))
                        {:state {:date-line date-line} :rows [] :repeats [] :per-page []} page-lines)
        rows (:rows outputs)
        repeats (:repeats outputs)
        matched? (fn [repeat-row]
                   (some #(and (= "STA" (get-in % [:parsed :discipline]))
                               (= (:source-name repeat-row) (get-in % [:parsed :source-name]))
                               (= (:result repeat-row) (get-in % [:parsed :result]))) rows))
        unparsed (count (filter #(= :unparsed (:parse-status %)) rows))
        per-page-counts (mapv :printed-count (:per-page outputs))
        unmatched (count (remove matched? repeats))
        reconciliation {:page-count (count pages) :printed-count (count rows)
                        :candidate-count (count rows) :parsed-count (- (count rows) unparsed)
                        :unparsed-count unparsed :unresolved-count (count rows)
                        :review-required-count (count rows)
                        :ranked-count (count (filter #(= :ranked (get-in % [:parsed :status])) rows))
                        :status-count (count (remove #(= :ranked (get-in % [:parsed :status])) rows))
                        :repeated-hessian-count (count repeats)
                        :hessian-unmatched-count unmatched
                        :per-page (:per-page outputs) :coverage :complete :status :unreviewed}]
    (when-not (and (= [0 0 0 28 11 21 8] per-page-counts)
                   (= 68 (count rows)) (= 50 (:ranked-count reconciliation))
                   (= 18 (:status-count reconciliation)) (zero? unparsed)
                   (= 5 (count repeats)) (zero? unmatched)
                   date-line cover-created-line)
      (fail! "Rhein-Main Cup printed results failed complete-coverage reconciliation"
             {:reconciliation reconciliation}))
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :source-url source-url :status :needs-review
     :metadata-discrepancies [{:field :cover-created-at
                               :source-line cover-created-line
                               :event-date-source-line date-line
                               :note "Cover creation timestamp predates printed event/results date; event date uses result-page section heading."}]
     :pages (mapv (fn [text lines]
                    {:page (:page (first lines)) :text text :lines lines :status :needs-review})
                  pages page-lines)
     :candidates rows :repeated-hessian-results repeats
     :reconciliation reconciliation
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))

(defn parse-pdf [path]
  (let [sha256 (sha256-file path)]
    (when-not (= source-sha256 sha256)
      (fail! "Rhein-Main Cup parser is bound to a different source PDF" {:sha256 sha256}))
    (let [{:keys [exit out err]} (shell/sh "pdftotext" "-layout" "-enc" "UTF-8" path "-")]
      (when-not (zero? exit) (fail! "pdftotext failed" {:stderr err}))
      (parse-pages sha256 (vec (remove str/blank? (str/split out #"\f")))))))
