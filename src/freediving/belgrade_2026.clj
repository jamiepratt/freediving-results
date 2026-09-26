(ns freediving.belgrade-2026
  "Source-bound, review-only extraction for the 2026 Belgrade Freediving Open PDF."
  (:require [clojure.string :as str]))

(def parser-version "belgrade-freediving-open-2026/2")
(def source-sha256 "336a72bb0938dfab7085b0bd3fde2f768cc5028f7897391989b1012bd2f9e146")
(def title "2026 Belgrade Freediving Open")
(def place-date "Serbia, Futog, 25.04.2026")

(defn supported? [pages]
  (boolean (some #(and (str/includes? % title) (str/includes? % place-date)) pages)))

(defn- heading [text]
  (or (when (= "SPE" (str/trim text)) "SPE")
      (when (re-find #"\bAthlete\s+Pol\s+Klub\s+Ostvareno\b" text)
        (some-> (re-find #"^\s*(DNF|DYNBF|DYN|SPE)\b" text) second))))

(defn- athlete-line? [text]
  (boolean (re-find #"Žensko / Female|Muško / Male" text)))

(defn- row [text discipline]
  (when-let [[_ rank name gender right]
             (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s{2,}(Žensko / Female|Muško / Male)\s{2,}(.+)" text)]
    (when-let [[_ club result]
               (re-matches #"\s*(?:(.+?)\s{2,})?(DNS|\d+(?:\.\d+)?(?: DSQ SP)?|\d{2}:\d{2},\d{2})\s*" right)]
      (let [status (cond (= result "DNS") "DNS"
                         (str/ends-with? result " DSQ SP") "DSQ SP"
                         :else "valid")]
        {:raw {:rank rank :source-name (str/trim name) :gender gender
               :club club :result result}
         :parsed {:federation "CMAS" :event-date "2026-04-25"
                  :discipline discipline :category (if (= gender "Žensko / Female") "Female" "Male")
                  :source-name (str/trim name) :rank (some-> rank parse-long)
                  :gender gender :club club :result result :result-status status}}))))

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- candidate [line discipline heading-evidence]
  (let [parsed-row (row (:text line) discipline)
        parsed (:parsed parsed-row)]
    {:coordinates {:page (:page line) :line (:line line)
                   :column-start 1 :column-end (inc (count (:text line)))}
     :raw {:line (:text line) :fields (:raw parsed-row)}
     :source-lines [line] :metadata-evidence (if heading-evidence [heading-evidence] [])
     :parse-status (if parsed-row :parsed :unparsed)
     :parsed parsed :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed-row) (conj :unparsed-source-line)
                           (and parsed-row (nil? (:club parsed))) (conj :club-unresolved))}))

(defn- classify [text]
  (let [s (str/trim text)]
    (cond (= s title) :title
          (= s place-date) :place-date
          (heading text) :discipline-heading
          (= s "SPE") :discipline-heading
          (= s "2x50") :discipline-qualifier
          (= s "CLUBS RANKING - SERBIA") :club-ranking-title
          (re-find #"^RB\s+KLUB\s+BODOVI$" s) :club-ranking-header
          (re-find #"^\d+\s+.+\s+\d+$" s) :club-ranking-row
          (#{"Glavni sudija" "Milan Pavković" "Božana Ostojić"} s) :footer
          (or (str/includes? s "Organizacioni odbor") (= s "s.r.")) :footer
          :else :unresolved-source-line)))

(defn- parse-page [page text]
  (let [lines (mapv (fn [i s] {:page page :line (inc i) :text s})
                    (range) (str/split text #"\n" -1))
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        headings (filter #(heading (:text %)) nonblank)
        valid? (and (some #(= title (str/trim (:text %))) nonblank)
                    (some #(= place-date (str/trim (:text %))) nonblank)
                    (seq headings)
                    (not-any? #(and (re-find #"\bAthlete\s+Pol\s+Klub\s+Ostvareno\b" (:text %))
                                    (nil? (heading (:text %)))
                                    (not (some #{"SPE"} (map (comp str/trim :text) nonblank)))) nonblank)
                    (every? #(or (not= "SPE" (heading (:text %)))
                                 (some (fn [line] (= "2x50" (str/trim (:text line)))) nonblank)) headings))
        processed (if valid?
                    (loop [remaining nonblank discipline nil heading-evidence nil candidates [] noncandidates []]
                      (if-let [line (first remaining)]
                        (let [h (heading (:text line))
                              d (when h (if (= h "SPE") "SPE 2x50" h))]
                          (cond
                            h (recur (rest remaining) d line candidates
                                     (conj noncandidates (assoc line :classification :discipline-heading)))
                            (athlete-line? (:text line))
                            (recur (rest remaining) discipline heading-evidence
                                   (conj candidates (candidate line discipline heading-evidence)) noncandidates)
                            :else (recur (rest remaining) discipline heading-evidence candidates
                                         (conj noncandidates (assoc line :classification (classify (:text line)))))))
                        {:candidates candidates :noncandidate-lines noncandidates}))
                    {:candidates []
                     :noncandidate-lines (mapv #(assoc % :classification :unsupported-page-line) nonblank)})
        candidates (mapv (fn [c]
                           (if (and (= :parsed (:parse-status c))
                                    (nil? (get-in c [:parsed :club])))
                             (let [line (get-in c [:coordinates :line])
                                   adjacent (vec (filter
                                                  #(and (= :unresolved-source-line (:classification %))
                                                        (= 1 (abs (- line (:line %)))))
                                                  (:noncandidate-lines processed)))]
                               (assoc-in c [:raw :adjacent-source-evidence] adjacent))
                             c))
                         (:candidates processed))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:page page :text text :lines lines
     :status (cond (empty? nonblank) :needs-OCR valid? :needs-review :else :unsupported-needs-parser)
     :candidates candidates :noncandidate-lines (:noncandidate-lines processed)
     :reconciliation {:page page :supported? (boolean valid?)
                      :candidate-count (when valid? (count candidates))
                      :parsed-count (when valid? parsed)
                      :unparsed-count (when valid? (- (count candidates) parsed))
                      :unresolved-count (when valid? (count candidates))
                      :nonblank-line-count (count nonblank)}}))

(defn parse-pages [pages]
  (let [processed (mapv (fn [i text] (parse-page (inc i) text)) (range) pages)
        candidates (vec (mapcat :candidates processed))
        unsupported (mapv :page (remove #(= :needs-review (:status %)) processed))
        ocr (mapv :page (filter #(= :needs-OCR (:status %)) processed))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :schema-version 3
     :status (if (seq unsupported) :partial-unsupported-needs-parser :needs-review)
     :pages (mapv #(dissoc % :candidates :noncandidate-lines :reconciliation) processed)
     :candidates candidates
     :reconciliation {:page-count (count pages)
                      :coverage (if (seq unsupported) :partial :complete)
                      :unsupported-pages unsupported :needs-ocr-pages ocr
                      :per-page (mapv :reconciliation processed)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates) :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (seq unsupported) (conj :unsupported-pages)
                              (seq ocr) (conj :needs-OCR))}}))
