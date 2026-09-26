(ns freediving.noxy-2025
  "Source-bound final result rows in the November 2025 nOxyCup PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "d544097ae251ec08801200f86aeaa95c0f90fda56c294970f87302664e85f3d2")
(def parser-version "cmas-noxycup-indoor-2025/1")
(def title "nOxyCup 2025 - Hungarian Open Indoor Apnea Championship")

(defn supported? [pages]
  (and (= 5 (count pages))
       (every? #(str/includes? % title) (map pages [0 2 3 4]))
       (str/includes? (pages 0) "DYN-BF - DYNAMIC WITH BI-FINS FINAL RESULTS")
       (str/includes? (pages 2) "STA - FINAL RESULTS")
       (str/includes? (pages 3) "DNF - DYNAMIC NO FINS FINAL RESULTS")
       (str/includes? (pages 4) "DYN - DYNAMIC WITH FINS FINAL RESULTS")
       (str/includes? (pages 1) "Boris Milosic")))

(defn- decimal [s]
  (some-> s (str/replace "," ".") bigdec))

(defn- printed-row [line discipline]
  (when-let [[_ rank name country tail]
             (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s{2,}([A-Z]{3})\s{2,}(.+?)\s*" line)]
    (let [parts (str/split (str/trim tail) #"\s{2,}")
          [first-value second-value notes] (if (= "STA" discipline)
                                             [(first parts) nil (second parts)]
                                             [(first parts) (second parts) (nth parts 2 nil)])
          dns? (= "DNS" first-value)
          distance? (not= "STA" discipline)
          distance-token? #(boolean (re-matches #"\d+(?:,\d+)?" (or % "")))
          time-parts (when (and (not distance?) (not dns?))
                       (re-matches #"(\d{2}):(\d{2})" (or first-value "")))
          valid? (and (not (str/blank? name))
                      (if dns?
                        (= 1 (count parts))
                        (if distance?
                          (and (<= 2 (count parts) 3)
                               (distance-token? first-value)
                               (distance-token? second-value))
                          (and (<= 1 (count parts) 2) time-parts
                               (< (parse-long (nth time-parts 2)) 60)))))]
      (when valid?
        (let [status (cond dns? "DNS"
                           (and notes (str/starts-with? notes "DQ")) notes)
              raw (cond-> {:rank rank :source-name name :representation country
                           :status status :notes notes}
                    distance? (assoc :realized-distance (when-not dns? first-value)
                                     :final-distance (when-not dns? second-value))
                    (not distance?) (assoc :final-result (when-not dns? first-value)))
              parsed (cond-> {:rank (some-> rank parse-long) :source-name name
                              :representation country :status status :notes notes
                              :unit (if distance? "m" "s")}
                       distance? (assoc :realized-distance (when-not dns? (decimal first-value))
                                        :final-distance (when-not dns? (decimal second-value)))
                       (not distance?) (assoc :final-time (when time-parts
                                                            {:components [(parse-long (nth time-parts 1))
                                                                          (parse-long (nth time-parts 2))]
                                                             :notation :colon-separated})
                                              :final-time-seconds (when time-parts
                                                                    (+ (* 60 (parse-long (nth time-parts 1)))
                                                                       (parse-long (nth time-parts 2))))))]
          {:raw raw :parsed parsed})))))

(defn- source-lines [page text]
  (mapv (fn [i s] {:page page :line (inc i) :text s})
        (range) (str/split text #"\n" -1)))

(defn- row-candidate [line discipline category date]
  (let [result (printed-row (:text line) discipline)
        parsed (when result (merge {:federation "CMAS" :event-date date
                                    :discipline discipline :category category}
                                   (:parsed result)))]
    {:coordinates {:page (:page line) :line (:line line)
                   :column-start 1 :column-end (inc (count (:text line)))}
     :source-lines [line]
     :raw {:line (:text line) :fields (:raw result)}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]]
                             [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn- page-info [page text]
  (let [lines (source-lines page text)
        continuation? (= page 2)
        discipline (cond continuation? "DYN-BF"
                         (str/includes? text "DYN-BF - DYNAMIC WITH BI-FINS FINAL RESULTS") "DYN-BF"
                         (str/includes? text "STA - FINAL RESULTS") "STA"
                         (str/includes? text "DNF - DYNAMIC NO FINS FINAL RESULTS") "DNF"
                         (str/includes? text "DYN - DYNAMIC WITH FINS FINAL RESULTS") "DYN")
        date (cond continuation? "2025-11-16"
                   (and discipline (str/includes? text "NOV., 15, 2025")) "2025-11-15"
                   (and discipline (str/includes? text "NOV., 16, 2025")) "2025-11-16")
        body (loop [remaining lines category nil in-table? continuation? out []]
               (if-let [line (first remaining)]
                 (let [trimmed (str/trim (:text line))
                       heading (cond (re-matches #"SENIORS(?: -)? WOMEN" trimmed) "Seniors - Women"
                                     (re-matches #"SENIORS(?: -)? MEN" trimmed) "Seniors - Men")
                       header? (and (str/includes? trimmed "Name & Surname")
                                    (str/includes? trimmed "Country"))
                       result-row? (and in-table? (not (str/blank? trimmed))
                                        (not heading) (not header?))]
                   (recur (rest remaining) (or heading category)
                          (boolean (or in-table? header?))
                          (cond-> out result-row? (conj [line (if continuation? "Seniors - Men" category)]))))
                 out))
        candidates (mapv (fn [[line category]] (row-candidate line discipline category date)) body)
        classification (cond continuation? :attempt-results-continuation
                             (and discipline date (seq candidates)) :attempt-results
                             (str/blank? text) :needs-OCR
                             :else :unsupported-page)
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        candidate-lines (set (map #(get-in % [:coordinates :line]) candidates))]
    {:page {:page page :text text :lines lines
            :status (if (seq candidates) :needs-review classification)}
     :candidates candidates
     :reconciliation {:page page :classification classification :event-date date
                      :candidate-count (count candidates)
                      :parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
                      :unparsed-count (count (filter #(= :unparsed (:parse-status %)) candidates))
                      :nonblank-line-count (count nonblank)}
     :noncandidate-lines (mapv #(assoc % :classification classification)
                               (remove #(contains? candidate-lines (:line %)) nonblank))}))

(defn parse-pages [pages]
  (let [processed (mapv (fn [i text] (page-info (inc i) text)) (range) pages)
        per-page (mapv :reconciliation processed)
        candidates (vec (mapcat :candidates processed))
        unparsed (count (filter #(= :unparsed (:parse-status %)) candidates))
        complete? (and (supported? pages)
                       (= [28 1 28 35 12] (mapv :candidate-count per-page))
                       (= [:attempt-results :attempt-results-continuation
                           :attempt-results :attempt-results :attempt-results]
                          (mapv :classification per-page))
                       (zero? unparsed))]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv :page processed) :candidates candidates
     :reconciliation {:page-count (count pages)
                      :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates)
                      :parsed-count (- (count candidates) unparsed)
                      :unparsed-count unparsed :unresolved-count (count candidates)
                      :per-page per-page
                      :nonblank-line-count (reduce + (map :nonblank-line-count per-page))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
