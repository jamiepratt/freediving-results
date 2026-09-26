(ns freediving.deep-dominica-2025
  "Source-bound printed positions in the Deep Dominica 2025 final results PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "462d904bb15c029b6dd2c83eaa1942f0c41d73e85fb601fa4d3a4dd0a15b1abc")
(def parser-version "cmas-deep-dominica-2025/1")
(def ^:private title "DEEP DOMINICA FREEDIVING COMPETITION")

(defn supported? [pages]
  (and (= 2 (count pages))
       (str/includes? (first pages) title)
       (str/includes? (first pages) "FINAL RESULTS")
       (every? #(str/includes? (first pages) (str "DAY " % "          Family Name")) (range 1 5))
       (every? #(str/includes? (second pages) (str "DAY " % "          Family Name")) (range 5 7))))

(defn- row-date [text]
  (when-let [[_ month day] (re-find #"^(November|December) (\d+)(?:st|th)\s+" text)]
    (case [month day]
      ["November" "24"] "2025-11-24"
      ["November" "25"] "2025-11-25"
      ["November" "27"] "2025-11-27"
      ["November" "28"] "2025-11-28"
      ["November" "30"] "2025-11-30"
      ["December" "1"] "2025-12-01"
      nil)))

(defn- value [s]
  (when (and s (re-matches #"\d+" s)) (bigdec s)))

(defn- printed-row [text date]
  (let [body (-> text str/trim (str/replace #"^(?:November|December) \d+(?:st|th)\s+" ""))
        parts (str/split body #"\s{2,}")
        parts (if-let [[_ country gender] (re-matches #"(.+?)\s+(FEMALE|Female|female|Male|male)" (or (second parts) ""))]
                (vec (concat [(first parts) country gender] (drop 2 parts)))
                parts)
        [name country gender declared discipline reached result final-points notes] parts
        numeric-or-dns? #(boolean (re-matches #"(?:\d+|DNS)" (or % "")))]
    (when (and (<= 8 (count parts) 9)
               (not (str/blank? name)) (not (str/blank? country))
               (re-matches #"(?:FEMALE|Female|female|Male|male)" (or gender ""))
               (re-matches #"\d+" (or declared ""))
               (re-matches #"(?:FIM|CWT|CWT-BF|CNF)" (or discipline ""))
               (numeric-or-dns? reached)
               (re-matches #"(?:White|WHITE|Yellow|Red|RED|DNS)" (or result ""))
               (numeric-or-dns? final-points)
               date)
      {:raw {:source-name name :representation country :gender gender
             :declared-depth declared :discipline discipline :reached-depth reached
             :result result :final-points final-points :notes notes}
       :parsed {:federation "CMAS" :event-date date :source-name name
                :representation country :gender gender :category nil
                :declared-depth (value declared) :discipline discipline
                :reached-depth (value reached) :result result
                :final-points (value final-points) :notes notes :unit nil}})))

(defn- candidate [line date]
  (let [row (printed-row (:text line) date)
        parsed (:parsed row)]
    {:coordinates {:page (:page line) :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :source-lines [line]
     :raw {:line (:text line) :fields (:raw row)}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (= "OPENER" (:source-name parsed)) (conj :opener-identity-uncertain)
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn- source-lines [page text]
  (mapv (fn [i s] {:page page :line (inc i) :text s})
        (range) (str/split text #"\n" -1)))

(defn parse-pages [pages]
  (let [all-lines (mapcat (fn [i text] (source-lines (inc i) text)) (range) pages)
        processed (loop [remaining all-lines day nil date nil candidates []]
                    (if-let [line (first remaining)]
                      (let [trimmed (str/trim (:text line))
                            heading (some-> (re-find #"^DAY ([1-6])\s+Family Name" trimmed) second parse-long)
                            next-date (or (row-date trimmed) date)
                            row? (and day (not heading) (not (str/blank? trimmed)))
                            candidate (when row? (candidate line next-date))]
                        (recur (rest remaining) (or heading day) next-date
                               (cond-> candidates candidate (conj candidate))))
                      candidates))
        candidates (vec processed)
        per-page (mapv (fn [page text]
                         (let [rows (filter #(= page (get-in % [:coordinates :page])) candidates)]
                           {:page page :classification :attempt-results
                            :candidate-count (count rows)
                            :parsed-count (count (filter #(= :parsed (:parse-status %)) rows))
                            :unparsed-count (count (filter #(= :unparsed (:parse-status %)) rows))
                            :nonblank-line-count (count (remove str/blank? (str/split text #"\n" -1)))}))
                       (range 1 (inc (count pages))) pages)
        unparsed (count (filter #(= :unparsed (:parse-status %)) candidates))
        complete? (and (supported? pages) (= [47 28] (mapv :candidate-count per-page))
                       (zero? unparsed))
        candidate-lines (set (map #(select-keys (:coordinates %) [:page :line]) candidates))
        noncandidate-lines (vec (remove #(contains? candidate-lines (select-keys % [:page :line]))
                                        (remove #(str/blank? (:text %)) all-lines)))]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv (fn [page text] {:page page :text text :lines (source-lines page text)
                                   :status :needs-review}) (range 1 (inc (count pages))) pages)
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count (- (count candidates) unparsed)
                      :unparsed-count unparsed :unresolved-count (count candidates)
                      :per-page per-page
                      :nonblank-line-count (reduce + (map :nonblank-line-count per-page))
                      :noncandidate-lines noncandidate-lines :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
