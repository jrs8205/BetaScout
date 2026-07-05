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

/** Wraps entries into a versioned catalog, deduplicated by package name and sorted. */
export function buildCatalog(entries, { generatedAt }) {
  const byPackage = new Map();
  for (const entry of entries) {
    if (!byPackage.has(entry.packageName)) byPackage.set(entry.packageName, entry);
  }
  const programs = [...byPackage.values()].sort((a, b) => a.packageName.localeCompare(b.packageName));
  return { version: 2, generatedAt, programs };
}
