// Serves the BetaScout beta catalog from KV (read-only, public, cached) and
// accepts crowd-sourced discovery hints. Hints are only candidates: nothing
// reaches the published catalog until the harvester's gplayapi verification
// confirms it, which is why the intake needs no submitter identity.

import { parseHintRequest } from './hints.js';

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
  'cache-control': 'public, max-age=3600',
};

async function catalogPackages(env) {
  const catalog = await env.CATALOG.get('catalog');
  if (!catalog) return new Set();
  try {
    return new Set((JSON.parse(catalog).programs ?? []).map((p) => p.packageName));
  } catch {
    return new Set();
  }
}

async function handleHintPost(request, env) {
  const result = parseHintRequest(await request.text(), await catalogPackages(env));
  if (result.status !== 204) return new Response(null, { status: result.status });
  const now = Date.now();
  for (const packageName of result.accepted) {
    const key = `hint:${packageName}`;
    // Read-modify-write; a racing write may lose a count increment, which is
    // acceptable — count is informational only, never a trust signal.
    let entry = { firstSeen: now, count: 0 };
    const existing = await env.CATALOG.get(key);
    if (existing) {
      try {
        entry = JSON.parse(existing);
      } catch {
        // A corrupt entry is simply reset.
      }
    }
    entry.count = (entry.count ?? 0) + 1;
    await env.CATALOG.put(key, JSON.stringify(entry));
  }
  return new Response(null, { status: 204 });
}

/** Raw hints are not publicly readable; only the harvester holds the token. */
function authorized(request, env) {
  return Boolean(env.HINTS_TOKEN) &&
    request.headers.get('authorization') === `Bearer ${env.HINTS_TOKEN}`;
}

function requireToken(request, env, handler) {
  if (!env.HINTS_TOKEN) return new Response('hints token not configured', { status: 503 });
  if (!authorized(request, env)) return new Response('unauthorized', { status: 401 });
  return handler();
}

async function handleHintList(env) {
  const hints = [];
  let cursor;
  do {
    const page = await env.CATALOG.list({ prefix: 'hint:', cursor });
    for (const key of page.keys) {
      const raw = await env.CATALOG.get(key.name);
      // list() is eventually consistent: a just-consumed hint can still be
      // listed while get() already answers null — skip those ghosts.
      if (raw === null) continue;
      let entry = {};
      try {
        entry = JSON.parse(raw);
      } catch {
        // A corrupt entry still surfaces as a hint with zeroed metadata.
      }
      hints.push({
        packageName: key.name.slice('hint:'.length),
        firstSeen: entry.firstSeen ?? 0,
        count: entry.count ?? 0,
      });
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return new Response(
    JSON.stringify({ hints }),
    { headers: { 'content-type': 'application/json; charset=utf-8' } },
  );
}

async function handleHintConsume(request, env) {
  let packages;
  try {
    packages = JSON.parse(await request.text())?.packages;
  } catch {
    // Falls through to the array check below.
  }
  if (!Array.isArray(packages)) return new Response(null, { status: 400 });
  for (const name of packages) {
    if (typeof name === 'string') await env.CATALOG.delete(`hint:${name}`);
  }
  return new Response(null, { status: 204 });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/hints') {
      if (request.method === 'POST') return handleHintPost(request, env);
      if (request.method === 'GET') return requireToken(request, env, () => handleHintList(env));
      return new Response('Method Not Allowed', { status: 405 });
    }
    if (url.pathname === '/hints/consume') {
      if (request.method === 'POST') {
        return requireToken(request, env, () => handleHintConsume(request, env));
      }
      return new Response('Method Not Allowed', { status: 405 });
    }

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
