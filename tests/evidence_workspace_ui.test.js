const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
  constructor(tag = 'div') { this.tagName = tag; this.children = []; this.textContent = ''; this.value = ''; this.listeners = {}; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = [...children]; this.textContent = ''; }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  click() { return this.listeners.click?.({preventDefault() {}}); }
  get visibleText() { return [this.textContent, ...this.children.map(x => x.visibleText ?? x.textContent ?? '')].join(' '); }
}

function workspace(responses = {}, statuses = {}, pathname = '/owner-evidence') {
  const nodes = new Map();
  const requests = [];
  const find = (element, id) => element.id === id ? element : element.children.map(child => find(child, id)).find(Boolean);
  const document = {
    createElement: tag => new Element(tag),
    getElementById: id => { const nested = [...nodes.values()].map(node => find(node, id)).find(Boolean); if(nested) return nested; if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); },
    addEventListener() {},
  };
  const context = vm.createContext({document, fetch: async (path, options) => { requests.push({path, options}); const status = statuses[path] || 200; return {ok: status >= 200 && status < 300, status, json: async () => responses[path]}; }, location: {origin: 'https://owner.example', pathname}, crypto: {randomUUID: () => 'retry-key'}, URLSearchParams, FormData: class { *[Symbol.iterator]() {} }});
  vm.runInContext(fs.readFileSync('resources/evidence_workspace.js', 'utf8'), context);
  return {context, node: document.getElementById, requests};
}

test('private attempt inspector separates denominators and shows retained versions with evidence gaps', async () => {
  const {context,node,requests}=workspace({'/api/attempt-inspector?limit=25&offset=0': {
    schema:'private-attempt-inspector/v1', cutoff:'2026-10-01T12:39:47Z',
    readiness:{'source-positions':138}, authority:{status:'absent'}, coverage:{ranked:0,withheld:138},
    counts:{source_positions:138,retained_observation_versions:276,distinct_sporting_attempts:null,eligible_peer_cohorts:0},
    gaps:['AIDA final publication and post-penalty semantics unverified'],
    pagination:{total:1,limit:25,offset:0}, filter_options:{federation:['AIDA','CMAS']},
    rows:[{source_id:'synthetic-source',version_id:'synthetic-v2',row_coordinate:{table:1,row:3},reference:{'source-sha256':'a'.repeat(64),'artifact-sha256':'b'.repeat(64),ordinal:1},
      source:{federation:'AIDA'},candidate:{raw:{fields:{card:'<script>test</script>'}},parsed:{discipline:'DNF',gender:'women'}},
      finality:'unknown',review:'unreviewed',publication:'private','comparison-status':'withheld',reasons:['missing current exact-row evidence'],
      'retained-versions':[{reference:{version_id:'synthetic-v1'},candidate:{parsed:{result:41}}},
        {reference:{version_id:'synthetic-v2'},candidate:{parsed:{result:42}}}],
      source_access:{status:'unavailable',reason:'No verified snapshot row binding'}}]}});
  await vm.runInContext('loadAttemptInspector()',context);
  for(const phrase of ['138 source positions','276 retained observation versions','Distinct sporting attempts: unknown','0 ranked positions','2026-10-01T12:39:47Z','AIDA final publication'])assert.ok(node('inspector-summary').visibleText.includes(phrase),phrase);
  assert.match(node('inspector-results').visibleText,/synthetic-source/);
  context.inspectorIndex=0;
  vm.runInContext('showInspectorRow(inspectorIndex)',context);
  const detail=node('inspector-detail').visibleText;
  for(const phrase of ['synthetic-v1','synthetic-v2','41','42','<script>test</script>','missing current exact-row evidence','No verified snapshot row binding'])assert.ok(detail.includes(phrase),phrase);
  assert.equal(requests.length,1);
  assert.ok(requests.every(request=>request.options.cache==='no-store'));
});

test('private inspector submits sport filters and clears prior ranks when fresh authority fails', async () => {
  const path='/api/attempt-inspector?federation=CMAS&discipline=DNF&representation=FRA&limit=50&offset=0';
  const statuses={};
  const {context,node,requests}=workspace({[path]:{coverage:{source_positions:1,retained_observation_versions:2},
    readiness:{status:'synthetic eligible',ranked_positions:1},pagination:{total:1,limit:50,offset:0},
    rows:[{reference:{source_id:'isolated-synthetic'},candidate:{parsed:{}},comparison_status:'eligible',rank:1}]}},statuses);
  context.FormData=class { *[Symbol.iterator](){yield ['federation','CMAS'];yield ['discipline','DNF'];yield ['representation','FRA'];yield ['limit','50'];} };
  await vm.runInContext('loadAttemptInspector()',context);
  assert.match(node('inspector-results').visibleText,/eligible \/ 1/);
  vm.runInContext('showInspectorRow(0)',context);
  statuses[path]=503;
  await assert.rejects(vm.runInContext('loadAttemptInspector()',context),/HTTP 503/);
  assert.equal(node('inspector-results').visibleText,'');
  assert.equal(node('inspector-detail').visibleText,'');
  assert.match(node('inspector-summary').visibleText,/ranks withheld/);
  assert.deepEqual(requests.map(request=>request.path),[path,path]);
});

test('older inspection completing after a newer failed refresh cannot restore ranks', async () => {
  const {context,node}=workspace();
  const pending=[];
  context.fetch=()=>new Promise(resolve=>pending.push(resolve));
  const earlier=vm.runInContext('loadAttemptInspector()',context);
  const newer=vm.runInContext('loadAttemptInspector()',context);
  pending[1]({ok:false,status:503});
  await assert.rejects(newer,/HTTP 503/);
  pending[0]({ok:true,status:200,json:async()=>({coverage:{ranked:1},
    pagination:{total:1,limit:25,offset:0},rows:[{source_id:'stale-synthetic',candidate:{parsed:{}},
      comparison_status:'eligible',rank:1,comparison_lists:[{href:'/api/attempt-inspector?peer_anchor=stale'}]}]})});
  await earlier;
  assert.equal(node('inspector-results').visibleText,'');
  assert.equal(node('inspector-detail').visibleText,'');
  assert.match(node('inspector-summary').visibleText,/ranks withheld/);
  assert.match(node('inspector-summary').visibleText,/Refresh/);
});

