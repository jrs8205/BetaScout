# UI Remodel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.
> Executed inline by the session that wrote it (full context); tasks are
> work-list granular, the spec (`2026-07-12-ui-remodel-design.md`) is the
> authority for all visual/copy detail.

**Goal:** Implement the approved UI remodel: brand theme, scan-status card on the main screen, "Open betas" rail, plain-language statuses, account-screen cards with honest copy, detail-screen status hero, 3-tab bottom navigation.

**Architecture:** Presentation-only. New pure mappers in `AppListFilter.kt` and a shared `ScanStatusViewModel` carry the only logic; everything else is Compose restyling. No data/worker changes except `SettingsRepository.useDynamicColor`.

**Tech Stack:** Kotlin, Compose M3, Hilt, DataStore, JUnit4.

## Global Constraints

- Repo 100% English; UI strings in `values/` + `values-fi/`; no attribution trailers.
- TDD for every pure mapper/selector; `./gradlew :app:testDebugUnitTest` green per task (baseline 141); `:app:lintDebug` + `:app:assembleDebug` at the end of UI tasks.
- Commit per task on `feat/ui-remodel`.

---

### Task 1: Theme + dynamic-color setting
- Modify: `ui/theme/Theme.kt` — brand dark/light `ColorScheme` (dark primary `#6CCBA9` on `#00382A`, containers `#211F26`/bg `#141218`; light primary `#0B6E4F`), `Shapes(8/12/16/20/28.dp)`, wordmark/hero typography (ExtraBold titleLarge variant); `BetaScoutTheme(useDynamicColor: Boolean, darkTheme, content)` — dynamic schemes only when the flag is true and API ≥ 31.
- Modify: `data/settings/SettingsRepository.kt` — `useDynamicColor: Flow<Boolean>` (default false) + `setUseDynamicColor(Boolean)`; test in a new `SettingsRepository`-level unit test only if one exists for other prefs, otherwise verified via ViewModel wiring + device.
- Modify: `MainViewModel.kt` (expose flag as StateFlow, default false), `MainActivity.kt` (pass to theme).
- Verify: suite + assembleDebug. Commit: `Add brand theme with opt-in dynamic color`.

### Task 2: Status mappers + open-betas selector (TDD)
- Modify: `ui/applist/AppListFilter.kt` — `statusLineRes(row): Int`, `statusBadge(row): StatusBadge?` (enum OPEN/FULL/CLOSED/JOINED/BETA with labelRes), `openBetas(rows): List<AppBetaOverview>` (observation OPEN + membership AVAILABLE, newest checkedAt first, cap 10).
- Test: `AppListFilterTest.kt` — every membership/liveStatus combination for line+badge; openBetas ordering/cap/filtering.
- Strings: status lines + badge labels (en+fi).
- Commit: `Add plain-language status mappers and open-betas selector`.

### Task 3: Shared ScanStatusViewModel (move, not rewrite)
- Create: `ui/scan/ScanStatusViewModel.kt` — move AccountViewModel's scan concerns: workBusy + `repository.scanRunning` combine, WorkInfo mapping (progress/error/needs-login output), `scanNow()`, `fullScan()`, `cancel()`; expose `ScanUiState(signedIn, busy, cancelling, progress, lastScan, error, needsLogin)`.
- Create: `ui/scan/ScanStatusCard.kt` — composable rendering idle/scanning/cancelling/signed-out/error per spec Component 2; callbacks for scan/cancel/sign-in.
- Modify: `ui/account/AccountViewModel.kt` — retains session/login/sign-out + needsReLogin only.
- Strings: card copy (en+fi). Verify suite (existing tests unaffected — no unit tests cover the ViewModels). Commit: `Extract shared scan status state and card`.

### Task 4: Main screen rebuild
- Modify: `ui/applist/AppListScreen.kt` — wordmark header (drop account/refresh actions), ScanStatusCard, open-betas chip rail (hidden when empty or query non-blank), pill tabs w/counts, roomy pill search, card rows w/ status line + badge + watch star.
- Modify: `ui/applist/AppListViewModel.kt` — expose `openBetas` from the filtered rows.
- Verify: suite + lint + assembleDebug + emulator/device glance. Commit: `Rebuild the main screen around the scan card and open-betas rail`.

### Task 5: Account screen cards
- Modify: `ui/account/AccountScreen.kt` — account card (avatar initial/email/sign-out or sign-in pitch), scan card (ScanStatusCard + last-scan detail + background-scan explainer), full re-scan card, appearance card (dynamic color switch, API 31+ only, bound via MainViewModel or new setting accessor in AccountViewModel).
- Strings: rewritten copy per spec Component 4 (en+fi) — "started by you"/"background scan", explainer, durations.
- Commit: `Restructure the account screen into cards with honest scan copy`.

### Task 6: Detail screen status hero
- Modify: `ui/appdetail/AppDetailScreen.kt` (+ ViewModel only if an action is missing) — centered header, hero card per state (OPEN/FULL+CLOSED/JOINED/NO_PROGRAM/UNKNOWN) with primary action (join / watch / open page), lastError line, watch card, info card (membership, link, marking, note).
- Strings en+fi. Commit: `Rebuild the app detail screen around a status hero`.

### Task 7: Navigation + watchlist + onboarding
- Modify: `ui/BetaScoutNavHost.kt` — Account as third bottom destination; account back-arrow removed when top-level.
- Modify: `ui/watchlist/WatchlistScreen.kt`, `ui/onboarding/OnboardingScreen.kt` — restyle with shared components.
- Commit: `Move account to the bottom bar and restyle watchlist and onboarding`.

### Task 8: Final verification
- Full suite + lint + assembleDebug; install on Pixel 8a; visual pass of all five screens + scan/cancel + theme toggle; fix findings; update HANDOFF.
