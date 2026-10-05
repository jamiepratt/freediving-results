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
