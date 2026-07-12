import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseHintRequest } from '../src/hints.js';

const catalog = new Set(['com.known.app']);

test('a valid batch is accepted and catalog members are filtered out', () => {
  const body = JSON.stringify({ version: 1, packages: ['com.example.app', 'com.known.app'] });
  const result = parseHintRequest(body, catalog);
  assert.equal(result.status, 204);
  assert.deepEqual(result.accepted, ['com.example.app']);
});

test('more than 50 entries is rejected as too large', () => {
  const packages = Array.from({ length: 51 }, (_, i) => `com.example.app${i}`);
  const result = parseHintRequest(JSON.stringify({ version: 1, packages }), catalog);
  assert.equal(result.status, 413);
  assert.deepEqual(result.accepted, []);
});

test('malformed JSON is rejected', () => {
  assert.equal(parseHintRequest('not json', catalog).status, 400);
});

test('a missing packages array is rejected', () => {
  assert.equal(parseHintRequest(JSON.stringify({ version: 1 }), catalog).status, 400);
});

test('an invalid package name rejects the whole request', () => {
  // Whole-request rejection: partially-invalid batches must not store anything.
  const body = JSON.stringify({ version: 1, packages: ['com.ok.app', 'not a package!'] });
  assert.equal(parseHintRequest(body, catalog).status, 400);
});

test('single-segment and overlong names are invalid', () => {
  assert.equal(
    parseHintRequest(JSON.stringify({ version: 1, packages: ['nodots'] }), catalog).status,
    400,
  );
  const long = 'com.' + 'a'.repeat(150);
  assert.equal(
    parseHintRequest(JSON.stringify({ version: 1, packages: [long] }), catalog).status,
    400,
  );
});

test('a batch that is entirely already-known still answers 204', () => {
  // 204 regardless of how much was stored — no feedback channel for probing
  // what the catalog contains.
  const result = parseHintRequest(JSON.stringify({ version: 1, packages: ['com.known.app'] }), catalog);
  assert.equal(result.status, 204);
  assert.deepEqual(result.accepted, []);
});

test('duplicates collapse to one accepted entry', () => {
  const body = JSON.stringify({ version: 1, packages: ['com.example.app', 'com.example.app'] });
  assert.deepEqual(parseHintRequest(body, catalog).accepted, ['com.example.app']);
});
