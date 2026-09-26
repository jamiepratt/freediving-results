(ns freediving.ffessm-2025-immersion-libre
  "Source-bound FFESSM 2025 immersion libre category rankings."
  (:require [clojure.string :as str]))

(def women-sha256 "0f2a7ce0dd6aacd16bbf2ccac62e9ddec695248049b400ca9ee89668c133c3a7")
(def men-sha256 "801f0e1e68dd70942395beb9bd316a8b6e73f5cac55cc44f20d0c026c8852e3b")

(def ^:private profiles
  {women-sha256 {:heading "Résultats Immersion Libre Femmes" :category "Femmes"
                 :gender "F" :rows 7 :version "ffessm-2025-immersion-libre-femmes/1"}
   men-sha256 {:heading "Résultats Immersion Libre Hommes" :category "Hommes"
               :gender "M" :rows 9 :version "ffessm-2025-immersion-libre-hommes/1"}})

(defn parser-version [sha256] (:version (get profiles sha256)))

(defn matching-sha [pages]
  (when (= 1 (count pages))
    (let [page (first pages)]
      (some (fn [[sha {:keys [heading]}]]
              (when (and (str/includes? page "Championnat de France Eau Libre 2025")
                         (str/includes? page heading)
                         (str/includes? page "Villefranche-sur-Mer")
                         (str/includes? page "\nFFESSM\n")
                         (str/includes? page "Classement")) sha)) profiles))))

(defn supported? [sha256 pages]
  (boolean (and (get profiles sha256) (= sha256 (matching-sha pages)))))

(defn- source-lines [page]
  (mapv (fn [index text] {:page 1 :line (inc index) :text text})
        (range) (str/split page #"\n" -1)))

(defn- integer [value]
  (when (and value (re-matches #"\d+" value)) (parse-long value)))

(defn- row-values [text {:keys [category gender]}]
  (let [cells (str/split (str/trim text) #"\s{2,}")
        [rank family given nationality announced realized depth-penalty plate-penalty points card] cells
        announced-num (some->> announced (re-matches #"(\d+) m") second integer)
        realized-num (integer realized)
        depth-num (integer depth-penalty)
        plate-num (integer plate-penalty)
        points-num (integer points)]
    (when (and (= 10 (count cells)) (integer rank)
               (contains? #{"Française" "Italienne"} nationality)
               announced-num realized-num depth-num plate-num points-num
               (pos? announced-num) (<= 0 realized-num announced-num)
               (= depth-num (- announced-num realized-num))
               (= points-num (- realized-num depth-num plate-num))
               (= card (if (zero? plate-num) "Blanc" "Jaune")))
      {:raw {:rank rank :family-name family :given-name given :nationality nationality
             :announced-depth announced :realized-depth realized :depth-penalty depth-penalty
             :plate-penalty plate-penalty :final-points points :card card}
       :parsed {:federation "FFESSM" :ranking-scope "FFESSM" :event-date nil :session nil
                :source-name (str given " " family) :family-name family :given-name given
                :gender gender :nationality nationality :category category :discipline "FIM"
                :rank (parse-long rank) :raw-rank rank
                :announced-depth (bigdec announced-num) :realized-depth (bigdec realized-num)
                :depth-penalty (bigdec depth-num) :plate-penalty (bigdec plate-num)
                :final-points (bigdec points-num) :card card :unit "m"
                :result-status :valid}})))

(defn- candidate [line profile]
  (let [{:keys [raw parsed]} (row-values (:text line) profile)]
    {:coordinates {:page 1 :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :source-lines [line] :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[key value]]
                             [key {:status (if (nil? value) :unknown :parsed) :value value}])
                           parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [sha256 pages]
  (let [{:keys [rows version] :as profile} (get profiles sha256)
        page (or (first pages) "")
        lines (source-lines page)
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        results (->> nonblank
                     (filter #(re-matches #"\s*\d+\s{2,}.*" (:text %))) vec)
        candidates (mapv #(candidate % profile) results)
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and (supported? sha256 pages) (= rows (count results))
                       (= rows parsed-count))
        candidate-lines (set (map :line results))]
    {:parser-version version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text page :lines lines
              :status (if complete? :needs-review :unsupported-page)}]
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates) :expected-rows rows
                      :nonblank-line-count (count nonblank)
                      :noncandidate-lines (vec (remove #(contains? candidate-lines (:line %)) nonblank))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (not complete?) (conj :unsupported-layout-or-row-count))}}))
