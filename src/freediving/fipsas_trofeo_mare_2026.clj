(ns freediving.fipsas-trofeo-mare-2026
  "Source-bound individual depth positions from the 2026 Trofeo del Mare PDFs."
  (:require [clojure.string :as str]))

(def parser-version "fipsas-trofeo-mare-2026/1")

(def ^:private sources
  {"fa71db79ef7086edaa8563c14394f2c2974581e4a0f238efb933772c3ac117cb"
   {:index 2 :gender :female :calendar-date "2026-05-22"
    :expected-pages 2 :expected-sections 7 :expected-positions 33
    :expected-by-page {1 17, 2 16}}
   "873a30214c1216a4c0ef717d6c7db66f09fca3a48c00edd47beeef30e5c6e2bc"
   {:index 3 :gender :male :calendar-date "2026-05-22"
    :expected-pages 4 :expected-sections 8 :expected-positions 79
    :expected-by-page {1 17, 2 25, 3 25, 4 12}}})

(defn source-sha256 [index]
  (some (fn [[sha source]] (when (= index (:index source)) sha)) sources))

(defn supported? [sha256] (contains? sources sha256))

(defn parser-version-for [sha256]
  (when (supported? sha256) parser-version))

(def ^:private heading-pattern #"^(CNF|CWT|CWTB|FIM) - (Promotion|Open|fino a 40 m|oltre 40 m)$")
(def ^:private row-pattern #"^\s*(\d+)\s+(.+)$")
(def ^:private time-pattern #"\d+'\d{2}'{0,2}(?: \d+'\d{2}'{0,2})?")
(def ^:private merged-club "Centro Sub Riviera Dei Fiori")
(def ^:private event "Gara Marina di Camerota 2026")

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- page-lines [index text]
  {:page (inc index) :text text
   :lines (mapv (fn [n s] {:page (inc index) :line (inc n) :text s})
                (range) (str/split text #"\n" -1))})

(defn- parse-row [source section line]
  (let [[_ rank text] (re-matches row-pattern (:text line))
        chunks (str/split (str/trim text) #"\s{2,}")
        merged-club? (str/starts-with? (first chunks) (str merged-club " "))
        missing-time? (and (= 6 (count chunks)) (not merged-club?))
        identity-width (if merged-club? 1 2)
        [identity declared reached time penalty effective]
        (if missing-time?
          [(take 2 chunks) (nth chunks 2 nil) (nth chunks 3 nil) nil
           (nth chunks 4 nil) (nth chunks 5 nil)]
          [(take identity-width chunks) (nth chunks identity-width nil)
           (nth chunks (inc identity-width) nil)
           (nth chunks (+ 2 identity-width) nil)
           (nth chunks (+ 3 identity-width) nil)
           (nth chunks (+ 4 identity-width) nil)])
        [club name] (if (= 1 (count identity))
                      (let [combined (first identity)]
                        (when (str/starts-with? combined (str merged-club " "))
                          [merged-club (subs combined (inc (count merged-club)))]))
                      identity)
        valid? (and (#{6 7} (count chunks)) (seq club) (seq name)
                    (every? #(re-matches #"-?\d+" (or % ""))
                            [declared reached penalty effective])
                    (or missing-time? (re-matches time-pattern (or time ""))))
        parsed (when valid?
                 {:federation "FIPSAS" :event event
                  :event-date (:calendar-date source) :printed-event-date nil
                  :calendar-event-date (:calendar-date source)
                  :event-date-source :official-calendar
                  :gender (:gender source) :category (:category section)
                  :discipline (:discipline section) :club club :source-name name
                  :rank (parse-long rank) :ranked? true :status :ranked
                  :declared-depth (parse-long declared)
                  :reached-depth (parse-long reached) :time time
                  :early-turn-penalty (parse-long penalty)
                  :effective-depth (parse-long effective)
                  :final-performance (parse-long effective)
                  :unit "m" :source-role :individual-ranking})]
    {:coordinates (select-keys line [:page :line])
     :source-lines [line (:heading-line section)]
     :raw {:line (:text line)
           :fields {:rank rank :club club :source-name name
                    :declared-depth declared :reached-depth reached
                    :time time :early-turn-penalty penalty
                    :effective-depth effective :tokens chunks}}
     :parse-status (if valid? :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required :pdf-event-date-unprinted]
                           missing-time? (conj :time-unprinted)
                           (not valid?) (conj :unparsed-source-line))
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-pages
  "Parse exact pdftotext -layout page strings with source hash and 1-based citations."
  [sha256 pages]
  (let [source (get sources sha256)]
    (when-not source
      (throw (ex-info "Trofeo del Mare parser is bound to different source PDFs"
                      {:sha256 sha256})))
    (let [page-data (mapv page-lines (range) pages)
          state (reduce
                 (fn [state line]
                   (let [trimmed (str/trim (:text line))]
                     (if-let [[_ discipline category] (re-matches heading-pattern trimmed)]
                       (let [section {:discipline discipline :category category
                                      :heading-line line}]
                         (-> state (assoc :section section) (update :sections conj section)))
                       (if (and (:section state) (re-matches row-pattern (:text line)))
                         (update state :candidates conj (parse-row source (:section state) line))
                         state))))
                 {:section nil :sections [] :candidates []}
                 (mapcat :lines page-data))
          candidates (:candidates state)
          parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
          positions-by-page (frequencies (map #(get-in % [:coordinates :page]) candidates))
          complete? (and (= (:expected-pages source) (count pages))
                         (= (:expected-sections source) (count (:sections state)))
                         (= (:expected-positions source) (count candidates) parsed-count)
                         (= (:expected-by-page source) positions-by-page)
                         (str/includes? (:text (first page-data))
                                        "Classifiche federali - Gara Marina di Camerota 2026"))
          reconciliation {:page-count (count pages)
                          :individual-table-count (count (:sections state))
                          :candidate-count (count candidates)
                          :parsed-count parsed-count
                          :unparsed-count (- (count candidates) parsed-count)
                          :ranked-count parsed-count :status-count 0
                          :positions-by-page positions-by-page
                          :coverage (if complete? :complete :partial)}]
      {:schema-version 3 :parser-version parser-version :source-sha256 sha256
       :status (if complete? :needs-review :partial-unsupported-needs-review)
       :pages page-data :candidates candidates :reconciliation reconciliation
       :publication {:status :blocked
                     :reasons [:owner-review-required :reconciliation-unreviewed]}})))
