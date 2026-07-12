// Harvest pipeline: discover beta apps from APKMirror, confirm/enrich a curated
// seed + the discoveries via the authoritative gplayapi tool, merge, accumulate
// into the published catalog, and write catalog/catalog.json.
//
// Only metadata is read (no APK downloads). APKMirror requests are spaced by the
// robots.txt crawl delay and capped per run; anything skipped is logged.
//
// Set GPLAY_ENABLED=1 (with a JDK and harvester/.gplay.local) to add the
// authoritative Google Play data; otherwise the APKMirror signal stands alone.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  parseFeed,
  isBetaItem,
  appPageUrl,
  extractPackageName,
  appNameFromTitle,
} from './apkmirror.js';
import { buildCatalog } from './catalog.js';
import { mergeCatalogEntries, accumulateCatalog } from './merge.js';
import { runGplay } from './gplay.js';
import { fetchText, sleep } from './fetch.js';
import {
  selectHintsToVerify,
  updateRejected,
  crowdEntry,
  fetchHints,
  consumeHints,
} from './hints.js';

const FEEDS = ['https://www.apkmirror.com/feed/'];
const CRAWL_DELAY_MS = 3000; // robots.txt: Crawl-delay: 3
const MAX_APPS = Number(process.env.MAX_APPS ?? 8);

const REPO_ROOT = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/\\]$/, '');
const SEED_URL = new URL('../seed-packages.json', import.meta.url);
const PUBLISHED_URL = new URL('../../catalog/catalog.json', import.meta.url);

const GPLAY_ENABLED = process.env.GPLAY_ENABLED === '1';
const HINTS_URL = process.env.HINTS_URL;
const HINTS_TOKEN = process.env.HINTS_TOKEN;
const GPLAY_HINTS_CAP = Number(process.env.GPLAY_HINTS_CAP ?? 25);
const REJECTED_URL = new URL('../hints-rejected.json', import.meta.url);
const GRADLEW = process.env.GRADLEW || `${REPO_ROOT}${process.platform === 'win32' ? '/gradlew.bat' : '/gradlew'}`;
const JAVA_HOME = process.env.GPLAY_JAVA_HOME || process.env.JAVA_HOME;

function loadSeedPackages() {
  try {
    return JSON.parse(readFileSync(SEED_URL, 'utf8')).packages ?? [];
  } catch {
    return [];
  }
}

function loadExistingPrograms() {
  try {
    return JSON.parse(readFileSync(PUBLISHED_URL, 'utf8')).programs ?? [];
  } catch {
    return [];
  }
}

async function collectBetaAppPages() {
  const betaByAppPage = new Map(); // app page URL -> feed title
  for (const feedUrl of FEEDS) {
    const { status, text } = await fetchText(feedUrl);
    if (status !== 200) {
      console.error(`feed ${feedUrl} -> HTTP ${status}`);
      continue;
    }
    for (const item of parseFeed(text).filter(isBetaItem)) {
      const page = appPageUrl(item.link);
      if (!betaByAppPage.has(page)) betaByAppPage.set(page, item.title);
    }
  }
  return betaByAppPage;
}

async function discoverPackages(betaByAppPage) {
  const discovered = [];
  let processed = 0;
  let dropped = 0;
  for (const [page, title] of betaByAppPage) {
    if (processed >= MAX_APPS) {
      dropped++;
      continue;
    }
    await sleep(CRAWL_DELAY_MS);
    const { status, text } = await fetchText(page);
    processed++;
    if (status !== 200) {
      console.error(`  page ${page} -> HTTP ${status} (skipped)`);
      continue;
    }
    const packageName = extractPackageName(text);
    if (!packageName) {
      console.error(`  no package on ${page} (skipped)`);
      continue;
    }
    discovered.push({ packageName, appName: appNameFromTitle(title) });
    console.log(`  discovered ${packageName}`);
  }
  if (dropped > 0) {
    console.error(`NOTE: capped at MAX_APPS=${MAX_APPS}; ${dropped} beta app(s) not processed this run.`);
  }
  return discovered;
}

function enrichViaGplay(packageNames) {
  if (!GPLAY_ENABLED) {
    console.error('gplay enrichment disabled (set GPLAY_ENABLED=1 to confirm via Google Play).');
    return [];
  }
  console.error(`Confirming ${packageNames.length} package(s) via gplayapi...`);
  try {
    return runGplay(packageNames, { gradlew: GRADLEW, projectRoot: REPO_ROOT, javaHome: JAVA_HOME });
  } catch (error) {
    console.error(`gplay enrichment failed (continuing with APKMirror only): ${error.message}`);
    return [];
  }
}

