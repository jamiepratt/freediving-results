(ns freediving.source-pages-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [freediving.extraction-test :as fixture]
            [freediving.extraction :as extraction]
            [freediving.source-pages :as pages]))

(defn sample
  ([] (sample {}))
  ([extraction-config]
   (let [pdf (fixture/synthetic-pdf "BT /F1 8 Tf 20 750 Td (2025 CMAS World Championship Freediving Outdoor) Tj 0 -12 Td (CWT MEN SENIORS) Tj 0 -12 Td (1 SAMPLE Person AIN Men Senior 100 100) Tj ET")
         [root sha] (with-redefs [fixture/synthetic-pdf (constantly pdf)] (fixture/registered-pdf))
         receipt (extraction/extract! root sha {:actor "synthetic-test" :config extraction-config})
         artifact (edn/read-string (slurp (:artifact-path receipt)))]
     [{:archive-root root :cache-root (str root "-pages")}
      {:job-id (:job-id receipt) :artifact-sha256 (:artifact-sha256 receipt)
       :source-sha256 sha
       :candidate-id (.formatHex (java.util.HexFormat/of)
                                 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                          (.getBytes (pr-str [sha [(into (sorted-map) (select-keys (get-in artifact [:candidates 0 :coordinates]) [:page :line]))]]) "UTF-8")))
       :ordinal 0 :payload (first (:candidates artifact))}])))

(deftest registered-observation-page-is-readable-and-attested
  (let [[config row] (sample)
        result (pages/render! config row 1)]
    (is (= 1 (get-in result [:metadata :page-count])))
    (is (= (:source-sha256 row) (get-in result [:metadata :source-sha256])))
    (is (= 1800 (max (get-in result [:metadata :width]) (get-in result [:metadata :height]))))
    (is (= (seq (:bytes result)) (seq (:bytes (pages/render! config row 1)))))
    (is (pages/verify-corpus! (:archive-root config) [row]))))

(deftest foreign-observation-pages-and-corrupt-evidence-are-rejected
  (let [[config row] (sample)]
    (doseq [bad [(assoc row :candidate-id (apply str (repeat 64 "a"))) (assoc row :source-lines [{:page 1 :line 1 :text "forged"}]) (assoc row :ordinal 99) (assoc row :payload {}) (assoc row :job-id "../bad")
                 (assoc row :source-sha256 (apply str (repeat 64 "a")))]]
      (is (thrown? Exception (pages/render! config bad 1))))
    (doseq [page [0 2 "1" -1]] (is (thrown? Exception (pages/render! config row page))))
    (is (thrown? Exception (pages/render! (assoc config :cache-root (str (:archive-root config) "/../bad")) row 1)))
    (pages/render! config row 1)
    (let [cached (first (.listFiles (io/file (:cache-root config) "derived-objects")))]
      (spit cached "corrupt")
      (is (thrown? Exception (pages/render! config row 1))))
    (spit (str (:archive-root config) "/objects/" (:source-sha256 row)) "%PDF-corrupt")
    (is (thrown? Exception (pages/render! config row 1)))))

(deftest cache-space-is-bounded-before-render
  (let [[config row] (sample)
        root (java.nio.file.Paths/get (:cache-root config) (make-array String 0))]
    (java.nio.file.Files/createDirectory root (into-array java.nio.file.attribute.FileAttribute
                                                          [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))]))
    (with-open [f (java.io.RandomAccessFile. (str root "/large") "rw")] (.setLength f (* 97 1024 1024)))
    (is (thrown-with-msg? Exception #"Cache size" (pages/render! config row 1)))))

(deftest symlinks-and-caller-render-overrides-are-denied
  (let [[config row] (sample)
        cache (java.nio.file.Paths/get (:cache-root config) (make-array String 0))]
    (java.nio.file.Files/createSymbolicLink cache (java.nio.file.Paths/get (:archive-root config) (make-array String 0))
                                            (make-array java.nio.file.attribute.FileAttribute 0))
    (is (thrown? Exception (pages/verify-config! config)))
    (java.nio.file.Files/delete cache)
    (is (thrown? Exception (pages/render! (assoc config :tool "curl") row 1)))
    (let [metadata (:metadata (pages/render! config row 1))]
      (is (= :single-fallback-font-v1 (get-in metadata [:font-profile :profile])))
      (is (re-matches #"[a-f0-9]{64}" (get-in metadata [:font-profile :font-sha256])))
      (is (re-matches #"[a-f0-9]{64}" (get-in metadata [:executables :pdftoppm :sha256])))
      (is (= :extracted-text-lines (:coordinate-system metadata)))
      (is (seq (:source-lines metadata))))))

(defn- fixture-tool! [config text]
  (let [file (io/file (.getParentFile (io/file (:archive-root config))) "synthetic-pdf-tool")]
    (spit file text)
    (java.nio.file.Files/setPosixFilePermissions (.toPath file)
                                                 (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))
    (str file)))

(deftest replacement-tool-with-same-version-invalidates-private-cache
  ;; Replace only the executable lookup boundary. Real Poppler still renders both
  ;; images; neither system tools nor source PDFs are modified.
  (let [[config row] (sample)
        tool-var (ns-resolve 'freediving.source-pages 'binary!)
        original @tool-var
        script (str "#!/usr/bin/python3\nimport os,sys\nos.execv(" (pr-str (original "pdftoppm"))
                    ",[" (pr-str (original "pdftoppm")) "]+sys.argv[1:])\n")
        tool (fixture-tool! config script)]
    (with-redefs-fn {tool-var #(if (= % "pdftoppm") tool (original %))}
      (fn []
        (let [before (pages/render! config row 1)]
          (fixture-tool! config (str script "# replacement executable, unchanged tool version\n"))
          (let [after (pages/render! config row 1)]
            (is (= (get-in before [:metadata :tool :version]) (get-in after [:metadata :tool :version])))
            (is (not= (get-in before [:metadata :render-id]) (get-in after [:metadata :render-id])))
            (is (not= (get-in before [:metadata :executables :pdftoppm :sha256])
                      (get-in after [:metadata :executables :pdftoppm :sha256])))
            (is (= 2 (count (.listFiles (io/file (:cache-root config) "derivations")))))))))))

(deftest stalled-tool-times-out-and-concurrent-request-is-refused
  (let [[config row] (sample)
        tool-var (ns-resolve 'freediving.source-pages 'binary!) original @tool-var
        tool (fixture-tool! config "#!/usr/bin/python3\nimport time\ntime.sleep(60)\n")
        entered (promise)]
    (with-redefs-fn {tool-var (fn [name] (deliver entered true) (if (= name "pdftoppm") tool (original name)))}
      (fn []
        (let [started (System/nanoTime)
              stalled (future (try (pages/render! config row 1) :unexpected-success
                                   (catch Exception e (.getMessage e))))]
          (is (= true (deref entered 5000 :never-started)))
          (is (thrown-with-msg? Exception #"renderer busy" (pages/render! config row 1)))
          (is (= "PDF render timeout" (deref stalled 20000 :did-not-time-out)))
          (is (< (/ (- (System/nanoTime) started) 1e9) 20.0)))))
    ;; The timeout must release the sole render slot and remove temporary files.
    (is (= 1 (get-in (pages/render! config row 1) [:metadata :page])))
    (is (empty? (filter #(.startsWith (.getName %) "render-") (.listFiles (io/file (:cache-root config))))))))
