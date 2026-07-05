// Bridges to the JVM gplayapi tool (harvester/gplay): runs it once for a batch
// of packages and parses its tab-separated output into result objects.

import { execSync } from 'node:child_process';

/** Parses one output line from the JVM tool. Returns null for non-data lines. */
export function parseGplayLine(rawLine) {
  const line = rawLine.replace(/\r$/, '');
  const parts = line.split('\t');
  if (parts.length < 2) return null;
  const packageName = parts[0].trim();
  if (!packageName) return null;

  if (parts[1].startsWith('ERROR ')) {
    return { packageName, error: parts[1].slice('ERROR '.length).trim() };
  }

  const fields = {};
  for (const part of parts.slice(1)) {
    const eq = part.indexOf('=');
    if (eq > 0) fields[part.slice(0, eq)] = part.slice(eq + 1);
  }
  return {
    packageName,
    available: fields.available === 'true' ? true : fields.available === 'false' ? false : undefined,
    subscribed: fields.subscribed === 'true',
    versionCode: fields.versionCode ? Number(fields.versionCode) : undefined,
    name: fields.name,
  };
}

/**
 * Runs the JVM gplayapi tool for the given packages and returns parsed results.
 * Requires a built credentials file and a JDK; throws if the tool fails.
 */
export function runGplay(packages, { gradlew, projectRoot, javaHome, credsPath = '../.gplay.local' }) {
  if (packages.length === 0) return [];
  // execSync (shell) so a Windows gradlew.bat works too; args are quoted because
  // paths and the package list contain spaces. Gradle noise goes to our stderr.
  const argString = [credsPath, ...packages].join(' ');
  const command =
    `"${gradlew}" -p "${projectRoot}/harvester/gplay" run --console=plain --quiet "--args=${argString}"`;
  const output = execSync(command, {
    env: javaHome ? { ...process.env, JAVA_HOME: javaHome } : process.env,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  return output.split(/\r?\n/).map(parseGplayLine).filter(Boolean);
}
