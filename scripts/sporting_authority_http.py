"""Owned HTTP boundary for sporting authority, separate from existing decisions."""
import hashlib
import hmac
import json
from pathlib import Path
import re
import sqlite3
import subprocess

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
<div id="status" role="status">Loading current private reviews...</div><button id="refresh">Refresh</button>
<section id="proposals"></section></main></body></html>'''
STYLE = b'''body{font:16px system-ui;color:#17302f;background:#f5f7f5;margin:0}main{max-width:1180px;margin:32px auto;padding:24px}
h1{font-size:32px}article{background:white;border:1px solid #cad6ce;border-radius:10px;margin:24px 0;padding:24px}
table{border-collapse:collapse;width:100%;font-size:14px}td,th{border:1px solid #d7dfda;padding:10px;text-align:left;vertical-align:top}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}button{padding:10px 16px;margin:8px;border:1px solid #6c8880;border-radius:6px;background:white}
label{display:block;margin-top:16px}textarea{width:90%;min-height:60px;font:inherit}a{color:#155f52}.notice{padding:16px;background:#e7eee9}'''
SCRIPT = b'''"use strict";
const root='/owner-evidence/api/sporting-authority';let current;
const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
function details(title,value){const d=node('details');d.append(node('summary',title),node('pre',JSON.stringify(value,null,2)));return d;}
async function load(){try{const r=await fetch(root+'/review',{cache:'no-store'});if(!r.ok)throw Error('Private authority unavailable ('+r.status+')');
current=await r.json();document.getElementById('status').textContent='Authority revision '+current.revision+' - '+current.proposals.length+' review proposals - current source authority '+current.currentness;
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
catch(e){document.getElementById('status').textContent=e.message;b.disabled=false;}});card.append(b);}list.append(card);}}
catch(e){document.getElementById('status').textContent=e.message;}}
document.getElementById('refresh').addEventListener('click',load);load();'''


def live_context(origin, snapshot_dir, env, config_path, *, review=False):
    """Read exact active pins. Legacy same-attempt approvals grant no sporting facts."""
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
        if origin.comparison_reader is None:
            raise ValueError('exact sporting rows unavailable')
        authority = origin.status_authority()
        if authority is None:
            raise ValueError('fresh owner/source status unavailable')
        pins['owner_authority_sha256'] = digest(authority)
        parsers = parser_versions(comparison)
        result = origin.comparison_reader({'year': '2026', 'environment': 'pool',
                                           'discipline': 'DNF', 'gender': 'women', 'limit': 200}, None)
        for row in result['rows']:
            parser = parsers.get((row['reference'].get('job-id'), row['reference'].get('artifact-sha256')))
            if parser is None:
                raise ValueError('exact sporting parser version unavailable')
            rows.append({'reference': {**row['reference'], 'parser-version': parser}, 'coordinates': row['row_coordinate'],
                         'year': str(row['year']), 'environment': row['environment'],
                         'discipline': row['discipline'].lower(), 'gender': row['gender'],
                         # No existing ledger authenticates exact extraction, distinct
                         # attempt and public eligibility for these rows. Unknown stays
                         # explicit; review cannot manufacture upstream decisions.
                         'upstream': {}})
    if owner.revision != before or owner.active_snapshot_sha256 != snapshot_sha:
        raise ConflictError('owner source changed while collecting sporting context')
    return {'pins': pins, 'rows': rows, 'rules': rules}


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
    name = env.get('OWNER_EVIDENCE_SPORTING_CONFIG')
    origin.sporting = None
    origin.sporting_request_key = None
    if not name:
        return
    config_path = Path(name)
    data = json.loads(private_bytes(config_path, 65536))
    if (not isinstance(data, dict) or set(data) - {'rule_objects'} !=
            {'schema', 'ledger_path', 'signing_key_path', 'request_key_path'}
            or data['schema'] != 'sporting-authority-service/v1'
            or any(not Path(data[k]).is_absolute() or Path(data[k]).resolve().is_relative_to(Path(snapshot_dir).resolve())
                   for k in ('ledger_path', 'signing_key_path', 'request_key_path'))):
        raise ValueError('invalid private sporting service configuration')
    request_key = private_bytes(data['request_key_path'], 256).decode('ascii').strip()
    if not 32 <= len(request_key) <= 256 or any(c.isspace() for c in request_key):
        raise ValueError('invalid sporting request key')
    def read():
        return live_context(origin, snapshot_dir, env, config_path)
    read.review = lambda: live_context(origin, snapshot_dir, env, config_path, review=True)
    origin.sporting = SportingAuthority(data['ledger_path'], data['signing_key_path'], read)
    origin.sporting_request_key = request_key


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
    if parsed.path not in (CURRENT, PREFIX + '/stage', PREFIX + '/actions') or parsed.query:
        return False
    if handler.server.sporting is None:
        handler._reply(503)
        return True
    try:
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
            if parsed.path.endswith('/stage'):
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
