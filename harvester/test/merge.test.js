import { test } from 'node:test';
import assert from 'node:assert/strict';

import { mergeCatalogEntries, accumulateCatalog } from '../src/merge.js';

test('gplay data enriches a discovered app as authoritative', () => {
  const entries = mergeCatalogEntries(
    [{ packageName: 'com.a', appName: 'A' }],
    [{ packageName: 'com.a', available: true, subscribed: false, versionCode: 100, name: 'App A' }],
  );

  const entry = entries.find((e) => e.packageName === 'com.a');
  assert.equal(entry.hasBeta, true);
  assert.equal(entry.productionVersionCode, 100);
  assert.equal(entry.appName, 'App A');
  assert.equal(entry.source, 'GPLAYAPI');
});

test('gplay availability=false overrides APKMirror discovery', () => {
  const entries = mergeCatalogEntries(
    [{ packageName: 'com.b', appName: 'B' }],
    [{ packageName: 'com.b', available: false, versionCode: 5, name: 'B' }],
  );

  assert.equal(entries.find((e) => e.packageName === 'com.b').hasBeta, false);
});

test('discovered app without gplay data falls back to APKMirror', () => {
  const entries = mergeCatalogEntries([{ packageName: 'com.c', appName: 'C' }], []);

  const entry = entries.find((e) => e.packageName === 'com.c');
  assert.equal(entry.hasBeta, true);
  assert.equal(entry.source, 'APKMIRROR');
  assert.equal(entry.productionVersionCode, undefined);
});

test('a gplay error counts as no data', () => {
  const entries = mergeCatalogEntries(
    [{ packageName: 'com.d', appName: 'D' }],
    [{ packageName: 'com.d', error: 'boom' }],
  );

  assert.equal(entries.find((e) => e.packageName === 'com.d').source, 'APKMIRROR');
});

test('a gplay-only package is included', () => {
  const entries = mergeCatalogEntries(
    [],
    [{ packageName: 'com.e', available: true, versionCode: 9, name: 'E' }],
  );

  assert.equal(entries.find((e) => e.packageName === 'com.e').source, 'GPLAYAPI');
});

test('accumulate updates existing entries, keeps unseen ones and adds new ones', () => {
  const merged = accumulateCatalog(
    [
      { packageName: 'com.a', productionVersionCode: 1, source: 'APKMIRROR' },
      { packageName: 'com.old', productionVersionCode: 7, source: 'GPLAYAPI' },
    ],
    [
      { packageName: 'com.a', productionVersionCode: 2, source: 'GPLAYAPI' },
      { packageName: 'com.new', productionVersionCode: 3, source: 'GPLAYAPI' },
    ],
  );

  const byPackage = Object.fromEntries(merged.map((e) => [e.packageName, e]));
  assert.equal(merged.length, 3);
  assert.equal(byPackage['com.a'].productionVersionCode, 2); // fresh wins
  assert.equal(byPackage['com.old'].productionVersionCode, 7); // kept
  assert.ok(byPackage['com.new']); // added
});
