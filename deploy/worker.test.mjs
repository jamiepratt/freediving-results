import {test} from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
const env = {GATEWAY_SECRET: 'test-secret'};
const req = (path, init={}) => new Request('https://poc.alphacompose.com'+path, {
  ...init, headers: {'CF-Connecting-IP':'203.0.113.10', ...init.headers}
});
test('gateway rejects nonpublic routes, cross-origin writes and oversized bodies', async () => {
  assert.equal((await worker.fetch(req('/api/owner'),env)).status,404);
  assert.equal((await worker.fetch(req('/api/corrections',{method:'POST'}),env)).status,403);
  assert.equal((await worker.fetch(req('/api/results',{headers:{Origin:'https://evil.example'}}),env)).status,403);
  assert.equal((await worker.fetch(req('/api/corrections',{method:'POST',headers:{Origin:'https://poc.alphacompose.com'},body:'x'.repeat(16385)}),env)).status,413);
  assert.equal((await worker.fetch(req('/'),{})).status,503);
});
test('gateway replaces spoofed client and authentication headers; never caches', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.equal(url,'https://poc-origin.alphacompose.com/api/results?q=Ann');
    assert.equal(options.headers.get('X-Freediving-Client'),'203.0.113.10');
    assert.equal(options.headers.get('X-Freediving-Gateway'),'test-secret');
    assert.equal(options.headers.get('Cookie'),null);
    assert.equal(options.headers.get('X-Forwarded-For'),null);
    return new Response('{}',{headers:{'Cache-Control':'public'}});
  };
  try {
    const r = await worker.fetch(req('/api/results?q=Ann',{headers:{'X-Freediving-Client':'fake','X-Freediving-Gateway':'fake','Cookie':'private','X-Forwarded-For':'fake'}}),env);
    assert.equal(r.status,200); assert.equal(r.headers.get('Cache-Control'),'no-store');
  } finally { globalThis.fetch=original; }
});
