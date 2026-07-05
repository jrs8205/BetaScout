// Merges APKMirror discovery with authoritative gplayapi results into catalog
// entries. gplayapi wins when present (it is Google's own data); otherwise the
// APKMirror "a beta build exists" signal stands.

/** @param discovered [{packageName, appName}]  @param gplayResults [{packageName, available, versionCode, name, error}] */
export function mergeCatalogEntries(discovered, gplayResults) {
  const gplayByPackage = new Map();
  for (const result of gplayResults) {
    if (result && !result.error && result.available !== undefined) {
      gplayByPackage.set(result.packageName, result);
    }
  }
  const discoveredByPackage = new Map(discovered.map((d) => [d.packageName, d]));

  const packages = new Set([...discoveredByPackage.keys(), ...gplayByPackage.keys()]);
  const entries = [];
  for (const packageName of packages) {
    const gplay = gplayByPackage.get(packageName);
    const found = discoveredByPackage.get(packageName);
    if (gplay) {
      entries.push({
        packageName,
        appName: gplay.name || found?.appName || packageName,
        hasBeta: gplay.available,
        productionVersionCode: gplay.versionCode,
        liveStatus: 'UNKNOWN',
        source: 'GPLAYAPI',
        notes: 'Confirmed via Google Play',
      });
    } else {
      entries.push({
        packageName,
        appName: found?.appName || packageName,
        hasBeta: true,
        liveStatus: 'UNKNOWN',
        source: 'APKMIRROR',
        notes: 'Beta build seen on APKMirror',
      });
    }
  }
  return entries;
}

/**
 * Accumulates this run's fresh entries into the previously published programs:
 * a fresh entry replaces the existing one for the same package, packages not
 * seen this run are kept, and new packages are added. Lets the catalog grow
 * across runs instead of being overwritten with only the latest finds.
 */
export function accumulateCatalog(existingPrograms, freshEntries) {
  const byPackage = new Map(existingPrograms.map((e) => [e.packageName, e]));
  for (const entry of freshEntries) {
    byPackage.set(entry.packageName, entry);
  }
  return [...byPackage.values()];
}
