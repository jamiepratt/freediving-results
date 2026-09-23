(ns freediving.test-runner
  (:require [clojure.test :as t]
            [freediving.archive-test]))

(defn -main [& _]
  (let [result (t/run-tests 'freediving.archive-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
