# UI remodel — design

Status: approved 2026-07-12 (style, structure and per-screen choices made by the
user against browser mockups; design summary approved in conversation).

## Goal

Replace the default-Material look with a distinctive, coherent visual identity
("Expressive Material": brand green, rounded tonal cards, pill tabs,
plain-language status copy) and restructure the main, account and detail
screens around the user's actual task — finding betas that can be joined —
while making the scanning system (including the invisible background scan)
legible in the UI.

## Non-goals

- No changes to data, Room schema, scanning logic, workers or scheduling.
- No new features beyond presentation (the "Open betas" rail derives from
  existing observations; no new persistence).
- No Play-facing assets (icons, store screenshots).

## Background

Current UI is bare Material 3 with dynamic (wallpaper) color on Android 12+,
default shapes/typography and cramped spacing (the search field touches the tab
indicator). Field feedback (2026-07-12): tab counts landed well; account-screen
copy is confusing — "manual" scan type implies an automatic one exists, and the
6-hourly background scan is never mentioned anywhere in the UI.

## Decisions (user-selected from mockups)

1. **Style:** "Expressive Material" — brand green accent, rounded tonal cards
   (16 dp+), pill-shaped tabs/chips, plain-language statuses
   ("Beta open — you can join").
2. **Main screen:** scan-status card on top + "Open betas" rail + the familiar
   three tabs with counts.
3. **Account screen:** three cards (account / last scan + background-scan
   explainer / full re-scan) with rewritten copy.
4. **Detail screen:** status-hero layout — a large status-colored card with the
   primary action first.
5. **Colors:** brand palette by default on all devices; dynamic Material You
   as an opt-in setting on the account screen.
6. **Navigation:** three-item bottom bar — Apps / Watchlist / Account.

## Component 1: Theme and design system

`ui/theme/Theme.kt` (+ new `Color.kt`, `Shape.kt` if useful):

- Brand color schemes, dark and light, built around BetaScout green
  (light primary `#0B6E4F`, dark primary `#6CCBA9`), with tonal surface
  containers for cards (dark: background `#141218`, cards `#211F26`).
- Shapes: rounded scale (extraSmall 8, small 12, medium 16, large 20,
  extraLarge 28 dp). Typography: defaults plus a heavy (ExtraBold) title style
  for the app wordmark and card heroes.
- Dynamic color becomes **opt-in**: `SettingsRepository.useDynamicColor`
  (DataStore `Flow<Boolean>`, default false) + setter; `BetaScoutTheme`
  takes `useDynamicColor: Boolean` and only then applies
  `dynamicDark/LightColorScheme` (API 31+). MainViewModel exposes the flag.

## Component 2: Shared scan status (main + account screens)

New `ui/scan/ScanStatusViewModel` (Hilt) — extracted from today's
AccountViewModel scan logic so both screens can host it:

- State: `signedOut` | `idle(lastScan)` | `scanning(progress)` | `cancelling` |
  `error(message)` + `needsReLogin`; derived from `settings.playSession`,
  `settings.lastScan`, the manual work's WorkInfo flow and
  `repository.scanRunning` (same combine semantics as the current
  AccountViewModel — that mapping moves, it is not rewritten).
- Actions: `scanNow()` (incremental), `fullScan()` (force), `cancel()`.
- A pure, unit-testable mapper produces the card's display model
  (title/subtitle/button/progress) from those inputs.
- AccountViewModel keeps only session concerns: login capture, sign-out,
  needs-re-login display.

**Scan-status card** (main screen, also reused as the account screen's scan
card body): idle → "Scanned today 10:14 · in 40 betas · background scan every
6 h" + **Scan** button; scanning → linear progress + per-app label + **Cancel**;
cancelling → "Cancelling…"; signed-out → sign-in pitch + **Sign in** button
navigating to the account tab.

## Component 3: Main screen (structure 3)

`AppListScreen` rebuilt:

- Header: wordmark title only (account icon leaves the top bar — Account is a
  bottom tab now; the manual refresh icon is dropped, resume-refresh covers it).
- Scan-status card (Component 2).
- **"Open betas" rail**: horizontal row of chips for apps whose observation is
  OPEN and whose tab classification is AVAILABLE, newest `checkedAt` first,
  capped at 10; hidden when empty or while a search query is active. Selector
  `openBetas(rows): List<AppBetaOverview>` lives in `AppListFilter.kt` with
  unit tests. Chip tap opens the app detail.
