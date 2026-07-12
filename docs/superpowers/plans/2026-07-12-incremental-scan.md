# Incremental Scan, Cancellation and Tab Counts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "Scan now" incremental (new + stale apps only) with per-app result persistence, add scan cancellation from the UI and the notification, show per-tab app counts, and fix the bug where apps installed after startup never enter the scan set.

**Architecture:** The scraper gains an `onObservation` callback so the repository persists each result the moment it is parsed (interrupted runs keep their work). The manual scan worker routes a new `KEY_FORCE` input into `refreshBetaStatus(force=…)` and refreshes the installed-app mirror before every run. UI: a cancel button (account screen + foreground-notification action), a secondary "Re-scan everything" button, tab titles with counts, and a resume-time app-list refresh.

**Tech Stack:** Kotlin, Jetpack Compose (M3), WorkManager, Room, Hilt, JUnit4 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-12-incremental-scan-design.md`

## Global Constraints

- Repo content is 100% English: code, comments, string resource *names*, commit messages.
- Every user-visible string goes to BOTH `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-fi/strings.xml` (Finnish).
- Commit messages: imperative mood, no attribution trailers of any kind.
- TDD: write the failing test, see it fail, implement, see it pass, commit.
- Test command: `./gradlew :app:testDebugUnitTest` (baseline: 132 tests green). Lint: `./gradlew :app:lintDebug`.
- No Room schema changes in this plan → no instrumented tests needed. (If you think you need one, stop: instrumented tests run ONLY on an emulator, never on a connected phone.)
- Follow the existing comment style: comments state constraints and reasons, not narration.

---

### Task 1: Feature branch

**Files:** none (git only)

- [ ] **Step 1: Create the branch**

```bash
cd "/c/Users/jrs82/Downloads/Beta sovellus"
git checkout -b feat/incremental-scan
```

Expected: `Switched to a new branch 'feat/incremental-scan'`

---

### Task 2: Scraper delivers each observation via callback

**Files:**
- Modify: `app/src/main/java/org/jarsi/betascout/data/scrape/BetaStatusScraper.kt`
- Test: `app/src/test/java/org/jarsi/betascout/data/scrape/BetaStatusScraperTest.kt`

**Interfaces:**
- Produces: `BetaStatusScraper.scrape(packages, session, onProgress, onObservation)` where
  `onObservation: suspend (BetaObservation) -> Unit = {}` fires once per successfully parsed
  page, immediately after the observation is created. `ScrapeOutcome` is unchanged.

- [ ] **Step 1: Write the failing tests**

Add to `BetaStatusScraperTest.kt` (inside the class):

```kotlin
    @Test
    fun `delivers each observation to the callback as soon as it is parsed`() = runTest {
        val events = mutableListOf<String>()
        val source = TestingPageSource { pkg, _ ->
            events += "fetch $pkg"
            Result.success(FetchedPage(OPEN_HTML))
        }

        scraper(source).scrape(
            listOf("com.a", "com.b"),
            session,
            onObservation = { events += "obs ${it.packageName}" },
        )

        // Each observation must be delivered before the next fetch starts, so an
        // interrupted run keeps everything checked so far.
        assertEquals(listOf("fetch com.a", "obs com.a", "fetch com.b", "obs com.b"), events)
    }

    @Test
    fun `observations delivered before a rate-limit stop are kept`() = runTest {
        val delivered = mutableListOf<String>()
        val source = TestingPageSource { pkg, _ ->
            if (pkg == "com.second") Result.failure(HttpStatusException(429))
            else Result.success(FetchedPage(OPEN_HTML))
        }

        val outcome = scraper(source).scrape(
            listOf("com.first", "com.second", "com.third"),
            session,
            onObservation = { delivered += it.packageName },
        )

        assertEquals(listOf("com.first"), delivered)
        assertEquals(listOf("com.first"), outcome.observations.map { it.packageName })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.jarsi.betascout.data.scrape.BetaStatusScraperTest"`
Expected: compilation error — `onObservation` parameter does not exist.

- [ ] **Step 3: Implement the callback**

In `BetaStatusScraper.kt`, change the `scrape` signature and the success branch:

```kotlin
    suspend fun scrape(
        packages: List<String>,
        session: PlaySession,
        onProgress: suspend (index: Int, total: Int, packageName: String) -> Unit = { _, _, _ -> },
        onObservation: suspend (BetaObservation) -> Unit = {},
    ): ScrapeOutcome {
```

and replace the block that appends the observation (currently `observations += BetaObservation(...)`) with:

```kotlin
            val observation = BetaObservation(
                accountKey = session.accountKey,
                packageName = packageName,
                liveStatus = result.liveStatus,
                observedMembership = result.membership,
                checkedAt = clock(),
            )
            observations += observation
            // Delivered immediately so the caller can persist it: a run that is
            // cancelled or dies later must not lose the pages already checked.
            onObservation(observation)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.jarsi.betascout.data.scrape.BetaStatusScraperTest"`
Expected: all tests PASS (12 total in this class).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/jarsi/betascout/data/scrape/BetaStatusScraper.kt app/src/test/java/org/jarsi/betascout/data/scrape/BetaStatusScraperTest.kt
git commit -m "Deliver scrape observations through a per-page callback"
```

---

### Task 3: Repository persists each observation immediately

**Files:**
- Modify: `app/src/main/java/org/jarsi/betascout/data/repo/DefaultAppRepository.kt:129-156`
- Test: `app/src/test/java/org/jarsi/betascout/data/repo/DefaultAppRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 2's `onObservation` callback.
- Produces: unchanged `refreshBetaStatus` signature; new behavior — observations and
  transitions are recorded per app during the run, not after it.

- [ ] **Step 1: Write the failing tests**

Add to `DefaultAppRepositoryTest.kt`. Also add these imports at the top of the file:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
```

Tests (inside the class):

```kotlin
    @Test
    fun `records each observation as soon as it is scraped`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        val seenDuringScan = mutableListOf<String?>()
        onFetch = { pkg ->
            // What com.a looks like in the DB while com.b is still being fetched.
            if (pkg == "com.b") seenDuringScan += observationDao.get(ACCOUNT, "com.a")?.packageName
        }

        repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(listOf<String?>("com.a"), seenDuringScan)
    }

    @Test
    fun `a run that dies mid-scan keeps the observations recorded so far`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        onFetch = { pkg -> if (pkg == "com.b") throw IllegalStateException("boom") }

        val result = repo.refreshBetaStatus(session)

        assertTrue(result.exceptionOrNull() is DataError.Local)
        assertEquals(
            ObservedMembership.NOT_JOINED,
            observationDao.get(ACCOUNT, "com.a")!!.observedMembership,
        )
        assertNull(observationDao.get(ACCOUNT, "com.b"))
    }

    @Test
    fun `a cancelled scan keeps completed observations and releases the scan lock`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        lateinit var job: Job
        onFetch = { pkg ->
            if (pkg == "com.b") {
                job.cancel()
                // First suspension point after the cancel → CancellationException here.
                delay(1)
            }
        }
        job = launch { repo.refreshBetaStatus(session) }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            ObservedMembership.NOT_JOINED,
            observationDao.get(ACCOUNT, "com.a")!!.observedMembership,
        )
        assertNull(observationDao.get(ACCOUNT, "com.b"))
        // The finally block released the lock: a follow-up scan must not be rejected.
        assertTrue(repo.refreshBetaStatus(session, force = true).isSuccess)
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.jarsi.betascout.data.repo.DefaultAppRepositoryTest"`
Expected: `records each observation as soon as it is scraped` FAILS
(`seenDuringScan` contains null — nothing persisted mid-run yet). The
dies-mid-scan test FAILS on `observationDao.get(ACCOUNT, "com.a")!!` (null —
batch persistence lost everything). The cancelled test FAILS the same way.

