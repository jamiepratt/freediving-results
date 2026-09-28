import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('../resources/evidence_workspace.js', import.meta.url), 'utf8');

class Element {
  constructor(tag = 'div') {
    this.tagName = tag;
    this.children = [];
    this.textContent = '';
    this.listeners = {};
    this.value = '';
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = [...children]; this.textContent = ''; }
  addEventListener(name, fn) { this.listeners[name] = fn; }
  get allText() { return [this.textContent, ...this.children.map(c => c.allText)].join(' '); }
  find(tag) { return this.tagName === tag ? [this] : this.children.flatMap(c => c.find(tag)); }
}

function workspace() {
  const elements = new Map();
  const document = {
    createElement: tag => new Element(tag),
    getElementById: id => {
      if (!elements.has(id)) elements.set(id, new Element());
      return elements.get(id);
    },
    addEventListener: () => {},
  };
  const context = vm.createContext({document, URL, URLSearchParams, FormData, fetch: () => { throw new Error('unexpected fetch'); }});
  vm.runInContext(script, context);
  return {context, elements, get: id => document.getElementById(id)};
}

test('route coverage shows dated partial counts, publisher, gaps, and safe citations', () => {
  const page = workspace();
  vm.runInContext(`renderRoutes(${JSON.stringify({
    cutoff: '2026-09-28T15:59:12Z',
    summary: {route_count: 8, lead_count: 42},
    routes: [{id: 'aida', authority: '<svg/onload=alert(1)>', status: 'inaccessible', role: 'primary', checked_at: '2026-09-28T15:59:10Z', gaps: ['HTTP 403; absence unknown'], discovery_url: 'javascript:alert(1)', citation: {url: 'https://example.org/route', locator: 'HTTP 403 response', sha256: 'abcd'}}]
  })})`, page.context);
  assert.match(page.get('route-summary').allText, /8 route groups.*42 leads.*2026-09-28/);
  assert.match(page.get('route-list').allText, /<svg\/onload=alert\(1\)>/);
  assert.match(page.get('route-list').allText, /HTTP 403; absence unknown/);
  const anchors = page.get('route-list').find('a');
  assert.equal(anchors.length, 1);
  assert.equal(anchors[0].href, 'https://example.org/route');
  assert.equal(anchors[0].rel, 'noopener noreferrer');
  assert.equal(script.includes('innerHTML'), false);
});

test('route leads preserve exact and candidate source link separation', () => {
  const page = workspace();
  vm.runInContext(`renderRouteLeads(${JSON.stringify({
    total: 42, offset: 0, limit: 25,
    items: [{id: 'ffessm-2026:00', route_id: 'ffessm', authority: 'FFESSM', title: 'Monopalme Femmes', competition_year: 2026, competition_date: null, status: 'acquired', relationship: 'primary', gaps: ['Competition date unknown'], citation: {url: 'https://example.org/index', locator: 'a[128]', sha256: 'abcd'}, exact_source_records: [{source_name: 'ffessm', record_id: '1'.repeat(64)}], candidate_source_records: [{source_name: 'maybe', record_id: '2'.repeat(64)}]}]
  })})`, page.context);
  assert.match(page.get('route-lead-summary').allText, /42 leads.*1 to 1/);
  assert.match(page.get('route-lead-results').allText, /Competition date unknown/);
  assert.match(page.get('route-lead-results').allText, /Exact source record/);
  assert.match(page.get('route-lead-results').allText, /Candidate source record/);
  assert.equal(page.get('route-previous').disabled, true);
  assert.equal(page.get('route-next').disabled, false);
});
