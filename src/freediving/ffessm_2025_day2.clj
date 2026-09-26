(ns freediving.ffessm-2025-day2
  "Source-bound extraction of the French 2025 outdoor championship day-two table."
  (:require [clojure.string :as str]))

(def source-sha256 "69b512bc86fd27d6558b4dfc41495389c31a16987a59ad6c641804ab1f293930")
(def parser-version "ffessm-france-outdoor-day2-2025/1")
(def ^:private heading "Résultats Eau Libre J2")
(def ^:private title "Championnat de France 2025 - Villefranche-sur-mer")
(def ^:private expected-rows 32)

(defn supported? [pages]
  (and (= 1 (count pages))
       (str/includes? (first pages) heading)
       (str/includes? (first pages) title)))

(defn- source-lines [text]
  (mapv (fn [index line] {:page 1 :line (inc index) :text line})
        (range) (str/split text #"\n" -1)))

(def ^:private result-pattern
  #"\s*(\S+)\s{2,}(.+?)\s+(Homme|Femme)\s+(\S+)\s{2,}(FFESSM|CMAS)\s{2,}(\d+\s*m)\s+(FIM|CWT-MONO|CWT-BI|CNF)\s{2,}(\d+\s*m)\s{2,}(\d+)\s{2,}(?:(\d+)\s{2,})?(-?\d+)\s{2,}(Blanc|Jaune|Rouge)(?:\s{2,}(.+?))?\s*")

(defn- row-values [text]
  (when-let [[_ family given sex nationality federation announced discipline realized
              depth-penalty plate-penalty points card comments]
             (re-matches result-pattern text)]
    (let [number (fn [value] (bigdec (str/replace value #"\s*m$" "")))
          announced-value (number announced)
          realized-value (number realized)
          depth-penalty-value (bigdec depth-penalty)
          plate-penalty-value (some-> plate-penalty bigdec)
          points-value (bigdec points)
          valid? (and (pos? announced-value)
                      (<= 0M realized-value announced-value)
                      (= depth-penalty-value (- announced-value realized-value))
                      (or (and (= card "Blanc") (zero? depth-penalty-value)
                               (nil? plate-penalty-value) (= points-value realized-value)
                               (nil? comments))
                          (and (= card "Jaune") (= 1M plate-penalty-value)
                               (= points-value (- realized-value depth-penalty-value plate-penalty-value))
                               (= comments "Annonce non atteinte"))
                          (and (= card "Rouge") (nil? plate-penalty-value)
                               (zero? points-value) (= comments "DQ syncope surface"))))]
      (when valid?
        {:raw {:family-name family :given-name given :sex sex :nationality nationality
               :federation federation :announced-depth announced :discipline discipline
               :realized-depth realized :depth-penalty depth-penalty
               :plate-penalty plate-penalty :final-points points :card card
               :comments comments}
         :parsed {:federation federation :event-date "2025-06-28"
                  :event-date-evidence :official-index-day-two
                  :source-name (str given " " family) :family-name family :given-name given
                  :gender (case sex "Homme" "M" "Femme" "F")
                  :source-sex sex :nationality nationality :category nil
                  :announced-depth announced-value :realized-depth realized-value
                  :depth-penalty depth-penalty-value :plate-penalty plate-penalty-value
                  :final-points points-value :discipline discipline :card card
                  :comments comments :unit "m"}}))))

(defn- candidate [{:keys [page line text] :as source}]
  (let [result (row-values text)
        parsed (:parsed result)]
    {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
     :source-lines [source]
     :raw {:line text :fields (:raw result)}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[key value]]
                             [key {:status (if (nil? value) :unknown :parsed)
                                   :value value}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [pages]
  (let [text (first pages)
        lines (if text (source-lines text) [])
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        header (take 5 nonblank)
        header-ok? (and (supported? pages)
                        (= 5 (count header))
                        (= heading (str/trim (:text (nth header 0))))
                        (= title (str/trim (:text (nth header 1))))
                        (str/includes? (:text (nth header 2)) "Pénalités prof")
                        (str/includes? (:text (nth header 3)) "Compétiteurs")
                        (str/includes? (:text (nth header 4)) "Annoncée"))
        rows (if header-ok? (drop 5 nonblank) [])
        candidates (mapv candidate rows)
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and header-ok? (= expected-rows (count candidates))
                       (= parsed expected-rows))]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text text :lines lines
              :status (cond (not header-ok?) :unsupported-needs-parser
                            complete? :needs-review
                            :else :partial-unsupported-needs-parser)}]
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates) :expected-rows expected-rows
                      :nonblank-line-count (count nonblank)
                      :noncandidate-lines (vec (if header-ok? header nonblank))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (not complete?) (conj :unsupported-layout-or-row-count))}}))