- [ ] **Step 3: Move persistence and transition recording into the callback**

In `DefaultAppRepository.refreshBetaStatus`, replace the section from
`val outcome = scraper.scrape(...)` down to (and including) the
`outcome.observations.forEach { ... }` upsert loop and the post-hoc `transitions`
computation with:

```kotlin
                // Persisted per page, not per run: a cancelled or dying run keeps
                // everything checked so far, and the next incremental scan continues
                // from the remainder. A transition only exists where a previous
                // observation is overwritten; a first sighting is not a change and
                // must not fire notifications.
                val transitions = mutableListOf<StatusTransition>()
                val outcome = scraper.scrape(
                    due,
                    session,
                    onProgress = { index, total, packageName ->
                        onProgress(ScanProgress(index, total, labels[packageName] ?: packageName))
                    },
                ) { observation ->
                    val previous = observed[observation.packageName]
                    if (previous != null && previous.liveStatus != observation.liveStatus) {
                        transitions += StatusTransition(
                            packageName = observation.packageName,
                            from = previous.liveStatus,
                            to = observation.liveStatus,
                        )
                    }
                    betaObservationDao.upsert(observation.toEntity())
                }
                android.util.Log.d(
                    TAG,
                    "refreshBetaStatus: scraped=${outcome.observations.size} needsLogin=${outcome.needsLogin}",
                )
```

