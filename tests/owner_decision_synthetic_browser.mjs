// Synthetic browser requests through the real Cloudflare Worker into a loopback origin.
import {webcrypto} from 'node:crypto';
import http from 'node:http';
import worker from '../deploy/worker.mjs';

const port = Number(process.argv[2]);
if (!Number.isInteger(port)) throw Error('loopback port required');
const origin = 'https://poc.alphacompose.com';
const upstream = 'https://owner-private.alphacompose.com';
const issuer = 'https://synthetic.cloudflareaccess.com';
const importToken = 'synthetic-separate-machine-import-token';
const env = {
  ACCESS_ISSUER: issuer,
  ACCESS_AUDIENCE: 'a'.repeat(32),
  OWNER_EVIDENCE_EMAILS: 'owner@example.com',
  OWNER_EVIDENCE_UPSTREAM: upstream,
  OWNER_EVIDENCE_GATEWAY_SECRET: 'synthetic-private-gateway-secret',
  OWNER_EVIDENCE_IMPORT_CLIENT_ID: 'synthetic1.access'
};
const pair = await webcrypto.subtle.generateKey(
  {name:'RSASSA-PKCS1-v1_5', modulusLength:2048,
   publicExponent:new Uint8Array([1,0,1]), hash:'SHA-256'}, true, ['sign','verify']);
const jwk = {...await webcrypto.subtle.exportKey('jwk', pair.publicKey),
             kid:'synthetic-key', alg:'RS256', use:'sig'};
const encode = value => Buffer.from(value).toString('base64url');
async function token(overrides={}) {
  const now = Math.floor(Date.now()/1000);
  const header = encode(JSON.stringify({alg:'RS256',kid:'synthetic-key'}));
  const claims = encode(JSON.stringify({iss:issuer, aud:[env.ACCESS_AUDIENCE],
    type:'app', email:'owner@example.com', iat:now, nbf:now-1, exp:now+120,
    ...overrides}));
  const signed = `${header}.${claims}`;
  const signature = await webcrypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey,
                                                new TextEncoder().encode(signed));
  return `${signed}.${encode(Buffer.from(signature))}`;
}

globalThis.fetch = async (url, options={}) => {
  if (url === `${issuer}/cdn-cgi/access/certs`) return Response.json({keys:[jwk]});
  if (!String(url).startsWith(upstream + '/owner-evidence/')) throw Error('unexpected upstream');
  const headers = new Headers(options.headers);
  headers.set('Host', 'owner-private.alphacompose.com');
  if (options.body) headers.set('Content-Length', String(options.body.length));
  return new Promise((resolve, reject) => {
    const request = http.request({hostname:'127.0.0.1', port,
      path:String(url).slice(upstream.length), method:options.method || 'GET',
      headers:Object.fromEntries(headers)}, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve(new Response(Buffer.concat(chunks), {
        status:response.statusCode, headers:response.headers})));
    });
    request.on('error', reject);
    if (options.body) request.write(Buffer.from(options.body));
    request.end();
  });
};
const browser = await token();
const machine = await token({email:undefined, common_name:'synthetic1.access', sub:''});
const request = (path, init={}) => worker.fetch(new Request(origin + path, {
  ...init, headers:{'Cf-Access-Jwt-Assertion':browser, ...init.headers}
}), env);
const queueResponse = await request('/owner-evidence/api/decisions?status=automatic_approved');
if (queueResponse.status !== 200) throw Error(`queue ${queueResponse.status}`);
const queue = await queueResponse.json();
const csrf = queue.csrf_token;
const revision = queue.revision;
const actionPath = id => `/owner-evidence/api/decisions/${id}/actions`;
const action = (id, actionName, expectedRevision, key, extra={}, headers={}) => {
  const body = JSON.stringify({action:actionName, expected_revision:expectedRevision,
    idempotency_key:key, reason:'synthetic owner review', csrf_token:csrf, ...extra});
  return request(actionPath(id), {method:'POST', body, headers:{Origin:origin,
    'Content-Type':'application/json', 'X-Freediving-CSRF':csrf, ...headers}});
};

