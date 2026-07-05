import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  parseFeed,
  isBetaItem,
  appPageUrl,
  extractPackageName,
  appNameFromTitle,
} from '../src/apkmirror.js';

const here = dirname(fileURLToPath(import.meta.url));
const fixture = (name) => readFileSync(join(here, 'fixtures', name), 'utf8');

test('parseFeed extracts title and link for each item', () => {
  const items = parseFeed(fixture('sample-feed.xml'));

  assert.equal(items.length, 3);
  assert.equal(items[0].title, 'Telegram Beta 12.9.0 by Telegram FZ-LLC');
  assert.equal(
    items[0].link,
    'https://www.apkmirror.com/apk/telegram-fz-llc/telegram-beta/telegram-beta-12-9-0-release/',
  );
});

test('isBetaItem detects beta or alpha in the title', () => {
  const items = parseFeed(fixture('sample-feed.xml'));

  assert.equal(isBetaItem(items[0]), true); // "Telegram Beta ..."
  assert.equal(isBetaItem(items[1]), false); // "Google Chrome ..." stable
  assert.equal(isBetaItem(items[2]), true); // "... beta ..."
});

test('isBetaItem does not treat "beta" inside another word as a beta', () => {
  assert.equal(
    isBetaItem({ title: 'Betamax Player 1.0 by Acme', link: 'https://x/apk/acme/betamax/betamax-1-0-release/' }),
    false,
  );
});

test('appPageUrl strips the release segment from a release link', () => {
  assert.equal(
    appPageUrl('https://www.apkmirror.com/apk/telegram-fz-llc/telegram-beta/telegram-beta-12-9-0-release/'),
    'https://www.apkmirror.com/apk/telegram-fz-llc/telegram-beta/',
  );
});

test('extractPackageName reads the package from the Play Store link', () => {
  assert.equal(extractPackageName(fixture('app-page-snippet.html')), 'org.telegram.messenger.beta');
});

test('extractPackageName returns null when no Play Store link is present', () => {
  assert.equal(extractPackageName('<html><body>no store link here</body></html>'), null);
});

test('appNameFromTitle strips the version and developer', () => {
  assert.equal(appNameFromTitle('Telegram Beta 12.9.0 by Telegram FZ-LLC'), 'Telegram Beta');
  assert.equal(appNameFromTitle('Google Chrome 149.0.7827.201 by Google LLC'), 'Google Chrome');
  assert.equal(appNameFromTitle('Home Assistant 2026.7.1-full beta by Home Assistant'), 'Home Assistant');
});
