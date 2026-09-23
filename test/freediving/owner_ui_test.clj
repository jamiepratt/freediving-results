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
                    const request={id:'r1',revision:2,'suggested-change':'<img src=x>',reason:'Visitor reason','evidence-reference':'https://visitor.example/unverified'};
                    assert.throws(()=>ui.triage(request,{action:'dismiss',actor:'owner',reason:''},'t1'));
                    assert.throws(()=>ui.triage(request,{action:'link-proposal',actor:'owner',reason:'Checked', 'proposal-id':''},'t1'));
                    assert.deepEqual(ui.triage(request,{action:'dismiss',actor:'owner',reason:'Insufficient registered evidence'},'t1'),{id:'t1',actor:'owner',reason:'Insufficient registered evidence','base-revision':2,'request-id':'r1',action:'dismiss'});
                    const link=ui.triage(request,{action:'link-proposal',actor:'owner',reason:'Inspected registered source','proposal-id':'p1'},'t2');
                    assert.equal(link['proposal-id'],'p1');assert.equal(link.evidence,undefined);assert.equal(link.after,undefined);
                    console.log('owner request contract passed');")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(deftest source-page-navigation-contract
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                    (async()=>{
                    assert.equal(ui.reviewEnabled({demo:true}),false);
                    assert.equal(ui.reviewEnabled({'review-enabled?':true}),true);
                    assert.equal(ui.reviewEnabled({'review-enabled?':'true'}),false);
                    assert.equal(ui.sourcePageQuery({'job-id':'a/b & c',ordinal:0},2),'job-id=a%2Fb+%26+c&ordinal=0&page=2');
                    assert.throws(()=>ui.sourcePageQuery({'job-id':'j',ordinal:0},0));
                    const pending=[],states=[];
                    const viewer=ui.pageViewer(url=>new Promise((resolve,reject)=>pending.push({url,resolve,reject})),s=>states.push(s));
                    const first=viewer.load({'job-id':'first',ordinal:0},1);
                    const second=viewer.load({'job-id':'second',ordinal:1},2);
                    assert.equal(states.at(-1).state,'loading');
                    pending[1].resolve({page:2,'page-count':3,width:1200,height:1600,'render-id':'second'});await second;
                    assert.equal(states.at(-1).metadata['render-id'],'second');assert.equal(states.at(-1).image,'/api/source-page.png?job-id=second&ordinal=1&page=2&render-id=second');
                    pending[0].resolve({page:1,'render-id':'stale'});await first;
                    assert.equal(states.at(-1).metadata['render-id'],'second');
                    const third=viewer.load({'job-id':'missing',ordinal:0},1);pending[2].reject(Error('Source unavailable'));await third;
                    assert.equal(states.at(-1).state,'error');assert.equal(states.at(-1).message,'Source unavailable');
                    const fourth=viewer.load({'job-id':'j',ordinal:0},1);viewer.clear();pending[3].resolve({page:1});await fourth;
                    assert.equal(states.at(-1).state,'empty');
                    })().catch(e=>{console.error(e);process.exit(1)});")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(deftest unparsed-source-search-and-page-attestation
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                    const p={outcome:'unknown',target:{'job-id':'source-j',ordinal:5,payload:{raw:{line:'17 Julia RAWGLYPH 90'},coordinates:{page:59,line:23}}}};
                    const c=ui.casePresentation(p);assert.match(c.search,/julia rawglyph/);assert.match(c.label,/Unparsed source text/);assert.match(c.label,/Julia RAWGLYPH/);assert.match(c.context,/page 59.*line 23/);
                    p.target.payload.raw.fields={'source-name':'Mirela RAW'};assert.match(ui.casePresentation(p).search,/mirela raw/);
                    assert.deepEqual(ui.viewerControls({state:'loaded',page:1,count:3,observationPage:1,busy:false}),{previous:true,next:false,go:false,visual:false});
                    assert.equal(ui.viewerControls({state:'loaded',page:2,count:3,observationPage:1,busy:false}).visual,true);
                    assert.equal(ui.viewerControls({state:'loaded',page:3,count:3,observationPage:3,busy:false}).next,true);
                    assert.equal(ui.viewerControls({state:'loading',page:1,count:3,observationPage:1,busy:false}).go,true);
                    assert.equal(ui.viewerControls({state:'loaded',page:1,count:3,observationPage:1,busy:true}).next,true);")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(defn -main [& _]
  (let [r (run-tests 'freediving.owner-ui-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
