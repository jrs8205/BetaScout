# Design: authenticated testing-page scraping (hybrid catalog + on-device scrape)

Date: 2026-07-06
Status: approved, implementation starting

## Goal

Detect, per user, which betas they are enrolled in and whether each testing program
is open / full / closed — reliably and without the version heuristic (proven wrong in
v0.3.2) and without gplayapi (an aggressive unofficial Play API that carries account-ban
risk). Replace it with the mechanism the reference app (β-Maniac) actually uses: fetch the
user's own authenticated Google Play testing page per installed app and parse its HTML.

This was validated against the confirmed β-Maniac 0.9.4 base APK: it builds
`play.google.com/apps/testing/<pkg>` URLs, loads them with the user's logged-in Google
session cookies (WebView + CookieManager), and parses the returned HTML with Jsoup for the
markers `joinForm` / `leaveForm` / `gaia_loginform` / `greenBox` plus the status vocabulary
`open` / `FULL` / `CLOSED`. It uses no Play private API (no `oauth2:...googleplay`,
`androidmarket`, `/fdfe/`, `X-DFE`).

## Account-safety constraint (primary)

No feature may materially risk the user's Google account. Concretely:

- **Removed entirely:** `gplayapi` (impersonates a Play client via a private protocol with
  the user's AAS token — highest ban risk) and any auto-join form POST (acting on the
  account automatically).
- **Kept, but engineered to be gentle:** authenticated scraping of the testing page is
  still technically automated access to Google (against ToS), but it is low-aggression and
  empirically tolerated (β-Maniac has run this way for years). We minimise request volume
  with a staleness TTL, a per-run cap, a crawl delay, and error backoff.
- **User-driven only:** joining a beta opens the real testing page in a Custom Tab; the user
  completes the opt-in themselves.

Honest note: this is *small* account risk, not zero. The zero-risk alternative (all
automation server-side, device does only user-initiated browsing) was considered and
rejected by the user in favour of β-Maniac-level automatic membership detection.

## Scope of scraping

All non-system installed apps are eligible (β-Maniac-style device discovery), NOT only
catalog-known apps. Request volume is bounded not by scope but by the TTL + cap + crawl
delay below, so the steady-state load stays gentle even with ~60 installed apps.

## Hybrid split (how this differs from β-Maniac)

