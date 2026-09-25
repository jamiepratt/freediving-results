(ns freediving.synthetic-pdf
  "Minimal PDF source for the explicitly synthetic local demos only."
  (:require [clojure.string :as str]))

(defn document
  "Render short WinAnsi demo lines as one page. Octal strings keep PDF offsets
   byte-exact and preserve accented sample names and literal punctuation."
  [lines]
  (let [literal (fn [s]
                  (let [bytes (.getBytes ^String s "windows-1252")]
                    (when-not (= s (String. bytes "windows-1252"))
                      (throw (ex-info "Synthetic PDF requires WinAnsi text" {})))
                    (str "(" (apply str (map #(format "\\%03o" (bit-and 255 %)) bytes)) ")")))
        stream (str "BT /F1 12 Tf 40 750 Td "
                    (str/join " 0 -18 Td " (map #(str (literal %) " Tj") lines)) " ET")
        objects ["<< /Type /Catalog /Pages 2 0 R >>"
                 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
                 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
                 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
                 (str "<< /Length " (count stream) " >>\nstream\n" stream "\nendstream")]
        pieces (map-indexed #(str (inc %1) " 0 obj\n" %2 "\nendobj\n") objects)
        prefix "%PDF-1.4\n"
        offsets (butlast (reductions + (count prefix) (map count pieces)))
        body (str prefix (apply str pieces))]
    (str body "xref\n0 6\n0000000000 65535 f \n"
         (apply str (map #(format "%010d 00000 n \n" %) offsets))
         "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" (count body) "\n%%EOF\n")))