test('current owner status separates canonical cohorts from historical identity receipt', () => {
  const {context,node}=workspace();
  context.receipt={schema:'private-presentation-status/v4',run_id:'normal-run',
    local:{snapshot_sha256:'snapshot',cutoff:'2026-10-05T00:00:00Z',gap_count:9},
    remote:{status:'failed',pending:'snapshot',failed:'snapshot',active:{snapshot_sha256:'snapshot',bundle_manifest_sha256:'bundle'}},
    authority:{owner_store_revision:227,owner_metrics:{pending:207,human_approved:5},
      canonical:{identity:{status:'unknown',revision:null,owner_event_revision:null,accepted_count:null},
        same_attempt:{status:'verified',revision:7,owner_event_revision:227,accepted_count:5}}},
    historical:{sha256:'historical-hash',receipt:{application:{canonical_revision:211,owner_store_revision:209}}}};
  vm.runInContext('renderPresentationStatus({},[],receipt)',context);
  const text=node('presentation-status').visibleText;
  for(const phrase of ['Status receipt: failed','212 owner decisions','207 pending review',
      'Athlete identity canonical scope: unknown','Same attempt canonical scope: verified; revision 7',
      'Accepted count within the verified cohort: 5','Historical private identity application: canonical revision 211; owner revision 209',
      'cache reuse and provider usage remain in the verified local run metrics'])assert.ok(text.includes(phrase),phrase);
  assert.doesNotMatch(text,/same_attempt|"pending":207/);
});

test('consolidated PDF review shows cited versions while decision review remains available', async () => {
  const {context, node, requests} = workspace({'/api/issue172-queue?limit=25&offset=0': {
    schema:'issue172-owner-queue-v1', audit_sha256:'a'.repeat(64), queue_sha256:'b'.repeat(64),
    total:1, offset:0, limit:25, items:[{id:'field-1',kind:'unresolved_field', source_key:'world-cup',
      source_sha256:'c'.repeat(64), source_position:{page:2,row:4}, citation:{page:2,row:4},
      evidence_version:{parser_version:'v2',observation_version:'o1'}, reason:'Card unclear',
      related_positions:[],status:'pending'}]}});
  await vm.runInContext('loadIssue172Queue()', context);
  assert.match(node('issue172-summary').visibleText, /1 unresolved PDF evidence item/);
  assert.match(node('issue172-results').visibleText, /Card unclear/);
  assert.match(node('issue172-results').visibleText, /field-1/);
  assert.match(node('issue172-results').visibleText, /v2/);
  assert.ok(requests.some(request => request.path === '/api/issue172-queue?limit=25&offset=0'));
  assert.match(fs.readFileSync('resources/evidence_workspace.html','utf8'), /id="decision-workspace"/);
});

test('owner decision queue separates scoreless gaps and shows ascending provider confidence', async () => {
  const {context, node} = workspace({'/api/decisions?status=&limit=25&offset=0': {
    revision: 7, snapshot_sha256: 'snapshot-7', total: 4, scoreless_total: 1, items: [
      {id: 'low', type: 'athlete_identity', source_name: 'AIDA', status: 'pending', provider_confidence: 0.41, proposed: 'one'},
      {id: 'high', type: 'athlete_identity', source_name: 'AIDA', status: 'pending', provider_confidence: 0.88, proposed: 'two'},
      {id: 'auto', type: 'athlete_identity', source_name: 'AIDA', status: 'automatic_approved', provider_confidence: 0.99, proposed: 'three'},
      {id: 'stale', type: 'athlete_identity', source_name: 'AIDA', status: 'pending', effective_status: 'invalidated', provider_confidence: 0.1, proposed: 'stale'},
    ], scoreless_items: [{id: 'gap', type: 'source_revision', source_name: 'CMAS', status: 'pending', provider_confidence: null, proposed: 'unknown'}],
  }});
  await vm.runInContext('loadDecisions()', context);
  const scored = node('decision-scored').visibleText;
  assert.ok(scored.indexOf('low') < scored.indexOf('high'));
  assert.match(node('decision-scoreless').visibleText, /gap/);
  assert.match(node('decision-automatic').visibleText, /auto/);
  assert.match(node('decision-automatic').visibleText, /stale/);
  assert.doesNotMatch(scored, /stale/);
  assert.match(node('decision-summary').visibleText, /uncalibrated/i);
  assert.match(node('decision-summary').visibleText, /snapshot-7/);
  const html = fs.readFileSync('resources/evidence_workspace.html', 'utf8');
  for(const status of ['human_corrected', 'invalidated', 'projection_pending'])
    assert.match(html, new RegExp(`<option value="${status}">`));
  assert.match(html, /Confidence scores for different decision types may use different scales/);
});

test('decision inspection shows immutable evidence, alternatives, versions and history', async () => {
  const {context, node} = workspace({'/api/decisions/d1': {
    id: 'd1', store_revision: 8, type: 'athlete_identity', status: 'automatic_approved', original: {name: 'Source A'},
    proposed: {name: 'Athlete B'}, selected_option: 'Athlete B', competing_options: ['Athlete C'],
    evidence: [{citation: 'PDF page 2 row 9'}], depends_on: ['d0'], provider_confidence: 0.91,
    rule_version: 'alias-rule-v3', model_version: 'jev-v2', policy_version: 'auto-v1',
    history: [{action: 'approve', actor_kind: 'automatic', at: '2026-10-02T10:00:00Z'}],
  }});
  await vm.runInContext("inspectDecision('d1')", context);
  const text = node('decision-detail').visibleText;
  for(const phrase of ['Source A', 'Athlete B', 'Athlete C', 'PDF page 2 row 9', 'alias-rule-v3', 'jev-v2', 'auto-v1', 'automatic', 'd0']) assert.ok(text.includes(phrase), phrase);
  assert.match(text, /Reverse/);
});

test('decision history labels approvals superseded by later owner correction or reversal', async () => {
  const {context, node} = workspace({
    '/api/decisions/auto-corrected': {id: 'auto-corrected', type: 'identity', status: 'human_corrected', evidence: [], history: [
      {revision: 1, action: 'register'}, {revision: 2, action: 'automatic_approve'},
      {revision: 3, action: 'correct', correction: {action: 'different_person'}}]},
    '/api/decisions/human-reversed': {id: 'human-reversed', type: 'identity', status: 'reversed', evidence: [], history: [
      {revision: 4, action: 'approve'}, {revision: 5, action: 'reverse'}]},
    '/api/decisions/invalidated': {id: 'invalidated', type: 'identity', status: 'automatic_approved', effective_status: 'invalidated', evidence: [], history: [
      {revision: 6, action: 'automatic_approve'}]},
    '/api/decisions/current-auto': {id: 'current-auto', type: 'identity', status: 'automatic_approved', evidence: [], history: [
      {revision: 7, action: 'automatic_approve'}]},
    '/api/decisions/recorrected': {id: 'recorrected', type: 'identity', status: 'human_corrected', evidence: [], history: [
      {revision: 8, action: 'automatic_approve'}, {revision: 9, action: 'correct', correction: {action: 'two'}},
      {revision: 10, action: 'correct', correction: {action: 'three'}}]},
  });
  await vm.runInContext("inspectDecision('auto-corrected')", context);
  assert.match(node('decision-detail').visibleText, /Automatic approval at revision 2 superseded by human correction at revision 3/);
  assert.doesNotMatch(node('decision-detail').visibleText, /Human approval at revision 2/);
  await vm.runInContext("inspectDecision('human-reversed')", context);
  assert.match(node('decision-detail').visibleText, /Human approval at revision 4 superseded by reversal at revision 5/);
  await vm.runInContext("inspectDecision('invalidated')", context);
  assert.doesNotMatch(node('decision-detail').visibleText, /superseded/i);
  await vm.runInContext("inspectDecision('current-auto')", context);
  assert.match(node('decision-detail').visibleText, /Automatic approval recorded; canonical edge unverified/);
  assert.doesNotMatch(node('decision-detail').visibleText, /Owner approval recorded/);
  await vm.runInContext("inspectDecision('recorrected')", context);
  assert.match(node('decision-detail').visibleText, /Human correction at revision 9 superseded by human correction at revision 10/);
  assert.doesNotMatch(node('decision-detail').visibleText, /Human correction at revision 10 superseded/);
});

