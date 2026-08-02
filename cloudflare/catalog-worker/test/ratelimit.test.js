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

/** Rate Limiting binding double: [allowed] scripts each successive limit() call. */
function fakeLimiter(...allowed) {
  const keys = [];
  return {
    keys,
    async limit({ key }) {
      keys.push(key);
      return { success: allowed.length > 0 ? allowed.shift() : true };
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

const dayBucket = () => Math.floor(Date.now() / 86_400_000);

test('a post rejected by the per-source limiter answers 429 and stores nothing', async () => {
  const kv = fakeKv();
  const limiter = fakeLimiter(false);

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_LIMITER: limiter,
  });

  assert.equal(response.status, 429);
  assert.equal(kv.store.has('hint:com.example.app'), false);
  assert.deepEqual(limiter.keys, ['203.0.113.7']);
});

test('posts are rejected once the global daily package budget is spent', async () => {
  const kv = fakeKv({ [`rl:global:${dayBucket()}:3`]: '5000' });

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
  });

  assert.equal(response.status, 429);
  assert.equal(kv.store.has('hint:com.example.app'), false);
});

test('a normal post advances the sharded budget, stores the hints and never stores the IP', async () => {
  const kv = fakeKv();

  const response = await worker.fetch(hintPost(['com.a.one', 'com.b.two']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
  });

  assert.equal(response.status, 204);
  assert.equal(kv.store.has('hint:com.a.one'), true);
  assert.equal(kv.store.has('hint:com.b.two'), true);
  const shardTotal = [...kv.store.entries()]
    .filter(([key]) => key.startsWith('rl:global:'))
    .reduce((sum, [, value]) => sum + Number(value), 0);
  assert.equal(shardTotal, 2);
  // PRIVACY.md: "no IP logging on the server" — the address must never reach
  // storage, neither as a key nor as a value.
  for (const [key, value] of kv.store.entries()) {
    assert.ok(!key.includes('203.0.113.7'), `IP leaked into key ${key}`);
    assert.ok(!String(value).includes('203.0.113.7'), `IP leaked into value of ${key}`);
  }
});

test('a missing rate-limiter binding does not break the intake', async () => {
  const kv = fakeKv();

  const response = await worker.fetch(hintPost(['com.example.app']), { CATALOG: kv });

  assert.equal(response.status, 204);
  assert.equal(kv.store.has('hint:com.example.app'), true);
});

test('a failing budget-counter write still stores the hints', async () => {
  const kv = fakeKv();
  const put = kv.put.bind(kv);
  kv.put = async (key, value) => {
    // Simulates KV's one-write-per-second-per-key rejection on the counter.
    if (key.startsWith('rl:global:')) throw new Error('KV PUT rate limited');
    return put(key, value);
  };

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
  });

  assert.equal(response.status, 204);
  assert.equal(kv.store.has('hint:com.example.app'), true);
});
