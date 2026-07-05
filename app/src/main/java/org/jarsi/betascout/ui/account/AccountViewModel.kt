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
import org.jarsi.betascout.data.gplay.GoogleAuth
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository

data class AccountUiState(
    val signedIn: Boolean = false,
    val email: String = "",
    val showLogin: Boolean = false,
    val busy: Boolean = false,
    val syncedCount: Int? = null,
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
            settings.gplayCredential.collect { credential ->
                if (credential != null) {
                    _state.update { it.copy(email = credential.first, signedIn = true) }
                }
            }
        }
    }

    fun startLogin() = _state.update { it.copy(showLogin = true, error = null, syncedCount = null) }
    fun cancelLogin() = _state.update { it.copy(showLogin = false) }

    /** Called by the login WebView once it captures the account email and oauth_token. */
    fun onLoginCaptured(email: String, oauthToken: String) {
        viewModelScope.launch {
            _state.update { it.copy(showLogin = false, busy = true, error = null) }
            val aasToken = GoogleAuth.exchangeOAuthToken(email, oauthToken).getOrElse { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "auth_failed") }
                return@launch
            }
            settings.saveGplayCredential(email, aasToken)
            val result = repository.syncMembership(email, aasToken)
            _state.update {
                it.copy(
                    busy = false,
                    signedIn = true,
                    email = email,
                    syncedCount = result.getOrNull(),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun resync() {
        viewModelScope.launch {
            val credential = settings.gplayCredential.first() ?: return@launch
            _state.update { it.copy(busy = true, syncedCount = null, error = null) }
            val result = repository.syncMembership(credential.first, credential.second)
            _state.update {
                it.copy(
                    busy = false,
                    syncedCount = result.getOrNull(),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            settings.clearGplayCredential()
            _state.value = AccountUiState()
        }
    }
}