test('identity decisions distinguish pending review from verified canonical edges and inspect cited originals', async () => {
  const id = 'a'.repeat(64);
  const {context, node, requests} = workspace({
    '/api/decisions/pending': {id: 'pending', type: 'identity', status: 'pending', effective_status: 'pending', evidence: [{id, citation: {source_citation: {locator: {row: 3}}}, version: {source_sha256: 'source-1'}}], history: []},
    '/api/decisions/accepted': {id: 'accepted', type: 'identity', status: 'human_approved', effective_status: 'human_approved', canonical_projection_status: 'verified', evidence: [], history: []},
    '/api/decisions/undelivered': {id: 'undelivered', type: 'identity', status: 'human_approved', effective_status: 'projection_pending', canonical_projection_status: 'pending', evidence: [], history: []},
    ['/api/detail/' + id]: {source_name: 'aida', citation: {row: 3}, raw_fields: {}, parsed_fields: {}},
    ['/api/source-view/' + id]: {format: 'cited_html_packet', citation: {row: 3}, source_value: {}, raw_fields: {}, parsed_fields: {}},
  });
  await vm.runInContext("inspectDecision('pending')", context);
  assert.match(node('decision-detail').visibleText, /Pending owner review/);
  assert.doesNotMatch(node('decision-detail').visibleText, /Accepted canonical edge/);
  assert.match(fs.readFileSync('resources/evidence_workspace.html', 'utf8'), /<option value="identity">Athlete identity<\/option>/);
  const inspect = node('decision-detail').children.flatMap(x => x.children || []).find(x => x.textContent === 'Inspect registered citation and original');
  assert.ok(inspect);
  await inspect.click();
  assert.ok(requests.some(r => r.path === '/api/detail/' + id));
  assert.ok(requests.some(r => r.path === '/api/source-view/' + id));
  assert.match(node('decision-detail').visibleText, /row/);
  await vm.runInContext("inspectDecision('accepted')", context);
  assert.match(node('decision-detail').visibleText, /Accepted canonical edge/);
  await vm.runInContext("inspectDecision('undelivered')", context);
  assert.match(node('decision-detail').visibleText, /canonical delivery pending/);
  assert.doesNotMatch(node('decision-detail').visibleText, /Accepted canonical edge/);
});

test('Human approved queue and same attempt detail show saved review and separate pending delivery', async () => {
  const items = Array.from({length: 5}, (_, i) => ({id: `approved-${i}`, type: 'same_attempt',
    status: 'human_approved', effective_status: 'projection_pending', canonical_projection_status: 'pending',
    provider_confidence: 0.8, evidence: [{citation: {row: i + 1}}], history: [{action: 'approve', revision: 221 + i}]}));
  const {context, node, requests} = workspace({
    '/api/decisions?status=human_approved&limit=25&offset=0': {revision: 225, total: 5, items, scoreless_items: [], scoreless_total: 0},
    '/api/decisions/approved-0': items[0],
  });
  context.FormData = class { *[Symbol.iterator]() { yield ['status', 'human_approved']; yield ['limit', '25']; } };
  await vm.runInContext('loadDecisions()', context);
  assert.ok(requests.some(r => r.path === '/api/decisions?status=human_approved&limit=25&offset=0'));
  const cards = node('decision-automatic').visibleText;
  for (let i = 0; i < 5; i++) assert.match(cards, new RegExp(`approved-${i}`));
  assert.match(cards, /Human approved/);
  assert.match(cards, /canonical delivery pending/);
  assert.doesNotMatch(cards, /Accepted canonical attempt/);
  await vm.runInContext("inspectDecision('approved-0')", context);
  const detail = node('decision-detail').visibleText;
  assert.match(detail, /Human approved/);
  assert.match(detail, /canonical delivery pending/);
  assert.match(detail, /221/);
  assert.doesNotMatch(detail, /Accepted canonical attempt/);
  assert.match(detail, /Preview Reverse/);
});

test('reversal previews dependent impact before writing with revision, CSRF and retry key', async () => {
  const {context, node, requests} = workspace({
    '/api/decisions/d1': {store_revision: 8, id: 'd1', status: 'automatic_approved', type: 'athlete_identity', evidence: [], history: []},
    '/api/decisions/d1/preview?action=reverse': {revision: 8, decision_id: 'd1', action: 'reverse', affected_decisions: ['d2'], affected_groups: ['athlete-1'], before: {count: 2}, after: {count: 3}},
    '/api/decisions/d1/actions': {store_revision: 9, id: 'd1', status: 'reversed', evidence: [], history: []},
    '/api/decisions?status=&limit=25&offset=0': {revision: 9, csrf_token: 'csrf-9', total: 0, items: [], scoreless_items: [], scoreless_total: 0},
  });
  await vm.runInContext('loadDecisions()', context);
  await vm.runInContext("inspectDecision('d1')", context);
  await vm.runInContext("previewDecisionAction('d1','reverse')", context);
  assert.match(node('decision-preview').visibleText, /d2/);
  assert.match(node('decision-preview').visibleText, /athlete-1/);
  assert.equal(requests.filter(r => r.options?.method === 'POST').length, 0);
  await vm.runInContext("submitDecisionAction('d1','reverse','correction')", context);
  const write=requests.find(r => r.options?.method === 'POST');
  assert.equal(write.path, '/api/decisions/d1/actions');
  assert.equal(write.options.headers['X-Freediving-CSRF'], 'csrf-9');
  const body=JSON.parse(write.options.body);
  assert.equal(body.action, 'reverse');
  assert.equal(body.expected_revision, 8);
  assert.equal(body.idempotency_key, 'retry-key');
  assert.equal(body.reason, 'correction');
  assert.equal(body.csrf_token, 'csrf-9');
});

