(ns freediving.test-runner
  (:require [clojure.test :as t]
            [freediving.archive-test]
            [freediving.aida-html-test]
            [freediving.html-evidence-test]
            [freediving.legacy-test]
            [freediving.extraction-test]
            [freediving.croatia-open-test]
            [freediving.athens-geometry-test]
            [freediving.depth-test]
            [freediving.depth-2026-test]
            [freediving.indoor-2026-test]
            [freediving.indoor-time-2026-test]
            [freediving.candidates-test]
            [freediving.packets-test]
            [freediving.source-pages-test]))

(defn -main [& _]
  (let [result (t/run-tests 'freediving.archive-test 'freediving.aida-html-test 'freediving.html-evidence-test 'freediving.legacy-test 'freediving.extraction-test 'freediving.croatia-open-test 'freediving.athens-geometry-test 'freediving.depth-test 'freediving.depth-2026-test 'freediving.indoor-2026-test 'freediving.indoor-time-2026-test 'freediving.candidates-test 'freediving.packets-test 'freediving.source-pages-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
