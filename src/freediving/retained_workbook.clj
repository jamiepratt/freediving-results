(ns freediving.retained-workbook
  "Source-bound routing for the retained GIA 2025 individual workbook.
   Census rows are source positions, not distinct sporting attempts."
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.security MessageDigest]
           [java.util HexFormat]
           [java.util.zip ZipInputStream]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Node NodeList]))

(def retained-source-sha256
  "352ebb0c4119f35cb254d1a4b999e89ee86ed09c5ca3c81be7d60c33102f187b")
(def parser-version "gia-2025-individual-workbook-census/1")
(def source-url
  "https://apnea.academy/site/assets/files/11984/classifica_generale_individuale_gia_2025.xlsx")
(def ^:private sheet-paths
  {"Classifica Generale " "xl/worksheets/sheet1.xml"
   "NAPOLI 2025" "xl/worksheets/sheet2.xml"
   "PAVIA 2025" "xl/worksheets/sheet3.xml"
   "ROMA 2025" "xl/worksheets/sheet4.xml"
   "FIRENZE 2025" "xl/worksheets/sheet5.xml"})
(def ^:private expected-kinds
  {"Classifica Generale " {"standings" 366 "formula_placeholder" 9}
   "NAPOLI 2025" {"combined_score" 96}
   "PAVIA 2025" {"distance_result" 143}
   "ROMA 2025" {"discipline_result" 140}
   "FIRENZE 2025" {"dynamic_result" 65 "static_result" 7 "secondary_score" 72}})
(def ^:private evidence-role
  {"standings" :aggregate
   "formula_placeholder" :placeholder
   "combined_score" :aggregate
   "secondary_score" :aggregate
   "distance_result" :individual-result
   "discipline_result" :individual-result
   "dynamic_result" :individual-result
   "static_result" :individual-result})

(defn- field [value key]
  (or (get value key) (get value (name key))))

(defn- sha256 [^bytes bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn- zip-entries [^bytes bytes]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
    (loop [entries {}]
      (if-let [entry (.getNextEntry zip)]
        (let [out (ByteArrayOutputStream.)]
          (.transferTo zip out)
          (recur (assoc entries (.getName entry) (.toByteArray out))))
        entries))))

(defn- xml-root [^bytes bytes]
  (let [factory (DocumentBuilderFactory/newInstance)]
    (.setFeature factory "http://apache.org/xml/features/disallow-doctype-decl" true)
    (.setFeature factory "http://xml.org/sax/features/external-general-entities" false)
    (.setFeature factory "http://xml.org/sax/features/external-parameter-entities" false)
    (.setNamespaceAware factory true)
    (.getDocumentElement (.parse (.newDocumentBuilder factory) (ByteArrayInputStream. bytes)))))

(defn- children [node local-name]
  (when node
    (let [^NodeList nodes (.getChildNodes ^Node node)]
      (for [index (range (.getLength nodes))
            :let [child (.item nodes index)]
            :when (and (= Node/ELEMENT_NODE (.getNodeType child))
                       (= local-name (or (.getLocalName child) (.getNodeName child))))]
        child))))

(defn- child [node local-name]
  (first (children node local-name)))

(defn- text-content [node]
  (when node (.getTextContent node)))

(defn- source-cell [cell shared-strings]
  (let [kind (let [type (.getAttribute cell "t")] (if (str/blank? type) "n" type))
        raw (text-content (child cell "v"))
        formula (when-let [node (child cell "f")] (str "=" (text-content node)))
        value (case kind
                "s" (get shared-strings (Integer/parseInt raw))
                "inlineStr" (text-content (child cell "is"))
                "str" raw
                "e" raw
                "b" (= raw "1")
                (when (and raw (not (str/blank? raw))) (bigdec raw)))]
    {:value value :formula formula :cell_type kind
     :cached_value (when formula value)}))

(defn- source-cells [entries path shared-strings]
  (when-let [bytes (get entries path)]
    (let [root (xml-root bytes)]
      (into {}
            (for [row (children (child root "sheetData") "row")
                  cell (children row "c")]
              [(.getAttribute cell "r") (source-cell cell shared-strings)])))))

(defn- same-value? [expected actual]
  (if (and (number? expected) (number? actual))
    (== expected actual)
    (= expected actual)))

(defn- cell-matches? [sheet address packet-cell source-cell]
  (and (= (str sheet "!" address) (field packet-cell :citation))
       (every? (fn [key]
                 (same-value? (field packet-cell key) (get source-cell key)))
               [:value :formula :cell_type :cached_value])))

(defn- has-value? [source address]
  (some? (get-in source [address :value])))

(defn- kind-matches? [source sheet row]
  (let [n (field row :row)
        kind (field row :kind)]
    (case sheet
      "Classifica Generale "
      (case kind
        "standings" (and (>= n 6) (has-value? source (str "D" n)))
        "formula_placeholder" (and (>= n 6)
                                   (not (has-value? source (str "D" n)))
                                   (or (has-value? source (str "B" n))
                                       (contains? source (str "P" n))))
        false)
      "NAPOLI 2025" (and (= kind "combined_score") (>= n 3)
                         (has-value? source (str "D" n)))
      "PAVIA 2025" (and (= kind "distance_result") (>= n 2)
                        (has-value? source (str "C" n)))
      "ROMA 2025" (and (= kind "discipline_result") (>= n 2)
                       (has-value? source (str "B" n)))
      "FIRENZE 2025" (and (>= n 2)
                          (case kind
                            "dynamic_result" (has-value? source (str "F" n))
                            "static_result" (has-value? source (str "K" n))
                            "secondary_score" (has-value? source (str "O" n))
                            false))
      false)))

