package org.jarsi.betascout.ui.appdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.UserBetaState

/** Why a single-app status re-check did not produce a fresh status. */
enum class CheckStatusError { SCAN_IN_PROGRESS, SCAN_BLOCKED, FAILED }

/** Maps a re-check failure to what the detail screen should say, or null when
 *  another surface (the sign-in prompt) already explains the situation. */
internal fun checkStatusErrorOf(error: Throwable): CheckStatusError? = when (error) {
    is DataError.NeedsLogin -> null
    is DataError.ScanInProgress -> CheckStatusError.SCAN_IN_PROGRESS
    is DataError.ScanBlocked -> CheckStatusError.SCAN_BLOCKED
    else -> CheckStatusError.FAILED
}

data class AppDetailUiState(
    val isLoading: Boolean = false,
    val overview: AppBetaOverview? = null,
    val signedIn: Boolean = false,
    /** A single-app status re-check is in flight (automatic or user-started). */
    val checkingStatus: Boolean = false,
    /** Set when the last re-check could not refresh the status; the screen must
     *  say so instead of silently keeping the stale membership on display. */
    val checkError: CheckStatusError? = null,
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AppRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val packageName: String = checkNotNull(savedStateHandle["packageName"])

    private val checking = MutableStateFlow(false)
    private val checkError = MutableStateFlow<CheckStatusError?>(null)

    /** Set when the user opens the beta page: they may join or leave there, so the
     *  status is re-checked once the screen resumes. */
    private var recheckOnResume = false

    val uiState: StateFlow<AppDetailUiState> = combine(
        repository.observeApps(),
        settings.playSession,
        checking,
        checkError,
    ) { rows, session, checkingNow, error ->
        AppDetailUiState(
            overview = rows.find { it.app.packageName == packageName },
            signedIn = session != null,
            checkingStatus = checkingNow,
            checkError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppDetailUiState(isLoading = true),
    )

    fun onBetaPageOpened() {
        recheckOnResume = true
    }

    fun onResumed() {
        if (!recheckOnResume) return
        recheckOnResume = false
        checkStatusNow()
    }

    /** Re-checks only this app, bypassing the scan TTL — the membership shown must
     *  reflect a join made moments ago, without waiting for the next full scan. */
    fun checkStatusNow() {
        if (checking.value) return
        viewModelScope.launch {
            val session = settings.playSession.first() ?: return@launch
            checking.value = true
            checkError.value = null
            try {
                repository.refreshSingleBetaStatus(session, packageName)
                    .onSuccess { checkError.value = null }
                    .onFailure { error ->
                        // Same handling as the scan worker: a dead session is cleared so
                        // every screen switches to prompting for a fresh sign-in. Every
                        // other failure is surfaced — the membership on screen may be
                        // stale and the user must not mistake it for a fresh result.
                        if (error is DataError.NeedsLogin) settings.clearPlaySession()
                        checkError.value = checkStatusErrorOf(error)
                    }
            } finally {
                checking.value = false
            }
        }
    }

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
