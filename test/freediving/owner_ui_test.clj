(ns freediving.owner-ui-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.shell :as shell]))
(deftest owner-request-contract
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                    assert.deepEqual(ui.comparison({raw:{fields:{performance:'012.50'}},parsed:{performance:12.5}}, {performance:13}),[['performance','012.50',12.5,13]]);
                    assert.equal(ui.scalar('number','12.5'),12.5);
                    assert.equal(ui.scalar('unknown',''),null);assert.throws(()=>ui.scalar('boolean','typo'));assert.equal(ui.scalar('boolean','false'),false);
                    assert.throws(()=>ui.scalar('number','NaN'));
                    const d={packet:{target:{'job-id':'j',ordinal:0}},effective:{revision:2,fields:{performance:12},identity:{outcome:'unknown'}}};
                    const p=ui.proposal(d,{field:'performance',category:'substantive-correction',type:'number',value:'13',actor:'owner',reason:'row',page:1,line:2},'retry-id');
                    assert.equal(p.before,12);assert.equal(p.after,13);assert.equal(p['base-revision'],2);assert.equal(p.id,'retry-id');
                    assert.deepEqual(p.evidence,[{page:1,line:2}]);
                    d.publication={revision:1,'review-revision':2,'policy-version':'p',observation:{'job-id':'j',ordinal:0}};
                    assert.throws(()=>ui.publication(d,{action:'validate',actor:'owner',reason:'checked',page:1,line:2,visual:false,substantive:true},'v'));
                    const v=ui.publication(d,{action:'validate',actor:'owner',reason:'checked',page:1,line:2,visual:true,substantive:true},'v');
                    assert.equal(v.attestations['source-visual-accuracy'],true);assert.equal(v['review-revision'],2);
                    console.log('owner request contract passed');")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(defn -main [& _]
  (let [r (run-tests 'freediving.owner-ui-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
