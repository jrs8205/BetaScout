import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { HintBudget } from '../src/index.js';

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

function fakeDoState() {
  const map = new Map();
  return {
    map,
    storage: {
      async get(key) {
        return map.get(key);
      },
      async put(key, value) {
        map.set(key, value);
      },
    },
  };
}

/** Wires a real HintBudget instance behind a fake DO namespace binding. */
function budgetBinding(budget) {
  return {
    idFromName: (name) => name,
    get: () => ({
      fetch: (url, options) => budget.fetch(new Request(url, options)),
    }),
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

async function spend(budget, count, day = dayBucket()) {
  const response = await budget.fetch(
    new Request('https://hint-budget/spend', {
      method: 'POST',
      body: JSON.stringify({ day, count, limit: 5000 }),
    }),
  );
  return (await response.json()).allowed;
}

test('a post rejected by the per-source limiter answers 429 and stores nothing', async () => {
  const kv = fakeKv();
  const limiter = fakeLimiter(false);

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_LIMITER: limiter,
    HINT_BUDGET: budgetBinding(new HintBudget(fakeDoState())),
  });

  assert.equal(response.status, 429);
  assert.equal(kv.store.has('hint:com.example.app'), false);
  assert.deepEqual(limiter.keys, ['203.0.113.7']);
});

test('posts are rejected once the daily budget object says no', async () => {
  const kv = fakeKv();
  const budget = new HintBudget(fakeDoState());
  assert.equal(await spend(budget, 5000), true);

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
    HINT_BUDGET: budgetBinding(budget),
  });

  assert.equal(response.status, 429);
  assert.equal(kv.store.has('hint:com.example.app'), false);
});

test('a normal post spends the budget, stores the hints and never stores the IP', async () => {
  const kv = fakeKv();
  const state = fakeDoState();

  const response = await worker.fetch(hintPost(['com.a.one', 'com.b.two']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
    HINT_BUDGET: budgetBinding(new HintBudget(state)),
  });

  assert.equal(response.status, 204);
  assert.equal(kv.store.has('hint:com.a.one'), true);
  assert.equal(kv.store.has('hint:com.b.two'), true);
  assert.equal(state.map.get('budget').spent, 2);
  // PRIVACY.md: "no IP logging on the server" — the address must never reach
  // storage: not in KV keys or values, not in the budget object's state.
  for (const [key, value] of kv.store.entries()) {
    assert.ok(!key.includes('203.0.113.7'), `IP leaked into KV key ${key}`);
    assert.ok(!String(value).includes('203.0.113.7'), `IP leaked into KV value of ${key}`);
  }
  assert.ok(!JSON.stringify([...state.map.entries()]).includes('203.0.113.7'));
});

test('the budget is atomic per day and resets on a new day', async () => {
  const budget = new HintBudget(fakeDoState());

  assert.equal(await spend(budget, 4_999, 100), true);
  assert.equal(await spend(budget, 2, 100), false);
  assert.equal(await spend(budget, 1, 100), true);
  // A new day starts a fresh budget.
  assert.equal(await spend(budget, 4_999, 101), true);
});

test('a missing limiter binding alone does not break the intake', async () => {
  const kv = fakeKv();

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_BUDGET: budgetBinding(new HintBudget(fakeDoState())),
  });

  assert.equal(response.status, 204);
  assert.equal(kv.store.has('hint:com.example.app'), true);
});

test('a missing budget binding fails closed instead of accepting unbudgeted hints', async () => {
  const kv = fakeKv();

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
  });

  assert.equal(response.status, 503);
  assert.equal(kv.store.has('hint:com.example.app'), false);
});

test('a failing budget object fails closed and stores nothing', async () => {
  // An overloaded Durable Object propagates errors to the caller; answering
  // 503 makes the device retry after a later scan (non-2xx batches are not
  // marked reported), while failing open would waive the whole daily budget
  // exactly when a flood is hammering it.
  const kv = fakeKv();

  const response = await worker.fetch(hintPost(['com.example.app']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
    HINT_BUDGET: {
      idFromName: (name) => name,
      get: () => ({
        fetch: async () => {
          throw new Error('DO overloaded');
        },
      }),
    },
  });

  assert.equal(response.status, 503);
  assert.equal(kv.store.has('hint:com.example.app'), false);
});

test('a post entirely of catalog members skips the budget and still answers 204', async () => {
  // Nothing would be stored, so no budget is needed — and the endpoint must
  // stay unprobeable (204 either way) even when the budget binding is absent.
  const kv = fakeKv({ catalog: JSON.stringify({ programs: [{ packageName: 'com.known.app' }] }) });

  const response = await worker.fetch(hintPost(['com.known.app']), {
    CATALOG: kv,
    HINT_LIMITER: fakeLimiter(),
  });

  assert.equal(response.status, 204);
  assert.equal(kv.store.has('hint:com.known.app'), false);
});
