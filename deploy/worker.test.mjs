import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
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
test('private path fails closed without Access configuration', async () => {
  const response = await worker.fetch(req('/owner-evidence'), env);
  assert.equal(response.status, 503);
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
});

const privateEnv = {
  ...env,
  ACCESS_ISSUER: 'https://test.cloudflareaccess.com',
  ACCESS_AUDIENCE: 'a'.repeat(32),
  OWNER_EVIDENCE_EMAILS: 'owner@example.com',
  OWNER_EVIDENCE_UPSTREAM: 'https://owner-origin.alphacompose.com',
  OWNER_EVIDENCE_GATEWAY_SECRET: 'private-test-secret'
};
const pair = await webcrypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
const jwk = {...await webcrypto.subtle.exportKey('jwk',pair.publicKey),kid:'test-key',alg:'RS256',use:'sig'};
const base64url = (bytes) => Buffer.from(bytes).toString('base64url');
async function token(overrides={}, signingKey=pair.privateKey) {
  const now = Math.floor(Date.now()/1000);
  const header = base64url(JSON.stringify({alg:'RS256',kid:'test-key',typ:'JWT'}));
  const payload = base64url(JSON.stringify({iss:privateEnv.ACCESS_ISSUER,aud:[privateEnv.ACCESS_AUDIENCE],type:'app',email:'owner@example.com',iat:now,nbf:now-1,exp:now+120,...overrides}));
  const data = `${header}.${payload}`;
  return `${data}.${base64url(await webcrypto.subtle.sign('RSASSA-PKCS1-v1_5',signingKey,new TextEncoder().encode(data)))}`;
}
async function withPrivateFetch(run, upstreamResponse=() => new Response('private rows',{headers:{'Content-Type':'text/plain','Cache-Control':'public','Set-Cookie':'leak=1'}})) {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push([url,options]);
    if (url === `${privateEnv.ACCESS_ISSUER}/cdn-cgi/access/certs`) return Response.json({keys:[jwk]});
    assert.equal(url,'https://owner-origin.alphacompose.com/owner-evidence/rows?q=one');
    assert.equal(options.headers.get('X-Freediving-Owner-Gateway'),'private-test-secret');
    assert.equal(options.headers.get('X-Freediving-Owner-Email'),'owner@example.com');
    for (const name of ['Cf-Access-Jwt-Assertion','Cookie','Authorization','X-Freediving-Gateway','X-Freediving-Owner-Gateway','X-Forwarded-For']) {
      if (name === 'X-Freediving-Owner-Gateway') continue;
      assert.equal(options.headers.get(name),null,name);
    }
    assert.equal(options.redirect,'manual');
    return upstreamResponse();
  };
  try { return await run(calls); } finally { globalThis.fetch=original; }
}
test('signed owner Access token reaches only the private upstream with isolated headers', async () => {
  await withPrivateFetch(async (calls) => {
    const response = await worker.fetch(req('/owner-evidence/rows?q=one',{headers:{'Cf-Access-Jwt-Assertion':await token(),'Cookie':'fake','Authorization':'fake','X-Freediving-Gateway':'fake','X-Freediving-Owner-Gateway':'fake','X-Forwarded-For':'fake'}}),privateEnv);
    assert.equal(response.status,200);
    assert.equal(await response.text(),'private rows');
    assert.equal(response.headers.get('Cache-Control'),'no-store');
    assert.equal(response.headers.get('Set-Cookie'),null);
    assert.equal(calls.length,2);
  });
});
test('private gateway rejects invalid assertions and non-owner identities', async () => {
  await withPrivateFetch(async (calls) => {
    const now = Math.floor(Date.now()/1000);
    const otherPair = await webcrypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
    for (const assertion of [
      undefined,
      'not.a.jwt',
      await token({email:'other@example.com'}),
      await token({type:'service-token'}),
      await token({aud:['b'.repeat(32)]}),
      await token({iss:'https://other.cloudflareaccess.com'}),
      await token({exp:now-1}),
      await token({nbf:now+60}),
      await token({},otherPair.privateKey)
    ]) {
      const headers = assertion ? {'Cf-Access-Jwt-Assertion':assertion} : {};
      assert.equal((await worker.fetch(req('/owner-evidence/rows',{headers}),privateEnv)).status,403);
    }
    const valid = await token();
    const [head,payload,signature] = valid.split('.');
    const tampered = `${head}.${base64url(JSON.stringify({iss:privateEnv.ACCESS_ISSUER,aud:[privateEnv.ACCESS_AUDIENCE],type:'app',email:'owner@example.com',exp:now+120,nbf:now-1,iat:now,extra:'tampered'}))}.${signature}`;
    assert.equal((await worker.fetch(req('/owner-evidence/rows',{headers:{'Cf-Access-Jwt-Assertion':tampered}}),privateEnv)).status,403);
    assert.equal(calls.some(([url]) => String(url).startsWith(privateEnv.OWNER_EVIDENCE_UPSTREAM)),false);
  });
});
test('private gateway rejects upstream redirects', async () => {
  await withPrivateFetch(async () => {
    const response = await worker.fetch(req('/owner-evidence/rows?q=one',{headers:{'Cf-Access-Jwt-Assertion':await token()}}),privateEnv);
    assert.equal(response.status,502);
    assert.equal(response.headers.get('Location'),null);
    assert.equal(response.headers.get('Cache-Control'),'no-store');
  }, () => new Response(null,{status:302,headers:{Location:'https://evil.example/'}}));
});
test('private HEAD response has no body', async () => {
  await withPrivateFetch(async () => {
    const response = await worker.fetch(req('/owner-evidence/rows?q=one',{method:'HEAD',headers:{'Cf-Access-Jwt-Assertion':await token()}}),privateEnv);
    assert.equal(response.status,200);
    assert.equal(await response.text(),'');
  });
});
test('private path and configuration boundaries are closed', async () => {
  const signed = await token();
  const headers = {'Cf-Access-Jwt-Assertion':signed};
  assert.equal((await worker.fetch(req('/owner-evidence-other',{headers}),privateEnv)).status,404);
  assert.equal((await worker.fetch(new Request('http://poc.alphacompose.com/owner-evidence',{headers}),privateEnv)).status,403);
  assert.equal((await worker.fetch(req('/owner-evidence/%2Fapi',{headers}),privateEnv)).status,404);
  assert.equal((await worker.fetch(req('/owner-evidence',{method:'POST',headers:{...headers,Origin:'https://poc.alphacompose.com'},body:'x'}),privateEnv)).status,405);
  assert.equal((await worker.fetch(req('/owner-evidence',{headers:{...headers,Origin:'https://evil.example'}}),privateEnv)).status,403);
  for (const change of [
    {ACCESS_AUDIENCE:''},
    {OWNER_EVIDENCE_EMAILS:''},
    {OWNER_EVIDENCE_UPSTREAM:'http://owner-origin.alphacompose.com'},
    {OWNER_EVIDENCE_UPSTREAM:'https://poc-origin.alphacompose.com'},
    {OWNER_EVIDENCE_UPSTREAM:'https://owner-origin.evil.example'},
    {OWNER_EVIDENCE_GATEWAY_SECRET:''}
  ]) assert.equal((await worker.fetch(req('/owner-evidence',{headers}),{...privateEnv,...change})).status,503);
});
