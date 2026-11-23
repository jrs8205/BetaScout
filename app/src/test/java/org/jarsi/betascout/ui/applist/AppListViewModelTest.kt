package org.jarsi.betascout.ui.applist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.InstalledAppInfo
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.domain.ScanProgress
import org.jarsi.betascout.domain.ScanSummary
import org.jarsi.betascout.domain.UserBetaState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counts refresh calls; [gate] (when set) makes ensureSeeded suspend so a test
 *  can hold a refresh in flight while another one is attempted. */
private class FakeAppRepository : AppRepository {
    var ensureSeededCalls = 0
    var refreshAppsCalls = 0
    var gate: CompletableDeferred<Unit>? = null
    var rows: List<AppBetaOverview> = emptyList()

    override fun observeApps(): Flow<List<AppBetaOverview>> = flowOf(rows)
    override suspend fun getApps(): List<AppBetaOverview> = emptyList()
    override val scanRunning: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun withScanLock(block: suspend () -> Unit) = block()

    override suspend fun ensureSeeded(): Result<Unit> {
        ensureSeededCalls++
        gate?.await()
        return Result.success(Unit)
    }

    var refreshAppsResult: Result<Unit> = Result.success(Unit)

    override suspend fun refreshApps(): Result<Unit> {
        refreshAppsCalls++
        return refreshAppsResult
    }

    override suspend fun setUserState(packageName: String, state: UserBetaState) =
        Result.success(Unit)

    override suspend fun clearAllObservations() = Result.success(Unit)

    override suspend fun refreshBetaStatus(
        session: PlaySession,
        cap: Int?,
        force: Boolean,
        onProgress: suspend (ScanProgress) -> Unit,
    ): Result<ScanSummary> = throw UnsupportedOperationException()

    override suspend fun refreshSingleBetaStatus(session: PlaySession, packageName: String) =
        Result.success(Unit)

    override suspend fun setWatching(
        packageName: String,
        watching: Boolean,
        reminderIntervalDays: Int?,
    ) = Result.success(Unit)

    override suspend fun setUserNote(packageName: String, note: String?) = Result.success(Unit)

    override suspend fun markCheckedNow(packageName: String) = Result.success(Unit)

