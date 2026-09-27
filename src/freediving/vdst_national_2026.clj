(ns freediving.vdst-national-2026
  "Exact, source-bound positions from the 2026 VDST national apnea protocol."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "vdst-national-2026/1")
(def source-sha256 "923fa36b95791d757e762ad637beaf5c0a0ba96a4eb236f9fd81e8f154b2efdd")
(def source-url "https://www.vdst.de/wp-content/uploads/AP_VDST_DM2026-Protokoll.pdf")
(def ^:private layout-sha256 "692ebcf2e45090e41edce91024144a83373037341242ffbe21ad625875e471b7")
(def ^:private event-date-by-session {"Abschnitt 1" "2026-03-07" "Abschnitt 2" "2026-03-08"})
(def ^:private ranked-pattern
  #"^\s*(?:(\d+)\.\s+)?(.+?)\s{2,}((?:19|20)\d\d)\s+(.+?)\s{2,}(\d\d:\d\d,\d\d|\d+,\dm)(?:\s+(.*))?$")
(def ^:private status-pattern
  #"^\s+(.+?)\s+((?:19|20)\d\d)\s+(.+?)\s*$")
(def ^:private competition-pattern #"^(?:noch )?Wettkampf (\d+) - (.+)$")
(def ^:private category-pattern #"^(.+(?:Nationale|Internationale) Wertung)$")
(def ^:private aggregate-position-pattern
  #"^\s*(?:\d+\.\s+)?(.+?)\s{2,}((?:19|20)\d\d)\s+(.+?)\s{2,}\d+\s*$")
(def ^:private status-headings {"nicht am Start" :not-started
                                "abgemeldet" :withdrawn
                                "rote Karte" :disqualified})

(defn- fail! [message data] (throw (ex-info message data)))
(defn- sha256-bytes [bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- sha256-file [path]
  (with-open [input (io/input-stream path)]
    (let [digest (MessageDigest/getInstance "SHA-256") buffer (byte-array 8192)]
      (loop [] (let [n (.read input buffer)]
                 (when (pos? n) (.update digest buffer 0 n) (recur))))
      (.formatHex (HexFormat/of) (.digest digest)))))
(defn- lines [page text]
  (mapv (fn [index s] {:page page :line (inc index) :text s})
        (range) (str/split text #"\n" -1)))
(defn- time-seconds [s]
  (let [[_ minutes seconds centis] (re-matches #"(\d\d):(\d\d),(\d\d)" s)]
    (+ (* 60 (parse-long minutes)) (parse-long seconds) (/ (parse-long centis) 100.0))))
(defn- value-fields [result]
  (if (str/ends-with? result "m")
    {:distance-metres (Double/parseDouble (str/replace (subs result 0 (dec (count result))) "," "."))
     :unit "m"}
    {:time-seconds (time-seconds result) :unit "min:sec,centisec"}))
(defn- base-candidate [state line raw parsed]
  (let [metadata (vec (remove nil? [(:session-line state) (:competition-line state)
                                    (:category-line state) (:status-line state)]))]
    {:coordinates (select-keys line [:page :line])
     :source-lines (conj metadata line)
     :metadata-evidence metadata
     :raw {:line (:text line) :fields raw}
     :parse-status :parsed
     :parsed (merge {:federation "VDST"
                     :event "7. Deutsche Meisterschaften Apnoetauchen 2026"
                     :event-date (event-date-by-session (:session state))
                     :session (:session state)
                     :competition (:competition state)
                     :discipline (:discipline state)
                     :category (:category state)
                     :section "final-result"}
                    parsed)
     :supporting-views []
     :review-status :unreviewed
     :unresolved-reasons [:owner-review-required]
     :publication {:status :blocked :reasons [:owner-review-required]}}))
(defn- ranked-row [state line match]
  (let [[_ rank name year club result tail] match
        rank (or rank (when (:first-place-heading state) "1"))
        parsed (merge {:source-name (str/trim name) :birth-year (parse-long year)
                       :club (str/trim club) :rank (some-> rank parse-long)
                       :status :ranked :result result :printed-suffix tail}
                      (value-fields result))
        raw {:rank rank :source-name (str/trim name) :birth-year year
             :club (str/trim club) :result result :suffix tail}]
    (-> (base-candidate state line raw parsed)
        (update :source-lines #(vec (remove nil? (concat % [(:first-place-heading state)])))))))
(defn- status-row [state line match]
  (let [[_ name year club] match
        raw {:source-name (str/trim name) :birth-year year :club (str/trim club)
             :status-heading (get-in state [:status-line :text])}
        parsed {:source-name (str/trim name) :birth-year (parse-long year)
                :club (str/trim club) :rank nil :status (:status state)
                :result nil :unit nil}]
    (base-candidate state line raw parsed)))
(defn- key-of [row]
  [(get-in row [:parsed :competition]) (get-in row [:parsed :source-name])
   (get-in row [:parsed :birth-year]) (get-in row [:parsed :result])
   (get-in row [:parsed :status])])
(defn- jury [text]
  (cond
    (str/includes? text "Jury-Entscheidung: 5:0 für die Rücknahme")
    {:original-status :disqualified :original-reason "DQ SP"
     :outcome :disqualification-overturned}))
(defn- add-evidence [rows index line key value]
  (update rows index (fn [row]
                       (cond-> (update row :source-lines conj line)
                         key (assoc-in [:parsed key] value)))))

(defn- scan [records]
  (reduce
   (fn [{:keys [state rows seen ranked-lines status-lines status-blocks section-count jury-notes] :as acc} line]
     (let [s (str/trim (:text line))
           [_ competition discipline] (re-matches competition-pattern s)
           [_ category] (re-matches category-pattern s)
           session (second (re-matches #"^(Abschnitt [12]) - .+$" s))
           aggregate (re-matches #"^(?:noch )?Gesamtwertung: .+$" s)
           status (status-headings s)
           ranked (when (and (:competition state) (:category state) (nil? status))
                    (re-matches ranked-pattern (:text line)))
           status-match (when (and (:status state) (:category state) (not ranked))
                          (re-matches status-pattern (:text line)))
           appeal (str/starts-with? s "Einspruch gegen Judge Entscheidung")
           appeal-index (when appeal
                          (last (keep-indexed
                                 (fn [index row]
                                   (when (and (= :ranked (get-in row [:parsed :status]))
                                              (= (:competition state) (get-in row [:parsed :competition]))
                                              (str/includes? s (get-in row [:parsed :source-name])))
                                     index)) rows)))
           decision (jury s)
           state (cond-> state
                   session (assoc :session session :session-line line)
                   aggregate (assoc :aggregate true :competition nil :discipline nil :category nil :category-line nil
                                    :status nil :status-line nil)
                   competition (assoc :aggregate false :competition (parse-long competition) :discipline discipline
                                      :competition-line line :category nil :category-line nil
                                      :status nil :status-line nil :first-place-heading nil)
                   category (assoc :category category :category-line line
                                   :status nil :status-line nil :first-place-heading nil)
                   status (assoc :status status :status-line line)
                   appeal (assoc :appeal-result-index appeal-index :appeal-line line)
                   (str/starts-with? s "1. und ") (assoc :first-place-heading line))
           row (cond ranked (ranked-row state line ranked)
                     status-match (status-row state line status-match))
           key (when row (key-of row))
           previous (get seen key)
           repeat-view (when (and ranked previous)
                         {:coordinates (select-keys line [:page :line]) :text (:text line)
                          :category (:category state) :rank (get-in row [:parsed :rank])
                          :category-line (:category-line state)})
           rows (cond
                  repeat-view (-> rows
                                  (update-in [previous :supporting-views] conj repeat-view)
                                  (update-in [previous :source-lines] conj line))
                  row (conj rows row)
                  :else rows)
           row-index (when row (or previous (dec (count rows))))
           state (cond-> state
                   ranked (assoc :last-ranked-index row-index :first-place-heading nil :status nil :status-line nil)
                   status-match (assoc :last-status-index row-index)
                   (or competition category aggregate) (assoc :last-ranked-index nil :last-status-index nil))
           rows (cond
                  (and decision (some? (:appeal-result-index state)))
                  (let [index (:appeal-result-index state)
                        existing (get-in rows [index :parsed :jury-decision])]
                    (-> (update-in rows [index :source-lines] conj (:appeal-line state))
                        (add-evidence index line nil nil)
                        (assoc-in [index :parsed :jury-decision]
                                  (-> (or existing decision)
                                      (update :source-notes (fnil into []) [(:appeal-line state) line])))))
                  (and (re-matches #"^(?:BO SP|DQ(?: BO)? SP|[Aa]ufgetaucht bei .+|Uhrzeit der Bekanntgabe: .+)$" s)
                       (some? (:last-status-index state)))
                  (add-evidence rows (:last-status-index state) line :status-note
                                (str/join " | " (conj (vec (str/split
                                                            (or (get-in rows [(:last-status-index state) :parsed :status-note]) "")
                                                            #" \| ")) s)))
                  :else rows)]
       (assoc acc :state state :rows rows
              :seen (if (and row (not previous)) (assoc seen key row-index) seen)
              :ranked-lines (+ ranked-lines (if ranked 1 0))
              :status-lines (+ status-lines (if status-match 1 0))
              :status-blocks (+ status-blocks (if status 1 0))
              :section-count (+ section-count (if category 1 0))
              :aggregate-count (+ (:aggregate-count acc 0) (if (and aggregate (not (str/starts-with? s "noch "))) 1 0))
              :aggregate-position-count (+ (:aggregate-position-count acc 0)
                                           (if (and (:aggregate state)
                                                    (not (str/includes? s "- Protokoll -"))
                                                    (re-matches aggregate-position-pattern (:text line))) 1 0))
              :aggregate-component-count (+ (:aggregate-component-count acc 0)
                                            (if (and (:aggregate state)
                                                     (re-matches #"^Wk \d+ - .+ - \d+ Punkte$" s)) 1 0))
              :competitions (cond-> (:competitions acc #{}) competition (conj (parse-long competition)))
              :jury-notes (+ jury-notes (if decision 1 0)))))
   {:state {} :rows [] :seen {} :ranked-lines 0 :status-lines 0 :status-blocks 0
    :section-count 0 :aggregate-count 0 :aggregate-position-count 0
    :aggregate-component-count 0 :competitions #{} :jury-notes 0}
   (mapcat identity records)))

(defn parse-pages [sha256 pages]
  (when-not (= source-sha256 sha256)
    (fail! "VDST national parser is bound to a different source PDF" {:sha256 sha256}))
  (when-not (and (= 26 (count pages))
                 (= layout-sha256 (sha256-bytes (.getBytes (str/join "\f" pages) "UTF-8"))))
    (fail! "VDST national extracted layout differs from archived PDF"
           {:page-count (count pages)}))
  (let [records (mapv lines (range 1 27) pages)
        scanned (scan records)
        rows (:rows scanned)
        ranked (filter #(= :ranked (get-in % [:parsed :status])) rows)
        statuses (remove #(= :ranked (get-in % [:parsed :status])) rows)
        repeats (reduce + (map #(count (:supporting-views %)) rows))
        decisions (filter #(get-in % [:parsed :jury-decision]) (:rows scanned))
        rec {:page-count 26 :competition-count (count (:competitions scanned))
             :ranking-heading-count (:section-count scanned)
             :aggregate-heading-count (:aggregate-count scanned)
             :aggregate-position-count (:aggregate-position-count scanned)
             :aggregate-component-count (:aggregate-component-count scanned)
             :printed-ranked-count (:ranked-lines scanned)
             :supporting-repeat-count repeats :ranked-count (count ranked)
             :status-block-count (:status-blocks scanned) :status-count (count statuses)
             :candidate-count (count rows) :parsed-count (count rows)
             :unresolved-count 0
             :ranked-by-competition (frequencies (map #(get-in % [:parsed :competition]) ranked))
             :unique-jury-decision-count (count decisions) :printed-jury-note-count (:jury-notes scanned)
             :coverage :complete :status :unreviewed}]
    (when-not (and (= 28 (:competition-count rec))
                   (= 95 (:ranking-heading-count rec))
                   (= 19 (:aggregate-heading-count rec))
                   (= 84 (:aggregate-position-count rec))
                   (= 121 (:aggregate-component-count rec))
                   (= 318 (:printed-ranked-count rec)) (= 182 repeats)
                   (= 136 (:ranked-count rec)) (= 22 (:status-count rec))
                   (= 158 (count rows)) (= 22 (:status-block-count rec))
                   (= 1 (:printed-jury-note-count rec))
                   (= 1 (count decisions))
                   (= (:status-lines scanned) (count statuses))
                   (every? #(get-in % [:parsed :rank]) ranked))
      (fail! "VDST national positions failed complete-coverage reconciliation" {:reconciliation rec}))
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :source-url source-url :status :needs-review
     :pages (mapv (fn [text ls] {:page (:page (first ls)) :text text :lines ls
                                 :status :needs-review}) pages records)
     :candidates rows
     :reconciliation rec
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pdf [path]
  (let [sha256 (sha256-file path)]
    (when-not (= source-sha256 sha256)
      (fail! "VDST national parser is bound to a different source PDF" {:sha256 sha256}))
    (let [{:keys [exit out err]} (shell/sh "pdftotext" "-layout" "-enc" "UTF-8" path "-")]
      (when-not (zero? exit) (fail! "pdftotext failed" {:stderr err}))
      (parse-pages sha256 (vec (remove str/blank? (str/split out #"\f")))))))