Keep the failure-stamping loop (`outcome.failures.forEach { ... }`) and the
summary construction as they are — `transitions = transitions` now refers to the
locally accumulated list.

- [ ] **Step 4: Run the full repository test class**

Run: `./gradlew :app:testDebugUnitTest --tests "org.jarsi.betascout.data.repo.DefaultAppRepositoryTest"`
Expected: ALL tests PASS — including the pre-existing transition, failure-stamping
and overlap tests, which prove the semantics did not change.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/jarsi/betascout/data/repo/DefaultAppRepository.kt app/src/test/java/org/jarsi/betascout/data/repo/DefaultAppRepositoryTest.kt
git commit -m "Persist scan observations per app so interrupted runs keep progress"
```

---

### Task 4: Worker refreshes the app list and routes a force flag

**Files:**
- Modify: `app/src/main/java/org/jarsi/betascout/work/BetaScanWorker.kt`
- Modify: `app/src/main/java/org/jarsi/betascout/work/BetaScanScheduler.kt:41-50`
- Modify: `app/src/main/java/org/jarsi/betascout/ui/account/AccountViewModel.kt:144-147`

**Interfaces:**
- Produces: `BetaScanWorker.KEY_FORCE = "force"` (input `Boolean`, default false);
  `BetaScanScheduler.scanNow(workManager, policy = KEEP, force = false)`;
  `AccountViewModel.fullResync()` and `AccountViewModel.cancelScan()`.

No unit-test harness exists for WorkManager workers in this repo (no Robolectric);
these changes are covered by compilation, the existing suite, and device validation.

- [ ] **Step 1: Add the force flag to the scheduler**

In `BetaScanScheduler.kt` replace `scanNow` with:

```kotlin
    /** Enqueues a user-initiated scan as unique work so it survives the user
     *  leaving the screen (or the app). KEEP means a second tap while one is running
     *  does nothing; REPLACE (after a fresh sign-in) supersedes a stale run and its
     *  recorded outcome. [force] ignores the freshness TTLs (full re-scan); the
     *  default incremental scan checks new apps and stale observations only. */
    fun scanNow(
        workManager: WorkManager,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        force: Boolean = false,
    ) {
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<BetaScanWorker>()
                .setInputData(
                    workDataOf(
                        BetaScanWorker.KEY_MANUAL to true,
                        BetaScanWorker.KEY_FORCE to force,
                    ),
                )
                .setConstraints(connected)
                .build(),
        )
    }
