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
    val checked: Int? = null,
    val joined: Int? = null,
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
        viewModelScope.launch {
            settings.playSession.collect { session ->
                if (session != null) {
                    _state.update { it.copy(email = session.first, signedIn = true) }
                }
            }
        }
    }

    fun startLogin() = _state.update {
        it.copy(showLogin = true, error = null, checked = null, joined = null, needsReLogin = false)
    }

    fun cancelLogin() = _state.update { it.copy(showLogin = false) }

    /** Called by the login WebView once it captures the account email and session cookies. */
    fun onLoginCaptured(email: String, cookieHeader: String) {
        viewModelScope.launch {
            _state.update { it.copy(showLogin = false, busy = true, error = null) }
            settings.savePlaySession(email, cookieHeader)
            scan(email, cookieHeader)
        }
    }

    fun resync() {
        viewModelScope.launch {
            val session = settings.playSession.first() ?: return@launch
            _state.update { it.copy(busy = true, checked = null, joined = null, error = null) }
            scan(session.first, session.second)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            settings.clearPlaySession()
            _state.value = AccountUiState()
        }
    }

    private suspend fun scan(email: String, cookieHeader: String) {
        android.util.Log.d("BetaScout", "scan: calling refreshBetaStatus (cookie ${cookieHeader.length} chars)")
        repository.refreshBetaStatus(
            PlaySession(cookieHeader),
            onProgress = { p -> _state.update { it.copy(progress = p) } },
        ).fold(
            onSuccess = { summary ->
                if (summary.needsLogin) {
                    settings.clearPlaySession()
                    _state.update {
                        it.copy(busy = false, progress = null, signedIn = false, needsReLogin = true)
                    }
                } else {
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = null,
                            signedIn = true,
                            email = email,
                            checked = summary.checked,
                            joined = summary.joined,
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
