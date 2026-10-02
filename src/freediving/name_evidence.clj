(ns freediving.name-evidence
  "Cited publisher name assertions. Pure replay creates no sporting attempts."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.text Normalizer Normalizer$Form]
           [java.util HexFormat]))

(def ^:private evidence-fields
  [:source-sha256 :parser-version :source-position :publisher :source-family
   :original-name :publisher-romanization :person-id :ranking])

(defn- present? [v]
  (and (string? v) (not (str/blank? v))))

(defn- require-field [field value]
  (when-not (present? value)
    (throw (ex-info "Missing cited name evidence field" {:field field}))))

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- digest [value]
  (let [bytes (.getBytes (pr-str (canonical value)) "UTF-8")]
    (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- comparison-name [name]
  (when (present? name)
    (-> (Normalizer/normalize name Normalizer$Form/NFKC)
        str/trim
        str/lower-case
        (str/replace #"\s+" " "))))

(defn name-assertion
  "Construct immutable cited evidence. :derived is comparison aid, never source spelling.
   A publisher-stated romanization is retained only when supplied by the source."
  [source]
  (let [evidence (select-keys source evidence-fields)
        {:keys [source-sha256 parser-version source-position publisher
                original-name publisher-romanization person-id]} evidence]
    (when-not (and (string? source-sha256) (re-matches #"(?i)[0-9a-f]{64}" source-sha256))
      (throw (ex-info "Expected a source SHA-256" {:field :source-sha256})))
    (doseq [[field value] [[:parser-version parser-version] [:publisher publisher]]]
      (require-field field value))
    (when-not (and (map? source-position) (seq source-position))
      (throw (ex-info "Missing exact source position" {:field :source-position})))
    (when-not (or (present? original-name) (present? publisher-romanization))
      (throw (ex-info "Missing publisher name spelling" {:field :original-name})))
    (when person-id
      (doseq [field [:authority :kind :value]]
        (when-not (if (= field :kind)
                    (or (present? (get person-id field)) (keyword? (get person-id field)))
                    (present? (get person-id field)))
          (throw (ex-info "Unscoped publisher person ID" {:field field})))))
    (assoc evidence
           :evidence-key (digest evidence)
           :derived {:comparison-name (comparison-name (or publisher-romanization original-name))})))

(defn import-name-evidence
  "Deterministically replay name-only rows into a state map. Existing attempts are untouched.
   Source bytes and parser versions are part of each key, so revised evidence coexists."
  [state sources]
  (let [existing (or (:name-assertions state) [])
        assertions (concat existing (map name-assertion sources))
        by-key (reduce (fn [acc assertion]
                         (let [key (:evidence-key assertion)]
                           (when (and (contains? acc key) (not= (get acc key) assertion))
                             (throw (ex-info "Conflicting name evidence key" {:evidence-key key})))
                           (assoc acc key assertion)))
                       (sorted-map) assertions)]
    (assoc state :name-assertions (vec (vals by-key)))))