- Pill-style tabs with counts (existing counts logic), search field restyled as
  a rounded pill with breathing room (fixes the cramped spacing), system-apps
  filter chip as today.
- App rows as tonal cards: icon, name, plain-language status line, status
  badge. Status line/badge come from a pure mapper
  `statusLineRes(row)` / `statusBadge(row)` in `AppListFilter.kt` (unit
  tested): JOINED → "You're in the beta" / badge "Joined ✓"; AVAILABLE+OPEN →
  "Beta open — you can join" / "Open"; AVAILABLE+FULL → "Beta full" / "Full";
  AVAILABLE+CLOSED → "Beta closed" / "Closed"; AVAILABLE otherwise → "Has a
  beta program" / "Beta"; NONE → "No known beta" / no badge. A watched app
  shows a star in the row.

## Component 4: Account screen (cards + copy)

Three cards, in order:

1. **Account**: avatar initial, email, "Google account connected", sign-out.
   Signed out: the sign-in pitch + button (login WebView flow unchanged).
2. **Scans**: "Last scan: today 10:14 · started by you / background scan" (the
   legacy no-type default label stays MANUAL, worded "started by you"),
   checked/failed counts with the retry note, then a divider and the
   background-scan explainer: "Automatic background scan — re-checks stale
   statuses every 6 hours while you're signed in." (no next-run estimate — 
   WorkManager gives no reliable time).
3. **Full re-scan**: title, honest duration copy ("checks every app again —
   can take 10–15 minutes") and the button; disabled while scanning.
4. **Appearance** (small): "Use device colors (Material You)" switch bound to
   `useDynamicColor` (only shown on API 31+).

The scan-in-progress/cancelling/error presentation on this screen comes from
Component 2's shared card. Terminology: Finnish copy uses the "skannaus" family
everywhere (the user's vocabulary); explanatory subtitles do the clarifying.

## Component 5: Detail screen (status hero)

`AppDetailScreen` rebuilt:

- Centered app header (icon, name, package, version).
- **Hero card**, colored by state:
  - OPEN + not joined → green container, "Beta is open", subtitle "You can
    join as a tester now · checked <time>", primary button **Join the beta**
    (existing Custom Tab flow).
  - FULL / CLOSED → amber/neutral container, "Beta is full/closed", subtitle
    suggests watching, primary button **Watch this beta** (enables watch).
  - JOINED → green-outline container, "You're in the beta ✓", button
    **Open beta page**.
  - NO_PROGRAM / UNKNOWN → neutral container, "No known beta program" /
    "Beta status unknown", button **Open testing page**.
  - `lastError` renders as a small "Latest re-check failed: …" line.
- **Watch card**: toggle + reminder-interval row (existing logic).
- **Info card**: membership line, beta-page link, own marking (existing
  selector), note (existing editor).

## Component 6: Navigation, watchlist, onboarding

- Bottom bar gains **Account** (icon AccountCircle) as the third destination;
  AccountScreen drops its back-arrow top bar when shown as a tab.
- WatchlistScreen and OnboardingScreen restyle with the same row/card
  components and theme; no behavior change.

## Strings

All new/changed copy in both `values/` and `values-fi/`. Key rewrites:
scan-type labels ("started by you" / "background scan"), background-scan
explainer, full re-scan copy, plain-language status lines and badges,
"Open betas" rail title. Resource names in English as always.

## Error handling

No new failure paths: scan errors, needs-re-login and scan-in-progress
continue to surface through the shared scan card exactly as AccountViewModel
does today.

## Testing

- Unit: `openBetas` selector (ordering, cap, hidden-when-searching handled in
  ViewModel), status line/badge mappers, scan-card display-model mapper,
  `useDynamicColor` default+setter. Existing 141 tests stay green.
- Lint + assembleDebug per task; device validation on Pixel 8a (visual pass of
  all five screens, scan/cancel flows, theme toggle) before v0.5.0.

## Rollout

Feature branch `feat/ui-remodel`, task-sized commits, merge after device
validation. Ships in v0.5.0 together with the incremental-scan work.
