(ns freediving.retained-workbook-batch-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.parser-batch :as batch]
            [freediving.retained-workbook :as workbook])
  (:import [java.nio.file Files Paths]))

(def ^:private original-packet-sha256
  "c2e41f2ccea411d9ea77c4557202d9cb3c0882c7f885b5ccba813616ed89e83d")
(def ^:private receipt-manifest-sha256
  "b1adcd2443fbe20b96eff77019a59a3e96ecd74efa0d4ca34fe0bc688eb96ec1")
(def ^:private replacement-packet-sha256
  "c5735f36ed2b3f73ef9e28572670cfa8fcbd4126241cb9c6737d34079186253a")
(def ^:private replacement-provenance-sha256
  "d1f7cec0840fffdd0bf0b67c6fda4ff1dba6666622a44c5df70b5111dc71d76e")

(defn- sha256 [bytes]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))

(defn- input-paths []
  (mapv System/getenv ["RETAINED_GIA_WORKBOOK"
                       "RETAINED_GIA_RECEIPT_MANIFEST"
                       "RETAINED_GIA_CENSUS_PACKET"
                       "RETAINED_GIA_CENSUS_PROVENANCE"]))

(defn- private-input []
  (let [[source-path receipt-path packet-path provenance-path] (input-paths)
        mode (if provenance-path :replacement :original)]
    (when (some some? [source-path receipt-path packet-path provenance-path])
      (when-not (every? #(and % (Files/isRegularFile (Paths/get % (make-array String 0))
                                                     (make-array java.nio.file.LinkOption 0)))
                        (cond-> [source-path receipt-path packet-path]
                          provenance-path (conj provenance-path)))
        (throw (ex-info "Retained GIA inputs are missing" {:mode mode})))
      (let [bytes (Files/readAllBytes (Paths/get source-path (make-array String 0)))
            receipt-bytes (Files/readAllBytes (Paths/get receipt-path (make-array String 0)))
            packet-bytes (Files/readAllBytes (Paths/get packet-path (make-array String 0)))
            packet-sha256 (sha256 packet-bytes)
            provenance-bytes (when provenance-path
                               (Files/readAllBytes (Paths/get provenance-path (make-array String 0))))
            provenance (when provenance-bytes (json/read-str (String. provenance-bytes "UTF-8")))
            _ (when-not (and (= workbook/retained-source-sha256 (sha256 bytes))
                             (= receipt-manifest-sha256 (sha256 receipt-bytes))
                             (= (if provenance-path replacement-packet-sha256 original-packet-sha256)
                                packet-sha256))
                (throw (ex-info "Retained GIA workbook, receipt or census digest mismatch" {:mode mode})))
            receipts (json/read-str (String. receipt-bytes "UTF-8"))
            packet (json/read-str (String. packet-bytes "UTF-8"))
            receipt (first (filter #(= workbook/retained-source-sha256 (get % "sha256"))
                                   (get receipts "sources")))]
        (when provenance-path
          (when-not (and (= replacement-provenance-sha256 (sha256 provenance-bytes))
                         (= "gia-2025-workbook-census-replacement/v1" (get provenance "schema"))
                         (= false (get provenance "original_packet_recovered"))
                         (= original-packet-sha256 (get provenance "original_packet_sha256"))
                         (= replacement-packet-sha256 (get provenance "replacement_packet_sha256"))
                         (= workbook/retained-source-sha256 (get provenance "source_workbook_sha256"))
                         (= (alength bytes) (get provenance "source_workbook_bytes"))
                         (= receipt-manifest-sha256 (get provenance "acquisition_manifest_sha256"))
                         (= 898 (get provenance "position_count"))
                         (= 5175 (get provenance "cited_cell_count")))
            (throw (ex-info "Retained GIA replacement provenance mismatch" {}))))
        (when-not receipt
          (throw (ex-info "Retained GIA acquisition receipt is missing" {})))
        {:bytes bytes :receipt receipt :census-packet packet :packet-kind mode}))))

(deftest retained-gia-workbook-replays-all-cited-positions-by-role
  (if-let [input (private-input)]
    (let [packet (:census-packet input)
          rows (mapcat #(get % "rows") (get packet "sheets"))
          hash workbook/retained-source-sha256
          document {:format :workbook :source-sha256 hash
                    :positions (workbook/positions-for-census hash packet)}
          entry {:document document :retained-input input}
          replay (batch/replay-registered-batch [entry entry])
          routed (get-in replay [:documents 0 :routed])]
      (is (= (if (System/getenv "RETAINED_GIA_CENSUS_PROVENANCE") :replacement :original)
             (:packet-kind input)))
      (is (= "gia-2025-individual-workbook-census/v1" (get packet "schema")))
      (is (= 898 (count rows)))
      (is (= 5175 (reduce + (map #(count (get % "cells")) rows))))
      (is (= {:individual-result 355 :aggregate 534 :placeholder 9}
             (frequencies (map #(get-in % [:coordinates :evidence-role]) routed))))
      (is (= {:known-positions 898 :routed 898 :gaps 0 :unexamined-sections 0}
             (get-in replay [:metrics :coverage])))
      (is (= {:requests 0 :cache-reuses 0 :retained-source-reuses 1
              :duplicate-inputs 1 :exceptions 0 :rejected-claims 0}
             (dissoc (:metrics replay) :coverage)))
      (is (empty? (:exception-queue replay)))
      (is (= :raw-bytes-receipt-and-cells
             (get-in replay [:documents 0 :verifications 0 :source-verification])))
      (is (= :needs-review (get-in replay [:documents 0 :extraction-status]))))
    (is true "Set three RETAINED_GIA_* paths, plus provenance for replacement validation")))

(defn -main [& _]
  (let [result (run-tests 'freediving.retained-workbook-batch-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
