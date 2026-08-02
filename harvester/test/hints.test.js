import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  selectHintsToVerify,
  updateRejected,
  crowdEntry,
  partitionVerifyResults,
  updateErrored,
  clearErrored,
  REJECTED_TTL_MS,
  ERRORED_TTL_MS,
} from '../src/hints.js';

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
