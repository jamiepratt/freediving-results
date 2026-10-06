'use strict';
const assert = require('node:assert/strict');
class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.events = {}; this.attributes = {}; this.value = ''; }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.children = items; }
  setAttribute(k, v) { this.attributes[k] = v; }
  removeAttribute(k) { delete this.attributes[k]; }
  addEventListener(k, v) { this.events[k] = v; }
  focus() {}
}
const nodes = Object.fromEntries(['content', 'status', 'demo'].map(k => [k, new Element('div')]));
const documentEvents = {}, events = {}, requests = [];
global.document = {getElementById: k => nodes[k], createElement: t => new Element(t), addEventListener: (k, v) => documentEvents[k] = v};
global.window = {addEventListener: (k, v) => events[k] = v, scrollTo() {}};
global.location = {pathname: '/comparison', search: '?comparison=2026-pool-dnf-women&federation=CMAS&representation=POL&sanction_scope=default&listing_filter=all', origin: 'http://localhost'};
global.history = {pushState: (_, __, url) => { const u = new URL(url, location.origin); location.pathname = u.pathname; location.search = u.search; }};
const query = location.search, id = 'a'.repeat(64), peer = 'b'.repeat(64);
const row = {id, 'result-id': 'c'.repeat(64), 'source-name': 'Synthetic <script>name</script>', federation: 'CMAS', 'official-event-placing': 2,
  'source-category': 'Senior', 'source-gender': 'Women', 'comparable-category': 'seniors', 'age-class': null, 'para-class': null, 'represented-country': 'POL',
  final: {value: 100, unit: 'm', basis: 'post-penalty'}, score: {value: 50, policy: 'aida-baseline-v1'}, status: 'ranked', provisional: false,
  citations: [{publisher: 'Synthetic CMAS', 'final-url': 'https://example.org/final.pdf'}], 'detail-url': '/comparison/attempts/' + id + query,
  ranks: ['national', 'continental', 'international'].map(scope => ({scope, rank: 1, status: 'ranked', 'eligible-peer-denominator': 3, descriptor: {scope, policy: 'aida-baseline-v1'}, url: '/comparison/peers/' + peer + query}))};
