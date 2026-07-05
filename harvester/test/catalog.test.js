import { test } from 'node:test';
import assert from 'node:assert/strict';

import { buildCatalogEntry, buildCatalog } from '../src/catalog.js';

test('buildCatalogEntry marks an APKMirror-discovered beta as unknown live status', () => {
  const entry = buildCatalogEntry({ packageName: 'org.telegram.messenger.beta', appName: 'Telegram Beta' });

  assert.equal(entry.packageName, 'org.telegram.messenger.beta');
  assert.equal(entry.appName, 'Telegram Beta');
  assert.equal(entry.liveStatus, 'UNKNOWN');
  assert.equal(entry.source, 'APKMIRROR');
});

test('buildCatalog wraps entries as a versioned catalog file', () => {
  const catalog = buildCatalog(
    [buildCatalogEntry({ packageName: 'com.a', appName: 'A' })],
    { generatedAt: 1720000000000 },
  );

  assert.equal(catalog.version, 2);
  assert.equal(catalog.generatedAt, 1720000000000);
  assert.equal(catalog.programs.length, 1);
});

test('buildCatalog deduplicates by package name and sorts by package', () => {
  const catalog = buildCatalog(
    [
      buildCatalogEntry({ packageName: 'com.b', appName: 'B' }),
      buildCatalogEntry({ packageName: 'com.a', appName: 'A' }),
      buildCatalogEntry({ packageName: 'com.a', appName: 'A duplicate' }),
    ],
    { generatedAt: 1 },
  );

  assert.deepEqual(catalog.programs.map((p) => p.packageName), ['com.a', 'com.b']);
});
