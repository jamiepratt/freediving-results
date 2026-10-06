"""Owned HTTP boundary for sporting authority, separate from existing decisions."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import threading
import time
from urllib.parse import parse_qs
from private_attempt_inspector import _remaining, READ_BUDGET_SECONDS

from sporting_authority import SportingAuthority, ConflictError, canonical, digest, private_bytes
from unified_evidence_query import SnapshotQuery

PREFIX = '/owner-evidence/api/sporting-authority'
CURRENT = PREFIX + '/current'
MAX_STAGE = 2 * 1024 * 1024
PAGE = b'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sporting source reviews</title><link rel="stylesheet" href="/owner-evidence/assets/sporting.css">
<script src="/owner-evidence/assets/sporting.js" defer></script></head><body><main>
<a href="/owner-evidence">Private evidence workspace</a><h1>Sporting source reviews</h1>
<p>Review exact source meaning, finality and cited rules first. Select the exact cohort separately, then approve its publication.
Extraction review, same-attempt decisions, athlete identity and public eligibility retain their independent authority.</p>
<p>Supported scope: 2026 pool DNF women. Unknown facts stay explicit. Disqualified achieved distances remain hypothetical.</p>
<p><a href="/owner-evidence/sporting/aida-diff">Open complete AIDA June 3 women retained-version comparison</a></p>
<div id="status" role="status">Loading current private reviews...</div><button id="refresh">Refresh</button>
<section id="proofs"><h2>Exact upstream source proofs</h2><p id="proof-status" role="status">Loading exact current source inventory...</p><div id="proof-rows"></div><div id="proof-pages"></div></section><section id="proposals"></section></main></body></html>'''
STYLE = b'''body{font:16px system-ui;color:#17302f;background:#f5f7f5;margin:0}main{max-width:1180px;margin:32px auto;padding:24px}
h1{font-size:32px}article{background:white;border:1px solid #cad6ce;border-radius:10px;margin:24px 0;padding:24px}
table{border-collapse:collapse;width:100%;font-size:14px}td,th{border:1px solid #d7dfda;padding:10px;text-align:left;vertical-align:top}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}button{padding:10px 16px;margin:8px;border:1px solid #6c8880;border-radius:6px;background:white}
label{display:block;margin-top:16px}textarea{width:90%;min-height:60px;font:inherit}a{color:#155f52}button:disabled{opacity:.55;cursor:not-allowed}.accuracy{border-top:1px solid #cad6ce;margin-top:20px;padding-top:12px}.notice{padding:16px;background:#e7eee9}'''
SCRIPT = b'''"use strict";
const root='/owner-evidence/api/sporting-authority';let current,loadGeneration=0;const accuracyRequests=new Map();
const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
function details(title,value){const d=node('details');d.append(node('summary',title),node('pre',JSON.stringify(value,null,2)));return d;}
async function load(){const generation=++loadGeneration;++proofGeneration;current=undefined;for(const id of ['proposals','proof-rows','proof-pages'])document.getElementById(id).replaceChildren();document.getElementById('status').textContent='Loading fresh current private reviews...';document.getElementById('proof-status').textContent='Current source authority pending fresh read.';try{const r=await fetch(root+'/review',{cache:'no-store'});if(!r.ok)throw Error('Private authority unavailable ('+r.status+')');
const value=await r.json();if(generation!==loadGeneration)return;current=value;document.getElementById('status').textContent='Authority revision '+current.revision+' - '+current.proposals.length+' review proposals - current source authority '+current.currentness;
const list=document.getElementById('proposals');list.replaceChildren();if(!current.proposals.length)list.append(node('p','No sporting decisions have been staged. Public sporting ranks remain withheld.'));
for(const item of current.proposals){const p=item.proposal,card=node('article');card.append(node('h2',p.id),node('p','Stored review action: '+item.action+' - revision '+item.revision),
node('p','Effective authority: '+item.authority_status+(item.authority_status==='stale'?' - Source or owner decisions changed, or evidence expired. Public ranks are withheld.':'')),
node('p','Valid until '+p.valid_until),details('Exact selected cohort and immutable source bindings',p.evidence),details('Cited sporting rules',p.rules));
for(const row of p.publication.rows){card.append(node('h3',row.source.federation+' / '+row.source['event-id']+' / '+row['result-id']));
const table=node('table'),head=node('tr');for(const title of ['Fact','Reviewed value','Source citation'])head.append(node('th',title));table.append(head);
for(const [name,fact] of Object.entries(row.facts)){const tr=node('tr');tr.append(node('td',name),node('td',JSON.stringify(fact.value)));
const cite=node('td'),a=node('a',fact.citation.url);a.href=fact.citation.url;a.rel='noreferrer';cite.append(a,node('pre',JSON.stringify(fact.citation,null,2)));tr.append(cite);table.append(tr);}card.append(table);
if(row.hypothetical)card.append(details('Separate disqualified achieved-distance hypothetical',row.hypothetical));}
const label=node('label','Reason for your review'),reason=node('textarea');label.append(reason);card.append(label);
const next=item.authority_status==='current'?{'stage':'source-approve','source-approve':'select-cohort','select-cohort':'publish'}[item.action]:undefined;
const labels={'source-approve':'Approve source meaning and finality','select-cohort':'Select exact cohort','publish':'Approve publication','reverse':'Withdraw sporting authority'};
for(const action of [next,...(item.action!=='stage'&&item.action!=='reverse'?['reverse']:[])].filter(Boolean)){const b=node('button',labels[action]);b.addEventListener('click',async()=>{
if(!reason.value.trim()){document.getElementById('status').textContent='Enter your review reason.';return;}b.disabled=true;try{const r=await fetch(root+'/actions',{method:'POST',headers:{'Content-Type':'application/json','X-Freediving-CSRF':current.csrf_token},
body:JSON.stringify({id:p.id,action,reason:reason.value,expected_revision:current.revision,idempotency_key:crypto.randomUUID(),csrf_token:current.csrf_token})});
if(!r.ok)throw Error('Review refused ('+r.status+'). Refresh exact current authority and check independent upstream approvals.');await load();}
catch(e){document.getElementById('status').textContent=e.message;b.disabled=false;}});card.append(b);}list.append(card);}await loadProofs(0);}
catch(e){if(generation!==loadGeneration)return;current=undefined;for(const id of ['proposals','proof-rows','proof-pages'])document.getElementById(id).replaceChildren();document.getElementById('status').textContent=e.message;document.getElementById('proof-status').textContent='Exact source authority unavailable. Refresh current proof.';}}
function fieldDifferences(previous,current,path=''){
const differences=[];for(const key of new Set([...Object.keys(previous||{}),...Object.keys(current||{})])){const a=previous?.[key],b=current?.[key],name=path?path+'.'+key:key;
if(a&&b&&typeof a==='object'&&typeof b==='object'&&!Array.isArray(a)&&!Array.isArray(b))differences.push(...fieldDifferences(a,b,name));
else if(JSON.stringify(a)!==JSON.stringify(b))differences.push({field:name,this_version:a,sibling_version:b});}return differences;}
function sourceVersions(card,row){const base={raw:row.source_review?.raw||row.raw_fields||{},parsed:row.source_review?.parsed||row.parsed_fields||{}};
const siblings=(row.retained_siblings||[]).map(version=>({...version,differences_from_this_version:fieldDifferences(base,{raw:version.raw||{},parsed:version.parsed||{}})}));
card.append(details('Same-position retained versions and differences',siblings),details('Selected date and acquisition provenance',row.source_review?.context||row.date_provenance||{}));}
function ruleMeanings(card,row){const proof=row.sporting_rule_bindings||{state:'missing',bindings:[]},section=node('section');
section.append(node('h4','Pinned sporting rules and source meanings'),node('p','Evidence: '+proof.state+' - '+(proof.reason||'Applicable rules do not establish final publication, source selection or distinctness.')));
for(const binding of proof.bindings||[]){section.append(node('p','Exact source event date: '+(binding.event_date||'unknown')+' | Policy: '+binding.policy),details('Applicable source scope',binding.scope));
for(const claim of binding.claims||[]){const doc=claim.document,cite=claim.citation,a=node('a',doc.issuer+' '+doc.edition+' - section '+cite.section+(cite.page?' - PDF page '+cite.page:''));a.href=doc.url;a.rel='noreferrer';a.target='_blank';section.append(a,
node('p',claim.claim+': '+claim.interpretation+' | Rule applicability: '+(claim.supported?'supported':'unverified or conflicting')),
node('p','Edition effective from '+(doc.effective_from||'unknown')+' to '+(doc.effective_until||'not specified')+' | '+claim.applicability.basis),details('Exact immutable rule and source claim',{document:doc,citation:cite,value:claim.value,reference:binding.reference,coordinates:binding.coordinates,source_view:binding.source_view,binding_sha256:binding.binding_sha256}));}
section.append(details('Unknowns requiring independent evidence',binding.unknowns),details('Conflicting evidence',binding.conflicts));}
section.append(details('Rule/source binding mismatches',proof.mismatches||[]));card.append(section);}
function publicationReview(card,row){const d=row.diagnostics?.publication||{},form=node('section');form.append(node('h4','Current publication diagnostics'));
const state=v=>v===true?'yes':v===false?'no':'unknown';form.append(node('p','Ready for validation: '+state(d.ready_for_validation)+' | Validated: '+state(d.validated)+' | Selected: '+state(d.selected)+' | Delivery: '+state(d.delivered)),
node('p','Active policy: '+(d.active_policy_version||'unknown')+' | Field review revision: '+(d.review_revision??'unknown')+' | Source accuracy revision: '+(d.source_accuracy_revision??'unknown')),
details('Substantive errors separate from accuracy',d.substantive_errors||[]),details('Validation, policy, selection and delivery blockers',{validation:d.validation_reasons,policy:d.policy_reasons,selection:d.selection_reasons,delivery:d.delivery_reasons}),details('Exact publication version binding',d.version_binding||row.reference));
const button=node('button','Publication review unavailable - preservation checkpoint required');button.disabled=true;form.append(button,node('p','A separate authority and policy checkpoint must preserve the 81 current public results before enabling publication review. HTML policy 2 can withdraw those results. Visual accuracy alone does not establish finality, source selection or publication eligibility.'));card.append(form);}
function accuracyReview(card,row,proof){
const review=row.source_review||{},form=node('section');form.className='accuracy';
form.append(node('h4','Source visual accuracy - one exact retained version'),node('p','Compare this version with its cited source. Acceptance records only faithful extraction of this row. Preliminary results, source anomalies and publication require separate decisions.'));
const available=review.enabled===true&&proof.source_review_available===true&&!!proof.pins?.canonical_scope_bindings?.source&&!!proof.context_pins_sha256;
if(!available)form.append(node('p','Source-only inspection. '+(review.reason||(!proof.source_review_available?'Source reviewer unavailable.':'Exact source binding unavailable.'))));
form.append(details('Raw and parsed values',{raw:review.raw||row.raw_fields,parsed:review.parsed||row.parsed_fields}),details('Known source anomalies',review.anomalies||row.known_anomalies||[]));
const checkbox=node('input');checkbox.type='checkbox';checkbox.accuracyAttestation=true;checkbox.checked=false;checkbox.disabled=!available;
const label=node('label','I compared this exact version and row against its cited source and confirm visual accuracy.');label.prepend(checkbox);form.append(label);
const reason=node('textarea');reason.accuracyReason=true;reason.disabled=!available;const reasonLabel=node('label','Visual accuracy review reason');reasonLabel.append(reason);form.append(reasonLabel);
const action=review.active_event?'revoke':'accept',button=node('button',action==='accept'?'Accept this exact version visual accuracy':'Revoke this exact version visual accuracy');
const enable=()=>{button.disabled=!available||!checkbox.checked||!reason.value.trim();};checkbox.addEventListener('change',enable);reason.addEventListener('input',enable);enable();
button.addEventListener('click',async()=>{if(button.disabled||!current)return;button.disabled=true;
const key=JSON.stringify([row.reference,row.coordinates,action,reason.value.trim()]);
let body=accuracyRequests.get(key);if(!body){body={id:crypto.randomUUID(),action,reference:row.reference,coordinates:row.coordinates,expected_source_binding_sha256:proof.pins.canonical_scope_bindings.source,base_revision:review.revision||0,event_id:review.active_event||null,reason:reason.value.trim(),source_visual_accuracy:true,expected_context_pins_sha256:proof.context_pins_sha256,csrf_token:current.csrf_token};accuracyRequests.set(key,body);}
let message;try{const response=await fetch(root+'/source-accuracy',{method:'POST',headers:{'Content-Type':'application/json','X-Freediving-CSRF':body.csrf_token},body:JSON.stringify(body)});
if(response.ok){accuracyRequests.delete(key);message='Exact version visual accuracy recorded. Publication authority stays separate.';}
else{if(response.status!==503)accuracyRequests.delete(key);message='Visual accuracy review refused ('+response.status+'). Refresh exact source and current review state.';}}
catch(e){message='Visual accuracy response unavailable. Refresh source state before retrying the same exact review.';}
await load();document.getElementById('proof-status').textContent=message;});form.append(button);card.append(form);}
let proofGeneration=0;
async function loadProofs(offset){const generation=++proofGeneration,container=document.getElementById('proof-rows');
container.replaceChildren();document.getElementById('proof-pages').replaceChildren();document.getElementById('proof-status').textContent='Reading fresh exact upstream proofs...';
try{const r=await fetch(root+'/proofs?offset='+offset+'&limit=20',{cache:'no-store'});if(!r.ok)throw Error('Exact upstream proofs unavailable ('+r.status+'). Retry fresh source authority.');
const proof=await r.json();if(generation!==proofGeneration)return;
document.getElementById('proof-status').textContent=proof.source_positions+' source positions - '+proof.pagination.total+' retained versions - relationship revision '+proof.relationship_revision+'. Missing canonical mappings are import gaps. Versions are not distinct sporting attempts.';
for(const row of proof.rows){const card=node('article'),ref=row.reference;card.append(node('h3',(row.federation||'Source')+' / ordinal '+ref.ordinal+' / '+ref['parser-version']),
details('Exact immutable source, parser, candidate and coordinates',{reference:ref,coordinates:row.coordinates}),details('Current independent authority and provenance',row.diagnostics),details('Current permitted upstream facts',row.upstream),details('Extracted values for source review',row.parsed_fields||{}));ruleMeanings(card,row);
const access=row.source_access||{};if(access.retained_row_id){const a=node('a','Open verified source position and retained versions');a.href='/owner-evidence/api/attempt-inspector/source/'+access.retained_row_id;a.target='_blank';card.append(a);
if(access.original_replay==='available_private_pdf'&&access.original_page){const pdf=node('a','Open cited original PDF page');pdf.href=a.href+'/page/'+access.original_page;pdf.target='_blank';card.append(node('p'),pdf);}}
card.append(node('p',access.reason||'Original access requires exact private source binding.'));sourceVersions(card,row);accuracyReview(card,row,proof);publicationReview(card,row);
if(row.relationship_review&&row.relationship_review.action==='review'){const revoke=node('button','Withdraw independent relationship review');revoke.addEventListener('click',async()=>{revoke.disabled=true;try{const result=await fetch(root+'/relationships',{method:'POST',headers:{'Content-Type':'application/json','X-Freediving-CSRF':current.csrf_token},body:JSON.stringify({assertion:row.relationship_review.assertion,action:'reverse',expected_revision:proof.relationship_revision,idempotency_key:crypto.randomUUID(),csrf_token:current.csrf_token})});if(!result.ok)throw Error('Relationship withdrawal refused ('+result.status+'). Refresh review state.');await load();}catch(e){document.getElementById('proof-status').textContent=e.message;revoke.disabled=false;}});card.append(revoke);}
if(proof.relationship_available){const form=node('details');form.append(node('summary','Independent typed relationship review'));
form.append(node('p','Review every retained version in this inventory and all possible repeat/conflicting source positions. This records distinctness and resolved conflicts for this one exact representative. Extraction acceptance and publication eligibility require their separate authorities.'));
const checkbox=node('input');checkbox.type='checkbox';const attest=node('label','I reviewed the complete exact inventory and this row is a distinct attempt with resolved source conflicts.');attest.prepend(checkbox);form.append(attest,details('Complete inventory requiring review',proof.reviewed_references));
const inputs={};for(const [key,title] of [['reason','Evidence and relationship reason'],['url','HTTPS authority citation'],['locator','Exact source locator']]){const label=node('label',title),input=node(key==='reason'?'textarea':'input');inputs[key]=input;label.append(input);form.append(label);}
const button=node('button','Record independent relationship review');button.addEventListener('click',async()=>{if(!checkbox.checked){document.getElementById('proof-status').textContent='Complete the exhaustive inventory review before recording authority.';return;}
button.disabled=true;const assertion={reference:ref,coordinates:row.coordinates,inventory_sha256:proof.inventory_sha256,canonical_binding_sha256:proof.pins.canonical_upstream_sha256,reviewed_references:proof.reviewed_references,'same-attempt':'distinct','source-conflict':'resolved',reason:inputs.reason.value,citation:{url:inputs.url.value,locator:inputs.locator.value}};
try{const result=await fetch(root+'/relationships',{method:'POST',headers:{'Content-Type':'application/json','X-Freediving-CSRF':current.csrf_token},body:JSON.stringify({assertion,action:'review',expected_revision:proof.relationship_revision,idempotency_key:crypto.randomUUID(),csrf_token:current.csrf_token})});if(!result.ok)throw Error('Independent review refused ('+result.status+'). Refresh exact inventory and inspect current conflicts.');await load();}
catch(e){document.getElementById('proof-status').textContent=e.message;button.disabled=false;}});form.append(button);card.append(form);}container.append(card);}
const pages=document.getElementById('proof-pages');for(const [label,next] of [['Previous',offset-20],['Next',offset+20]]){if(next>=0&&next<proof.pagination.total){const b=node('button',label);b.addEventListener('click',()=>loadProofs(next));pages.append(b);}}}
catch(e){if(generation===proofGeneration){container.replaceChildren();document.getElementById('proof-status').textContent=e.message;}}}
document.getElementById('refresh').addEventListener('click',load);load();'''


def retained_rows(origin, comparison, *, deadline, bind_sources=True):
    """Collect the complete pinned retained inventory, without granting authority."""
    deadline = min(deadline, time.monotonic() + READ_BUDGET_SECONDS)
    _remaining(deadline)
    rows = []
    parsers = parser_versions(comparison)
    filters = {'year': '2026', 'environment': 'pool', 'discipline': 'DNF', 'gender': 'women', 'limit': 200}
    offset = 0
    while True:
        result = origin.read_comparison({**filters, 'offset': offset}, None, deadline=deadline)
        if bind_sources:
            origin.bind_inspector_sources(result, {**filters, 'offset': offset}, deadline=deadline)
        for row in result['rows']:
            versions = row.get('retained-versions') or [{'reference': row['reference'], 'candidate': row.get('candidate', {})}]
            siblings = []
            for sibling in versions:
                sibling_reference = sibling['reference']
                sibling_parser = parsers.get((sibling_reference.get('job-id'), sibling_reference.get('artifact-sha256')))
                candidate = sibling.get('candidate') or {}
                siblings.append({'reference': {**sibling_reference, 'parser-version': sibling_parser},
                                 'coordinates': candidate.get('coordinates', row['row_coordinate']),
                                 'raw': candidate.get('raw', {}), 'parsed': candidate.get('parsed', {}),
                                 'anomalies': candidate.get('flags', []) + candidate.get('unresolved-reasons', []),
                                 'date_provenance': {'parsed_event_date': (candidate.get('parsed') or {}).get('event-date'),
                                                     'source_view': sibling.get('source', {}),
                                                     'source_access': row.get('source_access', {})}})
            for version in versions:
                reference = version['reference']
                parser = parsers.get((reference.get('job-id'), reference.get('artifact-sha256')))
                if parser is None:
                    raise ValueError('exact sporting parser version unavailable')
                rows.append({'reference': {**reference, 'parser-version': parser},
                             'coordinates': version.get('candidate', {}).get('coordinates', row['row_coordinate']),
                             'year': str(row['year']), 'environment': row['environment'],
                             'discipline': row['discipline'].lower(), 'gender': row['gender'],
                             'federation': row.get('federation', row.get('source', {}).get('federation', 'unknown')),
                             'source_access': row.get('source_access', {}),
                             'retained_siblings': siblings,
                             'raw_fields': version.get('candidate', {}).get('raw', row.get('raw_fields', {})),
                             'known_anomalies': version.get('candidate', {}).get('flags', []) + version.get('candidate', {}).get('unresolved-reasons', []),
                             'date_provenance': {'parsed_event_date': version.get('candidate', {}).get('parsed', {}).get('event-date'),
                                                 'source_view': version.get('source', {})},
                             'parsed_fields': version.get('candidate', {}).get('parsed', row.get('parsed_fields', {})),
                             'upstream': {}, 'diagnostics': {}})
        offset += len(result['rows'])
        total = result.get('pagination', {}).get('total', offset)
        if len(rows) > 400:
            raise ValueError('exact sporting inventory exceeds bound')
        if offset >= total:
            break
        if not result['rows']:
            raise ValueError('exact sporting inventory incomplete')
    _remaining(deadline)
    return rows


def _review_inventory(origin, comparison, deadline):
    """Join both independent reads inside one request deadline; export no partial context."""
    _remaining(deadline)
    with ThreadPoolExecutor(max_workers=1, thread_name_prefix='sporting-inventory') as pool:
        pending = pool.submit(retained_rows, origin, comparison, deadline=deadline)
        try:
            authority = origin.status_authority(deadline=deadline)
            if authority is None:
                raise ValueError('fresh owner/source status unavailable')
            rows = pending.result(timeout=_remaining(deadline))
            return authority, rows
        finally:
            pending.cancel()


def live_context(origin, snapshot_dir, env, config_path, *, review=False, deadline=None):
    """Read exact active pins. Legacy same-attempt approvals grant no sporting facts."""
    deadline = min(deadline if deadline is not None else float("inf"), time.monotonic() + READ_BUDGET_SECONDS)
    _remaining(deadline)
    config = json.loads(private_bytes(config_path, 65536))
    with SnapshotQuery(snapshot_dir) as snapshot:
        snapshot_sha = snapshot.manifest['snapshot_sha256']
        if snapshot_sha != origin.query.manifest['snapshot_sha256']:
            raise ValueError('active sporting snapshot changed')
    owner = origin.decisions
    if owner is None or owner.active_snapshot_sha256 != snapshot_sha:
        raise ValueError('current owner/source authority unavailable')
    binding = owner._binding()
    before = owner.revision
    pins = {'snapshot_sha256': snapshot_sha, 'owner_revision': before,
            'owner_binding_revision': binding['revision'],
            'source_bundle_sha256': origin.source_bundle_sha256,
            'service_config_sha256': hashlib.sha256(private_bytes(config_path, 65536)).hexdigest()}
    comparison_config = env.get('OWNER_EVIDENCE_COMPARISON_CONFIG')
    if comparison_config:
        from private_attempt_inspector import _private_bytes, _pinned
        body = _private_bytes(comparison_config, 65536)
        comparison = json.loads(body)
        _pinned(comparison['packet'])
        pins['comparison_config_sha256'] = hashlib.sha256(body).hexdigest()
        pins['packet_sha256'] = comparison['packet']['sha256']
    rules = {}
    for sha, item in config.get('rule_objects', {}).items():
        if (set(item) != {'path', 'sha256', 'url'} or item['sha256'] != sha
                or hashlib.sha256(private_bytes(item['path'])).hexdigest() != sha):
            raise ValueError('pinned sporting rule changed')
        rules[sha] = item['url']
    rows = []
    if review:
        if origin.comparison_reader is None or not comparison_config:
            raise ValueError('exact sporting rows unavailable')
        authority, rows = _review_inventory(origin, comparison, deadline)
        pins['owner_authority_sha256'] = digest(authority)
    reader = getattr(origin, 'sporting_proof_reader', None)
    if reader is None:
        pins['canonical_upstream_status'] = 'unavailable'
        for row in rows:
            row['diagnostics'] = {'capability': {'state': 'unavailable',
                'reason': 'Restricted exact source proof reader is not configured; no independent authority granted'}}
    else:
        request = [{'reference': row['reference'], 'coordinates': row['coordinates']} for row in rows]
        proof = reader(request, deadline=deadline)
        pins['canonical_upstream_sha256'] = proof['binding_sha256']
        pins['canonical_upstream_config_sha256'] = proof['config_sha256']
        pins['canonical_scope_bindings'] = proof['scope_bindings']
        for row, verified in zip(rows, proof['rows']):
            row.update({k: v for k, v in verified.items() if k not in ('reference', 'coordinates')})
        fresh = reader([], deadline=deadline)
        if any(proof[key] != fresh[key] for key in ('binding_sha256', 'config_sha256', 'scope_bindings')):
            raise ConflictError('exact upstream authority changed while collecting sporting context')
    if owner.revision != before or owner.active_snapshot_sha256 != snapshot_sha:
        raise ConflictError('owner source changed while collecting sporting context')
    _remaining(deadline)
    context = {'pins': pins, 'rows': rows, 'rules': rules}
    rules_config = env.get('OWNER_EVIDENCE_SPORTING_RULES_CONFIG')
    if rules_config:
        from sporting_rule_bindings import read_config, apply_catalog
        catalog, rule_pins = read_config(rules_config, private_bytes)
        apply_catalog(context, catalog, rule_pins)
        # Recheck immutable bytes after assembling the complete context.
        if read_config(rules_config, private_bytes)[1] != rule_pins:
            raise ConflictError('sporting rule meaning changed while collecting context')
    else:
        pins['sporting_rule_status'] = 'unavailable'
        for row in rows:
            row['sporting_rule_bindings'] = {'state': 'missing', 'bindings': [], 'mismatches': [],
                'reason': 'Independent pinned sporting rule and source meanings unavailable'}
    _remaining(deadline)
    return context


def parser_versions(comparison):
    """Explicit metadata from the hash-pinned adapter, never parser-name inference."""
    root = Path(comparison['runtime_path']).absolute()
    manifest_bytes = (root / 'manifest.json').read_bytes()
    if hashlib.sha256(manifest_bytes).hexdigest() != comparison['runtime_manifest_sha256']:
        raise ValueError('sporting parser runtime changed')
    manifest = json.loads(manifest_bytes)
    relative = 'src/freediving/attempt_view_adapter.clj'
    path = root / relative
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('sporting parser runtime symlink')
    body = path.read_bytes()
    if hashlib.sha256(body).hexdigest() != manifest['files'][relative]:
        raise ValueError('sporting parser metadata changed')
    matches = re.findall(r':job "([a-f0-9]{64})"\s+:artifact "([a-f0-9]{64})"\s+:parser "([^"\n]{1,128})"', body.decode('utf-8'))
    versions = {(job, artifact): parser for job, artifact, parser in matches}
    if not matches or len(versions) != len(matches):
        raise ValueError('sporting parser metadata unavailable')
    return versions


def configure(origin, snapshot_dir, env):
    origin.aida_diff_config = env.get('OWNER_EVIDENCE_AIDA_DIFF_CONFIG')
    if origin.aida_diff_config:
        from retained_aida_diff import read_service
        if not Path(origin.aida_diff_config).is_absolute() or Path(origin.aida_diff_config).resolve().is_relative_to(Path(snapshot_dir).resolve()):
            raise ValueError('invalid independent retained diff configuration path')
        read_service(origin.aida_diff_config)
    name = env.get('OWNER_EVIDENCE_SPORTING_CONFIG')
    origin.sporting = None
    origin.sporting_request_key = None
    origin.sporting_proof_reader = None
    origin.source_accuracy_review = None
    origin.relationship_reviews = None
    origin.sporting_deadlines = threading.local()
    if not name:
        return
    config_path = Path(name)
    data = json.loads(private_bytes(config_path, 65536))
    if (not isinstance(data, dict) or set(data) - {'rule_objects', 'relationship_ledger_path'} !=
            {'schema', 'ledger_path', 'signing_key_path', 'request_key_path'}
            or data['schema'] != 'sporting-authority-service/v1'
            or any(not Path(data[k]).is_absolute() or Path(data[k]).resolve().is_relative_to(Path(snapshot_dir).resolve())
                   for k in ('ledger_path', 'signing_key_path', 'request_key_path'))):
        raise ValueError('invalid private sporting service configuration')
    request_key = private_bytes(data['request_key_path'], 256).decode('ascii').strip()
    if not 32 <= len(request_key) <= 256 or any(c.isspace() for c in request_key):
        raise ValueError('invalid sporting request key')
    from private_sporting_proofs import create_reader
    origin.sporting_proof_reader = create_reader(env)
    review_config = env.get('OWNER_EVIDENCE_SOURCE_REVIEW_CONFIG')
    if review_config and origin.sporting_proof_reader:
        def source_accuracy_review(request, *, deadline=None, replay_only=False):
            return origin.sporting_proof_reader.review(request, review_config,
                                                      deadline=deadline, replay_only=replay_only)
        origin.source_accuracy_review = source_accuracy_review
    if origin.sporting_proof_reader is not None:
        # JVM initialization happens before the HTTP socket is bound. Discard
        # authority results; every request still reads and rechecks current pins.
        origin.sporting_proof_reader([], deadline=time.monotonic() + READ_BUDGET_SECONDS)
        if origin.comparison_reader is not None:
            origin.read_comparison({'limit': 1}, None, deadline=time.monotonic() + READ_BUDGET_SECONDS)
            from private_attempt_inspector import _private_bytes, _pinned
            comparison = json.loads(_private_bytes(env['OWNER_EVIDENCE_COMPARISON_CONFIG'], 65536))
            _pinned(comparison['packet'])
            rows = retained_rows(origin, comparison, deadline=time.monotonic() + READ_BUDGET_SECONDS,
                                 bind_sources=False)
            origin.sporting_proof_reader(
                [{'reference': row['reference'], 'coordinates': row['coordinates']} for row in rows],
                deadline=time.monotonic() + READ_BUDGET_SECONDS)
    def base(review=False):
        return live_context(origin, snapshot_dir, env, config_path, review=review,
                            deadline=getattr(origin.sporting_deadlines, 'deadline', None))
    def read():
        context = base()
        return origin.relationship_reviews.proofs(context) if origin.relationship_reviews else context
    read.review = lambda: origin.relationship_reviews.proofs(base(True)) if origin.relationship_reviews else base(True)
    origin.sporting_proof_context = read.review
    if data.get('relationship_ledger_path'):
        from private_sporting_relationships import RelationshipReviews
        ledger = Path(data['relationship_ledger_path'])
        if not ledger.is_absolute() or ledger.resolve().is_relative_to(Path(snapshot_dir).resolve()):
            raise ValueError('invalid independent relationship ledger path')
        origin.relationship_reviews = RelationshipReviews(ledger, lambda: base(True))
        origin.relationship_reviews.read_lock = lambda: origin.read_lock(getattr(origin.sporting_deadlines, 'deadline', None))
    origin.sporting = SportingAuthority(data['ledger_path'], data['signing_key_path'], read)
    origin.sporting_request_key = request_key
    origin.sporting.read_lock = lambda: origin.read_lock(getattr(origin.sporting_deadlines, 'deadline', None))
    original_close = origin.sporting.close
    def close():
        original_close()
        if origin.relationship_reviews:
            origin.relationship_reviews.close()
        if origin.sporting_proof_reader:
            origin.sporting_proof_reader.close()
    origin.sporting.close = close


def is_machine(handler):
    """A loopback caller can challenge; it cannot forge an owner signature."""
    return (handler.path == CURRENT and handler.command == 'POST'
            and handler.client_address[0] == '127.0.0.1'
            and handler._one('Host') == '127.0.0.1:' + str(handler.server.server_port)
            and not any(handler.headers.get_all(name, []) for name in
                        ('Origin', 'Cookie', 'Authorization', 'Cf-Access-Jwt-Assertion',
                         'X-Freediving-Owner-Email', 'X-Freediving-Owner-Gateway', 'Transfer-Encoding')))


def _body(handler, limit):
    lengths = handler.headers.get_all('Content-Length', [])
    if (handler._one('Content-Type') != 'application/json' or len(lengths) != 1
            or not lengths[0].isdigit() or not 0 < int(lengths[0]) <= limit):
        raise ValueError('invalid sporting request body')
    raw = handler.rfile.read(int(lengths[0]))
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError('duplicate sporting JSON key')
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('invalid sporting number')))


def get(handler, parsed):
    if parsed.path == '/owner-evidence/sporting/aida-diff':
        if parsed.query:
            handler._reply(400); return True
        try:
            from retained_aida_diff import read_service
            body = read_service(handler.server.aida_diff_config)
            handler._reply(200, body, 'text/html; charset=utf-8')
        except (ValueError, OSError, TypeError, KeyError, AttributeError):
            handler._reply(503)
        return True
    if hasattr(handler.server, 'sporting_deadlines'):
        handler.server.sporting_deadlines.deadline = getattr(handler, 'read_deadline', time.monotonic() + READ_BUDGET_SECONDS)
    if parsed.path == PREFIX + '/proofs':
        try:
            params = parse_qs(parsed.query, strict_parsing=True) if parsed.query else {}
            if set(params) - {'offset', 'limit'} or any(len(v) != 1 for v in params.values()):
                raise ValueError('invalid exact proof pagination')
            offset, limit = int(params.get('offset', ['0'])[0]), int(params.get('limit', ['20'])[0])
            if not 0 <= offset <= 400 or not 1 <= limit <= 50:
                raise ValueError('invalid exact proof page')
        except ValueError:
            handler._reply(400); return True
        try:
            context = handler.server.sporting_proof_context()
            rows = context['rows']
            ledger = getattr(handler.server, 'relationship_reviews', None)
            from private_sporting_relationships import inventory
            handler._json({'schema': 'sporting-exact-proof-diagnostics/v1', 'pins': context['pins'],
                           'context_pins_sha256': digest(context['pins']),
                           'source_review_available': getattr(handler.server, 'source_accuracy_review', None) is not None,
                           'inventory_sha256': digest(inventory(context)),
                           'reviewed_references': [r['reference'] for r in rows],
                           'relationship_revision': len(ledger.history()) if ledger else 0,
                           'relationship_available': ledger is not None and 'canonical_upstream_sha256' in context['pins'],
                           'source_positions': len({digest({'source-sha256': r['reference']['source-sha256'], 'coordinates': {k: v for k, v in r['coordinates'].items() if k in ('page', 'line', 'table', 'row')}}) for r in rows}),
                           'pagination': {'offset': offset, 'limit': limit, 'total': len(rows)},
                           'rows': rows[offset:offset + limit]})
        except (ValueError, OSError, sqlite3.Error, AttributeError):
            handler._reply(503)
        return True
    assets = {'/owner-evidence/sporting': (PAGE, 'text/html; charset=utf-8'),
              '/owner-evidence/assets/sporting.js': (SCRIPT, 'text/javascript; charset=utf-8'),
              '/owner-evidence/assets/sporting.css': (STYLE, 'text/css; charset=utf-8')}
    if parsed.path in assets and not parsed.query:
        body, content_type = assets[parsed.path]
        handler._reply(200, body, content_type)
        return True
    if parsed.path not in (PREFIX + '/review', PREFIX + '/preview') or parsed.query:
        return False
    if handler.server.sporting is None:
        handler._reply(503)
    else:
        try:
            value = handler.server.sporting.review()
            value['csrf_token'] = handler._csrf()
            value['domain_order'] = ['source-approve', 'select-cohort', 'publish']
            value['upstream_required'] = ['extraction review', 'same-attempt', 'public eligibility']
            handler._json(value)
        except (ValueError, OSError, sqlite3.Error):
            handler._reply(503)
    return True


def post(handler, parsed):
    if parsed.path not in (CURRENT, PREFIX + '/stage', PREFIX + '/actions', PREFIX + '/relationships', PREFIX + '/source-accuracy') or parsed.query:
        return False
    if handler.server.sporting is None:
        handler._reply(503)
        return True
    try:
        handler.server.sporting_deadlines.deadline = time.monotonic() + READ_BUDGET_SECONDS
        if parsed.path == CURRENT:
            if not is_machine(handler):
                handler._reply(403); return True
            body = _body(handler, 4096)
            signature = handler._one('X-Freediving-Sporting-HMAC')
            if (set(body) != {'schema', 'nonce'} or body['schema'] != 'sporting-authority-challenge/v1'
                    or signature is None or not hmac.compare_digest(signature, hmac.new(
                        handler.server.sporting_request_key.encode('ascii'), canonical(body), hashlib.sha256).hexdigest())):
                handler._reply(403); return True
            result = handler.server.sporting.current(body['nonce'])
        else:
            if handler._one('Origin') != 'https://poc.alphacompose.com' or handler._one('X-Freediving-CSRF') != handler._csrf():
                handler._reply(403); return True
            body = _body(handler, MAX_STAGE)
            if not hmac.compare_digest(body.get('csrf_token', ''), handler._csrf()):
                handler._reply(403); return True
            if parsed.path.endswith('/source-accuracy'):
                fields = {'id', 'action', 'reference', 'coordinates', 'expected_source_binding_sha256',
                          'base_revision', 'event_id', 'reason', 'source_visual_accuracy', 'csrf_token',
                          'expected_context_pins_sha256'}
                if not isinstance(body, dict) or set(body) != fields:
                    raise ValueError('invalid exact source accuracy action fields')
                writer = getattr(handler.server, 'source_accuracy_review', None)
                if writer is None:
                    handler._reply(503); return True
                context = handler.server.sporting_proof_context()
                replay_only = not hmac.compare_digest(body['expected_context_pins_sha256'], digest(context['pins']))
                target = [row for row in context['rows'] if
                          row['reference'] == body['reference'] and row['coordinates'] == body['coordinates']]
                if len(target) != 1 or target[0].get('source_review', {}).get('enabled') is not True:
                    raise ConflictError('exact retained source version is unavailable for visual accuracy review')
                request = {k: v for k, v in body.items() if k not in ('csrf_token', 'expected_context_pins_sha256')}
                request['actor'] = handler._one('X-Freediving-Owner-Email')
                result = writer(request, deadline=handler.server.sporting_deadlines.deadline, replay_only=replay_only)
            elif parsed.path.endswith('/relationships'):
                if set(body) != {'assertion', 'action', 'expected_revision', 'idempotency_key', 'csrf_token'}:
                    raise ValueError('invalid relationship action fields')
                ledger = handler.server.relationship_reviews
                if ledger is None:
                    handler._reply(503); return True
                result = ledger.act(body['assertion'], action=body['action'], expected_revision=body['expected_revision'],
                                    idempotency_key=body['idempotency_key'], actor=handler._one('X-Freediving-Owner-Email'))
            elif parsed.path.endswith('/stage'):
                if set(body) != {'proposal', 'expected_revision', 'idempotency_key', 'csrf_token'}:
                    raise ValueError('invalid sporting stage fields')
                result = handler.server.sporting.stage(body['proposal'], expected_revision=body['expected_revision'],
                                                       idempotency_key=body['idempotency_key'])
            else:
                if set(body) != {'id', 'action', 'reason', 'expected_revision', 'idempotency_key', 'csrf_token'}:
                    raise ValueError('invalid sporting action fields')
                result = handler.server.sporting.act(body['id'], action=body['action'], reason=body['reason'],
                                                     expected_revision=body['expected_revision'],
                                                     idempotency_key=body['idempotency_key'],
                                                     actor=handler._one('X-Freediving-Owner-Email'))
        handler._json(result)
    except ConflictError:
        handler._reply(409)
    except KeyError:
        handler._reply(404)
    except (ValueError, TypeError, UnicodeError):
        handler._reply(400)
    except (OSError, sqlite3.Error, subprocess.SubprocessError):
        handler._reply(503)
    return True
