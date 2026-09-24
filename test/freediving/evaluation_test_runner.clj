(ns freediving.evaluation-test-runner
  (:require [clojure.test :as t]
            [freediving.evaluation-data-test]
            [freediving.evaluation-protocol-test]
            [freediving.evaluation-providers-test]
            [freediving.evaluation-test]
            [freediving.evaluation-cli-test]
            [freediving.evaluation-node-test]
            [freediving.evaluation-batches-test]))

(defn -main [& _]
  (let [r (t/run-tests 'freediving.evaluation-data-test
                       'freediving.evaluation-protocol-test
                       'freediving.evaluation-providers-test
                       'freediving.evaluation-test
                       'freediving.evaluation-cli-test
                       'freediving.evaluation-node-test
                       'freediving.evaluation-batches-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
