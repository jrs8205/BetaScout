package org.jarsi.betascout.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.isRelevantApp

data class AppListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
    val filters: AppFilters = AppFilters(),
    val selectedTab: AppListTab = AppListTab.AVAILABLE,
    val apps: List<AppBetaOverview> = emptyList(),
    val counts: Map<AppListTab, Int> = emptyMap(),
    /** Joinable-right-now apps for the rail; hidden by the UI while searching. */
    val openBetas: List<AppBetaOverview> = emptyList(),
    /** The phone has at least one listable app, whatever the filters and tab. */
    val hasApps: Boolean = false,
    /** A refresh has run to its end at least once, whatever its outcome. */
    val firstRefreshDone: Boolean = false,
    /** A refresh the user started by hand (a pull, or Retry) is still going;
     *  drives the refresh indicator. State of the work, not of the screen, so a
     *  screen rebuilt mid-refresh shows it too. */
    val isUserRefreshing: Boolean = false,
) {
    /** The full-area spinner belongs to the very first load, when nothing is
     *  known yet and nothing has been tried. Any later refresh leaves the list
     *  — or an empty tab's text — where it is: a tab that happens to be empty is
     *  not "loading", a list left empty by a failed first load is not either,
     *  and a user-started refresh brings its own indicator. */
    val showLoading: Boolean
        get() = isLoading || isRefreshing && !hasApps && !firstRefreshDone
}

/** Progress of the installed-app refresh, kept in one value so the list never
 *  sees a half-updated combination of its parts. */
private data class RefreshState(
    val running: Boolean = false,
    val failed: Boolean = false,
    val firstDone: Boolean = false,
    val byUser: Boolean = false,
)

/** How long a user-started refresh is shown at the least. The installed-app read
 *  often finishes within a frame; without a floor the gesture would get no
 *  visible acknowledgement (and an indicator that never turns on can stick). */
private const val USER_REFRESH_MIN_MS = 600L

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val repository: AppRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(AppFilters())
    // "Not joined" stays the landing tab: joinable betas are the app's point.
    private val selectedTab = MutableStateFlow(AppListTab.AVAILABLE)
    private val refreshState = MutableStateFlow(RefreshState())

    val uiState: StateFlow<AppListUiState> = combine(
        repository.observeApps(), filters, selectedTab, refreshState,
    ) { rows, currentFilters, tab, refresh ->
        val filtered = filterApps(rows, currentFilters)
        AppListUiState(
            isLoading = false,
            isRefreshing = refresh.running,
            hasError = refresh.failed,
            firstRefreshDone = refresh.firstDone,
            isUserRefreshing = refresh.byUser,
            filters = currentFilters,
            selectedTab = tab,
            apps = appsForTab(filtered, tab),
            counts = tabCounts(filtered),
            openBetas = openBetas(filtered),
            hasApps = rows.any { it.app.isRelevantApp },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppListUiState(isLoading = true),
    )

    init {
        refresh()
    }

    fun selectTab(tab: AppListTab) {
        selectedTab.value = tab
    }

    private var refreshJob: Job? = null

    /** Outcome of the latest package read, for [refreshByUser]. Main-thread only. */
    private var lastAppsRefreshSucceeded = false

    fun refresh() {
        // Single-flight: the screen triggers refresh from both init and its
        // resume effect, and overlapping runs would race two catalog fetches
        // and Room replaces while the first finisher clears the shared
        // refreshing flag under the second.
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshState.update { it.copy(running = true, failed = false) }
            val seeded = repository.ensureSeeded()
            val refreshed = repository.refreshApps()
            lastAppsRefreshSucceeded = refreshed.isSuccess
            refreshState.update {
                it.copy(
                    running = false,
                    failed = seeded.isFailure || refreshed.isFailure,
                    firstDone = true,
                )
            }
        }
    }

    /**
     * A refresh the user asked for by hand — a pull, or Retry on the error line.
     * Refreshes like [refresh], joining a refresh already in flight rather than
     * starting a second one, and only then runs [afterRefresh], telling it
     * whether the installed apps were re-read. The scan a pull starts next is
     * handed that fact so it skips its own package read: one pull, one read.
     * Doing the read here (not leaving it to the scan) keeps the list updating
     * for signed-out and offline users, whose scan never starts.
     *
     * While it runs, [AppListUiState.isUserRefreshing] is up — here, in the view
     * model the work belongs to, so a rotation mid-refresh neither drops the
     * indicator nor lets a second gesture start alongside the first: a call made
     * while one is still going does nothing.
     */
    fun refreshByUser(afterRefresh: ((appsRefreshed: Boolean) -> Unit)? = null) {
        if (refreshState.value.byUser) return
        refreshState.update { it.copy(byUser = true) }
        refresh()
        val job = refreshJob
        viewModelScope.launch {
            val shownLongEnough = launch { delay(USER_REFRESH_MIN_MS) }
            job?.join()
            afterRefresh?.invoke(lastAppsRefreshSucceeded)
            shownLongEnough.join()
            refreshState.update { it.copy(byUser = false) }
        }
    }

    fun updateFilters(newFilters: AppFilters) {
        filters.value = newFilters
    }

    /** Closing the search panel drops everything it can set — the query and the
     *  filter chips alike — so the list is never narrowed by a control that is
     *  no longer on screen. */
    fun closeSearch() {
        filters.value = AppFilters()
    }
}