const phase = process.argv[3];
if (phase === 'projection') {
  const response = await request('/owner-evidence/api/canonical-projection');
  if (response.status !== 200) throw Error(`canonical projection ${response.status}`);
  process.stdout.write(JSON.stringify(await response.json()));
  process.exit(0);
}
if (phase === 'inspect') {
  const id = process.argv[4];
  const response = await request(`/owner-evidence/api/decisions/${id}`);
  if (response.status !== 200) throw Error(`decision inspect ${response.status}`);
  const detail = await response.json();
  process.stdout.write(JSON.stringify({decision_id:id,
    effective_status:detail.effective_status,
    store_revision:detail.store_revision}));
  process.exit(0);
}
if (phase === 'approve' || phase === 'reverse') {
  const id = process.argv[4];
  if (!id) throw Error('decision ID required');
  const rejected = {};
  rejected.expired_access = (await request(actionPath(id), {method:'POST',
    body:JSON.stringify({action:phase,expected_revision:revision,
      idempotency_key:'expired-'+phase,reason:'synthetic',csrf_token:csrf}),
    headers:{Origin:origin,'Content-Type':'application/json','X-Freediving-CSRF':csrf,
      'Cf-Access-Jwt-Assertion':await token({exp:Math.floor(Date.now()/1000)-1})}})).status;
  rejected.foreign_origin = (await action(id,phase,revision,'foreign-'+phase,{},
                                          {Origin:'https://foreign.example'})).status;
  const result = await action(id,phase,revision,'synthetic-'+phase);
  if (result.status !== 200) throw Error(`${phase} ${result.status}: ${await result.text()}`);
  const retry = await action(id,phase,revision,'synthetic-'+phase);
  rejected.stale_revision = (await action(id,phase,revision,'stale-'+phase)).status;
  const detailResponse = await request(`/owner-evidence/api/decisions/${id}`);
  if (detailResponse.status !== 200) throw Error(`inspect ${detailResponse.status}`);
  const detail = await detailResponse.json();
  const feedPath = '/owner-evidence/api/decision-events?after_revision=0';
  rejected.browser_event_feed = (await request(feedPath, {headers:{
    'X-Freediving-Import-Token':importToken}})).status;
  const feedResponse = await request(feedPath, {headers:{
    'Cf-Access-Jwt-Assertion':machine, 'X-Freediving-Import-Token':importToken}});
  if (feedResponse.status !== 200) throw Error(`machine feed ${feedResponse.status}`);
  process.stdout.write(JSON.stringify({action:result.status, retry:retry.status,
    rejected, decision_id:id, effective_status:detail.effective_status,
    feed:await feedResponse.json()}));
  process.exit(0);
}

const rejected = {};
rejected.expired_access = (await request(actionPath('root'), {method:'POST',
  body:JSON.stringify({action:'reverse',expected_revision:revision,
    idempotency_key:'expired',reason:'synthetic',csrf_token:csrf}),
  headers:{Origin:origin,'Content-Type':'application/json','X-Freediving-CSRF':csrf,
    'Cf-Access-Jwt-Assertion':await token({exp:Math.floor(Date.now()/1000)-1})}})).status;
rejected.foreign_origin = (await action('root','reverse',revision,'foreign',{},
                                        {Origin:'https://foreign.example'})).status;
const reverse = await action('root','reverse',revision,'reverse-root');
if (reverse.status !== 200) throw Error(`reverse ${reverse.status}`);
const reverseResult = await reverse.json();
const retry = await action('root','reverse',revision,'reverse-root');
rejected.stale_revision = (await action('root','approve',revision,'stale-approve')).status;
const correct = await action('corrected','correct',reverseResult.store_revision,'correct-independent',
                             {correction:{action:'two'}});
if (correct.status !== 200) throw Error(`correct ${correct.status}`);
const correctResult = await correct.json();
const inspection = {};
for (const id of ['root','child','corrected']) {
  const response = await request(`/owner-evidence/api/decisions/${id}`);
  if (response.status !== 200) throw Error(`inspect ${id}: ${response.status}`);
  const detail = await response.json();
  inspection[id] = detail.effective_status;
  if (id === 'corrected') inspection.correction = detail.correction;
}
const feedPath = '/owner-evidence/api/decision-events?after_revision=0';
rejected.browser_event_feed = (await request(feedPath, {headers:{
  'X-Freediving-Import-Token':importToken}})).status;
const feedResponse = await request(feedPath, {headers:{
  'Cf-Access-Jwt-Assertion':machine, 'X-Freediving-Import-Token':importToken}});
if (feedResponse.status !== 200) throw Error(`machine feed ${feedResponse.status}`);
const feed = await feedResponse.json();
process.stdout.write(JSON.stringify({rejected, actions:{reverse:reverse.status,
  retry:retry.status, correct:correct.status}, inspection,
  revision:correctResult.store_revision, feed}));
