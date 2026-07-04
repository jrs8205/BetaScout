package org.jarsi.betascout.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.AppRepository

data class WatchlistUiState(
    val isLoading: Boolean = false,
    val apps: List<AppBetaOverview> = emptyList(),
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val repository: AppRepository,
) : ViewModel() {

    val uiState: StateFlow<WatchlistUiState> = repository.observeApps()
        .map { rows ->
            WatchlistUiState(apps = rows.filter { it.userStatus?.watching == true })
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WatchlistUiState(isLoading = true),
        )

    fun markChecked(packageName: String) {
        viewModelScope.launch { repository.markCheckedNow(packageName) }
    }
}
