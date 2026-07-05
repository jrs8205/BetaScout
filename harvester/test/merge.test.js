import { test } from 'node:test';
import assert from 'node:assert/strict';

import { mergeCatalogEntries } from '../src/merge.js';

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
