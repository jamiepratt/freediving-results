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
  if (typeof module !== 'undefined') { module.exports={scalar,proposal,publication}; return; }
  const $=id=>document.getElementById(id);
  let csrf=null, packets=[], detail=null, selected=null, pending=null, busy=false, generation=0;
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
  function lock(value){busy=value;document.querySelectorAll('button').forEach(b=>b.disabled=value);}
  async function mutate(path,request){
    if(busy)return;
    pending={path,request};$('retry').hidden=true;lock(true);
    try{await api(path,request);pending=null;await loadDetail(selected);status('Saved. Audit history and current revisions reloaded.');}
    catch(e){status(e.status===409?'Conflict: '+e.message+'. Reload this case before preparing a new action.':e.message,true);$('retry').hidden=!!e.status;$('reload').hidden=false;}
    finally{lock(false);}
  }
  function renderList(){
    const filter=$('filter').value.toLowerCase(),outcome=$('outcome-filter').value;
    const visible=packets.filter(p=>(readable(p.target.payload.parsed)+' '+p.outcome).toLowerCase().includes(filter)&&(!outcome||p.outcome===outcome));
    $('cases').replaceChildren();$('case-count').textContent=visible.length+' of '+packets.length+' comparison cases';
    visible.forEach(p=>{const b=node('button',p.target.payload.parsed?.['source-name']||'Missing parsed source name','case');b.append(node('small',human(p.outcome)));b.onclick=()=>openCase(p.target);$('cases').append(b);});
    if(!visible.length)$('cases').append(node('p','No cases match these filters.'));
  }
  function expandable(title,value){const d=node('details');d.append(node('summary',title),structure(value));return d;}
  function renderDetail(d,e){
    const t=d.packet.target, parsed=t.payload.parsed||{}, fields=d.effective.fields||{};
    $('case-title').textContent=parsed['source-name']||'Missing parsed source name';
    $('revision').textContent='Review revision '+d.effective.revision+' · Extraction revision '+d.publication.revision+' · Identity '+readable(d.effective.identity);
    $('comparison').replaceChildren();
    const table=node('table'),head=node('tr');['Field','Original parsed','Effective approved'].forEach(x=>head.append(node('th',x)));table.append(head);
    Object.keys({...parsed,...fields}).forEach(k=>{const row=node('tr');[human(k),readable(parsed[k]),readable(fields[k])].forEach(x=>row.append(node('td',x)));table.append(row);});$('comparison').append(table);
    $('evidence').replaceChildren(structure(e));
    $('uncertainties').replaceChildren(structure(d.packet.uncertainties),expandable('Parser status and errors',{'parse-status':t.payload['parse-status'],errors:t.payload.errors}));
    $('candidates').replaceChildren();$('anchor').replaceChildren(new Option('Select an inspected candidate',''));
    (d.packet.candidates||[]).forEach((c,i)=>{
      const card=node('section',undefined,'candidate');card.append(node('h4',c.observations.map(o=>o.payload.parsed?.['source-name']||'Unknown name').join(' / ')),node('p','Retrieval signals: '+readable(c.signals)));
      c.observations.forEach(o=>{card.append(expandable('Source comparison and exact provenance',o));const b=node('button','Inspect candidate source lines');b.type='button';b.onclick=async()=>{try{const data=await api('/api/evidence?'+targetQuery(o));card.append(expandable('Inspected source evidence',data));}catch(err){status(err.message,true);}};card.append(b);});$('candidates').append(card);
      $('anchor').append(new Option(c.observations[0].payload.parsed?.['source-name']+' · '+c['source-sha256'].slice(0,12),String(i)));
    });
    if(!d.packet.candidates?.length)$('candidates').append(node('p','No supported candidate retrieved. Unknown and no-match do not establish distinct identities.'));
    $('field').replaceChildren(...Object.keys(fields).filter(k=>fields[k]===null||typeof fields[k]!=='object').map(k=>new Option(human(k),k)),new Option('Identity outcome','identity'));
    $('page').value=e.coordinates?.page||e['source-lines']?.[0]?.page||'';$('line').value=e.coordinates?.line||e['source-lines']?.[0]?.line||'';
    $('visual').checked=false;$('substantive').checked=false;$('publication-reason').value='';
    $('publication-state').textContent='Automated prerequisites: '+(d.publication['ready?']?'ready':'blocked')+'. Currently eligible: '+(d.publication['eligible?']?'yes':'no')+'. '+readable(d.publication.reasons);
    $('audit').replaceChildren();
    (d.history||[]).forEach(h=>{
      const item=node('article',undefined,'event');item.append(node('h4',human(h.action)+' · '+h.id),structure(Object.fromEntries(Object.entries(h).filter(([k])=>k!=='request'))));
      if(h.action==='propose' && !d.history.some(x=>x['proposal-id']===h.id)){['approve','reject'].forEach(action=>{const b=node('button',human(action));b.type='button';b.onclick=()=>decision(action,h.id);item.append(b);});}
      if(h.action==='approve' && Object.values(d.effective.active||{}).includes(h.id)){const b=node('button','Reverse approval');b.type='button';b.onclick=()=>decision('reverse',h.id);item.append(b);}$('audit').append(item);
    });
    if(!d.history?.length)$('audit').append(node('p','No private review proposals or decisions.'));
    $('publication-history').replaceChildren(structure(d['publication-history']));fieldChanged();$('detail').hidden=false;
  }
  async function loadDetail(t){const ticket=++generation,q=targetQuery(t);try{const [d,e]=await Promise.all([api('/api/detail?'+q),api('/api/evidence?'+q)]);if(ticket!==generation)return false;detail=d;renderDetail(d,e);return true;}catch(error){if(ticket!==generation)return false;throw error;}}
  async function openCase(t){if(busy)return;selected=t;detail=null;$('detail').hidden=true;pending=null;$('retry').hidden=true;status('Loading case...');try{if(!await loadDetail(t))return;status('Inspect original evidence before proposing a change.');$('case-title').focus();}catch(e){$('detail').hidden=true;status(e.message,true);}}
  function fieldChanged(){const f=$('field').value,identity=f==='identity';$('scalar-fields').hidden=identity;$('identity-fields').hidden=!identity;if(!identity){const v=detail.effective.fields[f];$('value-type').value=v===null?'unknown':typeof v==='number'?'number':typeof v==='boolean'?'boolean':'text';$('value').value=v??'';}}
  async function decision(action,id){try{const f=common({reason:$('decision-reason').value});const request={...audit(f,crypto.randomUUID(),detail.effective.revision),action,[action==='reverse'?'event-id':'proposal-id']:id};await mutate('/api/decisions',request);}catch(e){status(e.message,true);}}
  async function start(){const s=await api('/api/session');if(!s.authenticated){$('login-panel').hidden=false;$('workspace').hidden=true;return;}csrf=s.csrf;$('login-panel').hidden=true;$('workspace').hidden=false;const result=await api('/api/candidates');packets=result.packets;$('rubric').replaceChildren(structure(result.rubric));$('mode').textContent=s.demo?'SYNTHETIC DEMO · Owner only':'PRIVATE LOCAL · Owner only';renderList();status('Select a comparison case to inspect its evidence.');}
  $('login').onsubmit=async event=>{event.preventDefault();try{const capability=$('capability').value;$('capability').value='';const s=await api('/api/login',{capability});csrf=s.csrf;await start();status('Owner session established.');}catch(e){status(e.message,true);}};
  $('filter').oninput=renderList;$('outcome-filter').onchange=renderList;$('field').onchange=fieldChanged;
  $('proposal').onsubmit=event=>{event.preventDefault();try{const f=common(formData('proposal'));f.anchor=detail.packet.candidates[Number(f.anchor)]?.['local-identity-anchor'];if($('anchor').value==='')f.anchor=null;mutate('/api/proposals',proposal(detail,f,crypto.randomUUID()));}catch(e){status(e.message,true);}};
  $('publication-form').onsubmit=event=>{event.preventDefault();try{const f=common(formData('publication-form'));f.visual=$('visual').checked;f.substantive=$('substantive').checked;mutate('/api/publication',publication(detail,f,crypto.randomUUID()));}catch(e){status(e.message,true);}};
  $('retry').onclick=()=>{if(pending)mutate(pending.path,pending.request);};$('reload').onclick=()=>{if(selected)openCase(selected);};
  start().catch(e=>status(e.message,true));
})();
