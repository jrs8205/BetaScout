package org.jarsi.betavahti.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.betavahti.domain.AppBetaOverview
import org.jarsi.betavahti.domain.AppRepository

data class AppListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
    val filters: AppFilters = AppFilters(),
    val apps: List<AppBetaOverview> = emptyList(),
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val repository: AppRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(AppFilters())
    private val refreshing = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)

    val uiState: StateFlow<AppListUiState> = combine(
        repository.observeApps(), filters, refreshing, failed,
    ) { rows, currentFilters, isRefreshing, hasError ->
        AppListUiState(
            isLoading = false,
            isRefreshing = isRefreshing,
            hasError = hasError,
            filters = currentFilters,
            apps = filterApps(rows, currentFilters),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppListUiState(isLoading = true),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            failed.value = false
            val seeded = repository.ensureSeeded()
            val refreshed = repository.refreshApps()
            failed.value = seeded.isFailure || refreshed.isFailure
            refreshing.value = false
        }
    }

    fun updateFilters(newFilters: AppFilters) {
        filters.value = newFilters
    }
}