test('reversed approval offers safe restoration while invalidated or corrected decisions stay guarded', async () => {
  const {context, node, requests} = workspace({
    '/api/decisions/reversed': {id: 'reversed', status: 'reversed', effective_status: 'projection_pending', available_actions: ['approve'], evidence: [], history: []},
    '/api/decisions/stale': {id: 'stale', status: 'human_approved', effective_status: 'invalidated', available_actions: [], evidence: [], history: []},
    '/api/decisions/blocked': {id: 'blocked', status: 'reversed', effective_status: 'projection_pending', available_actions: [], evidence: [], history: []},
    '/api/decisions/corrected': {id: 'corrected', status: 'human_corrected', effective_status: 'projection_pending', available_actions: ['reverse', 'correct'], selected_option: 'one', competing_options: ['two'], correction: {action: 'two'}, evidence: [], history: []},
    '/api/decisions/reversed/preview?action=approve': {revision: 9, decision_id: 'reversed', action: 'approve', before: {reversed: 'projection_pending'}, after: {reversed: 'projection_pending'}, review_after: {reversed: 'human_approved'}},
  });
  await vm.runInContext("inspectDecision('reversed')", context);
  assert.match(node('decision-detail').visibleText, /Preview approve/);
  const actions = node('decision-detail').children.find(element => element.className === 'decision-actions');
  await actions.children[0].click();
  await new Promise(setImmediate);
  assert.match(node('decision-preview').visibleText, /Confirm approve/);
  assert.equal(requests.filter(request => request.options?.method === 'POST').length, 0);
  for (const id of ['stale', 'blocked']) {
    await vm.runInContext(`inspectDecision('${id}')`, context);
    assert.doesNotMatch(node('decision-detail').visibleText, /Preview (Reverse|approve|correction)/);
  }
  await vm.runInContext("inspectDecision('corrected')", context);
  assert.match(node('decision-detail').visibleText, /Preview Reverse/);
  assert.match(node('decision-detail').visibleText, /Preview correction: one/);
  assert.doesNotMatch(node('decision-detail').visibleText, /Preview correction: two|Preview approve/);
});

test('owner previews a distinct correction option and confirms that exact option', async () => {
  const {context, node, requests} = workspace({
    '/api/decisions?status=&limit=25&offset=0': {revision: 8, csrf_token: 'csrf-8', total: 0, items: [], scoreless_items: []},
    '/api/decisions/d1': {id: 'd1', store_revision: 8, type: 'identity', status: 'automatic_approved', selected_option: 'same_person', competing_options: ['same_person', 'different_person'], evidence: [], history: []},
    '/api/decisions/d1/preview?action=correct&option=different_person': {revision: 8, decision_id: 'd1', action: 'correct', before_option: 'same_person', after_option: 'different_person', affected_decisions: ['d1', 'd2'], affected_groups: ['person-1'], before: {d1: 'automatic_approved'}, after: {d1: 'human_corrected'}},
    '/api/decisions/d1/actions': {id: 'd1', store_revision: 9, type: 'identity', status: 'human_corrected', selected_option: 'same_person', competing_options: ['different_person'], correction: {action: 'different_person'}, evidence: [], history: []},
  });
  await vm.runInContext('loadDecisions()', context);
  await vm.runInContext("inspectDecision('d1')", context);
  const correctionButtons = node('decision-detail').children.flatMap(x => x.children || []).filter(x => /Preview correction/.test(x.textContent));
  assert.equal(correctionButtons.length, 1);
  assert.match(correctionButtons[0].textContent, /different_person/);
  correctionButtons[0].click();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(node('decision-preview').visibleText, /same_person/);
  assert.match(node('decision-preview').visibleText, /different_person/);
  assert.match(node('decision-preview').visibleText, /d2/);
  assert.equal(requests.filter(r => r.options?.method === 'POST').length, 0);
  await vm.runInContext("submitDecisionAction('d1','correct','source checked','different_person')", context);
  const write = requests.find(r => r.options?.method === 'POST');
  assert.equal(write.options.headers['X-Freediving-CSRF'], 'csrf-8');
  assert.deepEqual(JSON.parse(write.options.body).correction, {action: 'different_person'});
  assert.equal(JSON.parse(write.options.body).expected_revision, 8);
  assert.equal(JSON.parse(write.options.body).idempotency_key, 'retry-key');
});

test('correction confirmation requires its option preview and stale revisions require a fresh preview', async () => {
  const path = '/api/decisions/d1/actions';
  const {context, requests} = workspace({
    '/api/decisions?status=&limit=25&offset=0': {revision: 8, csrf_token: 'csrf-8', total: 0, items: [], scoreless_items: []},
    '/api/decisions/d1/preview?action=correct&option=two': {revision: 8, decision_id: 'd1', action: 'correct', before_option: 'one', after_option: 'two', affected_decisions: ['d1'], affected_groups: [], before: {}, after: {}},
    [path]: {error: 'stale'},
  }, {[path]: 409});
  await vm.runInContext('loadDecisions()', context);
  await vm.runInContext("previewDecisionAction('d1','correct','two')", context);
  await assert.rejects(vm.runInContext("submitDecisionAction('d1','correct','','one')", context), /Preview this action/);
  await assert.rejects(vm.runInContext("submitDecisionAction('d1','correct','','two')", context), /Revision changed/);
  await assert.rejects(vm.runInContext("submitDecisionAction('d1','correct','','two')", context), /Preview this action/);
  assert.equal(requests.filter(r => r.options?.method === 'POST').length, 1);
});

test('automatic approval audit sample is inspectable and does not block review', async () => {
  const {context, node} = workspace({'/api/decisions/audit-sample?limit=10': {
    revision: 4, sample_size: 1, population_size: 7, sampling_basis: 'stable hash', blocking: false,
    items: [{id: 'sample-1', type: 'athlete_identity', status: 'automatic_approved', provider_confidence: 0.94}],
  }});
  await vm.runInContext('loadDecisionAuditSample()', context);
  const text=node('decision-audit').visibleText;
  assert.match(text, /sample-1/);
  assert.match(text, /1 of 7/);
  assert.match(text, /nonblocking/i);
});

