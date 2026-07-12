// Crowd-hint verification support: pure selection/merge logic plus thin,
// injectable transports. The daily run (src/run.js) orchestrates them. Hints
// are only candidates — nothing reaches the catalog without gplayapi saying
// the package really has a testing program.

export const REJECTED_TTL_MS = 30 * 24 * 3600 * 1000;

/**
 * Splits pending hints into what this run verifies and what it skips.
 * @param hints [{packageName, firstSeen, count}]
 * @param options {catalogPackages: Set, rejected: [{packageName, rejectedAt}], now, cap}
 * @returns {verify, alreadyInCatalog, cooling, overflow} — package name arrays;
 *   verify is oldest-first so long-waiting hints go before fresh ones.
 */
export function selectHintsToVerify(hints, { catalogPackages, rejected, now, cap }) {
  const coolingSet = new Set(
    rejected
      .filter((entry) => now - entry.rejectedAt < REJECTED_TTL_MS)
      .map((entry) => entry.packageName),
  );
  const verify = [];
  const alreadyInCatalog = [];
  const cooling = [];
  const overflow = [];
  const oldestFirst = [...hints].sort((a, b) => (a.firstSeen ?? 0) - (b.firstSeen ?? 0));
  for (const hint of oldestFirst) {
    const packageName = hint.packageName;
    if (catalogPackages.has(packageName)) {
      alreadyInCatalog.push(packageName);
    } else if (coolingSet.has(packageName)) {
      cooling.push(packageName);
    } else if (verify.length < cap) {
      verify.push(packageName);
    } else {
      overflow.push(packageName);
    }
  }
  return { verify, alreadyInCatalog, cooling, overflow };
}

/** Adds this run's verification failures to the rejected list; a re-rejection
 *  refreshes the timestamp so the 30-day cooldown restarts. */
export function updateRejected(rejected, failures, now) {
  const byPackage = new Map(rejected.map((entry) => [entry.packageName, entry]));
  for (const packageName of failures) {
    byPackage.set(packageName, { packageName, rejectedAt: now });
  }
  return [...byPackage.values()];
}

/** Catalog entry for a gplayapi-verified crowd hint. */
export function crowdEntry(gplayResult) {
  return {
    packageName: gplayResult.packageName,
    appName: gplayResult.name || gplayResult.packageName,
    hasBeta: gplayResult.available,
    productionVersionCode: gplayResult.versionCode,
    liveStatus: 'UNKNOWN',
    source: 'CROWD',
    notes: 'Reported by users, confirmed via Google Play',
  };
}

export async function fetchHints(url, token, fetchImpl) {
  const response = await fetchImpl(url, {
    headers: { authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`GET ${url} -> HTTP ${response.status}`);
  return (await response.json()).hints ?? [];
}

export async function consumeHints(url, token, packages, fetchImpl) {
  const response = await fetchImpl(`${url}/consume`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ packages }),
  });
  if (!response.ok) throw new Error(`POST ${url}/consume -> HTTP ${response.status}`);
}
