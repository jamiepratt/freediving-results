'use strict';
const $ = id => document.getElementById(id);
let offset = 0, total = 0, active = 'browse', queueOffset = 0, comparisonOffset = 0, comparisonTotal = 0;
let routeOffset = 0;
const routeAuthorities = new Map();
function cell(text, tag='td') { const n=document.createElement(tag); n.textContent=text==null?'unknown':String(text); return n; }
function heading(text) { const n=document.createElement('h3'); n.textContent=text; return n; }
function jsonBlock(value) { const n=document.createElement('pre'); n.textContent=JSON.stringify(value,null,2); return n; }
async function fetchJson(path) { const r=await fetch(path,{credentials:'same-origin',cache:'no-store'}); if(r.status===401||r.status===403){$('presentation-status').textContent='Owner authentication expired or denied. Sign in and reload to verify the active snapshot.';if(r.status===401)location.href='/login';throw new Error('Owner authentication expired or denied');} if(!r.ok)throw new Error(`HTTP ${r.status}`); return r.json(); }
function entries(parent, object) { const dl=document.createElement('dl'); for(const [k,v] of Object.entries(object)){dl.append(cell(k,'dt'),cell(v==null?'unknown':typeof v==='object'?JSON.stringify(v):v,'dd'));} parent.append(dl); }
function renderPresentationStatus(overview,sources){
  const area=$('presentation-status');area.replaceChildren();
  const remote=location.pathname==='/owner-evidence'||location.pathname?.startsWith('/owner-evidence/');
  area.append(heading(remote ? 'Remote presentation' : 'Local demo presentation'));
  const snapshot=remote ? `Remote active snapshot: ${overview.snapshot_sha256 || 'unknown'}` : `Local demo snapshot: ${overview.snapshot_sha256 || 'unknown'}. Remote active snapshot: unavailable`;
  area.append(cell(`${snapshot}. Verified cutoff: ${overview.cutoff || 'unknown'}. Coverage: ${overview.coverage || 'unknown'}. Accepted distinct attempts: ${overview.confirmed_distinct_attempts ?? 'unknown'}.`, 'p'));
  area.append(cell(`Local completed revision: unavailable. Pending transfer: unavailable. Failed activation and retry: unavailable. ${remote ? 'This remote page has no private local checkpoint sync; check the local run state before retrying. A completed local run does not replace the remote active snapshot.' : 'This demo reads a snapshot directly; check the local run state and remote owner page for presentation status.'}`, 'p'));
  const gaps=sources.filter(source=>source.status!=='included');
  area.append(cell(gaps.length ? 'Explicit source gaps:' : 'No source gaps listed by this snapshot; coverage may still be partial.', 'p'));
  if(gaps.length){const list=document.createElement('ul');for(const source of gaps)list.append(cell(`${source.source_name}: ${source.status}${source.reason ? ' - '+source.reason : ''}`, 'li'));area.append(list);}
}
async function loadOverview(){
  $('presentation-status').textContent='Checking active snapshot...';
  const o=await fetchJson('/api/overview');
  const sources=await fetchJson('/api/sources');
  renderPresentationStatus(o,sources);
  const selectedDates=new Set(sources.filter(s=>s.source_schema==='aida-selected-html-packet/v1'||s.source_name.startsWith('aida-')).map(s=>s.source_name));
  const count=(predicate)=>o.counts.filter(predicate).reduce((total,row)=>total+row.records,0);
  const source=(name,collection,kind)=>count(row=>row.source_name===name&&row.collection===collection&&(!kind||row.kind===kind));
  $('overview').replaceChildren();
  entries($('overview'),{
    coverage:o.coverage,cutoff:o.cutoff,evidence_records:count(()=>true),
    candidate_source_positions:o.candidate_source_positions,
    observation_version_records:o.observation_version_records,
    aida_selected_date_positions:count(row=>(selectedDates.has(row.source_name)||row.source_name.startsWith('aida-'))&&row.collection==='positions'&&row.kind==='candidate_position'),
    ffessm_ranking_positions:source('ffessm-rankings','positions','candidate_position'),
    ffessm_daily_positions:source('ffessm-daily','observations','candidate_position'),
    ffessm_printed_field_correspondences:source('ffessm-correspondences','relationships','relationship'),
    ffessm_unmatched_daily_positions:source('ffessm-correspondences','unmatched_daily','gap'),
    apnea_gia_team_aggregates:source('apnea-file-reconciliation','gia_team.rows','aggregate'),
    san_mauro_2026_team_aggregates:source('apnea-file-reconciliation','san_mauro.rows','aggregate'),
    san_mauro_2025_individual_positions:source('san-mauro-jpg','positions','candidate_position'),
    san_mauro_2025_combined_aggregates:source('san-mauro-jpg','positions','aggregate'),
    eindhoven_result_rows:source('eindhoven-noxy5','result_rows'),
    eindhoven_overall_aggregate_rows:source('eindhoven-noxy5','overall_rows'),
    eindhoven_endpoint_records:source('eindhoven-noxy5','endpoint_records'),
    confirmed_distinct_attempts:o.confirmed_distinct_attempts,normalized_federation:o.normalized_federation,snapshot_sha256:o.snapshot_sha256,
  });
  $('overview').append(cell('Source row counts are not attempt totals. A position can have several observation versions; mirrors, rankings, and repeated acquisitions can describe the same dive. Unresolved overlap or publisher revision direction leaves accepted distinct attempts unknown.', 'p'));
  const table=document.createElement('table');table.append(makeRow(['Source','Collection','Kind','Records'],'th'));
  for(const x of o.counts)table.append(makeRow([x.source_name,x.collection,x.kind,x.records]));
  $('counts').replaceChildren(table);
  for(const s of sources){for(const id of ['source','queue-source']){const opt=document.createElement('option');opt.value=s.source_name;opt.textContent=`${s.source_name} (${s.status}${s.reason?', '+s.reason:''})`;$(id).append(opt);}}
  if(sources.some(s=>s.source_name==='eindhoven-noxy5')){const source=await fetchJson('/api/source?name=eindhoven-noxy5');const counts=source.metadata?.counts || {};const exact={};for(const key of ['linked_result_endpoint_records','endpoint_only_records'])if(Number.isInteger(counts[key]))exact[key]=counts[key];if(Object.keys(exact).length)entries($('overview'),exact);}
}
function makeRow(values,tag='td'){const tr=document.createElement('tr');for(const value of values)tr.append(cell(value,tag));return tr;}
function safeLink(url, label) {
  if (typeof url !== 'string' || !/^https:\/\//i.test(url)) return cell(label || url || 'unknown', 'span');
  const a=document.createElement('a');
  a.href=url;
  a.textContent=label || url;
  a.target='_blank';
  a.rel='noopener noreferrer';
  return a;
}
function routeCitation(citation, receipt) {
  const wrap=document.createElement('div');
  if (!citation) return wrap;
  wrap.append(safeLink(citation.url, 'Publisher citation'), cell(` ${citation.locator || 'unknown locator'}; evidence ${citation.evidence || 'unknown'}`, 'span'));
  if (receipt?.sha256) wrap.append(cell(`; retained response SHA-256 ${receipt.sha256}`, 'span'));
  return wrap;
}
function routeGaps(gaps) {
  const wrap=document.createElement('div');
  wrap.append(cell((gaps || []).length ? gaps.join('; ') : 'No explicit gap recorded', 'span'));
  return wrap;
}
function renderRoutes(result) {
  const routes=result.items || result.routes || [];
  routeAuthorities.clear();
  for(const route of routes)routeAuthorities.set(route.id,route.authority);
  const summary=result.summary || {};
  const leadStates=Object.entries(summary.leads_by_status || {}).filter(([,count])=>count).map(([status,count])=>`${status}: ${count}`).join('; ');
  const routeStates=Object.entries(summary.routes_by_status || {}).filter(([,count])=>count).map(([status,count])=>`${status}: ${count}`).join('; ');
  $('route-summary').textContent=`${summary.route_count ?? result.total ?? routes.length} route groups; ${summary.lead_count ?? 'unknown'} leads. Lead checks: ${leadStates || 'unknown'}. Route checks: ${routeStates || 'unknown'}. Zero unchecked known leads does not establish global completeness; inaccessible routes remain gaps. Dated partial inventory checked through ${result.cutoff || 'unknown'}. Confirmed distinct attempts: ${summary.confirmed_distinct_attempts ?? 'unknown'}. Roster SHA-256 ${result.roster_sha256 || 'unknown'}.`;
  const table=document.createElement('table');
  table.append(makeRow(['Publisher / route','Status / role','Checked at','Leads / disposition','Citation / gaps'],'th'));
  for (const route of routes) {
    const publisher=document.createElement('div');
    publisher.append(cell(`${route.authority || 'unknown'} / ${route.id}`, 'span'));
    if (route.discovery_url) publisher.append(cell(' Discovery: ', 'span'), safeLink(route.discovery_url, 'publisher route'));
    const provenance=document.createElement('div');
    provenance.append(routeCitation(route.citation,route.receipt),routeGaps(route.gaps));
    const counts=route.lead_status_counts || summary.leads_by_route?.[route.id]?.by_status || {};
    const dispositions=Object.entries(counts).filter(([,count])=>count).map(([status,count])=>`${status}: ${count}`).join('; ');
    const tr=document.createElement('tr');
    for (const value of [publisher,cell(`${route.status} / ${route.role}`),cell(route.checked_at),cell(`${route.lead_count ?? summary.leads_by_route?.[route.id]?.count ?? 0} leads; ${dispositions || 'none'}`),provenance]) {
      const td=document.createElement('td');td.append(value);tr.append(td);
    }
    table.append(tr);
  }
  const scroll=document.createElement('div');scroll.className='scroll';scroll.append(table);
  $('route-list').replaceChildren(scroll);
  const select=$('route-id');
  const selected=select.value;
  select.replaceChildren();
  const all=document.createElement('option');all.value='';all.textContent='All routes';select.append(all);
  for(const route of routes){const option=document.createElement('option');option.value=route.id;option.textContent=`${route.authority} / ${route.id}`;select.append(option);}
  select.value=selected;
}
function sourceRecordLinks(records,label) {
  const wrap=document.createElement('div');
  for(const source of records || []){
    const line=document.createElement('div');
    line.append(cell(`${label}: ${source.source_name || 'unknown'} / ${source.record_path || 'source only'}; record ${source.record_id || 'unknown'}`, 'span'));
    if (/^[a-f0-9]{64}$/i.test(source.record_id || '')) {
      const button=document.createElement('button');button.type='button';button.textContent='Inspect source record';
      button.addEventListener('click',()=>run(()=>detail(source.record_id),'detail'));
      line.append(button);
    }
    wrap.append(line);
  }
  return wrap;
}
function renderRouteLeads(result) {
  routeOffset=result.offset;
  const items=result.items || [];
  const start=result.total ? result.offset+1 : 0;
  $('route-lead-summary').textContent=`${result.total} leads; showing ${start} to ${result.offset+items.length}. Dated partial roster ${result.cutoff || 'unknown'}. Exact source records are URL-proven source links only; candidate links carry no equality decision.`;
  const table=document.createElement('table');
  table.append(makeRow(['Publisher route / event','Competition date / year','Status / source role','Citation / evidence gap','Snapshot source links'],'th'));
  for(const lead of items){
    const provenance=document.createElement('div');provenance.append(routeCitation(lead.citation,lead.receipt),routeGaps(lead.gaps));
    const links=document.createElement('div');
    links.append(sourceRecordLinks(lead.exact_source_records,'Exact source record'),sourceRecordLinks(lead.candidate_source_records,'Candidate source record'));
    if(!(lead.exact_source_records || []).length && !(lead.candidate_source_records || []).length)links.append(cell('No linked snapshot source record', 'span'));
    const date=lead.competition_date ? `${lead.competition_date}${lead.competition_date_to ? ' to '+lead.competition_date_to : ''}` : 'Competition date unknown';
    const tr=document.createElement('tr');
    for(const value of [cell(`${lead.authority || routeAuthorities.get(lead.route_id) || lead.route_id} / ${lead.title}`),cell(`${date} / ${lead.competition_year || 'unknown year'}`),cell(`${lead.status} / ${lead.relationship}`),provenance,links]){
      const td=document.createElement('td');td.append(value);tr.append(td);
    }
    table.append(tr);
  }
  const scroll=document.createElement('div');scroll.className='scroll';scroll.append(table);
  $('route-lead-results').replaceChildren(scroll);
  $('route-previous').disabled=result.offset===0;
  $('route-next').disabled=result.offset+items.length>=result.total;
}
async function loadRoutes(){renderRoutes(await fetchJson('/api/routes?limit=100&offset=0'));}
async function loadRouteLeads(){
  const params=new URLSearchParams();
  for(const [key,value] of new FormData($('route-filters')))if(value)params.set(key,value);
  params.set('offset',String(routeOffset));
  renderRouteLeads(await fetchJson('/api/route-leads?'+params));
}
async function loadAffiliateNames(){
  const response=await fetch('/api/affiliate-names',{credentials:'same-origin',cache:'no-store'});
  if(response.status===503){$('affiliate-summary').textContent='No checked affiliate name input configured.';return;}
  if(response.status===401){location.href='/login';return;}
  if(!response.ok)throw new Error(`HTTP ${response.status}`);
  const input=await response.json();
  $('affiliate-summary').textContent=`${input.assertions.length} cited name assertions; ${input.gaps.length} explicit gaps. Input SHA-256 ${input.input_sha256}. Snapshot manifest SHA-256 ${input.snapshot.manifest_sha256}. Identity candidate only; no attempts imported.`;
  const sources=new Map(input.sources.map(item=>[item.source_sha256,item]));
  const area=$('affiliate-results');area.replaceChildren();
  for(const assertion of input.assertions){
    const source=sources.get(assertion.source_sha256);
    const card=document.createElement('article');card.className='source-view';
    card.append(heading(assertion.original_name));
    entries(card,{publisher_romanization:assertion.publisher_romanization,person_id:assertion.person_id,
      evidence_key:assertion.evidence_key,parser_version:assertion.parser_version,
      candidate_observation_refs:assertion.candidate_observation_refs,uncertainty:assertion.uncertainty,
      source_sha256:assertion.source_sha256,publisher:source?.publisher,source_date:source?.source_date,
      retrieved_at:source?.retrieved_at});
    card.append(heading('Exact source position'),jsonBlock(assertion.source_position));
    if(source){
      const urls=document.createElement('p');
      urls.append(cell('Discovery: ','span'),safeLink(source.discovery_url,'publisher route'),
        cell(' Final: ','span'),safeLink(source.final_url,'retained source URL'));
      card.append(urls);
      const button=document.createElement('button');button.type='button';button.textContent='Show verified original as text';
      const original=document.createElement('pre');
      button.addEventListener('click',async()=>{
        button.disabled=true;
        try{const result=await fetch('/api/affiliate-names/source/'+assertion.source_sha256,{credentials:'same-origin',cache:'no-store'});
          if(!result.ok)throw new Error(`HTTP ${result.status}`);
          original.textContent=await result.text();
        }catch(error){original.textContent=`Original unavailable: ${error.message}`;}
        finally{button.disabled=false;}
      });
      card.append(button,original);
    }
    area.append(card);
  }
  if(input.gaps.length)area.append(heading('Explicit acquisition or parser gaps'),jsonBlock(input.gaps));
}
function params(){const data=new FormData($('filters'));const p=new URLSearchParams();for(const [k,v] of data)if(v)p.set(k,v);p.set('offset',String(offset));return p;}
function sessionSummary(value){
  if(value==null)return 'unknown';
  let session=value;
  if(typeof value==='string'&&value.startsWith('{')){
    try{session=JSON.parse(value);}catch{return value.slice(0,120);}
  }
  if(session&&typeof session==='object'){
    const parts=[session.session_id==null?null:`Session ${session.session_id}`,session.event_date,session.discipline].filter(x=>x!=null&&x!=='');
    return parts.join('; ')||'Session details available in record';
  }
  return String(session).slice(0,120);
}
async function browse(){const p=params();let path='/api/'+active;if(active==='browse')path='/api/browse';if(active==='gaps'||active==='relationships')p.delete('kind');const result=await fetchJson(path+'?'+p);total=result.total;const area=$('results');const table=document.createElement('table');table.append(makeRow(['Source','Kind','Event / date','Session','Discipline','Category','Path','Review'],'th'));for(const x of result.records){const tr=makeRow([x.source_name,x.kind,`${x.event_name||'unknown'} / ${x.event_date||x.date_from||'unknown'}`,sessionSummary(x.session),x.discipline,x.category,x.record_path,x.review_status]);tr.tabIndex=0;tr.addEventListener('click',()=>detail(x.record_id));tr.addEventListener('keydown',e=>{if(e.key==='Enter')detail(x.record_id)});table.append(tr);}const scroll=document.createElement('div');scroll.className='scroll';scroll.append(table);area.replaceChildren(scroll);$('summary').textContent=`${result.total} ${active==='browse'?'records':active}; showing ${result.records.length} from ${result.offset+1}. Counts are records, not distinct attempts.`;$('previous').disabled=offset===0;$('next').disabled=offset+result.records.length>=total;}
function sourcePosition(detail) {
  const citation=detail.citation || {};
  const info=document.createElement('div');
  if((detail.source_schema==='aida-selected-html-packet/v1'||detail.source_name?.startsWith('aida-')) && detail.collection==='positions'){
    info.append(heading('Selected-date HTML result row'));
    entries(info,{selected_date:citation.date,selector:citation.selector,table:citation.table,table_number:citation.table_number,tbody:citation.tbody,tbody_row:citation.tbody_row,row:citation.row,source_sha256:detail.source_sha256,original_availability:'Original HTML restricted; retained packet is a derivative, not original HTML',same_attempt:'unknown',athlete_identity:'unknown'});
    const values=Object.fromEntries(Object.entries(detail.raw_fields || {}).map(([key,value])=>[key,value?.value ?? value]));
    info.append(heading('Printed result cells'),jsonBlock(values));
  }else if(detail.source_name==='ffessm-rankings' && detail.collection==='positions'){
    info.append(heading('FFESSM ranking PDF row'));
    info.append(cell('Ranking PDF is supplemental. Its row date is unknown; a cited daily PDF may carry the date. Printed field correspondence does not establish the same attempt.', 'p'));
    entries(info,{ranking_citation:detail.citation,source_sha256:detail.source_sha256,same_attempt:'unknown'});
  }else if(detail.source_name==='ffessm-daily' && detail.collection==='observations'){
    info.append(heading('FFESSM daily PDF row'));
    entries(info,{printed_date:detail.event_date,date_source:detail.raw?.date_source,citation:detail.citation,source_sha256:detail.source_sha256,same_attempt:'unknown'});
  }else if(detail.source_name==='san-mauro-jpg' && detail.collection==='positions'){
    info.append(heading('San Mauro 2025 printed JPG row'));
    entries(info,{printed_row:citation.printed_row,bounding_box:citation.bbox,source_sha256:citation.source_sha256 || detail.source_sha256,competition_date:detail.raw?.competition_date,classification:detail.kind==='aggregate'?'combined ranking aggregate, excluded from individual attempt counts':'individual candidate position',uncertainty:'Five clipped printed cells remain uncertain across the JPG packet; inspect the row and queue for exact affected fields'});
  }else if(detail.source_name==='apnea-file-reconciliation' && detail.kind==='aggregate'){
    info.append(heading('Apnea Academy team aggregate'));
    info.append(cell('This is a team aggregate, excluded from individual attempt counts.', 'p'));
    entries(info,{sheet_citation:detail.citation,source_sha256:detail.source_sha256});
  }else if(detail.source_name==='eindhoven-noxy5' && citation.json_pointer){
    info.append(heading('Eindhoven timing JSON row'));
    entries(info,{json_pointer:citation.json_pointer,source_sha256:citation.source_sha256 || detail.source_sha256,result_val:detail.raw_fields?.result_val,result_final_val:detail.raw_fields?.result_final_val,card_status:detail.raw_fields?.card_status,penalties_json:detail.raw_fields?.penalties_json,same_attempt:'unknown',athlete_identity:'unknown'});
  }
  return info;
}
async function detail(id){const d=await fetchJson('/api/detail/'+id);const area=$('detail');area.replaceChildren();const basic={source_name:d.source_name,source_schema:d.source_schema,collection:d.collection,kind:d.kind,event_name:d.event_name,event_date:d.event_date,date_scope:d.date_scope,session:d.session,discipline:d.discipline,category:d.category,review_status:d.review_status,record_path:d.record_path,parser_version:d.parser_version,observation_version:d.observation_version,source_object_id:d.source_object_id,acquisition_id:d.acquisition_id,input_sha256:d.input_sha256,source_sha256:d.source_sha256,snapshot_sha256:d.snapshot_sha256};entries(area,basic);area.append(sourcePosition(d));for(const [label,value] of [['Citation',d.citation],['Raw fields',d.raw_fields],['Parsed fields',d.parsed_fields],['Retained packet record',d.raw]]){area.append(heading(label),jsonBlock(value));}const button=document.createElement('button');button.textContent='Inspect cited evidence';const view=document.createElement('div');view.className='source-view';button.addEventListener('click',()=>{button.disabled=true;view.textContent='Loading cited evidence...';showSourceView(id,view).catch(e=>{view.textContent=`Source view unavailable: ${e.message}`;}).finally(()=>{button.disabled=false;});});area.append(button,view);}
async function showSourceView(id,area){
  const info=await fetchJson('/api/source-view/'+id);
  const source=document.createElement('div'),parsed=document.createElement('div');
  const restrictedHtml=info.format==='safe_html_derivative'||info.format==='cited_html_packet';
  const title=info.format==='pdf'?`Original PDF page ${info.page}`:info.format==='jpeg'?'Original JPEG':info.format==='json'?`Original JSON ${info.locator}`:info.format==='cited_html_packet'?'Cited safe HTML packet':info.format==='safe_html_derivative'?'Safe result-table derivative':'Cited evidence';
  source.append(heading(title),heading('Source SHA-256'),cell(info.source_sha256,'p'));
  if(info.derivative_sha256)source.append(heading('Derivative SHA-256'),cell(info.derivative_sha256,'p'));
  if(info.receipt&&Object.keys(info.receipt).length)source.append(heading('Acquisition receipt'),jsonBlock(info.receipt));
  if(restrictedHtml){
    if(info.selected_view)source.append(heading('Selected view'),jsonBlock(info.selected_view));
    source.append(heading('Replay gap'),cell('Original HTML is restricted. The retained safe packet is a derivative and cannot replay the original HTML.','p'));
  }
  if(info.format==='pdf'||info.format==='jpeg'){
    const img=document.createElement('img');
    img.alt=info.format==='pdf'?`Cited original PDF page ${info.page}`:'Cited original JPEG';
    img.src=info.format==='pdf'?'/api/source-view/'+id+'/page/'+info.page:'/api/source-view/'+id+'/image';
    source.append(img);
    if(info.region)source.append(heading('Cited region'),jsonBlock(info.region));
  }else source.append(heading('Cited source value'),jsonBlock(info.source_value));
  parsed.append(heading('Exact citation'),jsonBlock(info.citation),heading('Retained raw fields'),jsonBlock(info.raw_fields),heading('Parsed fields'),jsonBlock(info.parsed_fields));
  area.replaceChildren(source,parsed);
}
async function sourceDetail(){const name=$('source').value;const area=$('source-detail');if(!name){area.textContent='Select a source.';return;}const source=await fetchJson('/api/source?name='+encodeURIComponent(name));area.replaceChildren();entries(area,{source_name:source.source_name,status:source.status,reason:source.reason,sha256:source.sha256,source_sha256:source.source_sha256,source_schema:source.source_schema});area.append(heading('Retained source metadata'),jsonBlock(source.metadata||null));}
async function loadQueue(){const p=new URLSearchParams();for(const [k,v] of new FormData($('queue-filters')))if(v)p.set(k,v);p.set('offset',String(queueOffset));const q=await fetchJson('/api/queue?'+p);const overview=$('queue-overview');overview.replaceChildren();entries(overview,{coverage:q.coverage,cutoff:q.cutoff,candidate_positions:q.denominators.candidate_positions,confirmed_distinct_attempts:q.denominators.confirmed_distinct_attempts});const counts=document.createElement('p');counts.textContent=Object.entries(q.group_counts).map(([g,n])=>`${g.replaceAll('_',' ')}: ${n}`).join(' | ');overview.append(counts);const area=$('queue-results');const table=document.createElement('table');table.append(makeRow(['Group','Trigger','Evidence / unknown','Source / row / version'],'th'));for(const item of q.items){const cite=item.citation,ctx=item.context;const provenance=`Source: ${cite.source_name}; row: ${cite.record_path||'source only'}; record ID: ${cite.record_id||'none'}; source object ID: ${cite.source_object_id||'unknown'}; parser version: ${ctx.parser_version||'unknown'}; observation version: ${ctx.observation_version||'unknown'}; source SHA-256: ${ctx.source_sha256||'unknown'}; input SHA-256: ${ctx.input_sha256}`;const tr=makeRow([item.group.replaceAll('_',' '),item.trigger,`Support: ${item.supporting_evidence.join('; ')||'unknown'}; Contrary: ${item.contrary_evidence.join('; ')||'unknown'}; Unknown: ${item.unknown.join('; ')||'none recorded'}`,provenance]);tr.tabIndex=0;const inspect=()=>{if(cite.record_id)detail(cite.record_id);else {const d=$('detail');d.replaceChildren();entries(d,{source_name:cite.source_name,kind:ctx.kind,input_sha256:ctx.input_sha256});}};tr.addEventListener('click',inspect);tr.addEventListener('keydown',e=>{if(e.key==='Enter')inspect();});table.append(tr);}const scroll=document.createElement('div');scroll.className='scroll';scroll.append(table);area.replaceChildren(scroll);$('queue-summary').textContent=`${q.total} queue items; showing ${q.items.length} from ${q.total?queueOffset+1:0}. Snapshot ${q.snapshot_sha256}.`;$('queue-previous').disabled=queueOffset===0;$('queue-next').disabled=queueOffset+q.items.length>=q.total;}
function run(fn,target='summary'){fn().catch(e=>{$(target).textContent=e.message;});}
async function loadComparisons(){const result=await fetchJson('/api/comparisons?limit=25&offset='+comparisonOffset);comparisonTotal=result.total;const area=$('comparison-list');const table=document.createElement('table');table.append(makeRow(['Relationship','Status','Source / record path'],'th'));for(const item of result.items){const tr=makeRow([item.label,item.status,`${item.source_name} / ${item.record_path}`]);tr.tabIndex=0;const select=()=>run(()=>showComparison(item.id),'comparison-detail');tr.addEventListener('click',select);tr.addEventListener('keydown',e=>{if(e.key==='Enter')select();});table.append(tr);}const scroll=document.createElement('div');scroll.className='scroll';scroll.append(table);area.replaceChildren(scroll);$('comparison-summary').textContent=`${result.total} explicit relationship candidates; showing ${result.items.length} from ${result.total?comparisonOffset+1:0}. Confirmed distinct attempts: ${result.confirmed_distinct_attempts==null?'unknown':result.confirmed_distinct_attempts}. Snapshot ${result.snapshot_sha256}.`;$('comparison-previous').disabled=comparisonOffset===0;$('comparison-next').disabled=comparisonOffset+result.items.length>=result.total;}
function comparisonSide(side,index){const card=document.createElement('div');const label=side.source_name==='ffessm-rankings'?'Supplemental ranking PDF row':side.source_name==='ffessm-daily'?'Dated daily PDF row':index===0?'Retained evidence':'Imported evidence';card.append(heading(label));entries(card,{source_name:side.source_name,collection:side.collection,record_id:side.record_id,record_path:side.record_path,source_object_id:side.source_object_id,source_sha256:side.source_sha256,input_sha256:side.input_sha256,artifact_sha256:side.artifact_sha256,acquisition_id:side.acquisition_id,receipt_acquisition_id:side.source_receipt?.acquisition_id,retrieved_at:side.source_receipt?.retrieved_at,publisher:side.source_receipt?.publisher,final_url:side.source_receipt?.final_url,parser_version:side.parser_version,observation_version:side.observation_version,review_status:side.review_status,original_status:side.source_view?.status,original_reason:side.source_view?.reason,safe_derivative:side.source_view?.safe_derivative});card.append(heading('Independent citation'),jsonBlock(side.citation),heading('Printed / retained raw fields'),jsonBlock(side.raw_fields),heading('Parsed values'),jsonBlock(side.parsed_fields),heading('Recorded observation versions'),jsonBlock(side.observation_refs));if(side.collection!=='candidate_versions'&&(side.source_view?.status==='present'||side.source_view?.safe_derivative)){const button=document.createElement('button');button.textContent='Inspect cited original or safe derivative';const view=document.createElement('div');view.className='source-view';button.addEventListener('click',()=>{button.disabled=true;showSourceView(side.record_id,view).catch(e=>{view.textContent=`Source view unavailable: ${e.message}`;}).finally(()=>{button.disabled=false;});});card.append(button,view);}return card;}
async function showComparison(id){const item=await fetchJson('/api/comparison/'+id);const area=$('comparison-detail');area.replaceChildren();entries(area,{relationship_type:item.relationship.type,status:item.relationship.status,basis:item.relationship.basis,supporting_evidence:item.relationship.supporting_evidence,contrary_evidence:item.relationship.contrary_evidence,unknown:item.relationship.unknown,ranking_row_date:item.relationship.ranking_row_date,matched_daily_date:item.relationship.matched_daily_date,same_attempt:item.relationship.same_attempt,confirmed_distinct_attempts:item.confirmed_distinct_attempts,snapshot_sha256:item.snapshot_sha256});if(Object.keys(item.field_correspondences || {}).length){area.append(heading('Printed field correspondences'),jsonBlock(item.field_correspondences),cell('Ranking PDF is supplemental; matching printed fields leave attempt equality unknown.','p'));}if(item.unavailable){area.append(heading('Comparison unavailable'),cell(item.unavailable,'p'));}if(item.sides.length){const sides=document.createElement('div');sides.className='comparison-sides';item.sides.forEach((side,index)=>sides.append(comparisonSide(side,index)));area.append(sides);}area.append(heading('Parsed field differences'),jsonBlock(item.field_differences),heading('Raw field differences'),jsonBlock(item.raw_field_differences));}
async function loadRoatan(){const result=await fetchJson('/api/roatan');entries($('roatan-summary'),{current_positions:result.total,v1_observations:result.v1_observations,v2_observations:result.v2_observations,source_objects:result.source_objects,source_object_details:result.source_object_details,historical_extraction_acceptances:result.historical_extraction_acceptances,confirmed_distinct_attempts:result.confirmed_distinct_attempts,snapshot_sha256:result.snapshot_sha256});const table=document.createElement('table');table.append(makeRow(['Unit','JSON row','Name','Current parser','Review'],'th'));for(const item of result.items){const tr=makeRow([item.unit,item.index,item.name,item.parser_version,item.review_status]);tr.tabIndex=0;const show=()=>run(()=>showRoatan(item.unit,item.index),'roatan-detail');tr.addEventListener('click',show);tr.addEventListener('keydown',e=>{if(e.key==='Enter')show();});table.append(tr);}const scroll=document.createElement('div');scroll.className='scroll';scroll.append(table);$('roatan-list').replaceChildren(scroll);}
async function showRoatan(unit,index){const item=await fetchJson('/api/roatan/'+unit+'/'+index);const area=$('roatan-detail');area.replaceChildren();entries(area,{unit:item.unit,json_row_zero_based:item.index,name:item.name,declared_depth:item.declared_depth,raw_DEPTH:item.raw_depth,FINAL_DEPTH:item.final_depth,penalty:item.penalty,status:item.status,notes:item.notes,current_position_review:item.position_review_status,source_object_id:item.source_object_id,source_sha256:item.source_sha256,same_attempt:item.same_attempt,athlete_identity:item.athlete_identity,overlap:item.overlap});area.append(heading('Exact current citation'),jsonBlock(item.citation));const button=document.createElement('button');button.textContent='Inspect exact original JSON row';const view=document.createElement('div');view.className='source-view';button.addEventListener('click',()=>{button.disabled=true;showSourceView(item.position_record_id,view).catch(e=>{view.textContent=`Source view unavailable: ${e.message}`;}).finally(()=>{button.disabled=false;});});area.append(button,view);const sides=document.createElement('div');sides.className='comparison-sides';for(const version of ['v1','v2']){const data=item.versions[version];if(!data)continue;const side=document.createElement('div');side.append(heading(`Parser ${version}`));entries(side,{parser_version:data.parser_version,observation_version:data.observation_version,review_status:data.review_status,source_object_id:data.source_object_id,source_sha256:data.source_sha256,record_id:data.record_id});side.append(heading('Citation'),jsonBlock(data.citation),heading('Publisher raw fields'),jsonBlock(data.raw_fields),heading('Parsed fields'),jsonBlock(data.parsed_fields));sides.append(side);}area.append(sides,heading('v1 to v2 parsed changes'),jsonBlock(item.parsed_field_changes),heading('Historical extraction acceptance'),jsonBlock(item.historical_extraction));}
let decisionOffset=0, decisionTotal=0, decisionPageSize=25, decisionRevision=null, decisionCsrf=null, decisionPreview=null, decisionRetryKey=null;
function decisionCard(item){
  const card=document.createElement('article');card.className='decision-card';
  const button=document.createElement('button');button.type='button';button.textContent=`Inspect ${item.id}`;
  button.addEventListener('click',()=>run(()=>inspectDecision(item.id),'decision-detail'));
  card.append(button);
  entries(card,{type:item.type,source:item.source_name || item.source,status:item.status,provider_confidence:item.provider_confidence,proposed:item.proposed});
  return card;
}
function renderDecisions(response){
  decisionRevision=response.revision;decisionCsrf=response.csrf_token || null;
  decisionTotal=response.total ?? response.items.length;
  const scored=[],scoreless=[],other=[];
  for(const [index,item] of response.items.entries()){
    if(item.status==='pending' && Number.isFinite(item.provider_confidence))scored.push({item,index});
    else if(item.status==='pending')scoreless.push(item);
    else other.push(item);
  }
  for(const item of response.scoreless_items || []){
    if(item.status==='pending')scoreless.push(item);
    else other.push(item);
  }
  scored.sort((a,b)=>a.item.provider_confidence-b.item.provider_confidence || a.index-b.index);
  for(const [id,items] of [['decision-scored',scored.map(x=>x.item)],['decision-scoreless',scoreless],['decision-automatic',other]]){
    const area=$(id);area.replaceChildren();
    if(!items.length)area.append(cell('No decisions in this group.', 'p'));
    for(const item of items)area.append(decisionCard(item));
  }
  const snapshot=response.active_snapshot_sha256 || response.snapshot_sha256 || response.items[0]?.active_snapshot_sha256;
  $('decision-summary').textContent=`${decisionTotal} decisions; ${response.scoreless_total ?? scoreless.length} scoreless decisions. ${response.score_note || 'Provider confidence is uncalibrated, not measured accuracy.'} Decision revision ${response.revision}; bound evidence snapshot ${snapshot || 'unknown'}.`;
  $('decision-previous').disabled=decisionOffset===0;
  $('decision-next').disabled=decisionOffset+decisionPageSize>=Math.max(decisionTotal-(response.scoreless_total || 0),response.scoreless_total || 0);
}
async function loadDecisions(){
  const query=new URLSearchParams();
  for(const [key,value] of new FormData($('decision-filters')))if(value)query.set(key,value);
  if(!query.has('status'))query.set('status','');
  if(!query.has('limit'))query.set('limit','25');
  decisionPageSize=Number(query.get('limit'));
  query.set('offset',String(decisionOffset));
  const response=await fetchJson('/api/decisions?'+query);
  renderDecisions(response);
  $('decision-workspace').hidden=false;
}
async function loadDecisionAuditSample(){
  const sample=await fetchJson('/api/decisions/audit-sample?limit=10');
  const area=$('decision-audit');area.replaceChildren();
  area.append(cell(`${sample.sample_size} of ${sample.population_size} automatic approvals in this nonblocking audit sample. ${sample.sampling_basis}.`, 'p'));
  for(const item of sample.items)area.append(decisionCard(item));
}
function renderDecisionDetail(item){
  decisionRevision=item.store_revision ?? item.revision ?? decisionRevision;
  decisionPreview=null;decisionRetryKey=null;
  const area=$('decision-detail');area.replaceChildren(heading(`Decision ${item.id}`));
  entries(area,{type:item.type,subject_id:item.subject_id,source:item.source_name || item.source,status:item.status,effective_status:item.effective_status,provider_confidence:item.provider_confidence,rule_version:item.rule_version,model_version:item.model_version,policy_version:item.policy_version,snapshot_sha256:item.snapshot_sha256,active_snapshot_sha256:item.active_snapshot_sha256,binding_revision:item.binding_revision,store_revision:item.store_revision,canonical_projection_status:item.canonical_projection_status});
  for(const [label,value] of [['Original evidence',item.original],['Proposed value',item.proposed],['Selected option',item.selected_option],['Competing options',item.competing_options],['Supporting and conflicting evidence',item.evidence],['Dependencies',item.depends_on],['Missing evidence',item.missing_evidence_ids],['Correction',item.correction],['Decision history',item.history]])area.append(heading(label),jsonBlock(value ?? null));
  const actions=document.createElement('div');actions.className='decision-actions';
  const effective=item.effective_status || item.status;
  const available=effective==='pending'?['approve','reject']:['automatic_approved','human_approved','human_corrected'].includes(effective)?['reverse']:[];
  for(const action of available){const button=document.createElement('button');button.type='button';button.textContent=`Preview ${action === 'reverse' ? 'Reverse' : action}`;button.addEventListener('click',()=>run(()=>previewDecisionAction(item.id,action),'decision-detail'));actions.append(button);}
  const preview=document.createElement('div');preview.id='decision-preview';
  area.append(actions,preview);
}
async function inspectDecision(id){renderDecisionDetail(await fetchJson('/api/decisions/'+encodeURIComponent(id)));}
async function previewDecisionAction(id,action){
  const result=await fetchJson('/api/decisions/'+encodeURIComponent(id)+'/preview?action='+encodeURIComponent(action));
  decisionPreview={id,action,revision:result.revision};decisionRetryKey=null;
  const area=$('decision-preview');area.replaceChildren(heading(`${action} impact preview`));
  entries(area,{decision_id:result.decision_id,revision:result.revision,affected_decisions:result.affected_decisions,affected_groups:result.affected_groups,canonical_projection_status:result.canonical_projection_status});
  area.append(heading('Before'),jsonBlock(result.before),heading('After'),jsonBlock(result.after));
  const reason=document.createElement('input');reason.type='text';reason.maxLength=500;reason.placeholder='Reason for audit history';reason.setAttribute?.('aria-label','Reason for decision action');
  const commit=document.createElement('button');commit.type='button';commit.textContent=`Confirm ${action}`;
  commit.addEventListener('click',()=>run(()=>submitDecisionAction(id,action,reason.value),'decision-action-status'));
  const status=document.createElement('p');status.id='decision-action-status';status.setAttribute?.('role','status');
  area.append(reason,commit,status);
}
async function submitDecisionAction(id,action,reason){
  if(!decisionPreview || decisionPreview.id!==id || decisionPreview.action!==action)throw new Error('Preview this action before confirming.');
  if(!decisionCsrf)throw new Error('Reload decisions to obtain an authenticated write token.');
  const key=decisionRetryKey || crypto.randomUUID();decisionRetryKey=key;
  const result=await fetch('/api/decisions/'+encodeURIComponent(id)+'/actions',{
    method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-Freediving-CSRF':decisionCsrf},
    body:JSON.stringify({action,expected_revision:decisionPreview.revision,idempotency_key:key,reason,csrf_token:decisionCsrf}),
  });
  if(result.status===401){location.href='/login';return;}
  if(!result.ok){
    let message=result.status===403?'Owner access expired or denied. Refresh this private page after signing in.':`HTTP ${result.status}`;
    if(result.status!==403)try{const body=await result.json();message=body.error || body.message || message;}catch{}
    if(result.status===409){decisionPreview=null;message=`Revision changed. Reload and preview again. ${message}`;}
    throw new Error(message);
  }
  renderDecisionDetail(await result.json());
  await loadDecisions();
}
document.addEventListener('DOMContentLoaded',()=>{run(loadRoatan,'roatan-detail');run(loadOverview);run(browse);run(loadQueue,'queue-summary');run(loadComparisons,'comparison-summary');$('comparison-previous').addEventListener('click',()=>{comparisonOffset=Math.max(0,comparisonOffset-25);run(loadComparisons,'comparison-summary');});$('comparison-next').addEventListener('click',()=>{comparisonOffset+=25;run(loadComparisons,'comparison-summary');});$('filters').addEventListener('submit',e=>{e.preventDefault();offset=0;run(browse);});$('queue-filters').addEventListener('submit',e=>{e.preventDefault();queueOffset=0;run(loadQueue,'queue-summary');});$('queue-previous').addEventListener('click',()=>{queueOffset=Math.max(0,queueOffset-Number($('queue-filters').elements.limit.value));run(loadQueue,'queue-summary');});$('queue-next').addEventListener('click',()=>{queueOffset+=Number($('queue-filters').elements.limit.value);run(loadQueue,'queue-summary');});$('show-source').addEventListener('click',()=>run(sourceDetail));for(const name of ['candidates','gaps','relationships'])$(name).addEventListener('click',()=>{active=name==='candidates'?'browse':name;if(name==='candidates')document.querySelector('[name=kind]').value='candidate_position';offset=0;run(browse);});$('previous').addEventListener('click',()=>{offset=Math.max(0,offset-Number(document.querySelector('[name=limit]').value));run(browse);});$('next').addEventListener('click',()=>{offset+=Number(document.querySelector('[name=limit]').value);run(browse);});});
document.addEventListener('DOMContentLoaded',()=>{
  run(async()=>{await loadRoutes();await loadRouteLeads();},'route-summary');
  run(loadAffiliateNames,'affiliate-summary');
  loadDecisions().catch(()=>{$('decision-workspace').hidden=true;});
  $('decision-filters').addEventListener('submit',event=>{event.preventDefault();decisionOffset=0;run(loadDecisions,'decision-summary');});
  $('decision-previous').addEventListener('click',()=>{decisionOffset=Math.max(0,decisionOffset-decisionPageSize);run(loadDecisions,'decision-summary');});
  $('decision-next').addEventListener('click',()=>{decisionOffset+=decisionPageSize;run(loadDecisions,'decision-summary');});
  $('decision-audit-button').addEventListener('click',()=>run(loadDecisionAuditSample,'decision-audit'));
  $('route-filters').addEventListener('submit',event=>{event.preventDefault();routeOffset=0;run(loadRouteLeads,'route-lead-summary');});
  $('route-previous').addEventListener('click',()=>{routeOffset=Math.max(0,routeOffset-Number($('route-filters').elements.limit.value));run(loadRouteLeads,'route-lead-summary');});
  $('route-next').addEventListener('click',()=>{routeOffset+=Number($('route-filters').elements.limit.value);run(loadRouteLeads,'route-lead-summary');});
});
