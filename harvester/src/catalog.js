// Assembles catalog entries into the v2 catalog file the Android app consumes.
// Schema is documented in SCHEMA.md. The app reads packageName, appName,
// testingUrl, knownStatus, liveStatus, statusCheckedAt and notes; extra fields
// (source, hasBeta) are provenance the app ignores.

/** Builds a catalog entry for a beta discovered on APKMirror (existence only, live status unknown). */
export function buildCatalogEntry({ packageName, appName }) {
  return {
    packageName,
    appName,
    hasBeta: true,
    liveStatus: 'UNKNOWN',
    source: 'APKMIRROR',
    notes: 'Beta build seen on APKMirror',
  };
}

/**
 * Reads the previously published catalog via [read] and returns its programs.
 * A missing file (first run) yields []; any other failure — unreadable file,
 * corrupt JSON, missing programs array — throws, because accumulating from an
 * empty "existing" list would republish a near-empty catalog to every device.
 */
export function loadPublishedPrograms(read) {
  let text;
  try {
    text = read();
  } catch (error) {
    if (error?.code === 'ENOENT') return [];
    throw error;
  }
  const programs = JSON.parse(text).programs;
  if (!Array.isArray(programs)) throw new Error('published catalog has no programs array');
  return programs;
}

/** Wraps entries into a versioned catalog, deduplicated by package name and sorted. */
export function buildCatalog(entries, { generatedAt }) {
  const byPackage = new Map();
  for (const entry of entries) {
    if (!byPackage.has(entry.packageName)) byPackage.set(entry.packageName, entry);
  }
  const programs = [...byPackage.values()].sort((a, b) => a.packageName.localeCompare(b.packageName));
  return { version: 2, generatedAt, programs };
}
