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
      // Without the KV entry there is no catalog to serve. A 200 with an empty
      // catalog would look like valid data to clients and poison their caches;
      // a 404 makes them fall back to their cached or bundled copy instead.
      return new Response('catalog not found', { status: 404 });
    }
    return new Response(catalog, { headers: JSON_HEADERS });
  },
};