(defn packet-cells-match?
  "Compare every cited packet cell with the original XLSX XML. Returns false on
   malformed ZIP/XML, absent cells, changed values, formulas or cell types."
  [^bytes bytes sheets paths]
  (try
    (let [entries (zip-entries bytes)
          shared-root (some-> (get entries "xl/sharedStrings.xml") xml-root)
          shared-strings (mapv (fn [item]
                                 (apply str (map text-content (children item "t"))))
                               (children shared-root "si"))]
      (and (bytes? bytes)
           (= (set (keys paths)) (set (map #(field % :name) sheets)))
           (every? (fn [sheet]
                     (let [name (field sheet :name)
                           source (source-cells entries (get paths name) shared-strings)]
                       (and source
                            (every? (fn [row]
                                      (and (= name (field row :sheet))
                                           (integer? (field row :row))
                                           (or (not= paths sheet-paths)
                                               (kind-matches? source name row))
                                           (seq (field row :cells))
                                           (every? (fn [[address cell]]
                                                     (and (re-matches #"[A-Z]+[1-9][0-9]*" address)
                                                          (= (field row :row)
                                                             (Long/parseLong (re-find #"[0-9]+$" address)))
                                                          (contains? source address)
                                                          (cell-matches? name address cell (get source address))))
                                                   (field row :cells))))
                                    (field sheet :rows)))))
                   sheets)))
    (catch Exception _ false)))

(defn- position [hash row]
  (let [sheet (field row :sheet)
        n (field row :row)
        kind (field row :kind)
        id (str "sheet=" sheet "&row=" n "&kind=" kind)]
    {:id id :citation (str "sha256:" hash "#" id)
     :coordinates {:sheet sheet :row n :kind kind
                   :evidence-role (get evidence-role kind)
                   :cell-citations (->> (field row :cells) vals
                                        (map #(field % :citation)) sort vec)}}))

(defn positions-for-census
  "Exact router inventory derived from a census packet. Caller still must run
   claims-for-document to verify original source bytes and every cited cell."
  [hash packet]
  (mapv #(position hash %)
        (mapcat (fn [sheet] (field sheet :rows)) (field packet :sheets))))

(defn- exact-inventory? [document candidates]
  (= (set (map #(select-keys % [:id :citation :coordinates]) candidates))
     (set (map #(select-keys % [:id :citation :coordinates]) (:positions document)))))

(defn- valid-census? [packet]
  (let [sheets (field packet :sheets)
        rows (mapcat #(field % :rows) sheets)]
    (and (= "gia-2025-individual-workbook-census/v1" (field packet :schema))
         (= (set (keys sheet-paths)) (set (map #(field % :name) sheets)))
         (= expected-kinds
            (into {} (map (fn [sheet]
                            [(field sheet :name)
                             (frequencies (map #(field % :kind) (field sheet :rows)))]) sheets)))
         (= 898 (count rows))
         (= 5175 (reduce + (map #(count (field % :cells)) rows)))
         (= (count rows)
            (count (set (map (juxt #(field % :sheet) #(field % :row)
                                   #(field % :kind)) rows)))))))

(defn claims-for-document
  "Bind a retained census packet to original XLSX bytes, acquisition receipt and
   exact source positions. Only the known individual GIA workbook is supported.
   :census-packet must be parsed JSON/EDN, not an asserted hash alone."
  [document retained-input]
  (let [bytes (:bytes retained-input)
        receipt (:receipt retained-input)
        packet (:census-packet retained-input)
        hash (when (bytes? bytes) (sha256 bytes))
        source (field packet :source)
        sheets (field packet :sheets)
        verified? (and (= :workbook (:format document))
                       (= retained-source-sha256 hash (:source-sha256 document))
                       (= source-url (field receipt :final_url))
                       (= 200 (or (field receipt :http_status) (field receipt :status)))
                       (= (alength bytes) (field receipt :bytes))
                       (= hash (field receipt :sha256))
                       (= hash (field source :sha256))
                       (= (alength bytes) (field source :bytes)))
        census? (and verified? (valid-census? packet)
                     (packet-cells-match? bytes sheets sheet-paths))
        rows (when census? (mapcat #(field % :rows) sheets))
        candidates (when rows (positions-for-census hash packet))
        inventory? (and candidates (exact-inventory? document candidates))]
    (if inventory?
      (let [ids (set (map :id candidates))]
        {:claims [{:parser-id "gia-2025-individual-workbook"
                   :parser-version parser-version
                   :match-reason "Original GIA individual XLSX, receipt, census and exact cited cells"
                   :source-restriction {:sha256s #{hash} :formats #{:workbook}}
                   :supported-positions ids :claimed-positions ids}]
         :unsupported-reasons [] :unsupported-formats []
         :source-verification :raw-bytes-receipt-and-cells
         :extraction {:status :needs-review :source-sha256 hash
                      :source-url source-url :candidates candidates
                      :confirmed-distinct-attempts nil}})
      {:claims []
       :unsupported-reasons [(cond (not verified?) :source-or-receipt-mismatch
                                   (not census?) :workbook-census-or-cell-mismatch
                                   :else :workbook-position-inventory-mismatch)]
       :unsupported-formats [] :source-verification :failed :extraction nil})))
