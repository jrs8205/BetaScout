package org.jarsi.betavahti.ui.appdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.betavahti.domain.AppBetaOverview
import org.jarsi.betavahti.domain.AppRepository
import org.jarsi.betavahti.domain.UserBetaState

data class AppDetailUiState(
    val isLoading: Boolean = false,
    val overview: AppBetaOverview? = null,
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AppRepository,
) : ViewModel() {

    val packageName: String = checkNotNull(savedStateHandle["packageName"])

    val uiState: StateFlow<AppDetailUiState> = repository.observeApps()
        .map { rows -> AppDetailUiState(overview = rows.find { it.app.packageName == packageName }) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppDetailUiState(isLoading = true),
        )

    fun setUserState(state: UserBetaState) {
        viewModelScope.launch { repository.setUserState(packageName, state) }
    }

    fun setWatching(watching: Boolean, reminderIntervalDays: Int? = null) {
        viewModelScope.launch { repository.setWatching(packageName, watching, reminderIntervalDays) }
    }

    fun saveNote(note: String) {
        viewModelScope.launch { repository.setUserNote(packageName, note.ifBlank { null }) }
    }

    fun markCheckedNow() {
        viewModelScope.launch { repository.markCheckedNow(packageName) }
    }
}