test('dated route coverage shows all v7 states and a verifiable receipt', () => {
  const {context, node} = workspace();
  vm.runInContext(`renderRoutes(${JSON.stringify({
    cutoff: '2026-09-28T19:01:11Z', roster_sha256: 'roster-digest',
    summary: {route_count: 15, lead_count: 83, routes_by_status: {checked: 11, acquired: 3, inaccessible: 1}, leads_by_status: {checked: 34, acquired: 29, unchecked: 20}},
    items: [{id: 'aida_deutschland', authority: 'AIDA Deutschland', status: 'checked', role: 'primary', checked_at: '2026-09-28T18:30:05Z', lead_count: 10, lead_status_counts: {checked: 10}, citation: {url: 'https://example.org/results', locator: 'selected date 2025-04-26', evidence: 'response.html'}, receipt: {sha256: 'original-html-digest', final_url: 'https://example.org/results'}, gaps: ['Original HTML restricted']}],
  })})`, context);
  const text = node('route-summary').visibleText + node('route-list').visibleText;
  for (const phrase of ['15 route groups', '83 leads', 'checked: 34', 'acquired: 29', 'unchecked: 20', 'inaccessible: 1', 'original-html-digest', 'Original HTML restricted']) assert.match(text, new RegExp(phrase));
});

test('AIDA and Eindhoven details expose exact row locators and original availability', async () => {
  const aida = 'a'.repeat(64), noxy = 'b'.repeat(64);
  const {context, node} = workspace({
    ['/api/detail/' + aida]: {source_name: 'aida-4464-2025-04-26', collection: 'positions', kind: 'candidate_position', event_date: '2025-04-26', record_path: 'positions[34]', citation: {date: '2025-04-26', selector: 'day_1', table: 'table_ajax', table_number: 1, tbody: 'body_ajax', tbody_row: 35, row: 36}, raw_fields: {Diver: {value: 'Heiko'}, Card: {value: 'RED'}}, parsed_fields: {}, source_sha256: 'html-digest', snapshot_sha256: 'snapshot-digest'},
    ['/api/detail/' + noxy]: {source_name: 'eindhoven-noxy5', collection: 'result_rows', kind: 'candidate_position', event_date: '2026-05-09', record_path: 'result_rows[0]', citation: {json_pointer: '/rows/58', source_sha256: 'json-digest', url: 'https://noxyapp.com/api/competitions/5/results-snapshot'}, raw_fields: {result_val: '162.000', result_final_val: '81.000', card_status: 'WHITE'}, parsed_fields: {}, source_sha256: 'json-digest', snapshot_sha256: 'snapshot-digest'},
  });
  await vm.runInContext(`detail('${aida}')`, context);
  const aidaText = node('detail').visibleText;
  for (const phrase of ['Selected-date HTML result row', '2025-04-26', 'body_ajax', '35', 'Original HTML restricted', 'html-digest']) assert.ok(aidaText.includes(phrase), phrase);
  await vm.runInContext(`detail('${noxy}')`, context);
  const noxyText = node('detail').visibleText;
  for (const phrase of ['Eindhoven timing JSON row', '/rows/58', '162.000', '81.000', 'json-digest']) assert.ok(noxyText.includes(phrase), phrase);
});

test('cited HTML packet stays distinct from an original while timing JSON shows its exact row', async () => {
  const aida = 'a'.repeat(64), noxy = 'b'.repeat(64);
  const {context, node} = workspace({
    ['/api/source-view/' + aida]: {format: 'cited_html_packet', source_sha256: 'original-digest', derivative_sha256: 'packet-digest', citation: {date: '2025-04-26', tbody_row: 35}, source_value: {Card: {value: 'RED'}}, raw_fields: {}, parsed_fields: {}, original_replay: 'restricted_original_required'},
    ['/api/source-view/' + noxy]: {format: 'json', locator: '/rows/58', source_sha256: 'json-digest', citation: {json_pointer: '/rows/58'}, source_value: {result_val: '162.000', result_final_val: '81.000'}, raw_fields: {}, parsed_fields: {}},
  });
  const area = node('source-view-test');
  await vm.runInContext(`showSourceView('${aida}', document.getElementById('source-view-test'))`, context);
  assert.match(area.visibleText, /Cited safe HTML packet/);
  assert.match(area.visibleText, /Original HTML is restricted/);
  assert.match(area.visibleText, /packet-digest/);
  await vm.runInContext(`showSourceView('${noxy}', document.getElementById('source-view-test'))`, context);
  assert.match(area.visibleText, /Original JSON \/rows\/58/);
  assert.match(area.visibleText, /162.000/);
  assert.doesNotMatch(area.visibleText, /Original HTML is restricted/);
});

test('snapshot overview distinguishes evidence records from AIDA and Eindhoven source positions', async () => {
  const {context, node} = workspace({
    '/api/overview': {coverage: 'partial', cutoff: '2026-09-28T19:01:11Z', confirmed_distinct_attempts: null, snapshot_sha256: 'snapshot-digest', counts: [
      {source_name: 'aida-4408-2025-08-30', collection: 'positions', kind: 'candidate_position', records: 281},
      {source_name: 'eindhoven-noxy5', collection: 'result_rows', kind: 'candidate_position', records: 92},
      {source_name: 'eindhoven-noxy5', collection: 'overall_rows', kind: 'aggregate', records: 58},
      {source_name: 'eindhoven-noxy5', collection: 'endpoint_records', kind: 'endpoint_source_record', records: 94},
      {source_name: 'elsewhere', collection: 'positions', kind: 'candidate_position', records: 14164},
    ]},
    '/api/sources': [],
  });
  await vm.runInContext('loadOverview()', context);
  const text = node('overview').visibleText;
  for (const phrase of ['14689', '281', '92', '58', '94', 'confirmed_distinct_attempts unknown']) assert.ok(text.includes(phrase), phrase);
});

test('federation filter shows cited CMAS coverage and explicit unknown records', async () => {
  const html = fs.readFileSync('resources/evidence_workspace.html', 'utf8');
  assert.match(html, /name="federation"[^>]*>[^<]*<option value="">All cited federations<\/option>/);
  assert.match(html, /<option value="unknown">Unknown<\/option>/);
  const id = 'a'.repeat(64);
  const {context, node, requests} = workspace({
    '/api/overview': {coverage:'partial', counts:[{source_name:'sample',collection:'positions',kind:'candidate_position',records:2}], candidate_source_positions:2, confirmed_distinct_attempts:null,
      federation_mapping:{mapped_records:1,unknown_records:1,mapped_candidate_positions:1,unknown_candidate_positions:1}},
    '/api/sources':[],
    '/api/browse?federation=CMAS&kind=candidate_position&limit=25&offset=0': {total:1,offset:0,records:[{record_id:id,source_name:'sample',kind:'candidate_position',federation:'CMAS',authority:'CMAS',role:'primary',record_path:'positions[0]'}]},
    ['/api/detail/'+id]: {source_name:'sample',federation:'CMAS',authority:'CMAS',role:'primary',federation_citation:{url:'https://example.test/results.pdf',sha256:'a'.repeat(64),locator:'page 1 heading',evidence_text:'CMAS WORLD CUP'},citation:{page:1,row:1},raw_fields:{},parsed_fields:{}},
  });
  await vm.runInContext('loadOverview()', context);
  assert.match(node('overview').visibleText, /mapped_candidate_positions 1/);
  assert.match(node('overview').visibleText, /unknown_candidate_positions 1/);
  context.FormData = class { *[Symbol.iterator]() { yield ['federation','CMAS']; yield ['kind','candidate_position']; yield ['limit','25']; } };
  await vm.runInContext('browse()', context);
  assert.ok(requests.some(r => r.path === '/api/browse?federation=CMAS&kind=candidate_position&limit=25&offset=0'));
  assert.match(node('results').visibleText, /CMAS/);
  await vm.runInContext(`detail('${id}')`, context);
  assert.match(node('detail').visibleText, /CMAS WORLD CUP/);
  assert.match(node('detail').visibleText, /page 1 heading/);
});

