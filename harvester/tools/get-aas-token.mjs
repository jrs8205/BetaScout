// Exchanges a short-lived Google `oauth_token` (from the EmbeddedSetup browser
// flow) for a long-lived AAS master token that gplayapi uses.
//
// Usage:
//   EMAIL=acc@gmail.com OAUTH_TOKEN='oauth2_4/...' node tools/get-aas-token.mjs
//
// The oauth_token is single-use and expires within minutes — run this right
// after copying it. The printed aas_et/ token is a MASTER credential: treat it
// exactly like a password (store as a secret, never commit, use a throwaway
// account). This uses Google's unofficial auth endpoint (against Google ToS).

import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const email = process.env.EMAIL ?? process.argv[2];
const oauthToken = process.env.OAUTH_TOKEN ?? process.argv[3];

if (!email || !oauthToken) {
  console.error("Usage: EMAIL=acc@gmail.com OAUTH_TOKEN='oauth2_4/...' node tools/get-aas-token.mjs");
  process.exit(1);
}

const androidId = randomBytes(8).toString('hex'); // 16 hex chars

const body = new URLSearchParams({
  accountType: 'HOSTED_OR_GOOGLE',
  Email: email,
  has_permission: '1',
  add_account: '1',
  ACCESS_TOKEN: '1',
  Token: oauthToken,
  service: 'ac2dm',
  source: 'android',
  androidId,
  device_country: 'us',
  operatorCountry: 'us',
  lang: 'en',
  sdk_version: '17',
  google_play_services_version: '240913000',
  client_sig: '38918a453d07199354f8b19af05ec6562ced5788',
  callerSig: '38918a453d07199354f8b19af05ec6562ced5788',
  droidguard_results: 'dummy123',
});

const response = await fetch('https://android.clients.google.com/auth', {
  method: 'POST',
  headers: {
    'User-Agent': 'GoogleAuth/1.4 (generic_x86 KOT49H); gzip',
    'Content-Type': 'application/x-www-form-urlencoded',
    app: 'com.google.android.gms',
  },
  body,
});

const text = await response.text();
const fields = Object.fromEntries(
  text
    .trim()
    .split('\n')
    .map((line) => {
      const eq = line.indexOf('=');
      return eq === -1 ? [line, ''] : [line.slice(0, eq), line.slice(eq + 1)];
    }),
);

if (fields.Token && fields.Token.startsWith('aas_et/')) {
  const out = new URL('../.gplay.local', import.meta.url);
  writeFileSync(out, `EMAIL=${email}\nAAS_TOKEN=${fields.Token}\nANDROID_ID=${androidId}\n`);
  console.log('Success. Saved to harvester/.gplay.local (gitignored). The token is NOT printed here.');
} else {
  console.error('Exchange failed. Full response:\n' + text);
  process.exit(1);
}
