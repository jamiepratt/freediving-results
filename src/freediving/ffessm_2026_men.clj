(ns freediving.ffessm-2026-men
  "Source-bound 2026 FFESSM men's monofin ranking."
  (:require [clojure.string :as str]))

(def source-sha256 "88396b9204f154b29fd439ce91bd538f8502f7e91a76f1cb2ca2dafb62fd780d")
(def parser-version "ffessm-2026-cwt-men/1")

(defn supported? [pages]
  (and (= 1 (count pages))
       (str/includes? (first pages) "Championnat de France Eau Libre 2026")
       (str/includes? (first pages) "Résultats Monopalme Hommes")
       (str/includes? (first pages) "Classement")
       (str/includes? (first pages) "COMMENTAIRES")))

(defn- source-lines [text]
  (mapv (fn [index value] {:page 1 :line (inc index) :text value})
        (range) (str/split text #"\n" -1)))

(defn- position-line? [text]
  (boolean (re-find #"^\s*(?:\d+\s{2,})?\S+\s{2,}\S+\s{2,}Homme\s{2,}" text)))

(defn- measured [value]
  (when-let [[_ number] (and value (re-matches #"(\d+)\s*m" value))]
    (bigdec number)))

(defn- penalty [value]
  (when-let [[_ number] (and value (re-matches #"(\d+)m" value))]
    (bigdec number)))

(defn- row-values [text]
  (let [cells (str/split (str/trim text) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first cells)))
        [rank surname given sex nationality federation announced discipline reached raw-penalty points card comment]
        (if ranked? cells (cons nil cells))
        declared (measured announced)
        realized (measured reached)
        depth-penalty (penalty raw-penalty)
        final-performance (when (and points (re-matches #"\d+" points)) (bigdec points))
        valid? (and (<= 12 (count cells) 13)
                    (= "Homme" sex) (= "FFESSM" federation) (= "CWT" discipline)
                    (contains? #{"Française" "Francaise"} nationality)
                    declared realized depth-penalty final-performance
                    (pos? declared) (<= 0M realized declared)
                    (= depth-penalty (- declared realized))
                    (or (and ranked? (= "Blanc" card) (nil? comment)
                             (zero? depth-penalty) (= final-performance realized))
                        (and (not ranked?) (= "Rouge" card)
                             (= "DQ syncope surface" comment)
                             (zero? final-performance))))]
    (when valid?
      {:raw {:rank rank :surname surname :given-name given :sex sex
             :nationality nationality :federation federation :depth-declared announced
             :discipline discipline :depth-reached reached :depth-penalty raw-penalty
             :final-performance points :card card :comment comment}
       :parsed {:federation federation :event-date nil
                :source-name (str given " " surname) :gender "M"
                :representation nationality :category "Hommes" :discipline discipline
                :ranking-scope :classement :rank (some-> rank parse-long)
                :depth-declared declared :depth-reached realized
                :depth-penalty depth-penalty :final-performance final-performance
                :card card :reason comment :unit "m"
                :result-status (if (= "Rouge" card) :disqualified :valid)}})))

(defn- candidate [line]
  (let [{:keys [raw parsed]} (row-values (:text line))]
    {:coordinates {:page 1 :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :ranking-scope :classement
     :source-lines [line]
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[key value]]
                             [key {:status (if (nil? value) :unknown :parsed) :value value}])
                           parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [pages]
  (let [text (or (first pages) "")
        lines (source-lines text)
        candidates (->> lines (filter #(position-line? (:text %))) (mapv candidate))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        ranked-count (count (filter #(some? (get-in % [:parsed :rank])) candidates))
        disqualified-count (count (filter #(= :disqualified (get-in % [:parsed :result-status])) candidates))
        complete? (and (supported? pages) (= 8 (count candidates)) (= 8 parsed-count)
                       (= 7 ranked-count) (= 1 disqualified-count))
        candidate-lines (set (map #(get-in % [:coordinates :line]) candidates))
        nonblank (remove #(str/blank? (:text %)) lines)]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text text :lines lines
              :status (if complete? :needs-review :unsupported-page)}]
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates) :section-counts {:classement (count candidates)}
                      :ranked-count ranked-count :disqualified-count disqualified-count
                      :linked-subset-count 0 :unique-performance-count nil
                      :nonblank-line-count (count nonblank)
                      :noncandidate-lines (vec (remove #(contains? candidate-lines (:line %)) nonblank))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
