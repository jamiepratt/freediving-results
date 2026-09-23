(ns freediving.packets-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [freediving.packets :as packets]))

(deftest readable-packets-preserve-and-escape-evidence
  (let [html (packets/render-html {:packet-version 1 :total 1 :has-more? false
                                   :packets [{:packet-id "one" :source-name "<script>alert('x')</script>"
                                              :uncertainties ["A & B"]}]})]
    (is (str/includes? html "&lt;script&gt;"))
    (is (not (str/includes? html "<script>")))
    (is (str/includes? html "A &amp; B"))
    (is (str/includes? html "Candidate only"))
    (is (str/includes? html "Awaiting owner review"))))

(defn -main [& _]
  (let [r (run-tests 'freediving.packets-test)]
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))

(deftest private-export-is-repeatable-without-overwrites
  (let [parent (.toRealPath (java.nio.file.Files/createTempDirectory "packet-test" (make-array java.nio.file.attribute.FileAttribute 0)) (make-array java.nio.file.LinkOption 0))
        dest (str parent "/private")
        data {:packet-version 1 :packets [] :total 0 :has-more? false}]
    (packets/export! data dest)
    (is (= data (edn/read-string (slurp (str dest "/packets.edn")))))
    (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString
                        (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. (str dest "/packets.html"))) (make-array java.nio.file.LinkOption 0)))))
    (is (= "rwx------" (java.nio.file.attribute.PosixFilePermissions/toString
                        (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. dest)) (make-array java.nio.file.LinkOption 0)))))
    (is (map? (packets/export! data dest)))
    (is (thrown? Exception (packets/export! (assoc data :total 1) dest)))
    (let [link (.resolve parent "link")]
      (java.nio.file.Files/createSymbolicLink link (.toPath (java.io.File. dest)) (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown? Exception (packets/export! data (str link "/nested")))))))

(deftest request-is-one-bounded-edn-map
  (let [file (java.io.File/createTempFile "packet-request" ".edn")]
    (doseq [invalid ["" "{} {}" "[]" "{:limit 1001}" "{:offset -1}" "{:extra true}" "{:max-observations 100001}"]]
      (spit file invalid)
      (is (thrown? Exception (packets/read-request file))))
    (spit file "{:offset 0 :limit 50}")
    (is (= {:offset 0 :limit 50} (packets/read-request file)))))

(deftest readable-case-has-source-name-signals-and-counts
  (let [html (packets/render-html {:packets [{:target {:payload {:parsed {:source-name "Jane <Doe>"}}}
                                              :target-observations [{} {}] :outcome :ambiguous
                                              :candidates [{:observations [{:payload {:parsed {:source-name "DOE Jane"}}}]
                                                            :signals [:token-order]}]}]})]
    (doseq [text ["Jane &lt;Doe&gt;" "DOE Jane" "ambiguous" "token-order" "Grouped target listings: 2" "<details>"]]
      (is (str/includes? html text)))))
