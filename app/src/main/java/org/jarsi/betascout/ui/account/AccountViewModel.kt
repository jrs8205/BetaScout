package org.jarsi.betascout.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.SessionStanding
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.SignOutUseCase
import org.jarsi.betascout.work.BetaScanScheduler

/** Session-only state: scanning lives in [org.jarsi.betascout.ui.scan.ScanStatusViewModel]. */
data class AccountUiState(
    val signedIn: Boolean = false,
    val email: String = "",
    val showLogin: Boolean = false,
    /** A sign-in or sign-out is being persisted. */
    val busy: Boolean = false,
    val signOutFailed: Boolean = false,
    val error: String? = null,
)

/**
 * Whether a tap on the "sign in again" notification opens the sign-in page.
 * [standing] is where the stored session stands, null if that could not be
 * read. Only for a user the store says is signed out, expired or not: a read
 * that failed is not that. The session may be perfectly good, and a sign-in
 * page opened over it could store the WebView's cookies as a new sign-in and
 * start a scan. And never while a sign-out is still clearing those cookies
 * ([busy]): a page opened then could capture the old ones.
 */
internal fun opensSignInPage(standing: SessionStanding?, busy: Boolean): Boolean =
    standing != null && standing != SessionStanding.SIGNED_IN && !busy

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val workManager: WorkManager,
    private val signOutUseCase: SignOutUseCase,
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

    /** Entry point for the "sign in again" notification. The notification can
     *  outlive the problem it reports — the user may have signed in from the app
     *  since — so where the stored session stands is read first;
     *  [AccountUiState.signedIn] is not usable for this because it starts false
     *  until DataStore answers. Read without decrypting anything, and so that
     *  "could not be read" is not taken for "signed out" (see
     *  [opensSignInPage]). */
    fun startLoginIfSignedOut() {
        viewModelScope.launch {
            val standing = settings.readSessionStanding()
            if (opensSignInPage(standing, busy = _state.value.busy)) startLogin()
        }
    }

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
            BetaScanScheduler.scanNow(
                workManager,
                ExistingWorkPolicy.REPLACE,
                sessionGeneration = saved.getOrThrow(),
            )
            _state.update { it.copy(busy = false) }
        }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setUseDynamicColor(enabled) }
    }

    fun signOut() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, signOutFailed = false, error = null) }
        viewModelScope.launch {
            try {
                signOutUseCase.signOut()
                _state.value = AccountUiState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, signOutFailed = true) }
            }
        }
    }
}
