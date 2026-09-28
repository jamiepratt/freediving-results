(ns freediving.represented-geography-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.represented-geography :as geography]))

(deftest source-codes-follow-sports-continents
  (is (= :europe (geography/sports-continent "ARM")))
  (is (= :europe (geography/sports-continent "POL")))
  (is (= :asia (geography/sports-continent "HKG")))
  (is (= :asia (geography/sports-continent "TPE")))
  (is (= :asia (geography/sports-continent "PHL")))
  (is (= :africa (geography/sports-continent "EGY")))
  (is (= :americas (geography/sports-continent "USA")))
  (is (= :oceania (geography/sports-continent "AUS"))))

(deftest team-and-unknown-tokens-stay-unresolved
  (is (nil? (geography/sports-continent "CMAS1")))
  (is (nil? (geography/sports-continent "AIN")))
  (is (nil? (geography/sports-continent "PHI")))
  (is (nil? (geography/sports-continent "pol")))
  (is (nil? (geography/sports-continent nil))))

(defn -main [& _]
  (let [result (run-tests 'freediving.represented-geography-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
