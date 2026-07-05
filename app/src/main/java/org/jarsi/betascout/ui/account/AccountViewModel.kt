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

data class AccountUiState(
    val email: String = "",
    val token: String = "",
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
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

    fun onEmailChange(value: String) = _state.update { it.copy(email = value) }
    fun onTokenChange(value: String) = _state.update { it.copy(token = value) }

    fun sync() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, syncedCount = null, error = null) }
            val typedToken = _state.value.token.trim()
            val email = _state.value.email.trim()
            val credential = if (typedToken.isNotBlank()) {
                settings.saveGplayCredential(email, typedToken)
                email to typedToken
            } else {
                settings.gplayCredential.first()
            }
            if (credential == null) {
                _state.update { it.copy(syncing = false, error = "no_credential") }
                return@launch
            }
            val result = repository.syncMembership(credential.first, credential.second)
            _state.update {
                it.copy(
                    syncing = false,
                    signedIn = it.signedIn || result.isSuccess,
                    token = "",
                    syncedCount = result.getOrNull(),
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "error" },
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
