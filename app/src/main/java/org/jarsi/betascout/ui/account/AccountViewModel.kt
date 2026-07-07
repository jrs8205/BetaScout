package org.jarsi.betascout.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.domain.ScanProgress

data class AccountUiState(
    val signedIn: Boolean = false,
    val email: String = "",
    val showLogin: Boolean = false,
    val busy: Boolean = false,
    val progress: ScanProgress? = null,
    val lastScan: LastScanInfo? = null,
    val needsReLogin: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repository: AppRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state = _state.asStateFlow()

    init {
        // The plaintext-session migration now runs at app startup (BetaScoutApp), so it
        // is not repeated here; this only reflects the stored session into the UI.
        viewModelScope.launch {
            settings.playSession.collect { session ->
                if (session != null) {
                    _state.update { it.copy(email = session.accountEmail, signedIn = true) }
                }
            }
        }
        viewModelScope.launch {
            settings.lastScan.collect { last ->
                _state.update { it.copy(lastScan = last) }
            }
        }
    }

    fun startLogin() = _state.update {
        it.copy(showLogin = true, error = null, needsReLogin = false)
    }

    fun cancelLogin() = _state.update { it.copy(showLogin = false) }

    /** Called by the login WebView once it captures the account email and session cookies. */
    fun onLoginCaptured(email: String, cookieHeader: String) {
        viewModelScope.launch {
            _state.update { it.copy(showLogin = false, busy = true, error = null) }
            val session = PlaySession(accountEmail = email, cookieHeader = cookieHeader)
            val saved = runCatching { settings.savePlaySession(email, cookieHeader) }
            if (saved.isFailure) {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        error = saved.exceptionOrNull()?.message ?: "session_save_failed",
                    )
                }
                return@launch
            }
            scan(session)
        }
    }

    fun resync() {
        viewModelScope.launch {
            val session = settings.playSession.first() ?: return@launch
            _state.update { it.copy(busy = true, error = null) }
            // A user-initiated rescan bypasses the freshness TTL: memberships can
            // change outside the app at any time, so "Scan now" always re-checks.
            scan(session, force = true)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            // Delete the account's observations before clearing the session so a
            // signed-out account's beta memberships do not linger on the device.
            settings.playSession.first()?.let { repository.clearObservations(it.accountKey) }
            settings.clearPlaySession()
            settings.clearLastScan()
            _state.value = AccountUiState()
        }
    }

    private suspend fun scan(session: PlaySession, force: Boolean = false) {
        android.util.Log.d("BetaScout", "scan: calling refreshBetaStatus (cookie ${session.cookieHeader.length} chars)")
        repository.refreshBetaStatus(
            session,
            force = force,
            onProgress = { p -> _state.update { it.copy(progress = p) } },
        ).fold(
            onSuccess = { summary ->
                if (summary.needsLogin) {
                    settings.clearPlaySession()
                    _state.update {
                        it.copy(busy = false, progress = null, signedIn = false, needsReLogin = true)
                    }
                } else {
                    settings.saveLastScan(
                        LastScanInfo(
                            at = System.currentTimeMillis(),
                            checked = summary.checked,
                            joined = summary.joined,
                            notJoined = summary.notJoined,
                            failed = summary.failed,
                        ),
                    )
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = null,
                            signedIn = true,
                            email = session.accountEmail,
                        )
                    }
                }
            },
            onFailure = { e ->
                _state.update { it.copy(busy = false, progress = null, error = e.message ?: "scan_failed") }
            },
        )
    }
}
