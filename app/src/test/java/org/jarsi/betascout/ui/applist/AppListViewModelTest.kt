package org.jarsi.betascout.ui.applist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.domain.ScanProgress
import org.jarsi.betascout.domain.ScanSummary
import org.jarsi.betascout.domain.UserBetaState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Counts refresh calls; [gate] (when set) makes ensureSeeded suspend so a test
 *  can hold a refresh in flight while another one is attempted. */
private class FakeAppRepository : AppRepository {
    var ensureSeededCalls = 0
    var refreshAppsCalls = 0
    var gate: CompletableDeferred<Unit>? = null

    override fun observeApps(): Flow<List<AppBetaOverview>> = flowOf(emptyList())
    override val scanRunning: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun awaitScanIdle() = Unit

    override suspend fun ensureSeeded(): Result<Unit> {
        ensureSeededCalls++
        gate?.await()
        return Result.success(Unit)
    }

    override suspend fun refreshApps(): Result<Unit> {
        refreshAppsCalls++
        return Result.success(Unit)
    }

    override suspend fun setUserState(packageName: String, state: UserBetaState) =
        Result.success(Unit)

    override suspend fun clearObservations(accountKey: String) = Result.success(Unit)

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
}