```

- [ ] **Step 2: Route the flag and refresh installed apps in the worker**

In `BetaScanWorker.kt`:

Add to `companion object` after `KEY_MANUAL`:

```kotlin
        const val KEY_FORCE = "force"
```

In `doWork()`, after the `val manual = ...` line add:

```kotlin
        val force = inputData.getBoolean(KEY_FORCE, false)
```

After the `if (manual) { ... setForeground ... }` block and before `val result = ...`, add:

```kotlin
        // The installed-app mirror is otherwise only refreshed by the list screen;
        // an app installed since then must still join this scan's set. A failure
        // falls back to the cached list — a slightly stale set beats no scan.
        repository.refreshApps().onFailure {
            android.util.Log.w("BetaScout", "scan: refreshApps failed, using cached app list", it)
        }
```

Change the manual branch of `val result =` from `force = true` to:

```kotlin
            repository.refreshBetaStatus(session, force = force) { progress ->
```

(the periodic branch stays `repository.refreshBetaStatus(session, cap = SCAN_CAP)`).

Also update the class KDoc's manual-mode sentence to match reality:

```kotlin
 * - Manual ("Scan now"): uncapped; incremental by default (new + stale apps),
 *   forced to re-check everything when [KEY_FORCE] is set. With the 3-second
 *   crawl delay a large forced run takes well over ten minutes, so the run is
 *   promoted to a foreground service — surviving the user leaving the app —
 *   and reports per-app progress.
```

- [ ] **Step 3: Add the ViewModel entry points**

In `AccountViewModel.kt` replace `resync()` with:

```kotlin
    /** Incremental scan: new apps and stale observations only. */
    fun resync() {
        _state.update { it.copy(error = null) }
        BetaScanScheduler.scanNow(workManager)
    }

    /** Full re-scan: ignores freshness TTLs and re-checks every app. */
    fun fullResync() {
        _state.update { it.copy(error = null) }
        BetaScanScheduler.scanNow(workManager, force = true)
    }

    fun cancelScan() {
        workManager.cancelUniqueWork(BetaScanScheduler.MANUAL_WORK_NAME)
    }
```

- [ ] **Step 4: Compile and run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (previous count + 5 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/jarsi/betascout/work/BetaScanWorker.kt app/src/main/java/org/jarsi/betascout/work/BetaScanScheduler.kt app/src/main/java/org/jarsi/betascout/ui/account/AccountViewModel.kt
git commit -m "Make manual scans incremental, refresh the app list before each run"
```

---

### Task 5: Cancel and full-scan controls in the UI and the notification

**Files:**
- Modify: `app/src/main/java/org/jarsi/betascout/ui/account/AccountScreen.kt`
- Modify: `app/src/main/java/org/jarsi/betascout/work/BetaScanWorker.kt` (notification action)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-fi/strings.xml`

**Interfaces:**
- Consumes: `AccountViewModel.cancelScan()` / `fullResync()` from Task 4.

- [ ] **Step 1: Add the strings**

`app/src/main/res/values/strings.xml` (near the other `account_*` strings):

```xml
    <string name="account_scan_cancel">Cancel scan</string>
    <string name="account_full_scan">Re-scan everything</string>
    <string name="account_full_scan_hint">Checks every app again — can take 10–15 minutes.</string>
    <string name="scan_notification_cancel">Cancel</string>
```

`app/src/main/res/values-fi/strings.xml` (same keys, same place):

```xml
    <string name="account_scan_cancel">Peruuta skannaus</string>
    <string name="account_full_scan">Skannaa kaikki uudelleen</string>
    <string name="account_full_scan_hint">Tarkistaa kaikki sovellukset uudelleen — voi kestää 10–15 minuuttia.</string>
    <string name="scan_notification_cancel">Peruuta</string>
```

- [ ] **Step 2: Cancel button on the account screen**

In `AccountScreen.kt`, inside the `if (state.busy) { ... }` block, add after the
progress `Column`/`Box` (still inside `if (state.busy)`):

```kotlin
                OutlinedButton(
                    onClick = viewModel::cancelScan,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_scan_cancel))
                }
