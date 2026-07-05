// Pure parsing helpers for APKMirror's public release feeds and app pages.
// These read only metadata (app name, package, beta-or-not) — no APK downloads.

const XML_ENTITIES = {
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&#39;': "'",
  '&apos;': "'",
};

function decodeXml(text) {
  return text.replace(/&(amp|lt|gt|quot|#39|apos);/g, (m) => XML_ENTITIES[m] ?? m);
}

/** Parses an APKMirror RSS feed into `{ title, link }` items. */
export function parseFeed(xml) {
  const items = [];
  const itemRe = /<item>([\s\S]*?)<\/item>/g;
  let match;
  while ((match = itemRe.exec(xml)) !== null) {
    const block = match[1];
    const title = decodeXml((block.match(/<title>([\s\S]*?)<\/title>/)?.[1] ?? '').trim());
    const link = (block.match(/<link>([\s\S]*?)<\/link>/)?.[1] ?? '').trim();
    if (title || link) items.push({ title, link });
  }
  return items;
}

/** True when the release is a beta/alpha build (standalone word in the title). */
export function isBetaItem(item) {
  return /\b(beta|alpha)\b/i.test(item.title ?? '');
}

/** Turns a release-page link into the parent app-page URL by dropping the last segment. */
export function appPageUrl(releaseLink) {
  const parts = releaseLink.replace(/\/+$/, '').split('/');
  parts.pop();
  return parts.join('/') + '/';
}

/** Extracts the Play Store package id from an app page's "View on Play Store" link. */
export function extractPackageName(appPageHtml) {
  const match = appPageHtml.match(/play\.google\.com\/store\/apps\/details\?id=([A-Za-z0-9._]+)/);
  return match ? match[1] : null;
}

/** Derives a clean app name from a feed title by dropping the version and "by <developer>". */
export function appNameFromTitle(title) {
  const withoutDeveloper = title.replace(/\s+by\s+.*$/i, '').trim();
  const tokens = withoutDeveloper.split(/\s+/);
  const versionAt = tokens.findIndex((token) => /^\d/.test(token));
  const name = (versionAt === -1 ? tokens : tokens.slice(0, versionAt)).join(' ').trim();
  return name || withoutDeveloper;
}
