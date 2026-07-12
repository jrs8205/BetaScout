# BetaScout Privacy Policy

*Last updated: 2026-07-12 · applies to BetaScout v0.6.0 and later*

BetaScout is a free, open-source Android app (Apache 2.0) that lists your
installed apps, shows which ones have a Google Play testing (beta) program, and
reminds you to re-check the ones you watch. This policy describes exactly what
data the app handles. The source code is public, so every claim here can be
verified: <https://github.com/jrs8205/BetaScout>.

## The short version

- Your app list, your beta memberships and your markings **stay on your
  device**. There is no telemetry, no analytics, no ads and no tracking of any
  kind.
- The network is used to download the shared beta catalog and — only after you
  sign in — to read *your own* Google Play testing pages.
- The only thing that can ever leave your device beyond that is an **optional,
  off-by-default** contribution of bare package names to the shared catalog.

## Data stored on your device

- **Installed-app list** — read via Android's package manager to build the main
  list; stored in a local database.
- **Beta observations** — what your own testing pages showed (program status,
  membership, check time); stored locally, per signed-in account, and deleted
  when you sign out.
- **Your markings** — watch flags, reminder intervals, notes; stored locally.
- **Google web session** — if you sign in, the session cookie is stored
  encrypted at rest (Android Keystore) and is used solely to fetch
  `play.google.com/apps/testing/…` pages for apps installed on your device.
  It is never transmitted anywhere except to Google as part of those page
  loads. Signing out deletes the session, the observations and the WebView
  cookies. Local app data is excluded from Android cloud backups.

## Network use

1. **Beta catalog download** (always): the app fetches the shared catalog from
   `betascout-catalog.jarsi.workers.dev`. This is a plain GET request; no
   identifiers, cookies or device data are sent with it.
2. **Your own testing pages** (only when signed in): the app loads
   `play.google.com/apps/testing/<package>` pages with your session, throttled
   to one request every few seconds, and stops immediately on rate-limit
   responses. Automated reading of these pages is a gray area in Google's terms
   of service; the app is deliberately gentle, but a small risk to the Google
   account cannot be ruled out. Signing in is optional.
3. **Discovery sharing** (opt-in, default **off**): if you enable "Share
   discoveries", the app uploads the bare package names of apps whose scans
   found a testing program. Nothing else is ever included — no account, no
   email, no memberships, no device or install identifiers, no IP logging on
   the server. Uploaded names are only candidates: they enter the shared
   catalog after a server-side job independently verifies them against Google
   Play. You can turn sharing off at any time in settings.

## What the server stores

The catalog service stores the published catalog and, for opted-in
contributions, the package name with a first-seen timestamp and a counter.
It stores no submitter information of any kind.

## Permissions

| Permission | Used for |
|---|---|
| `QUERY_ALL_PACKAGES` | Listing your installed apps — the app's core purpose |
| `POST_NOTIFICATIONS` | Watch reminders and beta-opening alerts |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Catalog download and reading your own testing pages |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Letting a manual scan finish if you leave the app |

## Children

BetaScout is a developer/enthusiast utility and is not directed at children.

## Changes and contact

Changes to this policy are made in the public repository, where the full
history is visible. Questions and reports:
<https://github.com/jrs8205/BetaScout/issues>.
