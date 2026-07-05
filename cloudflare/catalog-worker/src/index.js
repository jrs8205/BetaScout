// Serves the BetaScout beta catalog from KV. Read-only, public, cached.

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
  'cache-control': 'public, max-age=3600',
};

export default {
  async fetch(request, env) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method Not Allowed', { status: 405 });
    }

    const catalog = await env.CATALOG.get('catalog');
    if (catalog === null) {
      return new Response(JSON.stringify({ version: 2, programs: [] }), { headers: JSON_HEADERS });
    }
    return new Response(catalog, { headers: JSON_HEADERS });
  },
};
