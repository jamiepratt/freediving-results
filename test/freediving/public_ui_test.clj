(ns freediving.public-ui-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.shell :as shell]))
(deftest public-navigation-and-source-value-contract
  (let [r (shell/sh "node" "-e"
                    "const a=require('node:assert/strict'),ui=require('./resources/public.js');
     a.equal(ui.searchURL({q:'A & B',discipline:'STA',page:2}),'/api/results?q=A+%26+B&discipline=STA&page=2&limit=10');
     a.deepEqual(ui.performance({'final-depth':32}),{label:'Final depth',value:'32'});a.deepEqual(ui.performance({'final-time':{raw:'01:02.30'}}),{label:'Final time',value:'01:02.30'});a.equal(ui.display(null),'Not recorded');a.equal(ui.display({raw:'01:02.30',seconds:62.3}),'01:02.30');
     a.equal(ui.display({minutes:1,seconds:2}),'minutes: 1 · seconds: 2');
     a.deepEqual(ui.comparison({original:{performance:12},effective:{performance:13},'raw-values':{performance:'012'}}),[['performance','012','12','13']]);
     a.equal(ui.internalLink('results','a'.repeat(64)),'/results/'+ 'a'.repeat(64));a.equal(ui.internalLink('results','javascript:bad'),null);
     a.equal(ui.citationURL('javascript:alert(1)'),null);a.equal(ui.citationURL('https://example.org/results.pdf'),'https://example.org/results.pdf');
     console.log('public UI contract passed');")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(defn -main [& _]
  (let [r (run-tests 'freediving.public-ui-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
