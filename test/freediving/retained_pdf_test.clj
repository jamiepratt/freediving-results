(ns freediving.retained-pdf-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.archive :as archive]
            [freediving.retained-pdf :as retained-pdf])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- sha256 [^bytes bytes]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn- pdf-bytes []
  (let [stream "BT /F1 12 Tf 72 720 Td (Synthetic result row) Tj ET\n"
        objects ["<< /Type /Catalog /Pages 2 0 R >>"
                 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
                 (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
                      " /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>")
                 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
                 (str "<< /Length " (count stream) " >>\nstream\n" stream "endstream")]
        header "%PDF-1.4\n"
        {:keys [body offsets]}
        (reduce (fn [{:keys [body offsets]} [n object]]
                  {:body (str body n " 0 obj\n" object "\nendobj\n")
                   :offsets (conj offsets (count body))})
                {:body header :offsets []}
                (map-indexed (fn [index object] [(inc index) object]) objects))
        xref (count body)
        table (apply str (map #(format "%010d 00000 n \n" %) offsets))]
    (.getBytes (str body "xref\n0 6\n0000000000 65535 f \n" table
                    "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" xref
                    "\n%%EOF\n") "US-ASCII")))

(defn- fixture []
  (let [dir (.toRealPath (Files/createTempDirectory "retained-pdf-test-" (make-array FileAttribute 0))
                         (make-array java.nio.file.LinkOption 0))
        path (.resolve dir "source.pdf")
        bytes (pdf-bytes)
        hash (sha256 bytes)
        root (str (.resolve dir "archive"))]
    (Files/write path bytes (make-array java.nio.file.OpenOption 0))
    (archive/register! root (str path)
                       {:sha256 hash :discovery-url "https://example.org/results"
                        :final-url "https://example.org/results.pdf"
                        :acquisition-method "direct HTTP download"
                        :retrieved-at "2026-09-23T13:14:09Z"
                        :content-type "application/pdf" :publisher "Example federation"
                        :relationship :publisher :mirror-of nil})
    {:root root :path path :hash hash}))

(defn- failure-reason [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))

(deftest archived-pdf-produces-byte-verified-layout-pages
  (let [{:keys [root hash]} (fixture)
        retained (retained-pdf/verified-input root {:source-sha256 hash :format :pdf})]
    (is (= hash (:source-sha256 retained)))
    (is (true? (:trusted-extraction? retained)))
    (is (= 1 (count (:pages retained))))
    (is (re-find #"Synthetic result row" (first (:pages retained))))
    (is (= "pdftotext" (get-in retained [:pdftotext :name])))
    (is (re-find #"^pdftotext version " (get-in retained [:pdftotext :version])))
    (is (= ["-layout" "-enc" "UTF-8"]
           (get-in retained [:pdftotext :arguments])))
    (is (= [(sha256 (.getBytes (first (:pages retained)) "UTF-8"))]
           (get-in retained [:pdftotext :page-sha256s])))))

(deftest missing-mismatched-and-non-pdf-objects-fail-closed
  (let [{:keys [root hash]} (fixture)
        document {:source-sha256 hash :format :pdf}
        object (java.io.File. root (str "objects/" hash))]
    (is (= :missing-source
           (failure-reason #(retained-pdf/verified-input root
                                                         (assoc document :source-sha256
                                                                (apply str (repeat 64 "0")))))))
    (spit object "tampered")
    (is (= :source-hash-mismatch
           (failure-reason #(retained-pdf/verified-input root document)))))
  (let [{:keys [root hash]} (fixture)
        bogus (str root "/objects/" hash)]
    (Files/delete (.toPath (java.io.File. bogus)))
    (is (= :missing-source
           (failure-reason #(retained-pdf/verified-input root
                                                         {:source-sha256 hash :format :pdf})))))
  (let [dir (.toRealPath (Files/createTempDirectory "retained-nonpdf-test-" (make-array FileAttribute 0))
                         (make-array java.nio.file.LinkOption 0))
        path (.resolve dir "source.pdf")
        bytes (.getBytes "not a PDF" "UTF-8")
        hash (sha256 bytes)
        root (str (.resolve dir "archive"))]
    (Files/write path bytes (make-array java.nio.file.OpenOption 0))
    (archive/register! root (str path)
                       {:sha256 hash :discovery-url "https://example.org/results"
                        :final-url "https://example.org/results.pdf"
                        :acquisition-method "direct HTTP download"
                        :retrieved-at "2026-09-23T13:14:09Z"
                        :content-type "application/pdf" :publisher "Example federation"
                        :relationship :publisher :mirror-of nil})
    (is (= :not-pdf
           (failure-reason #(retained-pdf/verified-input root
                                                         {:source-sha256 hash :format :pdf}))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.retained-pdf-test)]
    (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0))))
