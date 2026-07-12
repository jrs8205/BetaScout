package org.jarsi.betascout.ui.account

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.ScanProgress
import org.jarsi.betascout.work.BetaScanScheduler
import org.jarsi.betascout.work.BetaScanWorker

data class AccountUiState(
    val signedIn: Boolean = false,
    val email: String = "",
    val showLogin: Boolean = false,
    val busy: Boolean = false,
    /** A cancelled run is still unwinding: controls stay disabled, no progress row. */
    val cancelling: Boolean = false,
    val progress: ScanProgress? = null,
    val lastScan: LastScanInfo? = null,
    val needsReLogin: Boolean = false,
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

    /** WorkManager reports an active scan job; the actual lock may outlive it. */
    private val workBusy = MutableStateFlow(false)

    init {
        // The plaintext-session migration now runs at app startup (BetaScoutApp), so it
        // is not repeated here; this only reflects the stored session into the UI.
        viewModelScope.launch {
            settings.playSession.collect { session ->
                _state.update {
                    it.copy(
                        signedIn = session != null,
                        email = session?.accountEmail ?: it.email,
                        // A fresh session settles any earlier expiry.
                        needsReLogin = if (session != null) false else it.needsReLogin,
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.lastScan.collect { last ->
                _state.update { it.copy(lastScan = last) }
            }
        }
        // The scan itself runs as WorkManager work (it outlives this screen and the
        // whole app process); the screen just mirrors that work's state.
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BetaScanScheduler.MANUAL_WORK_NAME)
                .collect { infos -> onScanWorkChanged(infos.firstOrNull()) }
        }
        // Busy must track the actual scan lock, not just WorkManager state: a
        // cancelled worker reports CANCELLED while its run is still unwinding and
        // holding the lock — a scan started in that window would be rejected as
        // "already running".
        viewModelScope.launch {
            combine(workBusy, repository.scanRunning) { work, lock -> work to lock }
                .collect { (work, lock) ->
                    _state.update { it.copy(busy = work || lock, cancelling = !work && lock) }
                }
        }
    }

    private fun onScanWorkChanged(info: WorkInfo?) {
        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                workBusy.value = true
                _state.update {
                    val total = info.progress.getInt(BetaScanWorker.KEY_PROGRESS_TOTAL, 0)
                    val label = info.progress.getString(BetaScanWorker.KEY_PROGRESS_LABEL)
                    it.copy(
                        error = null,
                        progress = if (total > 0 && label != null) {
                            ScanProgress(
                                index = info.progress.getInt(BetaScanWorker.KEY_PROGRESS_INDEX, 0),
                                total = total,
                                currentLabel = label,
                            )
                        } else {
                            null
                        },
                    )
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                workBusy.value = false
                _state.update {
                    it.copy(
                        progress = null,
                        // Guarded on signedIn so a stale pre-re-login result cannot
                        // resurface the prompt once a new session is in place.
                        needsReLogin = !it.signedIn &&
                            info.outputData.getBoolean(BetaScanWorker.KEY_NEEDS_LOGIN, false),
                    )
                }
            }

            WorkInfo.State.FAILED -> {
                workBusy.value = false
                _state.update {
                    it.copy(
                        progress = null,
                        error = info.outputData.getString(BetaScanWorker.KEY_ERROR) ?: "scan_failed",
                    )
                }
            }

            WorkInfo.State.CANCELLED, null -> {
                workBusy.value = false
                _state.update { it.copy(progress = null) }
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
            BetaScanScheduler.scanNow(workManager, ExistingWorkPolicy.REPLACE)
        }
    }

    /** Incremental scan: new apps and stale observations only. */
    fun resync() {
        _state.update { it.copy(error = null) }
        BetaScanScheduler.scanNow(workManager)
    }

    /** Full re-scan: ignores freshness TTLs and re-checks every app. */
    fun fullResync() {
        _state.update { it.copy(error = null) }
        BetaScanScheduler.scanNow(workManager, force = true)
    }

    fun cancelScan() {
        workManager.cancelUniqueWork(BetaScanScheduler.MANUAL_WORK_NAME)
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
