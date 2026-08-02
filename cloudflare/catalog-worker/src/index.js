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

// Abuse damping for the unauthenticated intake: a device reports each package
// once, so a real user needs a handful of posts per day — sustained volume is
// either a bug loop or deliberate flooding of the verify queue.
const IP_HOURLY_POST_LIMIT = 30;
const GLOBAL_DAILY_PACKAGE_LIMIT = 5000;

async function readCount(env, key) {
  const count = Number(await env.CATALOG.get(key));
  return Number.isFinite(count) ? count : 0;
}

async function handleHintPost(request, env) {
  // Read-modify-write counters can lose a racing increment; the limits are
  // abuse damping, not exact accounting, so approximate counts are fine.
  const ip = request.headers.get('cf-connecting-ip') ?? 'unknown';
  const ipKey = `rl:ip:${ip}:${Math.floor(Date.now() / 3_600_000)}`;
  const ipCount = await readCount(env, ipKey);
  if (ipCount >= IP_HOURLY_POST_LIMIT) return new Response(null, { status: 429 });

  const result = parseHintRequest(await request.text(), await catalogPackages(env));
  if (result.status !== 204) return new Response(null, { status: result.status });

  const globalKey = `rl:global:${Math.floor(Date.now() / 86_400_000)}`;
  const globalCount = await readCount(env, globalKey);
  if (globalCount + result.accepted.length > GLOBAL_DAILY_PACKAGE_LIMIT) {
    return new Response(null, { status: 429 });
  }

  // Expiry outlives the bucket so a live counter is never dropped mid-window;
  // a stale bucket key is unreachable and ages out on its own.
  await env.CATALOG.put(ipKey, String(ipCount + 1), { expirationTtl: 7_200 });
  if (result.accepted.length > 0) {
    await env.CATALOG.put(globalKey, String(globalCount + result.accepted.length), {
      expirationTtl: 172_800,
    });
  }

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
