const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
  constructor(tag = 'div') { this.tagName = tag; this.children = []; this.textContent = ''; this.value = ''; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = [...children]; this.textContent = ''; }
  addEventListener() {}
  get visibleText() { return [this.textContent, ...this.children.map(x => x.visibleText ?? x.textContent ?? '')].join(' '); }
}

function workspace(responses = {}) {
  const nodes = new Map();
  const document = {
    createElement: tag => new Element(tag),
    getElementById: id => { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); },
    addEventListener() {},
  };
  const context = vm.createContext({document, fetch: async path => ({ok: true, status: 200, json: async () => responses[path]}), location: {}, URLSearchParams, FormData: class { *[Symbol.iterator]() {} }});
  vm.runInContext(fs.readFileSync('resources/evidence_workspace.js', 'utf8'), context);
  return {context, node: document.getElementById};
}

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
