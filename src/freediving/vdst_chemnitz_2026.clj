(ns freediving.vdst-chemnitz-2026
  "Source-bound result positions from the official 2026 Chemnitz 50m protocol."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def source-sha256 "7e7065def63a16a53a21d12b623f22fdebd43d0774e168d1c31c117731e25f93")
(def parser-version "vdst-chemnitz-50m-2026/1")
(def source-url "https://www.vdst.de/wp-content/uploads/AP_CAPC2026-Protokoll.pdf")
(def event-name "3. Chemnitzer Apnoe Cup - 50m Edition")
(def event-date "2026-02-07")

(def ^:private competition #"^(?:noch )?Wettkampf (\d+) - (.+)$")
(def ^:private category #"^(?:noch )?(Jugend I \(Jg\.2014/2013\)|Jugend II \(Jg\.2012/2011\)|Junior \(Jg\.2010/2009\)|Senior \(Jg\.2008-1977\)|Master \(Jg\.1976 und älter\)|Jahrgang 2012 / 2011|Jahrgang 2010 und älter)$")
(def ^:private result-line #"^\s*(?:(\d+)\.\s+)?(.+?)\s+(\d{4})\s{2,}(.+?)\s{2,}(\d{2}:\d{2},\d{2}|\d+(?:,\d+)?m)\s*$")
(def ^:private status-person #"^\s+(.+?)\s+(\d{4})\s{2,}(.+?)\s*$")
(def ^:private status-headings #{"nicht am Start" "rote Karte" "abgemeldet"})
(def ^:private expected-per-page [0 0 0 10 25 20 21 18 19 8])
(def ^:private expected-per-competition
  {"1" [6 1] "2" [2 1] "3" [7 1] "4" [6 0] "5" [8 0]
   "6" [10 0] "7" [7 0] "8" [6 0] "9" [2 0] "10" [3 1]
   "11" [8 1] "12" [6 1] "131" [5 1] "132" [5 0]
   "13" [3 0] "141" [7 2] "142" [6 0] "14" [3 0]
   "15" [7 1] "16" [4 0]})

(defn- fail! [message data] (throw (ex-info message data)))

(defn- line-records [page text]
  (mapv (fn [i s] {:page page :line (inc i) :text s})
        (range) (str/split text #"\n" -1)))

(defn- context-fields [{:keys [competition heading category]}]
  (let [round (cond (#{"131" "141"} competition) :round-1
                    (#{"132" "142"} competition) :round-2
                    (#{"13" "14"} competition) :end-round
                    :else :preliminary)
        discipline (cond (str/includes? heading "DBF") "DBF"
                         (str/includes? heading "DNF") "DNF"
                         (str/includes? heading "DYN") "DYN"
                         (str/includes? heading "Speed") "Speed-Apnoe"
                         (str/includes? heading "Parcours") "25m Parcours")]
    {:federation "VDST" :event-name event-name :event-date event-date
     :competition-number competition :competition-heading heading
     :round round :discipline discipline :category category
     :gender (cond (str/includes? heading "weiblich") "F"
                   (str/includes? heading "männlich") "M")}))

(defn- value-fields [printed]
  (if (str/ends-with? printed "m")
    {:performance (bigdec (str/replace (subs printed 0 (dec (count printed))) "," "."))
     :unit "m"}
    (let [[_ minutes seconds hundredths] (re-matches #"(\d{2}):([0-5]\d),(\d{2})" printed)]
      {:realized-time {:components [(parse-long minutes) (parse-long seconds)
                                    (parse-long hundredths)]
                       :fraction (parse-long hundredths) :fraction-digits 2
                       :notation :colon-separated}
       :unit "s"})))

(defn- candidate [line evidence raw parsed]
  {:coordinates {:page (:page line) :line (:line line)
                 :column-start 1 :column-end (inc (count (:text line)))}
   :source-lines evidence :raw (assoc raw :line (:text line))
   :parse-status :parsed :parsed parsed
   :fields (into {} (map (fn [[key value]]
                           [key {:status (if (nil? value) :unknown :parsed)
                                 :value value}]) parsed))
   :review-status :unreviewed :unresolved-reasons [:owner-review-required]
   :publication {:status :blocked :reasons [:owner-review-required]}})

(defn- result-candidate [line context]
  (when-let [[_ rank name year club printed] (re-matches result-line (:text line))]
    (let [winner-line (when (= (dec (:line line)) (some-> context :winner-line :line))
                        (:winner-line context))
          evidenced-rank (or rank (when winner-line "1"))]
      (candidate line (cond-> [(:event-line context) (:category-line context)]
                        winner-line (conj winner-line)
                        true (conj line))
                 {:rank evidenced-rank :source-name name :birth-year year :club club :result printed}
                 (merge (context-fields context)
                        {:source-name name :birth-year (parse-long year) :club club
                         :rank (some-> evidenced-rank parse-long)
                         :realised-performance printed
                         :status (if evidenced-rank :ranked :result)}
                        (value-fields printed))))))

(defn- status-candidate [lines i context]
  (when-let [[_ name year club] (and (:status-heading context)
                                     (re-matches status-person (:text (nth lines i))))]
    (let [line (nth lines i)
          red? (= "rote Karte" (:status-heading context))
          code-line (when red? (nth lines (inc i) nil))
          announcement-line (when red? (nth lines (+ i 2) nil))
          code (some-> code-line :text str/trim)
          announcement (some->> announcement-line :text str/trim
                                (re-matches #"Uhrzeit der Bekanntgabe: (\d{2}:\d{2})") second)]
      (when (and red? (or (str/blank? code) (nil? announcement)))
        (fail! "Chemnitz jury decision is incomplete" {:page (:page line) :line (:line line)}))
      (candidate line (cond-> [(:event-line context) (:category-line context)
                               (:status-line context) line]
                        red? (conj code-line announcement-line))
                 {:source-name name :birth-year year :club club
                  :status (:status-heading context) :status-code code
                  :announcement-time announcement}
                 (merge (context-fields context)
                        {:source-name name :birth-year (parse-long year) :club club
                         :rank nil :realised-performance nil :performance nil :unit nil
                         :status (case (:status-heading context)
                                   "rote Karte" :disqualified
                                   "abgemeldet" :withdrawn
                                   "nicht am Start" :did-not-start)
                         :status-label (:status-heading context)
                         :status-code code :announcement-time announcement})))))

(defn- scan-page [page text initial-context]
  (let [lines (line-records page text)]
    (loop [i 0 context initial-context rows []]
      (if (= i (count lines))
        {:page {:page page :text text :lines lines :status :needs-review}
         :context context :rows rows}
        (let [line (nth lines i)
              s (str/trim (:text line))
              event (re-matches competition s)
              cat (re-matches category s)
              context (cond event (assoc context :competition (second event)
                                         :heading (nth event 2) :event-line line
                                         :category nil :category-line nil
                                         :status-heading nil :status-line nil)
                            cat (assoc context :category (second cat) :category-line line
                                       :status-heading nil :status-line nil :winner-line nil)
                            (status-headings s) (assoc context :status-heading s
                                                       :status-line line)
                            (str/starts-with? s "1. und Sieger")
                            (assoc context :winner-line line)
                            :else context)
              result (when (:category context) (result-candidate line context))
              status (when (and (not result) (:category context))
                       (status-candidate lines i context))]
          (recur (inc i) context (cond-> rows result (conj result) status (conj status))))))))

(defn parse-pages [sha256 pages]
  (when-not (= source-sha256 sha256)
    (fail! "Chemnitz parser is bound to a different source PDF" {:sha256 sha256}))
  (when-not (and (= 10 (count pages))
                 (str/includes? (nth pages 2) "Abschnitt 1 - Samstag 07.02.2026")
                 (every? #(str/includes? % "3. Chemnitzer Apnoe Cup - 50m Edition")
                         (subvec (vec pages) 1)))
    (fail! "Chemnitz source text has changed or is incomplete" {:page-count (count pages)}))
  (let [scanned (loop [i 0 context {} result []]
                  (if (= i 10) result
                      (let [x (scan-page (inc i) (nth pages i) context)]
                        (recur (inc i) (:context x) (conj result x)))))
        rows (vec (mapcat :rows scanned))
        result? #(some? (get-in % [:parsed :realised-performance]))
        results (filter result? rows)
        statuses (remove result? rows)
        per-page (mapv (fn [item] {:page (get-in item [:page :page])
                                   :candidate-count (count (:rows item))
                                   :result-count (count (filter result? (:rows item)))
                                   :status-count (count (remove result? (:rows item)))})
                       scanned)
        per-competition (into {}
                              (map (fn [[number xs]]
                                     [number [(count (filter result? xs))
                                              (count (remove result? xs))]])
                                   (group-by #(get-in % [:parsed :competition-number]) rows)))
        supporting-captions (vec (filter #(str/starts-with? (str/trim (:text %)) "1. und Sieger")
                                         (mapcat :lines (map :page scanned))))
        qualification-notes (vec (filter #(str/includes? (:text %) "scheidet aus")
                                         (mapcat :lines (map :page scanned))))
        penalty-notes (vec (filter #(str/includes? (:text %) "berührt")
                                   (mapcat :lines (map :page scanned))))
        reconciliation {:page-count 10 :printed-position-count (count rows)
                        :candidate-count (count rows) :result-count (count results)
                        :status-count (count statuses) :parsed-count (count rows)
                        :unparsed-count 0 :unreconciled-position-count 0
                        :unresolved-count (count rows)
                        :duplicate-result-view-count 0
                        :supporting-winner-caption-count (count supporting-captions)
                        :qualification-note-count (count qualification-notes)
                        :penalty-note-count (count penalty-notes)
                        :per-page per-page :per-competition per-competition
                        :coverage :complete :status :unreviewed}]
    (when-not (and (= expected-per-page (mapv :candidate-count per-page))
                   (= expected-per-competition per-competition)
                   (= 111 (count results)) (= 10 (count statuses))
                   (= 4 (count supporting-captions))
                   (= 2 (count qualification-notes))
                   (every? #(and (get-in % [:parsed :discipline])
                                 (get-in % [:parsed :gender])
                                 (get-in % [:parsed :category])) rows))
      (fail! "Chemnitz printed positions failed complete-coverage reconciliation"
             {:reconciliation reconciliation}))
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :source-url source-url :status :needs-review
     :pages (mapv :page scanned) :candidates rows
     :supporting-winner-captions supporting-captions
     :qualification-notes qualification-notes :penalty-notes penalty-notes
     :reconciliation reconciliation
     :publication {:status :blocked
                   :reasons [:owner-review-required :reconciliation-unreviewed]}}))

(defn- sha256-file [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (java.io.FileInputStream. path)]
      (let [buffer (byte-array 8192)]
        (loop [] (let [n (.read stream buffer)]
                   (when (pos? n) (.update digest buffer 0 n) (recur))))))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn parse-pdf [path]
  (let [sha256 (sha256-file path)]
    (when-not (= source-sha256 sha256)
      (fail! "Chemnitz parser is bound to a different source PDF" {:sha256 sha256}))
    (let [{:keys [exit out err]} (shell/sh "pdftotext" "-layout" "-enc" "UTF-8" path "-")]
      (when-not (zero? exit) (fail! "pdftotext failed" {:stderr err}))
      (parse-pages sha256 (vec (remove str/blank? (str/split out #"\f")))))))
