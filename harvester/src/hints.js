// Crowd-hint verification support: pure selection/merge logic plus thin,
// injectable transports. The daily run (src/run.js) orchestrates them. Hints
// are only candidates — nothing reaches the catalog without gplayapi saying
// the package really has a testing program.

export const REJECTED_TTL_MS = 30 * 24 * 3600 * 1000;
// A hint that keeps erroring in gplayapi stops occupying verify slots after
// this many attempts and gets one retry per TTL window.
export const ERROR_COOLDOWN_ATTEMPTS = 3;
export const ERRORED_TTL_MS = 30 * 24 * 3600 * 1000;

/**
 * Splits pending hints into what this run verifies and what it skips.
 * @param hints [{packageName, firstSeen, count}]
 * @param options {catalogPackages: Set, rejected: [{packageName, rejectedAt}],
 *   errored: [{packageName, attempts, lastError, lastErrorAt}], now, cap}
 * @returns {verify, alreadyInCatalog, cooling, erroring, overflow} — package
 *   name arrays; verify is oldest-first so long-waiting hints go before fresh ones.
 */
export function selectHintsToVerify(hints, { catalogPackages, rejected, errored = [], now, cap }) {
  const coolingSet = new Set(
    rejected
      .filter((entry) => now - entry.rejectedAt < REJECTED_TTL_MS)
      .map((entry) => entry.packageName),
  );
  const erroringSet = new Set(
    errored
      .filter(
        (entry) =>
          entry.attempts >= ERROR_COOLDOWN_ATTEMPTS && now - entry.lastErrorAt < ERRORED_TTL_MS,
      )
      .map((entry) => entry.packageName),
  );
  const verify = [];
  const alreadyInCatalog = [];
  const cooling = [];
  const erroring = [];
  const overflow = [];
  const oldestFirst = [...hints].sort((a, b) => (a.firstSeen ?? 0) - (b.firstSeen ?? 0));
  for (const hint of oldestFirst) {
    const packageName = hint.packageName;
    if (catalogPackages.has(packageName)) {
      alreadyInCatalog.push(packageName);
    } else if (coolingSet.has(packageName)) {
      cooling.push(packageName);
    } else if (erroringSet.has(packageName)) {
      erroring.push(packageName);
    } else if (verify.length < cap) {
      verify.push(packageName);
    } else {
      overflow.push(packageName);
    }
  }
  return { verify, alreadyInCatalog, cooling, erroring, overflow };
}

/**
 * Splits a verify batch by its gplayapi results.
 * @returns {confirmed: [gplayResult], rejected: [packageName],
 *   errored: [{packageName, error}]} — errored hints stay pending on the Worker.
 */
export function partitionVerifyResults(verify, results) {
  const byPackage = new Map(results.map((r) => [r.packageName, r]));
  const confirmed = [];
  const rejected = [];
  const errored = [];
  for (const packageName of verify) {
    const result = byPackage.get(packageName);
    if (!result) {
      errored.push({ packageName, error: 'no result from gplayapi' });
    } else if (result.error !== undefined) {
      errored.push({ packageName, error: result.error });
    } else if (result.available === true) {
      confirmed.push(result);
    } else if (result.available === false || result.available === null) {
      // false = program exists but is unavailable; null = no program at all.
      // Both mean "no joinable beta" — reject with the 30-day cooldown.
      rejected.push(packageName);
    } else {
      errored.push({ packageName, error: 'no availability in gplayapi output' });
    }
  }
  return { confirmed, rejected, errored };
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

/** Adds this run's gplayapi errors to the errored list: first failure starts
 *  at one attempt, repeats increment and refresh the message/timestamp. */
export function updateErrored(errored, failures, now) {
  const byPackage = new Map(errored.map((entry) => [entry.packageName, entry]));
  for (const { packageName, error } of failures) {
    const attempts = (byPackage.get(packageName)?.attempts ?? 0) + 1;
    byPackage.set(packageName, { packageName, attempts, lastError: error, lastErrorAt: now });
  }
  return [...byPackage.values()];
}

/** Removes packages that settled (confirmed or rejected) from the errored list
 *  so an eventual success wipes the failure history. */
export function clearErrored(errored, settledPackages) {
  const settled = new Set(settledPackages);
  return errored.filter((entry) => !settled.has(entry.packageName));
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
