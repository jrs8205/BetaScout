# BetaScout harvester

Builds the BetaScout beta catalog (`catalog.json`, see [SCHEMA.md](SCHEMA.md))
from public sources. This is a **separate program** from the Android app: it
communicates with the app only through the published catalog data, so its
licence does not affect the app.

- **Licence:** GPL-3.0-or-later. The app stays Apache-2.0; the data/network
  boundary keeps the copyleft on this component. GPL is chosen because the
  planned gplayapi integration (below) is GPL-3.0.
- **Runtime:** Node.js 22+, zero dependencies. Tests use the built-in runner.

```bash
npm test                 # unit tests (pure parsing, offline fixtures)
MAX_APPS=5 npm run harvest   # live run against APKMirror, writes catalog.json
```

## What it does today

1. Reads APKMirror's public release RSS feed.
2. Keeps beta/alpha uploads (detected from the title).
3. Resolves each app's Play Store package name from its app page.
4. Writes a v2 catalog with `liveStatus: UNKNOWN` (existence only).

It reads **metadata only** — no APK downloads or redistribution — and spaces
requests by the crawl delay APKMirror's `robots.txt` asks for (3s), capping the
number of app pages per run (anything skipped is logged, never silently dropped).

## Caveats (read before running at scale)

- APKMirror sits behind Cloudflare and returns 403 intermittently; runs tolerate
  this and continue.
- APKMirror's Terms of Use restrict automated access — review them before relying
  on this in production.
- "Beta build exists on APKMirror" is a proxy for "has a beta program", not proof
  the Play testing program is currently joinable.

## Roadmap

- **gplayapi enrichment** — query Google Play's internal API (anonymous account)
  for authoritative `testingProgram` existence, keyed by package name.
- **opt-in open/full checker** — load `play.google.com/apps/testing/<pkg>` with an
  authenticated bot account to set `liveStatus` OPEN/FULL/CLOSED. This is the
  fragile, ToS-sensitive core; it needs a throwaway Google account.
- **publish** — write the merged catalog to Cloudflare KV; a Worker serves it to
  the app. Run on a schedule via GitHub Actions.
