import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  selectHintsToVerify,
  updateRejected,
  crowdEntry,
  partitionVerifyResults,
  settledForConsume,
  updateErrored,
  clearErrored,
  consumePendingHints,
  REJECTED_TTL_MS,
  ERRORED_TTL_MS,
} from '../src/hints.js';

test('a batch-level gplay failure produces no per-package errors', () => {
  // The tool crashing (expired AAS token, jar download failure) says nothing
  // about the individual hints; recording per-package errors would push every
  // hint in the batch into the 30-day error cooldown after three bad nights.
  const result = partitionVerifyResults(['com.a', 'com.b'], null);

  assert.equal(result.batchFailed, true);
  assert.deepEqual(result.confirmed, []);
  assert.deepEqual(result.rejected, []);
  assert.deepEqual(result.errored, []);
});

test('a batch that ran is not flagged as failed even when a package is missing', () => {
  const result = partitionVerifyResults(['com.a'], []);

  assert.equal(result.batchFailed, false);
  assert.deepEqual(result.errored, [{ packageName: 'com.a', error: 'no result from gplayapi' }]);
});

test('the consume list includes hints settled on earlier runs whose consume failed', () => {
  // A rejected hint whose consume step failed shows up as "cooling" on the next
  // run; without re-adding it here it would stay pending on the Worker for the
  // full 30-day TTL and then burn a verify slot just to be re-rejected.
  const consumed = settledForConsume({
    confirmedPackages: ['com.new'],
    rejectedNow: ['com.rejected'],
    alreadyInCatalog: ['com.known'],
    cooling: ['com.rejectedEarlier'],
  });

  assert.deepEqual(
    [...consumed].sort(),
    ['com.known', 'com.new', 'com.rejected', 'com.rejectedEarlier'],
  );
});

const DAY_MS = 24 * 3600 * 1000;
const NOW = 1000 * DAY_MS;

function hint(packageName, firstSeen = 0) {
  return { packageName, firstSeen, count: 1 };
}

test('selection drops catalog members, respects the cap and keeps oldest first', () => {
  const hints = [
    hint('com.new2', 20),
    hint('com.known', 1),
    hint('com.new1', 10),
    hint('com.new3', 30),
  ];

  const result = selectHintsToVerify(hints, {
    catalogPackages: new Set(['com.known']),
    rejected: [],
    now: NOW,
    cap: 2,
  });

  assert.deepEqual(result.verify, ['com.new1', 'com.new2']);
  assert.deepEqual(result.alreadyInCatalog, ['com.known']);
  assert.deepEqual(result.overflow, ['com.new3']);
  assert.deepEqual(result.cooling, []);
});

test('recently rejected hints cool off and become eligible after 30 days', () => {
  const rejected = [
    { packageName: 'com.cooling', rejectedAt: NOW - 10 * DAY_MS },
    { packageName: 'com.eligible', rejectedAt: NOW - REJECTED_TTL_MS - DAY_MS },
  ];

  const result = selectHintsToVerify(
    [hint('com.cooling'), hint('com.eligible')],
    { catalogPackages: new Set(), rejected, now: NOW, cap: 10 },
  );

  assert.deepEqual(result.cooling, ['com.cooling']);
  assert.deepEqual(result.verify, ['com.eligible']);
});

test('repeatedly erroring hints cool off without eating cap slots and retry after 30 days', () => {
  const errored = [
    { packageName: 'com.jammed', attempts: 3, lastError: 'x', lastErrorAt: NOW - DAY_MS },
    { packageName: 'com.retry', attempts: 5, lastError: 'x', lastErrorAt: NOW - ERRORED_TTL_MS - DAY_MS },
    { packageName: 'com.twice', attempts: 2, lastError: 'x', lastErrorAt: NOW - DAY_MS },
  ];

  const result = selectHintsToVerify(
    [hint('com.jammed', 1), hint('com.retry', 2), hint('com.twice', 3)],
    { catalogPackages: new Set(), rejected: [], errored, now: NOW, cap: 2 },
  );

  assert.deepEqual(result.erroring, ['com.jammed']);
  assert.deepEqual(result.verify, ['com.retry', 'com.twice']);
  assert.deepEqual(result.overflow, []);
});

test('updateRejected stamps new failures and keeps existing entries', () => {
  const existing = [{ packageName: 'com.old', rejectedAt: 5 }];

  const updated = updateRejected(existing, ['com.fresh'], NOW);

  assert.deepEqual(updated, [
    { packageName: 'com.old', rejectedAt: 5 },
    { packageName: 'com.fresh', rejectedAt: NOW },
  ]);
});

test('re-rejecting refreshes the timestamp', () => {
  const existing = [{ packageName: 'com.again', rejectedAt: 5 }];

  const updated = updateRejected(existing, ['com.again'], NOW);

  assert.deepEqual(updated, [{ packageName: 'com.again', rejectedAt: NOW }]);
});

