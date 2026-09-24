const ORIGIN = 'https://poc.alphacompose.com';
const UPSTREAM = 'https://poc-origin.alphacompose.com';
const allowed = /^(?:\/|\/public\.(?:js|css)|\/api\/results|\/(?:api\/)?(?:results|athletes)\/[a-f0-9]{64}|\/api\/corrections)$/;
const failure = (status) => new Response('Request unavailable', {status, headers: {'Cache-Control':'no-store', 'Content-Type':'text/plain; charset=utf-8'}});
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.hostname !== new URL(ORIGIN).hostname) return failure(403);
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
