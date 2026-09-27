(ns freediving.vdst-mitteldeutscher-2025
  "Source-bound apnea results from the mixed-sport 7th Mitteldeutscher Cup protocol."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "vdst-mitteldeutscher-cup-2025/1")
(def source-sha256 "6cf13597a909de1ec38809de01a281e2a690f6ac63fef0506ee57480f6d7abdc")
(def source-url "https://www.vdst.de/wp-content/uploads/MDC2025-Protokoll.pdf")
(def event-date "2025-10-25")
(def ^:private layout-sha256 "c901b88c725042e2b1aac45b78ac41592d3772c36be6206bfd18a98bc5d63299")
(def ^:private expected-competitions {101 10, 102 6, 103 7, 104 2, 105 1, 106 2})
(def ^:private expected-pages {11 5, 12 11, 19 7, 20 2, 25 3})
(def ^:private competition-pattern #"^(?:noch )?Wettkampf (\d+) - (.+)$")
(def ^:private category-pattern #"^(?:noch )?(APJ I|APJ II|APS) - (.+)$")
(def ^:private timed-row-pattern
  #"^\s*(\d+)\.\s+(.+?)\s{2,}((?:19|20)\d\d)\s{2,}(.+?)\s{2,}(\d\d:\d\d,\d\d)\s*$")
(def ^:private status-row-pattern
  #"^\s{2,}(.+?)\s{2,}((?:19|20)\d\d)\s{2,}(.+?)\s*$")

(defn- fail! [message data] (throw (ex-info message data)))

(defn- sha256-bytes [bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))

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

(defn- time-seconds [s]
  (let [[_ minutes seconds centis] (re-matches #"(\d\d):(\d\d),(\d\d)" s)]
    (+ (* 60 (parse-long minutes)) (parse-long seconds) (/ (parse-long centis) 100.0))))

(defn- apnea? [state]
  (contains? expected-competitions (:competition state)))

(defn- candidate [line state]
  (when (and (apnea? state) (:category state))
    (let [timed (re-matches timed-row-pattern (:text line))
          status (when (= "disqualifiziert" (:pending-status state))
                   (re-matches status-row-pattern (:text line)))
          [_ rank name year club result] timed
          [_ status-name status-year status-club] status
          name (or name status-name)
          year (or year status-year)
          club (or club status-club)
          source-lines (vec (remove nil? [(:event-date-line state) (:session-line state)
                                          (:competition-line state) (:category-line state)
                                          (:status-line state) line]))]
      (when (or timed status)
        {:coordinates {:page (:page line) :line (:line line)}
         :source-lines source-lines
         :metadata-evidence (vec (butlast source-lines))
         :raw {:line (:text line)
               :fields {:rank rank :source-name (str/trim name) :birth-year year
                        :club (str/trim club) :result result
                        :status-heading (:pending-status state)}}
         :parse-status :parsed
         :parsed {:federation "VDST" :event "7. Mitteldeutscher Cup im Finswimming & Apnoetauchen"
                  :event-date event-date :session "Abschnitt 1" :competition (:competition state)
                  :discipline (:discipline state)
                  :gender (if (odd? (:competition state)) "Female" "Male")
                  :category (:category state) :section "final-result"
                  :source-name (str/trim name) :birth-year (parse-long year)
                  :club (str/trim club) :rank (some-> rank parse-long)
                  :status (if timed :ranked :disqualified)
                  :result result :time-seconds (when timed (time-seconds result))
                  :unit (when timed "min:sec,centisec")}
         :review-status :unreviewed
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
            [_ competition discipline] (re-matches competition-pattern s)
            [_ category _] (re-matches category-pattern s)
            status (= s "disqualifiziert")
            state (cond-> state
                    competition (assoc :competition (parse-long competition)
                                       :discipline discipline :competition-line line
                                       :category nil :category-line nil
                                       :pending-status nil :status-line nil)
                    category (assoc :category s :category-line line
                                    :pending-status nil :status-line nil)
                    status (assoc :pending-status s :status-line line))
            row (candidate line state)
            rows (cond-> rows row (conj row))
            rows (cond
                   (and (apnea? state) (re-matches #"^\d00m: .+" s))
                   (annotate-last rows line :split-times s)
                   (and (apnea? state) (= "Gesicht aus dem Wasser bei 90 m" s))
                   (annotate-last rows line :disqualification-reason s)
                   (and (apnea? state) (re-matches #"^Uhrzeit der Bekanntgabe: \d\d:\d\d$" s))
                   (annotate-last rows line :announcement s)
                   :else rows)
            state (if row (assoc state :pending-status nil :status-line nil) state)]
        (recur (rest remaining) state rows))
      {:state state :rows rows})))

(defn parse-pages [sha256 pages]
  (when-not (= source-sha256 sha256)
    (fail! "Mitteldeutscher Cup parser is bound to a different source PDF" {:sha256 sha256}))
  (when-not (and (= 30 (count pages))
                 (= layout-sha256 (sha256-bytes (.getBytes (str/join "\f" pages) "UTF-8"))))
    (fail! "Mitteldeutscher Cup source text differs from the archived PDF" {:page-count (count pages)}))
  (let [records (mapv line-records (range 1 (inc (count pages))) pages)
        result (reduce (fn [{:keys [state rows per-page]} lines]
                         (let [part (scan-page lines state)]
                           {:state (:state part) :rows (into rows (:rows part))
                            :per-page (conj per-page {:page (:page (first lines))
                                                      :printed-count (count (:rows part))})}))
                       {:state {:event-date-line (get-in records [0 6])
                                :session-line (get-in records [1 3])}
                        :rows [] :per-page []} records)
        rows (:rows result)
        by-competition (frequencies (map #(get-in % [:parsed :competition]) rows))
        by-page (into {} (map (juxt :page :printed-count)
                              (filter (comp pos? :printed-count) (:per-page result))))
        reconciliation {:page-count 30 :printed-count (count rows)
                        :candidate-count (count rows) :parsed-count (count rows)
                        :unparsed-count 0 :by-competition by-competition
                        :per-page (:per-page result) :coverage :complete
                        :status :unreviewed}]
    (when-not (and (= expected-competitions by-competition)
                   (= expected-pages by-page)
                   (= 1 (count (filter #(= :disqualified (get-in % [:parsed :status])) rows))))
      (fail! "Mitteldeutscher Cup apnea positions failed complete-coverage reconciliation"
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
      (fail! "Mitteldeutscher Cup parser is bound to a different source PDF" {:sha256 sha256}))
    (let [{:keys [exit out err]} (shell/sh "pdftotext" "-layout" "-enc" "UTF-8" path "-")]
      (when-not (zero? exit) (fail! "pdftotext failed" {:stderr err}))
      (parse-pages sha256 (vec (remove str/blank? (str/split out #"\f")))))))
