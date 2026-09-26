(ns freediving.ffessm-2025-day1
  "Source-bound extraction of the French 2025 outdoor championship day-one table."
  (:require [clojure.string :as str]))

(def source-sha256 "88424b8970a669117ac354c883d299ee32112e7dc37ef89f62ae9c4837976af4")
(def parser-version "ffessm-france-outdoor-day1-2025/1")
(def ^:private heading "Résultats Eau Libre J1")
(def ^:private title "Championnat de France 2025 - Villefranche sur mer")
(def ^:private expected-rows 39)

(defn supported? [pages]
  (and (= 1 (count pages))
       (str/includes? (first pages) heading)
       (str/includes? (first pages) title)))

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- source-lines [text]
  (mapv (fn [index line] {:page 1 :line (inc index) :text line})
        (range) (str/split text #"\n" -1)))

(defn- row [text]
  (when-let [[_ family given sex nationality federation announced discipline realized depth-penalty plate-penalty points card comments]
             (re-matches #"\s*(\S+)\s{2,}(.+?)\s+(Homme|Femme)\s+(\S+)\s{2,}(FFESSM|CMAS)\s{2,}(\d+)\s*m\s{2,}(FIM|CWT-MONO|CWT-BI|CNF)\s{2,}(\d+)\s*m\s{2,}(\d+)\s{2,}(?:(\d+)\s{2,})?(-?\d+)\s{2,}(Blanc|Jaune|Rouge)(?:\s{2,}(.+?))?\s*" text)]
    (let [announced (bigdec announced)
          realized (bigdec realized)
          depth-penalty (bigdec depth-penalty)
          plate-penalty (some-> plate-penalty bigdec)
          points (bigdec points)
          comments (some-> comments str/trim)
          valid? (and (pos? announced) (<= 0M realized announced)
                      (= depth-penalty (- announced realized))
                      (or (and (= card "Blanc") (zero? depth-penalty)
                               (nil? plate-penalty) (= points realized) (nil? comments))
                          (and (= card "Jaune") (= 1M plate-penalty)
                               (= points (- realized depth-penalty plate-penalty))
                               (= comments "Annonce non atteinte"))
                          (and (= card "Rouge") (nil? plate-penalty)
                               (zero? points) (contains? #{"DQ syncope surface" "DQ syncope profonde"} comments))))]
      (when valid?
        {:raw {:family-name family :given-name given :sex sex :nationality nationality
               :federation federation :announced-depth (str announced) :discipline discipline
               :realized-depth (str realized) :depth-penalty (str depth-penalty)
               :plate-penalty (some-> plate-penalty str) :final-points (str points)
               :card card :comments comments}
         :parsed {:federation federation :event-date "2025-06-27"
                  :event-date-evidence :official-index-day-one
                  :source-name (str given " " family) :family-name family :given-name given
                  :gender (case sex "Homme" "M" "Femme" "F")
                  :source-sex sex :nationality nationality :category nil
                  :announced-depth announced :realized-depth realized
                  :depth-penalty depth-penalty :plate-penalty plate-penalty
                  :final-points points :discipline discipline :card card :comments comments
                  :unit "m"}}))))

(defn- candidate [{:keys [page line text] :as source}]
  (let [result (row text)
        parsed (:parsed result)]
    {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
     :source-lines [source]
     :raw {:line text :fields (:raw result)}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[key value]] [key (field value)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [pages]
  (let [text (first pages)
        lines (if text (source-lines text) [])
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        header (take 7 nonblank)
        header-ok? (and (supported? pages)
                        (= 7 (count header))
                        (= heading (str/trim (:text (nth header 0))))
                        (= title (str/trim (:text (nth header 1))))
                        (str/includes? (:text (nth header 2)) "Défaut")
                        (str/includes? (:text (nth header 3)) "Pénalités prof")
                        (str/includes? (:text (nth header 4)) "Compétiteurs")
                        (str/includes? (:text (nth header 5)) "Annoncée")
                        (str/includes? (:text (nth header 6)) "1pt"))
        rows (if header-ok? (drop 7 nonblank) [])
        candidates (mapv candidate rows)
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and header-ok? (= expected-rows (count candidates)) (= parsed expected-rows))]
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
