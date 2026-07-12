import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  selectHintsToVerify,
  updateRejected,
  crowdEntry,
  REJECTED_TTL_MS,
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
