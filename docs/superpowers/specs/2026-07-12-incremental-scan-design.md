# Incremental scanning, scan cancellation, tab counts — design

Status: approved 2026-07-12.

## Goal

Address four field observations from overnight v0.4.2 use:

1. A running scan cannot be cancelled.
2. The Joined / Not joined / No beta tabs do not show how many apps each holds.
3. "Scan now" always re-checks every app (~10–15 min per run); it should reuse
   previous results and only check what is new or stale.
4. Bug: an app installed while a scan was running never appeared on any tab,
   even after a second full scan.

## Non-goals

- No change to the periodic background worker's cadence, cap (30) or TTLs.
- No change to ScanPolicy TTL values (open/full 24 h, closed 3 d, no-program 7 d,
  unknown 12 h).
- No resumable-mid-run checkpointing beyond what per-app persistence gives for free.
- Slot-open notifications for transitions observed before a cancellation are
  accepted as lost for that run (state is persisted correctly; a notification for
  an already-recorded transition will not fire later).

## Background

- Installed apps are mirrored into Room only by `AppListViewModel.refresh()`
  (init). `DefaultAppRepository.refreshBetaStatus` reads `installedAppDao.getAll()`
  and never re-syncs from PackageManager, so an app installed while the process
  lives is invisible to scans and the list. This is the root cause of item 4.
- Scan results already persist per account in `beta_observations` with
  `checkedAt`, and `ScanPolicy.selectDue` already implements "never-checked first,
  then stale-by-TTL". Item 3 exists only because the manual scan passes
  `force=true` (decision of 2026-07-11, now deliberately reversed).
- `BetaStatusScraper.scrape` collects observations in memory and the repository
  upserts them only after the whole batch finishes, so an interrupted run loses
  all of its work.

## Decision (reverses 2026-07-11)

"Scan now" becomes incremental: `force=false`, uncapped. It checks new
(never-checked) apps first, then apps whose observation is stale per ScanPolicy
TTLs. A full forced re-scan remains available as a secondary action.

## Component 1: Installed-apps sync (bug fix)

- `BetaScanWorker.doWork()` calls `repository.refreshApps()` before
  `refreshBetaStatus`, in both manual and periodic modes. On failure the run
  continues with the cached list and logs a warning (tag `BetaScout`).
- `AppListScreen` refreshes the installed-app mirror when the screen resumes
  (`LifecycleResumeEffect` → `viewModel.refresh()`), so a newly installed app
  appears on the "No beta" tab (catalog fallback) immediately, before any scan.

## Component 2: Incremental "Scan now" + full re-scan

- `BetaScanScheduler.scanNow` enqueues the manual worker with a new
  `KEY_FORCE` input (default false). The manual path passes `force` through to
  `refreshBetaStatus(session, cap = null, force = force)`.
- Account screen: primary button "Scan now" (incremental). Below it a secondary
  `TextButton` "Re-scan everything" with a supporting line noting it re-checks
  every app and can take 10–15 minutes. Both disabled while busy. Strings en+fi.
- Post-login scan stays `force=false` (a fresh account has no observations, so
  everything is due anyway).

## Component 3: Per-app persistence

- `BetaStatusScraper.scrape` gains an
  `onObservation: suspend (BetaObservation) -> Unit` callback invoked as each
  page is parsed. The returned `ScrapeOutcome.observations` list stays for the
  summary.
- `DefaultAppRepository.refreshBetaStatus` persists each observation in the
  callback: computes the transition (previous vs new liveStatus, first sighting
  is not a transition) and upserts the row immediately. Failure stamping
  (`lastError`) is unchanged.
- Effect: a cancelled, crashed or throttling-stopped run keeps everything
  checked so far; the next incremental scan continues with only the remainder.

## Component 4: Scan cancellation

- Account screen progress area gains a "Cancel" `OutlinedButton` →
  `AccountViewModel.cancelScan()` → `workManager.cancelUniqueWork(MANUAL_WORK_NAME)`.
  The existing CANCELLED branch already resets busy/progress.
- The foreground notification gains a cancel action built with
  `WorkManager.createCancelPendingIntent(id)` so the scan can be stopped from the
  notification shade. Strings en+fi.
- Cancellation does not write a `LastScanInfo`; the previous completed summary
  stays on the account screen.

## Component 5: Tab counts

- `AppListUiState` gains `counts: Map<BetaMembership, Int>` computed in the
  ViewModel from `filterApps(rows, filters)` grouped by `betaMembership()` —
  the active search/filters affect the numbers, the selected tab does not.
- Tab titles render as "Not joined (23)" / "Joined (39)" / "No beta (76)" via a
  formatted string resource (en+fi).

## Error handling

- `refreshApps()` failure inside the worker: log + proceed with cached list
  (a scan with a slightly stale app set beats no scan).
- Cancellation propagates as `CancellationException` through scraper delay/fetch;
  the repository's per-app upserts already committed stay committed. The scan
  mutex is released by the existing `finally`.

## Testing (TDD)

- Repository: per-app persistence (observation visible after each callback),
  transitions still computed per app, an exception mid-batch keeps earlier
  upserts, failure stamping unchanged.
- Scraper: `onObservation` fires per parsed page, not for failures; stop-early
  paths (429/403, consecutive failures, needs-login) keep already-delivered
  observations.
- Worker: `refreshApps` called before scan; `KEY_FORCE` routed to `force=true`;
  default manual run is `force=false` uncapped.
- ViewModel/filter: counts per tab react to filters and rows.
- Full suite + lint must stay green (132 unit tests at baseline).

## Rollout

Single change set on a feature branch, then version bump to v0.5.0
(versionCode 9) after device validation on both phones.
