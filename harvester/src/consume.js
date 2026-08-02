// Final harvest step: consume the settled hints recorded by run.js, AFTER the
// catalog has been durably published (KV put + git commit). Kept as its own
// script so a failed publish skips it and the hints stay pending on the
// Worker — the next run re-verifies and settles them instead of losing them.

import { readFileSync } from 'node:fs';
import { consumePendingHints } from './hints.js';

const CONSUME_PENDING_URL = new URL('../hints-consume-pending.json', import.meta.url);

const url = process.env.HINTS_URL;
const token = process.env.HINTS_TOKEN;
if (!url || !token) {
  console.error('hints consume skipped (HINTS_URL/HINTS_TOKEN not set).');
} else {
  try {
    const count = await consumePendingHints({
      readPending: () => readFileSync(CONSUME_PENDING_URL, 'utf8'),
      url,
      token,
      fetchImpl: fetch,
    });
    console.log(`Hints consumed after publish: ${count}.`);
  } catch (error) {
    // Deliberately non-fatal: unconsumed hints are re-settled by the next run.
    console.error(`hints consume failed (settled next run): ${error.message}`);
  }
}
