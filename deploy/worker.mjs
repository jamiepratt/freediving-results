const ORIGIN = 'https://poc.alphacompose.com';
const UPSTREAM = 'https://poc-origin.alphacompose.com';
const allowed = /^(?:\/|\/public\.(?:js|css)|\/api\/results|\/(?:api\/)?(?:results|athletes)\/[a-f0-9]{64}|\/api\/corrections)$/;
const failure = (status) => new Response('Request unavailable', {status, headers: {'Cache-Control':'no-store', 'Content-Type':'text/plain; charset=utf-8'}});
const privatePath = (path) => path === '/owner-evidence' || path.startsWith('/owner-evidence/');
const safePrivatePath = /^\/owner-evidence(?:\/[A-Za-z0-9._~-]+)*\/?$/;
const decisionActionPath = /^\/owner-evidence\/api\/decisions\/[A-Za-z0-9_-]{1,128}\/actions$/;
const decisionEventPath = '/owner-evidence/api/decision-events';
const decoder = new TextDecoder('utf-8', {fatal:true});
function decodeSegment(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw Error('Invalid JWT encoding');
  const binary = atob(value.replace(/-/g,'+').replace(/_/g,'/'));
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}
function privateConfig(env) {
  const {ACCESS_ISSUER, ACCESS_AUDIENCE, OWNER_EVIDENCE_EMAILS, OWNER_EVIDENCE_UPSTREAM, OWNER_EVIDENCE_GATEWAY_SECRET, OWNER_EVIDENCE_IMPORT_CLIENT_ID} = env;
  if (typeof ACCESS_ISSUER !== 'string' || !/^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(ACCESS_ISSUER)) return null;
  if (typeof ACCESS_AUDIENCE !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(ACCESS_AUDIENCE)) return null;
  if (typeof OWNER_EVIDENCE_GATEWAY_SECRET !== 'string' || OWNER_EVIDENCE_GATEWAY_SECRET.length < 16) return null;
  if (typeof OWNER_EVIDENCE_EMAILS !== 'string') return null;
  const emails = OWNER_EVIDENCE_EMAILS.split(',').map(s => s.trim());
  if (!emails.length || emails.some(s => !/^[^\s,@]+@[^\s,@]+\.[^\s,@]+$/.test(s) || s !== s.toLowerCase())) return null;
  let upstream;
  try { upstream = new URL(OWNER_EVIDENCE_UPSTREAM); } catch { return null; }
  if (upstream.protocol !== 'https:' || !/^[a-z0-9-]+\.alphacompose\.com$/.test(upstream.hostname) ||
      [new URL(ORIGIN).hostname,new URL(UPSTREAM).hostname].includes(upstream.hostname) ||
      upstream.port || upstream.username || upstream.password || upstream.pathname !== '/' || upstream.search || upstream.hash ||
      upstream.origin !== OWNER_EVIDENCE_UPSTREAM) return null;
  if (OWNER_EVIDENCE_IMPORT_CLIENT_ID !== undefined &&
      (typeof OWNER_EVIDENCE_IMPORT_CLIENT_ID !== 'string' || !/^[A-Za-z0-9_-]{8,128}\.access$/.test(OWNER_EVIDENCE_IMPORT_CLIENT_ID))) return null;
  return {issuer:ACCESS_ISSUER,audience:ACCESS_AUDIENCE,emails:new Set(emails),upstream:upstream.origin,secret:OWNER_EVIDENCE_GATEWAY_SECRET,importClientId:OWNER_EVIDENCE_IMPORT_CLIENT_ID};
}
async function verifiedOwner(token, config, machine=false) {
  if (typeof token !== 'string' || token.length > 8192) return null;
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const header = JSON.parse(decoder.decode(decodeSegment(parts[0])));
    const claims = JSON.parse(decoder.decode(decodeSegment(parts[1])));
    if (header.alg !== 'RS256' || typeof header.kid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(header.kid)) return null;
    const now = Math.floor(Date.now()/1000);
    if (claims.iss !== config.issuer || !(claims.aud === config.audience || Array.isArray(claims.aud) && claims.aud.includes(config.audience)) ||
        claims.type !== 'app' ||
        (machine ? !(config.importClientId && claims.common_name === config.importClientId && claims.sub === '' && !claims.email)
                 : !(typeof claims.email === 'string' && config.emails.has(claims.email) && !claims.common_name)) ||
        !Number.isInteger(claims.exp) || claims.exp <= now || !Number.isInteger(claims.nbf) || claims.nbf > now ||
        !Number.isInteger(claims.iat) || claims.iat > now) return null;
    const keysResponse = await fetch(`${config.issuer}/cdn-cgi/access/certs`, {redirect:'manual',signal:AbortSignal.timeout(5000),cf:{cacheTtl:0,cacheEverything:false}});
    if (!keysResponse.ok) return null;
    const keys = (await keysResponse.json()).keys;
    if (!Array.isArray(keys)) return null;
    const key = keys.find(k => k.kid === header.kid && k.kty === 'RSA' && (k.alg === undefined || k.alg === 'RS256') && (k.use === undefined || k.use === 'sig'));
    if (!key) return null;
    const publicKey = await crypto.subtle.importKey('jwk',key,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);
    const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5',publicKey,decodeSegment(parts[2]),new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
    return valid ? (machine ? claims.common_name : claims.email) : null;
  } catch { return null; }
}
async function privateRequest(request, url, env) {
  if (!safePrivatePath.test(url.pathname) || url.pathname.length > 2048 || url.search.length > 2048) return failure(404);
  const action = request.method === 'POST' && decisionActionPath.test(url.pathname) && !url.search;
  const machine = url.pathname === decisionEventPath;
  if (machine && request.method !== 'GET') return failure(405);
  if (request.method !== 'GET' && request.method !== 'HEAD' && !action) return failure(405);
  const origin = request.headers.get('Origin');
  if ((origin && origin !== ORIGIN) || (action && origin !== ORIGIN)) return failure(403);
  if (action && request.headers.get('Content-Type') !== 'application/json') return failure(415);
  const csrf = request.headers.get('X-Freediving-CSRF');
  if (action && (typeof csrf !== 'string' || !/^[A-Za-z0-9_-]{3,128}$/.test(csrf))) return failure(403);
  if (action && Number(request.headers.get('Content-Length')) > 16384) return failure(413);
  const config = privateConfig(env);
  if (!config) return failure(503);
  if (machine && !config.importClientId) return failure(503);
  const importToken = request.headers.get('X-Freediving-Import-Token');
  if (machine && (typeof importToken !== 'string' || importToken.length < 24 || importToken.length > 256 || /\s/.test(importToken))) return failure(403);
  const identity = await verifiedOwner(request.headers.get('Cf-Access-Jwt-Assertion'),config,machine);
  if (!identity) return failure(403);
  const headers = new Headers({'X-Freediving-Owner-Gateway':config.secret});
  if (machine) {
    headers.set('X-Freediving-Owner-Machine',identity);
    headers.set('X-Freediving-Import-Token',importToken);
  } else headers.set('X-Freediving-Owner-Email',identity);
  let body;
  if (action) {
    headers.set('Origin', ORIGIN);
    headers.set('Content-Type', 'application/json');
    headers.set('X-Freediving-CSRF', csrf);
    const reader = request.body?.getReader();
    const chunks = []; let size = 0;
    if (!reader) return failure(400);
    while (true) {
      const {value, done} = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 16384) { await reader.cancel(); return failure(413); }
      chunks.push(value);
    }
    body = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
  }
  try {
    const result = await fetch(config.upstream + url.pathname + url.search, {method:request.method,headers,body,redirect:'manual',signal:AbortSignal.timeout(15000),cf:{cacheTtl:0,cacheEverything:false}});
    if (result.status >= 300 && result.status < 400) return failure(502);
    const responseHeaders = new Headers({'Cache-Control':'no-store','Strict-Transport-Security':'max-age=31536000','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'});
    const contentType = result.headers.get('Content-Type');
    if (contentType) responseHeaders.set('Content-Type',contentType);
    return new Response(request.method === 'HEAD' || [204,304].includes(result.status) ? null : result.body,{status:result.status,headers:responseHeaders});
  } catch { return failure(503); }
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.hostname !== new URL(ORIGIN).hostname) return failure(403);
    if (privatePath(url.pathname)) return url.protocol === 'https:' ? privateRequest(request,url,env) : failure(403);
    if (url.protocol !== 'https:') return Response.redirect(ORIGIN + url.pathname + url.search, 308);
    if (!allowed.test(url.pathname)) return failure(404);
    const post = request.method === 'POST' && url.pathname === '/api/corrections';
    if (request.method !== 'GET' && !post) return failure(405);
    const origin = request.headers.get('Origin');
    if ((origin && origin !== ORIGIN) || (post && origin !== ORIGIN)) return failure(403);
    if (!env.GATEWAY_SECRET) return failure(503);
    const ip = request.headers.get('CF-Connecting-IP');
    if (!ip || !/^[0-9a-fA-F:.]{3,45}$/.test(ip)) return failure(403);
    const headers = new Headers();
    for (const name of ['Origin','Content-Type','X-Correction-Request']) {
      if (request.headers.has(name)) headers.set(name, request.headers.get(name));
    }
    headers.set('X-Freediving-Gateway', env.GATEWAY_SECRET);
    headers.set('X-Freediving-Client', ip);
    let body;
    if (post) {
      const reader = request.body?.getReader();
      const chunks = []; let size = 0;
      if (reader) {
        while (true) {
          const {value, done} = await reader.read();
          if (done) break;
          size += value.length;
          if (size > 16384) { await reader.cancel(); return failure(413); }
          chunks.push(value);
        }
      }
      body = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
    }
    try {
      const result = await fetch(UPSTREAM + url.pathname + url.search, {
        method: request.method, headers, body, redirect: 'manual',
        signal: AbortSignal.timeout(15000), cf: {cacheTtl: 0, cacheEverything: false}
      });
      const response = new Response(result.body, result);
      response.headers.set('Cache-Control', 'no-store');
      response.headers.set('Strict-Transport-Security', 'max-age=31536000');
      return response;
    } catch { return failure(503); }
  }
};
