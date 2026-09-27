(ns freediving.vdst-chemnitz-2025
  "Source-bound transcription of the official 2nd Chemnitzer Apnoe Cup protocol."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "vdst-chemnitzer-apnoe-cup-2025/1")
(def source-sha256 "1fe9133079465f14b6d195db690f036cfe6fc396f45780bd799a28c882f58518")
(def source-url "https://www.vdst.de/wp-content/uploads/AP_CAPC2025-Protokoll.pdf")
(def event-date "2025-02-16")
(def ^:private expected-pages [0 0 0 10 17 18 19 18 9])
(def ^:private expected-sections {"main" 56 "super-finale-round-1" 10
                                  "super-finale-round-2" 7 "super-finale-final" 6
                                  "parcours" 12})
(def ^:private competition-pattern #"^(?:noch )?Wettkampf (\d+) - (.+)$")
(def ^:private category-pattern #"^(APJ II|APJ III|APS|APM) \((.+)\)$")
(def ^:private person-pattern
  #"^\s*(?:(\d+)\.\s+)?(.+?)\s+((?:19|20)\d\d)\s+(.+?)(?:\s{2,}(\d\d:\d\d,\d\d|\d+,\d+m))?\s*$")
(def ^:private time-pattern #"(\d\d):(\d\d),(\d\d)")
(def ^:private distance-pattern #"(\d+,\d+)m")

(defn- fail! [message data] (throw (ex-info message data)))

(defn- sha256-file [path]
  (with-open [input (io/input-stream path)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [n (.read input buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur))))
      (.formatHex (HexFormat/of) (.digest digest)))))

(defn- line-records [page text]
  (mapv (fn [i s] {:page page :line (inc i) :text s})
        (range) (str/split text #"\n" -1)))

(defn- performance [printed]
  (cond
    (nil? printed) nil
    (re-matches distance-pattern printed)
    (let [[_ n] (re-matches distance-pattern printed)]
      {:performance (bigdec (str/replace n "," ".")) :unit "m"})
    (re-matches time-pattern printed)
    (let [[_ m s c] (re-matches time-pattern printed)]
      {:time-seconds (+ (* 60 (parse-long m)) (parse-long s) (/ (parse-long c) 100.0))
       :unit "min:sec,centisec"})))

(defn- discipline [number]
  ({1 "Speed-Apnea 2 x 25m" 2 "Speed-Apnea 2 x 25m"
    3 "DYN-BI" 4 "DYN-BI" 5 "Speed Endurance 4 x 25m"
    6 "Speed Endurance 4 x 25m" 7 "DNF" 8 "DNF"
    9 "Speed Endurance 8 x 25m" 10 "Speed Endurance 8 x 25m"
    11 "DYN" 12 "DYN" 13 "Speed Endurance 4 x 25m"
    14 "Speed Endurance 4 x 25m" 15 "Parcours" 16 "Parcours"} number))

(defn- section [{:keys [competition round]}]
  (cond
    (#{13 14} competition)
    (case round "Runde 1" "super-finale-round-1"
          "Runde 2" "super-finale-round-2"
          "Finalrunde" "super-finale-final" nil)
    (#{15 16} competition) "parcours"
    (some? competition) "main"))

(defn- person-row [line state]
  (when-let [[_ rank name year club printed] (re-matches person-pattern (:text line))]
    (when (and (>= (:page line) 4) (:competition state) (:category state)
               (or printed (:pending-status state)))
      (let [status (cond
                     printed (if (or rank (= "Finalrunde" (:round state))) :ranked :completed-unranked)
                     (= "nicht am Start" (:pending-status state)) :did-not-start
                     (= "abgemeldet" (:pending-status state)) :withdrawn
                     (= "rote Karte" (:pending-status state)) :disqualified
                     :else :unknown)
            section (section state)
            source-lines (vec (remove nil? [(:competition-line state) (:round-line state)
                                            (:category-line state) (:winner-line state)
                                            (:status-line state) line]))
            parsed (merge {:federation "VDST" :event "2. Chemnitzer Apnoe Cup"
                           :event-date event-date :competition (:competition state)
                           :discipline (discipline (:competition state))
                           :gender (if (odd? (:competition state)) "Female" "Male")
                           :category (:category state) :round (:round state)
                           :section section :source-name (str/trim name)
                           :birth-year (parse-long year) :club (str/trim club)
                           :rank (or (some-> rank parse-long)
                                     (when (:winner-line state) 1))
                           :status status :result printed}
                          (performance printed))]
        {:coordinates {:page (:page line) :line (:line line)}
         :source-lines source-lines :metadata-evidence (vec (butlast source-lines))
         :raw {:line (:text line)
               :fields {:rank rank :source-name (str/trim name) :birth-year year
                        :club (str/trim club) :result printed
                        :status-heading (:pending-status state)}}
         :parse-status (if (and section (not= :unknown status)) :parsed :unparsed)
         :parsed parsed :review-status :unreviewed
         :unresolved-reasons [:owner-review-required]
         :publication {:status :blocked :reasons [:owner-review-required]}}))))

(defn- annotate-last [rows line key value]
  (if (seq rows)
    (update rows (dec (count rows))
            (fn [row] (-> row (update :source-lines conj line)
                          (assoc-in [:parsed key] value))))
    rows))

(defn- scan-page [lines initial]
  (loop [remaining lines state initial rows []]
    (if-let [line (first remaining)]
      (let [s (str/trim (:text line))
            [_ number _] (re-matches competition-pattern s)
            [_ code category] (re-matches category-pattern s)
            round (when-let [[_ r] (re-matches #"^(?:noch )?(Runde [12]|Finalrunde)$" s)] r)
            status (when (#{"nicht am Start" "rote Karte" "abgemeldet"} s) s)
            winner? (boolean (re-matches #"^1\. und Gewinner(?:in)?: Chemnitzer Apnoe Cup 2025$" s))
            state (cond-> state
                    number (assoc :competition (parse-long number) :competition-line line
                                  :category (when (#{13 14} (parse-long number)) "Super Finale")
                                  :category-line (when (#{13 14} (parse-long number)) line)
                                  :pending-status nil
                                  :status-line nil :winner-line nil
                                  :round (when (#{13 14} (parse-long number)) (:round state))
                                  :round-line (when (#{13 14} (parse-long number)) (:round-line state)))
                    round (assoc :round round :round-line line :pending-status nil
                                 :status-line nil :winner-line nil)
                    code (assoc :category (str code " (" category ")") :category-line line
                                :pending-status nil :status-line nil :winner-line nil)
                    status (assoc :pending-status status :status-line line)
                    winner? (assoc :winner-line line))
            row (person-row line state)
            rows (cond-> rows row (conj row))
            rows (cond
                   (re-matches #"^Penalty:\s*\d+s$" s)
                   (annotate-last rows line :penalty s)
                   (= "langsamste Endzeit -> scheidet aus" s)
                   (annotate-last rows line :round-decision s)
                   (re-matches #"^DQ [A-Z ]+$" s)
                   (annotate-last rows line :status-code s)
                   (re-matches #"^Uhrzeit der Bekanntgabe: \d\d:\d\d$" s)
                   (annotate-last rows line :announcement s)
                   :else rows)
            state (if (and row (:winner-line state)) (assoc state :winner-line nil) state)]
        (recur (rest remaining) state rows))
      {:state state :rows rows})))

(defn parse-pages [sha256 pages]
  (when-not (= source-sha256 sha256)
    (fail! "Chemnitzer Cup parser is bound to a different source PDF" {:sha256 sha256}))
  (when-not (and (= 9 (count pages))
                 (str/includes? (first pages) "2. Chemnitzer Apnoe Cup")
                 (str/includes? (last pages) "Protokollende: 16:44 Uhr"))
    (fail! "Chemnitzer Cup source text is incomplete" {:page-count (count pages)}))
  (let [records (mapv line-records (range 1 (inc (count pages))) pages)
        result (reduce (fn [{:keys [state rows per-page]} lines]
                         (let [part (scan-page lines state)]
                           {:state (:state part) :rows (into rows (:rows part))
                            :per-page (conj per-page {:page (:page (first lines))
                                                      :printed-count (count (:rows part))})}))
                       {:state {} :rows [] :per-page []} records)
        rows (:rows result)
        counts (mapv :printed-count (:per-page result))
        sections (frequencies (map #(get-in % [:parsed :section]) rows))
        unparsed (count (filter #(= :unparsed (:parse-status %)) rows))
        reconciliation {:page-count 9 :printed-count (count rows)
                        :candidate-count (count rows) :parsed-count (- (count rows) unparsed)
                        :unparsed-count unparsed :status-count (count (filter #(nil? (get-in % [:parsed :result])) rows))
                        :result-count (count (filter #(some? (get-in % [:parsed :result])) rows))
                        :by-section sections :per-page (:per-page result)
                        :coverage :complete :status :unreviewed}]
    (when-not (and (= expected-pages counts) (= expected-sections sections)
                   (= 91 (count rows)) (= 10 (:status-count reconciliation))
                   (zero? unparsed))
      (fail! "Chemnitzer Cup positions failed complete-coverage reconciliation"
             {:reconciliation reconciliation}))
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :source-url source-url :status :needs-review
     :pages (mapv (fn [text lines] {:page (:page (first lines)) :text text
                                    :lines lines :status :needs-review}) pages records)
     :candidates rows :reconciliation reconciliation
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pdf [path]
  (let [sha256 (sha256-file path)]
    (when-not (= source-sha256 sha256)
      (fail! "Chemnitzer Cup parser is bound to a different source PDF" {:sha256 sha256}))
    (let [{:keys [exit out err]} (shell/sh "pdftotext" "-layout" "-enc" "UTF-8" path "-")]
      (when-not (zero? exit) (fail! "pdftotext failed" {:stderr err}))
      (parse-pages sha256 (vec (remove str/blank? (str/split out #"\f")))))))
