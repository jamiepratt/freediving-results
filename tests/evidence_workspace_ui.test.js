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

function workspace(responses = {}) {
  const nodes = new Map();
  const requests = [];
  const find = (element, id) => element.id === id ? element : element.children.map(child => find(child, id)).find(Boolean);
  const document = {
    createElement: tag => new Element(tag),
    getElementById: id => { const nested = [...nodes.values()].map(node => find(node, id)).find(Boolean); if(nested) return nested; if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); },
    addEventListener() {},
  };
  const context = vm.createContext({document, fetch: async (path, options) => { requests.push({path, options}); return {ok: true, status: 200, json: async () => responses[path]}; }, location: {origin: 'https://owner.example'}, crypto: {randomUUID: () => 'retry-key'}, URLSearchParams, FormData: class { *[Symbol.iterator]() {} }});
  vm.runInContext(fs.readFileSync('resources/evidence_workspace.js', 'utf8'), context);
  return {context, node: document.getElementById, requests};
}

test('owner decision queue separates scoreless gaps and shows ascending provider confidence', async () => {
  const {context, node} = workspace({'/api/decisions?status=&limit=25&offset=0': {
    revision: 7, snapshot_sha256: 'snapshot-7', total: 4, scoreless_total: 1, items: [
      {id: 'low', type: 'athlete_identity', source_name: 'AIDA', status: 'pending', provider_confidence: 0.41, proposed: 'one'},
      {id: 'high', type: 'athlete_identity', source_name: 'AIDA', status: 'pending', provider_confidence: 0.88, proposed: 'two'},
      {id: 'auto', type: 'athlete_identity', source_name: 'AIDA', status: 'automatic_approved', provider_confidence: 0.99, proposed: 'three'},
    ], scoreless_items: [{id: 'gap', type: 'source_revision', source_name: 'CMAS', status: 'pending', provider_confidence: null, proposed: 'unknown'}],
  }});
  await vm.runInContext('loadDecisions()', context);
  const scored = node('decision-scored').visibleText;
  assert.ok(scored.indexOf('low') < scored.indexOf('high'));
  assert.match(node('decision-scoreless').visibleText, /gap/);
  assert.match(node('decision-automatic').visibleText, /auto/);
  assert.match(node('decision-summary').visibleText, /uncalibrated/i);
  assert.match(node('decision-summary').visibleText, /snapshot-7/);
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
