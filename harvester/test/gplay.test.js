import { test } from 'node:test';
import assert from 'node:assert/strict';

import { parseGplayLine } from '../src/gplay.js';

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

test('returns null for non-data lines', () => {
  assert.equal(parseGplayLine('AUTH OK: authenticated as x'), null);
  assert.equal(parseGplayLine(''), null);
});

test('strips a trailing carriage return from Windows line endings', () => {
  const result = parseGplayLine('com.a\tavailable=true\tsubscribed=false\tversionCode=1\tname=App A\r');

  assert.equal(result.name, 'App A');
});