const metadata = {target: '2026-pool-dnf-women', status: 'ranked', policy: 'aida-baseline-v1', scope: {year: 2026, environment: 'pool', discipline: 'DNF', gender: 'women', category: 'seniors'}, filters: {federation: 'CMAS', representation: 'POL', sanction_scope: 'default', listing_filter: 'all'}, coverage: {provided: 3, 'eligible-comparison-peers': 3, withheld: 0, federations: ['CMAS', 'AIDA']}};
let data = {...metadata, rows: [row]}, ok = true;
global.fetch = async (url, options) => { requests.push({url, options}); return {ok, status: ok ? 200 : 404, json: async () => data}; };
require('../../resources/public.js');
function all(n) { return [n, ...n.children.flatMap(all)]; }
function text() { return all(nodes.content).map(n => n.textContent || '').join(' | '); }
(async () => {
  await events.pageshow();
  assert.equal(requests[0].url, '/api/comparison' + query);
  assert.match(text(), /2026 pool DNF women/);
  assert.match(text(), /3 eligible comparison peers/);
  assert.match(text(), /Official event placing/);
  const labels = all(nodes.content).map(n => n.textContent || '');
  assert.ok(labels.indexOf('Official event placing') < labels.indexOf('National'));
  assert.match(text(), /Peer denominator: 3/);
  assert.match(text(), /50/);
  assert.ok(all(nodes.content).some(n => n.href === row['detail-url']));
  assert.ok(all(nodes.content).some(n => n.href === row.ranks[0].url));
  assert.match(text(), /Synthetic <script>name<\/script>/);
  assert.ok(!all(nodes.content).some(n => n.tag === 'script' || n.innerHTML));
  console.log('Rendered public comparison list passed');
  location.pathname = '/comparison/attempts/' + id;
  data = {...metadata, attempt: {...row, provisional: true}};
  await events.pageshow();
  assert.equal(requests.at(-1).url, '/api/comparison/attempts/' + id + query);
  assert.match(text(), /Provisional comparison: conflicting source authority/);
  assert.match(text(), /Source gender: Women/);
  assert.match(text(), /Comparable category: seniors/);
  assert.match(text(), /Age class: unknown/);
  assert.match(text(), /Para class: unknown/);
  assert.match(text(), /Represented country: POL/);
  assert.match(text(), /Event listing: unknown/);
  assert.match(text(), /Event sanction: unknown/);
  assert.ok(all(nodes.content).some(n => n.href === 'https://example.org/final.pdf'));
  assert.ok(all(nodes.content).some(n => n.href === '/results/' + row['result-id']));
  assert.match(text(), /Evidence coverage cutoff: unknown/);
  const authority = 'e'.repeat(64), sourcePin = 'f'.repeat(64), artifactPin = '1'.repeat(64);
  location.search = query + '&authority=' + authority;
  data = {...metadata, authority: {'cohort-id': '2'.repeat(64), revision: 7, ledger: 'public-sporting-authority-v1'}, attempt: {...row,
    'detail-url': '/comparison/attempts/' + id + location.search, listing: 'international', sanction: 'international',
    provenance: {'result-id': row['result-id'], 'observation-id': '3'.repeat(64), ordinal: 56, 'source-sha256': sourcePin, 'artifact-sha256': artifactPin},
    citations: {finality: {url: 'https://example.org/finality.pdf', page: 5, line: 9, 'source-sha256': sourcePin, 'artifact-sha256': artifactPin}, 'source-gender': {url: 'https://example.org/gender.pdf', table: 1, row: 56}}}};
  await events.pageshow();
  assert.equal(requests.at(-1).url, '/api/comparison/attempts/' + id + query + '&authority=' + authority);
  assert.match(text(), /Finality/);
  assert.match(text(), /Source gender/);
  assert.match(text(), /Page: 5/);
  assert.match(text(), /Line: 9/);
  assert.match(text(), /Event listing: international/);
  assert.match(text(), /Event sanction: international/);
  assert.match(text(), /Current authority: cohort-id: .*revision: 7.*ledger: public-sporting-authority-v1/);
  assert.match(text(), /Observation Id: /);
  assert.match(text(), /Ordinal: 56/);
  assert.ok(all(nodes.content).some(n => n.href === 'https://example.org/finality.pdf'));
  assert.ok(all(nodes.content).some(n => n.href === 'https://example.org/gender.pdf'));
  assert.ok(text().includes(sourcePin) && text().includes(artifactPin));
  assert.equal(all(nodes.content).find(n => n.textContent === 'Current comparison list').href, '/comparison' + query);
  location.search = query;
  location.pathname = '/comparison/peers/' + peer;
  data = {...metadata, rows: [row, {...row, id: 'd'.repeat(64), federation: 'AIDA'}], descriptor: {denominator: 3, 'peer-ids': [id, 'd'.repeat(64)], 'comparison-policy': 'aida-baseline-v1', 'represented-geography-policy': 'sports-geography-v1', 'sanction-scope': 'default', 'listing-filter': 'all', geography: 'international', provisional: false}};
  await events.pageshow();
  assert.equal(requests.at(-1).url, '/api/comparison/peers/' + peer + query);
  assert.match(text(), /Exact comparison peers/);
  assert.match(text(), /Exact peer provenance/);
  assert.match(text(), /"peer-ids":/);
  assert.match(text(), /represented-geography-policy:/);
  assert.equal(all(nodes.content).filter(n => n.textContent === 'Rank 1').length, 6);
  // Refresh removes old authority before the new request resolves.
  let resolveRequest;
  global.fetch = (url, options) => { requests.push({url, options}); return new Promise(resolve => resolveRequest = resolve); };
  const pending = events.pageshow();
  assert.equal(nodes.content.children.length, 0);
  assert.equal(nodes.content.attributes['aria-busy'], 'true');
  resolveRequest({ok: false, status: 404}); await pending;
  assert.match(text(), /Comparison unavailable/);
  assert.match(text(), /Ranks are withheld/);
  assert.doesNotMatch(text(), /Rank 1|Achieved comparison score|Exact peer provenance/);
  assert.ok(!all(nodes.content).some(n => n.href === row.ranks[0].url));
  assert.ok(requests.every(request => request.options.cache === 'no-store' && request.options.credentials === 'omit'));
  events.pagehide(); assert.equal(nodes.content.children.length, 0);
  // Empty authority never invents a rank or a sporting attempt count.
  location.pathname = '/comparison';
  data = {...metadata, status: 'withheld', rows: [], coverage: {provided: 0, 'eligible-comparison-peers': 0, withheld: 0}, 'scope-gaps': ['Current sporting authority unavailable']};
  global.fetch = async () => ({ok: true, json: async () => data});
  await events.pageshow();
  assert.match(text(), /0 eligible comparison peers/);
  assert.match(text(), /Current sporting authority unavailable/);
  assert.match(text(), /does not mean zero sporting attempts/);
  assert.ok(all(nodes.content).some(n => n.href === '/?comparison=2026-pool-dnf-women'));
  data = {...metadata, status: 'withheld', rows: [row]};
  await events.pageshow();
  assert.doesNotMatch(text(), /Rank 1|Achieved comparison score/);
  assert.ok(!all(nodes.content).some(n => n.href === row.ranks[0].url));
  data = {...metadata, 'evidence-coverage-cutoff': '2026-10-01T12:39:47Z', 'projection-read-at': '2026-10-06T15:00:00Z', rows: [{...row, status: 'ineligible', hypothetical: {rank: 2, value: 99, unit: 'm', 'eligible-peer-denominator': 3, status: 'disqualified', basis: 'verified-source-achieved', score: {value: 49.5, policy: 'aida-baseline-v1'}, meaning: 'Hypothetical achieved-value score excluding disqualification; not a final valid result.', citation: {publisher: 'Synthetic AIDA', url: 'https://example.org/dq.pdf'}}}]};
  await events.pageshow();
  assert.match(text(), /Disqualified attempt - hypothetical only/);
  assert.match(text(), /Excluded from achieved ranks and peer denominators/);
  assert.match(text(), /Hypothetical rank: 2/);
  assert.match(text(), /Hypothetical comparison score: 49.5/);
  assert.match(text(), /Hypothetical achieved-value score excluding disqualification; not a final valid result/);
  assert.doesNotMatch(text(), /Achieved comparison score/);
  assert.doesNotMatch(text(), /Final post-penalty value/);
  assert.match(text(), /Evidence coverage cutoff: 2026-10-01T12:39:47Z/);
  assert.match(text(), /Projection readback time: 2026-10-06T15:00:00Z/);
  assert.ok(all(nodes.content).some(n => n.href === 'https://example.org/dq.pdf'));
  data = {...data, status: 'withheld'}; await events.pageshow();
  assert.doesNotMatch(text(), /Hypothetical rank|Provisional comparison/);
  global.FormData = class { constructor(form) { return all(form).filter(n => n.name).map(n => [n.name, n.value]); } };
  const filterForm = all(nodes.content).find(n => n.attributes['aria-label'] === 'Filter sporting comparison');
  all(filterForm).find(n => n.name === 'sanction_scope').value = 'broad';
  all(filterForm).find(n => n.name === 'listing_filter').value = 'national-local-only';
  filterForm.events.submit({preventDefault() {}});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(location.pathname, '/comparison');
  assert.equal(location.search, '?comparison=2026-pool-dnf-women&federation=CMAS&representation=POL&sanction_scope=broad&listing_filter=national-local-only');
  console.log('Rendered current detail, exact peers, refresh, revoked and empty comparison passed');
})().catch(e => { console.error(e); process.exitCode = 1; });
