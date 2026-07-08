# BetaScout

[![CI](https://github.com/jrs8205/BetaScout/actions/workflows/ci.yml/badge.svg)](https://github.com/jrs8205/BetaScout/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/jrs8205/BetaScout/total?label=Downloads)](https://github.com/jrs8205/BetaScout/releases)

**Find and track Google Play beta programs for the apps on your device.**

BetaScout scans your installed apps, shows which ones are known to have a Google Play
open-testing (beta) program, opens the opt-in page for you, and reminds you to re-check
the ones you are watching — so you catch a free slot when one opens up.

The app is fully bilingual: **English** and **Finnish** (suomi).

## What it does

- 📋 **Scans installed apps** — name, icon, version and install source, entirely on-device
- 🔍 **Knows about beta programs** — a curated, bundled database marks apps with known
  open-testing programs ("usually open" / "usually full")
- 🔗 **One-tap deep links** — open an app's `play.google.com/apps/testing/…` opt-in page
  or its Play Store page directly
- ✍️ **Track your own status** — mark each app as joined / not joined / full / no program,
  and keep private notes
- ⏰ **Watchlist with reminders** — watch apps you care about and get a notification at
  your chosen interval (7/14/30 days) reminding you to re-check; tapping it opens the
  beta page

## What it does *not* do

Honesty first:

- It **never joins a beta program on your behalf.** Joining requires pressing the *Join*
  button on Google Play yourself.
- **Without signing in it cannot detect whether a program is open or full.** That
  information is only visible to a signed-in Google account on the testing page. If you
  sign in inside the app, BetaScout reads *your own* testing pages to detect your
  memberships and each program's open/full status; otherwise it just takes you there.
- The bundled/remote catalog of *known* beta programs is small and curated (a few dozen
  apps). Most detection therefore comes from the signed-in scan of your own testing
  pages, not from the catalog.
- No accessibility-service automation, no APK sideloading, no Play Store bypassing.

## Screenshots

| Onboarding | App details | Watchlist |
|:---:|:---:|:---:|
| ![Onboarding](docs/screenshots/onboarding.png) | ![App details](docs/screenshots/app-detail.png) | ![Watchlist](docs/screenshots/watchlist.png) |

## Installation

BetaScout is distributed as an APK via [GitHub Releases](https://github.com/jrs8205/BetaScout/releases)
— it is **not** on the Play Store.

1. Download the latest APK from the Releases page.
2. Allow installs from unknown sources when Android asks.
3. Done. Listing and tracking work offline; the beta-catalog refresh and the
   signed-in status scan need a network connection.

> **Why not the Play Store?** BetaScout uses the `QUERY_ALL_PACKAGES` permission to list
> everything installed on your device. That permission is heavily restricted on the Play
> Store, but it is exactly what makes the app useful — so it lives here instead.

## Permissions

| Permission | Why |
|---|---|
| `QUERY_ALL_PACKAGES` | List your installed apps — the whole point of the app |
| `POST_NOTIFICATIONS` | Watchlist reminders and beta-opening alerts (asked on first launch, Android 13+) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Fetch the beta catalog, and read your own Play testing pages once you sign in |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Let a manual "Scan now" finish even if you leave the app |

The network is used for exactly two things: downloading the beta-program catalog, and —
only after you sign in — fetching `play.google.com/apps/testing/…` pages with your own
session to read your memberships. There is no telemetry and no analytics; your app list
and markings never leave the device.

## Building from source

Requirements: JDK 17+ and the Android SDK (or just Android Studio).

```bash
git clone https://github.com/jrs8205/BetaScout.git
cd BetaScout
./gradlew assembleDebug   # APK at app/build/outputs/apk/debug/
./gradlew test            # unit tests
```

## Tech stack

Kotlin · Jetpack Compose (Material 3) · MVVM + Repository · Room · DataStore ·
WorkManager · Hilt · Navigation-Compose · kotlinx-serialization

- minSdk 26 (Android 8.0) · targetSdk 36
- Core logic is unit-tested (TDD): link building, package scanning, seed parsing,
  repository behavior, list filtering and reminder scheduling policy

## Roadmap

- [x] Opt-in, signed-in check of your own testing pages (membership + open/full status)
- [ ] Community-sourced beta-program database (the current catalog is small and curated)

Suggestions and bug reports are welcome in [Issues](https://github.com/jrs8205/BetaScout/issues).

## License

The app is under the [Apache License 2.0](LICENSE). Beta membership and open/full/closed
status are read by signing into your own Google account and parsing your own testing pages
with [Jsoup](https://jsoup.org/) (MIT); no Google Play private API is used. The backend
harvester (`harvester/`) is GPL-3.0 and runs server-side only.
