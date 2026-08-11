import { test } from 'node:test';
import assert from 'node:assert/strict';

import { loadPublishedPrograms } from '../src/catalog.js';

test('a missing published catalog is a first run and yields no programs', () => {
  const enoent = Object.assign(new Error('no such file'), { code: 'ENOENT' });

  assert.deepEqual(loadPublishedPrograms(() => { throw enoent; }), []);
});

test('a corrupt or unreadable published catalog aborts instead of silently resetting', () => {
  // Returning [] here would make the harvest accumulate from nothing and
  // publish a near-empty catalog to every device, with no error in the log.
  assert.throws(() => loadPublishedPrograms(() => '{"programs": [truncated'));
  assert.throws(() => loadPublishedPrograms(() => '{"somethingElse": true}'), /programs/);
  const eacces = Object.assign(new Error('permission denied'), { code: 'EACCES' });
  assert.throws(() => loadPublishedPrograms(() => { throw eacces; }), /permission denied/);
});

test('a readable published catalog returns its programs', () => {
  assert.deepEqual(
    loadPublishedPrograms(() => '{"programs":[{"packageName":"com.a"}]}'),
    [{ packageName: 'com.a' }],
  );
});

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
