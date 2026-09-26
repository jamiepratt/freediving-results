(ns freediving.world-games-series-2025
  "Source-bound heat results in the March 2025 CMAS World Games Series resultbook."
  (:require [clojure.string :as str]))

(def source-sha256 "d8f13447d1289726ef635deb00da142157533c71870faf4e6cf7032bb37b9f5e")
(def parser-version "cmas-world-games-series-2025-03/1")

(def headings
  {"Men's Dynamic with Fins" ["Men" "DYN" "2025-03-30"]
   "Men's Dynamic without Fins" ["Men" "DNF" "2025-03-29"]
   "Women's Dynamic with Fins" ["Women" "DYN" "2025-03-30"]
   "Women's Dynamic without Fins" ["Women" "DNF" "2025-03-29"]})

(defn supported? [pages]
  (and (some #(str/includes? % "CSU Natatorium") pages)
       (some #(str/includes? % "RESULTS BOOK") pages)
       (some #(str/includes? % "Dynamic without Fins") pages)))

(defn- result-row [s]
  (when-let [[_ rank lane name noc card realized final behind]
             (re-matches #"\s*(?:(\d+)\s+)?(\d+)\s+(.+?)\s{2,}(AIN|[A-Z]{3})\s+(WHITE|RED)\s*(?:(\d+\.\d)\s+(\d+\.\d)(?:\s+(\d+\.\d))?|DSQ)\s*" s)]
    (let [dsq? (= "RED" card)]
      (when (= dsq? (nil? realized))
        {:raw {:heat-rank rank :lane lane :source-name name :representation noc
               :card card :realized-distance realized :final-distance final
               :distance-behind behind :status (when dsq? "DSQ")}
         :parsed {:heat-rank (some-> rank parse-long) :lane (parse-long lane)
                  :source-name name :representation noc :card card
                  :realized-distance (some-> realized bigdec)
                  :final-distance (some-> final bigdec)
                  :distance-behind (some-> behind bigdec)
                  :status (when dsq? "DSQ") :unit "m"}}))))

(defn- page-info [page text]
  (let [lines (mapv (fn [i s] {:page page :line (inc i) :text s})
                    (range) (str/split text #"\n" -1))
        heading (some (fn [h] (when (str/includes? text h) h)) (keys headings))
        heat (some-> (re-find #"(?m)^\s*(?:SAT|SUN) \d{2} MAR 2025\s+(Heat [12])\s*$" text) second)
        result? (boolean (re-find #"(?m)^\s*Results\s*(?:REVISED)?\s*$" text))
        summary? (str/includes? text "Results Summary")
        header-index (last (keep-indexed (fn [i line]
                                           (when (and (str/includes? (:text line) "Code")
                                                      (or (str/includes? (:text line) "Dist. (m)")
                                                          (str/includes? (:text line) "Card"))) i)) lines))
        ;; In the publisher's heat pages the contiguous row block follows the
        ;; final column header and ends at the first blank line.
        row-lines (if (and heat result? header-index)
                    (->> (subvec lines (inc header-index))
                         (drop-while #(str/blank? (:text %)))
                         (take-while #(not (str/blank? (:text %)))) vec)
                    [])
        [category discipline date] (get headings heading)
        context {:federation "CMAS" :event-date date :discipline discipline
                 :category category :session heat :event-number (case discipline "DNF" (if (= category "Men") 2 1)
                                                                      "DYN" (if (= category "Men") 4 3) nil)}
        candidates (mapv (fn [line]
                           (let [r (result-row (:text line))
                                 parsed (when r (merge context (:parsed r)))]
                             {:coordinates {:page page :line (:line line) :column-start 1
                                            :column-end (inc (count (:text line)))}
                              :source-lines [line] :raw {:line (:text line) :fields (:raw r)}
                              :parse-status (if r :parsed :unparsed)
                              :parsed parsed
                              :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
                              :review-status :unreviewed
                              :unresolved-reasons (cond-> [:owner-review-required]
                                                    (nil? r) (conj :unparsed-source-line))})) row-lines)
        classification (cond
                         (str/blank? text) (if (= page 1) :blank-front-page :needs-OCR)
                         (and heat result? header-index) :heat-results
                         summary? :supporting-summary
                         heading :supporting-page
                         :else :front-matter)
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        candidate-lines (set (map #(get-in % [:coordinates :line]) candidates))]
    {:page {:page page :text text :lines lines
            :status (if (= classification :heat-results) :needs-review classification)}
     :candidates candidates
     :reconciliation {:page page :classification classification
                      :candidate-count (count candidates)
                      :parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
                      :unparsed-count (count (filter #(= :unparsed (:parse-status %)) candidates))
                      :nonblank-line-count (count nonblank)}
     :noncandidate-lines (mapv #(assoc % :classification classification)
                               (remove #(contains? candidate-lines (:line %)) nonblank))}))

(defn parse-pages [pages]
  (let [processed (mapv (fn [i text] (page-info (inc i) text)) (range) pages)
        candidates (vec (mapcat :candidates processed))
        per-page (mapv :reconciliation processed)
        heat-pages (filter #(= :heat-results (:classification %)) per-page)
        unparsed (count (filter #(= :unparsed (:parse-status %)) candidates))
        unsupported (mapv :page (filter #(= :needs-OCR (:classification %)) per-page))]
    {:parser-version parser-version :schema-version 3
     :status (if (or (seq unsupported) (not= 8 (count heat-pages)) (pos? unparsed))
               :partial-unsupported-needs-parser :needs-review)
     :pages (mapv :page processed) :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if (= 8 (count heat-pages)) :complete :partial)
                      :heat-page-count (count heat-pages) :unsupported-pages unsupported
                      :candidate-count (count candidates) :parsed-count (- (count candidates) unparsed)
                      :unparsed-count unparsed :unresolved-count (count candidates)
                      :per-page per-page
                      :nonblank-line-count (reduce + (map :nonblank-line-count per-page))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