function loadRejected() {
  try {
    return JSON.parse(readFileSync(REJECTED_URL, 'utf8')).rejected ?? [];
  } catch {
    return [];
  }
}

function saveRejected(rejected) {
  writeFileSync(REJECTED_URL, JSON.stringify({ rejected }, null, 2) + '\n');
}

/**
 * Fetches crowd hints from the Worker, verifies at most GPLAY_HINTS_CAP new
 * packages via gplayapi and returns the verified ones as CROWD catalog entries.
 * Every failure path degrades to "skip" — the normal harvest must never depend
 * on the hints pipeline being up.
 */
async function processHints(knownPrograms) {
  if (!HINTS_URL || !HINTS_TOKEN) {
    console.error('hints step skipped (HINTS_URL/HINTS_TOKEN not set).');
    return [];
  }
  let hints;
  try {
    hints = await fetchHints(HINTS_URL, HINTS_TOKEN, fetch);
  } catch (error) {
    console.error(`hints fetch failed (skipping the step): ${error.message}`);
    return [];
  }
  if (hints.length === 0) {
    console.log('Hints: none pending.');
    return [];
  }

  const rejected = loadRejected();
  const { verify, alreadyInCatalog, cooling, overflow } = selectHintsToVerify(hints, {
    catalogPackages: new Set(knownPrograms.map((p) => p.packageName)),
    rejected,
    now: Date.now(),
    cap: GPLAY_HINTS_CAP,
  });
  console.log(
    `Hints: ${hints.length} pending; verifying ${verify.length} ` +
      `(in catalog ${alreadyInCatalog.length}, cooling ${cooling.length}, over cap ${overflow.length}).`,
  );

  const results = verify.length > 0 ? enrichViaGplay(verify) : [];
  const byPackage = new Map(
    results
      .filter((r) => r && !r.error && r.available !== undefined)
      .map((r) => [r.packageName, r]),
  );
  const entries = [];
  const rejectedNow = [];
  for (const packageName of verify) {
    const result = byPackage.get(packageName);
    // A gplayapi error leaves the hint pending for the next run.
    if (!result) continue;
    if (result.available) {
      entries.push(crowdEntry(result));
    } else {
      rejectedNow.push(packageName);
    }
  }
  if (rejectedNow.length > 0) {
    saveRejected(updateRejected(rejected, rejectedNow, Date.now()));
  }

  // Merged, rejected and already-in-catalog hints are all settled; only
  // pending ones (over cap, cooling, gplay errors) stay on the Worker.
  const consumed = [...entries.map((e) => e.packageName), ...rejectedNow, ...alreadyInCatalog];
  if (consumed.length > 0) {
    try {
      await consumeHints(HINTS_URL, HINTS_TOKEN, consumed, fetch);
    } catch (error) {
      console.error(`hints consume failed (retried next run): ${error.message}`);
    }
  }
  console.log(`Hints verified: ${entries.length} added as CROWD, ${rejectedNow.length} rejected.`);
  return entries;
}

async function main() {
  const betaByAppPage = await collectBetaAppPages();
  console.log(`Beta uploads found in feeds: ${betaByAppPage.size}`);

  const discovered = await discoverPackages(betaByAppPage);
  const seed = loadSeedPackages();
  const packagesToConfirm = [...new Set([...discovered.map((d) => d.packageName), ...seed])];
  const gplayResults = enrichViaGplay(packagesToConfirm);

  const fresh = mergeCatalogEntries(discovered, gplayResults);
  const existing = loadExistingPrograms();
  const crowd = await processHints([...existing, ...fresh]);
  const accumulated = accumulateCatalog(existing, [...fresh, ...crowd]);
  const published = accumulated.filter((e) => e.hasBeta === true);

  const catalog = buildCatalog(published, { generatedAt: Date.now() });
  mkdirSync(fileURLToPath(new URL('.', PUBLISHED_URL)), { recursive: true });
  writeFileSync(PUBLISHED_URL, JSON.stringify(catalog, null, 2) + '\n');

  const confirmed = published.filter((e) => e.source === 'GPLAYAPI').length;
  console.log(`\nWrote catalog/catalog.json: ${catalog.programs.length} program(s), ${confirmed} confirmed via Google Play.`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
