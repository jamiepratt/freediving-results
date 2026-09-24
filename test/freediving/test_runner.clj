(ns freediving.test-runner
  (:require [clojure.test :as t]
            [freediving.archive-test]
            [freediving.aida-html-test]
            [freediving.legacy-test]
            [freediving.extraction-test]
            [freediving.depth-test]
            [freediving.candidates-test]
            [freediving.packets-test]
            [freediving.source-pages-test]))

(defn -main [& _]
  (let [result (t/run-tests 'freediving.archive-test 'freediving.aida-html-test 'freediving.legacy-test 'freediving.extraction-test 'freediving.depth-test 'freediving.candidates-test 'freediving.packets-test 'freediving.source-pages-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
