// Orchestrates a harvest: read APKMirror release feeds, keep beta uploads,
// resolve each app's package name from its app page, and write catalog.json.
//
// Only metadata is read (no APK downloads). Requests are spaced by the crawl
// delay APKMirror's robots.txt asks for, and capped per run; anything skipped
// is logged rather than silently dropped.

import { writeFileSync } from 'node:fs';
import {
  parseFeed,
  isBetaItem,
  appPageUrl,
  extractPackageName,
  appNameFromTitle,
} from './apkmirror.js';
import { buildCatalogEntry, buildCatalog } from './catalog.js';
import { fetchText, sleep } from './fetch.js';

const FEEDS = ['https://www.apkmirror.com/feed/'];
const CRAWL_DELAY_MS = 3000; // robots.txt: Crawl-delay: 3
const MAX_APPS = Number(process.env.MAX_APPS ?? 8);

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

async function main() {
  const betaByAppPage = await collectBetaAppPages();
  console.log(`Beta uploads found in feeds: ${betaByAppPage.size}`);

  const entries = [];
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
    const appName = appNameFromTitle(title);
    entries.push(buildCatalogEntry({ packageName, appName }));
    console.log(`  + ${packageName}  (${appName})`);
  }
  if (dropped > 0) {
    console.error(`NOTE: capped at MAX_APPS=${MAX_APPS}; ${dropped} beta app(s) not processed this run.`);
  }

  const catalog = buildCatalog(entries, { generatedAt: Date.now() });
  writeFileSync(new URL('../catalog.json', import.meta.url), JSON.stringify(catalog, null, 2) + '\n');
  console.log(`\nWrote catalog.json with ${catalog.programs.length} program(s).`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
