// Verifies the saved AAS credential works with Google Play by exchanging it for
// a Play-scoped auth token. Reads harvester/.gplay.local. Prints only pass/fail,
// never the tokens.

import { readFileSync } from 'node:fs';

function readEnvFile(url) {
  return Object.fromEntries(
    readFileSync(url, 'utf8')
      .trim()
      .split('\n')
      .map((line) => {
        const eq = line.indexOf('=');
        return [line.slice(0, eq), line.slice(eq + 1)];
      }),
  );
}

const creds = readEnvFile(new URL('../.gplay.local', import.meta.url));

const body = new URLSearchParams({
  accountType: 'HOSTED_OR_GOOGLE',
  Email: creds.EMAIL,
  has_permission: '1',
  EncryptedPasswd: creds.AAS_TOKEN,
  service: 'oauth2:https://www.googleapis.com/auth/googleplay',
  source: 'android',
  androidId: creds.ANDROID_ID,
  app: 'com.android.vending',
  client_sig: '38918a453d07199354f8b19af05ec6562ced5788',
  device_country: 'us',
  operatorCountry: 'us',
  lang: 'en',
  sdk_version: '17',
  google_play_services_version: '240913000',
});

const response = await fetch('https://android.clients.google.com/auth', {
  method: 'POST',
  headers: {
    'User-Agent': 'GoogleAuth/1.4 (generic_x86 KOT49H); gzip',
    'Content-Type': 'application/x-www-form-urlencoded',
    app: 'com.android.vending',
  },
  body,
});

const text = await response.text();
const fields = Object.fromEntries(
  text.trim().split('\n').map((line) => {
    const eq = line.indexOf('=');
    return eq === -1 ? [line, ''] : [line.slice(0, eq), line.slice(eq + 1)];
  }),
);

if (fields.Auth) {
  console.log('OK — obtained a Play-scoped auth token. The account credential works with Google Play.');
} else {
  console.error('FAILED. Response (may indicate an expired or bad credential):');
  console.error(text.replace(/(Auth|Token)=\S+/g, '$1=[REDACTED]'));
  process.exit(1);
}
