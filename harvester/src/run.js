// Harvest pipeline: discover beta apps from APKMirror, optionally confirm/enrich
// each via the authoritative gplayapi tool, merge, and write catalog.json.
//
// Only metadata is read (no APK downloads). APKMirror requests are spaced by the
// robots.txt crawl delay and capped per run; anything skipped is logged.
//
// Set GPLAY_ENABLED=1 (with a JDK and harvester/.gplay.local) to add the
// authoritative Google Play data; otherwise the APKMirror signal stands alone.

import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  parseFeed,
  isBetaItem,
  appPageUrl,
  extractPackageName,
  appNameFromTitle,
} from './apkmirror.js';
import { buildCatalog } from './catalog.js';
import { mergeCatalogEntries } from './merge.js';
import { runGplay } from './gplay.js';
import { fetchText, sleep } from './fetch.js';

const FEEDS = ['https://www.apkmirror.com/feed/'];
const CRAWL_DELAY_MS = 3000; // robots.txt: Crawl-delay: 3
const MAX_APPS = Number(process.env.MAX_APPS ?? 8);

const REPO_ROOT = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/\\]$/, '');
const GPLAY_ENABLED = process.env.GPLAY_ENABLED === '1';
const GRADLEW = process.env.GRADLEW || `${REPO_ROOT}${process.platform === 'win32' ? '/gradlew.bat' : '/gradlew'}`;
const JAVA_HOME = process.env.GPLAY_JAVA_HOME || process.env.JAVA_HOME;

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

function enrichViaGplay(discovered) {
  if (!GPLAY_ENABLED) {
    console.error('gplay enrichment disabled (set GPLAY_ENABLED=1 to confirm via Google Play).');
    return [];
  }
  const packages = discovered.map((d) => d.packageName);
  console.error(`Confirming ${packages.length} package(s) via gplayapi...`);
  try {
    return runGplay(packages, { gradlew: GRADLEW, projectRoot: REPO_ROOT, javaHome: JAVA_HOME });
  } catch (error) {
    console.error(`gplay enrichment failed (continuing with APKMirror only): ${error.message}`);
    return [];
  }
}

async function main() {
  const betaByAppPage = await collectBetaAppPages();
  console.log(`Beta uploads found in feeds: ${betaByAppPage.size}`);

  const discovered = await discoverPackages(betaByAppPage);
  const gplayResults = enrichViaGplay(discovered);

  const entries = mergeCatalogEntries(discovered, gplayResults);
  const confirmed = entries.filter((e) => e.source === 'GPLAYAPI').length;
  const catalog = buildCatalog(entries, { generatedAt: Date.now() });
  writeFileSync(new URL('../catalog.json', import.meta.url), JSON.stringify(catalog, null, 2) + '\n');
  console.log(`\nWrote catalog.json: ${catalog.programs.length} program(s), ${confirmed} confirmed via Google Play.`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