```

- [ ] **Step 3: Full-scan secondary button**

In the `if (state.signedIn)` block, between the "Scan now" `Button` and the
sign-out `OutlinedButton`, add:

```kotlin
                TextButton(
                    onClick = viewModel::fullResync,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_full_scan))
                }
                Text(
                    text = stringResource(R.string.account_full_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
```

Add the import: `androidx.compose.material3.TextButton`.

- [ ] **Step 4: Cancel action on the foreground notification**

In `BetaScanWorker.kt`, add the import `androidx.work.WorkManager` and in
`createForegroundInfo()` build the notification as:

```kotlin
        // Lets the user stop a long scan from the shade; per-app persistence means
        // cancelling loses nothing already checked.
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(applicationContext.getString(R.string.scan_notification_title))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(
                R.drawable.ic_stat_reminder,
                applicationContext.getString(R.string.scan_notification_cancel),
                cancelIntent,
            )
            .build()
```

- [ ] **Step 5: Compile, lint, run the suite**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL, no new lint errors.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/jarsi/betascout/ui/account/AccountScreen.kt app/src/main/java/org/jarsi/betascout/work/BetaScanWorker.kt app/src/main/res/values/strings.xml app/src/main/res/values-fi/strings.xml
git commit -m "Add scan cancellation and a full re-scan action"
```

---

### Task 6: Tab counts

**Files:**
- Modify: `app/src/main/java/org/jarsi/betascout/ui/applist/AppListFilter.kt`
- Modify: `app/src/main/java/org/jarsi/betascout/ui/applist/AppListViewModel.kt:16-50`
- Modify: `app/src/main/java/org/jarsi/betascout/ui/applist/AppListScreen.kt:92,130-150`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fi/strings.xml`
- Test: `app/src/test/java/org/jarsi/betascout/ui/applist/AppListFilterTest.kt`

**Interfaces:**
- Produces: `fun tabCounts(filteredRows: List<AppBetaOverview>): Map<BetaMembership, Int>`
  in `AppListFilter.kt`; `AppListUiState.counts: Map<BetaMembership, Int> = emptyMap()`.

- [ ] **Step 1: Write the failing tests**

Add to `AppListFilterTest.kt`:

```kotlin
    @Test
    fun `tabCounts groups the filtered rows by membership tab`() {
        val rows = listOf(
            row(
                "com.joined",
                observedLiveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
            ),
            row("com.available", betaStatus = KnownBetaStatus.OFTEN_FULL),
            row("com.none"),
            // Framework package: filtered out entirely, must not count anywhere.
            row("com.android.providers.media", isSystem = true, hasLauncher = false),
        )

        val counts = tabCounts(filterApps(rows, AppFilters()))

        assertEquals(1, counts[BetaMembership.JOINED] ?: 0)
        assertEquals(1, counts[BetaMembership.AVAILABLE] ?: 0)
        assertEquals(1, counts[BetaMembership.NONE] ?: 0)
    }

    @Test
    fun `tab counts follow the active search query`() {
        val rows = listOf(
            row("com.whatsapp", label = "WhatsApp", betaStatus = KnownBetaStatus.OFTEN_FULL),
            row("com.spotify", label = "Spotify"),
        )

        val counts = tabCounts(filterApps(rows, AppFilters(query = "whats")))

        assertEquals(mapOf(BetaMembership.AVAILABLE to 1), counts)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.jarsi.betascout.ui.applist.AppListFilterTest"`
Expected: compilation error — `tabCounts` does not exist.

- [ ] **Step 3: Implement `tabCounts`**

Add to `AppListFilter.kt` (after `filterApps`):

```kotlin
/** Per-tab totals of the already-filtered rows, shown in the tab titles. */
fun tabCounts(filteredRows: List<AppBetaOverview>): Map<BetaMembership, Int> =
    filteredRows.groupingBy { it.betaMembership() }.eachCount()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.jarsi.betascout.ui.applist.AppListFilterTest"`
Expected: PASS.

- [ ] **Step 5: Surface the counts in the UI state**

`AppListViewModel.kt` — add to `AppListUiState`:

```kotlin
    val counts: Map<BetaMembership, Int> = emptyMap(),
```

and change the `combine` body to filter once:

```kotlin
    ) { rows, currentFilters, tab, isRefreshing, hasError ->
        val filtered = filterApps(rows, currentFilters)
        AppListUiState(
            isLoading = false,
            isRefreshing = isRefreshing,
            hasError = hasError,
            filters = currentFilters,
            selectedTab = tab,
            apps = filtered.filter { it.betaMembership() == tab },
            counts = tabCounts(filtered),
        )
    }
```

- [ ] **Step 6: Render the counts in the tab titles**

Strings — `values/strings.xml`:

```xml
    <string name="tab_with_count">%1$s (%2$d)</string>
```

`values-fi/strings.xml`:

```xml
    <string name="tab_with_count">%1$s (%2$d)</string>
```

`AppListScreen.kt` — change `BetaTabs` to:

```kotlin
@Composable
private fun BetaTabs(
    selected: BetaMembership,
    counts: Map<BetaMembership, Int>,
    onSelectTab: (BetaMembership) -> Unit,
) {
    val selectedIndex = betaTabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    TabRow(selectedTabIndex = selectedIndex) {
        betaTabs.forEach { (membership, labelRes) ->
            Tab(
                selected = membership == selected,
                onClick = { onSelectTab(membership) },
                text = {
                    Text(
                        stringResource(
                            R.string.tab_with_count,
                            stringResource(labelRes),
                            counts[membership] ?: 0,
                        ),
                    )
                },
            )
        }
    }
}
```

and its call site to:

```kotlin
            BetaTabs(
                selected = uiState.selectedTab,
                counts = uiState.counts,
                onSelectTab = onSelectTab,
            )
```

- [ ] **Step 7: Compile and run the suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/jarsi/betascout/ui/applist/ app/src/test/java/org/jarsi/betascout/ui/applist/AppListFilterTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-fi/strings.xml
git commit -m "Show per-tab app counts in the tab titles"
```

---

### Task 7: Refresh the installed-app list on resume

**Files:**
- Modify: `app/src/main/java/org/jarsi/betascout/ui/applist/AppListScreen.kt:49-64`

**Interfaces:** none new.

- [ ] **Step 1: Add the resume-time refresh**

In `AppListScreen` (the public composable taking the ViewModel), add before
`AppListContent(...)`:

```kotlin
    // The installed-app mirror only updates when this screen refreshes it. Coming
    // back from the launcher after installing an app must pick the new app up
    // without a manual refresh tap.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
```

Add the import: `androidx.lifecycle.compose.LifecycleResumeEffect`.

(The ViewModel's `init { refresh() }` stays: it covers the first composition and
process restores; the duplicate refresh on first resume is an idempotent, cheap
PackageManager query.)

- [ ] **Step 2: Compile and run the suite + lint**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/jarsi/betascout/ui/applist/AppListScreen.kt
git commit -m "Refresh the installed-app list when the list screen resumes"
```

---

### Task 8: Final verification

- [ ] **Step 1: Full suite + lint from a clean state**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL; test count = baseline 132 + 7 new = 139.

- [ ] **Step 2: Assemble a debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Review the diff against the spec**

```bash
git log --oneline main..HEAD
git diff main --stat
```

Check each spec requirement (installed-apps sync, incremental default, force
path, per-app persistence, cancel UI + notification, tab counts) maps to a
commit. Device validation (both phones) happens before any merge/release.
