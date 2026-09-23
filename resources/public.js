(function () {
  'use strict';
  const keys = ['q', 'federation', 'discipline', 'category', 'date', 'page'];
  function searchURL(values) {
    const p = new URLSearchParams();
    keys.forEach(k => { if (values[k]) p.set(k, values[k]); });
    p.set('limit', '10');
    return '/api/results?' + p.toString();
  }
  function display(v) {
    if (v == null || v === '') return 'Not recorded';
    if (Array.isArray(v)) return v.map(display).join(' · ');
    if (typeof v === 'object') return v.raw != null ? String(v.raw) : Object.entries(v).map(([k, x]) => k + ': ' + display(x)).join(' · ');
    return String(v);
  }
  function performance(f) {
    const key = ['performance', 'final-depth', 'final-distance', 'realized-distance', 'final-time', 'realized-time', 'final-duration', 'realized-duration', 'duration'].find(k => f[k] != null);
    return {label: key ? key[0].toUpperCase() + key.slice(1).replaceAll('-', ' ') : 'Performance', value: display(key ? f[key] : null)};
  }
  function comparison(r) {
    return [...new Set([...Object.keys(r.original || {}), ...Object.keys(r.effective || {}), ...Object.keys(r['raw-values'] || {})])]
      .map(k => [k, display((r['raw-values'] || {})[k]), display((r.original || {})[k]), display((r.effective || {})[k])]);
  }
  function internalLink(kind, id) { return /^(results|athletes)$/.test(kind) && /^[a-f0-9]{64}$/.test(id || '') ? '/' + kind + '/' + id : null; }
  function citationURL(v) { try { const u = new URL(v); return ['https:', 'http:'].includes(u.protocol) && !u.username && !u.password ? u.href : null; } catch (_) { return null; } }
  if (typeof module !== 'undefined') module.exports = {searchURL, display, performance, comparison, internalLink, citationURL};
  if (typeof document === 'undefined') return;
  const main = document.getElementById('content'), status = document.getElementById('status'), banner = document.getElementById('demo');
  let sequence = 0;
  const label = k => k === 'representation' ? 'Event representation (not citizenship)' : k.split('-').map(x => x[0].toUpperCase() + x.slice(1)).join(' ');
  function el(tag, text, cls) { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; }
  function link(text, href, cls) { const n = el('a', text, cls); if (href) n.href = href; return n; }
  function section(title, sub) { const n = el('section', null, 'panel'); n.append(el('h2', title)); if (sub) n.append(el('p', sub, 'muted')); return n; }
  function values() { const p = new URLSearchParams(location.search); return Object.fromEntries(keys.map(k => [k, p.get(k) || ''])); }
  function navigate(url) { history.pushState(null, '', url); load(); }
  function searchForm(v, filters) {
    const form = el('form', null, 'search-form'); form.setAttribute('aria-label', 'Search and filter results');
    const wrap = el('div', null, 'search-box'); const l = el('label', 'Source name'); l.htmlFor = 'q';
    const input = el('input'); input.id = 'q'; input.name = 'q'; input.type = 'search'; input.maxLength = 200; input.value = v.q || ''; input.placeholder = 'Search a name as printed in the source';
    wrap.append(l, input); form.append(wrap);
    const grid = el('div', null, 'filters');
    ['federation', 'discipline', 'category', 'date'].forEach(k => {
      const w = el('div'); const lab = el('label', k === 'date' ? 'Event date' : label(k)); lab.htmlFor = k;
      const s = el('select'); s.id = k; s.name = k; const all = el('option', 'All ' + (k === 'date' ? 'dates' : k === 'category' ? 'categories' : k + 's')); all.value = ''; s.append(all);
      const options = [...new Set([...(filters[k] || []), ...(v[k] ? [v[k]] : [])])]; options.forEach(x => { const o = el('option', display(x)); o.value = x; s.append(o); }); s.value = v[k] || ''; w.append(lab, s); grid.append(w);
    });
    form.append(grid); const actions = el('div', null, 'actions'); const submit = el('button', 'Search results'); submit.type = 'submit'; actions.append(submit, link('Clear filters', '/', 'text-link')); form.append(actions);
    form.addEventListener('submit', e => { e.preventDefault(); const data = Object.fromEntries(new FormData(form)); const p = new URLSearchParams(); keys.filter(k => k !== 'page').forEach(k => { if (data[k]) p.set(k, data[k]); }); navigate('/' + (p.size ? '?' + p : '')); });
    return form;
  }
  function resultCards(rows) {
    const list = el('div', null, 'result-list');
    rows.forEach(r => {
      const f = r.effective || {}, card = el('article', null, 'result-card');
      const info = el('div'); info.append(el('p', [display(f.federation), display(f.discipline)].join(' / '), 'eyebrow'));
      const title = el('h3'); title.append(link(display(f['source-name']), internalLink('results', r['result-id']))); info.append(title);
      if ((r.original || {})['source-name'] !== f['source-name']) info.append(el('p', 'Original source name: ' + display((r.original || {})['source-name']), 'muted'));
      info.append(el('p', 'Event: ' + display(f['event-name']) + ' · Date: ' + display(f['event-date']), 'muted'));
      const tags = el('div', null, 'tags'); tags.append(el('span', 'Category: ' + display(f.category)), el('span', 'Representation: ' + display(f.representation)), el('span', r.identity && r.identity.status === 'approved' ? 'Approved identity link' : 'Identity unresolved')); info.append(tags);
      const perf = performance(f), metric = el('div', null, 'performance'); metric.append(el('span', perf.label), el('strong', perf.value), el('span', 'Unit: ' + display(f.unit)), el('span', 'Status: ' + display(f.status)));
      card.append(info, metric); list.append(card);
    }); return list;
  }
  function evidence(refs) {
    const n = el('ul', null, 'evidence'); (refs || []).forEach(ref => { const item = el('li'); item.append(link('Source page ' + display(ref.page) + ', line ' + display(ref.line), internalLink('results', ref['result-id']))); n.append(item); }); return n;
  }
  function detail(r) {
    const f = r.effective || {}; main.append(link('← Search results', '/', 'back-link'), el('p', 'RESULT / SOURCE RECORD', 'eyebrow'), el('h1', display(f['source-name'])), el('p', 'Event: ' + display(f['event-name']) + ' · Date: ' + display(f['event-date']), 'lead'));
    const summary = el('div', null, 'summary'); [['Federation', f.federation], ['Discipline', f.discipline], [performance(f).label, performance(f).value], ['Unit', f.unit], ['Category', f.category], ['Status', f.status], ['Event representation', f.representation]].forEach(([k, v]) => { const d = el('div'); d.append(el('span', k), el('strong', display(v))); summary.append(d); }); main.append(summary);
    const identity = section('Athlete connection');
    if (r.identity && r.identity.status === 'approved' && internalLink('athletes', r.identity.id)) identity.append(el('p', 'This record has an active approved identity link.'), link('View approved athlete history →', internalLink('athletes', r.identity.id)));
    else identity.append(el('p', 'Identity unresolved. This validated source record is searchable under its source name; no athlete identity is inferred.'));
    main.append(identity);
    const original = section('From the source to this result', 'Original source tokens are retained separately from parsed values and effective approved corrections. “Not recorded” means the source value is unavailable; units and successful outcomes are never inferred.');
    const scroll = el('div', null, 'table-scroll'); scroll.tabIndex = 0; scroll.setAttribute('role', 'region'); scroll.setAttribute('aria-label', 'Source and corrected values');
    const table = el('table'), head = el('thead'), hr = el('tr'); ['Field', 'Original source token', 'Original parsed value', 'Effective value'].forEach(t => {const th = el('th', t); th.scope = 'col'; hr.append(th); }); head.append(hr); table.append(head); const body = el('tbody'); comparison(r).forEach(([k, ...v]) => { const tr = el('tr'), th = el('th', label(k)); th.scope = 'row'; tr.append(th); v.forEach(x => tr.append(el('td', x))); body.append(tr); }); table.append(body); scroll.append(table); original.append(scroll); main.append(original);
    const audit = section('Public correction history', 'Approved corrections and reversals, with their public reasons and evidence.');
    if (!(r['correction-audit'] || []).length) audit.append(el('p', 'No public corrections recorded.'));
    (r['correction-audit'] || []).forEach(a => { const entry = el('article', null, 'audit-entry'); entry.append(el('p', (a.action === 'reverse' ? 'Reversed' : 'Approved') + ' · ' + label(a.field), 'eyebrow'), el('h3', display(a.before) + ' → ' + display(a.after)), el('p', a.reason || 'No public reason recorded.')); if (a['correction-reason'] && a['correction-reason'] !== a.reason) entry.append(el('p', 'Correction rationale: ' + a['correction-reason'])); entry.append(el('p', display(a['recorded-at']) + (a.action === 'approve' ? (a['effective?'] ? ' · Currently effective' : ' · No longer effective') : ' · Restored the prior value'), 'muted'), evidence(a.evidence)); audit.append(entry); }); main.append(audit);
    const sources = section('Source evidence', 'Source representation describes the event entry. It does not establish citizenship.'); sources.append(el('p', 'Source page ' + display((r['source-position'] || {}).page) + ', line ' + display((r['source-position'] || {}).line)));
    (r.citations || []).forEach(c => { const p = el('p'); p.append(el('strong', display(c.publisher) + ' ')); const u = citationURL(c['final-url'] || c['discovery-url']); if (u) { const a = link('Open source citation ↗', u); a.rel = 'noopener noreferrer'; p.append(a); } else p.append(el('span', 'Source link unavailable')); sources.append(p); sources.append(el('p', 'Source relationship: ' + display(c.relationship) + (c['mirror-of'] ? ' · Mirror of: ' + display(c['mirror-of']) : ''), 'muted')); if (c['source-sha256']) { const metadata = el('details'); metadata.append(el('summary', 'Source fingerprint'), el('p', c['source-sha256'], 'muted')); sources.append(metadata); } }); main.append(sources);
    main.append(el('p', 'Anonymous correction submissions are not available in this local pilot.', 'muted'));
  }
  async function load() {
    const token = ++sequence; main.replaceChildren(); status.textContent = 'Loading public records…'; main.setAttribute('aria-busy', 'true'); banner.hidden = true;
    const path = location.pathname; const search = path === '/';
    try {
      const response = await fetch(search ? searchURL(values()) : '/api' + path, {cache: 'no-store', credentials: 'omit', headers: {'Accept': 'application/json'}});
      if (!response.ok) throw new Error(response.status === 404 ? 'unavailable' : 'error');
      const data = await response.json(); if (token !== sequence) return;
      banner.hidden = !data.demo;
      if (search) {
        main.append(el('p', 'FREEDIVING / RESULTS ARCHIVE', 'eyebrow'), el('h1', 'Every result has a source.'), el('p', 'Explore validated source records. Follow the evidence, see approved corrections, and discover connected results.', 'lead'));
        main.append(searchForm(values(), data.filters || {})); if (data.coverage) main.append(el('p', 'Partial pilot coverage · ' + display(data.coverage.results) + ' public records · ' + display(data.coverage.approved_identities) + (data.coverage.approved_identities === 1 ? ' approved athlete history' : ' approved athlete histories'), 'muted'));
        const heading = el('div', null, 'results-heading'); heading.append(el('h2', 'Public results'), el('p', data.total + ' matching ' + (data.total === 1 ? 'record' : 'records'), 'muted')); main.append(heading);
        if (data.results.length) main.append(resultCards(data.results)); else main.append(el('div', 'No public results match these filters. Try another source name or clear the filters.', 'empty'));
        const nav = el('nav', null, 'pagination'); nav.setAttribute('aria-label', 'Result pages');
        const pageLink = (text, page) => { const p = new URLSearchParams(location.search); p.set('page', page); return link(text, '/?' + p); };
        if (data.page > 1) nav.append(pageLink('← Previous', data.page - 1)); nav.append(el('span', 'Page ' + data.page + ' of ' + Math.max(1, data.pages))); if (data.page < data.pages) nav.append(pageLink('Next →', data.page + 1)); main.append(nav);
        status.textContent = data.total + ' matching public records. Page ' + data.page + '.';
      } else if (path.startsWith('/results/')) { detail(data.result); status.textContent = 'Public result loaded.'; }
      else {
        main.append(link('← Search results', '/', 'back-link'), el('p', 'APPROVED CONNECTIONS', 'eyebrow'), el('h1', 'Athlete result history'), el('p', 'Only currently eligible records with active approved identity links appear here. Source names are retained as recorded.', 'lead'));
        const names = [...new Set(data.results.map(r => (r.effective || {})['source-name']).filter(Boolean))]; main.append(el('p', 'Names in these sources: ' + names.join(' · '), 'source-names'), resultCards(data.results)); status.textContent = data.results.length + ' linked public records.';
      }
    } catch (e) {
      if (token !== sequence) return; main.replaceChildren(); main.append(el('h1', e.message === 'unavailable' ? 'Record unavailable' : 'Results unavailable'), el('p', e.message === 'unavailable' ? 'This public record is unavailable.' : 'Public results could not be loaded. Please try again.', 'lead'), link('Return to search', '/')); const retry = el('button', 'Try again'); retry.addEventListener('click', load); main.append(retry); status.textContent = 'Public records unavailable.';
    } finally { if (token === sequence) main.removeAttribute('aria-busy'); }
  }
  document.addEventListener('click', e => { const a = e.target.closest('a'); if (!a || !a.href || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button) return; const u = new URL(a.href); if (u.origin === location.origin && !u.hash) { e.preventDefault(); navigate(u.pathname + u.search); main.focus(); window.scrollTo(0, 0); } });
  window.addEventListener('popstate', load);
  window.addEventListener('pageshow', load);
  window.addEventListener('pagehide', () => { sequence++; main.replaceChildren(); });
})();
