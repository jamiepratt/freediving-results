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
    if (url === `${privateEnv.ACCESS_ISSUER}/cdn-cgi/access/certs`) {
      assert.equal(options.redirect,'manual');
      return Response.json({keys:[jwk]});
    }
    assert.equal(url,'https://owner-origin.alphacompose.com/owner-evidence/rows?q=one');
    assert.equal(options.headers.get('X-Freediving-Owner-Gateway'),'private-test-secret');
    assert.equal(options.headers.get('X-Freediving-Owner-Email'),'owner@example.com');
    assert.equal(options.headers.get('X-Freediving-Import-Token'),null);
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
    const response = await worker.fetch(req('/owner-evidence/rows?q=one',{headers:{'Cf-Access-Jwt-Assertion':await token(),'Cookie':'fake','Authorization':'fake','X-Freediving-Gateway':'fake','X-Freediving-Owner-Gateway':'fake','X-Freediving-Import-Token':'fake','X-Forwarded-For':'fake'}}),privateEnv);
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

test('owner decision action requires Access, exact origin, JSON and CSRF before forwarding', async () => {
  const signed = await token();
  const path = '/owner-evidence/api/decisions/decision-1/actions';
  const body = JSON.stringify({action:'reverse', expected_revision:2, idempotency_key:'retry-1', csrf_token:'abc'});
  const headers = {'Cf-Access-Jwt-Assertion':signed, Origin:'https://poc.alphacompose.com', 'Content-Type':'application/json', 'X-Freediving-CSRF':'abc'};
  const original = globalThis.fetch;
  let seen = false;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/cdn-cgi/access/certs')) return Response.json({keys:[jwk]});
    seen = true;
    assert.equal(url,'https://owner-origin.alphacompose.com'+path);
    assert.equal(options.method,'POST');
    assert.equal(options.headers.get('Origin'),'https://poc.alphacompose.com');
    assert.equal(options.headers.get('Content-Type'),'application/json');
    assert.equal(options.headers.get('X-Freediving-CSRF'),'abc');
    assert.equal(options.headers.get('Cookie'),null);
    assert.equal(options.headers.get('Cf-Access-Jwt-Assertion'),null);
    assert.equal(new TextDecoder().decode(options.body),body);
    return Response.json({revision:3});
  };
  try {
  assert.equal((await worker.fetch(req(path,{method:'POST',headers:{...headers,Origin:'https://evil.example'},body}),privateEnv)).status,403);
  assert.equal((await worker.fetch(req(path,{method:'POST',headers:{...headers,'Content-Type':'text/plain'},body}),privateEnv)).status,415);
  assert.equal((await worker.fetch(req(path,{method:'POST',headers:{...headers,'X-Freediving-CSRF':''},body}),privateEnv)).status,403);
  assert.equal((await worker.fetch(req('/owner-evidence/api/queue',{method:'POST',headers,body}),privateEnv)).status,405);
  assert.equal((await worker.fetch(req(path,{method:'POST',headers,body:'x'.repeat(16385)}),privateEnv)).status,413);
    const response = await worker.fetch(req(path,{method:'POST',headers,body}),privateEnv);
    assert.equal(response.status,200);
    assert.equal((await response.json()).revision,3);
    assert.equal(seen,true);
  } finally { globalThis.fetch = original; }
});

test('decision event feed requires signed Access service identity and isolates machine token', async () => {
  const path = '/owner-evidence/api/decision-events?after_revision=2';
  const machineId = 'abc12345.access';
  const importToken = 'separate-owner-import-token-for-tests';
  const config = {...privateEnv, OWNER_EVIDENCE_IMPORT_CLIENT_ID: machineId};
  const machineJwt = await token({email:undefined, common_name:machineId, sub:''});
  const browserJwt = await token();
  const original = globalThis.fetch;
  let forwarded = 0;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/cdn-cgi/access/certs')) return Response.json({keys:[jwk]});
    forwarded++;
    assert.equal(url,'https://owner-origin.alphacompose.com'+path);
    assert.equal(options.headers.get('X-Freediving-Import-Token'),importToken);
    assert.equal(options.headers.get('X-Freediving-Owner-Gateway'),'private-test-secret');
    assert.equal(options.headers.get('X-Freediving-Owner-Machine'),machineId);
    assert.equal(options.headers.get('X-Freediving-Owner-Email'),null);
    assert.equal(options.headers.get('Cf-Access-Jwt-Assertion'),null);
    return Response.json({payload_json:'{}',signature:'a'.repeat(64)});
  };
  try {
    const headers = {'Cf-Access-Jwt-Assertion':machineJwt,'X-Freediving-Import-Token':importToken};
    assert.equal((await worker.fetch(req(path,{headers:{...headers,Origin:'https://evil.example'}}),config)).status,403);
    assert.equal((await worker.fetch(req(path,{headers:{...headers,'X-Freediving-Import-Token':''}}),config)).status,403);
    assert.equal((await worker.fetch(req(path,{headers:{...headers,'Cf-Access-Jwt-Assertion':browserJwt}}),config)).status,403);
    assert.equal((await worker.fetch(req(path,{headers:{...headers,'Cf-Access-Jwt-Assertion':await token({common_name:'wrong.access',email:undefined,sub:''})}}),config)).status,403);
    assert.equal((await worker.fetch(req(path,{headers}),privateEnv)).status,503);
    assert.equal(forwarded,0);
    assert.equal((await worker.fetch(req(path,{headers}),config)).status,200);
    assert.equal(forwarded,1);
    const other = await worker.fetch(req('/owner-evidence/api/queue',{headers}),config);
    assert.equal(other.status,403);
    assert.equal(forwarded,1);
  } finally { globalThis.fetch=original; }
});