test('snapshot presents source positions and observation versions separately from unknown accepted attempts', async () => {
  const {context, node} = workspace({
    '/api/overview': {coverage: 'partial', counts: [
      {source_name: 'sample', collection: 'positions', kind: 'candidate_position', records: 2},
      {source_name: 'sample', collection: 'observation_versions', kind: 'observation_version', records: 3},
    ], candidate_source_positions: 2, observation_version_records: 3,
    confirmed_distinct_attempts: null},
    '/api/sources': [],
  });
  await vm.runInContext('loadOverview()', context);
  const text = node('overview').visibleText;
  assert.match(text, /candidate_source_positions 2/);
  assert.match(text, /observation_version_records 3/);
  assert.match(text, /confirmed_distinct_attempts unknown/);
  assert.match(text, /overlap/i);
  assert.match(text, /publisher revision direction/i);
  assert.match(text, /Source row counts are not attempt totals/);
});

test('owner status identifies remote active partial snapshot and leaves local handoff unknown', async () => {
  const {context, node} = workspace({
    '/api/overview': {coverage: 'partial', cutoff: '2026-10-03T04:00:00Z', snapshot_sha256: 'remote-new', counts: [], confirmed_distinct_attempts: null,
      local: {status: 'complete', snapshot_sha256: 'stale-local'}, remote: {status: 'failed', pending: 'stale-local'}},
    '/api/sources': [{source_name: 'restricted-source', status: 'excluded', reason: 'original inaccessible'}],
  });
  await vm.runInContext('loadOverview()', context);
  const status = node('presentation-status').visibleText;
  for (const phrase of ['Remote active snapshot', 'remote-new', '2026-10-03T04:00:00Z', 'partial', 'restricted-source', 'original inaccessible',
    'Local completed revision: unavailable', 'Pending transfer: unavailable', 'Failed activation and retry: unavailable']) assert.ok(status.includes(phrase), phrase);
  assert.doesNotMatch(status, /stale-local/);
  assert.match(status, /accepted distinct attempts: unknown/i);
});

test('owner status shows synced local checkpoint and preserved remote active revision', async () => {
  const {context, node} = workspace({
    '/api/overview': {coverage: 'partial', cutoff: '2026-10-01T00:00:00Z', snapshot_sha256: 'remote-old', counts: []},
    '/api/sources': [],
    '/api/presentation-status': {revision: 2, run_id: 'run-2', local: {snapshot_sha256: 'local-new', cutoff: '2026-10-03T00:00:00Z', gap_count: 3},
      remote: {status: 'failed', pending: 'local-new', failed: 'local-new', active: {snapshot_sha256: 'remote-old', bundle_manifest_sha256: 'bundle-old'}}},
  });
  await vm.runInContext('loadOverview()', context);
  const status = node('presentation-status').visibleText;
  for (const phrase of ['local-new', '2026-10-03T00:00:00Z', '3', 'Pending transfer', 'Failed activation', 'remote-old']) assert.ok(status.includes(phrase), phrase);
});

test('owner reconciliation status shows bounded metrics and sample bias without implying publication', async () => {
  const snap='a'.repeat(64);
  const {context,node}=workspace({
    '/api/overview': {coverage:'partial',cutoff:'2026-10-03T00:00:00Z',snapshot_sha256:snap,counts:[]},
    '/api/sources': [],
    '/api/presentation-status': {schema:'private-presentation-status/v2',run_id:'run-1',
      local:{snapshot_sha256:snap,cutoff:'2026-10-03T00:00:00Z',gap_count:1},
      remote:{status:'pending',pending:snap,failed:null,active:null},
      reconciliation:{snapshot_sha256:snap,decision_revision:3,owner_store_revision:7,
        metrics:{decision_denominator:4,automatic_approved:2,unknown:1,error:1,conflict:0,pending_review:2,
          source_gaps:1,provider_calls_recorded:2,sampled_error:{sampling_frame:'owner selected approvals',
            numerator:1,denominator:2,selection:'convenience',selection_bias:'biased selection'},
          accepted_athletes:null,distinct_attempts:null,actual_monetary_cost:null}}},
  });
  await vm.runInContext('loadOverview()',context);
  const text=node('presentation-status').visibleText;
  for(const phrase of ['decision revision 3','owner store revision 7','2 of 4','1/2','owner selected approvals','biased selection','cost unknown','athletes unknown','attempts unknown']) assert.match(text,new RegExp(phrase,'i'));
  assert.doesNotMatch(text,/published/i);
});

test('private application receipt shows pending work and verified store readback without approvals or publication', async () => {
  const snap='a'.repeat(64);
  const {context,node}=workspace({
    '/api/overview': {coverage:'partial',cutoff:'2026-10-03T00:00:00Z',snapshot_sha256:snap,counts:[]},
    '/api/sources': [],
    '/api/presentation-status': {schema:'private-presentation-status/v3',run_id:'run-1',revision:3,
      local:{snapshot_sha256:snap,cutoff:'2026-10-03T00:00:00Z',gap_count:0},
      remote:{status:'active',pending:null,failed:null,active:{snapshot_sha256:snap,bundle_manifest_sha256:'b'.repeat(64)}},
      application:{snapshot_sha256:snap,canonical_revision:211,canonical_readback_sha256:'c'.repeat(64),
        owner_store_revision:12,pending_proposals:207,unresolved_exclusions:2,
        provider_calls_recorded:0,publication_status:'private'}},
  });
  await vm.runInContext('loadOverview()',context);
  const text=node('presentation-status').visibleText;
  for(const phrase of ['211 canonical events','207 pending','2 unresolved','zero provider calls','private','publication unverified']) assert.match(text,new RegExp(phrase,'i'));
  assert.doesNotMatch(text,/automatically approved|published|accepted athletes/i);
});