    override suspend fun markReminded(packageNames: List<String>) = Result.success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a refresh started while one is in flight is dropped, not run in parallel`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        repository.gate = CompletableDeferred()
        val viewModel = AppListViewModel(repository)

        // The screen's resume effect calls refresh() while the init-time refresh
        // is still inside ensureSeeded.
        viewModel.refresh()
        viewModel.refresh()
        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.ensureSeededCalls)
        assertEquals(1, repository.refreshAppsCalls)
    }

    @Test
    fun `a refresh after the previous one finished runs normally`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        val viewModel = AppListViewModel(repository)
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, repository.ensureSeededCalls)
    }

    @Test
    fun `an empty tab keeps its empty state while a refresh runs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        // One app with no beta: the landing "Not joined" tab has nothing to list.
        repository.rows = listOf(
            AppBetaOverview(
                InstalledAppInfo("com.example", "Example", "1.0", 1L, null, false, true, 0L),
            ),
        )
        repository.gate = CompletableDeferred()
        val viewModel = AppListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isRefreshing)
        assertTrue(state.apps.isEmpty())
        // A pull on an empty tab already shows the pull indicator; a second,
        // full-area spinner replacing the empty text would be noise.
        assertFalse(state.showLoading)

        repository.gate!!.complete(Unit)
    }

    @Test
    fun `the first refresh, before any app is known, shows the loading state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        repository.gate = CompletableDeferred()
        val viewModel = AppListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        assertTrue(viewModel.uiState.value.showLoading)

        repository.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showLoading)
    }

    @Test
    fun `a pull after a failed first load shows no second, full-area spinner`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        // The first package read fails and the database has no app to show.
        repository.refreshAppsResult = Result.failure(DataError.Scan(RuntimeException("boom")))
        val viewModel = AppListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasError)
        assertFalse(viewModel.uiState.value.hasApps)
        assertFalse(viewModel.uiState.value.showLoading)

        // The user pulls; the refresh is held in flight.
        repository.gate = CompletableDeferred()
        viewModel.refresh()
        runCurrent()

        assertTrue(viewModel.uiState.value.isRefreshing)
        // The pull brings its own indicator. Only the very first load, before
        // anything was ever attempted, may take over the whole list area.
        assertFalse(viewModel.uiState.value.showLoading)

        repository.gate!!.complete(Unit)
    }

    @Test
    fun `a refresh the user started stays marked for as long as it runs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        val viewModel = AppListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isUserRefreshing)

        repository.gate = CompletableDeferred()
        viewModel.refreshByUser()
        runCurrent()

        // The mark belongs to the work, and the work lives here: a screen that
        // is rebuilt mid-refresh (rotation) reads the same state and keeps its
        // indicator, instead of looking idle and inviting a second pull.
        assertTrue(viewModel.uiState.value.isUserRefreshing)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(viewModel.uiState.value.isUserRefreshing)

        repository.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isUserRefreshing)
    }

    @Test
    fun `a refresh that finishes at once is still shown for a moment`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        val viewModel = AppListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        viewModel.refreshByUser()
        runCurrent()

        // The read is over within the same dispatch; without a floor the
        // gesture would get no visible acknowledgement.
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertTrue(viewModel.uiState.value.isUserRefreshing)
        advanceTimeBy(599)
        runCurrent()
        assertTrue(viewModel.uiState.value.isUserRefreshing)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(viewModel.uiState.value.isUserRefreshing)
    }

    @Test
    fun `a second pull while the first is still running starts nothing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        val viewModel = AppListViewModel(repository)
        advanceUntilIdle()

        repository.gate = CompletableDeferred()
        var first: Boolean? = null
        var second: Boolean? = null
        viewModel.refreshByUser { first = it }
        runCurrent()
        viewModel.refreshByUser { second = it }
        runCurrent()

        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(true, first)
        // No second read and no second scan request.
        assertNull(second)
        assertEquals(2, repository.refreshAppsCalls)
    }

    @Test
    fun `a pull reads the packages once and hands over only after that`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        val viewModel = AppListViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, repository.refreshAppsCalls)

        repository.gate = CompletableDeferred()
        var handedOver: Boolean? = null
        viewModel.refreshByUser { appsRefreshed -> handedOver = appsRefreshed }
        runCurrent()

        // The scan must not start while the package read is still running: it
        // is told the list is fresh and skips its own read.
        assertNull(handedOver)

        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(true, handedOver)
        assertEquals(2, repository.refreshAppsCalls)
    }

    @Test
    fun `a pull during a refresh already in flight joins it instead of reading again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        repository.gate = CompletableDeferred()
        // The init-time (or resume) refresh is in flight when the user pulls.
        val viewModel = AppListViewModel(repository)
        runCurrent()

        var handedOver: Boolean? = null
        viewModel.refreshByUser { appsRefreshed -> handedOver = appsRefreshed }
        runCurrent()
        assertNull(handedOver)

        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(true, handedOver)
        assertEquals(1, repository.ensureSeededCalls)
        assertEquals(1, repository.refreshAppsCalls)
    }

    @Test
    fun `a pull whose package read failed says so, so the scan reads for itself`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        val viewModel = AppListViewModel(repository)
        advanceUntilIdle()

        repository.refreshAppsResult = Result.failure(DataError.Scan(RuntimeException("boom")))
        var handedOver: Boolean? = null
        viewModel.refreshByUser { appsRefreshed -> handedOver = appsRefreshed }
        advanceUntilIdle()

        assertEquals(false, handedOver)
    }

    @Test
    fun `closing the search drops every filter the search panel can set`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        // A user app and a launchable system app, neither with a beta.
        repository.rows = listOf(
            AppBetaOverview(
                InstalledAppInfo("com.example", "Example", "1.0", 1L, null, false, true, 0L),
            ),
            AppBetaOverview(
                InstalledAppInfo("com.android.chrome", "Chrome", "1.0", 1L, null, true, true, 0L),
            ),
        )
        val viewModel = AppListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        viewModel.selectTab(AppListTab.ALL)
        viewModel.updateFilters(AppFilters(query = "chr", onlySystem = true))
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.apps.size)

        viewModel.closeSearch()
        advanceUntilIdle()

        // The "only system apps" chip lives inside the search panel; left on
        // after closing, it would filter the list with no visible control.
        assertEquals(AppFilters(), viewModel.uiState.value.filters)
        assertEquals(2, viewModel.uiState.value.apps.size)
    }

    @Test
    fun `the All tab lists every app and the landing tab only joinable ones`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeAppRepository()
        repository.rows = listOf(
            AppBetaOverview(
                InstalledAppInfo("com.example", "Example", "1.0", 1L, null, false, true, 0L),
            ),
        )
        val viewModel = AppListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        assertEquals(AppListTab.AVAILABLE, viewModel.uiState.value.selectedTab)
        assertTrue(viewModel.uiState.value.apps.isEmpty())

        viewModel.selectTab(AppListTab.ALL)
        advanceUntilIdle()

        assertEquals(listOf("com.example"), viewModel.uiState.value.apps.map { it.app.packageName })
        assertEquals(1, viewModel.uiState.value.counts[AppListTab.ALL])
    }
}