- **On-device scrape** → per-user live status + membership for **installed** apps.
- **Server-side catalog** (existing harvester + Cloudflare Worker, runs on a throwaway
  account, never the user's) → discovery of betas for apps the user does **not** have
  installed, plus fallback/cross-reference. β-Maniac is purely installed-driven; we keep the
  catalog as a second, safe discovery source.

## Architecture / components

Each unit has one responsibility and is testable in isolation.

| Component | Responsibility | Depends on |
|---|---|---|
| `SessionCapture` (WebView) | User Google login → extract Play session cookies | WebView, CookieManager |
| `SessionStore` | Persist session cookies (replaces `gplayCredential`) | DataStore |
| `TestingPageSource` (interface) | Fetch `apps/testing/<pkg>` HTML with the session. Impl `HttpTestingPageSource` (HTTP GET + cookies). Swappable for a WebView-DOM impl if Google JS-renders the page | HttpURLConnection/OkHttp |
| `TestingPageParser` (pure) | Parse HTML → `{membership, liveStatus, needsLogin}` from joinForm/leaveForm/gaia_loginform/greenBox/full markers | Jsoup (MIT) |
| `BetaStatusScraper` (implements the new source role, replacing `MembershipSource`) | Per package: fetch → parse → result; detect session expiry | above |
| `ScanScheduler` + `ScanWorker` | Pick due packages (TTL), throttle (crawl delay, per-run cap), write results to Room, back off on errors | WorkManager |
| `CatalogProvider` (unchanged) | Remote catalog: discovery of non-installed betas + fallback | HTTP |

### Data flow

1. **Login:** user taps sign-in → WebView loads Google login → on success extract
   `play.google.com` / `google.com` session cookies → `SessionStore`.
2. **Scan (periodic + on-demand):** `ScanWorker` → due non-system installed packages →
   `HttpTestingPageSource.fetch(pkg, session)` → `TestingPageParser.parse` → write
   `liveStatus` + observed membership + `checkedAt` to Room → UI updates via Flow.
3. **Session expired:** parser returns `needsLogin=true` → worker stops, sets a re-login
   flag → account screen prompts re-login.
4. **Discovery hybrid:** catalog supplies betas for non-installed apps; installed apps are
   scraped directly on device.
5. **Join:** from app detail, Custom Tab to the testing page; user opts in themselves.

## Data model

Separate observed data from user-declared data and catalog data.

- `LiveBetaStatus { UNKNOWN, OPEN, FULL, CLOSED, NO_PROGRAM }` (add `NO_PROGRAM`).
- `ObservedMembership { UNKNOWN, JOINED, NOT_JOINED }` (new).
- `BetaObservation(packageName, liveStatus, observedMembership, checkedAt, lastError?)` (new
  domain type + Room table `beta_observations`, PK packageName).
- `AppBetaOverview` gains `observation: BetaObservation?`.
- `observeApps()` combines four sources: installed_apps + beta_programs (catalog) +
  user_beta_status (manual mark) + beta_observations (scrape).
- Membership tabs ("Not joined" / "Joined") prefer observed membership, falling back to the
  user's manual mark.

### Room migration v2 → v3

`MIGRATION_2_3` creates `beta_observations`. `user_beta_status` and `beta_programs` are
untouched, so no user data is lost and catalog refresh never clobbers observations. v0.3.2
(schema v2) is live on user devices — the migration must be additive and tested on the
upgrade path.

## Scheduling, throttling, TTL (the account-safety engineering)

- New periodic `ScanWorker`, ~12 h interval, `KEEP`; runs only when signed in and network
  is connected.
- **Staleness TTL** — a package is due only if `now - checkedAt >= ttl(status)`:
  - OPEN / FULL → 24 h (can change soon)
  - CLOSED → 3–7 days
  - NO_PROGRAM → 7–14 days (still re-checked occasionally = discovery)
  - never checked (new install) → due immediately, subject to the per-run cap
- **Per-run cap** (~30 packages) + **crawl delay** (2–4 s, spread) between requests; overflow
  rolls to the next run. First-ever run spreads across several runs.
- **Backoff:** on 429/403 or session-expired, stop the run early and back off; if the session
  is invalid, raise the re-login flag.
- The existing reminder path becomes real for "notify when it opens" (#5): a reminder fires
  when a watched app's observed `liveStatus` transitions from not-open to OPEN.

## UI

- **Account screen:** same "Sign in with Google", now storing session cookies; shows signed-in
  email + "Scan now" + sign out; re-login prompt when the session expired.
- **App list:** membership tabs driven by observed membership; status chips show
  OPEN / FULL / CLOSED.
- **App detail:** live status + observed membership + "Join" (Custom Tab) when open and not
  joined + "last checked" timestamp.
- All new strings go to both `values/` (English default) and `values-fi/` (Finnish).

## Testing

- `TestingPageParser`: primary logic → heavy unit coverage against saved HTML fixtures
  (open / full / closed / joined / not-joined / logged-out). Fixtures captured from a real
  device during the smoke test.
- TTL selection, throttle/cap: pure functions → TDD.
- `BetaStatusScraper`: tested with a fake `TestingPageSource`.
- Repository: fake DAOs.
- Room `MIGRATION_2_3`: migration test on the v2 → v3 upgrade path.
- Device smoke test on Pixel 8a with the **signed, minified release** build (the v0.2.0
  lesson): sign in → scan → verify observed membership matches ground truth
  (WhatsApp / Todoist).

## Licensing

Removing gplayapi removes the only GPL-3.0 dependency → revert `LICENSE` to Apache-2.0 and
update the README. Jsoup is MIT, compatible.

## Fragility / known risks

- The testing page must be server-rendered enough for Jsoup to see the markers in the raw
  HTML (β-Maniac relies on this). If Google JS-renders it, swap `HttpTestingPageSource` for a
  WebView-DOM source behind the same interface. Capture a real fixture early to validate.
- Google web-session cookies expire → periodic re-login (acceptable; the re-login flag
  handles it).
- Account risk is small but non-zero; mitigated by TTL + cap + crawl delay + backoff.

## Out of scope

- Auto-join (form POST on the user's behalf) — removed for account safety.
- Leaving a beta from inside the app — the testing page (Custom Tab) handles it.
- Changes to the harvester / Cloudflare backend — unchanged, server-side only.