test('stale owner receipt is omitted after a correction or active snapshot change', async () => {
  const {context,node}=workspace({
    '/api/overview': {coverage:'partial',snapshot_sha256:'remote-new',counts:[]},
    '/api/sources': [],
    '/api/presentation-status': {status:'stale',reason:'owner decision or active snapshot changed',
      remote:{active:{snapshot_sha256:'remote-new',bundle_manifest_sha256:'bundle-new'}}},
  });
  await vm.runInContext('loadOverview()',context);
  const text=node('presentation-status').visibleText;
  assert.match(text,/stale/i);
  assert.doesNotMatch(text,/decision revision/i);
});

test('unreviewed run does not present a sampled error estimate', async () => {
  const {context,node}=workspace({
    '/api/overview': {coverage:'partial',snapshot_sha256:'snapshot',counts:[]},
    '/api/sources': [],
    '/api/presentation-status': {run_id:'run-1',local:{snapshot_sha256:'snapshot',cutoff:'2026-10-03T00:00:00Z',gap_count:0},
      remote:{status:'pending',pending:'snapshot',failed:null,active:null},
      reconciliation:{snapshot_sha256:'snapshot',decision_revision:0,owner_store_revision:0,
        metrics:{decision_denominator:0,automatic_approved:0,unknown:0,error:0,conflict:0,pending_review:0,
          source_gaps:0,provider_calls_recorded:0,sampled_error:null}}},
  });
  await vm.runInContext('loadOverview()',context);
  assert.match(node('presentation-status').visibleText,/Review sample: none; measured accuracy unknown/i);
});

test('expired or denied owner session does not present stale active status', async () => {
  for (const responseStatus of [401, 403]) {
    const {context, node} = workspace({}, {'/api/overview': responseStatus});
    await assert.rejects(vm.runInContext('loadOverview()', context), /authentication expired or denied/i);
    assert.match(node('presentation-status').visibleText, /authentication expired or denied/i);
    assert.doesNotMatch(node('presentation-status').visibleText, /Remote active snapshot/);
  }
});

test('loopback demo labels its served snapshot without asserting remote activation', async () => {
  const {context, node} = workspace({
    '/api/overview': {coverage: 'partial', cutoff: '2026-10-03T04:00:00Z', snapshot_sha256: 'demo-snapshot', counts: [], confirmed_distinct_attempts: null},
    '/api/sources': [],
  }, {}, '/');
  await vm.runInContext('loadOverview()', context);
  const status = node('presentation-status').visibleText;
  assert.match(status, /Local demo snapshot: demo-snapshot/);
  assert.match(status, /Remote active snapshot: unavailable/);
  assert.match(status, /Local completed revision: unavailable/);
  assert.doesNotMatch(status, /Remote active snapshot: demo-snapshot/);
});

test('Eindhoven browse row keeps its session cell concise and clickable', async () => {
  const session = JSON.stringify({session_id: 23, event_date: '2026-05-09', discipline: 'DNF', raw_fields: {buckets: Array.from({length: 50}, (_, i) => ({bucket_id: i, label: 'large source payload'}))}});
  const {context, node} = workspace({'/api/browse?offset=0': {total: 1, offset: 0, records: [{record_id: 'b'.repeat(64), source_name: 'eindhoven-noxy5', kind: 'candidate_position', event_date: '2026-05-09', session, discipline: 'DNF', record_path: 'result_rows[0]'}]}});
  await vm.runInContext('browse()', context);
  const text = node('results').visibleText;
  assert.match(text, /Session 23/);
  assert.match(text, /2026-05-09/);
  assert.doesNotMatch(text, /large source payload/);
  assert.ok(text.length < 500, `browse row has ${text.length} characters`);
});

test('Eindhoven overview uses source metadata for linked and endpoint-only records', async () => {
  const {context, node} = workspace({
    '/api/overview': {coverage: 'partial', counts: [{source_name: 'eindhoven-noxy5', collection: 'endpoint_records', kind: 'endpoint_source_record', records: 94}]},
    '/api/sources': [{source_name: 'eindhoven-noxy5', status: 'included'}],
    '/api/source?name=eindhoven-noxy5': {source_name: 'eindhoven-noxy5', metadata: {counts: {linked_result_endpoint_records: 92, endpoint_only_records: 2}}},
  });
  await vm.runInContext('loadOverview()', context);
  const text = node('overview').visibleText;
  assert.match(text, /linked_result_endpoint_records 92/);
  assert.match(text, /endpoint_only_records 2/);
});

test('v8 overview counts selected date packets by schema and keeps aggregates separate', async () => {
  const {context, node} = workspace({
    '/api/overview': {coverage: 'partial', cutoff: '2026-09-28T20:22:00Z', confirmed_distinct_attempts: null, counts: [
      {source_name: 'mabini-4545-2025-05-01', collection: 'positions', kind: 'candidate_position', records: 116},
      {source_name: 'adriatic-4375-2025-02-18', collection: 'positions', kind: 'candidate_position', records: 63},
      {source_name: 'ffessm-rankings', collection: 'positions', kind: 'candidate_position', records: 56},
      {source_name: 'ffessm-daily', collection: 'observations', kind: 'candidate_position', records: 71},
      {source_name: 'ffessm-correspondences', collection: 'relationships', kind: 'relationship', records: 56},
      {source_name: 'ffessm-correspondences', collection: 'unmatched_daily', kind: 'gap', records: 15},
      {source_name: 'apnea-file-reconciliation', collection: 'gia_team.rows', kind: 'aggregate', records: 40},
      {source_name: 'apnea-file-reconciliation', collection: 'san_mauro.rows', kind: 'aggregate', records: 10},
      {source_name: 'san-mauro-jpg', collection: 'positions', kind: 'candidate_position', records: 148},
      {source_name: 'san-mauro-jpg', collection: 'positions', kind: 'aggregate', records: 90},
    ]},
    '/api/sources': [
      {source_name: 'mabini-4545-2025-05-01', source_schema: 'aida-selected-html-packet/v1', status: 'included'},
      {source_name: 'adriatic-4375-2025-02-18', source_schema: 'aida-selected-html-packet/v1', status: 'included'},
    ],
  });
  await vm.runInContext('loadOverview()', context);
  const text = node('overview').visibleText;
  for (const phrase of ['aida_selected_date_positions 179', 'ffessm_ranking_positions 56', 'ffessm_daily_positions 71', 'ffessm_printed_field_correspondences 56', 'ffessm_unmatched_daily_positions 15', 'apnea_gia_team_aggregates 40', 'san_mauro_2026_team_aggregates 10', 'san_mauro_2025_individual_positions 148', 'san_mauro_2025_combined_aggregates 90', 'confirmed_distinct_attempts unknown']) assert.ok(text.includes(phrase), phrase);
});

