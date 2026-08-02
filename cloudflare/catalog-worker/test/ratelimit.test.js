import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

function fakeKv(initial = {}) {
  const store = new Map(Object.entries(initial));
  return {
    store,
    async get(key) {
      return store.has(key) ? store.get(key) : null;
    },
    async put(key, value) {
      store.set(key, String(value));
    },
    async delete(key) {
      store.delete(key);
    },
    async list({ prefix }) {
      return {
        keys: [...store.keys()].filter((k) => k.startsWith(prefix)).map((name) => ({ name })),
        list_complete: true,
      };
    },
  };
}

function hintPost(packages, ip = '203.0.113.7') {
  return new Request('https://worker.test/hints', {
    method: 'POST',
    headers: { 'cf-connecting-ip': ip, 'content-type': 'application/json' },
    body: JSON.stringify({ version: 1, packages }),
  });
}

const hourBucket = () => Math.floor(Date.now() / 3_600_000);
const dayBucket = () => Math.floor(Date.now() / 86_400_000);

test('a post from an IP over the hourly limit is rejected and stores nothing', async () => {
  const kv = fakeKv({ [`rl:ip:203.0.113.7:${hourBucket()}`]: '30' });

  const response = await worker.fetch(hintPost(['com.example.app']), { CATALOG: kv });

  assert.equal(response.status, 429);
  assert.equal(kv.store.has('hint:com.example.app'), false);
});

test('the hourly limit is per IP, not global', async () => {
  const kv = fakeKv({ [`rl:ip:203.0.113.7:${hourBucket()}`]: '30' });

  const response = await worker.fetch(
    hintPost(['com.example.app'], '198.51.100.9'),
    { CATALOG: kv },
  );

  assert.equal(response.status, 204);
  assert.equal(kv.store.has('hint:com.example.app'), true);
});

test('posts are rejected once the global daily package budget is spent', async () => {
  const kv = fakeKv({ [`rl:global:${dayBucket()}`]: '5000' });

  const response = await worker.fetch(hintPost(['com.example.app']), { CATALOG: kv });

  assert.equal(response.status, 429);
  assert.equal(kv.store.has('hint:com.example.app'), false);
});

test('a normal post advances both counters and stores the hints', async () => {
  const kv = fakeKv();

  const response = await worker.fetch(hintPost(['com.a.one', 'com.b.two']), { CATALOG: kv });

  assert.equal(response.status, 204);
  assert.equal(kv.store.get(`rl:ip:203.0.113.7:${hourBucket()}`), '1');
  assert.equal(kv.store.get(`rl:global:${dayBucket()}`), '2');
  assert.equal(kv.store.has('hint:com.a.one'), true);
  assert.equal(kv.store.has('hint:com.b.two'), true);
});
