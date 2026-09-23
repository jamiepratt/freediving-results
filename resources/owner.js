/* Private local owner review. No credentials are persisted in browser storage. */
(function () {
  'use strict';
  function scalar(type, value) {
    if (type === 'unknown') return null;
    if (type === 'number') {
      if (!String(value).trim() || !Number.isFinite(Number(value))) throw Error('Enter a finite number.');
      return Number(value);
    }
    if (type === 'boolean') { if (!['true','false'].includes(value)) throw Error('Boolean values must be exactly true or false.'); return value === 'true'; }
    return value;
  }
  function evidence(f) {
    const page = Number(f.page), line = Number(f.line);
    if (!Number.isInteger(page) || page < 1 || !Number.isInteger(line) || line < 1) throw Error('Enter the inspected source page and line.');
    return [{page, line}];
  }
  function audit(f, id, revision) {
    if (!f.actor.trim() || !f.reason.trim()) throw Error('Reviewer label and evidence-based reason are required.');
    return {id, actor:f.actor.trim(), reason:f.reason.trim(), 'base-revision':revision};
  }
  function proposal(d, f, id) {
    const t = d.packet.target, identity = f.field === 'identity';
    const p = {...audit(f,id,d.effective.revision),'job-id':t['job-id'],ordinal:t.ordinal,
      field:f.field,category:identity?'identity-matching':f.category,
      before:identity?d.effective.identity:d.effective.fields[f.field],
      after:identity?{outcome:f.outcome}:scalar(f.type,f.value),evidence:evidence(f)};
    if (identity && f.outcome === 'matched') {
      if (!f.anchor) throw Error('Choose a registered candidate anchor.');
      p.after = {outcome:'matched','identity-id':f.anchor['identity-id']};
      p['identity-target'] = f.anchor.reference;
      p.evidence.push(f.anchor.reference);
    }
    return p;
  }
  function publication(d,f,id) {
    if (f.action === 'validate' && !(f.visual && f.substantive)) throw Error('Both independent extraction attestations must be checked after inspecting the source.');
    const q=d.publication;
    return {...audit(f,id,q.revision),'job-id':q.observation['job-id'],ordinal:q.observation.ordinal,
      'review-revision':q['review-revision'],'policy-version':q['policy-version'],observation:q.observation,
      action:f.action,evidence:evidence(f),attestations:f.action==='validate'?{'source-visual-accuracy':true,'no-unresolved-substantive-errors':true}:{}};
  }
  function triage(request, f, id) {
    if (!['dismiss','link-proposal'].includes(f.action)) throw Error('Choose a triage action.');
    const event={...audit(f,id,request.revision),'request-id':request.id,action:f.action};
    if(f.action==='link-proposal') {
      if(!f['proposal-id']?.trim()) throw Error('Choose an existing proposal for this observation.');
      event['proposal-id']=f['proposal-id'].trim();
    }
    return event;
  }
  function comparison(payload, effective) {
    const raw=payload.raw?.fields||{}, parsed=payload.parsed||{};
    return Object.keys({...raw,...parsed,...effective}).map(k=>[k,raw[k],parsed[k],effective[k]]);
  }
  function casePresentation(packet) {
    const target=packet.target,payload=target.payload||{},raw=payload.raw||{},coords=payload.coordinates||{};
    const original=raw.fields?.['source-name']||raw.line||'';
    const label=payload.parsed?.['source-name']||('Unparsed source text: '+(original||'No source name available'));
    const context='Source '+String(target['job-id']).slice(0,12)+' · page '+(coords.page??'?')+' · line '+(coords.line??'?')+' · observation '+target.ordinal;
    return {label,context,search:JSON.stringify([payload.parsed,raw,payload['source-lines'],target['job-id'],context,packet.outcome]).toLowerCase()};
  }
  function viewerControls({state,page,count,observationPage,busy}) {
    const loaded=state==='loaded';
    return {previous:busy||!loaded||page<=1,next:busy||!loaded||page>=count,
      go:busy||state==='empty'||state==='loading',visual:busy||!loaded||page!==observationPage};
  }
  function reviewEnabled(session) { return session['review-enabled?'] === true; }
  function sourcePageQuery(target, page) {
    if (!Number.isInteger(page) || page < 1) throw Error('Choose a positive whole page number.');
    return new URLSearchParams({'job-id':target['job-id'],ordinal:target.ordinal,page}).toString();
  }
  function pageViewer(request, show) {
    let ticket=0;
    return {
      clear(){ticket++;show({state:'empty'});},
      async load(target,page){
        const current=++ticket;
        show({state:'loading',target,page});
        try {
          const query=sourcePageQuery(target,page),metadata=await request('/api/source-page?'+query);
          if(current===ticket)show({state:'ready',target,page,metadata,image:'/api/source-page.png?'+query+'&'+new URLSearchParams({'render-id':metadata['render-id']})});
        } catch(error){if(current===ticket)show({state:'error',target,page,message:error.message});}
      }
    };
  }
  if (typeof module !== 'undefined') { module.exports={scalar,proposal,publication,comparison,triage,reviewEnabled,sourcePageQuery,pageViewer,casePresentation,viewerControls}; return; }
  const $=id=>document.getElementById(id);
  let csrf=null, packets=[], detail=null, selected=null, pending=null, busy=false, generation=0, correctionOffset=0, canReview=false, hasViewer=false;
  function node(tag,text,cls) { const e=document.createElement(tag); if(text!==undefined)e.textContent=text; if(cls)e.className=cls;return e; }
  function human(v) { return String(v).replaceAll('-',' '); }
  function readable(v) {
    if(v===null || v===undefined)return 'Unknown';
    if(typeof v!=='object')return String(v);
    if(Array.isArray(v))return v.map(readable).join('; ') || 'None';
    return Object.entries(v).map(([k,x])=>human(k)+': '+readable(x)).join(' | ');
  }
  function structure(v) {
    if(!v || typeof v!=='object')return node('p',readable(v));
    if(Array.isArray(v)){ const list=node('ul');v.forEach(x=>{const li=node('li');li.append(structure(x));list.append(li);});if(!v.length)list.append(node('li','None'));return list; }
    const dl=node('dl');Object.entries(v).forEach(([k,x])=>{dl.append(node('dt',human(k)));const dd=node('dd');dd.append(structure(x));dl.append(dd);});return dl;
  }
  function status(message,error=false){ $('status').textContent=message;$('status').className=error?'status error':'status'; }
  async function api(path,body){
    const response=await fetch(path,{method:body?'POST':'GET',credentials:'same-origin',headers:body?{'Content-Type':'application/json','X-CSRF-Token':csrf||''}:{},body:body?JSON.stringify(body):undefined});
    const result=await response.json();
    if(!response.ok){ const error=Error(result.error||'Request failed');error.status=response.status;throw error; }return result;
  }
  function targetQuery(t){return new URLSearchParams({'job-id':t['job-id'],ordinal:t.ordinal}).toString();}
  function formData(id){return Object.fromEntries(new FormData($(id)));}
  function common(f){return {...f,actor:$('actor').value,page:$('page').value,line:$('line').value};}
  function lock(value){busy=value;document.querySelectorAll('button').forEach(b=>b.disabled=value);syncPageControls();}
  async function mutate(path,request){
    if(!canReview){status('Read-only inspection: owner review is not enabled.',true);return;}
    if(busy)return;
    pending={path,request};$('retry').hidden=true;lock(true);
    try{await api(path,request);pending=null;if(path==='/api/corrections/triage')await loadCorrections();else {await loadDetail(selected);await loadCorrections();}status('Saved. Audit history and current revisions reloaded.');}
    catch(e){status(e.status===409?'Conflict: '+e.message+(path==='/api/corrections/triage'?'. Refresh requests before preparing a new triage action.':'. Reload this case before preparing a new action.'):e.message,true);$('retry').hidden=!!e.status;$('reload').hidden=path==='/api/corrections/triage';}
    finally{lock(false);}
  }
  async function loadCorrections(){
    const sessionToken=csrf, pageOffset=correctionOffset, result=await api('/api/corrections?offset='+pageOffset), rows=result.requests;
    if(csrf!==sessionToken || correctionOffset!==pageOffset)return;
    $('corrections').replaceChildren();
    $('corrections-page').textContent=rows.length?'Requests '+(correctionOffset+1)+' to '+(correctionOffset+rows.length):'No requests on this page.';
    $('corrections-previous').hidden=correctionOffset===0;$('corrections-next').hidden=rows.length<100;
    rows.forEach(r=>{
      const card=node('article',undefined,'event');
      card.append(node('h3','Request '+r.id),node('p','Suggested change: '+r.suggestion),node('p','Visitor reason: '+r.reason),node('p','Unverified evidence citation: '+r.evidence),node('small','Request revision '+r.revision));
      const open=node('button',canReview?'Inspect observation and prepare proposal':'Inspect observation');open.type='button';open.onclick=()=>openCase(r);card.append(open);
      if(!canReview){card.append(expandable('Append-only triage history',r.history||[]));$('corrections').append(card);return;}
      const form=node('form');
      const label=(text,input)=>{const l=node('label',text);l.append(input);form.append(l);};
      const action=node('select');action.append(new Option('Dismiss with reason','dismiss'),new Option('Link an existing proposal','link-proposal'));label('Triage action',action);
      const proposalId=node('input');proposalId.maxLength=200;label('Existing proposal ID (required when linking)',proposalId);
      const actor=node('input');actor.value='local-owner';actor.required=true;actor.maxLength=200;label('Reviewer label',actor);
      const reason=node('textarea');reason.required=true;reason.maxLength=2000;reason.rows=3;label('Your triage reason',reason);
      const save=node('button','Record triage');form.append(save);
      form.onsubmit=event=>{event.preventDefault();try{mutate('/api/corrections/triage',triage(r,{action:action.value,'proposal-id':proposalId.value,actor:actor.value,reason:reason.value},crypto.randomUUID()));}catch(e){status(e.message,true);}};
      card.append(form,expandable('Append-only triage history',r.history||[]));$('corrections').append(card);
    });
  }
  function renderList(){
    const filter=$('filter').value.toLowerCase(),outcome=$('outcome-filter').value;
    const visible=packets.filter(p=>casePresentation(p).search.includes(filter)&&(!outcome||p.outcome===outcome));
    $('cases').replaceChildren();$('case-count').textContent=visible.length+' of '+packets.length+' comparison cases';
    visible.forEach(p=>{const presentation=casePresentation(p),b=node('button',presentation.label,'case');b.append(node('small',human(p.outcome)),node('small',presentation.context));b.onclick=()=>openCase(p.target);$('cases').append(b);});
    if(!visible.length)$('cases').append(node('p','No cases match these filters.'));
  }
  function expandable(title,value){const d=node('details');d.append(node('summary',title),structure(value));return d;}
  function sourceEvidence(e){
    const view=node('div'),table=node('table'),header=node('tr');
    ['Page','Line','Original source text'].forEach(label=>header.append(node('th',label)));table.append(header);
    (e['source-lines']||[]).forEach(line=>{const row=node('tr');[line.page,line.line,line.text].forEach(value=>row.append(node('td',readable(value))));table.append(row);});
    view.append(table,expandable('Exact source provenance, hashes and acquisitions',Object.fromEntries(Object.entries(e).filter(([key])=>key!=='source-lines'))));return view;
  }
  function auditEntry(h,history){
    const item=node('article',undefined,'event');
    const approval=h.action==='reverse'?history.find(x=>x.id===h['event-id']):h;
    const proposal=h.action==='propose'?h:history.find(x=>x.id===approval?.['proposal-id']);
    item.append(node('h4',human(h.action)+(proposal?' · '+human(proposal.field):'')),node('p',h.reason),node('small',(h.actor||'Unknown actor')+' · '+(h['recorded-at']||'Time unavailable')+(h.revision!==undefined?' · Revision '+h.revision:'')));
    if(proposal)item.append(node('p','Before: '+readable(proposal.before)+' → Proposed: '+readable(proposal.after)));
    if(h.action==='propose')item.append(node('p','Proposal ID: '+h.id));
    item.append(expandable('Full audit record and exact provenance',Object.fromEntries(Object.entries(h).filter(([key])=>key!=='request'))));return item;
  }
  function renderDetail(d,e){
    const t=d.packet.target, parsed=t.payload.parsed||{}, fields=d.effective.fields||{};
    $('case-title').textContent=casePresentation(d.packet).label;
    $('revision').textContent='Review revision '+d.effective.revision+' · Extraction revision '+d.publication.revision+' · Identity '+readable(d.effective.identity);
    $('comparison').replaceChildren();
    const table=node('table'),head=node('tr');['Field','Raw source','Original parsed','Effective approved'].forEach(x=>head.append(node('th',x)));table.append(head);
    comparison(t.payload,fields).forEach(([k,...values])=>{const row=node('tr');[human(k),...values.map(readable)].forEach(x=>row.append(node('td',x)));table.append(row);});$('comparison').append(table);
    $('evidence').replaceChildren(sourceEvidence(e));
    sourceTarget=t;observationPage=e.coordinates?.page||e['source-lines']?.[0]?.page||1;sourcePage=observationPage;
    $('source-viewer').hidden=!hasViewer;
    if(hasViewer)pageView.load(t,sourcePage);
    $('uncertainties').replaceChildren(structure(d.packet.uncertainties),structure({'parse-status':t.payload['parse-status'],'unresolved-reasons':t.payload['unresolved-reasons']||[],errors:t.payload.errors||[]}));
    $('candidates').replaceChildren();$('anchor').replaceChildren(new Option('Select an inspected candidate',''));
    (d.packet.candidates||[]).forEach((c,i)=>{
      const card=node('section',undefined,'candidate');card.append(node('h4',c.observations.map(o=>o.payload.parsed?.['source-name']||'Unknown name').join(' / ')),node('p','Retrieval signals: '+readable(c.signals)));
      c.observations.forEach(o=>{card.append(expandable('Source comparison and exact provenance',o));const b=node('button','Inspect candidate source lines');b.type='button';b.onclick=async()=>{try{const data=await api('/api/evidence?'+targetQuery(o));card.append(sourceEvidence(data));}catch(err){status(err.message,true);}};card.append(b);if(hasViewer){const pageButton=node('button','Open candidate page and comparison');pageButton.type='button';pageButton.onclick=()=>openCase(o);card.append(pageButton);}});$('candidates').append(card);
      $('anchor').append(new Option(c.observations[0].payload.parsed?.['source-name']+' · '+c['source-sha256'].slice(0,12),String(i)));
    });
    if(!d.packet.candidates?.length)$('candidates').append(node('p','No supported candidate retrieved. Unknown and no-match do not establish distinct identities.'));
    $('field').replaceChildren(...Object.keys(fields).filter(k=>fields[k]===null||typeof fields[k]!=='object').map(k=>new Option(human(k),k)),new Option('Identity outcome','identity'));
    $('page').value=e.coordinates?.page||e['source-lines']?.[0]?.page||'';$('line').value=e.coordinates?.line||e['source-lines']?.[0]?.line||'';
    $('visual').checked=false;$('substantive').checked=false;$('publication-reason').value='';
    $('publication-state').textContent='Automated prerequisites: '+(d.publication['ready?']?'ready':'blocked')+'. Currently eligible: '+(d.publication['eligible?']?'yes':'no')+'. '+readable(d.publication.reasons);
    $('audit').replaceChildren();
    (d.history||[]).forEach(h=>{
      const item=auditEntry(h,d.history);
      if(canReview && h.action==='propose' && !d.history.some(x=>x['proposal-id']===h.id)){['approve','reject'].forEach(action=>{const b=node('button',human(action));b.type='button';b.onclick=()=>decision(action,h.id);item.append(b);});}
      if(canReview && h.action==='approve' && Object.values(d.effective.active||{}).includes(h.id)){const b=node('button','Reverse approval');b.type='button';b.onclick=()=>decision('reverse',h.id);item.append(b);}$('audit').append(item);
    });
    if(!d.history?.length)$('audit').append(node('p','No private review proposals or decisions.'));
    $('publication-history').replaceChildren(...(d['publication-history']||[]).map(h=>auditEntry(h,[])));if(!d['publication-history']?.length)$('publication-history').append(node('p','No extraction decisions.'));fieldChanged();$('detail').hidden=false;
  }
  async function loadDetail(t){const ticket=++generation,q=targetQuery(t);try{const [d,e]=await Promise.all([api('/api/detail?'+q),api('/api/evidence?'+q)]);if(ticket!==generation)return false;detail=d;renderDetail(d,e);return true;}catch(error){if(ticket!==generation)return false;throw error;}}
  async function openCase(t){if(busy)return;pageView.clear();selected=t;detail=null;$('detail').hidden=true;pending=null;$('retry').hidden=true;status('Loading case...');try{if(!await loadDetail(t))return;status(canReview?'Inspect original evidence before proposing a change.':'Read-only: inspect the registered page and compare extracted values.');$('case-title').focus();}catch(e){$('detail').hidden=true;status(e.message,true);}}
  function fieldChanged(){const f=$('field').value,identity=f==='identity';$('scalar-fields').hidden=identity;$('identity-fields').hidden=!identity;if(!identity){const v=detail.effective.fields[f];$('value-type').value=v===null?'unknown':typeof v==='number'?'number':typeof v==='boolean'?'boolean':'text';$('value').value=v??'';}}
  async function decision(action,id){try{const f=common({reason:$('decision-reason').value});const request={...audit(f,crypto.randomUUID(),detail.effective.revision),action,[action==='reverse'?'event-id':'proposal-id']:id};await mutate('/api/decisions',request);}catch(e){status(e.message,true);}}
  function clearSession(){generation++;pageView.clear();canReview=false;hasViewer=false;csrf=null;correctionOffset=0;packets=[];detail=null;selected=null;pending=null;$('workspace').hidden=true;$('detail').hidden=true;$('logout').hidden=true;$('retry').hidden=true;$('reload').hidden=true;$('login-panel').hidden=false;['cases','comparison','evidence','uncertainties','candidates','audit','publication-history','rubric','corrections'].forEach(id=>$(id).replaceChildren());['proposal','publication-form'].forEach(id=>$(id).reset());$('decision-reason').value='';$('capability').value='';$('filter').value='';$('outcome-filter').value='';}
  async function start(){const s=await api('/api/session');$('mode').textContent=s.demo?'SYNTHETIC DEMO · Owner only':(canReview?'REAL CORPUS · Review enabled':'REAL CORPUS · Read-only');if(!s.authenticated){clearSession();status('Owner login required. Paste this local server’s capability to continue.');return;}csrf=s.csrf;canReview=reviewEnabled(s);hasViewer=s['source-viewer?']===true;document.querySelectorAll('[data-review-only]').forEach(e=>e.hidden=!canReview);$('inspection-mode').textContent=canReview?'Owner review enabled. Every decision requires an explicit action.':'Read-only inspection. Proposals, decisions, triage and extraction validation are disabled.';$('logout').hidden=false;$('login-panel').hidden=true;$('workspace').hidden=false;const result=await api('/api/candidates');packets=result.packets;$('rubric').replaceChildren(structure(result.rubric));$('mode').textContent=s.demo?'SYNTHETIC DEMO · Owner only':(canReview?'REAL CORPUS · Review enabled':'REAL CORPUS · Read-only');renderList();await loadCorrections();status('Select a comparison case to inspect its evidence.');}
  let sourceTarget=null, sourcePage=1, sourceCount=1, observationPage=1, sourceState='empty';
  function syncPageControls(){const controls=viewerControls({state:sourceState,page:sourcePage,count:sourceCount,observationPage,busy});['previous','next','go'].forEach(key=>$('source-'+key).disabled=controls[key]);if(hasViewer)$('visual').disabled=controls.visual;}
  const pageView=pageViewer(api, view=>{
    sourceState=view.state;
    $('source-image').replaceChildren();$('source-render').replaceChildren();
    $('source-previous').disabled=true;$('source-next').disabled=true;$('source-go').disabled=true;
    $('visual').checked=false;$('substantive').checked=false;$('visual').disabled=hasViewer;
    if(view.state==='empty'){sourceTarget=null;$('source-context').textContent='';return;}
    sourceTarget=view.target;sourcePage=view.page;$('source-page').value=sourcePage;$('source-page').removeAttribute('max');
    $('source-context').textContent='Observation '+view.target['job-id']+' / '+view.target.ordinal+' · PDF page '+view.page;
    if(view.state==='loading'){$('source-image').append(node('p','Verifying source and rendering page...'));return;}
    if(view.state==='error'){$('source-image').append(node('p','Page unavailable: '+view.message,'error'));syncPageControls();return;}
    const m=view.metadata;sourceCount=m['page-count'];$('source-page').max=sourceCount;
    const img=node('img');img.alt='Registered source PDF page '+view.page+' for observation '+view.target.ordinal;
    img.onload=()=>{if(!img.isConnected)return;sourceState='loaded';syncPageControls();if(sourcePage!==observationPage)$('source-context').append(' · Context page only. Return to observation page '+observationPage+' before visual attestation.');};
    img.onerror=()=>{if(!img.isConnected)return;img.remove();$('source-image').append(node('p','Page image unavailable. Reload this page to verify the source again.','error'));sourceState='error';syncPageControls();};
    img.src=view.image;img.style.width=$('source-zoom').value+'%';$('source-image').append(img);
    $('source-context').textContent+=' of '+sourceCount+' · '+m.width+' × '+m.height+' pixels';
    $('source-render').append(expandable('Verified render identity',m));
  });
  $('source-navigation').onsubmit=event=>{event.preventDefault();if(sourceTarget)pageView.load(sourceTarget,Number($('source-page').value));};
  $('source-previous').onclick=()=>{if(sourceTarget && sourcePage>1)pageView.load(sourceTarget,sourcePage-1);};
  $('source-next').onclick=()=>{if(sourceTarget && sourcePage<sourceCount)pageView.load(sourceTarget,sourcePage+1);};
  $('source-zoom').onchange=()=>{const img=$('source-image').querySelector('img');if(img)img.style.width=$('source-zoom').value+'%';};
  $('corrections-refresh').onclick=()=>{if(!busy)loadCorrections().catch(e=>status(e.message,true));};
  $('corrections-previous').onclick=()=>{if(busy)return;correctionOffset=Math.max(0,correctionOffset-100);loadCorrections().catch(e=>status(e.message,true));};
  $('corrections-next').onclick=()=>{if(busy)return;correctionOffset+=100;loadCorrections().catch(e=>status(e.message,true));};
  $('logout').onclick=async()=>{if(busy)return;lock(true);try{await api('/api/logout',{});clearSession();status('Signed out. Owner session revoked.');$('capability').focus();}catch(e){if(e.status===401){clearSession();status('Session expired. Log in again.');}else status('Sign out failed: '+e.message,true);}finally{lock(false);}};
  $('login').onsubmit=async event=>{event.preventDefault();try{const capability=$('capability').value;$('capability').value='';const s=await api('/api/login',{capability});csrf=s.csrf;await start();status('Owner session established.');}catch(e){status(e.message,true);}};
  $('filter').oninput=renderList;$('outcome-filter').onchange=renderList;$('field').onchange=fieldChanged;
  $('proposal').onsubmit=event=>{event.preventDefault();try{const f=common(formData('proposal'));f.anchor=detail.packet.candidates[Number(f.anchor)]?.['local-identity-anchor'];if($('anchor').value==='')f.anchor=null;mutate('/api/proposals',proposal(detail,f,crypto.randomUUID()));}catch(e){status(e.message,true);}};
  $('publication-form').onsubmit=event=>{event.preventDefault();try{const f=common(formData('publication-form'));f.visual=$('visual').checked;f.substantive=$('substantive').checked;mutate('/api/publication',publication(detail,f,crypto.randomUUID()));}catch(e){status(e.message,true);}};
  $('retry').onclick=()=>{if(pending)mutate(pending.path,pending.request);};$('reload').onclick=()=>{if(selected)openCase(selected);};
  start().catch(e=>status(e.message,true));
})();