test('v8 detail explains ranking dates, aggregate rows, and clipped JPG fields', async () => {
  const ranking = 'c'.repeat(64), aggregate = 'd'.repeat(64), jpg = 'e'.repeat(64);
  const {context, node} = workspace({
    ['/api/detail/' + ranking]: {source_name: 'ffessm-rankings', collection: 'positions', kind: 'candidate_position', event_date: null, date_scope: {}, citation: 'page 1 line 12 column start 1 column end 119', raw_fields: {'final-points': '39'}, parsed_fields: {'final-points': 39}, source_sha256: 'ranking-sha'},
    ['/api/detail/' + aggregate]: {source_name: 'apnea-file-reconciliation', collection: 'gia_team.rows', kind: 'aggregate', citation: 'Classifiche società 2024!B7:N7', raw_fields: {B7: {value: 'SAN MAURO APNEA'}}, parsed_fields: {}},
    ['/api/detail/' + jpg]: {source_name: 'san-mauro-jpg', collection: 'positions', kind: 'candidate_position', citation: {bbox: [0, 227, 997, 264], printed_row: 1}, raw_fields: {}, parsed_fields: {}, raw: {uncertainties: ['Five clipped printed cells remain uncertain']}},
  });
  await vm.runInContext(`detail('${ranking}')`, context);
  assert.match(node('detail').visibleText, /Ranking PDF is supplemental/);
  assert.match(node('detail').visibleText, /row date is unknown/);
  await vm.runInContext(`detail('${aggregate}')`, context);
  assert.match(node('detail').visibleText, /team aggregate, excluded from individual attempt counts/i);
  await vm.runInContext(`detail('${jpg}')`, context);
  assert.match(node('detail').visibleText, /printed_row 1/);
  assert.match(node('detail').visibleText, /clipped printed cells remain uncertain/);
});

test('FFESSM comparison presents cited printed field correspondences without attempt equality', async () => {
  const id = 'f'.repeat(64);
  const {context, node} = workspace({['/api/comparison/' + id]: {
    relationship: {type: 'shared_printed_fields', status: 'unknown', basis: 'printed values', unknown: 'attempt identity', matched_daily_date: '2025-06-27', ranking_row_date: null, same_attempt: null},
    field_correspondences: {card: {daily_printed: 'Blanc', ranking_printed: 'Blanc', daily_citation: 'page 1 line 8', ranking_citation: 'page 1 line 12'}},
    sides: [], field_differences: {}, raw_field_differences: {},
  }});
  await vm.runInContext(`showComparison('${id}')`, context);
  const text = node('comparison-detail').visibleText;
  for (const phrase of ['Printed field correspondences', 'Blanc', 'page 1 line 8', 'page 1 line 12', 'ranking_row_date unknown', 'matched_daily_date 2025-06-27', 'same_attempt unknown']) assert.ok(text.includes(phrase), phrase);
});

test('cited JPEG displays original image and bounded row citation', async () => {
  const id = '1'.repeat(64);
  const {context, node} = workspace({['/api/source-view/' + id]: {format: 'jpeg', source_sha256: 'jpg-sha', citation: {bbox: [0, 227, 997, 264], printed_row: 1}, source_value: {}, raw_fields: {}, parsed_fields: {}}});
  await vm.runInContext(`showSourceView('${id}', document.getElementById('source-view-test'))`, context);
  const area = node('source-view-test');
  assert.match(area.visibleText, /Original JPEG/);
  assert.match(area.visibleText, /printed_row/);
  const images = area.children.flatMap(c => c.children).filter(c => c.tagName === 'img');
  assert.equal(images.length, 1);
  assert.equal(images[0].src, '/api/source-view/' + id + '/image');
});


test('mapped private detail shows official placing before geographic lists and follows an exact fresh peer link', async () => {
  const href='/api/attempt-inspector?peer_anchor=synthetic&geography=international&peer_token='+ 'a'.repeat(64)+'&federation=CMAS';
  const {context,node,requests}=workspace({[href+'&limit=25&offset=0']:{peer_view:{status:'stale',reason:'Peer authority changed'},rows:[],pagination:{total:0,limit:25,offset:0}}});
  context.result={rows:[{reference:{'candidate-id':'synthetic'},candidate:{raw:{},parsed:{}},official_placing:{value:7,citation:{synthetic:'placing'}},
    common_score:{value:50,policy:'aida-baseline-v1'},comparison_lists:[{geography:'national',rank:1,denominator:2,provisional:true,href},
      {geography:'continental',rank:1,denominator:3,href},{geography:'international',rank:1,denominator:4,href}],
    hypothetical:{value:100,status:'disqualified'},hypothetical_lists:[{geography:'international',rank:2,denominator:4}]}]};
  vm.runInContext('renderAttemptInspector(result);showInspectorRow(0)',context);
  const detail=node('inspector-detail').visibleText;
  assert.ok(detail.indexOf('Official event placing') < detail.indexOf('National'));
  for(const value of ['National','Continental','International','1 / 2','1 / 3','1 / 4','provisional','aida-baseline-v1','DQ hypothetical'])assert.ok(detail.includes(value),value);
  await vm.runInContext('loadAttemptInspector(result.rows[0].comparison_lists[2].href)',context);
  assert.equal(requests[0].path,href+'&limit=25&offset=0');
  assert.equal(node('inspector-detail').visibleText,'');
  assert.match(node('inspector-summary').visibleText,/Peer authority changed/);
  assert.equal(node('inspector-results').visibleText.includes('1 / 4'),false);
});


test('copied peer links use the protected hosted endpoint while local links keep their API path', async () => {
  const href='/api/attempt-inspector?peer_anchor=synthetic&geography=international&peer_token='+ 'a'.repeat(64);
  const links = element => [ ...(element.tagName==='a' ? [element] : []), ...element.children.flatMap(links) ];
  for(const [pathname,prefix] of [['/owner-evidence','/owner-evidence'],['/owner-evidence/','/owner-evidence'],
                                 ['/',''],['/owner-evidence-untrusted','']]){
    const {context,node,requests}=workspace({[href+'&limit=25&offset=0']:{rows:[],pagination:{total:0,limit:25,offset:0}}}, {}, pathname);
    context.result={rows:[{candidate:{},comparison_lists:[{geography:'international',rank:1,denominator:1,href}]}]};
    vm.runInContext('renderAttemptInspector(result);showInspectorRow(0)',context);
    const link=links(node('inspector-detail'))[0];
    assert.equal(link.href,prefix+href,pathname);
    assert.equal(new URL(link.href,'https://poc.alphacompose.com').pathname,prefix+'/api/attempt-inspector');
    link.click();await new Promise(resolve=>setImmediate(resolve));
    assert.equal(requests[0].path,href+'&limit=25&offset=0','internal fetch path remains unprefixed');
  }
});
