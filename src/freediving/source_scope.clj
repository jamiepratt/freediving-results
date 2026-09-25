(ns freediving.source-scope
  "Typed source facts for partial scope descriptors. Missing facts stay absent."
  (:require [clojure.string :as str]
            [freediving.html-evidence :as evidence]
            [freediving.aida-html :as html])
  (:import [org.jsoup Jsoup]))

(defn- visible? [element]
  (not-any? (fn [e]
              (or (#{"template" "script" "noscript"} (.tagName e))
                  (.hasAttr e "hidden")
                  (= "true" (str/lower-case (str/trim (.attr e "aria-hidden"))))
                  (re-find #"(?i)(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden)\s*(?:!important\s*)?(?:;|$)" (.attr e "style"))))
            (cons element (.parents element))))
(defn- selected [doc selector]
  (let [xs (.select doc selector)]
    (when (and (= 1 (count xs)) (visible? (first xs)))
      (let [s (str/trim (.wholeText (first xs)))] (when-not (str/blank? s) s)))))

(defn- registered-event [artifact]
  (when-not (= (:job-id artifact) (html/digest (select-keys artifact html/identity-keys)))
    (throw (ex-info "HTML extraction identity mismatch" {})))
  (doseq [{:keys [acquisition-id manifest]} (:acquisitions artifact)]
    (when-not (and (= acquisition-id (html/digest manifest))
                   (= (:source-sha256 artifact) (:sha256 manifest)))
      (throw (ex-info "HTML acquisition identity mismatch" {}))))
  (let [ids (mapv #(second (re-matches #"https://www\.aidainternational\.org/StartList/([0-9]+)(?:\?day_index=[0-9]+|#start)?"
                                       (or (get-in % [:manifest :final-url]) ""))) (:acquisitions artifact))]
    (when (and (seq ids) (every? some? ids) (= 1 (count (set ids)))) (first ids))))

(defn html-values!
  "Replay the complete extraction before exposing typed, visible source facts.
  No title, row number, rank, start order, or inferred venue/round/session."
  [artifact ordinal]
  (let [event-id (registered-event artifact)
        payload (get (:candidates artifact) ordinal)
        context (evidence/bound-context! artifact payload ordinal (:source-sha256 artifact))
        doc (Jsoup/parse ^String (:raw-html artifact))
        table (nth (.select doc "table") (dec (get-in payload [:coordinates :table])) nil)
        row (when table (nth (vec (filter #(identical? table (.closest % "table")) (.select table "tr"))) (dec (get-in payload [:coordinates :row])) nil))
        fields (get-in payload [:raw :fields])
        date (selected doc "li.active .days")
        discipline (selected doc "select#discipline option[selected]")
        gender (selected doc "select#gender option[selected]")
        cells (when row (vec (filter #(= "td" (.tagName %)) (.children row))))
        name-index (.indexOf ^java.util.List (:headers context) "Diver")
        links (when (and cells (<= 0 name-index) (< name-index (count cells)))
                (.select (nth cells name-index) "a[href]"))
        profile (when (and (= 1 (count links)) (visible? (first links)))
                  (let [href (.attr (first links) "href")]
                    (when (re-matches #"https://www\.aidainternational\.org/Profile-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" href) href)))]
    (doseq [[selected raw] [[discipline (get fields "Discipline")]
                            [gender (get fields "Gender")]]]
      (let [normalize #(some-> % str/trim str/upper-case)
            canon #(get {"M" "MALE" "MEN" "MALE" "F" "FEMALE" "WOMEN" "FEMALE"} (normalize %) (normalize %))]
        (when (and selected raw (not= "ALL" (normalize selected)) (not= (canon selected) (canon raw)))
          (throw (ex-info "Conflicting row and selected source context" {})))))
    (when (seq (:context-errors context)) (throw (ex-info "Conflicting source acquisition context" {})))
    (when-not (and row (visible? row) (every? visible? (.select row "*")))
      (throw (ex-info "Hidden source row context" {})))
    (into {} (filter (fn [[_ v]] (and (string? v) (not (str/blank? v)))))
          {:event-id event-id
           :federation (when event-id "AIDA")
           :event-name (:event-name context)
           :date (when (= date (:event-date context)) date)
           :discipline (or (get fields "Discipline") (when (= discipline (:selected-discipline context)) discipline))
           :category (or (get fields "Gender") (when (= gender (:selected-gender context)) gender))
           :source-name (get fields "Diver")
           :source-athlete-id profile})))
