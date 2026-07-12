// Validation and filtering for POST /hints, kept pure for node --test.

export const PACKAGE_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;

const MAX_PACKAGES = 50;
const MAX_NAME_LENGTH = 150;

/**
 * @param {string} bodyText raw request body
 * @param {Set<string>} catalogPackages packages already in the published catalog
 * @returns {{status: 204|400|413, accepted: string[]}} Invalid input rejects the
 *   whole request (nothing from a partially-invalid batch is stored); packages
 *   the catalog already lists are silently dropped, and the response stays 204
 *   either way so the endpoint cannot be used to probe the catalog.
 */
export function parseHintRequest(bodyText, catalogPackages) {
  let parsed;
  try {
    parsed = JSON.parse(bodyText);
  } catch {
    return { status: 400, accepted: [] };
  }
  const packages = parsed?.packages;
  if (!Array.isArray(packages)) return { status: 400, accepted: [] };
  if (packages.length > MAX_PACKAGES) return { status: 413, accepted: [] };
  for (const name of packages) {
    if (typeof name !== 'string' || name.length > MAX_NAME_LENGTH || !PACKAGE_RE.test(name)) {
      return { status: 400, accepted: [] };
    }
  }
  const accepted = [...new Set(packages)].filter((name) => !catalogPackages.has(name));
  return { status: 204, accepted };
}
