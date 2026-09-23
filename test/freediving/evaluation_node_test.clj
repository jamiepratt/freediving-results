(ns freediving.evaluation-node-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.evaluation-cli :as cli]
            [freediving.evaluation-node :as node]))

(deftest graph-node-emits-an-opaque-receipt-and-replays-local-writes
  (let [parent (.toRealPath (java.nio.file.Files/createTempDirectory "shadow-node" (make-array java.nio.file.attribute.FileAttribute 0))
                            (make-array java.nio.file.LinkOption 0))
        seen (atom [])
        dataset (cli/read-input "test/fixtures/shadow-evaluation.edn")
        reference (apply str (repeat 64 "a"))
        resolved (atom [])
        f (node/node-function (fn [handle receipt] (swap! seen conj [handle receipt]))
                              (fn [ref] (swap! resolved conj ref) dataset)
                              (str (.resolve parent "private"))
                              [{:id "stub" :provider :stub}] {})]
    (f :node-handle reference)
    (f :node-handle reference)
    (is (= [reference reference] @resolved))
    (is (= 2 (count @seen)))
    (is (= (first @seen) (second @seen)))
    (is (= :node-handle (ffirst @seen)))
    (is (= #{:run-id :input-hash :report-hash} (set (keys (second (first @seen))))))
    (is (thrown? Exception (f :node-handle dataset)))))

(defn -main [& _]
  (let [r (run-tests 'freediving.evaluation-node-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
