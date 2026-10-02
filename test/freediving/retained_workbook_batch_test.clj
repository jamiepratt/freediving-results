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

(defn- sha256 [bytes]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))

(defn- input-paths []
  (mapv System/getenv ["RETAINED_GIA_WORKBOOK"
                       "RETAINED_GIA_RECEIPT_MANIFEST"
                       "RETAINED_GIA_CENSUS_PACKET"]))

(defn- private-input []
  (let [[source-path receipt-path packet-path] (input-paths)]
    (when (some some? [source-path receipt-path packet-path])
      (when-not (every? #(and % (Files/isRegularFile (Paths/get % (make-array String 0))
                                                     (make-array java.nio.file.LinkOption 0)))
                        [source-path receipt-path packet-path])
        (throw (ex-info "All three retained GIA inputs are required" {})))
      (let [bytes (Files/readAllBytes (Paths/get source-path (make-array String 0)))
            receipt-bytes (Files/readAllBytes (Paths/get receipt-path (make-array String 0)))
            packet-bytes (Files/readAllBytes (Paths/get packet-path (make-array String 0)))
            _ (when-not (and (= workbook/retained-source-sha256 (sha256 bytes))
                             (= receipt-manifest-sha256 (sha256 receipt-bytes))
                             (= original-packet-sha256 (sha256 packet-bytes)))
                (throw (ex-info "Original workbook, receipt or census digest mismatch" {})))
            receipts (json/read-str (String. receipt-bytes "UTF-8"))
            packet (json/read-str (String. packet-bytes "UTF-8"))
            receipt (first (filter #(= workbook/retained-source-sha256 (get % "sha256"))
                                   (get receipts "sources")))]
        (when-not receipt
          (throw (ex-info "Retained GIA acquisition receipt is missing" {})))
        {:bytes bytes :receipt receipt :census-packet packet}))))

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
    (is true "Set all three RETAINED_GIA_* paths for private corpus validation")))

(defn -main [& _]
  (let [result (run-tests 'freediving.retained-workbook-batch-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
