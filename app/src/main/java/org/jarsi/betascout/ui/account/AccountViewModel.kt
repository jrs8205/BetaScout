package org.jarsi.betascout.ui.account

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.work.BetaScanScheduler

/** Session-only state: scanning lives in [org.jarsi.betascout.ui.scan.ScanStatusViewModel]. */
data class AccountUiState(
    val signedIn: Boolean = false,
    val email: String = "",
    val showLogin: Boolean = false,
    /** True only while the captured session is being persisted. */
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repository: AppRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state = _state.asStateFlow()

    val useDynamicColor: StateFlow<Boolean> = settings.useDynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val shareDiscoveries: StateFlow<Boolean> = settings.shareDiscoveries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Starts true so the one-time prompt only appears once DataStore confirms
     *  it has never been answered — no flash on screen entry. */
    val sharePromptShown: StateFlow<Boolean> = settings.sharePromptShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setShareDiscoveries(enabled: Boolean) {
        viewModelScope.launch { settings.setShareDiscoveries(enabled) }
    }

    fun dismissSharePrompt() {
        viewModelScope.launch { settings.setSharePromptShown() }
    }

    init {
        // The plaintext-session migration runs at app startup (BetaScoutApp); this
        // only reflects the stored session into the UI.
        viewModelScope.launch {
            settings.playSession.collect { session ->
                _state.update {
                    it.copy(
                        signedIn = session != null,
                        email = session?.accountEmail ?: it.email,
                    )
                }
            }
        }
    }

    fun startLogin() = _state.update { it.copy(showLogin = true, error = null) }

    fun cancelLogin() = _state.update { it.copy(showLogin = false) }

    /** Called by the login WebView once it captures the account email and session cookies. */
    fun onLoginCaptured(email: String, cookieHeader: String) {
        viewModelScope.launch {
            _state.update { it.copy(showLogin = false, busy = true, error = null) }
            val saved = runCatching { settings.savePlaySession(email, cookieHeader) }
            if (saved.isFailure) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = saved.exceptionOrNull()?.message ?: "session_save_failed",
                    )
                }
                return@launch
            }
            // The scan card mirrors the enqueued work's state from here on.
            BetaScanScheduler.scanNow(workManager, ExistingWorkPolicy.REPLACE)
            _state.update { it.copy(busy = false) }
        }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setUseDynamicColor(enabled) }
    }

    fun signOut() {
        viewModelScope.launch {
            // Stop BOTH scans first: a worker still holding the old session would
            // otherwise keep fetching Google pages with its cookies and could write
            // freshly deleted observations back. await() confirms WorkManager has
            // processed the cancellations before anything is cleared.
            workManager.cancelUniqueWork(BetaScanScheduler.MANUAL_WORK_NAME).await()
            workManager.cancelUniqueWork(BetaScanScheduler.WORK_NAME).await()
            // Cancelling the unique periodic work removed its schedule too;
            // re-register it so background scans resume after the next sign-in
            // without an app restart (signed-out runs are no-ops).
            BetaScanScheduler.schedule(workManager)
            // Delete the account's observations before clearing the session so a
            // signed-out account's beta memberships do not linger on the device.
            settings.playSession.first()?.let { repository.clearObservations(it.accountKey) }
            settings.clearPlaySession()
            settings.clearLastScan()
            // The login WebView persists its Google cookies app-wide: without
            // clearing them the next "sign in" silently reuses the old session.
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            _state.value = AccountUiState()
        }
    }
}
