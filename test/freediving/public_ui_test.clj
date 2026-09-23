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
(deftest anonymous-correction-retry-and-validation-contract
  (let [r (shell/sh "node" "-e"
                    "const a=require('node:assert/strict'),ui=require('./resources/public.js');
(async()=>{
 const sent=[];let fail=true,n=0;
 const request=ui.correctionClient('a'.repeat(64),'v1',async(url,options)=>{
  sent.push({url,...options});if(fail)throw Error('offline');
  return {ok:true,json:async()=>({id:JSON.parse(options.body).id,status:'pending',duplicate:true})};
 },()=> 'request-'+ ++n);
 await a.rejects(request({suggestion:' ',reason:'why',evidence:'page 1'}),/suggested change/i);a.equal(sent.length,0);
 await a.rejects(request({suggestion:'x',reason:'why',evidence:''}),/evidence/i);
 await a.rejects(request({suggestion:'x'.repeat(1001),reason:'why',evidence:'p1'}),/1000/);
 const input={suggestion:'Żółć <b>32</b>',reason:'Source differs',evidence:'https://example.org/a.pdf page 1'};
 await a.rejects(request(input),/not confirmed/i);fail=false;
 const receipt=await request(input);a.equal(receipt.duplicate,true);a.equal(sent[0].body,sent[1].body);
 a.equal(sent[0].url,'/api/corrections');a.equal(sent[0].method,'POST');a.equal(sent[0].credentials,'omit');a.equal(sent[0].headers['X-Correction-Request'],'1');
 a.deepEqual(JSON.parse(sent[0].body),{id:'request-1','result-id':'a'.repeat(64),version:'v1',...input});
 await request({...input,reason:'Different reason'});a.notEqual(JSON.parse(sent[2].body).id,receipt.id);
 const limited=ui.correctionClient('r','v',async()=>({ok:false,status:429,json:async()=>({error:'Too many requests. Try later.'})}),()=> 'id');
 await a.rejects(limited(input),/Too many requests/);
 console.log('anonymous correction contract passed');
})().catch(e=>{console.error(e);process.exitCode=1});")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(deftest correction-form-renders-and-submits-without-navigation
  (let [r (shell/sh "node" "-e"
                    "const a=require('node:assert/strict');
class Element {
 constructor(tag){this.tag=tag;this.children=[];this.events={};this.attributes={};this.value='';}
 append(...items){this.children.push(...items)}
 replaceChildren(...items){this.children=items}
 setAttribute(k,v){this.attributes[k]=v} removeAttribute(k){delete this.attributes[k]}
 addEventListener(k,v){this.events[k]=v} focus(){this.focused=true} reportValidity(){return true}
}
const nodes=Object.fromEntries(['content','status','demo'].map(k=>[k,new Element('div')]));
global.document={getElementById:k=>nodes[k],createElement:t=>new Element(t),addEventListener:()=>{}};
const events={};global.window={addEventListener:(k,v)=>events[k]=v};
global.location={pathname:'/results/'+ 'a'.repeat(64),search:'',origin:'http://localhost'};
global.history={pushState:()=>{throw Error('must not navigate')}};
let post=null;
global.fetch=async(url,options)=> options.method==='POST' ? (post={url,options},{ok:true,json:async()=>({id:'receipt-123',status:'pending',duplicate:false})}) : {ok:true,json:async()=>({correction:{version:'v1'},result:{'result-id':'a'.repeat(64),effective:{'source-name':'Synthetic'}}})};
require('./resources/public.js');
function all(n){return [n,...n.children.flatMap(all)]}
(async()=>{
 await events.pageshow();
 const form=all(nodes.content).find(n=>n.attributes['aria-label']==='Suggest a correction');a.ok(form);
 const fields=all(form).filter(n=>n.tag==='textarea');a.equal(fields.length,3);
 fields.forEach(n=>{a.equal(n.required,true);a.ok(n.attributes['aria-describedby']);a.ok(all(form).some(l=>l.tag==='label'&&l.htmlFor===n.id));});
 a.deepEqual(fields.map(n=>n.maxLength),[1000,2000,2000]);
 fields[0].value='32 metres';fields[1].value='Source says 32';fields[2].value='Document page 1';
 await form.events.submit({preventDefault(){}});
 a.equal(post.url,'/api/corrections');a.equal(JSON.parse(post.options.body).suggestion,'32 metres');
 const feedback=all(form).find(n=>n.attributes.role==='status');a.match(feedback.textContent,/receipt-123/);a.match(feedback.textContent,/Pending owner approval/);a.ok(feedback.focused);
 a.ok(fields.every(n=>n.value===''));a.equal(all(form).find(n=>n.tag==='button').disabled,true);
 console.log('rendered correction form passed');
})().catch(e=>{console.error(e);process.exitCode=1});")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))))
(defn -main [& _]
  (let [r (run-tests 'freediving.public-ui-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
