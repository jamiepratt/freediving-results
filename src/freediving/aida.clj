(ns freediving.aida
  (:require [clojure.string :as str]))

(def parser-version "aida-wakayama-ranking/1")
(def event-name "34th AIDA FREEDIVING WORLD CHAMPIONSHIP WAKAYAMA 2025")
(defn supported? [pages]
  (let [text (str/join "\n" pages)]
    (and (str/includes? text (str "AIDA | " event-name))
         (boolean (re-find #"Medals\s+#\s+Name\s+Nationality\s+Result\s+Announced\s+Points\s+Penalties" text)))))
(defn- header? [s]
  (or (str/includes? s (str "AIDA | " event-name))
      (re-matches #"Medals\s+#\s+Name\s+Nationality\s+Result\s+Announced\s+Points\s+Penalties" s)
      (re-matches #"(?:DYN|Female|Male|EVENT RANKING)" s)
      (re-matches #"https://www\.aidainternational\.org/EventRanking/4349#rankings\s+\d+/\d+" s)))
(defn- decimal [s] (when s (bigdec s)))
(defn- candidate [lines context]
  (let [matches (keep-indexed (fn [i {:keys [text]}]
                                (when-let [m (re-matches #"\s*(?:(\d+)\.\s+)?(.*?)\s*\b([A-Z]{3})\s+(.+?)\s*" text)] [i m])) lines)
        [idx [_ rank inline representation tail]] (when (= 1 (count matches)) (first matches))
        numeric (when tail (re-matches #"(\d+(?:\.\d+)?)\s*(m)\s+(\d+(?:\.\d+)?)\s*(m)\s+(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)" tail))
        status (when (#{"DNS" "DSQ" "DNF"} tail) tail)
        fragments (when idx (vec (remove str/blank? (map-indexed (fn [i line] (str/trim (if (= i idx) inline (:text line)))) lines))))
        valid? (and idx (seq fragments) (or numeric status))
        [_ performance unit announced announced-unit points penalty] numeric
        raw-fields {:rank rank :source-name-fragments fragments :representation representation
                    :performance performance :unit unit :announced announced :announced-unit announced-unit
                    :points points :penalty penalty :status status :tail tail}
        parsed (when valid? (merge context {:federation "AIDA" :event-name event-name :event-date nil
                                            :rank (some-> rank parse-long) :source-name (str/join " " fragments)
                                            :representation representation :performance (decimal performance)
                                            :unit unit :announced (decimal announced) :announced-unit announced-unit
                                            :points (decimal points) :penalty (decimal penalty) :penalty-unit nil
                                            :status status :card nil}))
        reasons (cond-> [:owner-review-required :event-date-not-evidenced :card-not-evidenced :penalty-unit-not-evidenced]
                  (not valid?) (conj :unrecognized-row-layout)
                  (nil? (:category context)) (conj :category-not-evidenced)
                  (nil? (:discipline context)) (conj :discipline-not-evidenced)
                  (nil? status) (conj :status-not-evidenced))]
    {:coordinates (select-keys (first lines) [:page :line])
     :source-lines lines :raw {:lines (mapv :text lines) :fields raw-fields}
     :parse-status (if valid? :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :mechanical-repairs (if (and valid? (> (count fragments) 1))
                           [{:operation :join-name-fragments-with-space :input fragments :output (:source-name parsed)
                             :identity-correction? false}] [])
     :review-status :unreviewed :unresolved-reasons reasons}))

(defn parse-pages [pages]
  (let [page-data (mapv (fn [i text] {:page (inc i) :text text
                                      :status (if (str/blank? text) :needs-OCR :text-extracted)
                                      :lines (mapv (fn [j s] {:line (inc j) :text s}) (range) (str/split text #"\n" -1))}) (range) pages)
        lines (vec (for [p page-data l (:lines p)] (assoc l :page (:page p))))
        ;; Blank lines delimit visual rows. Never merge across a header or a page.
        result (reduce (fn [{:keys [pending context] :as state} line]
                         (let [s (str/trim (:text line))
                               boundary? (or (str/blank? s) (header? s)
                                             (and (re-find #"^\d+\.\s" s)
                                                  (some #(re-find #"^\s*\d+\.\s" (:text %)) pending))
                                             (and (seq pending) (not= (:page line) (:page (last pending)))))
                               state (if (and boundary? (seq pending))
                                       (-> state (update :candidates conj (candidate pending context)) (assoc :pending [])) state)
                               state (cond
                                       (#{"Female" "Male"} s) (assoc-in state [:context :category] s)
                                       (= "DYN" s) (assoc-in state [:context :discipline] s)
                                       :else state)
                               footer (re-matches #"https://www\.aidainternational\.org/EventRanking/4349#rankings\s+(\d+)/(\d+)" s)
                               state (if (and footer (= (second footer) (nth footer 2)))
                                       (assoc state :context {:category nil :discipline nil}) state)]
                           (cond (str/blank? s) state
                                 (header? s) (update state :noncandidate-lines conj (assoc line :reason :recognized-header-or-footer))
                                 :else (update state :pending conj line))))
                       {:pending [] :context {:category nil :discipline nil} :candidates [] :noncandidate-lines []} lines)
        candidates (cond-> (:candidates result) (seq (:pending result)) (conj (candidate (:pending result) (:context result))))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        per-page (mapv (fn [p] (let [cs (filter #(= p (get-in % [:coordinates :page])) candidates)
                                     n (count cs) parsed (count (filter #(= :parsed (:parse-status %)) cs))]
                                 {:page p :candidate-count n :parsed-count parsed :unparsed-count (- n parsed) :unresolved-count n}))
                       (range 1 (inc (count pages))))]
    {:parser-version parser-version :schema-version 2
     :status (if (some #(= :needs-OCR (:status %)) page-data) :needs-OCR :needs-review)
     :pages page-data :candidates candidates
     :reconciliation {:page-count (count pages) :candidate-count (count candidates) :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count) :unresolved-count (count candidates)
                      :nonblank-line-count (count (remove #(str/blank? (:text %)) lines))
                      :noncandidate-lines (:noncandidate-lines result) :per-page per-page :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
