(ns freediving.html-evidence
  "Independent retained HTML replay for private review and sanitized citations."
  (:require [clojure.string :as str]
            [freediving.aida-html :as html])
  (:import [org.jsoup Jsoup]
           [java.security MessageDigest]
           [java.util HexFormat]))
(defn sha256 [bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") ^bytes bytes)))
(defn html? [artifact] (= 4 (:schema-version artifact)))
(defn coordinates [artifact payload]
  (select-keys (:coordinates payload) (if (html? artifact) [:table :row] [:page :line])))
(defn- explicitly-hidden? [element]
  (some (fn [e]
          (or (#{"template" "script" "noscript"} (.tagName e))
              (.hasAttr e "hidden")
              (= "true" (str/lower-case (str/trim (.attr e "aria-hidden"))))
              (re-find #"(?i)(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden)\s*(?:!important\s*)?(?:;|$)" (.attr e "style"))))
        (cons element (.parents element))))
(defn context
  "Event name comes only from a unique retained heading/branding without explicit hiding, never title."
  [artifact]
  (let [doc (Jsoup/parse ^String (:raw-html artifact))
        branding (for [e (.select doc ".site-header__branding img[alt], .site-header__branding2 img[alt]")
                       :let [v (str/trim (.attr e "alt"))] :when (and (not (str/blank? v)) (not (explicitly-hidden? e)))]
                   {:selector (if (.closest e ".site-header__branding") ".site-header__branding img[alt]" ".site-header__branding2 img[alt]") :value v})
        headings (for [e (.select doc "h1") :let [v (str/trim (.wholeText e))] :when (and (not (str/blank? v)) (not (explicitly-hidden? e)))]
                   {:selector "h1" :value v})
        names (vec (concat branding headings))
        heading (when (= 1 (count names)) (first names))
        acquisitions (mapv (fn [a] (merge {:acquisition-id (:acquisition-id a)}
                                          (select-keys (get-in a [:manifest :provenance :browser-state])
                                                       [:selected-date :filters :representation :rendered-sha256]))) (:acquisitions artifact))
        selected (:context artifact)
        normalize #(when (string? %) (str/lower-case (str/trim %)))
        discipline ({"all" :all "sta" :sta "dyn" :dyn "dynb" :dynb "dnf" :dnf "cwt" :cwt "cwtb" :cwtb "cnf" :cnf "fim" :fim} (normalize (:selected-discipline selected)))
        gender ({"all" :all "male" :men "men" :men "female" :women "women" :women} (normalize (:selected-gender selected)))
        errors (vec (distinct (mapcat (fn [a]
                                        (concat (when (and (:selected-date a) (:event-date selected) (not= (:selected-date a) (:event-date selected))) [:acquisition-date-conflict])
                                                (when (and discipline (get-in a [:filters :discipline]) (not= discipline (get-in a [:filters :discipline]))) [:acquisition-discipline-conflict])
                                                (when (and gender (get-in a [:filters :gender]) (not= gender (get-in a [:filters :gender]))) [:acquisition-gender-conflict]))) acquisitions)))]
    (assoc (select-keys (:context artifact) [:event-date :selected-discipline :selected-gender])
           :event-name (:value heading) :event-name-evidence heading
           :acquisition-context acquisitions :context-errors errors)))
(defn bound-context!
  "Replay source hash/parser and bind the precise stored candidate ordinal. No authority is conferred."
  [artifact payload ordinal source-sha256]
  (when-not (and (html? artifact) (= html/parser-version (:parser-version artifact))
                 (string? (:raw-html artifact)) (= source-sha256 (:source-sha256 artifact))
                 (= source-sha256 (sha256 (.getBytes ^String (:raw-html artifact) "UTF-8"))))
    (throw (ex-info "HTML source provenance mismatch" {})))
  (let [replayed (html/parse-html (:raw-html artifact))]
    (when-not (and (= replayed (select-keys artifact (keys replayed)))
                   (nat-int? ordinal) (< ordinal (count (:candidates replayed)))
                   (= payload (nth (:candidates replayed) ordinal)))
      (throw (ex-info "HTML retained source replay or observation mismatch" {})))
    (merge (context artifact)
           {:coordinates (coordinates artifact payload)
            :headers (:headers (first (filter #(= (:table %) (get-in payload [:coordinates :table])) (:tables artifact))))
            :raw-row (get-in payload [:raw :html]) :cells (get-in payload [:raw :cells])})))
