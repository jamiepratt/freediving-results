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
(deftest scored-identity-proposal-binds-exact-pair
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                    const target={'job-id':'left',ordinal:0,'candidate-id':'a','source-sha256':'s1','artifact-sha256':'a1',page:2,line:3};
                    const candidate={'job-id':'right',ordinal:4,'candidate-id':'b','source-sha256':'s2','artifact-sha256':'a2',page:5,line:6};
                    const score={'score-status':'complete','run-id':'run','provider-id':'jev','case-id':'case','result-hash':'hash','target-reference':target,'candidate-reference':candidate,identity:{probabilities:{match:0.91,'no-match':0.07,abstain:0.02}}};
                    const d={packet:{target:{'job-id':'left',ordinal:0}},effective:{revision:2,fields:{},identity:{outcome:'unknown'}},'jev-scores':[score]};
                    const f={field:'identity',outcome:'matched',score,inspection:{'both-versions-reviewed':true,'contrary-evidence-reviewed':true,'source-dependence-reviewed':true},actor:'owner',reason:'Both registered originals inspected',page:2,line:3};
                    assert.throws(()=>ui.proposal(d,{...f,inspection:{...f.inspection,'both-versions-reviewed':false}},'p'));
                    assert.throws(()=>ui.proposal(d,{...f,score:{...score,'score-status':'stale'}},'p'));
                    assert.throws(()=>ui.proposal(d,{...f,score:{...score,'result-hash':'other'}},'p'));
                    const match=ui.proposal(d,f,'p');
                    assert.deepEqual(match['jev-score'],{'run-id':'run','provider-id':'jev','case-id':'case','result-hash':'hash'});
                    assert.deepEqual(match.inspection,{'both-versions-reviewed':true,'contrary-evidence-reviewed':true,'source-dependence-reviewed':true});
                    assert.deepEqual(match.evidence,[target,candidate]);
                    assert.deepEqual(match['identity-target'],candidate);
                    assert.deepEqual(match.after,{outcome:'matched','identity-id':'local-observation:right:4'});
                    for(const outcome of ['no-match','unknown']){const p=ui.proposal(d,{...f,outcome},outcome);assert.deepEqual(p.after,{outcome});assert.deepEqual(p.evidence,[target,candidate]);assert.equal(p['identity-target'],undefined);}
                    console.log('scored identity contract passed');")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(deftest owner-queue-sorts-current-jev-probabilities
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                    const packet=(id,score,outcome='candidate')=>({target:{'job-id':id,ordinal:0,payload:{parsed:{'source-name':id}}},outcome,'jev-scores':score?[score]:[]});
                    const score=(status,match,noMatch)=>({'score-status':status,identity:{outcome:'match',probabilities:{match,'no-match':noMatch,abstain:0.1}}});
                    const rows=[packet('missing'),packet('high',score('complete',0.8,0.1)),packet('tie-b',score('complete',0.5,0.4)),packet('stale',score('stale',0.99,0.01)),packet('low',score('complete',0.1,0.8)),packet('tie-a',score('complete',0.5,0.4)),packet('failed',score('failed',0.98,0.01))];
                    const ids=(mode,filter='',outcome='')=>ui.queueCases(rows,{sort:mode,filter,outcome}).map(p=>p.target['job-id']);
                    assert.deepEqual(ids('match-desc'),['high','tie-a','tie-b','low','missing','stale','failed']);
                    assert.deepEqual(ids('match-asc'),['low','tie-a','tie-b','high','missing','stale','failed']);
                    assert.deepEqual(ids('no-match-desc'),['low','tie-a','tie-b','high','missing','stale','failed']);
                    assert.deepEqual(ids('no-match-asc'),['high','tie-a','tie-b','low','missing','stale','failed']);
                    assert.deepEqual(ids('match-desc','tie'),['tie-a','tie-b']);
                    assert.deepEqual(ids('match-desc','','unknown'),[]);
                    assert.equal(ui.scoreSummary(rows[3]).status,'stale');
                    assert.equal(ui.scoreSummary(rows[0]).status,'missing');
                    assert.equal(ui.scoreSummary(rows[1]).probabilities.match,0.8);
                    const multi=packet('multi',score('complete',0.9,0.03));multi['jev-scores'].push(score('complete',0.4,0.55));
                    assert.equal(ui.scoreSummary(multi,'match-desc').probabilities.match,0.9);
                    assert.equal(ui.scoreSummary(multi,'match-asc').probabilities.match,0.4);
                    assert.equal(ui.scoreSummary(multi,'no-match-desc').probabilities['no-match'],0.55);
                    assert.equal(ui.scoreSummary(multi,'no-match-asc').probabilities['no-match'],0.03);
                    assert.deepEqual(ui.scoreRows(multi,'no-match-desc').map(x=>[x.sortingPair,x.score.identity.probabilities.match]),[[false,0.9],[true,0.4]]);
                    assert.deepEqual(ui.queueCases([multi,packet('middle',score('complete',0.6,0.2))],{sort:'no-match-desc'}).map(p=>p.target['job-id']),['multi','middle']);
                    const invalid=packet('invalid',score('complete',1.2,-0.3));
                    assert.equal(ui.scoreSummary(invalid).probabilities,null);
                    assert.deepEqual(ui.queueCases([invalid,packet('valid',score('complete',0.2,0.7))],{sort:'match-desc'}).map(p=>p.target['job-id']),['valid','invalid']);
                    console.log('owner queue ordering passed');")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(deftest owner-score-presentation-keeps-exact-values-and-neutral-status
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                    const s=p=>({'score-status':'complete',identity:{outcome:'match',probabilities:{match:p,'no-match':1-p,abstain:0}},'model-version':'jev-v1','completed-at':'2026-09-25T12:00:00Z','run-id':'run-1','case-id':'case-1','request-hash':'request-1','result-hash':'result-1','source-references':[[{page:2,lines:[3]}],[{page:7,lines:[8]}]]});
                    assert.equal(ui.probabilityColor(0),'#8b1e2d');
                    assert.equal(ui.probabilityColor(0.5),'#a75a00');
                    assert.equal(ui.probabilityColor(1),'#17643d');
                    assert.notEqual(ui.probabilityColor(0.25),ui.probabilityColor(0.75));
                    const view=ui.scorePresentation(s(0.321),Date.parse('2026-09-26T12:00:00Z'));
                    assert.match(view.probabilities,/P\\(match\\) 0\\.321.*P\\(no match\\) 0\\.679.*P\\(abstain\\) 0/s);
                    assert.match(view.identity,/run-1.*case-1.*request-1.*result-1/s);
                    assert.match(view.model,/jev-v1/);assert.match(view.age,/1 day ago/);
                    assert.match(view.evidence,/2 source records/);
                    assert.match(view.outcome,/match/);
                    assert.equal(ui.scorePresentation({'score-status':'stale',identity:{probabilities:{match:0.99}}}).color,null);
                    assert.match(ui.scorePresentation({'score-status':'failed'}).status,/failed/i);
                    console.log('owner score presentation passed');")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(deftest owner-score-citations-cover-every-source-format
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                    const score={'score-status':'complete',identity:{outcome:'match',probabilities:{match:0.5,'no-match':0.4,abstain:0.1}},'source-references':[[{'evidence-id':'pdf-id','source-sha256':'pdf-source','artifact-sha256':'pdf-artifact','observation-id':'pdf-row','source-format':'pdf',page:7,lines:[12,14]}],[{'evidence-id':'html-id','source-sha256':'html-source','artifact-sha256':'html-artifact','observation-id':'html-row','source-format':'html',locator:{table:3,row:9}},{'evidence-id':'json-id','source-sha256':'json-source','artifact-sha256':'json-artifact','observation-id':'json-row','source-format':'json',locator:{'row-index-zero-based':0,'source-page-url':'https://source.example/view?a=<script>'}}]]};
                    const evidence=ui.scorePresentation(score).evidence;
                    for(const value of ['pdf-id','pdf-source','pdf-artifact','pdf-row','page 7','lines 12-14','html-id','html-source','html-artifact','html-row','table 3','row 9','json-id','json-source','json-artifact','json-row','row index zero based 0','https://source.example/view?a=<script>']) assert.ok(evidence.includes(value),value+' absent from '+evidence);
                    assert.match(evidence,/3 source records/);
                    console.log('owner source citations passed');")]
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
(deftest html-evidence-never-fabricates-pdf-coordinates
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                   const d={packet:{target:{'job-id':'j',ordinal:0,'source-format':'html',payload:{coordinates:{table:2,row:3}}}},effective:{revision:0,fields:{points:1}}};
                   const p=ui.proposal(d,{field:'points',category:'substantive-correction',type:'number',value:'2',actor:'owner',reason:'Inspected row',table:2,row:3},'p');
                   assert.deepEqual(p.evidence,[{table:2,row:3}]);assert.match(ui.casePresentation(d.packet).context,/table 2.*row 3/);
                   assert.throws(()=>ui.proposal(d,{field:'points',actor:'owner',reason:'row',table:0,row:3},'p'));
                   (async()=>{let url;let state;const viewer=ui.pageViewer(async u=>{url=u;return {'render-id':'h',coordinates:{table:2,row:3}}},s=>state=s);await viewer.load(d.packet.target,1);assert.equal(url,'/api/source-html?job-id=j&ordinal=0');assert.equal(state.state,'html');assert.equal(state.image,undefined);})().catch(e=>{console.error(e);process.exit(1)});")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(deftest pdf-table-hints-keep-pdf-inspection
  (let [r (shell/sh "node" "-e"
                    "const assert=require('node:assert/strict');const ui=require('./resources/owner.js');
                   const target={'job-id':'pdf',ordinal:0,'source-format':'pdf',payload:{coordinates:{page:2,line:3,table:99,row:99}}};
                   assert.match(ui.casePresentation({target}).context,/page 2.*line 3/);
                   (async()=>{let url,state;const v=ui.pageViewer(async u=>{url=u;return {'render-id':'pdf'}},s=>state=s);await v.load(target,2);assert.equal(url,'/api/source-page?job-id=pdf&ordinal=0&page=2');assert.equal(state.state,'ready');})().catch(e=>{console.error(e);process.exit(1)});")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(defn -main [& _]
  (let [r (run-tests 'freediving.owner-ui-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
