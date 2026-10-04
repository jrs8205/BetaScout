// Validation and filtering for POST /hints, kept pure for node --test.

export const PACKAGE_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;

const MAX_PACKAGES = 50;
const MAX_NAME_LENGTH = 150;
// A legitimate batch (50 names × 150 chars + JSON overhead) stays under 10 KB;
// anything bigger is refused before JSON.parse touches it.
export const MAX_BODY_BYTES = 32 * 1024;

/**
 * Reads the request body as text while enforcing [maxBytes] on the wire bytes
 * actually received — Content-Length is advisory (absent on chunked uploads, and
 * a client may understate it), so the cap has to be applied while streaming.
 * @returns {Promise<string|null>} null once the body exceeds the cap.
 */
export async function readBodyCapped(request, maxBytes) {
  if (!request.body) return '';
  const reader = request.body.getReader();
  const chunks = [];
  let received = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.byteLength;
      if (received > maxBytes) return null;
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const joined = new Uint8Array(received);
  let offset = 0;
  for (const chunk of chunks) {
    joined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(joined);
}

/** UTF-8 size of [text]; every code unit is at least one byte, so the cheap
 *  length check short-circuits the encode for grossly oversized input. */
function utf8ByteLength(text) {
  return new TextEncoder().encode(text).byteLength;
}

/**
 * @param {string} bodyText raw request body
 * @param {Set<string>} catalogPackages packages already in the published catalog
 * @returns {{status: 204|400|413, accepted: string[]}} Invalid input rejects the
 *   whole request (nothing from a partially-invalid batch is stored); packages
 *   the catalog already lists are silently dropped, and the response stays 204
 *   either way so the endpoint cannot be used to probe the catalog.
 */
export function parseHintRequest(bodyText, catalogPackages) {
  if (bodyText.length > MAX_BODY_BYTES || utf8ByteLength(bodyText) > MAX_BODY_BYTES) {
    return { status: 413, accepted: [] };
  }
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