test('verify results split into confirmed, rejected and errored', () => {
  const verify = ['com.ok', 'com.gone', 'com.broken'];
  const results = [
    { packageName: 'com.ok', available: true, versionCode: 7, name: 'Ok App' },
    { packageName: 'com.gone', available: false },
    { packageName: 'com.broken', error: 'Status{code=NOT_FOUND}' },
  ];

  const { confirmed, rejected, errored } = partitionVerifyResults(verify, results);

  assert.deepEqual(confirmed, [
    { packageName: 'com.ok', available: true, versionCode: 7, name: 'Ok App' },
  ]);
  assert.deepEqual(rejected, ['com.gone']);
  assert.deepEqual(errored, [
    { packageName: 'com.broken', error: 'Status{code=NOT_FOUND}' },
  ]);
});

test('an app with no testing program at all is rejected, not errored', () => {
  const verify = ['com.noprogram'];
  const results = [{ packageName: 'com.noprogram', available: null, name: 'Plain App' }];

  const { confirmed, rejected, errored } = partitionVerifyResults(verify, results);

  assert.deepEqual(confirmed, []);
  assert.deepEqual(rejected, ['com.noprogram']);
  assert.deepEqual(errored, []);
});

test('packages without a usable gplayapi result are errored, not dropped', () => {
  const verify = ['com.missing', 'com.noavail'];
  const results = [{ packageName: 'com.noavail', available: undefined, name: 'X' }];

  const { confirmed, rejected, errored } = partitionVerifyResults(verify, results);

  assert.deepEqual(confirmed, []);
  assert.deepEqual(rejected, []);
  assert.deepEqual(errored, [
    { packageName: 'com.missing', error: 'no result from gplayapi' },
    { packageName: 'com.noavail', error: 'no availability in gplayapi output' },
  ]);
});

test('updateErrored stamps new failures and increments repeat offenders', () => {
  const existing = [
    { packageName: 'com.flaky', attempts: 2, lastError: 'old', lastErrorAt: 5 },
  ];
  const failures = [
    { packageName: 'com.flaky', error: 'Status{code=NOT_FOUND}' },
    { packageName: 'com.fresh', error: 'timeout' },
  ];

  const updated = updateErrored(existing, failures, NOW);

  assert.deepEqual(updated, [
    { packageName: 'com.flaky', attempts: 3, lastError: 'Status{code=NOT_FOUND}', lastErrorAt: NOW },
    { packageName: 'com.fresh', attempts: 1, lastError: 'timeout', lastErrorAt: NOW },
  ]);
});

test('clearErrored drops packages that got a definitive verify result', () => {
  const errored = [
    { packageName: 'com.settled', attempts: 2, lastError: 'x', lastErrorAt: 5 },
    { packageName: 'com.still', attempts: 1, lastError: 'y', lastErrorAt: 5 },
  ];

  const cleared = clearErrored(errored, ['com.settled']);

  assert.deepEqual(cleared, [
    { packageName: 'com.still', attempts: 1, lastError: 'y', lastErrorAt: 5 },
  ]);
});

test('consumePendingHints consumes the packages the harvest recorded', async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, body: options?.body });
    return { ok: true };
  };

  const count = await consumePendingHints({
    readPending: () => JSON.stringify({ packages: ['com.a', 'com.b'] }),
    url: 'https://x/hints',
    token: 't',
    fetchImpl,
  });

  assert.equal(count, 2);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://x/hints/consume');
  assert.deepEqual(JSON.parse(calls[0].body).packages, ['com.a', 'com.b']);
});

test('a missing or empty pending file is a consume no-op', async () => {
  let fetched = false;
  const fetchImpl = async () => {
    fetched = true;
    return { ok: true };
  };

  const missing = await consumePendingHints({
    readPending: () => {
      throw new Error('ENOENT');
    },
    url: 'u',
    token: 't',
    fetchImpl,
  });
  const empty = await consumePendingHints({
    readPending: () => JSON.stringify({ packages: [] }),
    url: 'u',
    token: 't',
    fetchImpl,
  });

  assert.equal(missing, 0);
  assert.equal(empty, 0);
  assert.equal(fetched, false);
});

test('a verified hint becomes a CROWD catalog entry', () => {
  const entry = crowdEntry({
    packageName: 'com.crowd.app',
    available: true,
    versionCode: 42,
    name: 'Crowd App',
  });

  assert.deepEqual(entry, {
    packageName: 'com.crowd.app',
    appName: 'Crowd App',
    hasBeta: true,
    productionVersionCode: 42,
    liveStatus: 'UNKNOWN',
    source: 'CROWD',
    notes: 'Reported by users, confirmed via Google Play',
  });
});
